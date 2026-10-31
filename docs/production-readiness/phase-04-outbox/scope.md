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
1. `SmartLifecycle`; stops before the transport and pool close; a stop ends the batch after the send in flight and commits what was sent (agreed in round 2).
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
| AC8 | Two app instances: no event sent twice absent a crash or a timed-out send (a send past its confirm timeout may yet arrive, then repeats), none lost | CONSUMER |
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
| A3 | 2026-10-31 | R4.3 and AC9: rows park after `max-rejections` (the sink refused the message), not the plan's "max attempts". | Attempts also count outages, so a long outage would park good events and recovery would not deliver them. Agreed in round 1 (`codex/r1-response.md`). |
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
