# Phase 8 — Final full-project verification: run results

On `f96ece6` (tag `hardening/phase-07-closed`), JDK 25.0.3 Corretto, Maven offline, `-T1`, Docker 29.9.2. Plan §10.

| Step | Result | Evidence |
|---|---|---|
| 1 Clean full-reactor `verify` | **BUILD SUCCESS** in 17 min 39 s. 123 modules; 95 test suites with tests, 1,557 tests, 0 failures, 0 errors, **0 skipped**. Enforcer, ArchUnit, checkstyle, JaCoCo and japicmp all ran | `evidence-raw/step1-verify.log` |
| 2 Container suite, in-scope providers | **BUILD SUCCESS** in 7 min 10 s with `-Pdocker` on the four modules holding in-scope container tests, after an `install -DskipTests` of the step 1 reactor. All 13 classes ran, none skipped: Oracle 9 (`OracleSmokeIT`, `-DialectIT`, `-ExceptionPathsIT`, `-FlywayPostureIT`, `-NullabilityCheckIT`, `-TimeoutDefaultsIT`, `-TimeoutLeversIT`, `-TransactionRulesIT`, `OutboxOracleIT`), RabbitMQ 3 (`RabbitEventTransportIT`, `RabbitTopologyIT`, `RabbitHandlerFailureIT`), PostgreSQL 1 (`PostgresParityIT`). Keycloak, the JWKS endpoint and Toxiproxy are exercised by the consumer runs (Phases 3 to 7), not by reactor tests | `evidence-raw/step2-docker.log` |
| 3 Golden path, fresh all-capability app, C1 + C2 matrix | **PASS.** Golden path **9/9** (1,048 s). A fresh archetype app with all seven certified capabilities, nothing opened to anonymous callers, against Oracle, RabbitMQ and Toxiproxy in front of both: **63/63** (`allcaps-20261103-215144`). See below | [`evidence/step3-allcaps`](evidence/step3-allcaps/), [`evidence/golden-path-summary-f0735c7.tsv`](evidence/golden-path-summary-f0735c7.tsv), scenario `tooling/hardening/scenarios/phase-08-allcaps.sh` |
| 4 Security, dependency, licence and secrets scans | pending | |
| 5 Compatibility against the Phase 0 baseline | pending | |
| 6 Real-infrastructure cross-capability workflows | Covered by step 3's run (M1 to M7): outbox + RabbitMQ + Oracle, REST resilience + security, and one trace across the HTTP, database, broker and outbound boundaries | as step 3 |
| 7 OpenShift (D7) | pending: needs the chart tool's location, invocation and cluster access | |
| 8 Release rehearsal | pending | |

## Not run in step 2, by scope

Container tests outside the certified capabilities were not run, and are **not certified**: Redis (`RedisPrefixRoundTripIT`, `RedisCacheDockerIT`, `RedisLockProviderIT`, `RedisRateLimiterProviderIT`), S3 (`S3ObjectStoreIT`), the JDBC lock TCK (`JdbcLockProviderTckTest`), and Kafka (`KafkaEventTransportIT`): no build contract requires Kafka's container tests.

## Findings

| ID | Step | Finding | Status |
|---|---|---|---|
| HI-0104 | 1 | The committed generated docs (`llms-full.txt`, the skill's resources, `docs/reference`) are behind the code; the full build rewrites them. The metadata is right; the generator lists removed keys without saying so | OPEN: regenerate from the final full build and commit before `release-decision.md` |

## Step 3: the all-capability matrix

App: `features=data,messaging,restclient`, `-Ddatabase=oracle`, outbox and RabbitMQ adopted as `outbox.md` says; core, errors,
logging, observability and security come with every generated app. A schema owner runs Flyway, a runtime user has DML only.

| # | Scenario (plan §9 C1 and C2) | Result |
|---|---|---|
| — | Anonymous requests to the probe 401; readiness open | pass |
| M1 | Authenticated request → DB write + outbox → RabbitMQ → handler → outbound call | One trace at every hop: request log, row, handler, both downstream calls. The request's call relays the token; the handler's carries none, and no principal is on the listener thread |
| M2 | Downstream 401 and 503 | 401: 500 not retryable, sent once, no downstream body; 503: retried 3 times |
| M3 | Database slow (700 ms), broker and downstream healthy | Outbound call 527 ms; orders and readiness 200; the event delivered |
| M4 | Broker down, database healthy | 5 orders accepted, readiness up, nothing delivered while down, all 5 after, no duplicates |
| M5 | Two principals, 100 concurrent chains | No wrong principal or repeated trace; 50 rows each; all 100 events, no duplicates; each principal's own token downstream; no token on a handler call; the pool never exhausted |
| M6 | SIGKILL, then a new instance | All 30 events received, no duplicates. The kill may have come after most were sent; a kill mid-drain with 300 events is Phase 4's AC7 |
| M7 | SIGTERM with a chain in flight | Answered, exit 143 in 5 s |
| — | Logs | No principal, token or downstream body; the stored line carries the trace |

Trial 1 was 61 of 62: my check expected `platform-starter-resilience`, which the archetype does not add; the REST client
brings Resilience4j itself, as Phase 6 certified. The check was corrected.
