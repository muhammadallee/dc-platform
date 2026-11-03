# Phase 8 — Final full-project verification: run results

On `f96ece6` (tag `hardening/phase-07-closed`), JDK 25.0.3 Corretto, Maven offline, `-T1`, Docker 29.9.2. Plan §10.

| Step | Result | Evidence |
|---|---|---|
| 1 Clean full-reactor `verify` | **BUILD SUCCESS** in 17 min 39 s. 123 modules; 95 test suites with tests, 1,557 tests, 0 failures, 0 errors, **0 skipped**. Enforcer, ArchUnit, checkstyle, JaCoCo and japicmp all ran | `evidence-raw/step1-verify.log` |
| 2 Container suite, in-scope providers | **BUILD SUCCESS** in 7 min 10 s with `-Pdocker` on the four modules holding in-scope container tests, after an `install -DskipTests` of the step 1 reactor. All 13 classes ran, none skipped: Oracle 9 (`OracleSmokeIT`, `-DialectIT`, `-ExceptionPathsIT`, `-FlywayPostureIT`, `-NullabilityCheckIT`, `-TimeoutDefaultsIT`, `-TimeoutLeversIT`, `-TransactionRulesIT`, `OutboxOracleIT`), RabbitMQ 3 (`RabbitEventTransportIT`, `RabbitTopologyIT`, `RabbitHandlerFailureIT`), PostgreSQL 1 (`PostgresParityIT`). Keycloak, the JWKS endpoint and Toxiproxy are exercised by the consumer runs (Phases 3 to 7), not by reactor tests | `evidence-raw/step2-docker.log` |
| 3 Golden path, fresh all-capability app, C1 + C2 matrix | pending | |
| 4 Security, dependency, licence and secrets scans | pending | |
| 5 Compatibility against the Phase 0 baseline | pending | |
| 6 Real-infrastructure cross-capability workflows | pending | |
| 7 OpenShift (D7) | pending: needs the chart tool's location, invocation and cluster access | |
| 8 Release rehearsal | pending | |

## Not run in step 2, by scope

Container tests outside the certified capabilities were not run, and are **not certified**: Redis (`RedisPrefixRoundTripIT`, `RedisCacheDockerIT`, `RedisLockProviderIT`, `RedisRateLimiterProviderIT`), S3 (`S3ObjectStoreIT`), the JDBC lock TCK (`JdbcLockProviderTckTest`), and Kafka (`KafkaEventTransportIT`): no build contract requires Kafka's container tests.

## Findings

| ID | Step | Finding | Status |
|---|---|---|---|
| HI-0104 | 1 | The committed generated docs (`llms-full.txt`, the skill's resources, `docs/reference`) are behind the code; the full build rewrites them. The metadata is right; the generator lists removed keys without saying so | OPEN: regenerate from the final full build and commit before `release-decision.md` |
