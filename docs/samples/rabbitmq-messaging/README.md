# RabbitMQ messaging — sample

## What it solves

Services exchange events through RabbitMQ without touching a broker template. Failures end somewhere you can see: a handler that
keeps failing, or a message it cannot read, lands in a dead-letter queue you can inspect and replay, and one trace follows the event
across services.

## When to use / not use

- **Use `OutboxPublisher`** for any publish tied to a database change. See [the outbox sample](../outbox/README.md).
- **Use `EventPublisher` directly** only when losing the event is acceptable, or the caller can handle the failure: it throws if the
  broker is down or the message is unroutable.
- **Do not** expect exactly-once or ordering. **Consumers must deduplicate.**
- **Do not** register handlers at runtime. They belong on singletons created at startup.

## Minimal real setup

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-messaging-rabbit</artifactId>
</dependency>
```

```yaml
spring:
  application:
    name: billing          # names the consumer group, and the producer header
  rabbitmq:
    host: rabbitmq.internal
    username: ${RABBIT_USER}
    password: ${RABBIT_PASSWORD}
```

An event, and a handler that deduplicates:

```java
@EventType(value = "order.placed", version = 1)
public record OrderPlaced(String ref) { }

@Service
class Billing {

    private final JdbcTemplate jdbc;

    Billing(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventHandler(destination = "dc.orders:order.placed")
    @Transactional
    public void on(EventEnvelope<OrderPlaced> event) {
        try {
            jdbc.update("insert into processed_events (event_id) values (?)", event.eventId());
        } catch (DuplicateKeyException alreadyProcessed) {
            return;                // a redelivery: the first one committed
        }
        charge(event.payload());   // same transaction as the insert
    }
}
```

```sql
create table processed_events (event_id varchar(64) primary key);
```

The id and the business write commit together, so a crash between them cannot apply the effect without recording it. This pattern is
demonstrated by the hardening consumer (`Deduplicator` in `tooling/hardening/scenarios/phase-05/probe/RabbitProbe.java`), and is your
responsibility rather than a platform capability.

## Walkthrough

1. The subscription creates `dc.orders` (topic), the quorum queue `dc.orders.order.placed.billing`, `dc.orders.dlx`, and the DLQ
   `dc.orders.order.placed.billing.dlq`. This happens on each new connection, so the service starts without the broker.
2. A publish carries `eventId`, `eventType`, `eventVersion`, `producer` and `traceparent`, and waits for the broker's confirm.
3. The handler runs in a span continuing that trace. Returning acknowledges the message.
4. If it throws: up to 3 attempts, 1 s then 2 s apart, then the broker dead-letters it. An unreadable message, or a newer version
   than this service declares, goes there at once.

See [`verification.md`](verification.md), [`operations.md`](operations.md), [`troubleshooting.md`](troubleshooting.md),
[`commands.md`](commands.md), [`open-issues.md`](open-issues.md), and the [module reference](../../modules/messaging.md).
