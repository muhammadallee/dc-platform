# Messaging

Publish and handle integration events without coupling application code to a broker: inject
`EventPublisher`, mark handler methods `@EventHandler`, and pick the transport (in-memory, Kafka,
or RabbitMQ) via a starter.

> **Kafka is experimental.** The Kafka transport has Testcontainers round-trip tests, but it is not
> yet part of the supported platform: no example uses it, it is absent from the smoke matrix, and
> platform infrastructure does not yet provide Kafka. **RabbitMQ is the supported broker** today;
> adopt `platform-starter-messaging-kafka` deliberately and expect it to firm up in a later train.

## What you get

- **`EventPublisher`** — `publish(destination, event)` / `publish(destination, payload)`.
  AT-LEAST-ONCE, blocks until the transport acknowledges the send.
- **`EventEnvelope<T>`** — immutable envelope; `eventType`/`eventVersion` are derived from
  `@EventType` on the payload class, or fall back to the simple class name / version 1.
- **`@EventHandler(destination, eventType)`** — marks a method as a handler; takes either the
  payload type or `EventEnvelope<T>` when headers/metadata are needed.
- **Structured headers** — every message carries `eventId`, `eventType`, `eventVersion`, `producer`, and
  `traceparent` when a trace is active. A credential-named header is refused. A handler runs in a span continuing
  the message's trace.
- **Bounded retry + DLQ** — a failed handler is retried up to `handler.retry.max-attempts`, with a capped exponential
  backoff, then dead-lettered: by the broker on RabbitMQ, by a republish to `destination + dlq.suffix` elsewhere. A payload
  that cannot be read, or a newer version than the handler knows, goes there on the first attempt.
- **Metrics** — `platform.messaging.published` (publish outcome) and `platform.messaging.handled`
  (handler outcome: success/retry/dlq), both via Micrometer when a `MeterRegistry` is present.
- **`/actuator/platform`** reports `messaging[ACTIVE <provider>]`, or `INACTIVE` when no single
  `EventTransport` bean is configured.

For a publish tied to a database change, use the [transactional outbox](outbox.md): `EventPublisher` sends immediately,
so a crash right after your commit loses the event.

Delivery is at-least-once: every message carries a stable `eventId` header, generated once when the envelope is built, and
consumers deduplicate by it. On RabbitMQ a send waits for the broker's publisher confirm; an unroutable message, a nack or
no confirm within `dc.platform.messaging.rabbit.confirm-timeout` (default `5s`) throws `EventPublishException`, so **a
destination needs a bound queue before you publish to it**.

## Starter coordinates

Pick exactly one (each brings its own `EventTransport`):

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-messaging-inmemory</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-messaging-kafka</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-messaging-rabbit</artifactId>
</dependency>
```

## Zero-config behavior

```java snippet:messaging-usage
@EventType("OrderPlaced")
record OrderPlaced(String orderId) {}

@Service
class OrdersService {
    private final EventPublisher publisher;

    OrdersService(EventPublisher publisher) {
        this.publisher = publisher;
    }

    void place(String orderId) {
        publisher.publish("dc.orders", new OrderPlaced(orderId));
    }
}

@Component
class OrdersEventHandlers {
    @EventHandler(destination = "dc.orders", eventType = "OrderPlaced")
    void onOrderPlaced(OrderPlaced event) {
        // ...
    }
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.messaging.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.messaging.default-destination-prefix` | `dc.` | Naming convention only; not enforced. |
| `dc.platform.messaging.handler.retry.max-attempts` | `3` | Total attempts before a handler failure is dead-lettered. |
| `dc.platform.messaging.handler.retry.backoff` | `1s` | Pause before the second attempt; doubles per attempt. |
| `dc.platform.messaging.handler.retry.max-backoff` | `30s` | The longest pause between attempts. |
| `dc.platform.messaging.dlq.suffix` | `.dlq` | The DLQ destination on transports without native dead-lettering. |
| `dc.platform.messaging.rabbit.quorum-queues` | `true` | Declare quorum queues, main and dead-letter; `false` for classic. An existing queue of the other type fails its declaration: RabbitMQ does not convert queues. |
| `dc.platform.messaging.rabbit.confirm-timeout` | `5s` | How long a send waits for the broker's confirm. |
| `dc.platform.messaging.rabbit.prefetch` | `10` | Messages a consumer holds at once, and so how many a retrying handler can delay. |
| `dc.platform.messaging.rabbit.shutdown-timeout` | `30s` | How long a stopping consumer waits for the handler in flight. Shutdown phases run in turn, so keep the pod's grace period above the web drain plus the outbox's `stop-timeout` plus this. |
| `dc.platform.messaging.rabbit.delivery-limit` | `20` | Returns a quorum queue allows before dead-lettering a message that keeps killing its consumer. A message merely held by a stopping consumer counts too, hence well above what rolling restarts reach. |

(Hand-written until the generated reference lands in phase 14.)

### Kafka: required `spring.kafka.*` serializer configuration

`KafkaEventTransport` reuses Boot's own `KafkaTemplate`/`ConsumerFactory` beans as-is — set the
byte-array (de)serializers yourself so the transport's `byte[]` contract matches:

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
```

### RabbitMQ: destination syntax

Destinations are `"exchange"` (empty routing key) or `"exchange:routingKey"`, e.g.
`"dc.orders:order.placed"`. Each subscription declares a durable topic exchange, a bound queue
`<exchange>.<routingKey>.<group>` and a dead-letter exchange + queue `….dlq`. Declarations run on every new connection, so a
service starts without the broker, and a broker that lost its definitions gets them back.

### RabbitMQ: how a delivery ends

The container acks after the handler returns, including any transaction the handler ran.

| Outcome | What happens |
|---|---|
| Handler returns | Acked |
| Handler keeps failing | `max-attempts` attempts in the consumer, then the broker dead-letters it. With the defaults, 3 s of backoff plus the handler's own time per poison message; other messages the consumer holds wait |
| Unreadable payload, or a version newer than `@EventType` declares | The DLQ at once, with a WARN or ERROR that never quotes the payload. A replay delivers a newer version after upgrading |
| Consumer stops | The handler in flight finishes, within `shutdown-timeout`; buffered messages go back; a retry backoff stops and puts its message back |
| Consumer dies mid-handler | The broker redelivers; `delivery-limit` dead-letters a message that keeps doing this |

Quorum queues dead-letter at-least-once (RabbitMQ 3.10 or later); classic queues do so at-most-once.

Handler time is yours to bound: a handler that blocks holds its consumer and the messages it has buffered. Give it timeouts, as the
data capability's transaction default does. Handlers belong on singletons created at startup: one on a lazy bean receives only
once that bean exists, and only the first instance of a non-singleton bean receives.

Handlers on one destination share a queue: each message goes to the handler declaring its `eventType`, else to one declaring none.
Two handlers that could receive the same message fail startup. A message no handler wants is acknowledged and counted as
`outcome=ignored`.

**Guarantees, and what they are not.** Delivery is at-least-once and unordered. **Deduplicate by `eventId`:** insert it in a
processed-events table with a unique constraint, in the same transaction as the business write, and skip on a violation.
`docs/samples/rabbitmq-messaging/` shows it. Use `OutboxPublisher` for any publish tied to a database change; a direct
`EventPublisher.publish` fails if the broker is down or the message is unroutable, and the caller must handle that.

**Schema rule.** Consumers tolerate unknown fields, so adding a field needs no version change. A change an old consumer cannot
read raises `@EventType(version = …)`.

### RabbitMQ: inspecting and replaying a DLQ

`tooling/scripts/rabbit-dlq-replay.py inspect <queue>` prints headers and leaves the messages there; `replay <queue>` moves what
is in the DLQ now back to the main queue, keeping event ids. It needs the `rabbitmq_shovel` and `rabbitmq_shovel_management`
plugins. Fix the cause first, or the messages come straight back. Do not delete the main queue during a replay: the shovel would
publish into nothing.

## Customize

Provide your own `EventSerializer` bean to replace the default Jackson JSON codec:

```java
@Bean
EventSerializer avroEventSerializer() {
    return new AvroEventSerializer();
}
```

## Replace / Disable

- Define your own `EventPublisher` bean to replace the default entirely.
- Define your own `EventTransport` bean to use a provider the platform doesn't ship yet.
- `dc.platform.messaging.enabled=false` switches the capability off wholesale.

## Testing

Use `platform-starter-messaging-inmemory` in tests regardless of your production provider:
`InMemoryEventTransport.awaitIdle(Duration)` gives sleep-free, deterministic publish/handle
assertions. `platform-messaging-test`'s `TestEventTransport` and `@AutoConfigureTestTransport` are
purpose-built for this.

## Local dev notes

The in-memory provider needs no Docker or network. Kafka/RabbitMQ round-trip tests are
`@Tag("docker")` (Testcontainers) and excluded from the default build; run them with
`mvn -Pdocker -pl messaging/platform-messaging-kafka,messaging/platform-messaging-rabbit -am verify`
against a running Docker daemon, or start `docker-compose.local.yml` for manual exploration.
