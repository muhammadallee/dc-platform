# Phase 7 — Messaging & Events (P1, size L; Docker only for kafka/rabbit tagged tests)

**Order inside phase:** api → spi → INMEMORY impl → autoconfigure → events → kafka → rabbit → starters.
In-memory first makes everything locally testable and is the reference implementation for the TCK (phase 12).

## Modules
`messaging/platform-messaging-{api,spi,inmemory,kafka,rabbit,autoconfigure}`,
`platform-starter-messaging-{inmemory,kafka,rabbit}`, `platform-messaging-test`,
`events/platform-events-{api,autoconfigure}`, `platform-starter-events`.

## messaging-api (contracts; full javadoc incl. delivery-semantics section)
```java
package ae.gov.dubaicustoms.platform.messaging;

/** An integration event envelope. Immutable. Headers always include correlationId, eventType, eventVersion. */
public record EventEnvelope<T>(String eventType, int eventVersion, String key,
                               T payload, Map<String,String> headers, Instant occurredAt) {
    public static <T> Builder<T> of(T payload) { … }   // builder derives eventType from @EventType or class name
}

/** Declares the logical event type + schema version for a payload class. */
@Retention(RUNTIME) @Target(TYPE)
public @interface EventType { String value(); int version() default 1; }

/** Publish integration events to the configured transport. AT-LEAST-ONCE. Thread-safe.
 *  Blocking publish; returns after transport ack. Failures throw EventPublishException (code DC-MSG-0001). */
public interface EventPublisher {
    void publish(String destination, EventEnvelope<?> event);
    default void publish(String destination, Object payload) { … }
}

/** Marks a handler method: single param = payload (deserialized) or EventEnvelope<T>.
 *  Errors: thrown exceptions → transport retry/DLQ policy (see properties). */
@Retention(RUNTIME) @Target(METHOD)
public @interface EventHandler { String destination(); String eventType() default ""; }

public class EventPublishException extends PlatformException { … }
public class EventSerializationException extends PlatformException { … }   // DC-MSG-0002
```

## messaging-spi
```java
package ae.gov.dubaicustoms.platform.messaging.spi;

/** Provider contract. Implementations must be thread-safe and honor at-least-once. */
public interface EventTransport extends AutoCloseable {
    String name();                                                   // "inmemory","kafka","rabbit"
    void send(String destination, byte[] key, byte[] value, Map<String,String> headers);
    Subscription subscribe(String destination, String group, TransportListener listener);
    interface Subscription extends AutoCloseable {}
    @FunctionalInterface interface TransportListener {
        /** Return normally = ack. Throw = nack → provider retry policy. */
        void onMessage(byte[] key, byte[] value, Map<String,String> headers);
    }
}
/** Pluggable payload (de)serialization; default = Jackson JSON. */
public interface EventSerializer {
    byte[] serialize(Object payload);
    <T> T deserialize(byte[] bytes, Class<T> type);
    String contentType();
}
```

## messaging-inmemory (Implementation; the local + test transport)
Bounded in-JVM queues per destination, one dispatcher virtual-thread per subscription group,
sync-drain mode for tests (`InMemoryEventTransport.awaitIdle(Duration)`), simple redelivery
(configurable attempts then drop+log with error code). Heavily commented as reference implementation.

## messaging-autoconfigure
- `MessagingProperties` (`dc.platform.messaging`): `enabled`, `default-destination-prefix="dc."`,
  `handler.retry.max-attempts=3`, `handler.retry.backoff=1s`, `dlq.suffix=".dlq"`,
  `correlation.propagate=true`.
- Beans (template pattern): `EventPublisher` impl over any `EventTransport` bean (back-off) —
  adds correlation + type headers, Observation around publish, serializer via `EventSerializer`
  (`@ConditionalOnMissingBean` Jackson default);
  `EventHandlerRegistrar` (BeanPostProcessor scanning `@EventHandler`, subscribes with retry/DLQ wrapper,
  metrics `platform.messaging.handled` with outcome tag) — comment block documents the full lifecycle;
  `CapabilityDescriptor("messaging", ACTIVE, transport.name())`.
- Conditional on exactly-one `EventTransport`; if none → capability INACTIVE descriptor, no publisher bean
  (comment: fail-at-injection is clearer than fail-at-boot when messaging is optional).

## kafka / rabbit implementations (`@Tag("docker")` integration tests via Testcontainers)
- kafka: transport over `KafkaTemplate`/listener container factory built from Boot's own
  `spring.kafka.*` (do NOT duplicate broker config; comment this decision); destination=topic;
  DLQ via DeadLetterPublishingRecoverer honoring platform retry props; headers mapped verbatim.
- rabbit: destination=exchange+routingKey (`dest` or `dest:rk` syntax, documented), quorum-queue
  declarables opt-in property, DLQ via DLX.
- Each provider: unit tests docker-free (mocks for template wiring) + `@Tag("docker")` round-trip tests.

## events slice (domain events, in-process)
- events-api: `DomainEvent` marker interface; `DomainEventPublisher { void publish(DomainEvent e); }`;
  `@DomainEventHandler` (method annotation).
- events-autoconfigure: bridges to Spring `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`
  when transactions present (`@ConditionalOnClass`); optional relay: if messaging `EventPublisher` bean exists AND
  `dc.platform.events.relay.enabled=true`, domain events annotated `@EventType` are re-published as
  integration events after commit (this is the lightweight outbox precursor; a true outbox is a
  documented future enhancement — leave `docs/decisions/` note, not code).
- Tests: same-tx handler ordering, after-commit semantics with H2 + `@Transactional` tests, relay test with inmemory transport.

## messaging-test (Test Support)
`TestEventTransport` (records sent messages, manual `deliver(...)`), AssertJ assertions
(`assertThatEvents().sentTo("dc.orders").withType("OrderPlaced")`), `@AutoConfigureTestTransport`.

## Acceptance
```bash
mvn -T1C verify                              # green with NO docker
mvn -Pdocker -pl messaging/platform-messaging-kafka,messaging/platform-messaging-rabbit -am verify
# scratch app w/ starter-messaging-inmemory: publish→handler round trip; /actuator/platform shows messaging[ACTIVE inmemory]
```
DoD: full javadoc incl. delivery-semantics on EventPublisher/@EventHandler; docs/modules/messaging.md +
events.md with a sequence description; BOM; CHANGELOG.
