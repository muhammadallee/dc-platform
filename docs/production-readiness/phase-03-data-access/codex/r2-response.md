# Phase 3 — Round 2: implementer response to `r2-review.out.md`

Reviewed commit `db83fae`. Measurements come from Oracle container runs (Oracle 23ai Free, Hibernate 7.4.1
in 19 mode, ojdbc11, Spring Boot 4.1.0, HikariCP 7.0.2) in `OracleTimeoutDefaultsIT`, with the defaults
shortened to `spring.transaction.default-timeout=1s` and `jakarta.persistence.query.timeout=4000`, and the
same SQL-engine cross join as round 1 (about 10 s uncancelled; a cancel lands about 1 s after its limit).
Raw logs: `evidence-raw/r2-native-session*.log`, `evidence-raw/r2-f1-red.log`.

## Round-1 dispositions

F3, F4, F5, F6, AC2, AC3, AC7, AC8, AC9, AC10 (supported sources), AC11, AC12, AC15 and AC16 are
confirmed; no response is needed. F1 and F2 are partial because of R2-F2 and R2-F3, answered below. On the
F2 `{vendor}` concern: **not fixed** is correct. It was carried as a documented residual for the Gate E
issue register in round 1, not claimed as fixed.

## R2-F1 — Oracle defaults crash an already-started custom Hikari pool — **Agree** (High) — FIXED

Reproduced before the fix: a context with `new HikariDataSource(config)` (Oracle URL, one timeout set)
fails startup with `IllegalStateException: The configuration of the pool is sealed once started`, and so
does the unit case (`evidence-raw/r2-f1-red.log`).

Fix: `OracleConnectionDefaults` skips a pool that is running or closed (`HikariDataSource.isRunning()`
and `isClosed()`, both public in 7.0.2). Such a pool's configuration is sealed and the application
owns it. `data.md` says to set both driver properties on its config.

Tests:
- `OracleConnectionDefaultsTest.aPoolTheApplicationAlreadyStartedIsLeftAlone` (unit);
- `PlatformDataJpaAutoConfigurationTest.anOraclePoolTheApplicationStartedItselfDoesNotFailStartup` (context: startup succeeds, and the application's value is unchanged).

Both use `minimumIdle=0` and `initializationFailTimeout=-1`, so no connection is attempted.

## R2-F2 — the query default can override an explicit transaction timeout — **Partly agree**, Medium (Codex: High)

Measured per path. The default-query-timeout fix (round-1 F1) introduced a regression, but only on
paths that bypass Spring's query handling:

| Path inside a transaction | 1 s transaction | 6 s transaction | Verdict |
|---|---|---|---|
| repository method, `@Query`, injected `EntityManager.createQuery/createNativeQuery` | ~1.1–2.0 s | ~7.0 s | transaction time wins (round 1) |
| `em.unwrap(Session.class).createNativeQuery(sql)` | 1.1 s | 7.0 s | transaction time wins |
| `em.unwrap(Session.class).createQuery(jpql, Long.class)` | — | 7.0 s | transaction time wins |
| `em.unwrap(Session.class).createSelectionQuery(jpql, Long.class)` | **5.0 s** | **5.0 s** | query default wins |
| `((EntityManager) em.getDelegate()).createQuery(jpql, …)` | — | **5.0 s** | query default wins |
| `createSelectionQuery(…).setTimeout(6)` | — | 7.0 s | query's own timeout wins (the workaround) |

The example in the review, `getDelegate().createNativeQuery`, is the affected kind. The unwrapped
`Session`'s `createQuery` and `createNativeQuery` are not affected. In Spring 7, `unwrap` on the shared
`EntityManager` still applies the transaction time to the `jakarta.persistence.Query` it returns.

Why Medium:
- The statement is still bounded: by the query default (30 s in production) and, behind it, by the
  60 s Oracle read timeout.
- A statement that starts after the deadline still fails, and so does the commit (round-1 F3).
- Only two Hibernate-specific paths are affected. Every Spring Data path and the standard JPA
  `EntityManager` API keep the transaction budget.

Decision: keep the default and document it rather than make the fallback transaction-aware. That would
mean hooking each transaction begin (a `TransactionExecutionListener`) and rewriting a Hibernate session
property. That property is fixed at query creation, so it would still only approximate the remaining
time, and it would couple the platform to Hibernate's session internals. Changes:
- `data.md` (bounded waits) and the CHANGELOG name the two paths and the workaround (`.setTimeout(…)` on
  the query).
- The decision log adds a D110 round-2 addendum.
- The comment in `PlatformDataJpaEnvironmentPostProcessor` is corrected; round 1's "never lengthens a
  statement" was wrong for these paths.
- Pinned by `OracleTimeoutDefaultsIT.aQueryCreatedOnTheNativeSessionKeepsTheTransactionTime` and
  `…aQueryThatBypassesSpringsQueryHandlingTakesTheQueryDefaultInsteadOfTheTransactionTime`, including
  the workaround.
- Registered for Gate E.

## R2-F3 — custom Flyway beans miss the Oracle network bounds — **Agree on the gap**, Medium (Codex: High) — documented residual

Correct: `Flyway.configure().dataSource(url, user, password)` builds Flyway's own internal
`DriverDataSource`, which neither post-processor recognises. Why Medium rather than High:
- A `Flyway` bean defined by the application makes Boot's Flyway auto-configuration back off. The
  platform's contract (AC14, R10.2) covers the connections Boot creates, the pool and Boot's separate
  Flyway user, and both are bounded and tested.
- Changing the connection properties on a Flyway internal class would tie the platform to Flyway
  internals.
- The JVM system properties already bound every Oracle connection, this one included, and are
  verified by `OracleFlywayPostureIT`.

`data.md` (*Schema owner and runtime user*) and the CHANGELOG now say that a `Flyway` bean of your own,
built from a URL, needs the two JVM properties. Registered for Gate E alongside the `{vendor}` residual.

## Acceptance criteria

- **AC10 / R9:** pools started by the application are now covered and tested (R2-F1). Flyway-created
  data sources are a documented exclusion (R2-F3).
- **AC14 / R10.2:** Boot-created connections are bounded. The two exclusions are the application's own
  Flyway bean and a `{vendor}` location. Both are documented with the system-property workaround and
  registered for Gate E.
- **R2.3 / §7.4:** the transaction budget holds for every Spring-created query (measured above). The two
  Hibernate-specific bypass paths are documented and pinned.

## Follow-up after the re-evaluation

- R2-F1 (maintain concern: a suspended pool is not `isRunning()` but is still sealed): agreed. The check
  is now `getHikariPoolMXBean() == null && !isClosed()`; the MXBean is set from the moment the pool
  starts. Added `OracleConnectionDefaultsTest.aStartedPoolThatIsSuspendedIsLeftAloneToo`.
- Test concern: agreed. The 6 s unwrapped-`Session` timing assertion had been lost in an edit. Every
  outcome in both R2-F2 tests now asserts the timeout exception chain and a distinguishing window
  (kept: 5.5–9 s; query default: 3.5–5.5 s; 1 s transaction: 0.8–3.5 s). The test is renamed
  `aJpaQueryFromTheUnwrappedSessionKeepsTheTransactionTime`, because `unwrap(Session.class)` returns
  Spring's shared proxy.
- AC14 and R2.3 (maintain concern): agreed that they are not met by default on the named paths. They are
  recorded as accepted Medium residuals with workarounds and registered at Gate E, not claimed as met.
