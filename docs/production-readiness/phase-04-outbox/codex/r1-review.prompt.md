# Phase 4 (Transactional Outbox) — review round 1

You are reviewing a transactional outbox added to an internal Spring Boot 4.1 platform (Java 25, HikariCP 7, Spring 7,
ojdbc11, production on Oracle 19c, certified on 23ai Free in 19 mode). Read-only checkout at `de67f30`. The diff below is
the whole phase from `39e354d`; read the touched files in full. No JVM or Docker in your sandbox: this is a static review.
The scope and its acceptance criteria follow the diff instruction; the implementer has **not** yet run a consumer
(Gate F), so consumer-level ACs are pending, but find what they would expose.

Be adversarial about these risks in particular:

1. **Event loss.** Any path where a business commit succeeds and its event is never delivered, or a row is marked `SENT`
   without the sink acknowledging it (Rabbit publisher confirms, the returned-message and nack paths, a channel closing
   mid-confirm, `CorrelationData`, `mandatory`).
2. **Atomicity.** `JdbcOutboxPublisher` under `JpaTransactionManager`: does the insert really join the transaction? Is
   `hasResource(dataSource)` right with wrapped data sources (`TransactionAwareDataSourceProxy`, `LazyConnectionDataSourceProxy`,
   a metrics or tracing wrapper), read-only transactions, `REQUIRES_NEW`, a transaction that is marked rollback-only?
3. **The Oracle claim.** `FOR UPDATE SKIP LOCKED` with `ORDER BY` and a stopped cursor fetch: are locks only taken on
   fetched rows, on 19c? Fetch-size effects: can the driver fetch and lock more than `batch-size`? What about the locks of
   rows fetched but not read? BLOB/CLOB reads inside that cursor? The `ROWNUM` bounded delete.
4. **The drainer.** Thread death, an `Error`, interrupts, a poison row (unreadable headers) blocking the table forever, a
   stuck `send`, the backoff and pause logic, a row whose backoff is never applied when a batch rolls back, statement
   timeouts, `stop()` racing a batch, restart after stop, housekeeping failures, long transactions, connection validity.
5. **The drainer's pool.** `HikariDataSource.copyStateTo` on a started pool: anything copied that should not be
   (`dataSource`, `metricsTrackerFactory`, `scheduledExecutor`, `threadFactory`, leak detection, sealed state, the pool's
   own `connectionTimeout`)? A non-Hikari wrapper that hides Hikari? Credentials rotated at runtime? Does it get the
   Oracle bounds in every order of bean creation?
6. **Bean wiring and back-off.** `@ConditionalOnBean(DataSource)` ordering, `@AutoConfiguration(afterName)`, multiple
   `DataSource` beans, `EventSerializer` absent when Jackson is absent, no `EventTransport` (does the existing failure analyzer
   apply?), more than one transport, `enabled=false` with no `EventPublisher` bean, the kill switches, whether any new bean
   changes behaviour for a service that does **not** use the outbox (the post-processor's `spring.rabbitmq.*` defaults, the
   Rabbit constructor refusing to start, `EventHandlerRegistrar`'s DLQ republish now blocking on confirms, a consumer-only
   service).
7. **Trace and principal.** Is the header injection right? Is the restored scope right on the drainer thread, including
   MDC cleanup? Does `traceparent` reach the wire, and what does a consumer see? Is `RequestContext.open`, marked
   `@PlatformInternal`, legitimate here?
8. **The relay change** and its transaction semantics: `canPublish` true but publish failing inside a domain-event callback.
9. **Redaction (§7.8).** What can reach a row or a log line: credential-header matching gaps, `last_error`, payload, the
   `destination` in logs.
10. **Metrics.** Gauge registration with multiple drainers or context refresh; a stale gauge while the DB is down; tags.

For each finding give: **severity** (Critical/High/Medium/Low per the plan: High = incorrect behaviour under a realistic
failure), `file:line`, a concrete failing scenario, and the fix you would make. Then go through AC1–AC18 and state for each:
met / partly / not met / pending Gate F, with a one-line reason. Do not list style issues. If something is right, do not
mention it unless it is an AC.

## Scope

# Phase 4 — Transactional Outbox: scope

| Item | Value |
|---|---|
| Phase start commit | `39e354d` (tag `hardening/phase-03-closed`) |
| Capability under test | New `platform-messaging-outbox`, `platform-messaging-outbox-autoconfigure` and `platform-starter-messaging-outbox`, the `OutboxPublisher` API in `platform-messaging-api`, `EventEnvelope.eventId` |
| Owner decisions | HD11: platform JDBC module (2026-10-31). HC7: the platform has no users, so breaking changes need only a CHANGELOG entry (2026-10-31). HD3, HD13: Oracle only, prod 19c, certified on 23ai Free |
| Certified dependencies | core, errors (Phase 1), logging, observability (Phase 2), data (Phase 3) |
| Fixtures (not certified here) | RabbitMQ transport (certified in Phase 5; only its publisher confirms are hardened here, R7), security, openapi, validation |
| Phase 0 gaps | G4-01, G4-02; brought forward from Phase 5: G5-01 (event id), G5-02 (publisher confirms). Carried: HI-0071 |

## 1. Requirements

### R1 Module layout (HD11)
1. The outbox belongs to the **messaging capability**: the drainer sends through `EventTransport`, a messaging SPI, and CLAUDE.md rule 5 forbids an autoconfigure module depending on another capability's SPI.
2. `platform-messaging-outbox` (Implementation): depends on messaging-api, messaging-spi, spring-jdbc, spring-tx, HikariCP. No dependency on JPA or data-jpa.
3. `platform-starter-messaging-outbox` (POM only): messaging-autoconfigure, outbox-autoconfigure (its own module: the enforcer's fan-out ceiling refused it in messaging-autoconfigure) + the outbox implementation. It names no transport; a service adds a transport starter as well. No transport bean → startup fails with a `FailureAnalyzer` message (a table nobody drains is silent data loss).
4. Listed in the platform BOM.

### R2 Write path (§7.4; plan "same transaction and DataSource")
1. New API in messaging-api: `OutboxPublisher.publish(String destination, EventEnvelope<?> event)`, separate from `EventPublisher` so a reader can see which publishes are guaranteed.
2. Inserts through the application's `DataSource`, inside the caller's transaction, and serializes at write time (a later class change cannot alter a stored event).
3. **Refuses, with an exception, when no transaction is active or the active transaction is not bound to that `DataSource`** (`TransactionSynchronizationManager.hasResource`). Without this check a write under a second transaction manager would auto-commit and silently lose atomicity.
4. Works under `JpaTransactionManager` and `DataSourceTransactionManager`.

### R3 Stored row and redaction (§7.6, §7.8)
1. Table `platform_outbox`, 19c-safe (no JSON column type, no `BOOLEAN`): `event_id` (PK), `event_type`, `event_version`, `destination`, `msg_key`, `payload` (BLOB), `content_type`, `headers` (CLOB, JSON text), `principal`, `created_at` (TSTZ), `next_attempt_at` (TSTZ), `attempts`, `rejections`, `status` (`PENDING` / `SENT` / `PARKED`), `sent_at` (TSTZ), `last_error`.
2. Headers stored: the application's envelope headers with credential-named keys **refused at write** (exception, not silently dropped), plus `traceparent`/`tracestate` of the current span. `principal` is the subject id only, never a token.
3. `last_error` holds the exception class name only (a transport message can carry hosts and credentials).
4. The payload is the application's data and is not masked; `outbox.md` says so.

### R4 Drainer (§7.4, §7.10)
1. `SmartLifecycle`; stops before the transport and pool close; a stop lets the current batch finish.
2. **Lock while sending (D116):** one transaction per batch: claim up to `batch-size` due rows with `FOR UPDATE SKIP LOCKED`, send each, update each, commit. A killed pod's locks are released by the database, so no lease or recovery job exists. Duplicates after a kill are bounded by one batch. The first transport failure ends the batch.
3. Backoff: exponential with a cap, per row (`next_attempt_at`); after `max-attempts` the row becomes `PARKED`, with a WARN and a metric. Parked rows are never deleted automatically and are replayed by a documented SQL statement.
4. **Own bounded connections:** a small Hikari pool (default 2) built from `spring.datasource.*`. It is **not** a `DataSource` bean and there is **no** `PlatformTransactionManager` bean: either would make Boot back off and take `@Transactional` away from the service's JPA writes (context test required). The drainer applies, itself: the Oracle read and connect bounds (Phase 3 post-processors do not see a non-bean pool), Hikari meters, and its own statement timeouts. It does not inherit the 30 s transaction default.
5. Order: oldest first within a batch, **no global or per-key ordering guarantee** (retries and `SKIP LOCKED` reorder); documented.
6. Delivery: **at-least-once**; consumers deduplicate by event id (documented).
7. Trace and principal are restored from the row while sending (log lines carry the original `traceId`; the sent message carries the stored `traceparent`).

### R5 Retention (plan: no unbounded bloat)
`SENT` rows are deleted in bounded batches after `sent-retention` (default 24 h). Cleanup runs on the drainer's connections and never holds a long transaction. Index on `(status, next_attempt_at)`.

### R6 Metrics and kill switch (§7.3, §7.9)
1. Gauges from a periodic query on the drainer's pool, never on scrape: `platform.outbox.pending`, `platform.outbox.parked`, `platform.outbox.oldest.pending.age` (seconds). Counters: `platform.outbox.sent`, `platform.outbox.failed`, `platform.outbox.parked.total`. Tags bounded: no destination-free IDs, no event ids.
2. Tier `DEFAULT_ON`: `dc.platform.messaging.outbox.enabled=false` logs a startup WARN stating publishes are no longer guaranteed. `OutboxPublisher` then publishes through `EventPublisher` after commit (D32's behaviour), so application code keeps working. The drainer does not run.
3. Capability report entry names the outbox and its state. Beans back off (`@ConditionalOnMissingBean`), properties are a record under `dc.platform.messaging.outbox`.

### R7 Sink acknowledgement (brought forward: G5-02)
`EventTransport.send` is documented as blocking until acknowledged; `RabbitEventTransport.send` is not, so a row could be marked `SENT` and lost. Phase 4 makes it true: publisher confirms and `mandatory`, a bounded wait, and an exception on nack, return (unroutable) or timeout. The rest of Rabbit stays in Phase 5.

### R8 Event id (brought forward: G5-01)
`EventEnvelope` gains a required `eventId` (UUID string), generated once at `build()`, overridable on the builder. Stored in the row and sent on every message as header `eventId`; retries never change it. `DefaultEventPublisher` sends it too. Breaking under HC7.

### R9 Domain event relay (G4-01, D32)
When the outbox is active and a transaction is bound to its `DataSource`, `DomainEventRelay` writes through `OutboxPublisher` **inside** the transaction rather than after commit. Otherwise behaviour stays as today. `events.md` and the book's "precursor" passages are updated.

### R10 Schema delivery
A versioned migration inside a platform jar would collide with the service's version sequence and can trip the Phase 3 out-of-order guard; runtime creation fails under a runtime user without DDL rights. So: the DDL ships as a **resource and documentation** that the service copies into its own migration sequence, with a grants line. No archetype feature (amendment A2). At startup a metadata check fails with a `FailureAnalyzer` message naming the file when the table is missing.

### R11 Consumer-visible changes
CHANGELOG `Breaking`: `EventEnvelope` constructor, Rabbit publisher confirms (`spring.rabbitmq.publisher-confirm-type`, `publisher-returns` defaulted), and a publish to an unroutable destination now fails. `Added`: the starter, API, properties.

## 2. Exclusions (and where they are handled)

| Item | Where |
|---|---|
| PostgreSQL verification (the plan names it) | Out under HD3: scope amendment A1 below |
| Rabbit topology, consumers, retry, DLQ, `traceparent` on `DefaultEventPublisher` (G5-03..07) | Phase 5. The outbox itself sends only `traceparent`; `DefaultEventPublisher`'s `correlationId` header stays until G5-07 |
| Audit routing | Out of scope (HD1) |
| Exactly-once, ordering | Not offered |
| CDC/log-tailing | Not offered |

## 3. Applicable §7 contracts

| § | Applies as |
|---|---|
| 7.1 override | `OutboxPublisher`, drainer and store beans back off by type or name |
| 7.2 snapshot | Properties default snapshot; config metadata |
| 7.3 kill switch | R6.2, tested |
| 7.4 outage posture | DB and sink each slow, erroring, down; no request failure from a sink outage; recovery |
| 7.5 health | No readiness impact from the sink or backlog; `dependencies` unchanged |
| 7.6 propagation | R3.2, R4.7 |
| 7.7 error surface | A failed outbox write inside a request follows the Phase 3 data mapping (rolled back with the business write) |
| 7.8 redaction | R3 |
| 7.9 observability | R6.1 |
| 7.10 graceful shutdown | R4.1 |
| 7.11 startup | Oracle down, missing table, no transport: clear bounded failure |

## 4. Acceptance criteria

| AC | Criterion | Level |
|---|---|---|
| AC1 | Business write + outbox row commit atomically; business rollback leaves no row; a business constraint failure at commit leaves no row | CONTAINER + CONSUMER |
| AC2 | No transaction, or a transaction on another `DataSource`: publish refuses | SLICE |
| AC3 | Under `JpaTransactionManager` a service with the outbox still has Boot's pool and transaction manager; the drainer adds no `DataSource` or `PlatformTransactionManager` bean | SLICE |
| AC4 | Two claimers on a table holding more than two batches each get a **full** batch of different rows, on Oracle | CONTAINER |
| AC5 | Sink down: rows accumulate, lag metric rises, requests unaffected; drain on recovery; event count and ids complete | CONSUMER |
| AC6 | Sink slow and sink nacking/unroutable: bounded, no request impact, nothing marked sent that was not acknowledged | CONTAINER + CONSUMER |
| AC7 | Pod killed (`SIGKILL`) mid-drain: no event lost, duplicates ≤ one batch per kill, each dedupable by id | CONSUMER |
| AC8 | Two app instances: no event sent twice absent a crash, none lost | CONSUMER |
| AC9 | Backoff and parking: after `max-attempts` a row is `PARKED`, metric and WARN; replay statement resends it with the same id | CONTAINER + CONSUMER |
| AC10 | Drainer exhausts its own pool, or the DB is slow: request threads and the main pool unaffected | CONSUMER |
| AC11 | Retention deletes `SENT` rows in bounded batches and never `PENDING` or `PARKED` | CONTAINER |
| AC12 | Row and message carry the event id, type, version, `traceparent`, principal; drainer log lines on the original trace; credential-named header refused; no token in the row | SLICE + CONSUMER |
| AC13 | `enabled=false`: startup WARN, no drainer, publish after commit; no transport: clear failure; missing table: clear failure naming the file | SLICE + CONSUMER |
| AC14 | SIGTERM mid-batch: batch finishes, exit 143, no duplicate | CONSUMER |
| AC15 | Runtime user with grants only runs the outbox; migration 19c-safe; Oracle driver bounds on the drainer pool | CONTAINER + CONSUMER |
| AC16 | Relay writes in the transaction with the outbox on; crash after commit loses nothing | SLICE + CONSUMER |
| AC17 | Rabbit send confirms: nack, unroutable, timeout each throw | CONTAINER |
| AC18 | Phases 1–3 consumers still pass; downstream compile; golden path with the new scenario; CI green | CONSUMER |

## 5. Surface inventory

`EventEnvelope` callers: messaging-api (7 files), messaging-autoconfigure (4), events-autoconfigure (2), audit-messaging-autoconfigure (1). `RabbitEventTransport.send` callers: `DefaultEventPublisher`, DLQ republish in `EventHandlerRegistrar`.

## 6. Environment prerequisites

JDK 25, Maven 3.9, Docker. Oracle `gvenzl/oracle-free:23-slim-faststart`, RabbitMQ `rabbitmq:3.13-management-alpine` (present locally), Toxiproxy 2.9.0. Gate F runs Oracle (~2 GB), RabbitMQ, Toxiproxy and **two** app containers, with `-Xmx` set on each; `nice_galileo` stopped during consumer runs. Host has 15.9 GB.

## 7. Reproducing current behaviour (baseline `39e354d`)

- No outbox: no table, no drainer (G4-01, G4-02).
- `DomainEventRelay` publishes on an after-commit callback; a crash in between loses the event (D32).
- `EventEnvelope` has no id (G5-01).
- `RabbitEventTransport.send` calls `rabbitTemplate.send` with no confirm and no `mandatory` (G5-02), contradicting the SPI's javadoc.

## 8. Baseline targeted tests (Gate A, before any change)

`mvn -o -T1 -B -pl messaging/platform-messaging-api,messaging/platform-messaging-spi,messaging/platform-messaging-inmemory,messaging/platform-messaging-rabbit,messaging/platform-messaging-autoconfigure,events/platform-events-autoconfigure -am verify` → exit 0, BUILD SUCCESS; 0 failures, 0 errors, 0 skipped (raw log `evidence-raw/gate-a-baseline-tests.log`, sha256 `d993d553…`, not committed).

## 9. Decisions to record (decision log, at Gate B)

| ID | Decision |
|---|---|
| D115 | JDBC outbox module inside the messaging capability; separate `OutboxPublisher` API (HD11) |
| D116 | Lock while sending rather than claim with a lease: no recovery job, duplicates bounded by one batch |
| D117 | Non-bean drainer pool and manual transaction handling |
| D118 | Schema as a copied migration, not a shipped or runtime-created one |
| D119 | Rabbit publisher confirms brought forward; event id required on the envelope |

## 10. Scope amendments

| ID | Date | Amendment | Reason |
|---|---|---|---|
| A1 | 2026-10-31 | The plan's "PostgreSQL and Oracle verified" is Oracle only. | HD3 amended: PostgreSQL is out for now. H2 is used for local runs and unit tests, as evidence for neither. |
| A2 | 2026-10-31 | R10: no archetype `outbox` feature. A service adds the starter and copies the DDL. | The archetype can only render an unwanted file empty, and an empty `V2__…` would collide with the service's next migration. The startup failure names the file. |

## 11. HI-0071 (carried from Phase 3)

Closure said Phase 4's outbox work would change the transaction layer. **With this design it does not:** the write path joins the caller's transaction and the drainer uses plain JDBC. HI-0071 stays open as its own item.

**Gate B spike (Spring 7, real `AbstractPlatformTransactionManager`):**

| Failure | Listener calls | Failure object passed? |
|---|---|---|
| Runtime exception from `doCommit` (the lost-connection case) | `beforeCommit`, then `afterRollback` | **No** (`null`) |
| `TransactionException` from `doCommit` | `beforeCommit`, then `afterCommit` | Yes |
| Failure in a `beforeCommit` synchronization | `afterRollback` only (no `beforeCommit`) | No |

So a listener cannot mark the failing exception in the case that matters. A thread-local "commit attempted" flag would fix the one over-veto (a `beforeCommit` callback's 503), at the cost of lifecycle state, but not the misses Codex named. Not clearly better, so the choice goes back to the owner at closure.

## Diff (main code; tests and docs are in the checkout)

```diff
diff --git a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/EventsProperties.java b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/EventsProperties.java
index e150f2d..e27b9e5 100644
--- a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/EventsProperties.java
+++ b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/EventsProperties.java
@@ -32,11 +32,10 @@ public record EventsProperties(
      * as integration events after commit, via the messaging capability's {@code EventPublisher}
      * (only active when that bean actually exists).
      *
-     * <p>This is a lightweight outbox precursor, not a true transactional outbox: the relay
-     * publishes on the same after-commit callback as {@code @DomainEventHandler}, so a crash
-     * between commit and publish still loses the integration event. A true outbox (durable
-     * staging table + separate publisher process) is a documented future enhancement — see
-     * {@code docs/decisions/decision-log.md}.
+     * <p>With {@code platform-starter-messaging-outbox} and a transaction on its database, the relay writes through
+     * the outbox inside that transaction. Otherwise it publishes on the same after-commit callback as
+     * {@code @DomainEventHandler}, so a crash between commit and publish loses the integration event; see
+     * {@code docs/decisions/decision-log.md} (D32).
      *
      * @param enabled opt-in switch; the relay is inactive by default even when an
      *     {@code EventPublisher} bean exists
diff --git a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/PlatformEventsAutoConfiguration.java b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/PlatformEventsAutoConfiguration.java
index f685a2d..2d00e09 100644
--- a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/PlatformEventsAutoConfiguration.java
+++ b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/PlatformEventsAutoConfiguration.java
@@ -10,6 +10,8 @@ import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DomainEventRel
 import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.ImmediateDispatcher;
 import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.TransactionalDispatcher;
 import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
+import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
+import org.springframework.beans.factory.ObjectProvider;
 import org.springframework.boot.autoconfigure.AutoConfiguration;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
@@ -97,8 +99,10 @@ public class PlatformEventsAutoConfiguration {
         @ConditionalOnBean(EventPublisher.class)
         @ConditionalOnMissingBean
         DomainEventRelay domainEventRelay(
-                EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, EventsProperties properties) {
-            return new DomainEventRelay(eventPublisher, dispatcher, properties.relay().destinationPrefix());
+                EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, EventsProperties properties,
+                ObjectProvider<OutboxPublisher> outbox) {
+            return new DomainEventRelay(eventPublisher, dispatcher, properties.relay().destinationPrefix(),
+                    outbox.getIfAvailable());
         }
     }
 }
diff --git a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/DomainEventRelay.java b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/DomainEventRelay.java
index 6d10265..99f0c95 100644
--- a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/DomainEventRelay.java
+++ b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/DomainEventRelay.java
@@ -4,25 +4,40 @@ import ae.gov.dubaicustoms.platform.events.DomainEvent;
 import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
 import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
 import ae.gov.dubaicustoms.platform.messaging.EventType;
+import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
 import java.util.Objects;
 import org.springframework.context.ApplicationListener;
 import org.springframework.context.PayloadApplicationEvent;
 
 /**
- * Re-publishes {@code @EventType}-annotated {@link DomainEvent}s as integration events via the
- * messaging capability's {@link EventPublisher}, after the enclosing transaction commits (or
- * immediately when none is active).
- *
- * <p>This is a lightweight outbox precursor, not a true transactional outbox — see
- * {@code EventsProperties.Relay}'s javadoc and {@code docs/decisions/decision-log.md}.
+ * Re-publishes {@code @EventType}-annotated {@link DomainEvent}s as integration events. With the transactional outbox
+ * on and a transaction on its database, the event is written through {@link OutboxPublisher} inside that transaction, so
+ * it commits with the business write. Otherwise it goes through {@link EventPublisher} after the enclosing transaction
+ * commits (or immediately when none is active), and a crash between the commit and the send loses it — see
+ * {@code EventsProperties.Relay}'s javadoc and {@code docs/decisions/decision-log.md} (D32).
  */
 public final class DomainEventRelay implements ApplicationListener<PayloadApplicationEvent<?>> {
 
     private final EventPublisher eventPublisher;
     private final AfterCommitDispatcher dispatcher;
     private final String destinationPrefix;
+    private final OutboxPublisher outbox;
 
     public DomainEventRelay(EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, String destinationPrefix) {
+        this(eventPublisher, dispatcher, destinationPrefix, null);
+    }
+
+    /**
+     * Creates the relay.
+     *
+     * @param eventPublisher publishes when the outbox cannot; never {@code null}
+     * @param dispatcher runs the fallback publish after commit; never {@code null}
+     * @param destinationPrefix prepended to the event type; never {@code null}
+     * @param outbox the transactional outbox, or {@code null} without one
+     */
+    public DomainEventRelay(EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, String destinationPrefix,
+            OutboxPublisher outbox) {
+        this.outbox = outbox;
         this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
         this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
         this.destinationPrefix = Objects.requireNonNull(destinationPrefix, "destinationPrefix must not be null");
@@ -39,6 +54,11 @@ public final class DomainEventRelay implements ApplicationListener<PayloadApplic
             return;
         }
         String destination = destinationPrefix + annotation.value();
-        dispatcher.dispatch(() -> eventPublisher.publish(destination, EventEnvelope.of(payload).build()));
+        EventEnvelope<Object> envelope = EventEnvelope.of(payload).build();
+        if (outbox != null && outbox.canPublish()) {
+            outbox.publish(destination, envelope);
+            return;
+        }
+        dispatcher.dispatch(() -> eventPublisher.publish(destination, envelope));
     }
 }
diff --git a/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventEnvelope.java b/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventEnvelope.java
index 76b32a9..54a0464 100644
--- a/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventEnvelope.java
+++ b/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventEnvelope.java
@@ -4,15 +4,19 @@ import java.time.Instant;
 import java.util.LinkedHashMap;
 import java.util.Map;
 import java.util.Objects;
+import java.util.UUID;
 import org.apiguardian.api.API;
 
 /**
  * An integration event envelope carrying a payload plus transport-agnostic metadata. Immutable.
  *
- * <p>Headers always include {@code correlationId}, {@code eventType}, and {@code eventVersion};
- * {@code platform-messaging-autoconfigure}'s {@code EventPublisher} implementation adds the first
- * from the current request context and the latter two from this envelope before handing off to
- * the transport. Additional headers are transport- or application-specific.
+ * <p>Headers always include {@code eventId}, {@code correlationId}, {@code eventType}, and
+ * {@code eventVersion}; {@code platform-messaging-autoconfigure}'s {@code EventPublisher} implementation
+ * adds {@code correlationId} from the current request context and the others from this envelope before
+ * handing off to the transport. Additional headers are transport- or application-specific.
+ *
+ * <p>Delivery is at-least-once: a consumer can see the same event more than once and deduplicates by
+ * {@link #eventId()}, which is generated once, when the envelope is built, and never changes on a retry.
  *
  * <pre>{@code
  * EventEnvelope<OrderPlaced> event = EventEnvelope.of(new OrderPlaced(orderId))
@@ -23,6 +27,7 @@ import org.apiguardian.api.API;
  *
  * <p>Thread-safe (immutable); {@code headers} is an unmodifiable snapshot.
  *
+ * @param eventId the stable, unique id of this event, a UUID unless set explicitly; never {@code null}
  * @param eventType the logical event type, derived from {@link EventType#value()} or the
  *     payload's simple class name if absent; never {@code null}
  * @param eventVersion the schema version, from {@link EventType#version()} or {@code 1}
@@ -33,16 +38,20 @@ import org.apiguardian.api.API;
  * @since 0.2.0
  */
 @API(status = API.Status.STABLE, since = "0.1.0")
-public record EventEnvelope<T>(String eventType, int eventVersion, String key, T payload,
+public record EventEnvelope<T>(String eventId, String eventType, int eventVersion, String key, T payload,
                                 Map<String, String> headers, Instant occurredAt) {
 
+    /** The header carrying {@link #eventId()} on every message. */
+    public static final String EVENT_ID_HEADER = "eventId";
+
     /**
      * Validates required components and defensively copies {@code headers}.
      *
-     * @throws NullPointerException if {@code eventType}, {@code payload}, {@code headers}, or
-     *     {@code occurredAt} is null
+     * @throws NullPointerException if {@code eventId}, {@code eventType}, {@code payload},
+     *     {@code headers}, or {@code occurredAt} is null
      */
     public EventEnvelope {
+        Objects.requireNonNull(eventId, "eventId must not be null");
         Objects.requireNonNull(eventType, "eventType must not be null");
         Objects.requireNonNull(payload, "payload must not be null");
         Objects.requireNonNull(headers, "headers must not be null");
@@ -72,6 +81,7 @@ public record EventEnvelope<T>(String eventType, int eventVersion, String key, T
 
         private final T payload;
         private final Map<String, String> headers = new LinkedHashMap<>();
+        private String eventId;
         private String key;
         private Instant occurredAt;
 
@@ -79,6 +89,18 @@ public record EventEnvelope<T>(String eventType, int eventVersion, String key, T
             this.payload = Objects.requireNonNull(payload, "payload must not be null");
         }
 
+        /**
+         * Sets the event id explicitly, for a republish of an event that already has one; defaults to a
+         * new random UUID at {@link #build()} time.
+         *
+         * @param eventId the event id; never {@code null}
+         * @return this builder
+         */
+        public Builder<T> eventId(String eventId) {
+            this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
+            return this;
+        }
+
         /**
          * Sets the partitioning/routing key.
          *
@@ -126,7 +148,8 @@ public record EventEnvelope<T>(String eventType, int eventVersion, String key, T
             // No Clock injected here: this is a value-object builder, not platform logic: callers
             // needing deterministic timestamps call occurredAt(clock.instant()) explicitly.
             Instant instant = occurredAt != null ? occurredAt : Instant.now();
-            return new EventEnvelope<>(type, version, key, payload, headers, instant);
+            String id = eventId != null ? eventId : UUID.randomUUID().toString();
+            return new EventEnvelope<>(id, type, version, key, payload, headers, instant);
         }
     }
 }
diff --git a/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventPublishException.java b/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventPublishException.java
index 16b87d8..0b41057 100644
--- a/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventPublishException.java
+++ b/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/EventPublishException.java
@@ -18,6 +18,8 @@ public final class EventPublishException extends PlatformException {
 
     private static final ErrorCode CODE = new ErrorCode("DC-MSG-0001");
 
+    private final boolean rejected;
+
     /**
      * Creates the exception.
      *
@@ -26,6 +28,29 @@ public final class EventPublishException extends PlatformException {
      * @throws NullPointerException if {@code message} is null
      */
     public EventPublishException(String message, Throwable cause) {
+        this(message, cause, false);
+    }
+
+    /**
+     * Creates the exception.
+     *
+     * @param message the detail message; never {@code null}
+     * @param cause the underlying transport failure, or {@code null}
+     * @param rejected whether the sink received this message and refused it, see {@link #rejected()}
+     * @throws NullPointerException if {@code message} is null
+     */
+    public EventPublishException(String message, Throwable cause, boolean rejected) {
         super(CODE, Objects.requireNonNull(message, "message must not be null"), cause);
+        this.rejected = rejected;
+    }
+
+    /**
+     * Whether the sink received this message and refused it (for example, unroutable), so sending it again may never
+     * work. {@code false} for an outage or a timeout, which time can fix.
+     *
+     * @return {@code true} if the sink refused the message
+     */
+    public boolean rejected() {
+        return rejected;
     }
 }
diff --git a/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/OutboxPublisher.java b/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/OutboxPublisher.java
new file mode 100644
index 0000000..aed264f
--- /dev/null
+++ b/messaging/platform-messaging-api/src/main/java/ae/gov/dubaicustoms/platform/messaging/OutboxPublisher.java
@@ -0,0 +1,46 @@
+package ae.gov.dubaicustoms.platform.messaging;
+
+import org.apiguardian.api.API;
+
+/**
+ * Publishes an integration event **in the caller's database transaction**: the event commits or rolls
+ * back with the business write, and is delivered later, at least once. Use it for any publish tied to a
+ * database change; {@link EventPublisher} sends immediately and cannot give that guarantee.
+ *
+ * <pre>{@code
+ * @Transactional
+ * public void place(Order order) {
+ *     orders.save(order);
+ *     outbox.publish("dc.orders:order.placed", EventEnvelope.of(new OrderPlaced(order.id())).key(order.id()).build());
+ * }
+ * }</pre>
+ *
+ * <p>Delivery is at-least-once and unordered: a consumer can see an event twice, or a later event first,
+ * and deduplicates by {@link EventEnvelope#eventId()}.
+ *
+ * <p>Implementations are thread-safe.
+ *
+ * @since 1.0.0
+ */
+@API(status = API.Status.STABLE, since = "1.0.0")
+public interface OutboxPublisher {
+
+    /**
+     * Stores {@code event} for delivery to {@code destination}, inside the active transaction.
+     *
+     * @param destination the transport-specific destination; never {@code null}
+     * @param event the envelope to store; never {@code null}
+     * @throws IllegalStateException if no transaction is active on the outbox's database: the event could
+     *     not commit atomically with the business write
+     * @throws IllegalArgumentException if a header is named like a credential: stored rows must not hold one
+     */
+    void publish(String destination, EventEnvelope<?> event);
+
+    /**
+     * Whether {@link #publish} would accept an event right now, for code that may run outside a transaction and has a
+     * fallback.
+     *
+     * @return {@code true} if the current thread can publish through the outbox
+     */
+    boolean canPublish();
+}
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/MessagingProperties.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/MessagingProperties.java
index 0116773..822c376 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/MessagingProperties.java
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/MessagingProperties.java
@@ -45,7 +45,7 @@ public record MessagingProperties(
         handler = handler != null ? handler : new Handler(null);
         dlq = dlq != null ? dlq : new Dlq(null);
         correlation = correlation != null ? correlation : new Correlation(true);
-        rabbit = rabbit != null ? rabbit : new Rabbit(false);
+        rabbit = rabbit != null ? rabbit : new Rabbit(false, null);
     }
 
     /**
@@ -117,10 +117,18 @@ public record MessagingProperties(
      *
      * @param quorumQueues declare quorum queues instead of classic queues; opt-in for production
      *     clusters
+     * @param confirmTimeout how long a send waits for the broker's publisher confirm before it fails
      * @since 0.2.0
      */
     public record Rabbit(
             /** Declare quorum queues instead of classic queues; opt-in for production clusters. */
-            @DefaultValue("false") boolean quorumQueues) {
+            @DefaultValue("false") boolean quorumQueues,
+            /** How long a send waits for the broker's publisher confirm before it fails. */
+            @DefaultValue("5s") Duration confirmTimeout) {
+
+        /** Fills in the default timeout when unset (used when constructed programmatically). */
+        public Rabbit {
+            confirmTimeout = confirmTimeout != null ? confirmTimeout : Duration.ofSeconds(5);
+        }
     }
 }
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java
index aa1f8ea..fd2a1a6 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java
@@ -134,7 +134,7 @@ public class PlatformMessagingAutoConfiguration {
                 RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, MessagingProperties properties) {
             return new RabbitEventTransport(rabbitTemplate, connectionFactory,
                     properties.handler().retry().maxAttempts(), properties.handler().retry().backoff(),
-                    properties.rabbit().quorumQueues());
+                    properties.rabbit().quorumQueues(), properties.rabbit().confirmTimeout());
         }
     }
 }
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingEnvironmentPostProcessor.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingEnvironmentPostProcessor.java
new file mode 100644
index 0000000..1d5f590
--- /dev/null
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingEnvironmentPostProcessor.java
@@ -0,0 +1,45 @@
+package ae.gov.dubaicustoms.platform.messaging.autoconfigure;
+
+import java.util.LinkedHashMap;
+import java.util.Map;
+import org.springframework.boot.SpringApplication;
+import org.springframework.boot.env.EnvironmentPostProcessor;
+import org.springframework.core.Ordered;
+import org.springframework.core.env.ConfigurableEnvironment;
+import org.springframework.core.env.MapPropertySource;
+
+/**
+ * Contributes what a Rabbit send needs to be acknowledged (D119) as Boot's own {@code spring.rabbitmq.*}
+ * defaults: publisher confirms, returns and {@code mandatory}, without which a send can be lost silently,
+ * and a 5 s connection timeout (Boot's client default of 60 s would hold a sender that long).
+ *
+ * <p>Defaults land in a LOWEST-precedence property source named {@code platform-messaging-defaults}, so user
+ * configuration wins; {@code RabbitEventTransport} refuses to start if confirms, returns or
+ * {@code mandatory} end up off. Registered via {@code META-INF/spring.factories}. Stateless and thread-safe.
+ *
+ * @since 1.0.0
+ */
+public class PlatformMessagingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
+
+    /** Property-source name; keep stable: operators look for it in /actuator/env. */
+    static final String PROPERTY_SOURCE_NAME = "platform-messaging-defaults";
+
+    @Override
+    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
+        if (!environment.getProperty("dc.platform.messaging.enabled", Boolean.class, true)) {
+            return;
+        }
+        Map<String, Object> defaults = new LinkedHashMap<>();
+        defaults.put("spring.rabbitmq.publisher-confirm-type", "correlated");
+        defaults.put("spring.rabbitmq.publisher-returns", "true");
+        defaults.put("spring.rabbitmq.template.mandatory", "true");
+        defaults.put("spring.rabbitmq.connection-timeout", "5s");
+        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
+    }
+
+    @Override
+    public int getOrder() {
+        // Lowest precedence: application.yml/profile values must win over these defaults.
+        return Ordered.LOWEST_PRECEDENCE;
+    }
+}
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/DefaultEventPublisher.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/DefaultEventPublisher.java
index 24ef022..17918c4 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/DefaultEventPublisher.java
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/DefaultEventPublisher.java
@@ -17,7 +17,7 @@ import java.util.Objects;
 
 /**
  * Default {@link EventPublisher}: wraps the single configured {@link EventTransport}, adding
- * {@code correlationId}/{@code eventType}/{@code eventVersion} headers and instrumenting the send
+ * {@code eventId}/{@code correlationId}/{@code eventType}/{@code eventVersion} headers and instrumenting the send
  * with a Micrometer {@link Observation}.
  */
 public final class DefaultEventPublisher implements EventPublisher {
@@ -53,6 +53,7 @@ public final class DefaultEventPublisher implements EventPublisher {
 
     private void doPublish(String destination, EventEnvelope<?> event) {
         Map<String, String> headers = new LinkedHashMap<>(event.headers());
+        headers.put(EventEnvelope.EVENT_ID_HEADER, event.eventId());
         headers.put(EVENT_TYPE_HEADER, event.eventType());
         headers.put(EVENT_VERSION_HEADER, String.valueOf(event.eventVersion()));
         if (properties.correlation().propagate()) {
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java
index 28ca7f4..e53f92a 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java
@@ -13,6 +13,7 @@ import java.lang.reflect.Type;
 import java.time.Instant;
 import java.util.Map;
 import java.util.Objects;
+import java.util.UUID;
 import java.util.concurrent.CopyOnWriteArrayList;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
@@ -127,7 +128,9 @@ public final class EventHandlerRegistrar implements BeanPostProcessor, Disposabl
         Class<?> payloadType = envelopePayloadType(method);
         Object payload = serializer.deserialize(value, payloadType);
         int eventVersion = parseVersion(headers.get("eventVersion"));
-        return new EventEnvelope<>(eventType, eventVersion, null, payload, headers, Instant.now());
+        // A message from a non-platform producer has no id; it still gets one, though a redelivery will differ.
+        String eventId = headers.getOrDefault(EventEnvelope.EVENT_ID_HEADER, UUID.randomUUID().toString());
+        return new EventEnvelope<>(eventId, eventType, eventVersion, null, payload, headers, Instant.now());
     }
 
     private static Class<?> envelopePayloadType(Method method) {
diff --git a/messaging/platform-messaging-autoconfigure/src/main/resources/META-INF/spring.factories b/messaging/platform-messaging-autoconfigure/src/main/resources/META-INF/spring.factories
index 9b812a9..50ff115 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/resources/META-INF/spring.factories
+++ b/messaging/platform-messaging-autoconfigure/src/main/resources/META-INF/spring.factories
@@ -1,3 +1,5 @@
 # FailureAnalyzer (phase-16 A.2): teaches the "messaging on, no transport starter" startup failure.
+org.springframework.boot.env.EnvironmentPostProcessor=\
+ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingEnvironmentPostProcessor
 org.springframework.boot.diagnostics.FailureAnalyzer=\
 ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.MessagingNoTransportFailureAnalyzer
diff --git a/messaging/platform-messaging-outbox-autoconfigure/pom.xml b/messaging/platform-messaging-outbox-autoconfigure/pom.xml
new file mode 100644
index 0000000..6b702e6
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/pom.xml
@@ -0,0 +1,110 @@
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+  <modelVersion>4.0.0</modelVersion>
+
+  <parent>
+    <groupId>ae.gov.dubaicustoms.platform</groupId>
+    <artifactId>platform-parent</artifactId>
+    <version>${revision}</version>
+    <relativePath>../../build/platform-parent</relativePath>
+  </parent>
+
+  <artifactId>platform-messaging-outbox-autoconfigure</artifactId>
+  <packaging>jar</packaging>
+
+  <name>DC Platform :: Messaging :: Transactional Outbox :: Auto Configuration</name>
+  <description>
+    Wires the transactional outbox: OutboxPublisher on the application's DataSource, a drainer on its own bounded pool,
+    the kill switch and the startup checks. A separate module so the messaging auto-configuration stays within its
+    dependency ceiling and a service without the outbox carries none of this.
+  </description>
+
+  <dependencies>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-outbox</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-spi</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-api</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-core-api</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-autoconfigure</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>com.zaxxer</groupId>
+      <artifactId>HikariCP</artifactId>
+    </dependency>
+    <!-- Supplies the stored principal, guarded by @ConditionalOnClass (CLAUDE.md rule 5). -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-security-api</artifactId>
+      <version>${revision}</version>
+      <optional>true</optional>
+    </dependency>
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-core</artifactId>
+      <optional>true</optional>
+    </dependency>
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-tracing</artifactId>
+      <optional>true</optional>
+    </dependency>
+    <!-- Generates config metadata from the properties record at compile time. -->
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-configuration-processor</artifactId>
+      <optional>true</optional>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-autoconfigure</artifactId>
+      <version>${revision}</version>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-inmemory</artifactId>
+      <version>${revision}</version>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-starter-data-jpa</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>com.h2database</groupId>
+      <artifactId>h2</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>com.fasterxml.jackson.core</groupId>
+      <artifactId>jackson-databind</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <!-- Runs PlatformArchRules over this module (ArchConstitutionTest). -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-build-tools</artifactId>
+      <scope>test</scope>
+    </dependency>
+  </dependencies>
+</project>
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxAutoConfiguration.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxAutoConfiguration.java
new file mode 100644
index 0000000..375edd1
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxAutoConfiguration.java
@@ -0,0 +1,139 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure;
+
+import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
+import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
+import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
+import ae.gov.dubaicustoms.platform.messaging.outbox.JdbcOutboxPublisher;
+import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxContext;
+import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxDrainer;
+import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.AfterCommitOutboxPublisher;
+import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxConnections;
+import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.PlatformOutboxContext;
+import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
+import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
+import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
+import io.micrometer.core.instrument.MeterRegistry;
+import io.micrometer.tracing.Tracer;
+import io.micrometer.tracing.propagation.Propagator;
+import java.time.Clock;
+import java.util.function.Supplier;
+import javax.sql.DataSource;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.ObjectProvider;
+import org.springframework.boot.autoconfigure.AutoConfiguration;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
+import org.springframework.boot.context.properties.EnableConfigurationProperties;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+
+/*
+ * Transactional outbox (HD11). Activation: platform-messaging-outbox on the classpath, a DataSource bean, and
+ * dc.platform.messaging.enabled not false. Tier DEFAULT_ON (plan §7.3):
+ *   dc.platform.messaging.outbox.enabled=true (default): outboxPublisher writes in the caller's transaction;
+ *   outboxDrainer (SmartLifecycle) delivers; outboxCapabilityDescriptor reports ACTIVE.
+ *   dc.platform.messaging.outbox.enabled=false: a startup WARN says publishes are no longer guaranteed;
+ *   outboxPublisher publishes through EventPublisher after commit; no drainer runs.
+ * Backs off by bean: OutboxPublisher, OutboxContext, outboxDrainer, outboxCapabilityDescriptor.
+ * Deliberately adds no DataSource and no PlatformTransactionManager bean: either would make Boot back off from the
+ * service's own pool or JPA transaction manager (OutboxConnections holds the drainer's pool instead).
+ */
+@AutoConfiguration(afterName = {
+        "ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingAutoConfiguration",
+        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
+@ConditionalOnClass({JdbcOutboxPublisher.class, DataSource.class})
+@ConditionalOnBean(DataSource.class)
+@ConditionalOnProperty(prefix = "dc.platform.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
+@EnableConfigurationProperties(OutboxProperties.class)
+public class OutboxAutoConfiguration {
+
+    private static final Logger log = LoggerFactory.getLogger(OutboxAutoConfiguration.class);
+
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnProperty(prefix = "dc.platform.messaging.outbox", name = "enabled", havingValue = "true",
+            matchIfMissing = true)
+    static class Enabled {
+
+        @Bean
+        @ConditionalOnMissingBean
+        OutboxContext outboxContext(ObjectProvider<PlatformOutboxContext.TraceSource> trace,
+                ObjectProvider<PlatformOutboxContext.PrincipalSource> principal) {
+            return new PlatformOutboxContext(trace.getIfAvailable(() -> java.util.Map::of),
+                    principal.getIfAvailable(() -> () -> null));
+        }
+
+        @Bean
+        @ConditionalOnMissingBean
+        OutboxPublisher outboxPublisher(DataSource dataSource, EventSerializer serializer, OutboxContext context) {
+            return new JdbcOutboxPublisher(dataSource, serializer, context, Clock.systemUTC());
+        }
+
+        @Bean(destroyMethod = "close")
+        @ConditionalOnMissingBean
+        OutboxConnections outboxConnections(DataSource dataSource, OutboxProperties properties,
+                ObjectProvider<MeterRegistry> registry) {
+            return OutboxConnections.of(dataSource, properties.poolSize(), registry.getIfAvailable());
+        }
+
+        @Bean
+        @ConditionalOnMissingBean
+        OutboxDrainer outboxDrainer(OutboxConnections connections, EventTransport transport, OutboxContext context,
+                OutboxProperties properties, ObjectProvider<MeterRegistry> registry) {
+            return new OutboxDrainer(connections.dataSource(), transport, context, properties.settings(),
+                    Clock.systemUTC(), registry.getIfAvailable());
+        }
+
+        @Bean
+        @ConditionalOnMissingBean(name = "outboxCapabilityDescriptor")
+        CapabilityDescriptor outboxCapabilityDescriptor() {
+            return new CapabilityDescriptor("outbox", "ACTIVE", "jdbc");
+        }
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnProperty(prefix = "dc.platform.messaging.outbox", name = "enabled", havingValue = "false")
+    static class Disabled {
+
+        @Bean
+        @ConditionalOnMissingBean
+        OutboxPublisher outboxPublisher(EventPublisher eventPublisher) {
+            log.warn("dc.platform.messaging.outbox.enabled=false: events are published after commit and are NO LONGER "
+                    + "GUARANTEED. A crash between the commit and the send loses the event.");
+            return new AfterCommitOutboxPublisher(eventPublisher);
+        }
+
+        @Bean
+        @ConditionalOnMissingBean(name = "outboxCapabilityDescriptor")
+        CapabilityDescriptor outboxCapabilityDescriptor() {
+            return new CapabilityDescriptor("outbox", "INACTIVE", "disabled: publishes are not guaranteed");
+        }
+    }
+
+    /** The current trace as W3C headers, when tracing is on the classpath. */
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnClass({Tracer.class, Propagator.class})
+    static class TraceConfiguration {
+
+        @Bean
+        @ConditionalOnBean({Tracer.class, Propagator.class})
+        PlatformOutboxContext.TraceSource outboxTraceSource(Tracer tracer, Propagator propagator) {
+            return PlatformOutboxContext.traceSource(tracer, propagator);
+        }
+    }
+
+    /** The current principal's subject, when the security API is on the classpath. */
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnClass(CurrentUserAccessor.class)
+    static class PrincipalConfiguration {
+
+        @Bean
+        @ConditionalOnBean(CurrentUserAccessor.class)
+        PlatformOutboxContext.PrincipalSource outboxPrincipalSource(CurrentUserAccessor accessor) {
+            Supplier<String> subject = () -> accessor.currentUser().map(user -> user.subject()).orElse(null);
+            return subject::get;
+        }
+    }
+}
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java
new file mode 100644
index 0000000..68d08db
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java
@@ -0,0 +1,66 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure;
+
+import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxSettings;
+import java.time.Duration;
+import org.springframework.boot.context.properties.ConfigurationProperties;
+import org.springframework.boot.context.properties.bind.DefaultValue;
+import org.springframework.validation.annotation.Validated;
+
+/**
+ * Configuration for the transactional outbox. Bound from {@code dc.platform.messaging.outbox.*}.
+ *
+ * <p>Immutable; validated at startup. Registered by {@code OutboxAutoConfiguration} via
+ * {@code @EnableConfigurationProperties} (never scanned).
+ *
+ * @param enabled {@code false} publishes after commit, no longer guaranteed, and logs a WARN saying so
+ * @param poolSize the most database connections the drainer holds, on its own pool
+ * @param batchSize the most rows claimed in one transaction, and so the most duplicates a killed instance can cause
+ * @param pollInterval the pause when nothing is due
+ * @param maxRejections how many times the sink may reject a row before it is parked; outages do not count
+ * @param initialBackoff the first per-row retry delay; doubles per attempt
+ * @param maxBackoff the longest per-row retry delay and the longest pause after a failure
+ * @param sentRetention how long a sent row is kept before deletion
+ * @param statementTimeout the timeout on each statement the drainer runs
+ * @param gaugeInterval how often the backlog metrics are refreshed
+ * @param retentionInterval how often sent rows are deleted
+ * @param stopTimeout how long a shutdown waits for the batch in flight
+ * @since 1.0.0
+ */
+@Validated
+@ConfigurationProperties(prefix = "dc.platform.messaging.outbox")
+public record OutboxProperties(
+        /** False publishes after commit, no longer guaranteed, and logs a WARN saying so. */
+        @DefaultValue("true") boolean enabled,
+        /** The most database connections the drainer holds, on its own pool. */
+        @DefaultValue("2") int poolSize,
+        /** The most rows claimed in one transaction, and so the most duplicates a killed instance can cause. */
+        @DefaultValue("50") int batchSize,
+        /** The pause when nothing is due. */
+        @DefaultValue("1s") Duration pollInterval,
+        /** How many times the sink may reject a row before it is parked; outages do not count. */
+        @DefaultValue("10") int maxRejections,
+        /** The first per-row retry delay; doubles per attempt. */
+        @DefaultValue("1s") Duration initialBackoff,
+        /** The longest per-row retry delay and the longest pause after a failure. */
+        @DefaultValue("5m") Duration maxBackoff,
+        /** How long a sent row is kept before deletion. */
+        @DefaultValue("24h") Duration sentRetention,
+        /** The timeout on each statement the drainer runs. */
+        @DefaultValue("30s") Duration statementTimeout,
+        /** How often the backlog metrics are refreshed. */
+        @DefaultValue("5s") Duration gaugeInterval,
+        /** How often sent rows are deleted. */
+        @DefaultValue("5m") Duration retentionInterval,
+        /** How long a shutdown waits for the batch in flight. */
+        @DefaultValue("30s") Duration stopTimeout) {
+
+    /**
+     * The drainer's settings.
+     *
+     * @return the settings; never {@code null}
+     */
+    OutboxSettings settings() {
+        return new OutboxSettings(batchSize, pollInterval, maxRejections, initialBackoff, maxBackoff, sentRetention,
+                statementTimeout, gaugeInterval, retentionInterval, stopTimeout);
+    }
+}
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/AfterCommitOutboxPublisher.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/AfterCommitOutboxPublisher.java
new file mode 100644
index 0000000..d8e6b1b
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/AfterCommitOutboxPublisher.java
@@ -0,0 +1,41 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
+import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
+import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
+import java.util.Objects;
+import org.springframework.transaction.support.TransactionSynchronization;
+import org.springframework.transaction.support.TransactionSynchronizationManager;
+
+/**
+ * What {@link OutboxPublisher} does with {@code dc.platform.messaging.outbox.enabled=false}: publishes after commit, so
+ * application code keeps working but a crash between the commit and the send loses the event. The startup WARN says so.
+ * Immediately when no transaction is active. Thread-safe.
+ */
+public final class AfterCommitOutboxPublisher implements OutboxPublisher {
+
+    private final EventPublisher publisher;
+
+    public AfterCommitOutboxPublisher(EventPublisher publisher) {
+        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
+    }
+
+    @Override
+    public void publish(String destination, EventEnvelope<?> event) {
+        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
+            publisher.publish(destination, event);
+            return;
+        }
+        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
+            @Override
+            public void afterCommit() {
+                publisher.publish(destination, event);
+            }
+        });
+    }
+
+    @Override
+    public boolean canPublish() {
+        return true;
+    }
+}
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java
new file mode 100644
index 0000000..9623b03
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java
@@ -0,0 +1,76 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;
+
+import com.zaxxer.hikari.HikariConfig;
+import com.zaxxer.hikari.HikariDataSource;
+import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
+import io.micrometer.core.instrument.MeterRegistry;
+import java.sql.SQLException;
+import javax.sql.DataSource;
+
+/**
+ * The drainer's own bounded connections, so a stuck or backlogged drainer can never starve request threads. Holds the
+ * pool instead of being a {@code DataSource} bean: such a bean would make Boot back off from the service's own pool.
+ *
+ * <p>A Hikari application pool is copied, so the drainer gets the same URL, credentials, driver properties (the Oracle
+ * read and connect bounds, added to the application's pool before this runs) and connection-init SQL. Any other data
+ * source is wrapped. The copy does not connect until first use. Thread-safe.
+ */
+public final class OutboxConnections implements AutoCloseable {
+
+    private static final String POOL_NAME = "platform-outbox";
+
+    private final HikariDataSource pool;
+
+    private OutboxConnections(HikariDataSource pool) {
+        this.pool = pool;
+    }
+
+    /**
+     * Builds the drainer's pool.
+     *
+     * @param application the application's data source; never {@code null}
+     * @param poolSize the most connections the drainer may hold
+     * @param registry receives the pool's meters, or {@code null} for none
+     * @return the connections; never {@code null}
+     */
+    public static OutboxConnections of(DataSource application, int poolSize, MeterRegistry registry) {
+        HikariConfig config = new HikariConfig();
+        HikariDataSource hikari = unwrapHikari(application);
+        if (hikari != null) {
+            hikari.copyStateTo(config);
+        } else {
+            config.setDataSource(application);
+        }
+        config.setPoolName(POOL_NAME);
+        config.setMaximumPoolSize(poolSize);
+        config.setMinimumIdle(0);
+        // Never fails startup on its own: Flyway and the drainer's table check report a missing database clearly.
+        config.setInitializationFailTimeout(-1);
+        config.setMetricRegistry(null);
+        config.setHealthCheckRegistry(null);
+        config.setMetricsTrackerFactory(registry != null ? new MicrometerMetricsTrackerFactory(registry) : null);
+        return new OutboxConnections(new HikariDataSource(config));
+    }
+
+    private static HikariDataSource unwrapHikari(DataSource dataSource) {
+        try {
+            return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
+        } catch (SQLException e) {
+            return null;
+        }
+    }
+
+    /**
+     * The pool. Not exposed as a bean.
+     *
+     * @return the drainer's data source; never {@code null}
+     */
+    public DataSource dataSource() {
+        return pool;
+    }
+
+    @Override
+    public void close() {
+        pool.close();
+    }
+}
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxTableMissingFailureAnalyzer.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxTableMissingFailureAnalyzer.java
new file mode 100644
index 0000000..7bb5b7b
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxTableMissingFailureAnalyzer.java
@@ -0,0 +1,22 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxTableMissingException;
+import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
+import org.springframework.boot.diagnostics.FailureAnalysis;
+
+// Turns the drainer's startup check into the action that fixes it: the platform never creates the table (a runtime user
+// has no DDL rights, and a migration in a jar would collide with the service's version numbers, D118).
+public class OutboxTableMissingFailureAnalyzer extends AbstractFailureAnalyzer<OutboxTableMissingException> {
+
+    @Override
+    protected FailureAnalysis analyze(Throwable rootFailure, OutboxTableMissingException cause) {
+        return new FailureAnalysis(
+                "The transactional outbox is on, but the platform_outbox table is missing or this database user cannot "
+                        + "read it, so published events could not be stored or delivered.",
+                "Copy the DDL from classpath:ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql "
+                        + "(platform-messaging-outbox) into your own Flyway migrations, and grant select, insert, update, "
+                        + "delete on it to the runtime user. To run without the outbox, set "
+                        + "dc.platform.messaging.outbox.enabled=false: publishes are then no longer guaranteed.",
+                cause);
+    }
+}
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/PlatformOutboxContext.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/PlatformOutboxContext.java
new file mode 100644
index 0000000..fb9723e
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/PlatformOutboxContext.java
@@ -0,0 +1,99 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxContext;
+import io.micrometer.tracing.Span;
+import io.micrometer.tracing.TraceContext;
+import io.micrometer.tracing.Tracer;
+import io.micrometer.tracing.propagation.Propagator;
+import java.util.LinkedHashMap;
+import java.util.Map;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+
+/**
+ * Reads the caller's trace and principal for the outbox row, and restores the stored trace on the drainer's thread so
+ * its log lines land on the original trace. Only {@code traceparent}/{@code tracestate} leave here (plan §277).
+ * Thread-safe.
+ */
+public final class PlatformOutboxContext implements OutboxContext {
+
+    private static final Logger log = LoggerFactory.getLogger(PlatformOutboxContext.class);
+    private static final String TRACEPARENT = "traceparent";
+
+    private final TraceSource trace;
+    private final PrincipalSource principal;
+
+    public PlatformOutboxContext(TraceSource trace, PrincipalSource principal) {
+        this.trace = trace;
+        this.principal = principal;
+    }
+
+    /** Supplies the current trace as W3C headers; empty without one. */
+    @FunctionalInterface
+    public interface TraceSource {
+        Map<String, String> headers();
+    }
+
+    /** Supplies the current principal's subject id, or {@code null}. */
+    @FunctionalInterface
+    public interface PrincipalSource {
+        String subject();
+    }
+
+    /**
+     * The trace source over Micrometer Tracing.
+     *
+     * @param tracer the tracer; never {@code null}
+     * @param propagator writes the W3C headers; never {@code null}
+     * @return the source; never {@code null}
+     */
+    public static TraceSource traceSource(Tracer tracer, Propagator propagator) {
+        return () -> {
+            Span span = tracer.currentSpan();
+            TraceContext context = span != null ? span.context() : null;
+            if (context == null) {
+                return Map.of();
+            }
+            Map<String, String> headers = new LinkedHashMap<>();
+            propagator.inject(context, headers, Map::put);
+            return headers;
+        };
+    }
+
+    @Override
+    public Map<String, String> traceHeaders() {
+        return trace.headers();
+    }
+
+    @Override
+    public String principal() {
+        return principal.subject();
+    }
+
+    @Override
+    public Scope restore(Map<String, String> headers) {
+        String traceId = traceId(headers.get(TRACEPARENT));
+        if (traceId == null) {
+            return () -> { };
+        }
+        AutoCloseable scope = RequestContext.open(new CorrelationId(traceId), Map.of());
+        return () -> {
+            try {
+                scope.close();
+            } catch (Exception e) {
+                log.debug("closing the restored outbox context failed: {}", e.getClass().getName());
+            }
+        };
+    }
+
+    // version-traceId-spanId-flags; anything else is not restored, and the send goes on.
+    private static String traceId(String traceparent) {
+        if (traceparent == null) {
+            return null;
+        }
+        String[] parts = traceparent.split("-");
+        return parts.length == 4 && parts[1].matches("[0-9a-f]{32}") ? parts[1] : null;
+    }
+}
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/resources/META-INF/spring.factories b/messaging/platform-messaging-outbox-autoconfigure/src/main/resources/META-INF/spring.factories
new file mode 100644
index 0000000..0478822
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/resources/META-INF/spring.factories
@@ -0,0 +1,3 @@
+# Names the action that fixes a missing outbox table.
+org.springframework.boot.diagnostics.FailureAnalyzer=\
+ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxTableMissingFailureAnalyzer
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports b/messaging/platform-messaging-outbox-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
new file mode 100644
index 0000000..0e622f4
--- /dev/null
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
@@ -0,0 +1 @@
+ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.OutboxAutoConfiguration
diff --git a/messaging/platform-messaging-outbox/pom.xml b/messaging/platform-messaging-outbox/pom.xml
new file mode 100644
index 0000000..d08d053
--- /dev/null
+++ b/messaging/platform-messaging-outbox/pom.xml
@@ -0,0 +1,88 @@
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+  <modelVersion>4.0.0</modelVersion>
+
+  <parent>
+    <groupId>ae.gov.dubaicustoms.platform</groupId>
+    <artifactId>platform-parent</artifactId>
+    <version>${revision}</version>
+    <relativePath>../../build/platform-parent</relativePath>
+  </parent>
+
+  <artifactId>platform-messaging-outbox</artifactId>
+  <packaging>jar</packaging>
+
+  <name>DC Platform :: Messaging :: Transactional Outbox</name>
+  <description>
+    JDBC transactional outbox: the event row commits with the business write in the caller's transaction, and a
+    bounded drainer delivers it at least once through the EventTransport (HD11). Needs a DataSource, not JPA.
+  </description>
+
+  <dependencies>
+    <!-- impl -> same-capability spi/api + its 3rd-party libs (CLAUDE.md rule 5). -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-spi</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-api</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework</groupId>
+      <artifactId>spring-jdbc</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework</groupId>
+      <artifactId>spring-context</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>com.fasterxml.jackson.core</groupId>
+      <artifactId>jackson-databind</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>org.slf4j</groupId>
+      <artifactId>slf4j-api</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-core</artifactId>
+      <optional>true</optional>
+    </dependency>
+    <!-- Runs PlatformArchRules over this module (ArchConstitutionTest). -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-build-tools</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>com.h2database</groupId>
+      <artifactId>h2</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.awaitility</groupId>
+      <artifactId>awaitility</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.testcontainers</groupId>
+      <artifactId>testcontainers-junit-jupiter</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.testcontainers</groupId>
+      <artifactId>testcontainers-oracle-free</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>com.oracle.database.jdbc</groupId>
+      <artifactId>ojdbc11</artifactId>
+      <scope>test</scope>
+    </dependency>
+  </dependencies>
+</project>
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java
new file mode 100644
index 0000000..9ab9196
--- /dev/null
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java
@@ -0,0 +1,103 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox;
+
+import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
+import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
+import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
+import com.fasterxml.jackson.core.JsonProcessingException;
+import com.fasterxml.jackson.databind.ObjectMapper;
+import java.time.Clock;
+import java.time.OffsetDateTime;
+import java.util.LinkedHashMap;
+import java.util.Locale;
+import java.util.Map;
+import java.util.Objects;
+import java.util.Set;
+import javax.sql.DataSource;
+import org.springframework.jdbc.core.JdbcTemplate;
+import org.springframework.transaction.support.TransactionSynchronizationManager;
+
+/**
+ * Writes the outbox row through the application's {@link DataSource}, inside the caller's transaction, so it
+ * commits or rolls back with the business write. It refuses to write unless a transaction holds a connection of
+ * that {@code DataSource} (a publish under another transaction manager would auto-commit on its own connection and
+ * silently lose atomicity). Serializes now: a later class change cannot alter a stored event.
+ *
+ * <p>Thread-safe.
+ *
+ * @since 1.0.0
+ */
+public final class JdbcOutboxPublisher implements OutboxPublisher {
+
+    static final ObjectMapper JSON = new ObjectMapper();
+
+    private static final String INSERT = """
+            insert into platform_outbox (event_id, event_type, event_version, destination, msg_key, payload, content_type,
+                headers, principal, created_at, next_attempt_at, attempts, rejections, status)
+            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 'PENDING')
+            """;
+
+    // The same names the logging capability masks (D102): a name ending in one of these, ignoring case, '-' and '_'.
+    private static final Set<String> CREDENTIAL_SUFFIXES =
+            Set.of("password", "passwd", "secret", "token", "apikey", "authorization", "cookie");
+
+    private final DataSource dataSource;
+    private final JdbcTemplate jdbc;
+    private final EventSerializer serializer;
+    private final OutboxContext context;
+    private final Clock clock;
+
+    /**
+     * Creates the publisher.
+     *
+     * @param dataSource the application's data source, the one the business write uses; never {@code null}
+     * @param serializer serializes the payload; never {@code null}
+     * @param context supplies the trace and principal to store; never {@code null}
+     * @param clock supplies the created time; never {@code null}
+     */
+    public JdbcOutboxPublisher(DataSource dataSource, EventSerializer serializer, OutboxContext context, Clock clock) {
+        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
+        this.jdbc = new JdbcTemplate(dataSource);
+        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
+        this.context = Objects.requireNonNull(context, "context must not be null");
+        this.clock = Objects.requireNonNull(clock, "clock must not be null");
+    }
+
+    @Override
+    public void publish(String destination, EventEnvelope<?> event) {
+        Objects.requireNonNull(destination, "destination must not be null");
+        Objects.requireNonNull(event, "event must not be null");
+        if (!canPublish()) {
+            throw new IllegalStateException("OutboxPublisher.publish needs an active transaction on the outbox's "
+                    + "DataSource, or the event cannot commit with the business write: make the calling method "
+                    + "@Transactional under a transaction manager for that DataSource");
+        }
+        event.headers().keySet().forEach(JdbcOutboxPublisher::refuseCredentialName);
+        Map<String, String> headers = new LinkedHashMap<>(event.headers());
+        headers.putAll(context.traceHeaders());
+        OffsetDateTime now = OffsetDateTime.now(clock);
+        jdbc.update(INSERT, event.eventId(), event.eventType(), event.eventVersion(), destination, event.key(),
+                serializer.serialize(event.payload()), serializer.contentType(), toJson(headers), context.principal(),
+                now, now);
+    }
+
+    @Override
+    public boolean canPublish() {
+        return TransactionSynchronizationManager.isActualTransactionActive()
+                && TransactionSynchronizationManager.hasResource(dataSource);
+    }
+
+    private static void refuseCredentialName(String name) {
+        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
+        if (CREDENTIAL_SUFFIXES.stream().anyMatch(normalized::endsWith)) {
+            throw new IllegalArgumentException("Outbox rows must not hold credentials; refused header: " + name);
+        }
+    }
+
+    private static String toJson(Map<String, String> headers) {
+        try {
+            return JSON.writeValueAsString(headers);
+        } catch (JsonProcessingException e) {
+            throw new IllegalArgumentException("Outbox headers could not be stored", e);
+        }
+    }
+}
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxContext.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxContext.java
new file mode 100644
index 0000000..aa0042f
--- /dev/null
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxContext.java
@@ -0,0 +1,61 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox;
+
+import java.util.Map;
+
+/**
+ * What the outbox reads from the caller's context when writing a row, and restores from it when sending. An SPI
+ * of this module so it stays free of tracing and security types; the auto-configuration supplies the real one.
+ *
+ * <p>Implementations are thread-safe.
+ *
+ * @since 1.0.0
+ */
+public interface OutboxContext {
+
+    /** Stores nothing and restores nothing. */
+    OutboxContext NONE = new OutboxContext() {
+        @Override
+        public Map<String, String> traceHeaders() {
+            return Map.of();
+        }
+
+        @Override
+        public String principal() {
+            return null;
+        }
+
+        @Override
+        public Scope restore(Map<String, String> headers) {
+            return () -> { };
+        }
+    };
+
+    /**
+     * The current trace, as W3C headers ({@code traceparent}, {@code tracestate}).
+     *
+     * @return the headers; empty without a current trace; never {@code null}
+     */
+    Map<String, String> traceHeaders();
+
+    /**
+     * The current principal's subject id, never a token.
+     *
+     * @return the subject, or {@code null} if there is none
+     */
+    String principal();
+
+    /**
+     * Restores the stored trace on the current thread while a row is sent, so log lines carry it.
+     *
+     * @param headers the row's stored headers; never {@code null}
+     * @return closes the restored context; never {@code null}
+     */
+    Scope restore(Map<String, String> headers);
+
+    /** Ends a {@linkplain #restore restored} context. */
+    @FunctionalInterface
+    interface Scope extends AutoCloseable {
+        @Override
+        void close();
+    }
+}
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java
new file mode 100644
index 0000000..046b5e5
--- /dev/null
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java
@@ -0,0 +1,424 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox;
+
+import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
+import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
+import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
+import com.fasterxml.jackson.core.JsonProcessingException;
+import com.fasterxml.jackson.core.type.TypeReference;
+import io.micrometer.core.instrument.Counter;
+import io.micrometer.core.instrument.Gauge;
+import io.micrometer.core.instrument.MeterRegistry;
+import java.nio.charset.StandardCharsets;
+import java.sql.Connection;
+import java.sql.PreparedStatement;
+import java.sql.ResultSet;
+import java.sql.SQLException;
+import java.sql.SQLNonTransientConnectionException;
+import java.sql.SQLRecoverableException;
+import java.sql.SQLTransientConnectionException;
+import java.time.Clock;
+import java.time.Duration;
+import java.time.Instant;
+import java.time.OffsetDateTime;
+import java.util.ArrayList;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import java.util.Objects;
+import java.util.concurrent.atomic.AtomicLong;
+import javax.sql.DataSource;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.context.SmartLifecycle;
+
+/**
+ * Delivers outbox rows at least once through the {@link EventTransport}.
+ *
+ * <p>One transaction per batch, on the drainer's own bounded connections: claim due rows with
+ * {@code FOR UPDATE SKIP LOCKED}, send each, record each, commit (D116). Row locks keep a concurrent instance off the
+ * batch, and the database releases them when a pod dies, so no lease or recovery job exists; a kill can only repeat
+ * the rows of the batch in flight. The batch limit is applied by fetching at most {@code batch-size} rows from the
+ * cursor, because Oracle locks as it fetches: {@code ROWNUM} or {@code FETCH FIRST} would limit before skipping
+ * locked rows, or are refused with {@code FOR UPDATE}.
+ *
+ * <p>A send failure ends the batch and pauses the drainer. Only a rejection by the sink counts toward parking
+ * ({@link EventPublishException#rejected()}); an outage delays rows but never parks them, so recovery delivers
+ * the whole backlog. Log lines name the exception class only: a transport message can carry hosts and credentials.
+ *
+ * <p>Thread-safe. Runs on one daemon thread.
+ *
+ * @since 1.0.0
+ */
+public final class OutboxDrainer implements SmartLifecycle {
+
+    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);
+
+    // Stops before the transport and the pools close (bean destruction), after the web server's graceful phase.
+    private static final int PHASE = DEFAULT_PHASE - 4096;
+
+    private static final String CLAIM = """
+            select event_id, event_type, event_version, destination, msg_key, payload, content_type, headers, attempts,
+                rejections, principal
+            from platform_outbox where status = 'PENDING' and next_attempt_at <= ?
+            order by next_attempt_at for update skip locked
+            """;
+    private static final String MARK_SENT =
+            "update platform_outbox set status = 'SENT', sent_at = ?, attempts = ?, last_error = null where event_id = ?";
+    private static final String MARK_FAILED = "update platform_outbox set status = ?, attempts = ?, rejections = ?, "
+            + "next_attempt_at = ?, last_error = ? where event_id = ?";
+    // ROWNUM, not FETCH FIRST: a bounded DELETE on Oracle 19c.
+    private static final String DELETE_SENT =
+            "delete from platform_outbox where status = 'SENT' and sent_at < ? and rownum <= ?";
+    private static final String COUNT_BY_STATUS = "select status, count(*) from platform_outbox group by status";
+    private static final String OLDEST_PENDING = "select min(created_at) from platform_outbox where status = 'PENDING'";
+    private static final String PROBE = "select event_id from platform_outbox where 1 = 0";
+
+    private final DataSource dataSource;
+    private final EventTransport transport;
+    private final OutboxContext context;
+    private final OutboxSettings settings;
+    private final Clock clock;
+    private final AtomicLong pending = new AtomicLong();
+    private final AtomicLong parked = new AtomicLong();
+    private final AtomicLong oldestPendingSeconds = new AtomicLong();
+    private final Counter sentCounter;
+    private final Counter failedCounter;
+    private final Counter parkedCounter;
+
+    private volatile boolean running;
+    private volatile Thread thread;
+    private Instant nextGauges = Instant.MIN;
+    private Instant nextRetention = Instant.MIN;
+    private Duration pause = Duration.ZERO;
+
+    /**
+     * Creates the drainer.
+     *
+     * @param dataSource the drainer's own bounded connections, never the application's pool; never {@code null}
+     * @param transport sends the rows; never {@code null}
+     * @param context restores the stored trace while sending; never {@code null}
+     * @param settings the bounds; never {@code null}
+     * @param clock the clock; never {@code null}
+     * @param registry receives the metrics, or {@code null} for none
+     */
+    public OutboxDrainer(DataSource dataSource, EventTransport transport, OutboxContext context,
+            OutboxSettings settings, Clock clock, MeterRegistry registry) {
+        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
+        this.transport = Objects.requireNonNull(transport, "transport must not be null");
+        this.context = Objects.requireNonNull(context, "context must not be null");
+        this.settings = Objects.requireNonNull(settings, "settings must not be null");
+        this.clock = Objects.requireNonNull(clock, "clock must not be null");
+        if (registry != null) {
+            Gauge.builder("platform.outbox.pending", pending, AtomicLong::get).register(registry);
+            Gauge.builder("platform.outbox.parked", parked, AtomicLong::get).register(registry);
+            Gauge.builder("platform.outbox.oldest.pending.age", oldestPendingSeconds, AtomicLong::get)
+                    .baseUnit("seconds").register(registry);
+        }
+        this.sentCounter = registry != null ? registry.counter("platform.outbox.sent") : null;
+        this.failedCounter = registry != null ? registry.counter("platform.outbox.failed") : null;
+        this.parkedCounter = registry != null ? registry.counter("platform.outbox.parked.total") : null;
+    }
+
+    @Override
+    public void start() {
+        probeTable();
+        running = true;
+        Thread drainer = new Thread(this::run, "platform-outbox-drainer");
+        drainer.setDaemon(true);
+        thread = drainer;
+        drainer.start();
+    }
+
+    @Override
+    public void stop() {
+        running = false;
+        Thread drainer = thread;
+        if (drainer == null) {
+            return;
+        }
+        // Not interrupted at first: the batch in flight finishes and commits, so its rows are not sent again.
+        try {
+            drainer.join(settings.stopTimeout().toMillis());
+            if (drainer.isAlive()) {
+                log.warn("The outbox batch in flight did not finish within {}; its rows may be sent again",
+                        settings.stopTimeout());
+                drainer.interrupt();
+                drainer.join(1000);
+            }
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+        }
+        thread = null;
+    }
+
+    @Override
+    public boolean isRunning() {
+        return running;
+    }
+
+    @Override
+    public int getPhase() {
+        return PHASE;
+    }
+
+    // Only a database that answers and refuses means the table is missing. An unreachable one is an outage: the
+    // service may still start (Flyway, when present, has already reported it) and the drainer keeps trying.
+    private void probeTable() {
+        try (Connection connection = dataSource.getConnection();
+                PreparedStatement statement = connection.prepareStatement(PROBE);
+                ResultSet ignored = statement.executeQuery()) {
+            log.debug("outbox table present");
+        } catch (SQLException e) {
+            if (!isConnectionFailure(e)) {
+                throw new OutboxTableMissingException(e);
+            }
+            log.warn("Could not check the outbox table, the database is unreachable: {}", e.getClass().getName());
+        }
+    }
+
+    private static boolean isConnectionFailure(SQLException e) {
+        return e instanceof SQLTransientConnectionException || e instanceof SQLNonTransientConnectionException
+                || e instanceof SQLRecoverableException || (e.getSQLState() != null && e.getSQLState().startsWith("08"));
+    }
+
+    private void run() {
+        while (running) {
+            boolean moreDue = false;
+            try {
+                moreDue = drainBatch() == settings.batchSize() && pause.isZero();
+                housekeeping();
+            } catch (SQLException | RuntimeException e) {
+                // The database, not the sink: rows stay locked by nobody and are claimed again.
+                log.warn("Outbox drain failed: {}", e.getClass().getName());
+                increasePause();
+            }
+            if (!moreDue && running) {
+                sleep(pause.isZero() ? settings.pollInterval() : pause);
+            }
+        }
+    }
+
+    private void sleep(Duration duration) {
+        try {
+            Thread.sleep(duration);
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+            running = false;
+        }
+    }
+
+    private void increasePause() {
+        Duration doubled = pause.isZero() ? settings.initialBackoff() : pause.multipliedBy(2);
+        pause = doubled.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : doubled;
+    }
+
+    /**
+     * Claims and sends one batch, in one transaction.
+     *
+     * @return how many rows were claimed
+     * @throws SQLException if the database failed; the transaction rolls back and the rows stay due
+     */
+    int drainBatch() throws SQLException {
+        try (Connection connection = dataSource.getConnection()) {
+            connection.setAutoCommit(false);
+            try {
+                List<Row> rows = claim(connection);
+                boolean failed = false;
+                for (Row row : rows) {
+                    if (!send(connection, row)) {
+                        failed = true;
+                        break;
+                    }
+                }
+                connection.commit();
+                if (failed) {
+                    increasePause();
+                } else if (!rows.isEmpty()) {
+                    pause = Duration.ZERO;
+                }
+                return rows.size();
+            } catch (SQLException | RuntimeException e) {
+                rollback(connection);
+                throw e;
+            }
+        }
+    }
+
+    private static void rollback(Connection connection) {
+        try {
+            connection.rollback();
+        } catch (SQLException e) {
+            log.debug("outbox rollback failed: {}", e.getClass().getName());
+        }
+    }
+
+    // Stops reading at batch-size: Oracle locks rows as they are fetched, so the rest of the table stays unlocked.
+    private List<Row> claim(Connection connection) throws SQLException {
+        List<Row> rows = new ArrayList<>();
+        try (PreparedStatement statement = connection.prepareStatement(CLAIM)) {
+            statement.setQueryTimeout(timeoutSeconds());
+            statement.setFetchSize(settings.batchSize());
+            statement.setObject(1, OffsetDateTime.now(clock));
+            try (ResultSet result = statement.executeQuery()) {
+                while (rows.size() < settings.batchSize() && result.next()) {
+                    rows.add(new Row(result.getString(1), result.getString(2), result.getInt(3), result.getString(4),
+                            result.getString(5), result.getBytes(6), result.getString(7), result.getString(8),
+                            result.getInt(9), result.getInt(10), result.getString(11)));
+                }
+            }
+        }
+        return rows;
+    }
+
+    private boolean send(Connection connection, Row row) throws SQLException {
+        Map<String, String> stored = parseHeaders(row.headers());
+        try (OutboxContext.Scope ignored = context.restore(stored)) {
+            byte[] key = row.key() != null ? row.key().getBytes(StandardCharsets.UTF_8) : null;
+            transport.send(row.destination(), key, row.payload(), messageHeaders(row, stored));
+            markSent(connection, row);
+            increment(sentCounter);
+            return true;
+        } catch (RuntimeException e) {
+            markFailed(connection, row, e);
+            return false;
+        }
+    }
+
+    private static Map<String, String> messageHeaders(Row row, Map<String, String> stored) {
+        Map<String, String> headers = new LinkedHashMap<>(stored);
+        headers.put(EventEnvelope.EVENT_ID_HEADER, row.eventId());
+        headers.put("eventType", row.eventType());
+        headers.put("eventVersion", String.valueOf(row.eventVersion()));
+        headers.put("contentType", row.contentType());
+        if (row.principal() != null) {
+            headers.put("principal", row.principal());
+        }
+        return headers;
+    }
+
+    private static Map<String, String> parseHeaders(String json) {
+        try {
+            return JdbcOutboxPublisher.JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
+        } catch (JsonProcessingException e) {
+            throw new IllegalStateException("A stored outbox row has unreadable headers", e);
+        }
+    }
+
+    private void markSent(Connection connection, Row row) throws SQLException {
+        try (PreparedStatement statement = connection.prepareStatement(MARK_SENT)) {
+            statement.setQueryTimeout(timeoutSeconds());
+            statement.setObject(1, OffsetDateTime.now(clock));
+            statement.setInt(2, row.attempts() + 1);
+            statement.setString(3, row.eventId());
+            statement.executeUpdate();
+        }
+    }
+
+    private void markFailed(Connection connection, Row row, RuntimeException failure) throws SQLException {
+        int attempts = row.attempts() + 1;
+        boolean isRejection = failure instanceof EventPublishException publish && publish.rejected();
+        int rejections = row.rejections() + (isRejection ? 1 : 0);
+        boolean park = rejections >= settings.maxRejections();
+        try (PreparedStatement statement = connection.prepareStatement(MARK_FAILED)) {
+            statement.setQueryTimeout(timeoutSeconds());
+            statement.setString(1, park ? "PARKED" : "PENDING");
+            statement.setInt(2, attempts);
+            statement.setInt(3, rejections);
+            statement.setObject(4, OffsetDateTime.now(clock).plus(settings.backoff(attempts)));
+            statement.setString(5, failure.getClass().getName());
+            statement.setString(6, row.eventId());
+            statement.executeUpdate();
+        }
+        increment(failedCounter);
+        if (park) {
+            increment(parkedCounter);
+            log.warn("Outbox event {} of type {} parked after {} rejections by {}", row.eventId(), row.eventType(),
+                    rejections, row.destination());
+        } else {
+            log.warn("Outbox send to {} failed (attempt {}): {}", row.destination(), attempts,
+                    failure.getClass().getName());
+        }
+    }
+
+    private void housekeeping() throws SQLException {
+        Instant now = clock.instant();
+        if (!now.isBefore(nextGauges)) {
+            refreshGauges();
+            nextGauges = now.plus(settings.gaugeInterval());
+        }
+        if (!now.isBefore(nextRetention)) {
+            deleteSent();
+            nextRetention = now.plus(settings.retentionInterval());
+        }
+    }
+
+    /**
+     * Refreshes the backlog metrics. Runs here rather than on scrape, so a scrape never touches the database.
+     *
+     * @throws SQLException if the database failed
+     */
+    void refreshGauges() throws SQLException {
+        long pendingRows = 0;
+        long parkedRows = 0;
+        try (Connection connection = dataSource.getConnection();
+                PreparedStatement counts = connection.prepareStatement(COUNT_BY_STATUS);
+                PreparedStatement oldest = connection.prepareStatement(OLDEST_PENDING)) {
+            counts.setQueryTimeout(timeoutSeconds());
+            oldest.setQueryTimeout(timeoutSeconds());
+            try (ResultSet result = counts.executeQuery()) {
+                while (result.next()) {
+                    if ("PENDING".equals(result.getString(1))) {
+                        pendingRows = result.getLong(2);
+                    } else if ("PARKED".equals(result.getString(1))) {
+                        parkedRows = result.getLong(2);
+                    }
+                }
+            }
+            try (ResultSet result = oldest.executeQuery()) {
+                OffsetDateTime created = result.next() ? result.getObject(1, OffsetDateTime.class) : null;
+                oldestPendingSeconds.set(created == null ? 0
+                        : Math.max(0, Duration.between(created.toInstant(), clock.instant()).toSeconds()));
+            }
+        }
+        pending.set(pendingRows);
+        parked.set(parkedRows);
+    }
+
+    /**
+     * Deletes sent rows past retention, in short bounded transactions. Never touches pending or parked rows.
+     *
+     * @return how many rows were deleted
+     * @throws SQLException if the database failed
+     */
+    int deleteSent() throws SQLException {
+        int total = 0;
+        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(settings.sentRetention());
+        try (Connection connection = dataSource.getConnection();
+                PreparedStatement statement = connection.prepareStatement(DELETE_SENT)) {
+            statement.setQueryTimeout(timeoutSeconds());
+            statement.setObject(1, cutoff);
+            statement.setInt(2, settings.batchSize() * 20);
+            int deleted;
+            do {
+                deleted = statement.executeUpdate();
+                total += deleted;
+            } while (deleted == settings.batchSize() * 20 && running);
+        }
+        return total;
+    }
+
+    private int timeoutSeconds() {
+        return Math.toIntExact(settings.statementTimeout().toSeconds());
+    }
+
+    private static void increment(Counter counter) {
+        if (counter != null) {
+            counter.increment();
+        }
+    }
+
+    /** One claimed row. */
+    private record Row(String eventId, String eventType, int eventVersion, String destination, String key,
+            byte[] payload, String contentType, String headers, int attempts, int rejections, String principal) {
+    }
+}
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxSettings.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxSettings.java
new file mode 100644
index 0000000..e202859
--- /dev/null
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxSettings.java
@@ -0,0 +1,52 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox;
+
+import java.time.Duration;
+import java.util.Objects;
+
+/**
+ * The drainer's bounds. Immutable.
+ *
+ * @param batchSize the most rows claimed in one transaction, and so the most duplicates a killed instance can cause
+ * @param pollInterval the pause when nothing is due
+ * @param maxRejections how many times the sink may reject a row before it is parked. Outages do not count, so a long
+ *     outage parks nothing
+ * @param initialBackoff the first per-row retry delay; doubles per attempt
+ * @param maxBackoff the longest per-row retry delay, and the longest pause after a failure
+ * @param sentRetention how long a sent row is kept before deletion
+ * @param statementTimeout the timeout on each statement the drainer runs
+ * @param gaugeInterval how often the backlog metrics are refreshed
+ * @param retentionInterval how often sent rows are deleted
+ * @param stopTimeout how long a stop waits for the current batch
+ * @since 1.0.0
+ */
+public record OutboxSettings(int batchSize, Duration pollInterval, int maxRejections, Duration initialBackoff,
+        Duration maxBackoff, Duration sentRetention, Duration statementTimeout, Duration gaugeInterval,
+        Duration retentionInterval, Duration stopTimeout) {
+
+    /** Rejects bounds the drainer cannot work with. */
+    public OutboxSettings {
+        if (batchSize < 1 || maxRejections < 1) {
+            throw new IllegalArgumentException("batch size and max rejections must be at least 1");
+        }
+        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
+        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
+        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
+        Objects.requireNonNull(sentRetention, "sentRetention must not be null");
+        Objects.requireNonNull(statementTimeout, "statementTimeout must not be null");
+        Objects.requireNonNull(gaugeInterval, "gaugeInterval must not be null");
+        Objects.requireNonNull(retentionInterval, "retentionInterval must not be null");
+        Objects.requireNonNull(stopTimeout, "stopTimeout must not be null");
+    }
+
+    /**
+     * The retry delay after a row's {@code attempts}-th attempt: doubling, capped.
+     *
+     * @param attempts the attempts made so far, at least 1
+     * @return the delay; never {@code null}
+     */
+    Duration backoff(int attempts) {
+        int doublings = Math.min(attempts - 1, 30);
+        Duration delay = initialBackoff.multipliedBy(1L << doublings);
+        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
+    }
+}
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxTableMissingException.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxTableMissingException.java
new file mode 100644
index 0000000..40e8c49
--- /dev/null
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxTableMissingException.java
@@ -0,0 +1,21 @@
+package ae.gov.dubaicustoms.platform.messaging.outbox;
+
+/**
+ * The outbox table is missing or not readable when the drainer starts. A service that publishes to a table nobody can
+ * read loses its events at the first request, so startup fails instead.
+ *
+ * @since 1.0.0
+ */
+public final class OutboxTableMissingException extends RuntimeException {
+
+    private static final long serialVersionUID = 1L;
+
+    /**
+     * Creates the exception.
+     *
+     * @param cause the database's refusal
+     */
+    public OutboxTableMissingException(Throwable cause) {
+        super("The platform_outbox table is missing or not readable", cause);
+    }
+}
diff --git a/messaging/platform-messaging-outbox/src/main/resources/ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql b/messaging/platform-messaging-outbox/src/main/resources/ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql
new file mode 100644
index 0000000..1e4e41a
--- /dev/null
+++ b/messaging/platform-messaging-outbox/src/main/resources/ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql
@@ -0,0 +1,26 @@
+-- The platform outbox table. Copy this into your own migration sequence (for example V7__platform_outbox.sql);
+-- the platform never creates it, because a runtime user has no DDL rights and a migration inside a platform jar
+-- would collide with your version numbers. Oracle 19c-safe; also runs on H2 for local runs.
+create table platform_outbox (
+    event_id        varchar(64)  not null primary key,
+    event_type      varchar(255) not null,
+    event_version   numeric(10)  not null,
+    destination     varchar(255) not null,
+    msg_key         varchar(255),
+    payload         blob         not null,
+    content_type    varchar(100) not null,
+    headers         clob         not null,
+    principal       varchar(255),
+    created_at      timestamp with time zone not null,
+    next_attempt_at timestamp with time zone not null,
+    attempts        numeric(10)  not null,
+    rejections      numeric(10)  not null,
+    status          varchar(10)  not null,
+    sent_at         timestamp with time zone,
+    last_error      varchar(255)
+);
+
+create index platform_outbox_due on platform_outbox (status, next_attempt_at);
+
+-- With a separate runtime user, grant through the role it holds:
+-- grant select, insert, update, delete on platform_outbox to <runtime role>;
diff --git a/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java b/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java
index d361e9a..1f4eaa0 100644
--- a/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java
+++ b/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java
@@ -1,5 +1,6 @@
 package ae.gov.dubaicustoms.platform.messaging.rabbit;
 
+import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
 import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
 import java.time.Duration;
 import java.util.HashMap;
@@ -7,6 +8,9 @@ import java.util.LinkedHashMap;
 import java.util.Map;
 import java.util.Objects;
 import java.util.concurrent.CopyOnWriteArrayList;
+import java.util.concurrent.ExecutionException;
+import java.util.concurrent.TimeUnit;
+import java.util.concurrent.TimeoutException;
 import org.aopalliance.intercept.MethodInterceptor;
 import org.springframework.amqp.core.BindingBuilder;
 import org.springframework.amqp.core.DirectExchange;
@@ -17,6 +21,7 @@ import org.springframework.amqp.core.Queue;
 import org.springframework.amqp.core.TopicExchange;
 import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
 import org.springframework.amqp.rabbit.connection.ConnectionFactory;
+import org.springframework.amqp.rabbit.connection.CorrelationData;
 import org.springframework.amqp.rabbit.core.RabbitAdmin;
 import org.springframework.amqp.rabbit.core.RabbitTemplate;
 import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
@@ -41,18 +46,26 @@ import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
  * is in addition to, not instead of, {@code platform-messaging-autoconfigure}'s transport-agnostic
  * republish-to-{@code dlq.suffix} fallback (which every provider gets for free).
  *
+ * <p>{@link #send} honours the SPI's "blocks until acknowledged": it waits for the broker's publisher
+ * confirm, and a nack, a returned (unroutable) message or no confirm within the timeout is an
+ * {@link EventPublishException}. The connection factory and template must have publisher confirms, returns
+ * and {@code mandatory} on; the platform defaults them and the constructor refuses anything else.
+ *
  * <p>Thread-safe.
  *
  * @since 0.2.0
  */
 public final class RabbitEventTransport implements EventTransport {
 
+    private static final String UNACKNOWLEDGED = "A send could be lost without anyone knowing. Set ";
+
     private final RabbitTemplate rabbitTemplate;
     private final ConnectionFactory connectionFactory;
     private final RabbitAdmin rabbitAdmin;
     private final int maxAttempts;
     private final Duration backoff;
     private final boolean quorumQueues;
+    private final Duration confirmTimeout;
     private final CopyOnWriteArrayList<SimpleMessageListenerContainer> containers = new CopyOnWriteArrayList<>();
 
     /**
@@ -65,22 +78,39 @@ public final class RabbitEventTransport implements EventTransport {
      * @param backoff pause between redelivery attempts; never {@code null}
      * @param quorumQueues declare quorum queues ({@code x-queue-type=quorum}) instead of classic
      *     queues; opt-in for production clusters
+     * @param confirmTimeout how long a send waits for the broker's confirm; never {@code null}
+     * @throws IllegalStateException if publisher confirms, returns or {@code mandatory} are off
      */
     public RabbitEventTransport(RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, int maxAttempts,
-            Duration backoff, boolean quorumQueues) {
-        this(rabbitTemplate, connectionFactory, new RabbitAdmin(connectionFactory), maxAttempts, backoff, quorumQueues);
+            Duration backoff, boolean quorumQueues, Duration confirmTimeout) {
+        this(rabbitTemplate, connectionFactory, new RabbitAdmin(connectionFactory), maxAttempts, backoff, quorumQueues,
+                confirmTimeout);
     }
 
     // Package-private: lets docker-free unit tests inject a mocked RabbitAdmin instead of one
     // backed by a real broker channel.
     RabbitEventTransport(RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, RabbitAdmin rabbitAdmin,
-            int maxAttempts, Duration backoff, boolean quorumQueues) {
+            int maxAttempts, Duration backoff, boolean quorumQueues, Duration confirmTimeout) {
         this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
         this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
         this.rabbitAdmin = Objects.requireNonNull(rabbitAdmin, "rabbitAdmin must not be null");
         this.maxAttempts = maxAttempts;
         this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
         this.quorumQueues = quorumQueues;
+        this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout must not be null");
+        requireAcknowledgedPublishing(rabbitTemplate, connectionFactory);
+    }
+
+    private static void requireAcknowledgedPublishing(RabbitTemplate template, ConnectionFactory factory) {
+        if (!factory.isPublisherConfirms()) {
+            throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.publisher-confirm-type=correlated");
+        }
+        if (!factory.isPublisherReturns()) {
+            throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.publisher-returns=true");
+        }
+        if (!template.isMandatoryFor(new Message(new byte[0]))) {
+            throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.template.mandatory=true");
+        }
     }
 
     @Override
@@ -99,7 +129,30 @@ public final class RabbitEventTransport implements EventTransport {
         if (key != null) {
             properties.setHeader("key", key);
         }
-        rabbitTemplate.send(dest.exchange(), dest.routingKey(), new Message(value, properties));
+        CorrelationData correlation = new CorrelationData();
+        rabbitTemplate.send(dest.exchange(), dest.routingKey(), new Message(value, properties), correlation);
+        awaitConfirm(destination, correlation);
+    }
+
+    private void awaitConfirm(String destination, CorrelationData correlation) {
+        CorrelationData.Confirm confirm;
+        try {
+            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
+        } catch (TimeoutException e) {
+            throw new EventPublishException("no broker confirm for " + destination + " within " + confirmTimeout, e);
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+            throw new EventPublishException("interrupted waiting for the broker's confirm for " + destination, e);
+        } catch (ExecutionException e) {
+            throw new EventPublishException("failed waiting for the broker's confirm for " + destination, e);
+        }
+        // The broker acks a mandatory message it could not route, after returning it.
+        if (correlation.getReturned() != null) {
+            throw new EventPublishException("unroutable: no queue is bound for " + destination, null, true);
+        }
+        if (!confirm.ack()) {
+            throw new EventPublishException("not confirmed by the broker: " + destination, null, true);
+        }
     }
 
     @Override
diff --git a/messaging/platform-starter-messaging-outbox/pom.xml b/messaging/platform-starter-messaging-outbox/pom.xml
new file mode 100644
index 0000000..497dfc7
--- /dev/null
+++ b/messaging/platform-starter-messaging-outbox/pom.xml
@@ -0,0 +1,42 @@
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+  <modelVersion>4.0.0</modelVersion>
+
+  <parent>
+    <groupId>ae.gov.dubaicustoms.platform</groupId>
+    <artifactId>platform-parent</artifactId>
+    <version>${revision}</version>
+    <relativePath>../../build/platform-parent</relativePath>
+  </parent>
+
+  <artifactId>platform-starter-messaging-outbox</artifactId>
+  <!-- Starters contain no code (CLAUDE.md rule 4): jar packaging like Boot starters, empty jar. -->
+  <packaging>jar</packaging>
+
+  <name>DC Platform :: Messaging :: Starter (Transactional Outbox)</name>
+  <description>
+    Guaranteed publish alongside a database change: OutboxPublisher writes in your transaction, a bounded drainer
+    delivers at least once. Add a transport starter (RabbitMQ) and the outbox table to your migrations.
+  </description>
+
+  <dependencies>
+    <!-- starter -> same-capability autoconfigure + named impl (CLAUDE.md rule 5). No transport: pick one. -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-autoconfigure</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-outbox-autoconfigure</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-messaging-outbox</artifactId>
+      <version>${revision}</version>
+    </dependency>
+  </dependencies>
+</project>
```
