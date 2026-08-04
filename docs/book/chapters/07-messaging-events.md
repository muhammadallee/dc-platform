# Chapter 7 — Messaging and Events

> **Capabilities covered:** `messaging`, `events`
>
> Integration events across a broker, in-process domain events, retry, and the dead-letter path.
>
> **Starters:** `platform-starter-messaging-{inmemory,rabbit,kafka}`, `platform-starter-events` ·
> **Reference:** [modules/messaging.md](../../modules/messaging.md) ·
> [modules/events.md](../../modules/events.md)

---

## 1. Introduction and Business Value

Two capabilities that are routinely conflated, and separating them is the first thing this chapter
does.

- **Messaging** carries **integration events** — across process boundaries, over a broker, to services
  you do not deploy with. `EventPublisher` and `@EventHandler`.
- **Events** carries **domain events** — inside one process, after the transaction commits, to code
  you deploy together. `DomainEventPublisher` and `@DomainEventHandler`.

They look similar and solve genuinely different problems. Using the wrong one gives you either a
broker hop for work that never leaves the JVM, or a side effect that fires for a transaction that
rolled back.

### The problem messaging solves

[Chapter 6](06-restclient-resilience.md) was a chapter about defending against synchronous coupling.
This one removes it.

If A must call B must call C on every request, availability multiplies downward — three services at
99.9% give you 99.7% — and latency adds up. Worse, a change to C's interface ripples back to A.

Publishing an event inverts the dependency. A announces that something happened; B and C decide
whether they care. A does not know they exist, does not wait for them, and does not fail when they do.

### The problem the *platform's* messaging solves

Any team can add `spring-kafka` and publish a message. What is hard is everything around it, and the
platform's contribution is almost entirely in that surrounding material:

| Hard part | Platform's answer |
|---|---|
| Broker APIs leak into domain code | `EventPublisher` — application code never sees `KafkaTemplate` or `RabbitTemplate` |
| Consumers bind to producer class names | `@EventType` gives events an identity independent of Java class names |
| A poison message blocks the queue forever | Bounded retry, then DLQ — **identically over every transport** |
| The dead-letter path is forgotten | RabbitMQ topology, including the DLX and DLQ, is declared automatically |
| Async work is untraceable | Correlation propagates as a message header |
| Nobody notices a growing DLQ | Metrics for publish and handle outcomes |
| Messaging tests need a broker, or sleep | A deterministic in-memory transport with `awaitIdle` |

That third row is the one worth dwelling on. **The retry-and-DLQ behaviour is transport-agnostic**, so
the delivery semantics your code depends on do not change when you swap in-memory for RabbitMQ for
Kafka. That is what makes local development against an in-memory transport an honest rehearsal rather
than a different system.

### The problem domain events solve

The classic bug this prevents:

```java
@Transactional
void placeOrder(Order order) {
    repository.save(order);
    emailService.sendConfirmation(order);   // sent
    inventoryService.reserve(order);        // throws -> transaction rolls back
}
```

The order does not exist. The customer has a confirmation email for it. That is user-visible data
corruption, and it is entirely ordinary-looking code.

`@DomainEventHandler` methods run **after commit**, never on rollback. The email cannot be sent for an
order that was not saved.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No messaging abstraction | Broker APIs in domain code; changing brokers is a rewrite |
| No event-type identity | A producer-side class rename silently breaks every consumer |
| No bounded retry and DLQ | One bad message stalls a partition forever, or is dropped with no record |
| No correlation on messages | Async processing is an untraceable gap between cause and effect |
| No messaging metrics | Silent message loss discovered by a business reconciliation |
| No after-commit dispatch | Side effects fire for transactions that rolled back |
| No deterministic test transport | Messaging tests need a broker, or use `Thread.sleep` and flake forever |

!!! warning "RabbitMQ is the supported broker. Kafka is experimental."
    The Kafka transport has Testcontainers round-trip tests, but no example uses it, it is absent from
    the smoke matrix, and platform infrastructure does not yet provide Kafka. **Adopt
    `platform-starter-messaging-kafka` deliberately**, knowing there is no infrastructure, worked
    example, or on-call knowledge behind it yet. The API is transport-agnostic, so adopting Kafka later
    is a starter swap rather than a code change — which is exactly the argument for not adopting it
    early.

---

## 2. Core Concepts and Underlying Principles

### 2.1 Integration events versus domain events

| | Domain event | Integration event |
|---|---|---|
| Scope | One process | Across services |
| Transport | Spring `ApplicationEventPublisher` | A broker |
| Timing | **After commit** | On publish |
| Consumer | Code you deploy together | A service you do not control |
| Coupling | Compile-time — the same class | The wire shape plus `eventType` |
| Failure | Handler exception, in-process | Retry, then DLQ |
| API | `DomainEventPublisher`, `@DomainEventHandler` | `EventPublisher`, `@EventHandler` |
| Capability | `events` | `messaging` |

The decision rule: **does the consumer deploy with you?** If yes, a domain event — a broker hop for
in-process work buys latency, an infrastructure dependency, and a serialization boundary, for nothing.
If no, an integration event.

The two compose. §4.6 shows the relay that turns a domain event into an integration event, so an
aggregate publishes once and both worlds see it.

### 2.2 At-least-once, and what "publish returned" means

`EventPublisher.publish` **blocks until the transport acknowledges the send**. A normal return means
the broker has accepted the event — not that any subscriber has processed it, and not that any
subscriber ever will.

The platform's stated semantic is **AT-LEAST-ONCE**, and every word of that matters:

- **At least once** — a message may be delivered more than once. Network partitions, consumer
  restarts, and redelivery after a failed acknowledgement all produce duplicates.
- **Not exactly-once** — no broker gives you that end-to-end without a coordinated transaction most
  teams cannot afford.
- **Not at-most-once** — the platform does not silently drop.

!!! warning "Every handler must be idempotent. This is not optional."
    Processing the same event twice must have the same effect as processing it once. If it credits an
    account, ships a parcel, or sends an email, a duplicate is a customer-visible defect. Use a natural
    key from the payload, or [`@Idempotent`](10-coordination.md). Assume duplicates will arrive,
    because they will.

### 2.3 Why `@EventType` exists

Without it, the obvious identity for an event is its Java class name. That is a trap.

```
   Producer                                   Consumer
   record OrderPlaced(...)          -->       record OrderPlaced(...)
        |
        | someone refactors: OrderPlacedEvent
        v
   record OrderPlacedEvent(...)     -->       record OrderPlaced(...)    NO MATCH
```

The rename is a local, sensible refactor. The consumer is a different repository, a different team,
and possibly a different release cycle — and nothing failed at compile time. The break appears in
production as messages that are silently ignored.

`@EventType("OrderPlaced")` decouples the wire identity from the class name. Rename the class freely;
the contract is the annotation value. The version field additionally lets you evolve schemas
deliberately:

```java
@EventType(value = "OrderPlaced", version = 2)
public record OrderPlaced(String orderId, String currency) { }
```

!!! success "Best practice — annotate every event payload, from the first one"
    Without the annotation the platform falls back to the simple class name, which works and quietly
    reintroduces the coupling. The annotation costs one line and is the difference between a rename
    being free and a rename being an incident.

### 2.4 The envelope

`EventEnvelope<T>` carries the payload plus transport-agnostic metadata:

| Component | Source | Notes |
|---|---|---|
| `eventType` | `@EventType.value()`, else the simple class name | The wire identity |
| `eventVersion` | `@EventType.version()`, else `1` | Schema version |
| `key` | You, via the builder | Partitioning/routing. `null` when the transport does not need one |
| `payload` | You | Never null |
| `headers` | Platform + you | Always includes `correlationId`, `eventType`, `eventVersion` |
| `occurredAt` | Platform | When the event occurred |

The `key` matters more than it looks on Kafka: messages with the same key land on the same partition
and are therefore **ordered relative to each other**. Using the aggregate id as the key is what gives
you per-order ordering without global ordering (which would cost you all parallelism).

### 2.5 The retry and DLQ path

This is the mechanism that keeps one bad message from stopping everything, and it is worth knowing
precisely:

```
   message arrives
        |
        v
   attempt 1 ---- success ----> count outcome=success, ack. done.
        |
      throws
        |
        v
   sleep backoff (default 1s), count outcome=retry
        |
        v
   attempt 2 ---- success ----> done
        |
      throws
        |
        v
   sleep backoff, count outcome=retry
        |
        v
   attempt 3 (max-attempts default 3)
        |
      throws
        |
        v
   count outcome=dlq
   log at ERROR naming the method, attempts, and destination
   republish the RAW message to  destination + ".dlq"
   give up on this delivery
```

Three properties of this design are deliberate:

- **The retries happen in the handler thread**, with a real sleep between them. Simple and predictable;
  it also means a retrying message occupies a consumer for `attempts × backoff` at minimum. §5.4.
- **The raw message is republished**, not a re-serialised one. The DLQ contains exactly what arrived,
  which is what you need for forensics and replay.
- **It is transport-agnostic.** In-memory, RabbitMQ, and Kafka behave identically. Rabbit and Kafka
  *additionally* get their own broker-native dead-lettering — the platform's fallback is in addition
  to, not instead of.

!!! warning "The DLQ is a queue, not a bin. Something must consume it."
    A DLQ nobody reads is a slow-motion data-loss incident: messages accumulate, retention expires, and
    the business discovers the gap at reconciliation. Decide before you go live who monitors DLQ depth
    and what the replay procedure is. §5.2.

### 2.6 After-commit dispatch, and why it is the whole point of domain events

```
  publisher.publish(new OrderPlaced(id))
        |
        +-- transaction active?
        |       |
        |       +-- YES: register a TransactionSynchronization
        |       |         handlers run AFTER COMMIT
        |       |         rollback -> handlers NEVER run
        |       |
        |       +-- NO:  dispatch immediately
        v
  handlers invoked on the publishing thread
```

The "no transaction" branch matters. It means domain events work in a service with no database at all,
dispatching immediately — the capability does not require [data](08-data.md).

!!! warning "After-commit handlers run outside the transaction"
    A handler that writes to the database opens a **new** transaction. If it fails, the original commit
    has already happened and cannot be undone. This is not a flaw — it is the semantics you asked for —
    but it means an after-commit handler's failure is your problem to handle, not the transaction
    manager's.

### 2.7 The relay, and the honest limitation

An opt-in bridge: a domain event annotated `@EventType` is re-published as an integration event on the
same after-commit callback.

```
  publish(OrderPlaced)  -->  commit  -->  @DomainEventHandler methods
                                     \
                                      -->  EventPublisher.publish("dc.OrderPlaced", ...)
```

The value is that an aggregate publishes **once**. Without it, code publishes twice by hand and the two
publications drift apart over time — someone adds a field to one and forgets the other.

The limitation is documented rather than glossed over: **this is an outbox precursor, not an outbox.**
The commit and the publish are not atomic. A crash between them loses the integration event, with no
record that it was ever meant to exist.

```
   BEGIN
   save(order)
   COMMIT               <-- durable
   *** crash ***
   publish(...)         <-- never happened, and nothing knows
```

A true transactional outbox writes the event to a table *inside* the same transaction and relays it
separately, so the event is as durable as the data. That is recorded as future work.

!!! warning "Do not build a financial or regulatory flow on the relay"
    If losing an event on a crash is unacceptable, the relay is not the mechanism. Write your own
    outbox table inside the transaction, or accept the gap deliberately and reconcile. The platform
    states this limitation plainly precisely so nobody assumes a guarantee that is not there.

---

## 3. Feature Reference

### 3.1 Messaging — public API

Package `ae.gov.dubaicustoms.platform.messaging`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `EventPublisher` | interface | `publish(destination, EventEnvelope)` and a payload overload |
| `EventEnvelope<T>` | record | The envelope, with a builder via `EventEnvelope.of(payload)` |
| `@EventType` | annotation | `String value()`, `int version() default 1`. Targets `TYPE` |
| `@EventHandler` | annotation | `String destination()`, `String eventType() default ""`. Targets `METHOD` |
| `EventPublishException` | exception | The transport rejected or failed to acknowledge |
| `EventSerializationException` | exception | Payload (de)serialisation failed |

!!! note "`@EventHandler(eventType = \"\")` means every type on that destination"
    The default. Useful for a generic auditor or an archiver; a mistake for a typed handler, which will
    then receive events it cannot deserialise.

### 3.2 Messaging — SPI

Package `ae.gov.dubaicustoms.platform.messaging.spi`. Both **EXPERIMENTAL**.

| Type | Purpose |
|---|---|
| `EventTransport` | The provider contract. `name()`, `send(...)`, `subscribe(...)`, `close()` |
| `EventSerializer` | `serialize`, `deserialize`, `contentType`. Default is Jackson JSON |

**`EventTransport` implementation requirements**, from the interface: thread-safe; `send` must not
return until the broker has accepted the message; a `Subscription` must keep redelivering an
unacknowledged message rather than dropping it; `close` must release connections and dispatcher threads
synchronously.

!!! note "The platform wraps exactly one `EventTransport` bean"
    Two transport beans is a configuration error, not a feature. Pick one transport starter.

### 3.3 Events — public API

Package `ae.gov.dubaicustoms.platform.events`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `DomainEvent` | marker interface | Implement it on your event records |
| `DomainEventPublisher` | interface | `publish(DomainEvent)` |
| `@DomainEventHandler` | annotation | Marks a handler. **Exactly one parameter**, the concrete event type |

Dispatch matches the parameter's **exact runtime type** — not assignability. A handler taking
`DomainEvent` does not receive `OrderPlaced`.

### 3.4 Test support

| Type | Module | Purpose |
|---|---|---|
| `InMemoryEventTransport` | messaging-inmemory | `awaitIdle(Duration)` for deterministic assertions |
| `TestEventTransport` | messaging-test | Records published events for assertion |
| `@AutoConfigureTestTransport` | messaging-test | Swaps in the test transport |
| `EventsAssert` | messaging-test | Fluent assertions over recorded events |

### 3.5 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

**Messaging**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.messaging.enabled` | Boolean | `true` | Kill switch | During an incident, to stop consuming |
| `dc.platform.messaging.handler.retry.max-attempts` | Integer | `3` | Total delivery attempts before the DLQ | Lower for fast-failing handlers; higher only if failures are genuinely transient |
| `dc.platform.messaging.handler.retry.backoff` | Duration | `1s` | Pause between attempts | Raise for a slow dependency — but see §5.4 |
| `dc.platform.messaging.dlq.suffix` | String | `.dlq` | Appended to form the DLQ destination | To match an existing broker naming convention |
| `dc.platform.messaging.correlation.propagate` | Boolean | `true` | Publish the correlation id as a header | Essentially never |
| `dc.platform.messaging.default-destination-prefix` | String | `dc.` | **A naming convention only — not enforced** | To match an existing topic taxonomy |
| `dc.platform.messaging.rabbit.quorum-queues` | Boolean | `false` | Declare quorum queues instead of classic | **In production clusters.** See §5.1 |

**Events**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.events.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.events.relay.enabled` | Boolean | **`false`** | Re-publish `@EventType` domain events as integration events | When you want the bridge — and have read §2.7 |
| `dc.platform.events.relay.destination-prefix` | String | `dc.` | Prefix for the derived destination | To match your topic taxonomy |

### 3.6 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformMessagingAutoConfiguration` | Messaging API on classpath, exactly one `EventTransport` bean, `messaging.enabled != false` | `eventPublisher`, `eventHandlerRegistrar`, `jacksonEventSerializer`, capability descriptor | You define an `EventPublisher` or `EventSerializer` bean |
| `PlatformEventsAutoConfiguration` | Events API on classpath, `events.enabled != false` | `domainEventPublisher`, handler registrar, dispatcher, and — if `relay.enabled` — the relay | You define a `DomainEventPublisher` or `AfterCommitDispatcher` bean |

`MessagingNoTransportFailureAnalyzer` turns "no `EventTransport` bean" into a *Description / Action*
block naming the transport starters, rather than a bean-resolution stack trace.

`/actuator/platform` reports `messaging[ACTIVE <provider>]`, or `INACTIVE` when no single transport is
configured.

### 3.7 Extension points

| Extension | How | Effect |
|---|---|---|
| A different serialisation format | `EventSerializer` bean | Replaces the Jackson default — Avro, Protobuf, a schema registry |
| A transport the platform does not ship | `EventTransport` bean | The façade binds to it. Certify against the messaging TCK |
| Replace publishing entirely | `EventPublisher` bean | Platform's backs off |
| Change domain-event dispatch timing | `AfterCommitDispatcher` bean | Platform-wide — async pools, ordering guarantees |

---

## 4. How-to Guide

### 4.1 Add the capabilities

Exactly one transport starter:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-messaging-inmemory</artifactId>   <!-- or -rabbit, or -kafka -->
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-events</artifactId>
</dependency>
```

Start the app; the banner should read `messaging[ACTIVE] (inmemory)`.

### 4.2 Publish and handle an integration event

```java snippet:book-07-messaging
@EventType(value = "OrderPlaced", version = 1)
record OrderPlaced(String orderId, String customerId) {
}

@Service
class OrderPublisher {

    private final EventPublisher publisher;

    OrderPublisher(EventPublisher publisher) {
        this.publisher = publisher;
    }

    void place(String orderId, String customerId) {
        publisher.publish("dc.orders", new OrderPlaced(orderId, customerId));
    }
}

@Component
class OrderEventHandlers {

    @EventHandler(destination = "dc.orders", eventType = "OrderPlaced")
    void onOrderPlaced(OrderPlaced event) {
        // Must be idempotent — this may be delivered more than once.
    }
}
```

The publisher blocks until the transport acknowledges. The correlation id from
[Chapter 1](01-core.md) rides along as a header, and the handler runs with that context established.

### 4.3 Use the envelope when you need metadata

```java snippet:book-07-envelope
@Service
class ShipmentPublisher {

    private final EventPublisher publisher;

    ShipmentPublisher(EventPublisher publisher) {
        this.publisher = publisher;
    }

    void dispatch(String shipmentId, String carrier) {
        EventEnvelope<ShipmentDispatched> event =
                EventEnvelope.of(new ShipmentDispatched(shipmentId, carrier))
                        .key(shipmentId)               // partition/routing key — ordering per shipment
                        .header("priority", "high")
                        .build();
        publisher.publish("dc.shipments", event);
    }
}

@EventType(value = "ShipmentDispatched", version = 1)
record ShipmentDispatched(String shipmentId, String carrier) {
}
```

To read headers on the consuming side, take the envelope instead of the payload:

```java
@EventHandler(destination = "dc.shipments", eventType = "ShipmentDispatched")
void onDispatched(EventEnvelope<ShipmentDispatched> envelope) {
    String priority = envelope.headers().get("priority");
    ShipmentDispatched payload = envelope.payload();
}
```

### 4.4 Publish a domain event

```java snippet:book-07-domain-events
record InvoiceIssued(String invoiceId, String customerId) implements DomainEvent {
}

@Service
class InvoiceService {

    private final DomainEventPublisher events;

    InvoiceService(DomainEventPublisher events) {
        this.events = events;
    }

    @Transactional
    void issue(String invoiceId, String customerId) {
        // ... persist the invoice ...
        events.publish(new InvoiceIssued(invoiceId, customerId));
        // Handlers run AFTER this transaction commits. A rollback means they never run.
    }
}

@Component
class InvoiceEventHandlers {

    @DomainEventHandler
    void onIssued(InvoiceIssued event) {
        // Runs outside the original transaction. A failure here cannot undo the commit.
    }
}
```

### 4.5 Choose between them

| You want to… | Use |
|---|---|
| Tell another service something happened | `EventPublisher` |
| Update a read model in the same service after commit | `DomainEventPublisher` |
| Send an email after a successful save | `DomainEventPublisher` |
| Trigger a workflow in another team's service | `EventPublisher` |
| Decouple two aggregates in the same service | `DomainEventPublisher` |
| Both, from one publish | Domain event + the relay (§4.6) |

### 4.6 Turn on the relay

```yaml
dc:
  platform:
    events:
      relay:
        enabled: true
        destination-prefix: "dc."
```

Now a domain event that also carries `@EventType` is re-published as an integration event after
commit:

```java
@EventType(value = "InvoiceIssued", version = 1)
record InvoiceIssued(String invoiceId, String customerId) implements DomainEvent { }
```

Destination is `destination-prefix + eventType` → `dc.InvoiceIssued`.

!!! warning "Read §2.7 before enabling this in a flow that must not lose events"
    The relay is an outbox *precursor*. A crash between commit and publish loses the integration event
    silently.

### 4.7 Replace the serialiser

```java snippet:book-07-event-serializer
@Configuration
class AvroSerialization {

    @Bean
    EventSerializer avroEventSerializer(AvroCodec codec) {
        return new EventSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                return codec.encode(payload);
            }

            @Override
            public <T> T deserialize(byte[] bytes, Class<T> type) {
                return codec.decode(bytes, type);
            }

            @Override
            public String contentType() {
                return "application/avro";
            }
        };
    }
}

interface AvroCodec {
    byte[] encode(Object payload);

    <T> T decode(byte[] bytes, Class<T> type);
}
```

!!! warning "Changing the serialiser is a wire-format change"
    Every consumer of every destination this service publishes to must be able to read the new format.
    Plan it as a coordinated migration — dual-publish, migrate consumers, then switch — not as a
    configuration change.

### 4.8 Test without a broker

```java
@PlatformWebTest
@AutoConfigureTestTransport
class OrderFlowTest {

    @Autowired TestEventTransport transport;

    @Test
    void placingAnOrderPublishesTheEvent() throws Exception {
        mockMvc.perform(post("/orders").with(jwt()).contentType(APPLICATION_JSON).content("{...}"))
               .andExpect(status().isCreated());

        transport.awaitIdle(Duration.ofSeconds(2));
        EventsAssert.assertThat(transport)
                .publishedTo("dc.orders")
                .withEventType("OrderPlaced");
    }
}
```

!!! success "Best practice — `awaitIdle`, never `Thread.sleep`"
    `awaitIdle` returns as soon as the transport has drained, so the test is both deterministic *and*
    fast. A sleep is either too short (flaky) or too long (slow), and usually manages both across a
    build. This applies whatever your production transport is — test against in-memory regardless.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | `platform-starter-messaging-inmemory` | No Docker, no network, deterministic |
| **Production (Rabbit)** | `rabbit.quorum-queues: true` | Classic queues do not survive a broker node failure without data loss. Quorum queues replicate |
| Production | A DLQ consumer or alert | §5.2 |
| Production | Retry tuned to the handler's actual failure mode | §5.4 |
| All | `@EventType` on every payload | Not configuration, but the most important convention here |

!!! warning "`quorum-queues` defaults to `false` and should be `true` in any real cluster"
    The default is off because quorum queues need a cluster to be worth anything, and the default has
    to work on a single local broker. In production, classic queues mean a broker node failure can lose
    unacknowledged messages. This is opt-in, and it is easy to forget.

### 5.2 What to monitor

| Signal | Meter or source | Alert when |
|---|---|---|
| **DLQ depth** | Broker metrics | **Any sustained non-zero value.** The single most important signal here |
| Handle outcome | `platform.messaging.handled{outcome=dlq}` | Non-zero — a handler is consistently failing |
| Retry rate | `platform.messaging.handled{outcome=retry}` | Climbing — a dependency is degrading |
| Publish failures | `platform.messaging.published{outcome}` | Any failure — the broker is unreachable or rejecting |
| Consumer lag | Broker metrics (Kafka lag, Rabbit queue depth) | Growing — consumers cannot keep up |
| Transport active | `/actuator/platform` | Reports `messaging[INACTIVE]` |

!!! tip "Alert on `outcome=dlq` before you alert on DLQ depth"
    The metric fires the moment a message is routed, on the *producing* side of the DLQ, and it names
    the service. Queue depth is a broker-side signal that tells you a queue is growing without telling
    you who filled it. Use both; the metric is faster and more actionable.

The platform's DLQ error log is deliberately rich — it names the handler method, the attempt count,
and the destination — so a `outcome=dlq` alert leads directly to a log line that identifies the code.

### 5.3 Troubleshooting

**Nothing is published, and startup failed.** The `FailureAnalyzer` should have told you: no
`EventTransport` bean. Add exactly one transport starter.

**`/actuator/platform` says `messaging[INACTIVE]`.** No single `EventTransport` — either none, or more
than one transport starter on the classpath.

**Events publish but the handler never runs.**

| Cause | Check |
|---|---|
| Destination mismatch | Publisher and `@EventHandler` must agree exactly. A typo is silent |
| `eventType` filter mismatch | The filter compares against `@EventType.value()`, not the class name |
| The bean is not a component | `@EventHandler` is discovered on Spring beans. A `new`-ed object has no handlers |
| Different consumer group | Instances in different groups each get a copy; the same group load-balances |
| Handler throwing on every message | Check the DLQ — it may be running and failing |

**A message went to the DLQ.** Working as designed. The ERROR log names the method, attempt count, and
destination. Fix the handler, then replay from the DLQ.

**Duplicate side effects.** At-least-once delivery is working as specified. Your handler is not
idempotent. §2.2.

**Domain-event handlers never run.** Either the transaction rolled back (correct), or the parameter
type does not *exactly* match the published type — dispatch is by exact runtime type, not
assignability.

**Domain-event handlers run when they should not.** No transaction was active at publish time, so
dispatch was immediate. Check that the publishing method is actually `@Transactional` and not
self-invoked past its proxy.

### 5.4 Scaling and performance

**Retry occupies a consumer.** The platform's retry sleeps in the handler thread. With the defaults —
3 attempts, 1 s backoff — a consistently failing message holds a consumer for ~2 seconds before
reaching the DLQ. Ten thousand poison messages is ~5.5 hours of consumer time on one thread.

!!! warning "Raising `backoff` and `max-attempts` multiplies that"
    `max-attempts: 5` with `backoff: 10s` means a poison message holds a consumer for 40 seconds. That
    is a throughput decision disguised as a resilience setting. If your handler's failures are *not*
    transient, fewer attempts is strictly better — the DLQ is where it is going anyway.

**Ordering costs parallelism.** On Kafka, per-key ordering is free but requires all messages for a key
to be on one partition — so a hot key becomes a throughput ceiling. Global ordering means one
partition, which means one consumer.

**Publishing is synchronous.** `publish` blocks until acknowledged, so it adds broker round-trip
latency to the request that calls it. Publishing inside a request path is normal; publishing fifty
events inside one request is not.

**In-memory is not a production transport.** It is bounded by heap and lost on restart, and it does not
cross process boundaries.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| **Event payloads are persisted by the broker** | Often for days. PII in a payload is PII in the broker, subject to its retention and access controls |
| DLQ messages persist longest | They are the ones nobody deletes. A poison message with sensitive data sits there indefinitely |
| Correlation ids propagate as headers | Fine. Do not add identity or authorisation data to headers — a broker is not an auth channel |
| Broker credentials | Standard `spring.rabbitmq.*` / `spring.kafka.*`, supplied as Vault-populated placeholders |
| Consumers cannot be authorised per-event | The platform has no per-event authorisation. Access control is the broker's, at the destination level |
| Deserialisation of untrusted payloads | The Jackson default binds to your declared type, which is safe. A custom serialiser that supports polymorphic typing may not be |

!!! success "Best practice — publish identifiers, not the whole entity"
    `OrderPlaced(orderId, customerId)` rather than the full order with addresses and payment details.
    Consumers that need more can fetch it, authenticated, over
    [REST](06-restclient-resilience.md). This bounds what is in the broker, bounds what a DLQ retains,
    and makes schema evolution far easier.

---

## 6. Deep Dive

### 6.1 Why the DLQ republish is transport-agnostic

Kafka and RabbitMQ both have native dead-lettering. The platform implements its own anyway, on top of
`EventTransport.send`, and layers it *in addition* to the broker's.

The reason is the same as [Chapter 3](03-logging-observability.md)'s argument about structured logs at
startup: **the guarantee must not change when the transport does.** If DLQ behaviour were delegated to
the broker, then:

- In-memory (local and test) would have no DLQ at all, so your tests would never exercise the path.
- Kafka's and Rabbit's semantics differ in ways your handler code would have to know about.
- Swapping transports would change your failure behaviour — the exact thing the abstraction exists to
  prevent.

Implementing it once at the platform layer means a test against the in-memory transport genuinely
rehearses production. The broker-native mechanism still runs underneath as defence in depth.

### 6.2 Why retry is synchronous in the handler thread

The alternative — a delayed-redelivery queue, or a scheduled retry — is more sophisticated and would
not block a consumer.

It is also substantially more machinery: a retry topic per destination, a scheduler, and message
metadata to track attempt counts across redeliveries. And it changes ordering, because a retried
message now arrives after messages published later.

The platform chose the simpler thing, and the cost is the throughput arithmetic in §5.4. Knowing the
trade lets you tune it: if your handler's failures are transient and rare, the defaults are fine; if
they are frequent, lower `max-attempts` so poison messages reach the DLQ quickly rather than occupying
consumers.

### 6.3 What the consumer group actually controls

`EventTransport.subscribe(destination, group, listener)`. The group name determines the fan-out shape:

```
   Same group        -> messages are LOAD-BALANCED across instances
                        (one instance handles each message)

   Different groups  -> each group gets a COPY
                        (every group handles every message)
```

Three instances of one service share a group, so each message is handled once — which is what you want
for "process this order". Two *different* services subscribing to the same destination use different
groups, so both see every message — which is what you want for "the shipping service and the billing
service both care that an order was placed".

Getting this wrong is a classic distributed bug in both directions: a shared group across two different
services means each service randomly misses two thirds of the messages; per-instance groups within one
service means every message is processed N times.

### 6.4 Exact-type dispatch for domain events

```java
@DomainEventHandler
void onAny(DomainEvent event) { ... }        // receives NOTHING
```

Dispatch matches the parameter's **exact runtime type**. A handler declaring the marker interface never
fires.

This is a deliberate trade of flexibility for predictability. Assignability-based dispatch would mean
adding a new event type could silently activate an existing broad handler — a spooky-action-at-a-
distance bug. Exact matching means a handler's set of triggers is visible in its signature and cannot
change without editing it.

For genuinely cross-cutting concerns — auditing every domain event, say — use Spring's own
`@EventListener`, which does support hierarchical matching, and accept that you have opted out of the
predictability.

### 6.5 The `default-destination-prefix` is not enforced

`dc.platform.messaging.default-destination-prefix` defaults to `dc.` and the property description says
plainly: *a naming convention only, not enforced*.

Nothing validates that `publish("orders", ...)` should have been `publish("dc.orders", ...)`. That is
consistent with the platform's general posture — conventions are documented and defaulted, not policed
— but it means destination naming is genuinely your discipline. A typo creates a new destination
rather than failing, and the message goes somewhere nobody is listening.

!!! success "Best practice — declare destination names as constants, in one place"
    ```java
    public final class Destinations {
        public static final String ORDERS = "dc.orders";
    }
    ```
    A string literal at a publish site and a string literal in an `@EventHandler` are two independent
    chances to typo the same name. The annotation requires a constant expression, which a `static
    final String` satisfies.

### 6.6 Why `EventSerializer` and `EventTransport` are EXPERIMENTAL

Both are marked `@API(status = EXPERIMENTAL)`, while the consumer-facing types are STABLE. That split
is the three-audience model from [the overview](../overview.md) doing its job: the platform is
confident about what *applications* use and reserves room to move on what *providers* implement.

Concretely, `EventTransport`'s own javadoc says new default methods may be added in minor versions. If
you write a transport, expect to revisit it. If you only publish and handle events, none of this
affects you.

### 6.7 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Non-idempotent handlers | At-least-once is easy to forget | Duplicates *will* arrive. Design for it |
| Omitting `@EventType` | The fallback works | A class rename silently breaks consumers |
| Domain event where an integration event belongs | Both are "events" | Does the consumer deploy with you? |
| Integration event for in-process work | It feels more decoupled | A broker hop and a serialisation boundary for nothing |
| Assuming the relay is an outbox | It looks like one | A crash between commit and publish loses the event |
| Publishing the whole entity | Convenient for consumers | PII in the broker, and painful schema evolution |
| No DLQ consumer | It works in testing | Slow-motion data loss |
| Raising retry attempts and backoff | It feels more resilient | Multiplies consumer occupancy per poison message |
| Sharing a consumer group across services | Both subscribe to the same destination | Each service misses most messages |
| `Thread.sleep` in tests | It is the obvious thing | `awaitIdle` is deterministic and faster |
| Classic queues in a production cluster | It is the default | A node failure can lose unacknowledged messages |
| Handler taking `DomainEvent` | It looks like a catch-all | Exact-type dispatch — it receives nothing |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-event-driven`](../../examples.md) — a producer and a consumer
communicating only through platform messaging, with a deliberate poison message
(`shipmentId = "poison"`) and a retry/DLQ test already in place.

### Lab 1 — Basic: publish, handle, and trace

**Goal.** See the full path, including the correlation id crossing the async boundary.

**Steps.**

1. Define an event record with `@EventType`. Publish it from a controller.
2. Write an `@EventHandler` that logs on receipt.
3. `curl` the endpoint. Find the correlation id on the response.
4. Find that **same** id on both the publish log line and the handler log line.
5. Remove `@EventType` and republish. What is the `eventType` header now?
6. Rename the record class. Does the handler still receive it — with and without the annotation?

**Expected outcome.** One correlation id joins the request and the async handling. Step 5 shows the
class-name fallback. **Step 6 is the lesson**: with the annotation the rename is free; without it the
handler stops matching.

**Hints.**

- Take `EventEnvelope<T>` instead of the payload to inspect headers directly.
- The handler runs on a different thread — the correlation id is there because the platform re-opens
  the context from the header, not because `ThreadLocal` propagated.

**How to verify.** A test asserting the published envelope's `eventType` is your annotation's value,
not the class name.

### Lab 2 — Intermediate: drive a message to the DLQ

**Goal.** Watch the retry sequence and understand its cost.

**Steps.**

1. Make a handler throw for one specific payload value.
2. Publish that payload. Count the log lines. How many attempts, and how far apart?
3. Find the DLQ destination and confirm the raw message arrived.
4. Check `platform.messaging.handled` — what are the `outcome` tag values and their counts?
5. Set `max-attempts: 5` and `backoff: 3s`. Repeat. Time how long one poison message occupies the
   consumer.
6. Compute how long 1,000 poison messages would take at those settings. Then argue for the right
   values.
7. Write a DLQ consumer that logs and counts. What would a real one do?

**Expected outcome.** Three attempts, 1 s apart, then a DLQ republish and an ERROR naming the method.
Step 5 gives ~12 seconds for one message. Step 6 should produce an uncomfortable number and the
observation from §5.4 — for non-transient failures, *fewer* attempts is better.

**Hints.**

- The in-memory transport makes the DLQ inspectable directly; no broker needed.
- Step 7 has no single answer — alert, park for manual review, or replay after a fix. Write down which
  your service needs.

**How to verify.** A test asserting exactly `max-attempts` invocations and that the message landed on
`destination + ".dlq"`.

### Lab 3 — Advanced: prove the transaction boundary, then break the relay

**Goal.** Establish after-commit semantics empirically, then reproduce the outbox gap.

**Steps.**

1. Write a `@Transactional` method that saves an entity, publishes a domain event, and returns.
   Confirm the handler runs after commit.
2. Make the method throw after publishing. Confirm the handler does **not** run and the row is absent.
3. Now publish from a method with **no** transaction. When does the handler run?
4. Make an after-commit handler throw. Is the original commit rolled back? Explain, then design how you
   would actually handle that failure.
5. Enable the relay. Confirm the domain event also arrives as an integration event, and note the
   destination.
6. **Reproduce the gap**: make the relay's publish fail (point the transport at a broken destination,
   or throw from a stubbed transport) *after* the commit. Is the row there? Is the integration event?
   Is there any record that it was meant to exist?
7. Sketch a real outbox: what table, written in which transaction, relayed by what?

**Expected outcome.** Steps 1–3 establish the semantics. Step 4 shows the commit stands — the failure
is yours to handle. **Step 6 is the point of the lab**: durable data, lost event, no trace. Step 7
should make clear why a true outbox writes the event inside the same transaction.

**Hints.**

- Use `TransactionSynchronizationManager.isActualTransactionActive()` inside the handler to prove which
  branch of §2.6 you are on.
- For step 6, a stub `EventTransport` whose `send` throws is the cleanest way to force it.

**How to verify.** Three tests: handler runs on commit, does not run on rollback, and runs immediately
with no transaction — plus a written note on what you would do about step 6 in a flow that cannot lose
events.

---

## 8. Checklist / Quick Reference

**Add them** — exactly one transport

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-messaging-inmemory</artifactId>   <!-- or -rabbit / -kafka -->
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-events</artifactId>
</dependency>
```

**Integration events**

```java
@EventType(value = "OrderPlaced", version = 1)
record OrderPlaced(String orderId) { }

publisher.publish("dc.orders", new OrderPlaced(id));
EventEnvelope.of(payload).key(id).header("k", "v").build();

@EventHandler(destination = "dc.orders", eventType = "OrderPlaced")
void on(OrderPlaced event) { }            // or EventEnvelope<OrderPlaced> for headers
```

**Domain events**

```java
record InvoiceIssued(String id) implements DomainEvent { }
events.publish(new InvoiceIssued(id));    // handlers run AFTER COMMIT

@DomainEventHandler
void on(InvoiceIssued event) { }          // exact runtime type — not assignability
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.messaging.handler.retry.max-attempts` | `3` |
| `dc.platform.messaging.handler.retry.backoff` | `1s` |
| `dc.platform.messaging.dlq.suffix` | `.dlq` |
| `dc.platform.messaging.correlation.propagate` | `true` |
| `dc.platform.messaging.rabbit.quorum-queues` | `false` — **set `true` in a production cluster** |
| `dc.platform.events.relay.enabled` | `false` — opt-in, and read §2.7 first |
| `dc.platform.events.relay.destination-prefix` | `dc.` |

**Which capability**

| Consumer deploys with you? | Use |
|---|---|
| Yes | `DomainEventPublisher` — after commit, in-process |
| No | `EventPublisher` — over a broker |
| Both | Domain event + relay (accepting the outbox gap) |

**Diagnose it**

```bash
curl -s localhost:8080/actuator/platform | jq            # messaging[ACTIVE] (provider)?
curl -s localhost:8080/actuator/metrics/platform.messaging.handled | jq '.availableTags'
curl -s localhost:8080/actuator/metrics/platform.messaging.published | jq
# then check broker DLQ depth
```

**Rules of thumb**

- Every handler must be idempotent. At-least-once is a promise, not a caveat.
- `@EventType` on every payload, from the first one.
- Declare destination names as constants — the prefix convention is not enforced.
- Domain event = same deployable. Integration event = different deployable.
- The relay is **not** an outbox. A crash between commit and publish loses the event.
- After-commit handlers cannot undo the commit. Their failures are yours to handle.
- Publish identifiers, not entities. The broker retains what you send.
- Something must consume the DLQ. Alert on `outcome=dlq`, not only on queue depth.
- More retries with longer backoff means longer consumer occupancy per poison message.
- Same consumer group = load balance. Different groups = fan out.
- `awaitIdle`, never `Thread.sleep`.
- `quorum-queues: true` in any production Rabbit cluster.

---

**Next:** [Chapter 8 — Data and Persistence](08-data.md), which is where the transaction this chapter
kept talking about actually commits.

**Reference:** [modules/messaging.md](../../modules/messaging.md) ·
[modules/events.md](../../modules/events.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
