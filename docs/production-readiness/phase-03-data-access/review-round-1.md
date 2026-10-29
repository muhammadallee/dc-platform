# Phase 3 — Review round 1 (Gate C)

| Item | Value |
|---|---|
| Reviewed commit | `2c3fc78` (diff from phase start `60afe86`, `docs/production-readiness` excluded; isolated worktree) |
| Verbatim I/O | [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md) → [`codex/r1-review.out.md`](codex/r1-review.out.md); response [`codex/r1-response.md`](codex/r1-response.md); re-evaluation [`codex/r1-reassess.prompt.md`](codex/r1-reassess.prompt.md) → [`codex/r1-reassess.out.md`](codex/r1-reassess.out.md) |
| Codex scope note | Static review: Codex's read-only checkout ran no Maven or Docker. Claude's response is backed by Oracle container measurements (`evidence-raw/r1-explore*.log`) |

## Findings

| # | Finding | Severity | Claude | Re-evaluation | Disposition |
|---|---|---|---|---|---|
| F1 | A declared repository query or a non-transactional `EntityManager` query has no statement timeout | High | Agree — measured: 9.3 s and 9.8 s uncancelled under a 1 s transaction default | accept | FIXED — default `jakarta.persistence.query.timeout=30000` and `spring.jdbc.template.query-timeout=30s`; inside a transaction the remaining time wins (measured 2.0 s vs 4.2 s outside). `OracleTimeoutDefaultsIT`, including clean reuse of the cancelled connection |
| F2 | A separate Flyway user (`spring.flyway.user`) gets Boot's own `SimpleDriverDataSource`, without the Oracle network bounds | High | Agree — confirmed in Boot 4.1.0 bytecode | maintain concern: a `{vendor}` location connects before the `Flyway` bean exists | FIXED for migration (`OracleFlywayConnectionDefaults` on the `Flyway` bean; `OracleFlywayPostureIT` migrates through a separate owner user and asserts the properties). **Residual** (Gate E): with `{vendor}` in `spring.flyway.locations`, Boot opens one database-detection connection in `configureProperties` before any hook (bytecode: `configureDataSource` → `configureProperties` → customizers → `load`). Documented: keep the default location or set the two JVM system properties |
| F3 | An expired transaction becomes a non-retryable 500 | High | Partly agree — measured: an expired **query** is already Spring's `TransactionTimedOutException` (503); a **flush** gives raw `TransactionException("Transaction timeout expired")`, a **commit** `JpaSystemException` wrapping it (500) | accept | FIXED — `org.hibernate.TransactionException` is 503 only with Hibernate's exact expiry message; other `TransactionException`s stay unclassified. Chains pinned by `OracleTimeoutDefaultsIT` |
| F4 | Hibernate's null check (`PropertyValueException`) on the `EntityManager` path returns 500 | Medium (Codex: High) | Agree on the defect, Medium: the check is off by default when Bean Validation is present, and the body leaks nothing | accept, Medium | FIXED — `PropertyValueException` is a 409; `TransientPropertyValueException` (an unsaved reference) is not a subclass and stays 500. `OracleNullabilityCheckIT` records the chain; unit tests with the real types |
| F5 | A customizer ordered like the guard can re-enable out-of-order after it | High | Agree | accept | FIXED — the guard is a post-processor on the `Flyway` bean (finished configuration, before migration). `FlywayPostureTest`: an unordered and a `LOWEST_PRECEDENCE` customizer both fail startup; Oracle: `OracleFlywayPostureIT` |
| F6 | `prod,local` gets leak detection | Medium | Agree | accept | FIXED — only `local` without `prod`; mixed-profile test |

## Acceptance criteria after round 1

| AC | Status |
|---|---|
| AC2 | CONTAINER half done: `OracleTransactionRulesIT` (runtime exception rolls back, checked exception commits by default, commit visible); `data.md` *Transactions* documents both rules and `readOnly`. CONSUMER half: Gate F |
| AC3 | Classification proven per real Oracle chain (F4 added); HTTP matrix, `traceId` and the single WARN on the trace: Gate F |
| AC7 | Oracle container coverage added (`OracleFlywayPostureIT`, `prod` profile, own schema per case): out-of-order, misnamed migration, checksum mismatch, `clean` refused |
| AC8 | Codex **rejected** the response: R6.4 names `OffsetDateTime`, R6.1 a migration-time failure. Claude conceded; FIXED — `OffsetDateTime` round-trips to the same instant across JVM and session zones (`OracleDialectIT`), and a 129-character identifier in a migration fails startup naming the file and ORA-00972 (`OracleFlywayPostureIT`) |
| AC9 | Operational model documented (`data.md` *Schema owner and runtime user*: grants, `CURRENT_SCHEMA` via `connection-init-sql` or synonyms, grants in migrations). Enforcement (runtime user cannot `ALTER`/`DROP`): Gate F |
| AC10 | Scope amendment **A1** (Oracle driver properties verified on the data sources, not the environment source); F6 fixed; config-default snapshot extended |
| AC11 | SLICE half done: `DataMetricsSliceTest` (real Hikari pool + Spring Data repository meters, tag keys bounded, no SQL or ID in a tag value). CONSUMER half: Gate F |
| AC12 | accepted — replacement-by-name and kill-switch tests for `platformFlywayOutOfOrderGuard`, `platformOracleFlywayConnectionDefaults`, `platformOracleConnectionDefaults`; `DataJpaProperties` default snapshot |
| AC15 | accepted (golden path 9/9 on `f9d1a86`, downstream compile) |
| AC16 | Phase 1 consumer 92/92 accepted; Phase 2 consumer rerun at Gate F, with the DB-down check tightened from any 5xx to `503 DC-DATA-0503` |
| AC1, AC4, AC5, AC6, AC13, AC14 | Gate F (consumer); AC14's silent-peer startup test includes the separate Flyway user |

## Targeted tests after the fixes

| Command | Result |
|---|---|
| `mvn -o -T1 -Pdocker -pl errors/platform-errors-autoconfigure,data/platform-data-api,data/platform-data-jpa-autoconfigure,data/platform-starter-data-jpa-oracle -am verify` | BUILD SUCCESS — errors 73, data-api 11, data-jpa 52 unit/slice + **40 Oracle container tests** (was 23), 0 skipped (`evidence-raw/r1-errors-data-verify.log`) |
| `mvn -o -T1 -pl data/platform-data-jpa-autoconfigure,errors/platform-errors-autoconfigure -amd test-compile` | BUILD SUCCESS, 12 modules (`evidence-raw/r1-downstream-compile.log`) |

## Carried to Gate E

- F2 residual: a `{vendor}` Flyway location with a separate Flyway user opens one unbounded connection.
- Gate B observation: one missed transaction-timeout cancel of SQL inside a PL/SQL function (STATE.md).
