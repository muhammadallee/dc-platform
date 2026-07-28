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
- **Correlation propagation** — the current `RequestContext` correlation id is added as the
  `correlationId` header on every publish (toggle with `correlation.propagate`).
- **Bounded retry + DLQ** — failed handler deliveries retry
  (`handler.retry.max-attempts`/`handler.retry.backoff`) then republish to
  `destination + dlq.suffix` — this fallback is transport-agnostic and works identically over
  every provider; Kafka and RabbitMQ additionally get their own broker-native DLQ.
- **Metrics** — `platform.messaging.published` (publish outcome) and `platform.messaging.handled`
  (handler outcome: success/retry/dlq), both via Micrometer when a `MeterRegistry` is present.
- **`/actuator/platform`** reports `messaging[ACTIVE <provider>]`, or `INACTIVE` when no single
  `EventTransport` bean is configured.

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
| `dc.platform.messaging.handler.retry.max-attempts` | `3` | Total delivery attempts before a handler failure routes to the DLQ. |
| `dc.platform.messaging.handler.retry.backoff` | `1s` | Pause between handler retry attempts. |
| `dc.platform.messaging.dlq.suffix` | `.dlq` | Appended to a destination to form its DLQ destination. |
| `dc.platform.messaging.correlation.propagate` | `true` | Publish the current correlation id as the `correlationId` header. |
| `dc.platform.messaging.rabbit.quorum-queues` | `false` | Declare quorum queues instead of classic queues (rabbit provider only). |

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
`"dc.orders:order.placed"`. Each subscription declares a durable topic exchange, a bound queue,
and a dead-letter exchange + queue automatically.

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
