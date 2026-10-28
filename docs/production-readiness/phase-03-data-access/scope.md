# Phase 3 — Database / Data Access: scope

| Item | Value |
|---|---|
| Phase start commit | `60afe86` (tag `hardening/phase-02-closed`) |
| Capability under test | Data access: `platform-data-api`, `platform-data-jpa-autoconfigure`, `platform-starter-data-jpa`, new `platform-starter-data-jpa-oracle`; the data-exception mapping in `platform-errors-autoconfigure`; the archetype `data` feature |
| Database | **Oracle only** (HD3 amended 2026-10-28; PostgreSQL out for now). Production runs **19c**; certification on Oracle 23ai Free restricted to 19c features (HD13) |
| Certified dependencies | core, errors (Phase 1), logging, observability (Phase 2) |
| Fixtures (not certified here) | security, openapi, validation, restclient (archetype baseline, STATE.md Gate F resolution) |
| Phase 0 gaps | G3-01 … G3-07, GX-04 (data-jpa descriptor), HI-0041 |

## 1. Requirements

### R1 Oracle support (G3-01, HD3, HD12, HD13)
1. New POM-only starter `platform-starter-data-jpa-oracle`: `platform-starter-data-jpa` +
   `com.oracle.database.jdbc:ojdbc11` + `org.flywaydb:flyway-database-oracle` (versions from Boot's
   BOM). Listed in the platform BOM. No code.
2. Archetype: the `data` feature gets a `database` property, `h2` (default, unchanged output) or
   `oracle`. With `oracle`, the generated POM uses the Oracle starter and the `prod` profile expects
   an Oracle `spring.datasource.url`. H2 stays the local/test database in both cases. The golden path
   gains a scenario generating with `database=oracle` (build + local boot; no Oracle in the gate).
3. 19c targeting (HD13): container tests and the consumer run set
   `spring.jpa.properties.jakarta.persistence.database-major-version=19`, so Hibernate generates
   19c SQL and DDL (no SQL `BOOLEAN`, no 23ai-only syntax) against the 23ai container. The platform
   does **not** set this for consumers (Hibernate reads the real version from the connection).
4. The existing `PostgresParityIT` stays in the build but is **no certification evidence** (HD3).

### R2 Connection pool and timeouts (G3-02, §7.4)
Contributed as lowest-precedence defaults (property source `platform-data-jpa-defaults`, user values
win). Values are fixed at Gate B (decision log D110) — proposals:
1. Hikari `connection-timeout` 5 s (Hikari default 30 s lets request threads pile up behind an
   exhausted pool); `maximum-pool-size` stays Boot's 10 (sizing documented); `max-lifetime` below
   common firewall/Oracle idle limits (30 min); `keepalive-time` 2 min.
2. `leak-detection-threshold` only under the `local` profile (non-production), never in `prod`.
3. Per-statement timeout: `jakarta.persistence.query.timeout` default; transaction timeout:
   `spring.transaction.default-timeout`. Oracle driver read timeout (`oracle.jdbc.ReadTimeout`) as
   the network-level guard for a hung database, so a statement cannot block a thread forever when
   the database stops answering.
4. Pool exhaustion: a request that cannot get a connection within `connection-timeout` gets a
   ProblemDetail (R4), not a hang; the pool recovers when load drops.

### R3 Transactions
1. `spring.jpa.open-in-view=false` (already contributed; re-verified).
2. Read paths read-only: Spring Data repository read methods run `readOnly` (Spring Data default,
   verified); guidance for service-level query methods (`@Transactional(readOnly = true)`).
3. Rollback on exception verified against Oracle (runtime exception → no row; checked exception per
   Spring rules, documented).

### R4 Exception translation without leaking SQL (G3-04, §7.7)
A data-access advice in `platform-errors-autoconfigure`, active only when Spring's
`DataAccessException` is on the classpath (same pattern as `PlatformConstraintViolationHandler`):
- `DataIntegrityViolationException` (unique, FK, NOT NULL, check) → **409**, stable code, no
  constraint name, SQL, table or driver message in the body;
- optimistic / pessimistic locking failures → **409**, `retryable` per the Phase 1 rule;
- connection-acquisition timeout, query timeout, transaction timeout, resource failure → **503**,
  `retryable=true`;
- any other `DataAccessException` → generic 500 (`DC-CORE-0500`).
The full exception (SQL, constraint) is logged once on the request's trace, through the Phase 2 masks.
Error codes fixed at Gate B (decision log D111).

### R5 Flyway posture (G3-03)
Pinned as defaults: `spring.flyway.clean-disabled=true`, `validate-on-migrate=true`,
`out-of-order=false`, `validate-migration-naming=true` (`V<version>__<description>.sql`). Turning
`out-of-order` on outside the `local` profile fails startup with a `FailureAnalyzer` message.
A failed validation (changed checksum, bad name) blocks startup with a clear message — tested.

### R6 Oracle dialect behaviour (19c feature set)
Container tests prove, against Oracle with major version 19:
1. Identifiers: snake_case names unquoted (stored upper-case); 128-character limit; a name over the
   limit fails clearly at migration time.
2. Keys: sequences (`GenerationType.SEQUENCE`, allocation size matching the sequence `INCREMENT BY`)
   and identity columns both work; documented recommendation.
3. `boolean` → `NUMBER(1)` (19c has no SQL `BOOLEAN`).
4. Time: `Instant`/`OffsetDateTime` round-trip unchanged across JVM and session time zones
   (`hibernate.jdbc.time_zone=UTC`, `TIMESTAMP` vs `TIMESTAMP WITH TIME ZONE` documented).
5. Paging: Spring Data `Pageable` → `OFFSET … FETCH` (no `ROWNUM` wrapping needed on 19c), stable
   with an `ORDER BY`.
6. Oracle treats `''` as `NULL`: an empty string stored in a `NOT NULL` column fails (409 via R4);
   documented.
7. `Money` and `CorrelationId` converters round-trip.

### R7 DDL role vs runtime role (G3-06)
Documented model: Flyway connects as the schema owner (`spring.flyway.user`/`password`), the
application as a runtime user with DML grants only, resolving the owner's tables via
`ALTER SESSION SET CURRENT_SCHEMA` (Hikari `connection-init-sql`) or synonyms. The consumer run
creates both users and verifies that the runtime user **cannot** `ALTER` or `DROP` (ORA error), while
the application works.

### R8 Health, metrics, observation (§7.5, §7.9)
1. `db` reports in the `dependencies` group (Phase 2, D104), never in readiness — re-verified with
   Oracle stopped and slow.
2. Hikari pool meters (`hikaricp.connections.*`) and Spring Data repository timings
   (`spring.data.repository.invocations`) present with bounded tags (pool name, repository, method,
   state — no IDs, no SQL).
3. JDBC work is visible in traces only as far as Boot instruments it; no platform span with an
   unbounded attribute.

### R9 Overrides, kill switch, descriptor (§7.1–7.3, GX-04)
1. `dataJpaCapabilityDescriptor` gets its missing name guard (GX-04).
2. Every data bean has a tested override; config-default snapshot for `dc.platform.data.jpa.*` and
   the EPP defaults; kill switch `dc.platform.data.jpa.enabled` tier `DEFAULT_ON` (tested).

### R10 Shutdown and startup (§7.10, §7.11)
1. SIGTERM: an in-flight transaction completes (or rolls back) within the grace period; the pool
   closes (`HikariPool … Shutdown completed`); exit 143.
2. Startup with Oracle slow: starts within budget (time recorded). Startup with Oracle down: fails
   with a clear message within a bounded time — no hang.

### R11 Consumer-visible changes (CHANGELOG + migration notes)
Pool `connection-timeout` 30 s → 5 s (and the other R2 defaults); data exceptions 500 → 409/503 with
new codes; `validate-migration-naming=true` can fail startup on badly named existing migrations;
new Oracle starter and archetype `database` property.

## 2. Exclusions (and where they are handled)

| Item | Where |
|---|---|
| PostgreSQL (and every database other than Oracle) | later, separately (HD3 amended, HD12) |
| Oracle RAC failover (FCF/ONS, Application Continuity, UCP) | `BLOCKED` (HD4) |
| Oracle 19c on a real instance | not container-verified (HD13); Phase 8 environment if available |
| Transactional outbox | Phase 4 |
| Multi-tenancy / schema-per-tenant, R2DBC, JDBC-only services | outside the initiative |

## 3. Applicable §7 contracts

| § | Contract | Phase 3 position |
|---|---|---|
| 7.1 | Real override | R9 |
| 7.2 | Config-default snapshot | `dc.platform.data.jpa.*` + EPP defaults (R2, R5) |
| 7.3 | Kill-switch tier | `DEFAULT_ON` (R9) |
| 7.4 | Outage posture | Oracle unavailable / slow / erroring / exhausted pool: **fail-fast** per request (503 within the connection or statement timeout), app stays up and ready, recovers without restart |
| 7.5 | Health semantics | R8.1 |
| 7.6 | Context propagation | trace and MDC on data-access log lines and error bodies (request threads; no new thread boundary) |
| 7.7 | Error surface | R4 |
| 7.8 | Redaction | SQL and bind values never in responses; logged SQL passes the Phase 2 masks; no credentials in startup/pool logs |
| 7.9 | Observability | R8.2–8.3 |
| 7.10 | Graceful shutdown | R10.1 |
| 7.11 | Startup under adversity | R10.2 |

## 4. Acceptance criteria

| ID | Criterion | Level |
|---|---|---|
| AC1 | Oracle starter resolves driver + Flyway module; a generated `database=oracle` service boots in `prod` against Oracle and migrates | CONTAINER + CONSUMER |
| AC2 | CRUD with constraints on Oracle (19c mode): commit visible, runtime exception rolls back | CONTAINER + CONSUMER |
| AC3 | Unique / FK / NOT NULL / check / empty-string violations → 409 ProblemDetail with code and `traceId`, no SQL, constraint or table name; full detail logged on the trace | SLICE + CONTAINER + CONSUMER |
| AC4 | Pool exhaustion: requests beyond the pool get 503 within `connection-timeout`; no thread pile-up beyond bounds; pool recovers | CONTAINER + CONSUMER |
| AC5 | Oracle slow (Toxiproxy latency): statement/read timeout → 503; request threads bounded; recovery | CONSUMER |
| AC6 | Oracle down: DB requests 503 fast, others 200; readiness/liveness UP, `dependencies` DOWN; Oracle back → recovery without restart | CONSUMER |
| AC7 | Flyway: changed checksum and bad migration name block startup with a clear message; `out-of-order=true` outside `local` fails with a `FailureAnalyzer` message; `clean` refused | SLICE + CONTAINER |
| AC8 | Dialect: identifiers, sequence/identity, `boolean` → `NUMBER(1)`, `Instant` round-trip across zones, `OFFSET … FETCH` paging, `''` = `NULL`, converters (R6) | CONTAINER |
| AC9 | Runtime user cannot `ALTER`/`DROP`; app works through `CURRENT_SCHEMA`; Flyway runs as owner | CONSUMER |
| AC10 | Pool defaults in effect (`/actuator/env` source `platform-data-jpa-defaults`); `leak-detection-threshold` only under `local`; user values win | UNIT + SLICE |
| AC11 | Hikari and repository meters present, bounded tags; no SQL or ID tag values | SLICE + CONSUMER |
| AC12 | Override, kill-switch and config-default snapshot tests for every data bean and EPP default; descriptor name guard | SLICE |
| AC13 | SIGTERM with an in-flight transaction: completes, pool shut down, exit 143 | CONSUMER |
| AC14 | Startup: Oracle slow → within budget (recorded); Oracle down → clear failure within a bounded time | CONSUMER |
| AC15 | Downstream `-amd test-compile` green; golden path 8/8 + the Oracle generation scenario | build |
| AC16 | Phase 1 and Phase 2 consumers still pass against Phase 3 code | CONSUMER |

P0: R1, R2, R4, R5, R6, AC4–AC6. P1: R7, R8.2, R10.2. Deferred: none.

## 5. Surface inventory (callers of what R1, R2, R4, R5 change)

| Surface | Callers | Phase 3 action |
|---|---|---|
| archetype `data` feature (`pom.xml` comment "add its JDBC driver here", prod `datasource` block) | `tooling/platform-service-archetype` templates, golden path `data*` scenarios, Phase 2 consumer (adds PostgreSQL driver + Flyway module itself) | `database` property; Phase 2 harness keeps PostgreSQL as a fixture |
| `PlatformExceptionHandler` catch-all (data exceptions → 500 today) | errors tests, Phase 1 consumer (no data paths), Phase 2 consumer AC10 (`DC-CORE-0500` on DB down) | Phase 2 harness expectation for DB-down changes to 503 |
| `platform-data-jpa-defaults` property source | `PlatformDataJpaEnvironmentPostProcessorTest`, `docs/modules/data.md` | extended (R2, R5) |
| `PostgresParityIT` | data-jpa tests (`-Pdocker`) | kept, not evidence |

## 6. Environment prerequisites

JDK 25, Maven 3.9, Docker with ≥ 4 GB for containers (Docker VM here: 7.7 GiB; host has 15.9 GB,
about 4–5 GB free — Oracle and the consumer app run **one at a time**).
- `gvenzl/oracle-free:23-slim-faststart` (present locally, 6.46 GB image; about 2 GB RAM at runtime),
  pinned by digest at Gate F; Testcontainers `oracle-free` module for CONTAINER tests.
- Toxiproxy (`ghcr.io/shopify/toxiproxy:2.9.0`, as in Phase 2) for slow/down states.
- Disk: C: has 4.6 GB free; consumer and golden-path work directories go to D:.

## 7. Reproducing current behaviour (baseline `60afe86`)

- No Oracle anywhere: no `ojdbc`/`oracle` in any POM or `src/main` (G3-01).
- `PlatformDataJpaEnvironmentPostProcessor` contributes only `open-in-view`, Hibernate batching and
  `jdbc.time_zone`; Hikari runs on its defaults (`connection-timeout` 30 s, no leak detection), no
  statement or transaction timeout (G3-02).
- Flyway keys left to Boot/Flyway defaults; no naming validation (G3-03).
- `PlatformExceptionHandler` has no data-access mapping: a unique-key violation is a generic 500
  `DC-CORE-0500` (G3-04).
- One `@Tag("docker")` test (`PostgresParityIT`), PostgreSQL only (G3-05).
- `dataJpaCapabilityDescriptor` has no name guard (GX-04).
- Archetype `data` feature: prod with a real database fails on Flyway 10+ without the database
  module (HI-0041).

## 8. Baseline targeted tests (Gate A, before any change)

`mvn -o -T1 -B -Pdocker -pl data/platform-data-api,data/platform-data-jpa-autoconfigure -am verify`
→ exit 0, BUILD SUCCESS, 7 reactor modules; tests: build-tools 38, core-api 37, security-api 4,
data-api 11, data-jpa-autoconfigure 24 + 1 `docker` (`PostgresParityIT`, real PostgreSQL 16
container); 0 failures, 0 errors, **0 skipped** (raw log `evidence-raw/gate-a-baseline-tests.log`,
sha256 `7c893394…e0410f`, not committed).

## 9. Decisions to record (decision log, at Gate B)

| ID | Decision |
|---|---|
| D110 | Pool and timeout defaults (values per R2) |
| D111 | Data-exception mapping and error codes (R4); lives in errors-autoconfigure behind `@ConditionalOnClass(DataAccessException)` |
| D112 | Flyway posture pins and the `out-of-order` startup guard (R5) |
| D113 | Archetype `database` property (`h2` / `oracle`) and the per-database starter pattern (HD12) |

## 10. Scope amendments

None yet.
