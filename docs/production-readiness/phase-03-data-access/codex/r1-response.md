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
