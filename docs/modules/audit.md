# Audit

An append-only audit trail: record who did what, to which resource, with what outcome. Auditing is
**asynchronous and fire-and-forget** — it never blocks or slows the method it observes — and the
destination is chosen automatically by a **degradation chain** so a service always has a working sink.

## What you get

- **`@Audited(action, resourceExpression)`** — audits a method declaratively. The platform records an
  event whether the method returns or throws (`Outcome.SUCCESS` / `FAILURE`), deriving the actor from
  the current user, the correlation id from the request context, and the resource from the SpEL
  expression (`#result`, method args by name / `#a0`).
- **`Auditor`** — `record(AuditEvent)` for programmatic auditing the annotation cannot express.
- **`AuditEvent`** — `(action, actor, resource, outcome, at, correlationId, details)`.
- **Sinks, chosen by a `messaging → jdbc → log` chain (highest available wins):**
  - **messaging** — publishes each event to `dc.audit` (requires the messaging capability plus
    `platform-audit-messaging-autoconfigure`).
  - **jdbc** — appends to a `platform_audit` table (details serialised to JSON). Requires a
    `DataSource`; ships its own Flyway migration.
  - **log (default)** — one structured line per event on the dedicated `AUDIT` logger; no
    infrastructure. The always-available fallback.

  A startup WARN names the sink actually selected.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-audit</artifactId>
</dependency>
```

The starter wires the log sink. Add a `DataSource` to upgrade to the JDBC sink; add the messaging
capability and `platform-audit-messaging-autoconfigure` to upgrade to the messaging sink.

## Usage

```java
@Audited(action = "order.create", resourceExpression = "#result.id")
public Order create(CreateOrderCommand command) { ... }
```

```java
auditor.record(new AuditEvent(
        "order.cancel", currentUser, "order:" + id, Outcome.SUCCESS,
        Instant.now(), null, Map.of("reason", reason)));
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.audit.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.audit.queue-capacity` | `1000` | Bound on the async worker's queue; events offered to a full queue are dropped with a WARN. |

The sink is selected by classpath/beans (messaging > jdbc > log), not by a property.

## Replace / Disable

- Define your own `Auditor` bean to replace the async auditor entirely (it backs off).
- Define your own `AuditSink` bean to plug a custom destination under the platform `Auditor` (it wins
  the chain via `@ConditionalOnMissingBean`).
- `dc.platform.audit.enabled=false` switches the capability off wholesale.

## Design notes

- **Fire-and-forget (decision D52).** A bounded queue feeds a single daemon worker; under sustained
  overload the platform sheds audit records rather than add latency to — or back-pressure — the
  request path. Raise `queue-capacity`, or replace the `Auditor` bean with a synchronous one, if you
  must not lose records.
- **Actor resolution.** When the security capability is present, the actor is the current user's
  subject; otherwise it is `anonymous`. The `@Audited` aspect never references security-api directly —
  it goes through an internal `ActorResolver` seam.
- **Messaging sink is its own module (decision D53).** Because a messaging sink references
  `EventPublisher`, and the constitution forbids an impl reaching another capability while the
  autoconfigure fan-out ceiling forbids folding it in, the messaging sink lives in
  `platform-audit-messaging-autoconfigure`.

## Local dev notes

Everything is tested with no Docker: the log sink via a log appender, the JDBC sink against H2, the
messaging sink against a recording publisher, and the `@Audited` aspect end-to-end through the
ContextRunner.
