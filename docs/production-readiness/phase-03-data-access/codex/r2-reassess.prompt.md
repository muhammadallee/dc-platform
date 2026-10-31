# Re-evaluation — DC Platform hardening Phase 3 (Data Access, Oracle), Round 2

You reviewed commit db83fae (your working directory is that checkout). Below are your round-2 findings,
the implementer's point-by-point response (with Oracle container measurements), and the diff of the
round-2 changes, which are NOT in your checkout — judge them from the diff. Keep this pass short: do not
re-review the whole phase.

For EACH of R2-F1, R2-F2, R2-F3 and each AC row in your "Acceptance criteria not met" section, answer
exactly one of: **accept** (the response/fix resolves it, or the documented residual and severity are
reasonable), **reject** (the response is wrong — say why with code references), or **maintain concern**
(partly addressed — say what remains). State whether you accept the implementer's severity where it
differs from yours. In particular, check the R2-F2 measurement table against the Hibernate 7.4.1 /
Spring 7.0 source you cited: does it explain why `Session.createQuery`/`createNativeQuery` keep the
transaction time while `createSelectionQuery` and raw-delegate queries do not?

Output a table `| Item | Verdict | Reason |`, then any new concern the round-2 changes introduce.

## Your round-2 findings

## Round-1 dispositions

- **F1: PARTIAL** — Nontransactional repository and `EntityManager` queries now receive a statement timeout, with container tests. However, the claim that the remaining transaction timeout always wins is false for some Hibernate query paths; see R2-F2. [OracleTimeoutDefaultsIT.java:73](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutDefaultsIT.java:73).
- **F2: PARTIAL** — Boot’s separate `SimpleDriverDataSource` receives the bounds before migration, and the owner-schema test covers this. The `{vendor}` residual remains, and Flyway-created datasources are missed; see R2-F3. [OracleFlywayConnectionDefaults.java:22](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaults.java:22).
- **F2 `{vendor}` concern: NOT FIXED** — Boot’s detection connection still precedes this post-processor. Documentation and JVM-property guidance mitigate the problem but do not provide the promised default bound. [data.md:112](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/docs/modules/data.md:112).
- **F3: CONFIRMED** — Classification requires both the exact Hibernate class and exact expiry message; raw flush and wrapped commit paths have tests, including a negative transaction-error case. [DataAccessFailure.java:133](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java:133).
- **F4: CONFIRMED** — `PropertyValueException` maps to 409; the unrelated `TransientPropertyValueException` remains unclassified. Real-type unit tests and the Oracle nullability test cover the distinction. [DataAccessFailureTest.java:92](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java:92).
- **F5: CONFIRMED** — The guard checks the returned Flyway bean after configuration customizers and before ordinary bean initialization/migration. Both problematic customizer orders are tested. [FlywayPostureTest.java:58](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/FlywayPostureTest.java:58).
- **F6: CONFIRMED** — Leak detection requires `local` and excludes `prod`, including mixed profiles. [PlatformDataJpaEnvironmentPostProcessor.java:77](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java:77).
- **AC2 concern: CONFIRMED** — Declarative runtime rollback, checked-exception commit, and successful commit visibility have container tests. `OracleTransactionRulesIT.java:52`.
- **AC3 concern: CONFIRMED for the stated disposition** — Observed exception chains and classification tests are present; the explicitly deferred HTTP/logging matrix remains Gate F. `DataAccessFailureTest.java:32`, `PlatformExceptionHandlerTest.java:138`.
- **AC7 concern: CONFIRMED** — Oracle tests cover checksum mismatch, invalid naming, out-of-order rejection and refused clean. `OracleFlywayPostureIT.java:53`.
- **AC8 concern: CONFIRMED** — The added offset-date-time test compares instants across JVM/session zones; the long-identifier migration test checks the filename and ORA-00972. `OracleDialectIT.java:131`, `OracleFlywayPostureIT.java:75`.
- **AC9 concern: CONFIRMED for documentation** — Separate roles, grants and `CURRENT_SCHEMA` are documented; enforcement remains Gate F. `docs/modules/data.md:98`.
- **AC10/A1 concern: CONFIRMED for the tested Boot-created sources** — Environment snapshots and datasource-property assertions reflect A1. Custom-source failures remain below. `PlatformDataJpaEnvironmentPostProcessorTest.java:30`, `OracleFlywayPostureIT.java:111`.
- **AC11 concern: CONFIRMED** — The slice exercises real pool/repository meters and checks bounded tags. `DataMetricsSliceTest.java:54`.
- **AC12 concern: CONFIRMED** — Named replacement, kill-switch and property snapshot tests exist for the added platform beans. `PlatformDataJpaAutoConfigurationTest.java:70`.
- **AC15 concern: CONFIRMED as recorded evidence** — The ninth scenario exists, and the supplied disposition records successful generation/downstream compilation; I did not rerun them. `tooling/scripts/golden-path.sh:450`.
- **AC16 concern: CONFIRMED as recorded disposition** — Phase 1’s recorded result is accepted; Phase 2 remains explicitly assigned to Gate F. `docs/production-readiness/phase-03-data-access/review-round-1.md:30` at `db83fae`.

This review covers `60afe86..db83fae`. Confirmations above concern source/test implementation, not independently reproduced test passes. I inspected pinned dependency bytecode and upstream source; JVM execution failed because the sandbox could not load its Java security file. Maven and Oracle tests were not run.

### R2-F1 — Oracle defaults crash an already-started custom Hikari pool

- **Severity:** High
- **Location:** [OracleConnectionDefaults.java:33](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java:33)
- **Failure scenario:** An application supplies an Oracle datasource using `new HikariDataSource(config)`. At least one platform driver property is absent. The pool starts successfully, then platform bean post-processing throws `IllegalStateException`, aborting application startup.
- **Proof or reasoning:** The processor assumes every matching datasource is unstarted. Hikari’s configuration-taking constructor starts and seals the pool; `addDataSourceProperty()` checks that seal. Consequently, even an application that explicitly supplies its own read timeout fails when the processor tries to add the missing connect timeout. Existing tests construct only dormant, no-argument datasources. This follows directly from the pinned [HikariDataSource constructor](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariDataSource.java) and [HikariConfig mutation check](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java).
- **Proposed fix:** Apply defaults before pool startup. Recognize already-started application pools and back off instead of attempting sealed mutations; provide a preconstruction configuration hook/helper for those pools. Add a context test with a configuration-constructed datasource and only one explicit timeout.

### R2-F2 — The global query default can override an explicit transaction timeout

- **Severity:** High
- **Location:** [PlatformDataJpaEnvironmentPostProcessor.java:74](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java:74)
- **Failure scenario:** Inside `@Transactional(timeout = 1)`, application code executes a heavy query through the underlying `EntityManager`, for example:
  ```java
  ((EntityManager) em.getDelegate())
      .createNativeQuery(heavySql)
      .getSingleResult();
  ```
  The new default sets the statement timeout to 30 seconds, replacing Hibernate’s approximately one-second remaining transaction budget. Conversely, a longer transaction can have its query cancelled prematurely by the 30-second default.
- **Proof or reasoning:** Hibernate initially applies the remaining transaction timeout while [preparing the statement](https://raw.githubusercontent.com/hibernate/hibernate-orm/7.4.1/hibernate-core/src/main/java/org/hibernate/engine/jdbc/internal/StatementPreparerImpl.java), then [overwrites it with the query timeout](https://raw.githubusercontent.com/hibernate/hibernate-orm/7.4.1/hibernate-core/src/main/java/org/hibernate/sql/results/jdbc/internal/DeferredResultSetAccess.java). Spring corrects the query hint only when query creation passes through its [shared-EntityManager interception](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-orm/src/main/java/org/springframework/orm/jpa/SharedEntityManagerCreator.java). The tests use that intercepted path exclusively. Hibernate’s `Session.createSelectionQuery()` is another affected path: its result is not a Jakarta `Query`, so Spring’s `instanceof Query` adjustment does not apply. Before this change, an absent query-level default did not overwrite Hibernate’s transaction timeout.
- **Proposed fix:** Make the fallback transaction-aware at query/statement execution: use the remaining managed transaction budget when present, and the platform fallback otherwise. Add regression tests for underlying-EntityManager and Hibernate selection-query paths, with transaction limits both shorter and longer than the query default.

### R2-F3 — Custom Flyway beans silently miss Oracle network bounds

- **Severity:** High
- **Location:** [OracleFlywayConnectionDefaults.java:24](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaults.java:24)
- **Failure scenario:** An application defines a normal custom Flyway bean with `@Bean(initMethod = "migrate")`, returning `Flyway.configure().dataSource(oracleUrl, owner, password).load()`. With no JVM timeout overrides, a silent Oracle peer can leave migration/startup waiting without the platform read/connect bounds—even with the default migration location.
- **Proof or reasoning:** Flyway’s URL overload creates its own `org.flywaydb.core.internal.jdbc.DriverDataSource`, which implements `DataSource` directly. It is neither a Spring `AbstractDriverBasedDataSource` nor a Hikari pool, so both platform processors ignore it. The new tests exclusively supply Spring’s `SimpleDriverDataSource`. This is separate from the acknowledged `{vendor}` timing problem. The datasource construction is explicit in [Flyway 12.4.0 configuration](https://raw.githubusercontent.com/flyway/flyway/flyway-12.4.0/flyway-core/src/main/java/org/flywaydb/core/api/configuration/ClassicConfiguration.java).
- **Proposed fix:** Cover Flyway-created Oracle datasources through their connection-property configuration before connection creation, preserving explicit application values. Add a custom-Flyway-bean test and a silent-peer test for that construction path.

No additional security, exception-status/retry classification, Oracle-19c SQL, archetype-generation, or test-dependency-scope defect was established. The new JDBC template property does not configure audit, idempotency or locking’s manually constructed templates; I have not reported those pre-existing, out-of-scope paths as new regressions.

## Acceptance criteria not met

- **AC10 / R9 override behavior:** The supported-source tests do not cover configuration-constructed Hikari pools or Flyway-created datasources. The former can fail startup; the latter receives no Oracle defaults. See R2-F1 and R2-F3.
- **AC14 / R10.2 bounded startup:** Custom Flyway datasources remain unbounded as described in R2-F3. The acknowledged separate-user `{vendor}` connection also remains unbounded without its documented workaround. These are code limitations, not reports of missing Gate F execution.
- **R2.3 / §7.4 transaction-timeout contract:** The global query default can defeat explicit transaction budgets. Existing container tests do not cover the affected execution paths in R2-F2.

No further unmet UNIT/SLICE/CONTAINER criterion was established. Explicitly deferred Gate F work is excluded from these findings.

## Implementer response

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

## Round-2 diff (not in your checkout)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 63ac797..0f0954b 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -83,9 +83,12 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   (was Hikari's 30 s); `spring.transaction.default-timeout` 30 s (was unbounded) — a longer batch
   transaction needs an explicit `@Transactional(timeout = …)`; a query outside any transaction (a
   declared repository query, a non-transactional service, `JdbcTemplate`) is cancelled after 30 s
-  (`jakarta.persistence.query.timeout`, `spring.jdbc.template.query-timeout`; was unbounded); Oracle pools
+  (`jakarta.persistence.query.timeout`, `spring.jdbc.template.query-timeout`; was unbounded) — a query
+  built with Hibernate's `Session.createSelectionQuery` or on the raw `getDelegate()` takes this 30 s even
+  inside a transaction, so give it `.setTimeout(…)` where that matters; Oracle pools
   and a separate Flyway migration connection get the driver's `oracle.jdbc.ReadTimeout` 60 s and
-  `oracle.net.CONNECT_TIMEOUT` 10 s; leak detection (20 s) under `local`, never with `prod`.
+  `oracle.net.CONNECT_TIMEOUT` 10 s (not a pool the application started itself, nor a `Flyway` bean of its
+  own built from a URL: set them there); leak detection (20 s) under `local`, never with `prod`.
 - **Flyway naming is validated**: `spring.flyway.validate-migration-naming=true`, so a migration file not
   named `V<version>__<description>.sql` now fails startup instead of being silently skipped. Out-of-order
   migrations outside the `local` profile fail startup with a diagnostic, however they were enabled; `clean-disabled`,
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
index e73283c..892fc70 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
@@ -60,7 +60,8 @@ public class PlatformDataJpaEnvironmentPostProcessor implements EnvironmentPostP
      *   hands the remaining time to each statement, which the driver cancels (ORA-01013);
  * - a query outside any transaction (a declared repository query has none of its own, a non-transactional
  *   service) is bounded at 30 s too: Hibernate's default query timeout and JdbcTemplate's; inside a
- *   transaction Spring sets the remaining transaction time on each query, which overrides this default;
+ *   transaction Spring sets the remaining transaction time on each query it creates, which overrides
+ *   this default (not on Hibernate's SelectionQuery or a raw-delegate query: OracleTimeoutDefaultsIT);
      * - the Oracle driver's read and connect timeouts are added to the pool itself, keyed on its own
      *   URL (internal.OracleConnectionDefaults): the URL may come from a property source added after
      *   this post-processor ran;
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
index 75ceff4..dcabdb2 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
@@ -14,7 +14,8 @@ import org.springframework.core.Ordered;
  * pool starts, keyed on the pool's own URL — not on an environment property (the URL can come from a
  * property source added after environment post-processing) and not on driver presence (an
  * Oracle-starter service runs on H2 locally). A value the application already set, as a
- * {@code data-source-properties} entry or a JVM system property, is left alone.
+ * {@code data-source-properties} entry or a JVM system property, is left alone. A pool the application
+ * started itself ({@code new HikariDataSource(config)}) is left alone too: its configuration is sealed.
  *
  * <p>Thread-safe; stateless.
  */
@@ -27,7 +28,9 @@ public final class OracleConnectionDefaults implements BeanPostProcessor, Ordere
 
     @Override
     public Object postProcessBeforeInitialization(Object bean, String beanName) {
-        if (bean instanceof HikariDataSource pool && isOracle(pool.getJdbcUrl())) {
+        // A pool built with new HikariDataSource(config) has already started and sealed its configuration.
+        if (bean instanceof HikariDataSource pool && !pool.isRunning() && !pool.isClosed()
+                && isOracle(pool.getJdbcUrl())) {
             DEFAULTS.forEach((key, value) -> {
                 if (!pool.getDataSourceProperties().containsKey(key) && System.getProperty(key) == null) {
                     pool.addDataSourceProperty(key, value);
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
index 801494c..85d4630 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
@@ -7,6 +7,8 @@ import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayOutOfO
 import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayPresenceCheck;
 import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleConnectionDefaults;
 import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleFlywayConnectionDefaults;
+import com.zaxxer.hikari.HikariConfig;
+import com.zaxxer.hikari.HikariDataSource;
 import jakarta.persistence.EntityManagerFactory;
 import java.util.Optional;
 import org.junit.jupiter.api.Test;
@@ -76,6 +78,23 @@ class PlatformDataJpaAutoConfigurationTest {
                 .run(context -> assertThat(context).doesNotHaveBean(OracleConnectionDefaults.class));
     }
 
+    @Test
+    void anOraclePoolTheApplicationStartedItselfDoesNotFailStartup() {
+        // R2-F1: new HikariDataSource(config) is sealed; the defaults back off instead of throwing.
+        HikariConfig config = new HikariConfig();
+        config.setJdbcUrl("jdbc:oracle:thin:@//127.0.0.1:9/APP");
+        config.setMinimumIdle(0);
+        config.setInitializationFailTimeout(-1);
+        config.addDataSourceProperty("oracle.jdbc.ReadTimeout", "120000");
+        runner.withBean("dataSource", HikariDataSource.class, () -> new HikariDataSource(config),
+                        bean -> bean.setDestroyMethodName("close"))
+                .run(context -> {
+                    assertThat(context).hasNotFailed();
+                    assertThat(context.getBean(HikariDataSource.class).getDataSourceProperties())
+                            .containsOnlyKeys("oracle.jdbc.ReadTimeout");
+                });
+    }
+
     @Test
     void flywayChecksAreRegisteredReplaceableByNameAndAbsentWithoutFlyway() {
         runner.run(context -> {
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
index e0882e0..95d0b90 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
@@ -2,6 +2,7 @@ package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
 
 import static org.assertj.core.api.Assertions.assertThat;
 
+import com.zaxxer.hikari.HikariConfig;
 import com.zaxxer.hikari.HikariDataSource;
 import org.junit.jupiter.api.Test;
 
@@ -52,6 +53,18 @@ class OracleConnectionDefaultsTest {
         assertThat(pool.getDataSourceProperties()).doesNotContainKey("oracle.net.CONNECT_TIMEOUT");
     }
 
+    @Test
+    void aPoolTheApplicationAlreadyStartedIsLeftAlone() {
+        // new HikariDataSource(config) starts the pool and seals its configuration; adding to it throws.
+        try (HikariDataSource pool = startedPool()) {
+            assertThat(defaults.postProcessBeforeInitialization(pool, "dataSource")).isSameAs(pool);
+
+            assertThat(pool.getDataSourceProperties())
+                    .containsOnlyKeys("oracle.jdbc.ReadTimeout")
+                    .containsEntry("oracle.jdbc.ReadTimeout", "120000");
+        }
+    }
+
     @Test
     void otherBeansPassThrough() {
         Object bean = new Object();
@@ -63,4 +76,14 @@ class OracleConnectionDefaultsTest {
         pool.setJdbcUrl(url);
         return pool;
     }
+
+    /** An Oracle pool started by its constructor without connecting: no idle connections, no fail-fast. */
+    private static HikariDataSource startedPool() {
+        HikariConfig config = new HikariConfig();
+        config.setJdbcUrl("jdbc:oracle:thin:@//127.0.0.1:9/APP");
+        config.setMinimumIdle(0);
+        config.setInitializationFailTimeout(-1);
+        config.addDataSourceProperty("oracle.jdbc.ReadTimeout", "120000");
+        return new HikariDataSource(config);
+    }
 }
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutDefaultsIT.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutDefaultsIT.java
index 9a0de67..59f8169 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutDefaultsIT.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleTimeoutDefaultsIT.java
@@ -9,6 +9,7 @@ import java.util.ArrayList;
 import java.util.List;
 import java.util.concurrent.TimeUnit;
 import java.util.function.Supplier;
+import org.hibernate.Session;
 import org.junit.jupiter.api.Tag;
 import org.junit.jupiter.api.Test;
 import org.springframework.beans.factory.annotation.Autowired;
@@ -29,7 +30,10 @@ import org.springframework.transaction.support.TransactionTemplate;
  *   <li>a query outside any transaction — a declared repository query has none of its own, so does a
  *       non-transactional service — is cancelled by the default query timeout, and the connection is
  *       reused cleanly afterwards;</li>
- *   <li>inside a transaction, the remaining transaction time wins over that default;</li>
+ *   <li>inside a transaction, the remaining transaction time wins over that default — for a query
+ *       created through Spring's {@code EntityManager} proxy or the unwrapped {@code Session}'s
+ *       {@code createQuery}/{@code createNativeQuery}, but not for Hibernate's {@code SelectionQuery} or a
+ *       query on the raw delegate (residual R2-F2, pinned);</li>
  *   <li>a transaction whose deadline has passed fails its next statement: a query with Spring's
  *       {@code TransactionTimedOutException}, a flush or commit with Hibernate's
  *       {@code TransactionException("Transaction timeout expired")} — the chain the errors capability
@@ -53,7 +57,10 @@ class OracleTimeoutDefaultsIT {
     private static final String HEAVY_SQL =
             "select count(*) from (select level l from dual connect by level <= 20000) a, (select level l from dual connect by level <= 20000) b";
 
-    private static final String EXPIRED = "Transaction timeout expired";
+    /** The same work in JPQL: a cross join of generated rows ({@link OracleGeneratedRow}). */
+    private static final String HEAVY_JPQL = "select count(*) from OracleGeneratedRow a, OracleGeneratedRow b";
+
+    private static final String EXPIRED ="Transaction timeout expired";
 
     @DynamicPropertySource
     static void oracle(DynamicPropertyRegistry registry) {
@@ -105,6 +112,48 @@ class OracleTimeoutDefaultsIT {
         assertThat(outcome.millis()).isGreaterThanOrEqualTo(5_500L);
     }
 
+    @Test
+    void aQueryCreatedOnTheNativeSessionKeepsTheTransactionTime() {
+        // Review round 2, R2-F2: a query created on the unwrapped Hibernate Session, not through
+        // Spring's EntityManager proxy, inside transactions shorter and longer than the query default.
+        Outcome shorter = measure(() -> tx().execute(status ->
+                em.unwrap(Session.class).createNativeQuery(HEAVY_SQL).getSingleResult()));
+        assertThat(shorter.chain()).as(shorter.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
+        assertThat(shorter.millis()).as("1 s transaction, 4 s query default").isLessThan(3_500L);
+        TransactionTemplate longer = tx();
+        longer.setTimeout(6);
+        Outcome kept = measure(() -> longer.execute(status ->
+                em.unwrap(Session.class).createNativeQuery(HEAVY_SQL).getSingleResult()));
+        assertThat(kept.chain()).as(kept.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
+        Outcome jpql = measure(() -> longer.execute(status ->
+                em.unwrap(Session.class).createQuery(HEAVY_JPQL, Long.class).getSingleResult()));
+        assertThat(jpql.millis()).as("6 s transaction, Session.createQuery: " + jpql).isGreaterThanOrEqualTo(5_500L);
+    }
+
+    @Test
+    void aQueryThatBypassesSpringsQueryHandlingTakesTheQueryDefaultInsteadOfTheTransactionTime() {
+        // Residual (review round 2, R2-F2), pinned so a change is noticed: Spring hands the remaining
+        // transaction time only to a jakarta.persistence.Query it creates. Hibernate's SelectionQuery and a
+        // query on the raw delegate get the query default — past a shorter transaction's deadline, or
+        // cutting a longer one short. Still bounded: by the default here, by the read timeout behind it.
+        Outcome past = measure(() -> tx().execute(status ->
+                em.unwrap(Session.class).createSelectionQuery(HEAVY_JPQL, Long.class).getSingleResult()));
+        assertThat(past.chain()).as(past.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
+        assertThat(past.millis()).as("1 s transaction, selection query").isBetween(3_500L, 7_000L);
+        TransactionTemplate longer = tx();
+        longer.setTimeout(6);
+        Outcome selection = measure(() -> longer.execute(status ->
+                em.unwrap(Session.class).createSelectionQuery(HEAVY_JPQL, Long.class).getSingleResult()));
+        assertThat(selection.millis()).as("6 s transaction, selection query: " + selection).isLessThan(5_500L);
+        Outcome delegate = measure(() -> longer.execute(status ->
+                ((EntityManager) em.getDelegate()).createQuery(HEAVY_JPQL, Long.class).getSingleResult()));
+        assertThat(delegate.millis()).as("6 s transaction, raw delegate: " + delegate).isLessThan(5_500L);
+        // The documented workaround: a timeout set on the query itself.
+        Outcome own = measure(() -> longer.execute(status -> em.unwrap(Session.class)
+                .createSelectionQuery(HEAVY_JPQL, Long.class).setTimeout(6).getSingleResult()));
+        assertThat(own.millis()).as("own timeout: " + own).isGreaterThanOrEqualTo(5_500L);
+    }
+
     @Test
     void aQueryAfterTheDeadlineFailsWithSpringsTimedOutException() {
         Outcome outcome = measure(() -> tx().execute(status -> {
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index 751ccb9..82d768b 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -701,6 +701,14 @@ One line of context per decision; details live in the commit bodies referenced.
   unbounded before); inside a transaction Spring sets the remaining time per query, which wins. And the
   Oracle driver bounds on Flyway's own `SimpleDriverDataSource` (`spring.flyway.user`/`url`), applied on
   the `Flyway` bean; residual: a `{vendor}` location connects before that (system properties cover it).
+  Round 2 (Gate D): the remaining transaction time wins only for queries Spring creates as a
+  `jakarta.persistence.Query` (repositories, the injected `EntityManager`, the unwrapped `Session`'s
+  `createQuery`/`createNativeQuery`); Hibernate's `SelectionQuery` and raw-delegate queries take the query
+  default (measured both ways). Decision: keep the default and document `.setTimeout(…)` on those paths
+  rather than reaching into Spring's transaction or Hibernate's session internals — the statement stays
+  bounded, and the alternative only approximates the remaining time. A pool started by the application
+  (`new HikariDataSource(config)`, sealed) is now left alone instead of failing startup; a `Flyway` bean the
+  application builds from a URL gets no driver bounds (Flyway's own data source; system properties cover it).
 - **D111 — data-access failures are problems, classified by cause chain (hardening phase 3, Gate B).**
   Observed on Oracle: Spring translates only behind a repository proxy or at commit; `EntityManager`
   use lets raw Hibernate/JPA types through, and a batched insert reports a `BatchUpdateException`
diff --git a/docs/modules/data.md b/docs/modules/data.md
index 48c9ef8..524c872 100644
--- a/docs/modules/data.md
+++ b/docs/modules/data.md
@@ -45,13 +45,18 @@ a `Money` attribute converter, and a Flyway-presence guard — all on by default
     `spring.jdbc.template.query-timeout=30s`: a query that runs **outside** a transaction is bounded
     too. A declared repository query (`@Query` or a derived `findBy…`) has no transaction of its own,
     and neither has a service method without `@Transactional`. Inside a transaction the remaining
-    transaction time is used instead, so this default never lengthens a statement. `EntityManager.find`
-    outside a transaction is not a query; only the read timeout below bounds it;
+    transaction time is used instead for repository queries and for queries created on the injected
+    `EntityManager` (or its unwrapped `Session`'s `createQuery`/`createNativeQuery`). Two paths bypass that
+    and take this default instead — past a shorter transaction's deadline, or cutting a longer one short:
+    Hibernate's `Session.createSelectionQuery(…)` and a query on the raw `getDelegate()`. On those, set the
+    timeout on the query yourself (`.setTimeout(seconds)`). `EntityManager.find` outside a transaction is
+    not a query; only the read timeout below bounds it;
   - on a pool whose URL is `jdbc:oracle:`, the driver's `oracle.jdbc.ReadTimeout=60000` (the backstop
     for what a cancel cannot stop: a PL/SQL or lock wait, a dead network) and
     `oracle.net.CONNECT_TIMEOUT=10000` are added to the pool's `data-source-properties`. The same two are
     added to Flyway's own connection when migrations run as a separate user (see *Schema owner and
-    runtime user*);
+    runtime user*). A pool you build and start yourself (`new HikariDataSource(config)`) is sealed, so
+    nothing is added to it: set both properties on its config;
   - `leak-detection-threshold=20000` (a stack trace for a connection held over 20 s) only under the
     `local` profile, and never when `prod` is active as well.
   Hikari's keepalive (2 min) and max lifetime (30 min) are its own defaults. Set any key yourself to
@@ -113,7 +118,9 @@ the same Oracle read and connect timeouts to it. One exception: a `{vendor}` pla
 `spring.flyway.locations` makes Boot open one connection to detect the database **before** the platform
 can set them. Keep the default `db/migration` location, or set
 `-Doracle.jdbc.ReadTimeout=60000 -Doracle.net.CONNECT_TIMEOUT=10000` on the JVM (system properties apply
-to every Oracle connection).
+to every Oracle connection). The same JVM properties are the way for a `Flyway` bean you define yourself
+with a URL (`Flyway.configure().dataSource(url, user, password)`): Flyway then builds its own connection,
+which the platform does not touch.
 
 ## Oracle
 
```
