# Re-evaluation — DC Platform hardening Phase 3 (Data Access, Oracle), Round 1

You reviewed commit 2c3fc78 (your working directory is that checkout). Below are your findings and the
implementer's point-by-point response, which includes measurements from Oracle container runs. For EACH
finding F1–F6 and each AC row, answer exactly one of: **accept** (the response/fix resolves it),
**reject** (the response is wrong — say why with code references), or **maintain concern** (partly
addressed — say what remains). Judge the proposed fixes on their merits; they are not implemented in this
checkout yet, so assess whether each would resolve the failure scenario. Severity disagreements: say
whether you accept the implementer's severity. Be specific and brief. Output a table
`| Item | Verdict | Reason |` and then any new concern the response introduces.

## Your findings

Reviewed `60afe86..2c3fc78`. Found **five High and one Medium defects**. No Critical or Low findings, confirmed default-response credential leak, or 19c syntax defect in the new Oracle SQL.

This was a static review. Maven/Docker tests were not run in the read-only checkout; excluded production-readiness evidence was not assessed.

### F1 — Declared repository queries have no statement-timeout default
- Severity: High
- Location: `data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java:68`
- Failure scenario: A controller calls an unannotated repository `@Query` method whose Oracle SELECT takes 45 seconds. It occupies a connection for 45 seconds and succeeds instead of hitting the intended statement timeout.
- Proof or reasoning: The defaults configure only the transaction timeout; `jakarta.persistence.query.timeout` is absent. Declared repository queries do not automatically start transactions, so the transaction timeout does not protect this path. The 60-second socket timeout is a separate, later guard. Every JPA timeout test supplies an explicit hint or runs inside a transaction. [Spring Data transaction semantics](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).
- Proposed fix: Contribute and verify a default query timeout below the Oracle read timeout. Test a declared repository query and direct nontransactional `EntityManager` query without explicit hints, including cancellation and recovery.

### F2 — Separate Flyway owner connections bypass the network timeout defaults
- Severity: High
- Location: `data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java:30`
- Failure scenario: Configure the required separate Flyway owner credentials through `spring.flyway.user/password`. Oracle accepts a connection but stops responding during migration or validation. Startup remains blocked without the platform’s read-timeout bound.
- Proof or reasoning: The post-processor modifies only Spring-managed `HikariDataSource` beans. With separate Flyway credentials, Boot constructs an internal `SimpleDriverDataSource`; it neither receives this post-processing nor inherits Hikari’s arbitrary driver properties. Spring transaction timeouts also do not govern Flyway migration execution. [Boot’s datasource construction](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.0/module/spring-boot-flyway/src/main/java/org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration.java). Oracle’s socket read timeout has no default value. [Oracle connection properties](https://docs.oracle.com/en/database/oracle/oracle-database/26/jajdb/oracle/jdbc/OracleConnection.html).
- Proposed fix: Apply overridable Oracle network bounds to the actual Flyway datasource before its first connection. Test startup through Toxiproxy using separate owner/runtime credentials, including a connection that accepts TCP and then stops responding.

### F3 — An expired Hibernate transaction becomes a nonretryable 500
- Severity: High
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java:49`
- Failure scenario: A transaction performs database work, spends its remaining timeout waiting on another operation, then issues another query. The response is `500 DC-CORE-0500`, `retryable=false`, instead of the required retryable `503 DC-DATA-0503`.
- Proof or reasoning: Hibernate’s `JdbcCoordinatorImpl.determineRemainingTransactionTimeOutPeriod()` throws `org.hibernate.TransactionException("Transaction timeout expired")` without an SQL cause. This was confirmed in the locally installed Hibernate 7.4.1 bytecode. Neither that type nor its generic superclass is classified. Repository translation can wrap it in `JpaSystemException`, which also remains unclassified. The tests cover cancellation of an executing statement, not expiry before a subsequent statement. [Hibernate timeout implementation](https://raw.githubusercontent.com/hibernate/hibernate-orm/main/hibernate-core/src/main/java/org/hibernate/engine/jdbc/internal/JdbcCoordinatorImpl.java), [Spring translation fallback](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-orm/src/main/java/org/springframework/orm/jpa/hibernate/HibernateExceptionTranslator.java).
- Proposed fix: Recognize or normalize this specific transaction-expiry path without categorizing every Hibernate `TransactionException` as retryable. Add real transaction-expiry tests through both repository and direct `EntityManager` paths, then assert the HTTP contract.

### F4 — Direct EntityManager NOT NULL failures can still return 500
- Severity: High
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java:34`
- Failure scenario: With Hibernate nullability checking enabled, a service directly persists an entity whose `@Column(nullable=false)` property is null. Hibernate rejects it before SQL execution; the platform returns `500 DC-CORE-0500` instead of `409 DC-DATA-0409`.
- Proof or reasoning: Hibernate raises `org.hibernate.PropertyValueException`. It is absent from `INTEGRITY_TYPES` and has no SQLState fallback. Spring explicitly translates this exception into `DataIntegrityViolationException` behind a repository proxy, making the same failure’s status depend on the access path. [Spring’s translation](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-orm/src/main/java/org/springframework/orm/jpa/hibernate/HibernateExceptionTranslator.java). The Oracle fixtures do not exercise this nullability-checking path.
- Proposed fix: Classify the raw nullability failure consistently with its translated equivalent. Add a nullable-false entity test covering direct persistence, repository persistence, fixed response detail, and trace correlation.

### F5 — Another customizer can enable out-of-order migrations after the guard
- Severity: High
- Location: `data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/FlywayOutOfOrderGuard.java:37`
- Failure scenario: Under `prod`, a later-registered auto-configuration contributes an unordered `FlywayConfigurationCustomizer` that enables out-of-order migrations. The guard first observes `false`; the later customizer sets `true`; Flyway migrates without the required startup failure.
- Proof or reasoning: `LOWEST_PRECEDENCE` does not establish a unique final position. Unordered customizers have the same effective order, and equal order values do not guarantee relative ordering. Boot executes the customizer stream and then loads Flyway, with no subsequent platform check. [Spring ordering contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/Ordered.html), [Boot customizer execution](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.0/module/spring-boot-flyway/src/main/java/org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration.java). Existing tests exercise only the property-driven case.
- Proposed fix: Validate the completed Flyway configuration after all configuration customizers, before migration begins. Test a later-registered unordered customizer and one explicitly using `LOWEST_PRECEDENCE`.

### F6 — Mixed local/prod profiles enable production leak detection
- Severity: Medium
- Location: `data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java:69`
- Failure scenario: Start with `spring.profiles.active=prod,local`. A legitimate transaction holds a connection for 25 seconds and generates a leak-warning stack trace in production.
- Proof or reasoning: The condition checks only whether `local` is active. It contributes the 20-second threshold even when `prod` is also active, contrary to R2.2’s “never in prod” requirement. Tests check each profile separately.
- Proposed fix: Suppress the default whenever `prod` is active, or reject the conflicting profile combination. Add a mixed-profile test.

## Acceptance criteria not met

“Not demonstrated” below identifies missing coverage or unavailable execution evidence; it does not assert that an unobserved run failed.

| AC | Gap |
|---|---|
| **AC1** | The Oracle generation scenario builds/runs locally on H2. No reviewed consumer scenario proves the generated service boots in `prod` against Oracle and migrates. |
| **AC2** | Oracle tests exercise persistence and commit-time failures, but do not explicitly verify rollback of a successful write followed by an application runtime exception. Consumer coverage is absent. R3’s checked-exception and service read-only guidance is also missing. |
| **AC3** | F4 violates NOT NULL mapping. Oracle tests inspect exception chains while MVC tests construct exceptions separately; they do not prove the complete constraint matrix through HTTP or full-detail logging once on the trace through the masks. |
| **AC4** | The container test overrides acquisition timeout to one second and exercises one extra transaction. It does not establish concurrent HTTP 503 timing, bounded request accumulation, and recovery under the production defaults. |
| **AC5** | F1 and F3 affect timeout behavior. No reviewed Oracle/Toxiproxy consumer test establishes latency/read-timeout handling, bounded threads, and recovery. |
| **AC6** | No reviewed Oracle outage/recovery consumer test checks database requests, unrelated requests, and all three health groups together. Existing PostgreSQL fixtures are not Oracle certification evidence. |
| **AC7** | F5 bypasses the startup guard. Checksum, naming, and clean-refusal tests run on H2 only; required Oracle container coverage is missing. |
| **AC8** | Several dialect behaviors are covered, including the resolved version assertion. However, `OffsetDateTime` round-tripping is untested, and the oversized-identifier test executes JDBC DDL directly rather than demonstrating a clear migration-time failure. |
| **AC9** | No owner/runtime-role consumer fixture proves DML through `CURRENT_SCHEMA` and denied `ALTER`/`DROP`. The required operational model is also absent from `docs/modules/data.md`. |
| **AC10** | F6 violates the production leak-detection restriction. Oracle driver defaults are inserted into Hikari directly and are absent from the specified `platform-data-jpa-defaults` environment source. No actuator-source verification is present. |
| **AC11** | No reviewed data test asserts Hikari and repository meters together, their permitted tags, or absence of SQL/ID values. |
| **AC12** | Descriptor guarding is covered, but the matrix is incomplete: no replacement test for `platformFlywayOutOfOrderGuard`, no explicit kill-switch assertions for the new guard/post-processor, and no complete `DataJpaProperties` default snapshot. |
| **AC13** | No reviewed consumer test sends SIGTERM during an Oracle transaction and verifies its outcome, pool closure, and exit 143. |
| **AC14** | F2 leaves the separate migration connection unbounded. No reviewed slow/down Oracle startup test records and asserts the startup budget. |
| **AC15** | The ninth golden-path scenario exists, but successful nine-scenario execution and downstream `-amd test-compile` results are not available in the reviewed material. |
| **AC16** | Phase 1/2 rerun results are not available in the reviewed material. Additionally, `tooling/hardening/scenarios/phase-02-logging-observability.sh:215` still accepts **any 5xx** for DB-down, so it cannot detect regression to the old generic 500 contract. |

## Implementer response

# Phase 3 — Round 1: implementer response to `r1-review.out.md`

Reviewed commit `2c3fc78`. Every claim below that cites a measurement comes from an exploratory Oracle
container test written for this response (`OracleTimeoutDefaultsIT`, Oracle 23ai Free, Hibernate in 19
mode, ojdbc11 23.26.2, Hibernate 7.4.1, Spring Boot 4.1.0), with `spring.transaction.default-timeout=1s`
and the SQL-engine work `select count(*)` over a 20000 × 20000 `connect by` cross join (about 10 s
uncancelled). Raw logs: `evidence-raw/r1-explore.log`, `evidence-raw/r1-explore-2.log`.

## Findings

### F1 — repository queries without a statement timeout — **Agree** (High)

Measured. Without a transaction neither path is bounded by the transaction timeout:

| Path (no surrounding transaction) | Result |
|---|---|
| declared `@Query` repository method, no `@Transactional` | ran to completion, 9 304 ms, no exception |
| `EntityManager.createNativeQuery(...)` | ran to completion, 9 815 ms, no exception |

Proposed fix: contribute `spring.jpa.properties.jakarta.persistence.query.timeout=30000` (Hibernate applies
it as the default hint to every query) and `spring.jdbc.template.query-timeout=30s` (the same gap for
`JdbcTemplate`) in `platform-data-jpa-defaults`. Measured with a 4000 ms default:

| Path | Result |
|---|---|
| declared query, no transaction | cancelled at 4 699 ms → `org.springframework.dao.QueryTimeoutException` (ORA-01013) |
| `EntityManager` query, no transaction | cancelled at 4 608 ms → `jakarta.persistence.QueryTimeoutException` |
| `EntityManager` query in a 1 s transaction | cancelled at 1 989 ms: the transaction's remaining time still wins |
| declared query in a 1 s transaction | cancelled at 1 994 ms |

Inside a transaction Spring sets the remaining transaction time as the query's own hint
(`EntityManagerFactoryUtils.applyTransactionTimeout`), which overrides the persistence-unit default, so
the default never lengthens a transaction-bounded statement. Residual, documented: `EntityManager.find`
outside a transaction is not a query and gets no statement timeout; the 60 s read timeout bounds it.
Tests: the measured cases become assertions (`OracleTimeoutDefaultsIT`), plus the config-default snapshot.

### F2 — a separate Flyway connection bypasses the Oracle network bounds — **Agree** (High)

Confirmed in the Spring Boot 4.1.0 bytecode: `FlywayAutoConfiguration$FlywayConfiguration.getMigrationDataSource`
builds `DataSourceBuilder.derivedFrom(dataSource).type(SimpleDriverDataSource.class)` (or `create()` with
`spring.flyway.url`) whenever `spring.flyway.user`/`url` is set. That data source is not a bean, so
`OracleConnectionDefaults` never sees it. This is exactly the owner/runtime split AC9 requires.

Proposed fix: a bean post-processor on the `Flyway` bean (its configuration is complete there, and
`FlywayMigrationInitializer` migrates only afterwards) that adds the same two Oracle properties to the
connection properties of a driver-based Flyway data source with a `jdbc:oracle:` URL. An explicit JVM
system property still wins. Tests: a context-runner test with `spring.flyway.user` and an Oracle URL
(migration strategy replaced by a no-op, so nothing connects) asserting the properties on the data source
Flyway uses; an Oracle container test migrating through a separate Flyway user. The Toxiproxy
"accepts TCP, then silent" startup case is AC14, Gate F.

### F3 — an expired transaction becomes a non-retryable 500 — **Partly agree** (High)

Measured — two different paths, only one of them is defective:

| Expiry path (1 s transaction, 1.5 s sleep, then …) | Exception chain | Today |
|---|---|---|
| next **query** via `EntityManager` | `org.springframework.transaction.TransactionTimedOutException` | 503 (already classified) |
| next **query** via a repository | `TransactionTimedOutException` | 503 |
| explicit `em.flush()` of a pending insert | `org.hibernate.TransactionException("Transaction timeout expired")` | **500** |
| commit of a pending repository insert | `JpaSystemException` → `org.hibernate.TransactionException("Transaction timeout expired")` | **500** |

Spring checks the deadline before handing a query its remaining time, so the query path never reaches
Hibernate's check; the flush and commit paths do. Proposed fix as suggested: classify
`org.hibernate.TransactionException` as `UNAVAILABLE` only when its message is exactly Hibernate's
`"Transaction timeout expired"` (`JdbcCoordinatorImpl.determineRemainingTransactionTimeOutPeriod`, 7.4.1);
every other `TransactionException` stays unclassified. The Oracle test asserts the observed chains, so a
Hibernate upgrade that changes the message fails the build instead of silently returning 500.

### F4 — raw NOT NULL failure via `EntityManager` returns 500 — **Agree** (Medium, not High)

Agree on the defect: with Hibernate's nullability check on, `PropertyValueException` is thrown before any
SQL and nothing classifies it. Severity: the check is **off** by default when Bean Validation is on the
classpath (the archetype's validation starter), and then the database rejects the row (ORA-01400 → 409,
`OracleExceptionPathsIT`). It returns 500 only for a data-only service without Bean Validation, which gets
the wrong code but leaks nothing (fixed detail). Proposed fix: add `org.hibernate.PropertyValueException`
to the integrity types. `TransientPropertyValueException` (an unsaved reference, a programming error) is
**not** a subclass (`javap`: it extends `TransientObjectException`), so it stays a 500. Tests: unit test
with the real Hibernate type; Oracle test with `hibernate.check_nullability=true` recording the chain for
direct persist.

### F5 — a later customizer can re-enable out-of-order — **Agree** (High)

`Ordered.LOWEST_PRECEDENCE` does not guarantee last place among equal orders. Proposed fix: the guard
becomes a bean post-processor on the `Flyway` bean and checks `flyway.getConfiguration().isOutOfOrder()`
on the finished configuration (after every customizer, `flyway.conf` and properties), before
`FlywayMigrationInitializer` runs. Tests: an unordered customizer and a `LOWEST_PRECEDENCE` customizer that
each enable out-of-order fail startup under `prod` with the failure-analyzer message.

### F6 — `prod,local` gets leak detection — **Agree** (Medium)

Proposed fix: contribute the threshold only when `local` is active **and** `prod` is not; mixed-profile test.

## Acceptance criteria not met

| AC | Response |
|---|---|
| AC1, AC4, AC5, AC6, AC9, AC13, AC14 | Not a Gate C deliverable: these are CONSUMER criteria (or need it), run at Gate F, after review. The container halves exist (pool exhaustion → `CannotCreateTransactionException`, timeouts, driver bounds). |
| AC2 | **Agree**: add an Oracle test — runtime exception rolls back (no row), checked exception commits per Spring's default rule — and document both plus `@Transactional(readOnly = true)` for service read methods in `data.md`. |
| AC3 | **Partly agree.** The unit tests build exactly the chains `OracleExceptionPathsIT` records from real Oracle failures, so classification is proven per chain; HTTP end-to-end (body, `traceId`, a single WARN on the trace) is the CONSUMER half at Gate F. F4 fixed as above. |
| AC7 | **Agree in part**: add an Oracle container test that `out-of-order=true` under `prod` fails startup. Naming and checksum are Flyway's own client-side checks, independent of the database; the H2 tests stay the evidence for those. |
| AC8 | **Disagree** on `OffsetDateTime`: AC8 names `Instant` across zones, which is covered. The 128-character identifier test runs the DDL over JDBC; Flyway sends the same statement and gets the same ORA-00972 from the server. No change. |
| AC10 | **Disagree, with a scope amendment.** The Oracle driver properties were deliberately moved out of the environment source: a URL supplied by a later property source (`@DynamicPropertySource`, a config server) was missed by the environment-based version, so they are applied to the pool itself, keyed on its own URL (`OracleConnectionDefaultsTest`). Amendment A1: AC10 = pool and transaction defaults visible in `platform-data-jpa-defaults`; Oracle driver properties visible on the pool's `data-source-properties`. F6 fixed as above. |
| AC11 | **Agree**: add a slice test of the Hikari pool meters and Spring Data repository meters, asserting the tag keys are bounded and no tag value contains SQL or an ID. Consumer half at Gate F. |
| AC12 | **Agree**: add replacement tests for `platformFlywayOutOfOrderGuard` and `platformOracleConnectionDefaults` (and the new Flyway data-source post-processor), kill-switch assertions for them, and a `DataJpaProperties` default snapshot. |
| AC15 | Executed but not in the reviewer's checkout by design (the evidence folder is withheld at first pass): golden path 9/9 on `f9d1a86`, downstream `-amd test-compile` of the changed modules (13 modules). |
| AC16 | Phase 1 consumer 92/92 on Phase 3 code (same withholding). **Agree** on the Phase 2 harness: DB-down accepts any 5xx; tighten it to `503 DC-DATA-0503` when the Phase 2 consumer is rerun at Gate F. |
