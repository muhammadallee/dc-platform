# Phase 4 — Data (Spring Data JPA): scope

| Item | Value |
|---|---|
| Phase start commit | `e9f9b80` (`develop`, tag `native/phase-03-closed`) |
| Branch | `native/phase-04-data` |
| Capability | `platform-data-jpa-autoconfigure`, `platform-starter-data-jpa`, `platform-starter-data-jpa-oracle`; the logging masker's treatment of JPA values (HI-0167) |
| Database | Oracle, certified on 23ai Free in 19 mode (HD3, HD13); H2 for local runs and tests |
| Baseline | 55 unit and slice tests, 44 docker-tagged, no failures, no skips ([evidence](evidence/gateA-baseline.txt)) |

## 1. What this phase does

Data was certified on Oracle in v3 and already speaks Spring: `JpaRepository`, `@Transactional`, Flyway and Boot's own
property keys. The consumer probe imports no platform type. This phase confirms that with the Spring-native recipe
(plan §10), and fixes what the recipe shows:

1. **HI-0167 (High).** Logging a JPA value runs SQL. Reproduced on real Hibernate
   ([spike](evidence/gateA-lazy-loading-spike.txt)).
2. **HI-0168 (Medium).** A service's own `AuditorAware` bean is ignored unless it is named `platformAuditorAware`
   (contract 13). Found by reading the code; Gate B reproduces it with a failing test first.
3. **Contract gaps.** Data has no zero-config acceptance test (contract 14), and the consumer harness does not assert
   that the probe imports no platform type (contract 12).
4. **Documentation.** Book chapter 8 still teaches `Money`, `EntityId` and their converters (removed by ND7) and actuator
   on port 8080. The sample is rewritten and the lab "data" is new.

**Excluded:**
- PostgreSQL and other databases (`PostgresParityIT` stays and is not evidence);
- Oracle RAC (`BLOCKED`, HD4) and a real 19c instance (HD13);
- the outbox and its transaction semantics (Phases 5 and 6);
- HI-0071, parked by the owner until before production use and re-evaluated with the outbox;
- an authenticated auditor (iteration 2).

## 2. Decisions proposed at this gate (decision log at Gate B)

1. **No type is removed and no API is added.** Data keeps its property defaults, bean post-processors and checks.
   `OracleConnectionDefaults` stays (rule 12's recorded exception).
2. **The masker never loads a JPA value.** Recognised by contract name, with no Hibernate or JPA dependency in logging:

   | Value | Written as | Never called |
   |---|---|---|
   | Hibernate proxy | a fixed placeholder with the entity's type name | `toString()`, getters |
   | An uninitialised persistent collection | a fixed "not loaded" placeholder | `iterator()`, `size()`, `toString()` |
   | An initialised persistent collection | its elements, each under these rules | — |
   | An `@Entity` instance | a fixed placeholder with its type name | getters (a computed one can load), `toString()` (a generated one can load) |

   A service logs an entity's identifier or a record of what it needs. The guarantee covers what the masker does.
   Logback renders a message argument with `toString()` before any encoder (HI-0157), so a proxy or collection passed
   as a message argument can still load there; the logging and data pages say so.
3. **The auditor backs off by type.** A service's `AuditorAware` bean, whatever its name, replaces the platform's and is
   the one auditing uses. A service that enables auditing itself still switches the platform's configuration off.
4. **Accepted v3 residuals stay accepted:** HI-0064, HI-0065, HI-0066, HI-0069 (amendment A2).

## 3. How the platform gives each behaviour (rule 12)

| Behaviour | Means | Step |
|---|---|---|
| Open-in-view off, batching, UTC, pool wait, transaction and query timeouts, Flyway posture | property defaults in `platform-data-jpa-defaults` | 1 |
| Oracle driver read and connect timeouts | bean post-processors on Boot's pool and Flyway beans | 2 |
| Flyway presence and out-of-order checks | startup checks, internal | 2 |
| Auditing as `"system"` | a bean of Spring's `AuditorAware` | 3 |
| A platform type in service code | none | — |

## 4. Contracts (plan §7)

| # | Contract | Position | Proof |
|---|---|---|---|
| 1 | Real override | every bean replaceable | `PlatformDataJpaAutoConfigurationTest` |
| 2 | Default snapshot | properties and contributed defaults | `propertiesDefaultSnapshot`, `configDefaultSnapshot` |
| 3 | Kill switch | `DEFAULT_ON` | `killSwitchDisables`, `killSwitchRemovesThePostProcessors` |
| 4 | Outage posture | fail-fast per request: bounded waits, 503, recovery without restart | AC4–AC6 |
| 5 | Health | database in `dependencies`, never in readiness | AC6 |
| 6 | Context propagation | request threads only; the trace is on the error body and the WARN | AC3, AC6 |
| 7 | Error surface | 409 and 503 problems with fixed details, no SQL | AC3–AC5 |
| 8 | Redaction | no SQL or bind value in responses; logging a JPA value loads and reveals nothing more | AC3, AC18 |
| 9 | Observability | Hikari and repository meters, bounded tags | AC11 |
| 10 | Graceful shutdown | the in-flight transaction completes, the pool closes | AC13 |
| 11 | Startup under adversity | bounded with Oracle slow, down or silent | AC14 |
| 12 | Spring types only | the probe imports no platform type | AC17 |
| 13 | Back-off to the user's Spring bean | `AuditorAware` by type | AC19 |
| 14 | Zero-config acceptance | the starter plus a datasource is enough | AC20 |

## 5. Acceptance criteria

AC1–AC16 of [the v3 scope](../../production-readiness/phase-03-data-access/scope.md) stay, with amendments A1 and A2
and these changes:

- **AC8:** the `Money` and `CorrelationId` converter cases are retired with their types (ND7).
- **AC15:** the golden path is CI's `generator-gate`.
- **AC16:** the regressions are the Phase 2 consumer (floor 77) and the Phase 3 consumer (floor 65).

**New criteria:**

| ID | Criterion | Level |
|---|---|---|
| AC17 | The consumer probe imports no platform type, asserted by the harness | CONSUMER |
| AC18 | The masker runs no SQL and initialises nothing for a proxy, an uninitialised collection or an entity, as a key-value pair and nested in a map, record or bean; on real Hibernate, not a stand-in | SLICE + CONSUMER |
| AC19 | A service's `AuditorAware` bean under any name is the one that audits; the platform's steps back | SLICE |
| AC20 | With the starter and a datasource only: migrations run, auditing records `"system"`, and every default is in effect | SLICE |
| AC21 | The lab "data" passes the lab runner: H2, then Oracle Free, then the database stopped mid-request | LAB |

## 6. Carry-over table

| Guarantee | Source | Lives today in | Lives after in | Proof | Kind |
|---|---|---|---|---|---|
| Oracle starter with driver and Flyway module | HI-0051 | `platform-starter-data-jpa-oracle` | unchanged | `OracleSmokeIT`, AC1 | CARRIED |
| Pool wait, transaction and query timeouts bounded | HI-0052, HI-0062, D110 | environment post-processor | unchanged | `TimeoutDefaultsSliceTest`, `OracleTimeoutDefaultsIT`, AC4, AC5 | CARRIED |
| Flyway posture pinned; out-of-order refused outside `local` | HI-0053, D112 | post-processor, `FlywayOutOfOrderGuard` | unchanged | `FlywayPostureTest`, `OracleFlywayPostureIT` | CARRIED |
| Data failures are 409 or 503, never SQL | HI-0054, HI-0059, HI-0062, D111 | errors `DataAccessFailure` | unchanged | `OracleExceptionPathsIT`, AC3 | CARRIED |
| Degradation tested on a real Oracle | HI-0055 | container tests, harness | unchanged | 44 container tests, AC4–AC6 | CARRIED |
| Schema owner and runtime user separated | HI-0056 | `docs/modules/data.md` | unchanged | AC9 | CARRIED |
| Oracle RAC is not claimed | HI-0057, HD4 | closure | unchanged | documented | CARRIED (`BLOCKED`) |
| The descriptor backs off by name | HI-0058 | auto-configuration | unchanged | `descriptorBacksOffWhenUserDescriptorPresentUnderItsName` | CARRIED |
| `Instant` and `OffsetDateTime` survive time zones | HI-0060, HI-0067, D114 | Hibernate mapping, docs | unchanged | `OracleDialectIT` | CARRIED |
| An identifier over the limit fails at migration time | HI-0067 | Flyway | unchanged | `anIdentifierOverTheLimitFailsAtMigrationTimeNamingTheMigration` | CARRIED |
| Driver timeouts follow the pool's own URL | HI-0061 | `OracleConnectionDefaults` | unchanged | `theOracleUrlDefaultsReachTheDriverAndThePool`, `OracleConnectionDefaultsTest` | CARRIED |
| A started or suspended pool is left alone | HI-0063 | `OracleConnectionDefaults` | unchanged | `aPoolTheApplicationAlreadyStartedIsLeftAlone`, `aStartedPoolThatIsSuspendedIsLeftAloneToo`, `anOraclePoolTheApplicationStartedItselfDoesNotFailStartup` | CARRIED |
| Flyway's own connection gets the longer read timeout | HI-0070 | `OracleFlywayConnectionDefaults` | unchanged | `aSeparateOracleMigrationDataSourceGetsTheLongerFlywayReadTimeoutAndTheConnectTimeout`, AC14 | CARRIED |
| Two startup connections have no driver bound by default | HI-0064 | data page | unchanged | `aJvmSystemPropertyBoundsADriverDataSourceConnection` | CARRIED (limit) |
| Two query paths take the query default | HI-0065 | data page | unchanged | `aQueryThatBypassesSpringsQueryHandlingTakesTheQueryDefaultInsteadOfTheTransactionTime` | CARRIED (limit) |
| A PL/SQL cancel is best-effort; the read timeout bounds it | HI-0066 | data page | unchanged | `aWaitingPlSqlCallIsNotInterruptedByTheQueryTimeout`, AC5 | CARRIED (limit) |
| A lost connection is 503; while committing, never retryable | HI-0068, HI-0071 | errors `DataAccessFailure` | unchanged | `aCommitThatLosesItsConnectionThroughSpringsTranslationIsNotRetryable`, AC5 | CARRIED (HI-0071 parked) |
| One platform WARN per violation; Hibernate's lines stay | HI-0069, A2 | errors, Hibernate | unchanged | AC3 | CARRIED |
| Transaction boundaries | plan §10 | Spring, defaults | unchanged | `OracleTransactionRulesIT`, AC2 | CARRIED |
| Pool and repository meters with bounded tags | contract 9 | Boot | unchanged | `poolAndRepositoryMetersCarryBoundedTagsWithoutSqlOrIds`, AC11 | CARRIED |
| `Money` and `CorrelationId` converters round-trip | v3 R6.7 | removed in Phase 2 | — | ND7 | RETIRED |
| Logging a JPA value runs no SQL | Phase 3 closure, HI-0167 | not provided | logging `PlatformValueMasker` | AC18, new test | NEW RISK |
| A service's auditor is used | contract 13, HI-0168 | by bean name only | auto-configuration, by type | AC19, new test | NEW RISK |

**Mitigations for the `NEW RISK` rows:**
- **JPA values:** tests on real Hibernate that count statements and check `Hibernate.isInitialized`; the stand-in
  interface test is replaced. A documented limit for Logback's own rendering of message arguments.
- **Auditor:** a failing test first, then back-off by type. Two auditor beans in one service is the service's choice to
  resolve with `@Primary`, and the data page says so.

## 7. Test disposition

No test is removed, except that the Hibernate stand-in (`org/hibernate/proxy/HibernateProxy.java` in logging's test
sources) gives way to tests on real Hibernate. `backsOffWhenUserAuditorPresent` gains the any-name case.

## 8. Removal list

No main type. The Phase 0 removal map's only data entry, `platform-data-api`, went in Phase 2 (ND7).

Documentation that still names removed types: `docs/book/chapters/08-data.md`, `docs/samples/data-access/`.

## 9. Consumer run port plan

- **Scenario:** `tooling/hardening/scenarios/phase-03-data-access.sh`, floor **92**. Infrastructure, faults and
  assertions stay (rule 14).
- **Added:** the platform-import check (AC17), and a probe endpoint that logs an entity, a proxy and a lazy collection as
  key-value pairs and reports the statements run (AC18).
- **Repository:** `bash tooling/hardening/native-repo.sh native-04`.
- **Runs:** `HH_RUNS_ROOT=~/dc-hardening-runs/native-04`, as plan §5.9 says. Three in a row on the final commit.
- **Regressions:** core-errors (floor 77) and logging-observability (floor 65), because HI-0167 changes logging.

## 10. Environment prerequisites

- JDK 25, Maven 3.9 offline, Docker with about 3 GB free memory: one Oracle and one app container at a time.
- `gvenzl/oracle-free:23-slim-faststart` and Toxiproxy 2.9.0, both present locally.
- The container tests leave one reusable Oracle container running (`withReuse`). Stop it before a consumer run.
- Disk: about 5 GB free on C:. Check before every consumer run; delete only superseded, uncited runs.
