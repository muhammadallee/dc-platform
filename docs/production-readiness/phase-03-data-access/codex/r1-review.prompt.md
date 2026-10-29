# Independent review — DC Platform capability hardening, Phase 3 (Data Access, Oracle), Round 1

You are the independent reviewer for a production-hardening phase of an internal Spring Boot 4.1 /
Java 25 platform ("chassis"). Your working directory is a checkout of the reviewed commit; read any file
you need. Review ONLY against the phase scope below (requirements + acceptance criteria) and the diff
against the phase start commit. You are not given the implementer's self-assessment on purpose.

Hunt for real defects: correctness, security (SQL, table, constraint or credential details leaking into
error responses or logs), wrong HTTP status or error code for a data failure, retry hints on failures that
are not retryable (or missing on ones that are), connection-pool exhaustion and unbounded waits, timeouts
that do not actually bound a statement, transaction and Flyway behaviour that is unsafe in production,
Oracle 19c incompatibilities (production runs 19c; tests run on 23ai Free with Hibernate pinned to 19),
broken back-off/override of platform defaults, ordering bugs between Spring bean post-processors and
property sources, archetype generation defects, untested claims, regressions in modules that depend on
the changed ones, and places where the implementation does not meet a stated acceptance criterion.

## Severity scale

- **Critical** — data loss/corruption, security bypass, cross-caller data exposure, a defect that takes
  down all replicas.
- **High** — incorrect behaviour under a realistic failure (outage, slowness, restart, concurrency),
  leaked internals in responses/logs, broken back-off/override, misleading health signal.
- **Medium** — degraded operability/DX with a workaround; missing observability on a failure path.
- **Low** — cosmetic, docs, naming.

## Required output format

For EACH finding:

```
### F<n> — <short title>
- Severity: Critical | High | Medium | Low
- Location: <path>:<line>
- Failure scenario: <concrete inputs/state → wrong output/crash>
- Proof or reasoning: <code references; why it happens>
- Proposed fix: <specific>
```

Then a final section `## Acceptance criteria not met` listing any AC from the scope that the diff does
not satisfy or does not test, with the reason. If you find nothing for a category, say so explicitly.
Do not pad with style nitpicks.

## Phase scope (requirements + acceptance criteria)

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
1. New POM-only starter `platform-starter-data-jpa-oracle`. Starter → starter is forbidden (CLAUDE.md
   rule 5), so it lists the data-jpa starter's dependencies itself (`platform-data-jpa-autoconfigure`,
   `spring-boot-starter-data-jpa`, `flyway-core`, `spring-boot-flyway`) plus
   `com.oracle.database.jdbc:ojdbc11` and `org.flywaydb:flyway-database-oracle` (versions from Boot's
   BOM). Listed in the platform BOM. No code.
2. Archetype: the `data` feature gets a `database` property, `h2` (default, unchanged output) or
   `oracle`. With `oracle`, the generated POM uses the Oracle starter and the `prod` profile expects
   an Oracle `spring.datasource.url`. H2 stays the local/test database in both cases. The golden path
   gains a scenario generating with `database=oracle` (build + local boot; no Oracle in the gate).
3. 19c targeting (HD13): container tests and the consumer run set
   `spring.jpa.properties.jakarta.persistence.database-major-version=19`, so Hibernate generates
   19c SQL and DDL (no SQL `BOOLEAN`, no 23ai-only syntax) against the 23ai container. The platform
   does **not** set this for consumers (Hibernate reads the real version from the connection). The
   first container test asserts the **resolved** dialect version is 19 (read from the dialect, not
   inferred from configuration); if the setting does not stick, a fixed-version dialect is used.
   The pin governs Hibernate-generated SQL only: Flyway migrations, native queries and
   `connection-init-sql` run raw against 23ai (which accepts `BOOLEAN` columns, `SELECT` without
   `FROM DUAL`, `IF [NOT] EXISTS`), and Flyway itself sees version 23. Every hand-written SQL file is
   kept 19c-safe; these limits are recorded under the HD13 residual.
4. The existing `PostgresParityIT` stays in the build but is **no certification evidence** (HD3).

### R2 Connection pool and timeouts (G3-02, §7.4)
Contributed as lowest-precedence defaults (property source `platform-data-jpa-defaults`, user values
win). Oracle-only keys (`oracle.jdbc.*` connection properties) are contributed only when
`spring.datasource.url` starts with `jdbc:oracle:` — never on driver presence, because the
`database=oracle` archetype variant runs on H2 locally with `ojdbc11` on the classpath. Values are
fixed at Gate B after measuring each lever against Toxiproxy (decision log D110) — proposals:
1. Hikari `connection-timeout` 5 s (Hikari default 30 s lets request threads pile up behind an
   exhausted pool); `maximum-pool-size` stays Boot's 10 (sizing documented); `max-lifetime` below
   common firewall/Oracle idle limits (30 min); `keepalive-time` 2 min.
2. `leak-detection-threshold` only under the `local` profile (non-production), never in `prod`.
3. Per-statement timeout: `jakarta.persistence.query.timeout` default; transaction timeout:
   `spring.transaction.default-timeout`. Oracle driver read timeout (`oracle.jdbc.ReadTimeout`) as
   the network-level guard for a hung database, so a statement cannot block a thread forever when
   the database stops answering. The read timeout sits above the statement timeout, so a legitimately
   slow query hits the statement timeout first. `spring.transaction.default-timeout` applies to every
   transaction manager Boot creates (Phase 4's drainer inherits it; noted in D110).
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
- `DataIntegrityViolationException` (unique, FK, NOT NULL, check) → **409**, stable code, a **fixed**
  `detail` string (the Phase 1 rule that 4xx keep the exception message does not apply: it carries the
  constraint name and SQL), no
  constraint name, SQL, table or driver message in the body;
- optimistic / pessimistic locking failures → **409**, `retryable` per the Phase 1 rule;
- connection-acquisition timeout, query timeout, transaction timeout, resource failure → **503**,
  `retryable=true`;
- any other `DataAccessException` → generic 500 (`DC-CORE-0500`).
The full exception (SQL, constraint) is logged once on the request's trace, through the Phase 2 masks.
Mapped from the exception types observed on real paths (`save`, `saveAndFlush`, commit-time flush in
a `@Transactional` service, direct `EntityManager` use, pool timeout at transaction begin), not from
guessed class names. errors is Phase 1 certified: full errors tests and the Phase 1 consumer re-run
(§3.10).
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

## 9. Decisions to record (decision log, at Gate B)

| ID | Decision |
|---|---|
| D110 | Pool and timeout defaults (values per R2) |
| D111 | Data-exception mapping and error codes (R4); lives in errors-autoconfigure behind `@ConditionalOnClass(DataAccessException)` |
| D112 | Flyway posture pins and the `out-of-order` startup guard (R5) |
| D113 | Archetype `database` property (`h2` / `oracle`) and the per-database starter pattern (HD12) |

## 10. Scope amendments

None yet.

## Owner decisions
- HD3 amended: Oracle is the only certified database in this phase; PostgreSQL is out for now.
- HD12: per-database starters are POM-only (`platform-starter-data-jpa-oracle`); only Oracle exists for now.
- HD13: production runs Oracle 19c. Certification runs on Oracle 23ai Free with Hibernate pinned to 19
  (`spring.jpa.properties.jakarta.persistence.database-major-version=19`); 19c itself is not
  container-verified, so hand-written SQL must be reviewed for 19c.
- Oracle RAC failover (FCF/ONS, Application Continuity) is out of scope (no environment).

## Diff: phase start 60afe86 .. reviewed commit 2c3fc78 (docs/production-readiness excluded)

```diff
diff --git a/.gitignore b/.gitignore
index ee40450..98ddadb 100644
--- a/.gitignore
+++ b/.gitignore
@@ -10,3 +10,6 @@ docs/apidocs/
 
 # Hardening evidence: full raw logs stay local (referenced by path + SHA-256)
 evidence-raw/
+
+# Git Bash crash dumps (Windows)
+bash.exe.stackdump
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 70b730c..cb8c31e 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -69,7 +69,28 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
 - JSON mode switches Boot's startup banner off (`spring.main.banner-mode=off`, overridable) so every
   stdout line is JSON (D108).
 
+### Breaking (capability hardening, phase 3 — data access, Oracle)
+- **Data-access failures are no longer a generic 500.** A unique, foreign-key, NOT NULL or check
+  violation is **409 `DC-DATA-0409`**; an optimistic or pessimistic locking failure is
+  **409 `DC-DATA-0410`**; no connection within the pool timeout, a statement or transaction timeout,
+  or a lost connection is **503 `DC-DATA-0503`** (`retryable: true`). The detail is fixed (never SQL,
+  table or constraint names); the full exception is logged at WARN on the request's trace. Raw
+  JPA/Hibernate/JDBC exceptions from code that uses the `EntityManager` directly are classified too
+  (by cause chain and SQLState). An application `@ExceptionHandler` for these types still wins (D111).
+- **Bounded data-access waits** (D110, lowest-precedence defaults): Hikari `connection-timeout` 5 s
+  (was Hikari's 30 s); `spring.transaction.default-timeout` 30 s (was unbounded) — a longer batch
+  transaction needs an explicit `@Transactional(timeout = …)`; Oracle pools get the driver's
+  `oracle.jdbc.ReadTimeout` 60 s and `oracle.net.CONNECT_TIMEOUT` 10 s; leak detection (20 s) under
+  `local`.
+- **Flyway naming is validated**: `spring.flyway.validate-migration-naming=true`, so a migration file not
+  named `V<version>__<description>.sql` now fails startup instead of being silently skipped. Out-of-order
+  migrations outside the `local` profile fail startup with a diagnostic; `clean-disabled`,
+  `validate-on-migrate` and `out-of-order=false` are pinned (D112).
+
 ### Added
+- Data: `platform-starter-data-jpa-oracle` — the data-jpa stack plus the Oracle JDBC driver and
+  `flyway-database-oracle` (HD12). Archetype: `-Ddatabase=oracle` (with the `data` feature) generates a
+  service on it; `-Ddatabase` defaults to `h2` (D113).
 - Logging: `dc.platform.logging.mdc.include` (strict MDC mode), `dc.platform.logging.redaction.enabled`,
   `dc.platform.logging.redaction.patterns`.
 - Observability: `dependencies` health group; docs for collector tail sampling of errored requests.
diff --git a/build/platform-bom/pom.xml b/build/platform-bom/pom.xml
index 5931f2d..21c02dd 100644
--- a/build/platform-bom/pom.xml
+++ b/build/platform-bom/pom.xml
@@ -76,6 +76,7 @@
       <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-data-api</artifactId><version>${revision}</version></dependency>
       <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-data-jpa-autoconfigure</artifactId><version>${revision}</version></dependency>
       <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-starter-data-jpa</artifactId><version>${revision}</version></dependency>
+      <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-starter-data-jpa-oracle</artifactId><version>${revision}</version></dependency>
       <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-cache-api</artifactId><version>${revision}</version></dependency>
       <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-cache-autoconfigure</artifactId><version>${revision}</version></dependency>
       <dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-starter-cache-caffeine</artifactId><version>${revision}</version></dependency>
diff --git a/data/platform-data-jpa-autoconfigure/pom.xml b/data/platform-data-jpa-autoconfigure/pom.xml
index dba05fc..756a776 100644
--- a/data/platform-data-jpa-autoconfigure/pom.xml
+++ b/data/platform-data-jpa-autoconfigure/pom.xml
@@ -82,18 +82,19 @@
       <artifactId>h2</artifactId>
       <scope>test</scope>
     </dependency>
-    <!-- Flyway engine + Boot 4's Flyway integration on the test classpath (the same pair the starter
-         ships), so the presence guard's happy path and a real migration run are exercised; the absent
-         branches are tested with a FilteredClassLoader. -->
+    <!-- Flyway engine + Boot 4's Flyway integration (the same pair the starter ships). Optional: the
+         out-of-order guard (D112) is a FlywayConfigurationCustomizer, registered only when Flyway is
+         present (@ConditionalOnClass); the presence guard's absent branches are tested with a
+         FilteredClassLoader. -->
     <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-core</artifactId>
-      <scope>test</scope>
+      <optional>true</optional>
     </dependency>
     <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-flyway</artifactId>
-      <scope>test</scope>
+      <optional>true</optional>
     </dependency>
     <!-- @Tag("docker") Postgres parity test only: a real Postgres via Testcontainers. -->
     <dependency>
@@ -111,5 +112,22 @@
       <artifactId>testcontainers-junit-jupiter</artifactId>
       <scope>test</scope>
     </dependency>
+    <!-- @Tag("docker") Oracle tests (HD3, HD13): the same driver + Flyway module pair the Oracle
+         starter ships, and an Oracle Free container. -->
+    <dependency>
+      <groupId>com.oracle.database.jdbc</groupId>
+      <artifactId>ojdbc11</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.flywaydb</groupId>
+      <artifactId>flyway-database-oracle</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.testcontainers</groupId>
+      <artifactId>testcontainers-oracle-free</artifactId>
+      <scope>test</scope>
+    </dependency>
   </dependencies>
 </project>
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java
index 9540bdf..a5c1d25 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java
@@ -2,7 +2,9 @@ package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;
 
 import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
 import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.AuditorAwareProvider;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayOutOfOrderGuard;
 import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayPresenceCheck;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleConnectionDefaults;
 import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
 import jakarta.persistence.EntityManagerFactory;
 import java.util.Optional;
@@ -17,13 +19,15 @@ import org.springframework.boot.context.properties.EnableConfigurationProperties
 import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
 import org.springframework.context.annotation.Bean;
 import org.springframework.context.annotation.Configuration;
+import org.springframework.core.env.Environment;
 import org.springframework.data.domain.AuditorAware;
 import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
 
 /*
  * Activates when: JPA (jakarta.persistence.EntityManagerFactory + Spring Data AuditorAware) on the
  *                 classpath AND dc.platform.data.jpa.enabled != false.
- * Backs off when: the user defines the same-named bean (platformAuditorAware / the Flyway check) or
+ * Backs off when: the user defines the same-named bean (platformAuditorAware / the Flyway check /
+ *                 dataJpaCapabilityDescriptor) or
  *                 already enables JPA auditing themselves (jpaAuditingHandler present).
  * Beans: platformAuditorAware — AuditorAware<String>; resolves the current user's subject when the
  *                 security capability is present and a request is authenticated, otherwise "system".
@@ -32,7 +36,11 @@ import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
  *                 consumer never loads a security type;
  *        platformFlywayPresenceCheck — fails startup when require-migrations is on but Flyway is
  *                 absent, with an actionable message;
- *        dataJpaCapabilityDescriptor — one line in the startup capability banner.
+ *        dataJpaCapabilityDescriptor — one line in the startup capability banner;
+ *        platformOracleConnectionDefaults — adds the Oracle driver read/connect timeouts to a Hikari
+ *                 pool whose URL is jdbc:oracle: (only when HikariCP is present; D110);
+ *        platformFlywayOutOfOrderGuard — fails startup when Flyway out-of-order migrations are on
+ *                 outside the local profile (only when Flyway is present; D112).
  * Nested JpaAuditingConfiguration enables @EnableJpaAuditing only once a real EntityManagerFactory
  *        exists (@ConditionalOnBean) — this is why the class is ordered after HibernateJpa
  *        AutoConfiguration — so a context without JPA infrastructure still starts cleanly.
@@ -50,7 +58,9 @@ public class PlatformDataJpaAutoConfiguration {
         return new FlywayPresenceCheck(properties.requireMigrations(), getClass().getClassLoader());
     }
 
+    // CapabilityDescriptor is a shared type (one per capability), so the override is by name (D97).
     @Bean
+    @ConditionalOnMissingBean(name = "dataJpaCapabilityDescriptor")
     CapabilityDescriptor dataJpaCapabilityDescriptor() {
         return new CapabilityDescriptor("data-jpa", "ACTIVE", "");
     }
@@ -79,6 +89,36 @@ public class PlatformDataJpaAutoConfiguration {
     static class JpaAuditingConfiguration {
     }
 
+    /*
+     * Oracle driver read/connect timeouts on a Hikari pool with a jdbc:oracle: URL (D110). Static: a
+     * BeanPostProcessor must not pull the enclosing configuration in early.
+     */
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
+    static class OracleConnectionDefaultsConfiguration {
+
+        @Bean
+        @ConditionalOnMissingBean(name = "platformOracleConnectionDefaults")
+        static OracleConnectionDefaults platformOracleConnectionDefaults() {
+            return new OracleConnectionDefaults();
+        }
+    }
+
+    /*
+     * Out-of-order migrations only under the local profile (D112). Nested so Flyway types are only
+     * touched when Flyway is on the classpath.
+     */
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnClass(name = "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer")
+    static class FlywayGuardConfiguration {
+
+        @Bean
+        @ConditionalOnMissingBean(name = "platformFlywayOutOfOrderGuard")
+        FlywayOutOfOrderGuard platformFlywayOutOfOrderGuard(Environment environment) {
+            return new FlywayOutOfOrderGuard(environment);
+        }
+    }
+
     /*
      * Security-aware auditor: contributed only when CurrentUserAccessor is on the classpath. Uses an
      * ObjectProvider so an absent accessor bean falls back to "system".
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
index af31a5c..09483d7 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
@@ -1,5 +1,6 @@
 package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;
 
+import java.util.Arrays;
 import java.util.LinkedHashMap;
 import java.util.Map;
 import org.springframework.boot.SpringApplication;
@@ -10,7 +11,9 @@ import org.springframework.core.env.MapPropertySource;
 
 /**
  * Contributes the platform's Hibernate tuning as standard {@code spring.jpa.*} defaults: open-in-view
- * off, batched inserts/updates (size 50), and a UTC jdbc time zone. Snake_case physical naming is
+ * off, batched inserts/updates (size 50), and a UTC jdbc time zone — plus pool and timeout bounds
+ * (D110: 5 s connection wait, 30 s transactions, leak detection under {@code local}) and Flyway's
+ * safe settings pinned (D112). Snake_case physical naming is
  * Boot's own default, so it is deliberately left unset rather than pinned to a Boot-version-specific
  * strategy class.
  *
@@ -43,9 +46,43 @@ public class PlatformDataJpaEnvironmentPostProcessor implements EnvironmentPostP
         defaults.put("spring.jpa.properties.hibernate.order_updates", "true");
         // Persist and read timestamps in UTC regardless of the JVM/db session zone.
         defaults.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
+        poolAndTimeoutDefaults(environment, defaults);
+        flywayDefaults(defaults);
         environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
     }
 
+    /*
+     * D110, measured against Oracle (OracleExceptionPathsIT, OracleTimeoutLeversIT):
+     * - a request waits at most 5 s for a pooled connection (Hikari's 30 s lets request threads pile
+     *   up behind an exhausted pool); it then gets a 503 (D111);
+     * - every transaction — Spring Data repository methods included — is bounded at 30 s; Spring
+     *   hands the remaining time to each statement, which the driver cancels (ORA-01013);
+     * - the Oracle driver's read and connect timeouts are added to the pool itself, keyed on its own
+     *   URL (internal.OracleConnectionDefaults): the URL may come from a property source added after
+     *   this post-processor ran;
+     * - leak detection (a stack trace for a connection held over 20 s) only under the local profile.
+     * Hikari's own keepalive (2 min) and max lifetime (30 min) defaults are kept.
+     */
+    private static void poolAndTimeoutDefaults(ConfigurableEnvironment environment, Map<String, Object> defaults) {
+        defaults.put("spring.datasource.hikari.connection-timeout", "5000");
+        defaults.put("spring.transaction.default-timeout", "30s");
+        if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
+            defaults.put("spring.datasource.hikari.leak-detection-threshold", "20000");
+        }
+    }
+
+    /*
+     * D112: Flyway's safe defaults pinned rather than inherited, so a Flyway upgrade cannot loosen them —
+     * never clean a database, validate applied migrations before migrating, apply in version order
+     * only, and reject files that do not follow V<version>__<description>.sql instead of skipping them.
+     */
+    private static void flywayDefaults(Map<String, Object> defaults) {
+        defaults.put("spring.flyway.clean-disabled", "true");
+        defaults.put("spring.flyway.validate-on-migrate", "true");
+        defaults.put("spring.flyway.out-of-order", "false");
+        defaults.put("spring.flyway.validate-migration-naming", "true");
+    }
+
     @Override
     public int getOrder() {
         // Lowest precedence: application.yml/profile values must win over these defaults.
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/FlywayOutOfOrderGuard.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/FlywayOutOfOrderGuard.java
new file mode 100644
index 0000000..64d0f1b
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/FlywayOutOfOrderGuard.java
@@ -0,0 +1,40 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
+
+import java.util.Arrays;
+import org.flywaydb.core.api.configuration.FluentConfiguration;
+import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
+import org.springframework.core.Ordered;
+import org.springframework.core.env.Environment;
+
+/**
+ * Refuses out-of-order migrations outside the {@code local} profile (D112): applying an older version
+ * after a newer one means environments end up with the same versions in different orders. Checked on
+ * Flyway's effective configuration while it is built — before anything migrates — so the property,
+ * {@code spring.flyway.*}, a {@code flyway.conf} or another customizer turning it on are all caught.
+ * Runs last among the customizers. Thread-safe.
+ */
+public final class FlywayOutOfOrderGuard implements FlywayConfigurationCustomizer, Ordered {
+
+    private final Environment environment;
+
+    /**
+     * @param environment the application environment (active profiles); never {@code null}
+     */
+    public FlywayOutOfOrderGuard(Environment environment) {
+        this.environment = environment;
+    }
+
+    @Override
+    public void customize(FluentConfiguration configuration) {
+        if (configuration.isOutOfOrder() && !Arrays.asList(environment.getActiveProfiles()).contains("local")) {
+            throw new OutOfOrderMigrationsException("Flyway out-of-order migrations are enabled outside the "
+                    + "'local' profile (active profiles: " + Arrays.toString(environment.getActiveProfiles())
+                    + "); a shared database would apply versions in a different order than other environments.");
+        }
+    }
+
+    @Override
+    public int getOrder() {
+        return Ordered.LOWEST_PRECEDENCE;
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
new file mode 100644
index 0000000..75ceff4
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
@@ -0,0 +1,49 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
+
+import com.zaxxer.hikari.HikariDataSource;
+import java.util.Map;
+import org.springframework.beans.factory.config.BeanPostProcessor;
+import org.springframework.core.Ordered;
+
+/**
+ * Adds the Oracle driver's network bounds to a Hikari pool whose JDBC URL is {@code jdbc:oracle:}
+ * (D110): a socket read timeout as the backstop for what a statement cancel cannot stop (a PL/SQL or
+ * lock wait, a dead network) and a connect timeout for an unreachable host.
+ *
+ * <p>Applied to the pool bean after Boot has bound {@code spring.datasource.hikari.*} and before the
+ * pool starts, keyed on the pool's own URL — not on an environment property (the URL can come from a
+ * property source added after environment post-processing) and not on driver presence (an
+ * Oracle-starter service runs on H2 locally). A value the application already set, as a
+ * {@code data-source-properties} entry or a JVM system property, is left alone.
+ *
+ * <p>Thread-safe; stateless.
+ */
+public final class OracleConnectionDefaults implements BeanPostProcessor, Ordered {
+
+    /** Above the 30 s transaction timeout, so a slow query is cancelled by that first. */
+    static final Map<String, String> DEFAULTS = Map.of(
+            "oracle.jdbc.ReadTimeout", "60000",
+            "oracle.net.CONNECT_TIMEOUT", "10000");
+
+    @Override
+    public Object postProcessBeforeInitialization(Object bean, String beanName) {
+        if (bean instanceof HikariDataSource pool && isOracle(pool.getJdbcUrl())) {
+            DEFAULTS.forEach((key, value) -> {
+                if (!pool.getDataSourceProperties().containsKey(key) && System.getProperty(key) == null) {
+                    pool.addDataSourceProperty(key, value);
+                }
+            });
+        }
+        return bean;
+    }
+
+    private static boolean isOracle(String url) {
+        return url != null && url.startsWith("jdbc:oracle:");
+    }
+
+    @Override
+    public int getOrder() {
+        // After the configuration-properties binding post-processor has populated the pool.
+        return Ordered.LOWEST_PRECEDENCE;
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OutOfOrderMigrationsException.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OutOfOrderMigrationsException.java
new file mode 100644
index 0000000..c15fea5
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OutOfOrderMigrationsException.java
@@ -0,0 +1,12 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
+
+// Dedicated startup-failure type so OutOfOrderMigrationsFailureAnalyzer can target exactly the
+// "out-of-order migrations outside local" case (D112). An illegal startup state, like MissingFlywayException.
+public final class OutOfOrderMigrationsException extends IllegalStateException {
+
+    private static final long serialVersionUID = 1L;
+
+    public OutOfOrderMigrationsException(String message) {
+        super(message);
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OutOfOrderMigrationsFailureAnalyzer.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OutOfOrderMigrationsFailureAnalyzer.java
new file mode 100644
index 0000000..d9b013c
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OutOfOrderMigrationsFailureAnalyzer.java
@@ -0,0 +1,17 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
+
+import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
+import org.springframework.boot.diagnostics.FailureAnalysis;
+
+// Turns the out-of-order guard's startup failure into a Boot diagnostic whose Action names the fix
+// (D112). Registered in META-INF/spring.factories.
+public class OutOfOrderMigrationsFailureAnalyzer extends AbstractFailureAnalyzer<OutOfOrderMigrationsException> {
+
+    @Override
+    protected FailureAnalysis analyze(Throwable rootFailure, OutOfOrderMigrationsException cause) {
+        String action = "Remove spring.flyway.out-of-order=true (and any flyway.outOfOrder setting) for this "
+                + "environment and give the late migration a version above the latest applied one. Out-of-order "
+                + "runs are allowed only under the 'local' profile. See docs/modules/data.md#migrations.";
+        return new FailureAnalysis(cause.getMessage(), action, cause);
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/main/resources/META-INF/spring.factories b/data/platform-data-jpa-autoconfigure/src/main/resources/META-INF/spring.factories
index 1e3a6e0..a3e7281 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/resources/META-INF/spring.factories
+++ b/data/platform-data-jpa-autoconfigure/src/main/resources/META-INF/spring.factories
@@ -3,6 +3,8 @@
 org.springframework.boot.env.EnvironmentPostProcessor=\
 ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaEnvironmentPostProcessor
 
-# FailureAnalyzer (phase-16 A.2): teaches the "JPA on, Flyway missing" startup failure.
+# FailureAnalyzers: the "JPA on, Flyway missing" startup failure (phase-16 A.2) and out-of-order
+# migrations outside the local profile (D112).
 org.springframework.boot.diagnostics.FailureAnalyzer=\
-ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MissingFlywayFailureAnalyzer
+ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MissingFlywayFailureAnalyzer,\
+ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OutOfOrderMigrationsFailureAnalyzer
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/FlywayPostureTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/FlywayPostureTest.java
new file mode 100644
index 0000000..f745cc4
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/FlywayPostureTest.java
@@ -0,0 +1,89 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;
+
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.assertThatThrownBy;
+
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OutOfOrderMigrationsException;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OutOfOrderMigrationsFailureAnalyzer;
+import java.util.UUID;
+import org.flywaydb.core.Flyway;
+import org.junit.jupiter.api.Test;
+import org.springframework.boot.SpringApplication;
+import org.springframework.boot.autoconfigure.AutoConfigurations;
+import org.springframework.boot.diagnostics.FailureAnalysis;
+import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
+import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
+import org.springframework.boot.test.context.runner.ApplicationContextRunner;
+import org.springframework.context.ConfigurableApplicationContext;
+
+/**
+ * Flyway posture (scope R5, D112, AC7) on H2 with the platform defaults applied as at startup:
+ * out-of-order refused outside {@code local} with an actionable diagnostic, a misnamed migration and
+ * an edited applied migration both block startup, and {@code clean} is refused.
+ */
+class FlywayPostureTest {
+
+    private static ApplicationContextRunner runner(String database, String location) {
+        return new ApplicationContextRunner()
+                .withInitializer(context -> new PlatformDataJpaEnvironmentPostProcessor()
+                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
+                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
+                        FlywayAutoConfiguration.class, PlatformDataJpaAutoConfiguration.class))
+                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
+                        "spring.flyway.locations=classpath:db/" + location);
+    }
+
+    private static String database() {
+        return "posture-" + UUID.randomUUID();
+    }
+
+    @Test
+    void outOfOrderOutsideTheLocalProfileFailsStartupWithAnActionableDiagnostic() {
+        runner(database(), "flyway-posture-ok").withPropertyValues("spring.flyway.out-of-order=true")
+                .run(context -> {
+                    assertThat(context).hasFailed();
+                    Throwable failure = context.getStartupFailure();
+                    FailureAnalysis analysis = new OutOfOrderMigrationsFailureAnalyzer().analyze(failure);
+                    assertThat(analysis).isNotNull();
+                    assertThat(analysis.getDescription()).contains("out-of-order").contains("'local'");
+                    assertThat(analysis.getAction()).contains("spring.flyway.out-of-order");
+                    assertThat(failure).hasRootCauseInstanceOf(OutOfOrderMigrationsException.class);
+                });
+    }
+
+    @Test
+    void outOfOrderIsAllowedUnderTheLocalProfile() {
+        runner(database(), "flyway-posture-ok")
+                .withPropertyValues("spring.flyway.out-of-order=true", "spring.profiles.active=local")
+                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
+                .run(context -> assertThat(context).hasNotFailed());
+    }
+
+    @Test
+    void aMisnamedMigrationBlocksStartup() {
+        runner(database(), "flyway-posture-badname").run(context -> {
+            assertThat(context).hasFailed();
+            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("V1_missing_double_underscore");
+        });
+    }
+
+    @Test
+    void anEditedAppliedMigrationBlocksStartup() {
+        String database = database();
+        runner(database, "flyway-posture-checksum-a").run(context -> assertThat(context).hasNotFailed());
+        runner(database, "flyway-posture-checksum-b").run(context -> {
+            assertThat(context).hasFailed();
+            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("checksum mismatch");
+        });
+    }
+
+    @Test
+    void cleanIsRefused() {
+        runner(database(), "flyway-posture-ok").run(context -> {
+            assertThat(context).hasNotFailed();
+            Flyway flyway = context.getBean(Flyway.class);
+            assertThatThrownBy(flyway::clean).hasMessageContaining("clean");
+            assertThat(((ConfigurableApplicationContext) context.getSourceApplicationContext()).isActive()).isTrue();
+        });
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
index 9124971..18fcb98 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
@@ -4,6 +4,7 @@ import static org.assertj.core.api.Assertions.assertThat;
 
 import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
 import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayPresenceCheck;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleConnectionDefaults;
 import jakarta.persistence.EntityManagerFactory;
 import java.util.Optional;
 import org.junit.jupiter.api.Test;
@@ -49,6 +50,30 @@ class PlatformDataJpaAutoConfigurationTest {
                 .run(context -> assertThat(context.getBean(AuditorAware.class)).isSameAs(mine));
     }
 
+    @Test
+    void descriptorBacksOffWhenUserDescriptorPresentUnderItsName() {
+        CapabilityDescriptor mine = new CapabilityDescriptor("data-jpa", "ACTIVE", "custom");
+        runner.withBean("dataJpaCapabilityDescriptor", CapabilityDescriptor.class, () -> mine)
+                .run(context -> assertThat(context.getBean(CapabilityDescriptor.class)).isSameAs(mine));
+    }
+
+    @Test
+    void unrelatedDescriptorsDoNotSuppressTheDataJpaOne() {
+        runner.withBean("otherCapabilityDescriptor", CapabilityDescriptor.class,
+                        () -> new CapabilityDescriptor("other", "ACTIVE", ""))
+                .run(context -> assertThat(context).hasBean("dataJpaCapabilityDescriptor"));
+    }
+
+    @Test
+    void oracleConnectionDefaultsAreRegisteredAndReplaceableByName() {
+        runner.run(context -> assertThat(context).hasSingleBean(OracleConnectionDefaults.class));
+        Object mine = new Object();
+        runner.withBean("platformOracleConnectionDefaults", Object.class, () -> mine)
+                .run(context -> assertThat(context).doesNotHaveBean(OracleConnectionDefaults.class));
+        runner.withClassLoader(new FilteredClassLoader("com.zaxxer.hikari"))
+                .run(context -> assertThat(context).doesNotHaveBean(OracleConnectionDefaults.class));
+    }
+
     @Test
     void inactiveWhenClassMissing() {
         runner.withClassLoader(new FilteredClassLoader(EntityManagerFactory.class))
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java
index 71ebc4e..78a3cc3 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java
@@ -2,8 +2,11 @@ package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;
 
 import static org.assertj.core.api.Assertions.assertThat;
 
+import java.util.LinkedHashMap;
+import java.util.Map;
 import org.junit.jupiter.api.Test;
 import org.springframework.boot.SpringApplication;
+import org.springframework.core.env.MapPropertySource;
 import org.springframework.mock.env.MockEnvironment;
 
 class PlatformDataJpaEnvironmentPostProcessorTest {
@@ -23,6 +26,60 @@ class PlatformDataJpaEnvironmentPostProcessorTest {
                 .contains(PlatformDataJpaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
     }
 
+    @Test
+    void configDefaultSnapshot() {
+        // §7.2: a changed default fails here until the snapshot is consciously updated (CHANGELOG).
+        MockEnvironment environment = new MockEnvironment();
+        processor.postProcessEnvironment(environment, new SpringApplication());
+
+        assertThat(contributed(environment)).containsExactlyEntriesOf(snapshot(
+                "spring.jpa.open-in-view", "false",
+                "spring.jpa.properties.hibernate.jdbc.batch_size", "50",
+                "spring.jpa.properties.hibernate.order_inserts", "true",
+                "spring.jpa.properties.hibernate.order_updates", "true",
+                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC",
+                "spring.datasource.hikari.connection-timeout", "5000",
+                "spring.transaction.default-timeout", "30s",
+                "spring.flyway.clean-disabled", "true",
+                "spring.flyway.validate-on-migrate", "true",
+                "spring.flyway.out-of-order", "false",
+                "spring.flyway.validate-migration-naming", "true"));
+    }
+
+    @Test
+    void contributesNoDatabaseSpecificKeys() {
+        // Oracle driver timeouts are added to the pool itself (OracleConnectionDefaults), keyed on its URL.
+        MockEnvironment environment = new MockEnvironment()
+                .withProperty("spring.datasource.url", "jdbc:oracle:thin:@//db:1521/APP");
+        processor.postProcessEnvironment(environment, new SpringApplication());
+
+        assertThat(contributed(environment).keySet()).noneMatch(key -> key.contains("oracle"));
+    }
+
+    @Test
+    void leakDetectionOnlyUnderTheLocalProfile() {
+        MockEnvironment local = new MockEnvironment();
+        local.setActiveProfiles("local");
+        processor.postProcessEnvironment(local, new SpringApplication());
+        assertThat(contributed(local)).containsEntry("spring.datasource.hikari.leak-detection-threshold", "20000");
+
+        MockEnvironment prod = new MockEnvironment();
+        prod.setActiveProfiles("prod");
+        processor.postProcessEnvironment(prod, new SpringApplication());
+        assertThat(contributed(prod)).doesNotContainKey("spring.datasource.hikari.leak-detection-threshold");
+    }
+
+    @Test
+    void userPoolAndTransactionValuesWin() {
+        MockEnvironment environment = new MockEnvironment()
+                .withProperty("spring.datasource.hikari.connection-timeout", "250")
+                .withProperty("spring.transaction.default-timeout", "2m");
+        processor.postProcessEnvironment(environment, new SpringApplication());
+
+        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("250");
+        assertThat(environment.getProperty("spring.transaction.default-timeout")).isEqualTo("2m");
+    }
+
     @Test
     void userValueWins() {
         MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "true");
@@ -39,4 +96,18 @@ class PlatformDataJpaEnvironmentPostProcessorTest {
         assertThat(environment.getPropertySources()
                 .contains(PlatformDataJpaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
     }
+
+    @SuppressWarnings("unchecked")
+    private static Map<String, Object> contributed(MockEnvironment environment) {
+        return ((MapPropertySource) environment.getPropertySources()
+                .get(PlatformDataJpaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).getSource();
+    }
+
+    private static Map<String, Object> snapshot(String... keysAndValues) {
+        Map<String, Object> expected = new LinkedHashMap<>();
+        for (int i = 0; i < keysAndValues.length; i += 2) {
+            expected.put(keysAndValues[i], keysAndValues[i + 1]);
+        }
+        return expected;
+    }
 }
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
new file mode 100644
index 0000000..e0882e0
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
@@ -0,0 +1,66 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import com.zaxxer.hikari.HikariDataSource;
+import org.junit.jupiter.api.Test;
+
+class OracleConnectionDefaultsTest {
+
+    private final OracleConnectionDefaults defaults = new OracleConnectionDefaults();
+
+    @Test
+    void anOraclePoolGetsTheDriverReadAndConnectTimeouts() {
+        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
+        defaults.postProcessBeforeInitialization(pool, "dataSource");
+
+        assertThat(pool.getDataSourceProperties())
+                .containsEntry("oracle.jdbc.ReadTimeout", "60000")
+                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
+    }
+
+    @Test
+    void anotherDatabaseIsLeftAlone() {
+        // An Oracle-starter service runs on H2 locally with ojdbc11 on the classpath.
+        HikariDataSource pool = pool("jdbc:h2:mem:app");
+        defaults.postProcessBeforeInitialization(pool, "dataSource");
+
+        assertThat(pool.getDataSourceProperties()).isEmpty();
+    }
+
+    @Test
+    void anApplicationValueWins() {
+        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
+        pool.addDataSourceProperty("oracle.jdbc.ReadTimeout", "120000");
+        defaults.postProcessBeforeInitialization(pool, "dataSource");
+
+        assertThat(pool.getDataSourceProperties())
+                .containsEntry("oracle.jdbc.ReadTimeout", "120000")
+                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
+    }
+
+    @Test
+    void aJvmSystemPropertyWins() {
+        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
+        System.setProperty("oracle.net.CONNECT_TIMEOUT", "3000");
+        try {
+            defaults.postProcessBeforeInitialization(pool, "dataSource");
+        } finally {
+            System.clearProperty("oracle.net.CONNECT_TIMEOUT");
+        }
+
+        assertThat(pool.getDataSourceProperties()).doesNotContainKey("oracle.net.CONNECT_TIMEOUT");
+    }
+
+    @Test
+    void otherBeansPassThrough() {
+        Object bean = new Object();
+        assertThat(defaults.postProcessBeforeInitialization(bean, "other")).isSameAs(bean);
+    }
+
+    private static HikariDataSource pool(String url) {
+        HikariDataSource pool = new HikariDataSource();
+        pool.setJdbcUrl(url);
+        return pool;
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleContainers.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleContainers.java
new file mode 100644
index 0000000..aca8020
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleContainers.java
@@ -0,0 +1,78 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import java.sql.Connection;
+import java.sql.DriverManager;
+import java.sql.SQLException;
+import java.sql.Statement;
+import java.time.Duration;
+import java.util.Locale;
+import java.util.UUID;
+import org.springframework.test.context.DynamicPropertyRegistry;
+import org.testcontainers.oracle.OracleContainer;
+
+/**
+ * One Oracle Free container per test JVM, shared by every Oracle test class (an Oracle instance needs
+ * about 2 GB, so one per class would not fit next to the build), and one fresh schema user per JVM, so
+ * no state carries over between runs.
+ *
+ * <p>A cold start copies about 3 GB of datafiles out of the image and took 9 minutes on a laptop
+ * disk. The container is therefore reusable: with {@code testcontainers.reuse.enable=true} in
+ * {@code ~/.testcontainers.properties} later runs attach to the warm instance; without it every JVM
+ * starts (and removes) its own.
+ *
+ * <p>Production runs Oracle 19c (HD13); only 23ai Free exists as a container, so every test pins
+ * Hibernate to major version 19 and every hand-written SQL file stays 19c-safe.
+ */
+final class OracleContainers {
+
+    /** Present locally; pinned by digest in the phase evidence. */
+    static final String IMAGE = "gvenzl/oracle-free:23-slim-faststart";
+
+    private static OracleContainer container;
+    private static String user;
+    private static String password;
+
+    private OracleContainers() {
+    }
+
+    static synchronized OracleContainer oracle() {
+        if (container == null) {
+            OracleContainer started = new OracleContainer(IMAGE)
+                    .withStartupTimeout(Duration.ofMinutes(15))
+                    .withReuse(true);
+            started.start();
+            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
+            user = "PDT_" + suffix;
+            password = "Pw" + suffix;
+            createUser(started, user, password);
+            container = started;
+        }
+        return container;
+    }
+
+    /** Datasource (the per-JVM user) and the 19c pin, for a {@code @DynamicPropertySource}. */
+    static void register(DynamicPropertyRegistry registry) {
+        registry.add("spring.datasource.url", () -> oracle().getJdbcUrl());
+        registry.add("spring.datasource.username", () -> {
+            oracle();
+            return user;
+        });
+        registry.add("spring.datasource.password", () -> {
+            oracle();
+            return password;
+        });
+        registry.add("spring.jpa.properties.jakarta.persistence.database-major-version", () -> "19");
+    }
+
+    /** A schema owner with exactly what the tests' migrations and DML need. */
+    private static void createUser(OracleContainer oracle, String name, String secret) {
+        try (Connection system = DriverManager.getConnection(oracle.getJdbcUrl(), "system", oracle.getPassword());
+                Statement statement = system.createStatement()) {
+            statement.execute("create user " + name + " identified by \"" + secret + "\" quota unlimited on users");
+            statement.execute("grant create session, create table, create sequence, create view, create procedure to "
+                    + name);
+        } catch (SQLException ex) {
+            throw new IllegalStateException("could not create the Oracle test user", ex);
+        }
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleDialectIT.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleDialectIT.java
new file mode 100644
index 0000000..94d14ac
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleDialectIT.java
@@ -0,0 +1,161 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.assertThatThrownBy;
+
+import ae.gov.dubaicustoms.platform.data.Money;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
+import jakarta.persistence.EntityManager;
+import java.sql.SQLException;
+import java.time.Instant;
+import java.util.List;
+import java.util.TimeZone;
+import javax.sql.DataSource;
+import org.junit.jupiter.api.Tag;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
+import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
+import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
+import org.springframework.dao.DataIntegrityViolationException;
+import org.springframework.data.domain.Page;
+import org.springframework.data.domain.PageRequest;
+import org.springframework.data.domain.Sort;
+import org.springframework.jdbc.core.JdbcTemplate;
+import org.springframework.test.context.DynamicPropertyRegistry;
+import org.springframework.test.context.DynamicPropertySource;
+
+/**
+ * Oracle dialect behaviour the platform documents (scope R6), with Hibernate in 19c mode (HD13):
+ * identifiers, sequence and identity keys, {@code boolean} on {@code NUMBER(1)}, {@code Instant}
+ * across JVM and session time zones, {@code OFFSET … FETCH} paging, and Oracle's {@code ''} = NULL.
+ * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
+ */
+@Tag("docker")
+@DataJpaTest(properties = {
+        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
+        "spring.jpa.hibernate.ddl-auto=validate",
+        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
+                + "ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle.SqlCapture"
+})
+@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
+@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
+class OracleDialectIT {
+
+    @DynamicPropertySource
+    static void oracle(DynamicPropertyRegistry registry) {
+        OracleContainers.register(registry);
+    }
+
+    @Autowired
+    private EntityManager em;
+
+    @Autowired
+    private OracleWidgetRepository widgets;
+
+    @Autowired
+    private DataSource dataSource;
+
+    @Test
+    void unquotedSnakeCaseNamesAreStoredUpperCaseAnd128CharactersIsTheLimit() throws SQLException {
+        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
+        assertThat(jdbc.queryForObject(
+                "select count(*) from user_tables where table_name = 'ORACLE_WIDGET'", Integer.class)).isEqualTo(1);
+
+        String longest = "t" + "x".repeat(127);
+        jdbc.execute("create table " + longest + " (id number(19))");
+        jdbc.execute("drop table " + longest);
+        assertThatThrownBy(() -> jdbc.execute("create table " + longest + "y (id number(19))")).rootCause()
+                .hasMessageContaining("ORA-00972");
+    }
+
+    @Test
+    void sequenceAndIdentityKeysAreGenerated() {
+        OracleWidget first = widget("SEQ-1");
+        OracleWidget second = widget("SEQ-2");
+        em.persist(first);
+        em.persist(second);
+        OracleNote note = new OracleNote();
+        note.text = "identity";
+        em.persist(note);
+        em.flush();
+
+        assertThat(first.id).isNotNull();
+        assertThat(second.id).isNotNull().isNotEqualTo(first.id);
+        assertThat(note.id).isNotNull();
+        Number increment = (Number) em.createNativeQuery(
+                "select increment_by from user_sequences where sequence_name = 'ORACLE_WIDGET_SEQ'").getSingleResult();
+        assertThat(increment.intValue()).as("allocationSize must equal INCREMENT BY").isEqualTo(50);
+    }
+
+    @Test
+    void booleanIsStoredAsNumberOne() {
+        OracleWidget widget = widget("BOOL-1");
+        widget.active = true;
+        em.persist(widget);
+        em.flush();
+
+        Number stored = (Number) em.createNativeQuery(
+                "select active from oracle_widget where reference_code = 'BOOL-1'").getSingleResult();
+        assertThat(stored.intValue()).isEqualTo(1);
+        String type = (String) em.createNativeQuery(
+                "select data_type from user_tab_columns where table_name = 'ORACLE_WIDGET' and column_name = 'ACTIVE'")
+                .getSingleResult();
+        assertThat(type).isEqualTo("NUMBER");
+    }
+
+    @Test
+    void instantRoundTripsUnchangedAcrossJvmAndSessionTimeZones() {
+        Instant when = Instant.parse("2026-03-29T01:30:00.654321Z");
+        TimeZone original = TimeZone.getDefault();
+        try {
+            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
+            em.createNativeQuery("alter session set time_zone = '-05:00'").executeUpdate();
+            OracleWidget widget = widget("TZ-1");
+            widget.happenedAt = when;
+            em.persist(widget);
+            em.flush();
+            em.clear();
+
+            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
+            em.createNativeQuery("alter session set time_zone = '+09:00'").executeUpdate();
+            OracleWidget loaded = em.find(OracleWidget.class, widget.id);
+            assertThat(loaded.happenedAt).isEqualTo(when);
+        } finally {
+            TimeZone.setDefault(original);
+        }
+    }
+
+    @Test
+    void pagingUsesOffsetFetchAndIsStableWithAnOrderBy() {
+        for (int i = 0; i < 7; i++) {
+            em.persist(widget("PAGE-" + i));
+        }
+        em.flush();
+        em.clear();
+        SqlCapture.STATEMENTS.clear();
+
+        Page<OracleWidget> page = widgets.findAll(PageRequest.of(1, 3, Sort.by("referenceCode")));
+
+        assertThat(page.getContent()).extracting(w -> w.referenceCode).containsExactly("PAGE-3", "PAGE-4", "PAGE-5");
+        assertThat(page.getTotalElements()).isEqualTo(7);
+        List<String> selects = SqlCapture.STATEMENTS.stream().filter(sql -> sql.contains("order by")).toList();
+        assertThat(selects).anySatisfy(sql -> assertThat(sql.toLowerCase())
+                .contains("offset ? rows fetch first ? rows only").doesNotContain("rownum"));
+    }
+
+    @Test
+    void anEmptyStringIsNullOnOracleSoANotNullColumnRejectsIt() {
+        OracleWidget widget = widget("");
+        assertThatThrownBy(() -> widgets.saveAndFlush(widget))
+                .isInstanceOf(DataIntegrityViolationException.class)
+                .hasMessageContaining("ORA-01400");
+    }
+
+    private static OracleWidget widget(String reference) {
+        OracleWidget widget = new OracleWidget();
+        widget.referenceCode = reference;
+        widget.price = Money.of("1.00", "AED");
+        return widget;
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleExceptionPathsIT.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleExceptionPathsIT.java
new file mode 100644
index 0000000..db0f28f
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleExceptionPathsIT.java
@@ -0,0 +1,251 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.catchThrowable;
+
+import ae.gov.dubaicustoms.platform.data.Money;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
+import jakarta.persistence.EntityManager;
+import jakarta.persistence.LockModeType;
+import java.util.ArrayList;
+import java.util.List;
+import java.util.Map;
+import java.util.concurrent.CountDownLatch;
+import java.util.concurrent.TimeUnit;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Tag;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
+import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
+import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
+import org.springframework.test.context.DynamicPropertyRegistry;
+import org.springframework.test.context.DynamicPropertySource;
+import org.springframework.transaction.PlatformTransactionManager;
+import org.springframework.transaction.annotation.Propagation;
+import org.springframework.transaction.annotation.Transactional;
+import org.springframework.transaction.support.TransactionTemplate;
+
+/**
+ * The exception types data-access failures really surface as on Oracle, path by path (scope R4: the
+ * platform maps observed types, not guessed ones). Each test records the cause chain it saw, so a
+ * changed type in a Spring/Hibernate/driver upgrade fails here first. Not transactional: every path
+ * commits or fails for real. Excluded from the default build (docker JUnit tag); run under
+ * {@code -Pdocker}.
+ */
+@Tag("docker")
+@DataJpaTest(properties = {
+        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
+        "spring.jpa.hibernate.ddl-auto=validate",
+        "spring.datasource.hikari.maximum-pool-size=3",
+        "spring.datasource.hikari.connection-timeout=1000"
+})
+@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
+@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
+@Transactional(propagation = Propagation.NOT_SUPPORTED)
+class OracleExceptionPathsIT {
+
+    @DynamicPropertySource
+    static void oracle(DynamicPropertyRegistry registry) {
+        OracleContainers.register(registry);
+    }
+
+    @Autowired
+    private EntityManager em;
+
+    @Autowired
+    private OracleWidgetRepository widgets;
+
+    @Autowired
+    private PlatformTransactionManager transactionManager;
+
+    @AfterEach
+    void clean() {
+        tx().executeWithoutResult(status -> {
+            em.createNativeQuery("delete from oracle_part").executeUpdate();
+            em.createNativeQuery("delete from oracle_widget").executeUpdate();
+        });
+    }
+
+    @Test
+    void duplicateKeyFlushedAtCommitIsTranslated() {
+        widgets.saveAndFlush(widget("DUP-1"));
+        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> widgets.save(widget("DUP-1"))));
+        // Hibernate batches the insert (batch_size 50), so the driver reports a BatchUpdateException, not
+        // an SQLIntegrityConstraintViolationException; only its SQLState (23000) says "integrity".
+        assertThat(chain(thrown)).containsExactly("org.springframework.dao.DataIntegrityViolationException",
+                "org.hibernate.exception.ConstraintViolationException", "java.sql.BatchUpdateException");
+        assertThat(sqlState(thrown)).isEqualTo("23000");
+    }
+
+    @Test
+    void duplicateKeyThroughSaveAndFlushIsTranslated() {
+        widgets.saveAndFlush(widget("DUP-2"));
+        Throwable thrown = catchThrowable(() -> widgets.saveAndFlush(widget("DUP-2")));
+        assertThat(chain(thrown)).startsWith("org.springframework.dao.DataIntegrityViolationException");
+    }
+
+    @Test
+    void duplicateKeyThroughTheEntityManagerDirectlyIsNotTranslated() {
+        widgets.saveAndFlush(widget("DUP-3"));
+        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> {
+            em.persist(widget("DUP-3"));
+            em.flush();
+        }));
+        // No @Repository proxy, so no Spring translation: the raw Hibernate type reaches the caller.
+        assertThat(chain(thrown)).containsExactly("org.hibernate.exception.ConstraintViolationException",
+                "java.sql.BatchUpdateException");
+        assertThat(sqlState(thrown)).isEqualTo("23000");
+    }
+
+    @Test
+    void foreignKeyAndCheckViolationsAreIntegrityViolations() {
+        Throwable foreignKey = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
+                "insert into oracle_part (id, widget_id, name) values (1, 999999, 'orphan')").executeUpdate()));
+        assertThat(chain(foreignKey)).contains("java.sql.SQLIntegrityConstraintViolationException");
+        assertThat(foreignKey).hasStackTraceContaining("ORA-02291");
+
+        OracleWidget saved = widgets.saveAndFlush(widget("CHK-1"));
+        Throwable check = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
+                "update oracle_widget set active = 2 where id = " + saved.id).executeUpdate()));
+        assertThat(chain(check)).contains("java.sql.SQLIntegrityConstraintViolationException");
+        assertThat(check).hasStackTraceContaining("ORA-02290");
+    }
+
+    @Test
+    void concurrentUpdateIsAnOptimisticLockingFailure() {
+        OracleWidget saved = widgets.saveAndFlush(widget("OPT-1"));
+        OracleWidget first = widgets.findById(saved.id).orElseThrow();
+        OracleWidget second = widgets.findById(saved.id).orElseThrow();
+        first.active = true;
+        widgets.saveAndFlush(first);
+        second.active = false;
+        Throwable thrown = catchThrowable(() -> widgets.saveAndFlush(second));
+        assertThat(chain(thrown)).startsWith("org.springframework.orm.ObjectOptimisticLockingFailureException");
+    }
+
+    @Test
+    void lockHeldElsewhereIsAPessimisticLockingFailure() throws Exception {
+        OracleWidget saved = widgets.saveAndFlush(widget("PES-1"));
+        CountDownLatch locked = new CountDownLatch(1);
+        CountDownLatch release = new CountDownLatch(1);
+        Thread holder = new Thread(() -> tx().executeWithoutResult(status -> {
+            em.find(OracleWidget.class, saved.id, LockModeType.PESSIMISTIC_WRITE);
+            locked.countDown();
+            await(release);
+        }));
+        holder.start();
+        try {
+            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
+            Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> em.find(OracleWidget.class,
+                    saved.id, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0))));
+            // EntityManager directly: the JPA type, untranslated (ORA-00054 resource busy, NOWAIT).
+            assertThat(chain(thrown)).startsWith("jakarta.persistence.LockTimeoutException",
+                    "org.hibernate.exception.LockTimeoutException");
+            assertThat(thrown).hasStackTraceContaining("ORA-00054");
+        } finally {
+            release.countDown();
+            holder.join(30_000);
+        }
+    }
+
+    @Test
+    void selectOverItsQueryTimeoutIsCancelled() {
+        long started = System.nanoTime();
+        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
+                        "select count(*) from (select level l from dual connect by level <= 20000) a,"
+                                + " (select level l from dual connect by level <= 20000) b")
+                .setHint("jakarta.persistence.query.timeout", 1000)
+                .getSingleResult()));
+        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
+        // EntityManager directly: the JPA type, untranslated.
+        assertThat(chain(thrown)).startsWith("jakarta.persistence.QueryTimeoutException");
+        assertThat(thrown).hasStackTraceContaining("ORA-01013");
+        assertThat(millis).isBetween(900L, 4_000L);
+    }
+
+    @Test
+    void aWaitingPlSqlCallIsNotInterruptedByTheQueryTimeout() {
+        // Recorded behaviour, not a wish: a PL/SQL wait (like a lock wait or a dead network) ignores the
+        // cancel, so the statement timeout cannot be the only guard (scope R2.3, D110: read timeout).
+        long started = System.nanoTime();
+        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
+                        "select pdt_sleep(3) from dual")
+                .setHint("jakarta.persistence.query.timeout", 1000)
+                .getSingleResult()));
+        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
+        assertThat(thrown).isNull();
+        assertThat(millis).isGreaterThanOrEqualTo(2_900L);
+    }
+
+    @Test
+    void exhaustedPoolFailsTransactionBeginWithinTheConnectionTimeout() throws Exception {
+        CountDownLatch held = new CountDownLatch(3);
+        CountDownLatch release = new CountDownLatch(1);
+        List<Thread> holders = new ArrayList<>();
+        for (int i = 0; i < 3; i++) {
+            Thread holder = new Thread(() -> tx().executeWithoutResult(status -> {
+                em.createNativeQuery("select 1 from dual").getSingleResult();
+                held.countDown();
+                await(release);
+            }));
+            holders.add(holder);
+            holder.start();
+        }
+        try {
+            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
+            long started = System.nanoTime();
+            Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status ->
+                    em.createNativeQuery("select 1 from dual").getSingleResult()));
+            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
+            assertThat(chain(thrown)).containsExactly("org.springframework.transaction.CannotCreateTransactionException",
+                    "org.hibernate.exception.JDBCConnectionException", "java.sql.SQLTransientConnectionException");
+            assertThat(millis).isBetween(900L, 5_000L);
+        } finally {
+            release.countDown();
+            for (Thread holder : holders) {
+                holder.join(30_000);
+            }
+        }
+    }
+
+    private TransactionTemplate tx() {
+        return new TransactionTemplate(transactionManager);
+    }
+
+    private static OracleWidget widget(String reference) {
+        OracleWidget widget = new OracleWidget();
+        widget.referenceCode = reference;
+        widget.price = Money.of("1.00", "AED");
+        return widget;
+    }
+
+    /** Class names from the thrown exception down to the root cause. */
+    private static List<String> chain(Throwable thrown) {
+        assertThat(thrown).as("the path must fail").isNotNull();
+        List<String> types = new ArrayList<>();
+        for (Throwable t = thrown; t != null && types.size() < 20; t = t.getCause()) {
+            types.add(t.getClass().getName());
+        }
+        System.out.println("observed chain: " + types);
+        return types;
+    }
+
+    /** The first SQLState in the cause chain (BatchUpdateException included). */
+    private static String sqlState(Throwable thrown) {
+        for (Throwable t = thrown; t != null; t = t.getCause()) {
+            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
+                return sql.getSQLState();
+            }
+        }
+        return null;
+    }
+
+    private static void await(CountDownLatch latch) {
+        try {
+            latch.await(60, TimeUnit.SECONDS);
+        } catch (InterruptedException ex) {
+            Thread.currentThread().interrupt();
+        }
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleGeneratedRow.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleGeneratedRow.java
new file mode 100644
index 0000000..67a0a59
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleGeneratedRow.java
@@ -0,0 +1,19 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import jakarta.persistence.Entity;
+import jakarta.persistence.Id;
+import org.hibernate.annotations.Immutable;
+import org.hibernate.annotations.Subselect;
+
+/**
+ * 20000 generated rows with no table behind them, so a JPQL cross join of this entity is pure SQL-engine
+ * work (no PL/SQL in the call stack) for the timeout tests. Read-only; not part of schema validation.
+ */
+@Entity
+@Immutable
+@Subselect("select level l from dual connect by level <= 20000")
+class OracleGeneratedRow {
+
+    @Id
+    Long l;
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleNote.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleNote.java
new file mode 100644
index 0000000..c0c87f5
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleNote.java
@@ -0,0 +1,17 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import jakarta.persistence.Entity;
+import jakarta.persistence.GeneratedValue;
+import jakarta.persistence.GenerationType;
+import jakarta.persistence.Id;
+
+/** Identity-keyed Oracle test entity ({@code V2__oracle_note_and_part.sql}). Field access. */
+@Entity
+class OracleNote {
+
+    @Id
+    @GeneratedValue(strategy = GenerationType.IDENTITY)
+    Long id;
+
+    String text;
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleSmokeIT.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleSmokeIT.java
new file mode 100644
index 0000000..650a323
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleSmokeIT.java
@@ -0,0 +1,87 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.data.Money;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
+import jakarta.persistence.EntityManager;
+import java.time.Instant;
+import org.hibernate.engine.spi.SessionFactoryImplementor;
+import org.junit.jupiter.api.Tag;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
+import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
+import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
+import org.springframework.test.context.DynamicPropertyRegistry;
+import org.springframework.test.context.DynamicPropertySource;
+
+/**
+ * Oracle smoke: the 19c pin really reaches Hibernate's dialect, Flyway migrates through
+ * {@code flyway-database-oracle}, and Hibernate validates the entity against the 19c-safe schema.
+ * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
+ */
+@Tag("docker")
+@DataJpaTest(properties = {
+        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
+        "spring.jpa.hibernate.ddl-auto=validate"
+})
+@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
+@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
+class OracleSmokeIT {
+
+    @DynamicPropertySource
+    static void oracle(DynamicPropertyRegistry registry) {
+        OracleContainers.register(registry);
+    }
+
+    @Autowired
+    private EntityManager em;
+
+    @Autowired
+    private javax.sql.DataSource dataSource;
+
+    @Test
+    void theOracleUrlDefaultsReachTheDriverAndThePool() {
+        // D110: bracketed map keys keep the driver property names' dots and case through binding.
+        var hikari = (com.zaxxer.hikari.HikariDataSource) dataSource;
+        assertThat(hikari.getDataSourceProperties())
+                .containsEntry("oracle.jdbc.ReadTimeout", "60000")
+                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
+        assertThat(hikari.getConnectionTimeout()).isEqualTo(5000);
+    }
+
+    @Test
+    void hibernateResolvesTheOracle19DialectAgainstTheContainer() {
+        var dialect = em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class)
+                .getJdbcServices().getDialect();
+        assertThat(dialect.getClass().getSimpleName()).isEqualTo("OracleDialect");
+        assertThat(dialect.getVersion().getDatabaseMajorVersion()).isEqualTo(19);
+    }
+
+    @Test
+    void flywayMigratedAndAnEntityRoundTrips() {
+        Object applied = em.createNativeQuery(
+                "select count(*) from \"flyway_schema_history\" where \"success\" = 1").getSingleResult();
+        assertThat(((Number) applied).intValue()).isEqualTo(3);
+
+        CorrelationId correlationId = CorrelationId.random();
+        OracleWidget widget = new OracleWidget();
+        widget.referenceCode = "ORA-1";
+        widget.active = true;
+        widget.happenedAt = Instant.parse("2026-10-28T10:15:30.123456Z");
+        widget.price = Money.of("99.95", "AED");
+        widget.correlationId = correlationId;
+        em.persist(widget);
+        em.flush();
+        em.clear();
+
+        OracleWidget loaded = em.find(OracleWidget.class, widget.id);
+        assertThat(loaded.active).isTrue();
+        assertThat(loaded.happenedAt).isEqualTo(widget.happenedAt);
+        assertThat(loaded.price).isEqualTo(Money.of("99.95", "AED"));
+        assertThat(loaded.correlationId).isEqualTo(correlationId);
+        assertThat(loaded.createdBy).isEqualTo("system");
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTestApp.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTestApp.java
new file mode 100644
index 0000000..1b8a486
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTestApp.java
@@ -0,0 +1,14 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import org.springframework.boot.SpringBootConfiguration;
+import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
+
+/**
+ * Configuration anchor for the Oracle container tests: the nearest {@code @SpringBootConfiguration}
+ * for this package, so only the entities here are scanned (the H2 slice entities one package up stay
+ * out of the Oracle persistence unit).
+ */
+@SpringBootConfiguration
+@EnableAutoConfiguration
+class OracleTestApp {
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutLeversIT.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutLeversIT.java
new file mode 100644
index 0000000..89e6010
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutLeversIT.java
@@ -0,0 +1,121 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.catchThrowable;
+
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
+import jakarta.persistence.EntityManager;
+import java.util.concurrent.TimeUnit;
+import java.util.function.Supplier;
+import javax.sql.DataSource;
+import org.junit.jupiter.api.Tag;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
+import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
+import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
+import org.springframework.jdbc.core.JdbcTemplate;
+import org.springframework.test.context.DynamicPropertyRegistry;
+import org.springframework.test.context.DynamicPropertySource;
+import org.springframework.transaction.PlatformTransactionManager;
+import org.springframework.transaction.annotation.Propagation;
+import org.springframework.transaction.annotation.Transactional;
+import org.springframework.transaction.support.TransactionTemplate;
+
+/**
+ * Which timeout lever really bounds a slow Oracle statement (scope R2.3, D110): each test runs the
+ * same SQL-engine work (about 10 s uncancelled on a warm container) under one lever: each cancels it
+ * (ORA-01013) about a second after the 1 s limit (the driver's timeout thread polls once a second). A
+ * {@code pdt_sleep} (PL/SQL wait) is NOT interruptible by any of them, like a lock wait or a dead network;
+ * and SQL run inside a PL/SQL function was once not cancelled at all, so the work here stays plain SQL. JPA
+ * paths surface an untranslated {@code jakarta.persistence.QueryTimeoutException}.
+ * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
+ */
+@Tag("docker")
+@DataJpaTest(properties = {
+        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
+        "spring.jpa.hibernate.ddl-auto=validate"
+})
+@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
+@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
+@Transactional(propagation = Propagation.NOT_SUPPORTED)
+class OracleTimeoutLeversIT {
+
+    /** SQL-engine work (interruptible), tens of seconds uncancelled. */
+    private static final String HEAVY_SQL =
+            "select count(*) from (select level l from dual connect by level <= 20000) a, (select level l from dual connect by level <= 20000) b";
+
+    /** The same work in JPQL: a cross join of generated rows ({@link OracleGeneratedRow}). */
+    private static final String HEAVY_JPQL = "select count(*) from OracleGeneratedRow a, OracleGeneratedRow b";
+
+    @DynamicPropertySource
+    static void oracle(DynamicPropertyRegistry registry) {
+        OracleContainers.register(registry);
+    }
+
+    @Autowired
+    private EntityManager em;
+
+    @Autowired
+    private DataSource dataSource;
+
+    @Autowired
+    private PlatformTransactionManager transactionManager;
+
+    @Test
+    void jdbcQueryTimeoutCancelsTheStatement() {
+        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
+        jdbc.setQueryTimeout(1);
+        Outcome outcome = measure(() -> jdbc.queryForObject(HEAVY_SQL, Integer.class));
+        assertThat(outcome.cancelled()).as(outcome.toString()).isTrue();
+        assertThat(outcome.millis()).isLessThan(4_000);
+    }
+
+    @Test
+    void queryTimeoutHintOnAJpqlQuery() {
+        Outcome outcome = measure(() -> new TransactionTemplate(transactionManager).execute(status -> em.createQuery(
+                        HEAVY_JPQL)
+                .setHint("jakarta.persistence.query.timeout", 1000)
+                .getSingleResult()));
+        assertCancelledAsJpaTimeout(outcome);
+    }
+
+    @Test
+    void transactionTimeoutWithANativeQuery() {
+        TransactionTemplate tx = new TransactionTemplate(transactionManager);
+        tx.setTimeout(1);
+        Outcome outcome = measure(() -> tx.execute(status ->
+                em.createNativeQuery(HEAVY_SQL).getSingleResult()));
+        assertCancelledAsJpaTimeout(outcome);
+    }
+
+    @Test
+    void transactionTimeoutWithAJpqlQuery() {
+        TransactionTemplate tx = new TransactionTemplate(transactionManager);
+        tx.setTimeout(1);
+        Outcome outcome = measure(() -> tx.execute(status -> em.createQuery(
+                HEAVY_JPQL).getSingleResult()));
+        assertCancelledAsJpaTimeout(outcome);
+    }
+
+    private static void assertCancelledAsJpaTimeout(Outcome outcome) {
+        assertThat(outcome.cancelled()).as(outcome.toString()).isTrue();
+        assertThat(outcome.type()).isEqualTo("jakarta.persistence.QueryTimeoutException");
+        assertThat(outcome.millis()).isLessThan(4_000);
+    }
+
+    private static Outcome measure(Supplier<?> work) {
+        long started = System.nanoTime();
+        Throwable thrown = catchThrowable(work::get);
+        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
+        String type = thrown == null ? "none" : thrown.getClass().getName();
+        boolean cancelled = false;
+        for (Throwable t = thrown; t != null; t = t.getCause()) {
+            cancelled |= String.valueOf(t.getMessage()).contains("ORA-01013");
+        }
+        return new Outcome(millis, cancelled, type);
+    }
+
+    private record Outcome(long millis, boolean cancelled, String type) {
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleWidget.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleWidget.java
new file mode 100644
index 0000000..22e518c
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleWidget.java
@@ -0,0 +1,62 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.data.Money;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.CorrelationIdConverter;
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MoneyConverter;
+import jakarta.persistence.Convert;
+import jakarta.persistence.Entity;
+import jakarta.persistence.EntityListeners;
+import jakarta.persistence.GeneratedValue;
+import jakarta.persistence.GenerationType;
+import jakarta.persistence.Id;
+import jakarta.persistence.SequenceGenerator;
+import jakarta.persistence.Version;
+import java.time.Instant;
+import org.springframework.data.annotation.CreatedBy;
+import org.springframework.data.annotation.CreatedDate;
+import org.springframework.data.annotation.LastModifiedBy;
+import org.springframework.data.annotation.LastModifiedDate;
+import org.springframework.data.jpa.domain.support.AuditingEntityListener;
+
+/**
+ * Oracle test entity mapped onto {@code V1__oracle_widget.sql}: sequence key (allocation size equal
+ * to the sequence's {@code INCREMENT BY}), a {@code boolean} on {@code NUMBER(1)}, an {@code Instant},
+ * the platform converters, auditing and an optimistic-lock version. Field access.
+ */
+@Entity
+@EntityListeners(AuditingEntityListener.class)
+class OracleWidget {
+
+    @Id
+    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "oracle_widget_seq")
+    @SequenceGenerator(name = "oracle_widget_seq", sequenceName = "oracle_widget_seq", allocationSize = 50)
+    Long id;
+
+    String referenceCode;
+
+    boolean active;
+
+    Instant happenedAt;
+
+    @Convert(converter = MoneyConverter.class)
+    Money price;
+
+    @Convert(converter = CorrelationIdConverter.class)
+    CorrelationId correlationId;
+
+    @Version
+    long version;
+
+    @CreatedDate
+    Instant createdAt;
+
+    @CreatedBy
+    String createdBy;
+
+    @LastModifiedDate
+    Instant lastModifiedAt;
+
+    @LastModifiedBy
+    String lastModifiedBy;
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleWidgetRepository.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleWidgetRepository.java
new file mode 100644
index 0000000..f71bb97
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleWidgetRepository.java
@@ -0,0 +1,7 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import org.springframework.data.jpa.repository.JpaRepository;
+
+/** Spring Data repository for the Oracle tests (paging, translated exceptions). */
+interface OracleWidgetRepository extends JpaRepository<OracleWidget, Long> {
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/SqlCapture.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/SqlCapture.java
new file mode 100644
index 0000000..cb9eff6
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/SqlCapture.java
@@ -0,0 +1,20 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;
+
+import java.util.List;
+import java.util.concurrent.CopyOnWriteArrayList;
+import org.hibernate.resource.jdbc.spi.StatementInspector;
+
+/**
+ * Records every SQL string Hibernate prepares, so a test can assert the dialect's syntax (registered
+ * with {@code hibernate.session_factory.statement_inspector}).
+ */
+public class SqlCapture implements StatementInspector {
+
+    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();
+
+    @Override
+    public String inspect(String sql) {
+        STATEMENTS.add(sql);
+        return sql;
+    }
+}
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-badname/V1_missing_double_underscore.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-badname/V1_missing_double_underscore.sql
new file mode 100644
index 0000000..3bee440
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-badname/V1_missing_double_underscore.sql
@@ -0,0 +1,2 @@
+-- One underscore after the version: not V<version>__<description>.sql.
+create table posture_badname (id bigint primary key);
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-checksum-a/V1__posture_note.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-checksum-a/V1__posture_note.sql
new file mode 100644
index 0000000..6c915c9
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-checksum-a/V1__posture_note.sql
@@ -0,0 +1 @@
+create table posture_note (id bigint primary key);
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-checksum-b/V1__posture_note.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-checksum-b/V1__posture_note.sql
new file mode 100644
index 0000000..44d8119
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-checksum-b/V1__posture_note.sql
@@ -0,0 +1,2 @@
+-- Edited after it was applied: the checksum no longer matches the history table.
+create table posture_note (id bigint primary key, text varchar(64));
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-ok/V1__posture_ok.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-ok/V1__posture_ok.sql
new file mode 100644
index 0000000..a5ab0ba
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/flyway-posture-ok/V1__posture_ok.sql
@@ -0,0 +1 @@
+create table posture_ok (id bigint primary key);
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V1__oracle_widget.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V1__oracle_widget.sql
new file mode 100644
index 0000000..79a94e6
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V1__oracle_widget.sql
@@ -0,0 +1,21 @@
+-- 19c-safe (HD13): no BOOLEAN type, no IF [NOT] EXISTS, NUMBER(1) for booleans.
+-- Instant columns are TIMESTAMP WITH TIME ZONE: Hibernate maps Instant to its UTC timestamp type and
+-- reads an OffsetDateTime; a plain TIMESTAMP column fails on read with ORA-18716 (D114).
+create sequence oracle_widget_seq start with 1 increment by 50;
+
+create table oracle_widget (
+    id               number(19)    not null,
+    reference_code   varchar2(64)  not null,
+    active           number(1)     not null,
+    happened_at      timestamp(6) with time zone,
+    price            varchar2(64),
+    correlation_id   varchar2(32),
+    version          number(19)    not null,
+    created_at       timestamp(6) with time zone,
+    created_by       varchar2(128),
+    last_modified_at timestamp(6) with time zone,
+    last_modified_by varchar2(128),
+    constraint oracle_widget_pk primary key (id),
+    constraint oracle_widget_ref_uk unique (reference_code),
+    constraint oracle_widget_active_ck check (active in (0, 1))
+);
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V2__oracle_note_and_part.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V2__oracle_note_and_part.sql
new file mode 100644
index 0000000..0f67351
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V2__oracle_note_and_part.sql
@@ -0,0 +1,14 @@
+-- 19c-safe (HD13). An identity-keyed table and a child with a foreign key to oracle_widget.
+create table oracle_note (
+    id   number(19) generated by default as identity,
+    text varchar2(255) not null,
+    constraint oracle_note_pk primary key (id)
+);
+
+create table oracle_part (
+    id        number(19)   not null,
+    widget_id number(19)   not null,
+    name      varchar2(64) not null,
+    constraint oracle_part_pk primary key (id),
+    constraint oracle_part_widget_fk foreign key (widget_id) references oracle_widget (id)
+);
diff --git a/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V3__oracle_sleep_function.sql b/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V3__oracle_sleep_function.sql
new file mode 100644
index 0000000..0f0f9ee
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/resources/db/migration-platform-data-oracle/V3__oracle_sleep_function.sql
@@ -0,0 +1,8 @@
+-- 19c-safe (HD13). A slow statement on demand for the timeout tests.
+-- pdt_sleep: wall-clock wait (select pdt_sleep(5) from dual).
+create or replace function pdt_sleep(seconds in number) return number as
+begin
+    dbms_session.sleep(seconds);
+    return 1;
+end;
+/
diff --git a/data/platform-starter-data-jpa-oracle/pom.xml b/data/platform-starter-data-jpa-oracle/pom.xml
new file mode 100644
index 0000000..9913d5b
--- /dev/null
+++ b/data/platform-starter-data-jpa-oracle/pom.xml
@@ -0,0 +1,56 @@
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
+  <artifactId>platform-starter-data-jpa-oracle</artifactId>
+  <!-- Starters contain no code (CLAUDE.md rule 4): jar packaging like Boot starters, empty jar. -->
+  <packaging>jar</packaging>
+
+  <name>DC Platform :: Data :: JPA Starter (Oracle)</name>
+  <description>
+    Platform-conventional JPA persistence on Oracle: everything platform-starter-data-jpa brings plus
+    the Oracle JDBC driver and Flyway's Oracle database module, so a service talks to Oracle with one
+    dependency (HD12). Certified against Oracle in 19c mode.
+  </description>
+
+  <dependencies>
+    <!-- Starter -> starter is forbidden (CLAUDE.md rule 5), so the platform-starter-data-jpa stack is
+         listed here directly; keep the two in step. -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-data-jpa-autoconfigure</artifactId>
+      <version>${revision}</version>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-starter-data-jpa</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>org.flywaydb</groupId>
+      <artifactId>flyway-core</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-flyway</artifactId>
+    </dependency>
+    <!-- The database pair: Flyway 10+ refuses a database without its module ("Unsupported Database",
+         HI-0041), so the driver never travels alone. Versions come from Boot's dependency management. -->
+    <dependency>
+      <groupId>com.oracle.database.jdbc</groupId>
+      <artifactId>ojdbc11</artifactId>
+    </dependency>
+    <dependency>
+      <groupId>org.flywaydb</groupId>
+      <artifactId>flyway-database-oracle</artifactId>
+    </dependency>
+  </dependencies>
+</project>
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index 58bdc87..ee07999 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -685,6 +685,40 @@ One line of context per decision; details live in the commit bodies referenced.
   written raw it would make the whole line invalid JSON, and undecoded escapes (`password`)
   would hide credential names from the masks. Simpler than accepting the bypass as a residual, and
   fail-closed.
+- **D110 — bounded data-access waits (hardening phase 3, Gate B).** Measured against Oracle before
+  choosing: a statement cancel (JDBC query timeout, the JPA hint, a Spring transaction timeout) stops
+  SQL work about a second after its limit, but not a PL/SQL or lock wait or a dead network. Defaults
+  (lowest precedence): Hikari `connection-timeout` 5 s (was 30 s; a request behind an exhausted pool
+  fails fast, 503); `spring.transaction.default-timeout` 30 s (bounds every transaction, repository
+  methods included; note: it also applies to every transaction manager Boot creates, so Phase 4's
+  outbox drainer inherits it unless it sets its own); on a `jdbc:oracle:` pool the driver's
+  `oracle.jdbc.ReadTimeout` 60 s (above the transaction timeout, so slow SQL hits that first) and
+  `oracle.net.CONNECT_TIMEOUT` 10 s, added by a BeanPostProcessor keyed on the pool's own URL (an
+  environment check missed URLs from later property sources); leak detection 20 s under `local` only.
+  Hikari's keepalive and max lifetime stay its defaults.
+- **D111 — data-access failures are problems, classified by cause chain (hardening phase 3, Gate B).**
+  Observed on Oracle: Spring translates only behind a repository proxy or at commit; `EntityManager`
+  use lets raw Hibernate/JPA types through, and a batched insert reports a `BatchUpdateException`
+  whose SQLState is the only sign of a constraint violation. The errors capability's catch-all
+  therefore classifies the whole cause chain by class name (no compile dependency) and SQLState
+  (`23` → 409 `DC-DATA-0409`, `40`/locking → 409 `DC-DATA-0410`, `08`/timeouts/no connection → 503
+  `DC-DATA-0503`, retryable), with a fixed detail; a SQL bug stays 500. Simpler than a separate advice
+  behind `@ConditionalOnClass`, which would miss the raw types and need ordering.
+- **D112 — Flyway posture pinned; out-of-order refused outside `local` (hardening phase 3, Gate B).**
+  `clean-disabled`, `validate-on-migrate`, `out-of-order=false`, `validate-migration-naming` are
+  contributed explicitly so a Flyway upgrade cannot loosen them. The out-of-order guard is a
+  `FlywayConfigurationCustomizer` that checks the effective configuration before migrating (a check
+  bean could run after Flyway had already applied a migration), with a `FailureAnalyzer`.
+- **D113 — per-database starters; archetype `database` property (hardening phase 3, HD12).** One
+  POM-only starter per certified database carries the JDBC driver and Flyway's database module, so the
+  pair never travels apart (HI-0041). Only `platform-starter-data-jpa-oracle` exists (owner: other
+  databases later). The archetype takes `-Ddatabase=h2|oracle` (default `h2`, unchanged output apart
+  from a corrected comment); `oracle` requires the `data` feature and is validated like `features`.
+- **D114 — `Instant` on Oracle is `TIMESTAMP WITH TIME ZONE` (hardening phase 3, Gate B).** Hibernate
+  maps `Instant` to its UTC timestamp type and reads an offset date-time; a plain `TIMESTAMP` column
+  fails on read with ORA-18716. The platform keeps Hibernate's standard mapping rather than overriding
+  the `Instant` JDBC type for every service and database; Oracle migrations declare `Instant` columns
+  `TIMESTAMP WITH TIME ZONE` (documented, tested across JVM and session zones).
 - **D102 addendum — compound credential names (hardening phase 2, Gate F trial).** A field or text key
   ending in `password`, `passwd`, `secret`, `token`, `apikey`, `authorization` or `cookie` (ignoring
   case, `-`, `_`) is a credential too: `sessionToken`, `x-auth-token`, `db_password`. The consumer run
diff --git a/docs/modules/data.md b/docs/modules/data.md
index 909fd4c..f1e5d56 100644
--- a/docs/modules/data.md
+++ b/docs/modules/data.md
@@ -35,6 +35,26 @@ a `Money` attribute converter, and a Flyway-presence guard — all on by default
   telling you to add the starter or set `dc.platform.data.jpa.require-migrations=false`. Spring Boot 4
   ships `FlywayAutoConfiguration` in `spring-boot-flyway`, so `flyway-core` alone is an engine nobody
   starts: the starter bundles both, and the guard checks both.
+- **Bounded waits (D110)** — in the same `platform-data-jpa-defaults` source:
+  - `spring.datasource.hikari.connection-timeout=5000`: a request behind an exhausted pool fails in 5 s
+    (Hikari's own 30 s lets request threads pile up) with a 503 `DC-DATA-0503`;
+  - `spring.transaction.default-timeout=30s`: every transaction, Spring Data repository methods
+    included; once it is spent the driver cancels the running statement (reliably for plain SQL; for SQL
+    running inside a PL/SQL function the cancel is best-effort, and was once missed in testing);
+  - on a pool whose URL is `jdbc:oracle:`, the driver's `oracle.jdbc.ReadTimeout=60000` (the backstop
+    for what a cancel cannot stop: a PL/SQL or lock wait, a dead network) and
+    `oracle.net.CONNECT_TIMEOUT=10000` are added to the pool's `data-source-properties`;
+  - `leak-detection-threshold=20000` (a stack trace for a connection held over 20 s) only under the
+    `local` profile.
+  Hikari's keepalive (2 min) and max lifetime (30 min) are its own defaults. Set any key yourself to
+  change it — a value you set always wins (for the driver timeouts: a `data-source-properties` entry or
+  a JVM system property).
+- **Data errors as problems (D111)** — handled by the errors capability: a unique, foreign-key, NOT
+  NULL or check violation is a 409 `DC-DATA-0409`, a concurrent update or lock a 409 `DC-DATA-0410`, an
+  unavailable database or a timeout a retryable 503 `DC-DATA-0503`. The body never names SQL, tables or
+  constraints; the full exception is logged at WARN on the request's trace. This also covers raw
+  Hibernate/JPA exceptions from code that uses the `EntityManager` directly (Spring translates only
+  behind a repository).
 
 ## Migrations
 
@@ -43,7 +63,47 @@ once Flyway is present (never set `spring.jpa.hibernate.ddl-auto=create/update`
 service archetype's `data` feature ships `db/migration/V1__create_note.sql` for its `Note` sample and a
 `NoteRepositoryTest` that proves the migrated schema with CRUD across transactions, rollback, and a
 NOT NULL violation on H2. H2 covers local runs and tests only — the generated `prod` profile disables
-the embedded fallback, and PostgreSQL parity is the `@Tag("docker")` `PostgresParityIT`.
+the embedded fallback.
+
+Flyway's safe settings are pinned (D112), so an upgrade cannot loosen them: `clean-disabled=true`,
+`validate-on-migrate=true`, `out-of-order=false`, `validate-migration-naming=true`. In practice:
+- name every file `V<version>__<description>.sql` (two underscores) — a misnamed file fails startup
+  instead of being skipped;
+- never edit an applied migration — the checksum mismatch blocks startup; add `V<next>__…` instead;
+- out-of-order migrations are refused outside the `local` profile, with a startup diagnostic that
+  names the fix, however they were switched on (`spring.flyway.*`, `flyway.conf`, a customizer).
+
+## Oracle
+
+Production runs Oracle 19c; Oracle is the only certified database (PostgreSQL is out for now). Use the
+Oracle starter — it brings the JDBC driver **and** `flyway-database-oracle` (Flyway 10+ refuses a
+database without its module: `Unsupported Database`):
+
+```xml
+<dependency>
+  <groupId>ae.gov.dubaicustoms.platform</groupId>
+  <artifactId>platform-starter-data-jpa-oracle</artifactId>
+</dependency>
+```
+
+Generate a new service with `-Dfeatures=data -Ddatabase=oracle`; H2 still backs its local runs and tests.
+
+Behaviour verified against Oracle with Hibernate in 19c mode (`@Tag("docker")` tests in
+`platform-data-jpa-autoconfigure`):
+
+| Topic | What to do |
+|---|---|
+| Names | Unquoted snake_case names are stored upper-case; identifiers are limited to 128 characters (ORA-00972 beyond). |
+| Keys | Sequences: `allocationSize` must equal the sequence's `INCREMENT BY`. Identity columns (`generated by default as identity`) work too. |
+| `boolean` | Stored as `NUMBER(1)`; 19c has no SQL `BOOLEAN` — never use it in a migration. |
+| `Instant` | Map to `TIMESTAMP WITH TIME ZONE` (D114). Hibernate reads `Instant` as an offset date-time; a plain `TIMESTAMP` column fails on read with ORA-18716. Values are unchanged across JVM and session time zones. |
+| Paging | `Pageable` becomes `OFFSET … FETCH`; always sort for stable pages. |
+| Empty strings | Oracle stores `''` as `NULL`: a `NOT NULL` column rejects an empty string (409 `DC-DATA-0409`). |
+| Migrations | Write them 19c-safe: no `BOOLEAN`, no `IF [NOT] EXISTS`, `FROM DUAL` in selects, end PL/SQL blocks with a `/` line. |
+
+The certification container is Oracle 23ai Free (no 19c image exists): Hibernate is pinned to 19 there,
+but hand-written SQL runs against 23ai, which accepts 19c-invalid syntax — review migrations for 19c.
+RAC failover (FCF/ONS, Application Continuity) is not covered.
 
 ## Starter coordinates
 
@@ -83,5 +143,8 @@ Money total = price.add(Money.of("1.00", "AED"));   // 20.99 AED
 
 ## Local dev notes
 
-`platform-data-api` has no datastore dependency at all. The JPA slice (below) tests against H2; no
-Docker or network is required for the default build.
+`platform-data-api` has no datastore dependency at all. The JPA slice tests against H2; no
+Docker or network is required for the default build. The Oracle tests run under `mvn -Pdocker verify`;
+a cold Oracle container copies about 3 GB of datafiles (minutes on a laptop disk), so set
+`testcontainers.reuse.enable=true` in `~/.testcontainers.properties` to keep the instance warm between
+runs (each run still gets a fresh schema user).
diff --git a/docs/modules/errors.md b/docs/modules/errors.md
index 2e3212a..cfaaa18 100644
--- a/docs/modules/errors.md
+++ b/docs/modules/errors.md
@@ -10,7 +10,15 @@ RFC-9457 problem responses with stable, machine-readable error codes: throw a
   (404), `ConflictException` (409); each carries an `ErrorCode` (`DC-<CAP>-<NNNN>`) that becomes
   the problem `type`/`title` and the `code` extension.
 - **Platform exception mapping** — any other `PlatformException` maps to 500 with its own code;
-  anything unmapped maps to 500 with `DC-CORE-0500`. **No 5xx body ever carries an exception
+  a data-access failure maps to 409/503 with a `DC-DATA-*` code (below); anything else unmapped maps
+  to 500 with `DC-CORE-0500`.
+- **Data-access failures** — recognised anywhere in the cause chain, whether Spring translated them
+  (`DataIntegrityViolationException`, `OptimisticLockingFailureException`, …) or not (raw
+  Hibernate/JPA/JDBC types from code that uses the `EntityManager` directly; SQLState `23`/`40`/`08`):
+  constraint violation → 409 `DC-DATA-0409`, concurrent update or lock → 409 `DC-DATA-0410`,
+  unavailable database or timeout → 503 `DC-DATA-0503` (`retryable: true`). The detail is fixed — the
+  database's message, which names SQL, tables and constraints, never reaches the caller; it is logged
+  at WARN on the request's trace. A SQL bug (bad grammar, unknown table) stays a 500. **No 5xx body ever carries an exception
   message** — every 5xx gets the generic detail `An unexpected error occurred.`; the full exception
   goes to the log with the trace ID. 4xx business exceptions keep their application-authored message.
 - **Framework errors get the same contract** — Spring MVC's own rejections (404, 405, 406, 415, …) keep
@@ -121,6 +129,9 @@ Customizers run last, in order, on every problem body the platform emits.
 | `DC-CORE-0410` | Request body unreadable or malformed (e.g. invalid JSON). |
 | `DC-CORE-0499` | Any other request the servlet stack rejected (404, 405, 406, 415, …); status preserved. |
 | `DC-CORE-0500` | Unhandled exception; generic body, full details in the log. |
+| `DC-DATA-0409` | 409 — the request conflicts with stored data (unique, foreign-key, NOT NULL or check constraint). |
+| `DC-DATA-0410` | 409 — the data was changed or is locked by another request; reload and retry. |
+| `DC-DATA-0503` | 503 — the database is unavailable: no pooled connection in time, statement/transaction timeout, lost connection. Retryable. |
 | `DC-SEC-0401` | Missing or unacceptable credentials (security entry point). |
 | `DC-SEC-0403` | Authenticated, but lacking the required authority (security denied handler). |
 
diff --git a/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/PlatformDocs.java b/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/PlatformDocs.java
index 6ef6883..8949299 100644
--- a/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/PlatformDocs.java
+++ b/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/PlatformDocs.java
@@ -63,6 +63,7 @@ final class PlatformDocs {
         m.put("platform-starter-messaging-rabbit", "modules/messaging.md");
         m.put("platform-starter-events", "modules/events.md");
         m.put("platform-starter-data-jpa", "modules/data.md");
+        m.put("platform-starter-data-jpa-oracle", "modules/data.md");
         m.put("platform-starter-cache-caffeine", "modules/cache.md");
         m.put("platform-starter-cache-redis", "modules/cache.md");
         m.put("platform-starter-redis", "modules/redis.md");
diff --git a/errors/platform-errors-autoconfigure/pom.xml b/errors/platform-errors-autoconfigure/pom.xml
index 8709c75..b99b1a9 100644
--- a/errors/platform-errors-autoconfigure/pom.xml
+++ b/errors/platform-errors-autoconfigure/pom.xml
@@ -98,5 +98,22 @@
       <artifactId>spring-security-core</artifactId>
       <scope>test</scope>
     </dependency>
+    <!-- Data-access failures (D111) are classified by class name, so main code has no dependency on
+         these; the tests rebuild the exception chains observed on Oracle from the real types. -->
+    <dependency>
+      <groupId>org.springframework</groupId>
+      <artifactId>spring-tx</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework</groupId>
+      <artifactId>spring-jdbc</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.hibernate.orm</groupId>
+      <artifactId>hibernate-core</artifactId>
+      <scope>test</scope>
+    </dependency>
   </dependencies>
 </project>
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
index b592ea7..31dab0d 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
@@ -3,12 +3,14 @@ package ae.gov.dubaicustoms.platform.errors.autoconfigure;
 import ae.gov.dubaicustoms.platform.core.ErrorCode;
 import ae.gov.dubaicustoms.platform.core.PlatformException;
 import ae.gov.dubaicustoms.platform.errors.BusinessException;
+import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure;
 import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
 import jakarta.servlet.http.HttpServletRequest;
 import java.util.ArrayList;
 import java.util.LinkedHashMap;
 import java.util.List;
 import java.util.Map;
+import java.util.Optional;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.core.Ordered;
@@ -34,6 +36,11 @@ import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExcep
  * bodies get the same platform extensions ({@code DC-CORE-0410} for an unreadable request body,
  * {@code DC-CORE-0499} for any other rejected request). No 5xx body ever carries an exception
  * message: the full exception is logged with the trace ID instead.
+ * Data-access failures — Spring's translated types and the raw JPA/Hibernate/JDBC ones a service using
+ * the {@code EntityManager} directly lets through — become 409 {@code DC-DATA-0409} (constraint
+ * violation), 409 {@code DC-DATA-0410} (concurrent update or lock) or 503 {@code DC-DATA-0503}
+ * (no connection, timeout, lost connection), with a fixed detail that never names SQL, tables or
+ * constraints.
  * Ordered at {@link Ordered#LOWEST_PRECEDENCE} so application advice beans win first.
  *
  * <p>Registered by {@code PlatformErrorHandlingAutoConfiguration}; define your own bean named
@@ -102,7 +109,8 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
     }
 
     /**
-     * Fallback: anything unmapped is a 500 with code {@code DC-CORE-0500}. The exception message
+     * Fallback: a data-access failure (anywhere in the cause chain) is a 409/503 data problem;
+     * anything else unmapped is a 500 with code {@code DC-CORE-0500}. The exception message
      * is NOT leaked — unexpected messages routinely carry internals (SQL, hosts, file paths); the
      * full exception goes to the log, correlated via MDC.
      *
@@ -119,6 +127,17 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
         if (isSecurityException(exception)) {
             throw exception;
         }
+        // Data-access failures (D111): 409 for a conflict with stored data, 503 for an unavailable
+        // database, with a fixed detail — their messages carry SQL and constraint names. The full
+        // exception is logged once on the request's trace.
+        Optional<DataAccessFailure> dataFailure = DataAccessFailure.classify(exception);
+        if (dataFailure.isPresent()) {
+            DataAccessFailure failure = dataFailure.get();
+            log.warn("data access failure [{}]", failure.code().value(), exception);
+            ProblemDetail problem = factory.create(failure.status(), failure.code(), failure.detail(),
+                    request.getRequestURI(), exception);
+            return ResponseEntity.status(failure.status()).body(problem);
+        }
         log.error("unhandled exception [{}]", UNEXPECTED_ERROR.value(), exception);
         ProblemDetail problem = factory.create(
                 500, UNEXPECTED_ERROR, "An unexpected error occurred.", request.getRequestURI(), exception);
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
new file mode 100644
index 0000000..bfccd00
--- /dev/null
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
@@ -0,0 +1,152 @@
+package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.core.ErrorCode;
+import java.sql.SQLException;
+import java.util.IdentityHashMap;
+import java.util.Map;
+import java.util.Optional;
+import java.util.Set;
+
+/**
+ * Classifies a data-access failure by walking its cause chain (D111). Spring translates exceptions
+ * only behind a {@code @Repository} proxy or at commit; a service using the {@code EntityManager}
+ * directly lets raw Hibernate and JPA types through, and a batched insert reports a
+ * {@code BatchUpdateException} whose SQLState is the only sign of a constraint violation (all observed
+ * on Oracle, {@code OracleExceptionPathsIT}). So types are matched by class name anywhere in the chain
+ * and in each class's superclass line — no compile-time dependency on Spring Data, JPA or Hibernate —
+ * then by SQLState. The outermost match wins.
+ *
+ * <p>Response bodies use a fixed {@code detail}: the exception messages carry SQL, constraint and
+ * table names. Stateless and thread-safe.
+ */
+public enum DataAccessFailure {
+
+    /** Unique, foreign-key, NOT NULL or check violation: the request conflicts with stored data. */
+    INTEGRITY_CONFLICT(409, Codes.INTEGRITY_CONFLICT, "The request conflicts with existing data."),
+
+    /** Optimistic or pessimistic locking failure: another request changed or holds the data. */
+    CONCURRENT_UPDATE(409, Codes.CONCURRENT_UPDATE,
+            "The data was changed or is locked by another request; reload it and try again."),
+
+    /** No connection within the pool timeout, statement or transaction timeout, lost connection. */
+    UNAVAILABLE(503, Codes.UNAVAILABLE, "The database is temporarily unavailable.");
+
+    private static final Set<String> INTEGRITY_TYPES = Set.of(
+            "org.springframework.dao.DataIntegrityViolationException",
+            "org.hibernate.exception.ConstraintViolationException",
+            "java.sql.SQLIntegrityConstraintViolationException");
+
+    private static final Set<String> CONCURRENCY_TYPES = Set.of(
+            "org.springframework.dao.ConcurrencyFailureException",
+            "jakarta.persistence.OptimisticLockException",
+            "jakarta.persistence.PessimisticLockException",
+            "jakarta.persistence.LockTimeoutException",
+            "org.hibernate.StaleStateException",
+            "org.hibernate.exception.LockAcquisitionException",
+            "org.hibernate.exception.LockTimeoutException",
+            "java.sql.SQLTransactionRollbackException");
+
+    private static final Set<String> UNAVAILABLE_TYPES = Set.of(
+            "org.springframework.dao.QueryTimeoutException",
+            "org.springframework.dao.DataAccessResourceFailureException",
+            "org.springframework.dao.TransientDataAccessResourceException",
+            "org.springframework.transaction.CannotCreateTransactionException",
+            "org.springframework.transaction.TransactionTimedOutException",
+            "jakarta.persistence.QueryTimeoutException",
+            "org.hibernate.exception.JDBCConnectionException",
+            "java.sql.SQLTimeoutException",
+            "java.sql.SQLTransientConnectionException",
+            "java.sql.SQLNonTransientConnectionException",
+            "java.sql.SQLRecoverableException");
+
+    private static final int MAX_DEPTH = 32;
+
+    // Static constants (not enum fields) so the error-code registry gate sees them; a nested holder
+    // because an enum constructor cannot read the enum's own static fields.
+    private static final class Codes {
+        static final ErrorCode INTEGRITY_CONFLICT = new ErrorCode("DC-DATA-0409");
+        static final ErrorCode CONCURRENT_UPDATE = new ErrorCode("DC-DATA-0410");
+        static final ErrorCode UNAVAILABLE = new ErrorCode("DC-DATA-0503");
+    }
+
+    private final int status;
+    private final ErrorCode code;
+    private final String detail;
+
+    DataAccessFailure(int status, ErrorCode code, String detail) {
+        this.status = status;
+        this.code = code;
+        this.detail = detail;
+    }
+
+    /** @return the HTTP status of the problem response */
+    public int status() {
+        return status;
+    }
+
+    /** @return the stable error code */
+    public ErrorCode code() {
+        return code;
+    }
+
+    /** @return the fixed, internals-free problem detail */
+    public String detail() {
+        return detail;
+    }
+
+    /**
+     * Classifies {@code exception}, or returns empty when nothing in its cause chain is a known
+     * data-access failure (the caller keeps its own handling, e.g. the generic 500).
+     *
+     * @param exception the failure; may be {@code null}
+     * @return the classification, if any
+     */
+    public static Optional<DataAccessFailure> classify(Throwable exception) {
+        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
+        int depth = 0;
+        for (Throwable t = exception; t != null && depth < MAX_DEPTH && seen.put(t, Boolean.TRUE) == null;
+                t = t.getCause(), depth++) {
+            Optional<DataAccessFailure> byType = byType(t.getClass());
+            if (byType.isPresent()) {
+                return byType;
+            }
+            if (t instanceof SQLException sql) {
+                Optional<DataAccessFailure> byState = bySqlState(sql.getSQLState());
+                if (byState.isPresent()) {
+                    return byState;
+                }
+            }
+        }
+        return Optional.empty();
+    }
+
+    private static Optional<DataAccessFailure> byType(Class<?> type) {
+        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
+            String name = c.getName();
+            if (INTEGRITY_TYPES.contains(name)) {
+                return Optional.of(INTEGRITY_CONFLICT);
+            }
+            if (CONCURRENCY_TYPES.contains(name)) {
+                return Optional.of(CONCURRENT_UPDATE);
+            }
+            if (UNAVAILABLE_TYPES.contains(name)) {
+                return Optional.of(UNAVAILABLE);
+            }
+        }
+        return Optional.empty();
+    }
+
+    // SQL:2016 SQLState classes: 23 integrity constraint violation, 40 transaction rollback
+    // (serialization failure, deadlock), 08 connection exception.
+    private static Optional<DataAccessFailure> bySqlState(String state) {
+        if (state == null || state.length() < 2) {
+            return Optional.empty();
+        }
+        return switch (state.substring(0, 2)) {
+            case "23" -> Optional.of(INTEGRITY_CONFLICT);
+            case "40" -> Optional.of(CONCURRENT_UPDATE);
+            case "08" -> Optional.of(UNAVAILABLE);
+            default -> Optional.empty();
+        };
+    }
+}
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
index 90648a4..9c6445e 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
@@ -134,6 +134,53 @@ class PlatformExceptionHandlerTest {
         assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc://secret-host");
     }
 
+    @Test
+    void constraintViolationMapsTo409WithAFixedDetailAndNoSql() throws Exception {
+        try (AutoCloseable scope = RequestContext.open(new CorrelationId(CORRELATION), Map.of())) {
+            MvcResult result = mockMvc(defaults(), List.of()).perform(get("/duplicate"))
+                    .andExpect(status().isConflict())
+                    .andExpect(jsonPath("$.code").value("DC-DATA-0409"))
+                    .andExpect(jsonPath("$.type").value("urn:dc:problem:DC-DATA-0409"))
+                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data."))
+                    .andExpect(jsonPath("$.traceId").value(CORRELATION))
+                    .andExpect(jsonPath("$.retryable").value(false))
+                    .andReturn();
+            assertThat(result.getResponse().getContentAsString())
+                    .doesNotContain("ORA-").doesNotContain("ORDERS_REF_UK").doesNotContainIgnoringCase("insert into");
+        }
+    }
+
+    @Test
+    void concurrentUpdateMapsTo409WithItsOwnCode() throws Exception {
+        MvcResult result = mockMvc(defaults(), List.of()).perform(get("/stale"))
+                .andExpect(status().isConflict())
+                .andExpect(jsonPath("$.code").value("DC-DATA-0410"))
+                .andExpect(jsonPath("$.retryable").value(false))
+                .andReturn();
+        assertThat(result.getResponse().getContentAsString()).doesNotContain("ORDERS#42");
+    }
+
+    @Test
+    void unavailableDatabaseMapsTo503RetryableWithoutConnectionDetails() throws Exception {
+        MvcResult result = mockMvc(defaults(), List.of()).perform(get("/db-down"))
+                .andExpect(status().isServiceUnavailable())
+                .andExpect(jsonPath("$.code").value("DC-DATA-0503"))
+                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
+                .andExpect(jsonPath("$.retryable").value(true))
+                .andReturn();
+        assertThat(result.getResponse().getContentAsString())
+                .doesNotContain("db-secret-host").doesNotContain("HikariPool").doesNotContain("jdbc:");
+    }
+
+    @Test
+    void aSqlBugIsStillAGeneric500() throws Exception {
+        MvcResult result = mockMvc(defaults(), List.of()).perform(get("/bad-sql"))
+                .andExpect(status().isInternalServerError())
+                .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
+                .andReturn();
+        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret_column");
+    }
+
     @Test
     void requestBodyValidationOmitsRejectedValuesByDefault() throws Exception {
         MvcResult result = mockMvc(defaults(), List.of())
@@ -242,6 +289,39 @@ class PlatformExceptionHandlerTest {
             throw new IllegalStateException("cannot reach jdbc://secret-host:5432");
         }
 
+        // The chains observed on Oracle (OracleExceptionPathsIT), with Oracle's message text.
+        @GetMapping("/duplicate")
+        String duplicate() {
+            throw new org.springframework.dao.DataIntegrityViolationException(
+                    "could not execute batch [ORA-00001: unique constraint (APP.ORDERS_REF_UK) violated] "
+                            + "[insert into orders (reference_code,id) values (?,?)]",
+                    new org.hibernate.exception.ConstraintViolationException("could not execute batch",
+                            new java.sql.BatchUpdateException("ORA-00001: unique constraint (APP.ORDERS_REF_UK) violated",
+                                    "23000", 1, new int[0]), "APP.ORDERS_REF_UK"));
+        }
+
+        @GetMapping("/stale")
+        String stale() {
+            throw new org.springframework.dao.OptimisticLockingFailureException(
+                    "Row was updated or deleted by another transaction: [ORDERS#42]");
+        }
+
+        @GetMapping("/db-down")
+        String dbDown() {
+            throw new org.springframework.transaction.CannotCreateTransactionException(
+                    "Could not open JPA EntityManager for transaction",
+                    new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
+                            new java.sql.SQLTransientConnectionException(
+                                    "HikariPool-1 - Connection is not available, request timed out after 5000ms "
+                                            + "(jdbc:oracle:thin:@db-secret-host:1521/APP)")));
+        }
+
+        @GetMapping("/bad-sql")
+        String badSql() {
+            throw new org.springframework.jdbc.BadSqlGrammarException("select",
+                    "select secret_column from orders", new java.sql.SQLException("ORA-00942", "42000"));
+        }
+
         @PostMapping("/orders")
         String create(@jakarta.validation.Valid @RequestBody NewOrder order) {
             return "created";
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
new file mode 100644
index 0000000..30c43fd
--- /dev/null
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
@@ -0,0 +1,114 @@
+package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import java.sql.BatchUpdateException;
+import java.sql.SQLException;
+import java.sql.SQLIntegrityConstraintViolationException;
+import java.sql.SQLRecoverableException;
+import java.sql.SQLTransientConnectionException;
+import org.hibernate.StaleObjectStateException;
+import org.hibernate.exception.ConstraintViolationException;
+import org.hibernate.exception.JDBCConnectionException;
+import org.junit.jupiter.api.Test;
+import org.springframework.dao.DataIntegrityViolationException;
+import org.springframework.dao.OptimisticLockingFailureException;
+import org.springframework.transaction.CannotCreateTransactionException;
+
+/**
+ * Classification of the cause chains observed on Oracle ({@code OracleExceptionPathsIT} in
+ * data-jpa-autoconfigure), rebuilt here from the real types, plus the negatives: an unrelated or a
+ * syntax error stays a 500 (it is a bug, not a data conflict).
+ */
+class DataAccessFailureTest {
+
+    private static final String ORACLE_UNIQUE = "ORA-00001: unique constraint (APP.ORDERS_REF_UK) violated";
+
+    @Test
+    void translatedDuplicateKeyFromABatchedInsertIsAnIntegrityConflict() {
+        // DataIntegrityViolationException -> Hibernate ConstraintViolationException -> BatchUpdateException
+        BatchUpdateException batch = new BatchUpdateException(ORACLE_UNIQUE, "23000", 1, new int[0]);
+        Throwable chain = new DataIntegrityViolationException("could not execute batch",
+                new ConstraintViolationException("could not execute batch", batch, "APP.ORDERS_REF_UK"));
+        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
+    }
+
+    @Test
+    void untranslatedHibernateConstraintViolationIsAnIntegrityConflict() {
+        Throwable chain = new ConstraintViolationException("could not execute statement",
+                new SQLIntegrityConstraintViolationException(ORACLE_UNIQUE, "23000", 1), "APP.ORDERS_REF_UK");
+        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
+    }
+
+    @Test
+    void integritySqlStateAnywhereInTheChainIsAnIntegrityConflict() {
+        Throwable chain = new IllegalStateException("wrapped by some library",
+                new BatchUpdateException("ORA-02291: integrity constraint violated", "23000", 2291, new int[0]));
+        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
+    }
+
+    @Test
+    void lockingFailuresAreConcurrentUpdates() {
+        assertThat(DataAccessFailure.classify(new OptimisticLockingFailureException("row was updated")))
+                .contains(DataAccessFailure.CONCURRENT_UPDATE);
+        assertThat(DataAccessFailure.classify(new StaleObjectStateException("OracleWidget", 1L)))
+                .contains(DataAccessFailure.CONCURRENT_UPDATE);
+        assertThat(DataAccessFailure.classify(new jakarta.persistence.LockTimeoutException(
+                "ORA-00054: resource busy and acquire with NOWAIT specified")))
+                .contains(DataAccessFailure.CONCURRENT_UPDATE);
+        assertThat(DataAccessFailure.classify(new SQLException("deadlock", "40001")))
+                .contains(DataAccessFailure.CONCURRENT_UPDATE);
+    }
+
+    @Test
+    void noConnectionTimeoutsAndLostConnectionsAreUnavailable() {
+        Throwable poolTimeout = new CannotCreateTransactionException("Could not open JPA EntityManager",
+                new JDBCConnectionException("Unable to acquire JDBC Connection",
+                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));
+        assertThat(DataAccessFailure.classify(poolTimeout)).contains(DataAccessFailure.UNAVAILABLE);
+        assertThat(DataAccessFailure.classify(new jakarta.persistence.QueryTimeoutException(
+                "ORA-01013: user requested cancel of current operation")))
+                .contains(DataAccessFailure.UNAVAILABLE);
+        assertThat(DataAccessFailure.classify(new SQLRecoverableException("IO Error: Connection reset", "08006")))
+                .contains(DataAccessFailure.UNAVAILABLE);
+    }
+
+    @Test
+    void theOutermostClassificationWins() {
+        Throwable chain = new DataIntegrityViolationException("conflict",
+                new SQLTransientConnectionException("unrelated inner cause"));
+        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
+    }
+
+    @Test
+    void unrelatedFailuresAndSqlSyntaxErrorsAreNotClassified() {
+        assertThat(DataAccessFailure.classify(new IllegalStateException("boom"))).isEmpty();
+        assertThat(DataAccessFailure.classify(new SQLException("ORA-00942: table or view does not exist", "42000")))
+                .isEmpty();
+        assertThat(DataAccessFailure.classify(null)).isEmpty();
+    }
+
+    @Test
+    void aCauseCycleTerminates() {
+        RuntimeException cyclic = new RuntimeException("self") {
+            @Override
+            public synchronized Throwable getCause() {
+                return this;
+            }
+        };
+        assertThat(DataAccessFailure.classify(cyclic)).isEmpty();
+    }
+
+    @Test
+    void codesAndDetailsNameNoInternals() {
+        assertThat(DataAccessFailure.INTEGRITY_CONFLICT.status()).isEqualTo(409);
+        assertThat(DataAccessFailure.INTEGRITY_CONFLICT.code().value()).isEqualTo("DC-DATA-0409");
+        assertThat(DataAccessFailure.CONCURRENT_UPDATE.status()).isEqualTo(409);
+        assertThat(DataAccessFailure.CONCURRENT_UPDATE.code().value()).isEqualTo("DC-DATA-0410");
+        assertThat(DataAccessFailure.UNAVAILABLE.status()).isEqualTo(503);
+        assertThat(DataAccessFailure.UNAVAILABLE.code().value()).isEqualTo("DC-DATA-0503");
+        for (DataAccessFailure failure : DataAccessFailure.values()) {
+            assertThat(failure.detail()).doesNotContainIgnoringCase("sql").doesNotContain("ORA-");
+        }
+    }
+}
diff --git a/pom.xml b/pom.xml
index 3b13c87..b96ffea 100644
--- a/pom.xml
+++ b/pom.xml
@@ -98,6 +98,7 @@
     <module>data/platform-data-api</module>
     <module>data/platform-data-jpa-autoconfigure</module>
     <module>data/platform-starter-data-jpa</module>
+    <module>data/platform-starter-data-jpa-oracle</module>
     <module>cache/platform-cache-api</module>
     <module>cache/platform-cache-autoconfigure</module>
     <module>cache/platform-starter-cache-caffeine</module>
diff --git a/tooling/platform-service-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml b/tooling/platform-service-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml
index 9198fe8..55a1717 100644
--- a/tooling/platform-service-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml
+++ b/tooling/platform-service-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml
@@ -23,6 +23,13 @@
     <requiredProperty key="features">
       <defaultValue>none</defaultValue>
     </requiredProperty>
+    <!-- Production database of the data feature (HD12): "h2" (default: H2 locally, bring your own
+         production database starter) or "oracle" (platform-starter-data-jpa-oracle: JDBC driver + Flyway's
+         Oracle module; H2 still backs local runs and tests). "oracle" requires the data feature; anything
+         else fails generation from the pom.xml template, like features. -->
+    <requiredProperty key="database">
+      <defaultValue>h2</defaultValue>
+    </requiredProperty>
     <!-- Backstage catalog owner (group or user), stamped into catalog-info.yaml. Default keeps
          non-interactive generation working; teams override with -Downer=<team> (phase-16 E). -->
     <requiredProperty key="owner">
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/README.md b/tooling/platform-service-archetype/src/main/resources/archetype-resources/README.md
index 148161c..97fe8b9 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/README.md
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/README.md
@@ -37,7 +37,7 @@ platform secrets source — never from `application.yml`:
 | Setting | When |
 |---|---|
 | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (or `issuer-uri`) | always |
-| `spring.datasource.url` / `username` / `password` (+ the JDBC driver dependency) | `data` feature |
+| `spring.datasource.url` / `username` / `password` (generate with `-Ddatabase=oracle` for the Oracle driver + Flyway module) | `data` feature |
 | `app.greeting.base-url` | `restclient` feature sample |
 
 A missing value fails startup with a message naming it: `prod` never falls back to the offline
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/pom.xml b/tooling/platform-service-archetype/src/main/resources/archetype-resources/pom.xml
index 1c9df22..18a4721 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/pom.xml
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/pom.xml
@@ -9,6 +9,13 @@
 #if( $features != "none" && ( $featureRest != "," || $featureSet.contains(",none,") ) )
 #set( $rejected = $featureSet.matches("[dc-platform archetype: unsupported features value ${features} -- use none or a comma-separated subset of messaging,data,restclient without spaces") )
 #end
+## database: "h2" (default) or "oracle", and "oracle" only together with the data feature (HD12).
+#if( $database != "h2" && $database != "oracle" )
+#set( $rejected = $featureSet.matches("[dc-platform archetype: unsupported database value ${database} -- use h2 or oracle") )
+#end
+#if( $database == "oracle" && !$featureSet.contains(",data,") )
+#set( $rejected = $featureSet.matches("[dc-platform archetype: database=oracle needs the data feature -- add data to -Dfeatures") )
+#end
 <project xmlns="http://maven.apache.org/POM/4.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
@@ -76,13 +83,26 @@
 #end
 #if($featureSet.contains(",data,"))
 
+#if($database == "oracle")
+    <!-- feature: data on Oracle — platform JPA conventions (snake_case, auditing, Flyway migrations under
+         db/migration) plus the Oracle JDBC driver and Flyway's Oracle module. H2 backs local runs and
+         tests; the prod profile refuses the embedded fallback, so production supplies an Oracle
+         spring.datasource.url (jdbc:oracle:thin:@//host:1521/service) and credentials. -->
+    <dependency>
+      <groupId>ae.gov.dubaicustoms.platform</groupId>
+      <artifactId>platform-starter-data-jpa-oracle</artifactId>
+    </dependency>
+#else
     <!-- feature: data — platform JPA conventions (snake_case, auditing, Flyway migrations under
          db/migration). H2 backs local runs and tests; the prod profile refuses the embedded fallback, so
-         production must supply spring.datasource.* (add its JDBC driver here). -->
+         production must supply spring.datasource.* for a real database. For Oracle, regenerate with
+         -Ddatabase=oracle or replace this starter with platform-starter-data-jpa-oracle: a JDBC driver
+         alone is not enough, Flyway 10+ also needs its database module. -->
     <dependency>
       <groupId>ae.gov.dubaicustoms.platform</groupId>
       <artifactId>platform-starter-data-jpa</artifactId>
     </dependency>
+#end
     <dependency>
       <groupId>com.h2database</groupId>
       <artifactId>h2</artifactId>
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/db/migration/V1__create_note.sql b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/db/migration/V1__create_note.sql
index 41206ec..cedcfaf 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/db/migration/V1__create_note.sql
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/db/migration/V1__create_note.sql
@@ -2,8 +2,18 @@
 #if($featureSet.contains(",data,"))
 -- Feature sample (features=data): schema for the Note entity. Flyway owns the schema (Hibernate DDL is
 -- off once Flyway runs); add V2__..., V3__... for every change instead of editing this file.
+#if($database == "oracle")
+-- Runs on Oracle in production and on H2 locally, so only types both accept (Oracle has no BIGINT;
+-- numeric(19) is NUMBER(19) there). Keep migrations 19c-safe: no BOOLEAN columns, no IF [NOT] EXISTS,
+-- TIMESTAMP WITH TIME ZONE for Instant fields (docs/modules/data.md).
+create table note (
+    id   numeric(19) generated by default as identity primary key,
+    text varchar(255) not null
+);
+#else
 create table note (
     id   bigint generated by default as identity primary key,
     text varchar(255) not null
 );
 #end
+#end
diff --git a/tooling/platform-service-archetype/src/test/resources/projects/oracle/archetype.properties b/tooling/platform-service-archetype/src/test/resources/projects/oracle/archetype.properties
new file mode 100644
index 0000000..3e490dd
--- /dev/null
+++ b/tooling/platform-service-archetype/src/test/resources/projects/oracle/archetype.properties
@@ -0,0 +1,8 @@
+# data service on Oracle (database=oracle): Oracle starter, H2 still backs the generated tests.
+groupId=com.dc.demo
+artifactId=demo-oracle
+version=1.0.0-SNAPSHOT
+package=com.dc.demo.oracle
+features=data
+database=oracle
+owner=platform-team
diff --git a/tooling/platform-service-archetype/src/test/resources/projects/oracle/goal.txt b/tooling/platform-service-archetype/src/test/resources/projects/oracle/goal.txt
new file mode 100644
index 0000000..0b59873
--- /dev/null
+++ b/tooling/platform-service-archetype/src/test/resources/projects/oracle/goal.txt
@@ -0,0 +1 @@
+verify
diff --git a/tooling/scripts/golden-path.sh b/tooling/scripts/golden-path.sh
index 56b0655..e89f0e4 100755
--- a/tooling/scripts/golden-path.sh
+++ b/tooling/scripts/golden-path.sh
@@ -4,8 +4,10 @@
 #
 #   1. install the platform (tests skipped: the tested reactor build is the separate `mvn verify` gate)
 #      into an ISOLATED Maven repository, and record the archetype that was installed;
-#   2. reject invalid `features` input (unknown token, substring lookalike, spaces, none+feature);
-#   3. for every scenario — all 8 combinations of the optional tokens messaging/data/restclient, one of
+#   2. reject invalid `features` input (unknown token, substring lookalike, spaces, none+feature) and
+#      invalid `database` input (unknown value, oracle without data);
+#   3. for every scenario — all 8 combinations of the optional tokens messaging/data/restclient (plus
+#      data with database=oracle: the Oracle starter is selected, H2 still backs the run), one of
 #      them with every optional property omitted, one relocated (groupId != package, hyphenated
 #      artifactId, path with a space) — generate OUTSIDE the checkout, assert the generated files, run the
 #      generated `mvn verify` (every expected test class must run: no zero-test or skipped passes), check
@@ -126,13 +128,14 @@ UNTRUSTED=$(cat "$IDP_DIR/token-untrusted"); EXPIRED=$(cat "$IDP_DIR/token-expir
 echo "   jwks=$JWKS"
 
 # ---------------------------------------------------------------------------------------------------
-generate() { # log dir groupId artifactId package features(-=omit) platformVersion(-=omit)
-  local log=$1 dir=$2 group=$3 artifact=$4 pkg=$5 features=$6 version=$7
+generate() { # log dir groupId artifactId package features(-=omit) platformVersion(-=omit) database(-=omit)
+  local log=$1 dir=$2 group=$3 artifact=$4 pkg=$5 features=$6 version=$7 database=${8:--}
   local args=(-DarchetypeGroupId="$GROUP_ID" -DarchetypeArtifactId=platform-service-archetype
               -DarchetypeVersion="$REV" -DgroupId="$group" -DartifactId="$artifact" -Dpackage="$pkg"
               -DinteractiveMode=false)
   [ "$features" != "-" ] && args+=("-Dfeatures=$features")
   [ "$version" != "-" ] && args+=("-DplatformVersion=$version")
+  [ "$database" != "-" ] && args+=("-Ddatabase=$database")
   mkdir -p "$dir"
   (cd "$dir" && "${MVN[@]}" "$ARCHETYPE_PLUGIN:generate" "${args[@]}" -l "$(native_path "$log")")
 }
@@ -150,6 +153,18 @@ for bad in kafka database "data, restclient" none,data data,data; do
       || fail "features='$bad' left a buildable partial project"
   echo "   rejected: '$bad'"
 done
+# database: only h2/oracle, and oracle only together with the data feature (HD12).
+for bad in "postgres|data|unsupported database value" "oracle|restclient|needs the data feature"; do
+  IFS='|' read -r db feats message <<< "$bad"
+  slug="db-$db-$(printf '%s' "$feats" | tr -c 'a-z' '_')"
+  if generate "$EVIDENCE/negative-$slug.log" "$NEG" com.dc.demo "neg-$slug" com.dc.demo "$feats" "$REV" "$db"; then
+    fail "database='$db' with features='$feats' was accepted"
+  fi
+  grep -q "$message" "$EVIDENCE/negative-$slug.log" \
+      || fail "database='$db' with features='$feats' failed without the actionable message"
+  [ ! -s "$NEG/neg-$slug/pom.xml" ] || fail "database='$db' left a partial project"
+  echo "   rejected: database='$db' features='$feats'"
+done
 
 # ---------------------------------------------------------------------------------------------------
 has() { case ",$1," in *",$2,"*) return 0 ;; *) return 1 ;; esac; }
@@ -176,14 +191,14 @@ assert_generated() { # project package features artifactId
   for feature in messaging data restclient; do
     case $feature in
       messaging) starter=platform-starter-messaging-inmemory; sample="$src/messaging/OrderEvents.java" ;;
-      data) starter=platform-starter-data-jpa; sample="$src/data/Note.java" ;;
+      data) starter="platform-starter-data-jpa(-oracle)?"; sample="$src/data/Note.java" ;;
       restclient) starter=platform-starter-restclient; sample="$src/client/GreetingClient.java" ;;
     esac
     if has "$features" "$feature"; then
-      grep -q "<artifactId>$starter</artifactId>" "$p/pom.xml" || fail "$artifact: $feature enabled but $starter missing"
+      grep -qE "<artifactId>$starter</artifactId>" "$p/pom.xml" || fail "$artifact: $feature enabled but $starter missing"
       [ -s "$sample" ] || fail "$artifact: $feature enabled but $sample is empty"
     else
-      ! grep -q "<artifactId>$starter</artifactId>" "$p/pom.xml" || fail "$artifact: $feature disabled but $starter present"
+      ! grep -qE "<artifactId>$starter</artifactId>" "$p/pom.xml" || fail "$artifact: $feature disabled but $starter present"
       [ ! -s "$sample" ] || fail "$artifact: $feature disabled but $sample has content"
     fi
   done
@@ -323,16 +338,22 @@ probe_http() { # label features log artifact [prod]
   fi
 }
 
-run_scenario() { # id features groupId artifactId package platformVersion subdir
-  local id=$1 features=$2 group=$3 artifact=$4 pkg=$5 version=$6 subdir=$7 t0 dir p tests jar log effective
+run_scenario() { # id features groupId artifactId package platformVersion subdir database
+  local id=$1 features=$2 group=$3 artifact=$4 pkg=$5 version=$6 subdir=$7 database=${8:--} t0 dir p tests jar log effective
   t0=$(date +%s)
   effective=$([ "$features" = "-" ] && echo none || echo "$features")
   step "Scenario $id (features=$effective, groupId=$group, artifactId=$artifact, package=$pkg)"
   dir="$WORKDIR/scenarios/$id${subdir:+/$subdir}"
-  generate "$EVIDENCE/$id-generate.log" "$dir" "$group" "$artifact" "$pkg" "$features" "$version" \
+  generate "$EVIDENCE/$id-generate.log" "$dir" "$group" "$artifact" "$pkg" "$features" "$version" "$database" \
       || fail "$id: generation failed (see $EVIDENCE/$id-generate.log)"
   p="$dir/$artifact"
   assert_generated "$p" "$pkg" "$effective" "$artifact"
+  if [ "$database" = oracle ]; then
+    grep -q "<artifactId>platform-starter-data-jpa-oracle</artifactId>" "$p/pom.xml" \
+        || fail "$id: database=oracle did not select platform-starter-data-jpa-oracle"
+  elif grep -q "<artifactId>platform-starter-data-jpa-oracle</artifactId>" "$p/pom.xml"; then
+    fail "$id: the Oracle starter appeared without database=oracle"
+  fi
   (cd "$p" && ${TIMEOUT[@]+"${TIMEOUT[@]}"} "${MVN[@]}" verify -l "$(native_path "$EVIDENCE/$id-verify.log")") \
       || fail "$id: generated mvn verify failed (see $EVIDENCE/$id-verify.log)"
   tests=$(assert_tests "$p" "$effective" "$artifact")
@@ -416,7 +437,7 @@ requested_service_probes() { # project jar artifactId
 }
 
 # ---------------------------------------------------------------------------------------------------
-# id | features (- = omit) | groupId | artifactId | package | platformVersion (- = omit) | subdir
+# id | features (- = omit) | groupId | artifactId | package | platformVersion (- = omit) | subdir | database (- = omit)
 SCENARIO_TABLE="$(cat <<EOF
 none-defaults|-|com.dc.demo|demo-defaults|com.dc.demo|-|
 messaging|messaging|com.dc.demo|demo-messaging|com.dc.demo|$REV|
@@ -426,15 +447,16 @@ messaging-data|messaging,data|com.dc.demo|demo-messaging-data|com.dc.demo|$REV|
 messaging-restclient|messaging,restclient|com.dc.demo|demo-messaging-restclient|com.dc.demo|$REV|
 data-restclient|data,restclient|com.dc.demo|demo-service|com.dc.demo|$REV|
 all-relocated|restclient,messaging,data|org.example.billing|billing-svc|com.acme.billing.core|$REV|path with space
+data-oracle|data|com.dc.demo|demo-oracle|com.dc.demo|$REV||oracle
 EOF
 )"
 
 SELECTED="${GP_SCENARIOS:-all}"
 RAN=0
-while IFS='|' read -r id features group artifact pkg version subdir; do
+while IFS='|' read -r id features group artifact pkg version subdir database; do
   [ -n "$id" ] || continue
   if [ "$SELECTED" != all ] && ! has "$SELECTED" "$id"; then continue; fi
-  run_scenario "$id" "$features" "$group" "$artifact" "$pkg" "$version" "$subdir"
+  run_scenario "$id" "$features" "$group" "$artifact" "$pkg" "$version" "$subdir" "${database:--}"
   RAN=$((RAN + 1))
 done <<< "$SCENARIO_TABLE"
 [ "$RAN" -gt 0 ] || fail "no scenario matched GP_SCENARIOS=$SELECTED"
```
