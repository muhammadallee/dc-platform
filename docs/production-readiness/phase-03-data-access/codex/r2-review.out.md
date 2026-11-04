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
