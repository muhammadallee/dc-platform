# Chassis audit — generated service: logging + data + REST client

State and handoff: [chassis-audit-state.md](chassis-audit-state.md). Decisions: D83–D87 in
[decision-log.md](decisions/decision-log.md). Evidence logs live outside the checkout
(`D:\dcpa\evidence\`, not tracked; CI keeps the gate's evidence as the `golden-path-evidence` artifact).

## 1. Scope

Make a freshly generated service (Java 25, Spring Boot 4.1.0) build, start, and exercise structured
logging, data access, and the platform REST client with no edits after generation — fixed in the
producers (archetype, starters, auto-configuration, test kit, gate scripts, CI), not in a generated
copy. In scope: `tooling/platform-service-archetype`, `build/platform-service-parent`, the logging,
data-jpa, restclient, security, and test-kit modules a generated service reaches,
`tooling/scripts/golden-path.sh`, `.github/workflows/ci.yml`, DX docs. Out of scope: other capabilities'
redesign, business features, unrelated upgrades.

## 2. Environment (baseline)

| Item | Value |
|---|---|
| Branch / base commit | `main` @ `efd3070ff68044600308d63b3df7414b2fedd564`, clean (one untracked file: the task prompt); changes uncommitted |
| OS | Windows 10 Home 10.0.19045 amd64 (long paths on); Bash = Git Bash; PowerShell 7 |
| JDK | Amazon Corretto 25.0.3+9-LTS (the shell default is JDK 21; `JAVA_HOME` set per command) |
| Maven | 3.9.9; no `~/.m2/settings.xml` (Maven Central direct); `.mvn/jvm.config` `-Xmx2g -Xss8m -XX:+UseParallelGC` |
| Versions | Spring Boot 4.1.0 (root `spring-boot.version`, `platform-service-parent`'s parent), `maven.compiler.release=25`, train `revision=1.0.0-SNAPSHOT`, Hibernate 7.4.1, H2 2.4.240, Tomcat 11.0.22 |
| Maven repositories | task-owned only: `D:\dcpa\m2` (fresh, cold at start), `D:\dcpa\m2f` (fresh, final build), a C:-drive copy for the cross-drive check; the user's `~/.m2` was never read or written |
| Profiles | reactor: none; generated services: none / `local` / `test` / `prod` recorded per run |

## 3. Run contract

| Mode | Contract |
|---|---|
| Documented default launch | `mvn verify`, then `mvn spring-boot:run` or `java -jar target/<artifactId>-<version>.jar` with **no active profile**: JSON logs, H2 in-memory for `data`, offline placeholders for the IdP JWKS URL and the sample downstream. `local` is advertised too (console logs) and is tested separately. Authenticated calls need a real (or local test) JWKS URL, supplied as a property. |
| Local behavioral verification | No Docker, credentials, corporate or public endpoints: H2 for data, a loopback JDK `HttpServer` stub for outbound calls, a loopback JWKS issuer (`TestJwtIssuer`) so tokens are really validated. |
| Production (`prod` profile) | Secure defaults kept. IdP, datasource, downstream URL supplied externally. A missing value fails startup with an actionable message; never H2, never the placeholder IdP or downstream; the capability report requires authentication. |

Runs are self-contained at runtime but not offline: a cold run downloads Boot 4.1.0 and third-party
artifacts from Maven Central into the task-owned repository. No offline run was executed; no offline
claim is made.

## 4. Producer → consumer map

```
root pom (revision, Boot 4.1.0, flatten) ── build/platform-dependencies ─▶ build/platform-bom ─▶ build/platform-service-parent
                                                                                               (parent: spring-boot-starter-parent 4.1.0;
                                                                                                enforcer bans, repackage+build-info, jacoco, flatten)
capability modules  api ─▶ autoconfigure ─▶ starter   (logging, data-jpa, restclient, security, core, errors, …)
tooling/platform-service-archetype  (maven-archetype 3.4.0 packaging; parent = bare aggregator)
   │ install ─▶ platform-service-archetype:<rev>  (metadata: platformVersion default = <rev>, features, owner)
   ▼ archetype:generate (plugin 3.1.2, project-less, outside the checkout; Velocity; package relocation)
generated service ─ parent platform-service-parent:<rev> ─ BOM ─ starters (+ feature starters) ─ test kit
   ▼ mvn verify: surefire runs generated tests (slices, real-HTTP security/logging, data, client, conformance)
   ▼ spring-boot repackage ─▶ executable jar ─▶ java -jar / spring-boot:run ─▶ HTTP + log probes (golden-path.sh)
```

## 5. Contract-to-evidence table

| Requirement | Owner | Proof before | Gap | Gate now |
|---|---|---|---|---|
| Omitted properties resolve the train | archetype metadata | none (golden path passed the version) | default `0.2.0-SNAPSHOT` | gate `none-defaults`, archetype-it `basic`, gate checks jar metadata |
| Feature selection / validation | archetype templates | none | substring match, no validation | gate step 3 (5 invalid inputs) + `assert_generated` per scenario |
| REST-client generation | archetype + restclient starter | none | no feature | 4 gate scenarios + `GreetingClientTest` |
| Migrations actually run | data starter + template | none (Hibernate DDL hid it) | no `spring-boot-flyway`, no migration | `FlywayMigrationSliceTest`, `NoteRepositoryTest`, gate log check |
| Token validation over real HTTP | security + test kit | MockMvc `jwt()` only | decoder never exercised | `SecurityIntegrationTest`, gate 401/200 probes |
| Token relay / correlation to downstream | restclient autoconfigure | unit test with user-supplied bean | never active in an app | ordering test + `GreetingClientTest` relay chain |
| Structured logs | logging autoconfigure | module `JsonLogOutputTest` | none in consumers | `StructuredLoggingTest`, gate `logcheck` on every jar |
| Prod fails fast | generated yml | none | ran on H2 + placeholder IdP | gate prod ×3 fail-fast + fixture boot ×2 |
| Packaged jar runs | service parent | golden path used `spring-boot:start` only | no `java -jar` | gate `assert_jar` + jar boot per scenario |
| Gate runs in CI | ci.yml | not wired; archetype-it skipped | — | `generator-gate` job (not yet run in CI) |

## 6. Findings

Classification: CD = confirmed defect, RG = requirement gap, SI = suspected issue, EB = environment
blocker, OH = optional hardening. "Reproduced" means a failing command or test was observed on the base
commit; static findings say so.

| ID | Pri | Class | Location | Evidence | Root cause | Fix | Regression test | Status |
|---|---|---|---|---|---|---|---|---|
| F1 | P1 | CD | `archetype-metadata.xml` `platformVersion` default; IT fixtures | Reproduced: omitted-default generation → parent `0.2.0-SNAPSHOT`, `mvn verify` "Non-resolvable parent POM"; `-Parchetype-it` fails the same way | literal default never tied to the train | default `${project.version}`, filtering only that file (D84); fixtures omit it | gate `none-defaults`; archetype-it `basic`/`full`; gate asserts the installed metadata | FIXED |
| F2 | P1 | CD | all templates' `$features.contains("x")`; metadata | Reproduced: `features=database` enabled data; `kafka` and `restclient` silently produced a bare service; `<validationRegex>` probed: ignored in batch mode by 3.1.2 and 3.4.0 | substring matching, no validation | exact tokens + validation in the `pom.xml` template (D83) | gate step 3: `kafka`, `database`, `data, restclient`, `none,data`, `data,data` rejected with message, no buildable leftovers | FIXED |
| F3 | P1 | RG | archetype | Reproduced: no way to generate a REST-client service | not in the original spec | `restclient` token, starter, `client/GreetingClient`, yml `app.greeting.base-url` (non-prod placeholder), `GreetingClientTest` | 4 gate scenarios incl. requested service | FIXED |
| F4 | P1 | CD | `platform-starter-data-jpa`; `FlywayPresenceCheck` | Reproduced: data jar log has 0 Flyway lines; with the new generated test on base artifacts, `NoteRepositoryTest` 4/4 errors (no `Flyway` bean) | Boot 4 moved `FlywayAutoConfiguration` to `spring-boot-flyway`; guard checked `flyway-core` only | starter adds `spring-boot-flyway` (as locking/idempotency do, D41); guard checks both | `FlywayPresenceCheckTest` (engine-only case), `FlywayMigrationSliceTest`, `NoteRepositoryTest`, gate "Successfully applied 1 migration" | FIXED |
| F5 | P2 | CD | data sample | Static + F4: table existed only through Hibernate `create-drop` on embedded H2 | sample shipped no migration | `db/migration/V1__create_note.sql`, `@Column(nullable=false)`, `setText` | `NoteRepositoryTest` (Flyway script applied, CRUD in separate transactions, rollback, NOT NULL) | FIXED |
| F6 | P1 | CD | generated `application.yml` | Reproduced: `--spring.profiles.active=prod` → readiness UP on `jdbc:h2:mem`, placeholder JWKS, anonymous `/actuator/platform` = 200 | placeholders in the all-profiles document; Boot's embedded fallback | placeholders on `on-profile: "!prod"`; `prod`: `embedded-database-connection: none` (D85) | gate: prod without datasource / IdP / downstream each refuses to start with the fix named; prod fixture: anonymous capability report 401 | FIXED |
| F7 | P1 | CD | `PlatformRestClientAutoConfiguration` | Reproduced: new ordering test fails without the fix (relay bean absent); generated relay test on base artifacts fails (no `Authorization` downstream) | `@ConditionalOnBean(CurrentUserAccessor)` evaluated before security (alphabetical order) | `afterName` security auto-configuration | `tokenRelayRegistersUnderTheRealAutoConfigurationOrder…`; `GreetingClientTest.inboundCorrelationAndToken…` | FIXED |
| F8 | P3 | CD | `DefaultPlatformRestClientFactory` | Static: API javadoc promises `Observation` instrumentation and client-name metric tags; builder had no registry | `RestClient.builder()` without observation wiring | lazy `ObservationRegistry`, `client.name` convention | `recordsAnHttpClientObservationTaggedWithThePlatformClientName` | FIXED |
| F9 | P3 | CD | archetype packaging | Reproduced: generated services had no `.gitignore` (jar lacked it); flatten leaves `.flattened-pom.xml` | default excludes drop `.gitignore` | `__gitignore__` + `gitignore` property; ignore `.flattened-pom.xml` | gate `assert_generated` requires `.gitignore` | FIXED |
| F10 | P2 | CD (verification) | `golden-path.sh`, `ci.yml`, archetype-it, generated tests | Observed: gate covered `messaging` only, explicit version, fixed ports 8080/9001, no jar/auth/data/client probes; not in CI; archetype-it skipped and broken; generated tests = context load + MockMvc JWT | — | new gate (§7), CI `generator-gate`, fixtures, generated real-HTTP tests | the gate itself; red runs in §8 | FIXED (CI run NOT_RUN) |
| F11 | P3 | CD (docs) | `@PlatformTest`, `docs/testing.md`, book ch. 14 | Static: said `test` → console logs; code switches only on `local` | stale docs | corrected | `StructuredLoggingTest` parses JSON under `test` | FIXED |
| F12 | P3 | CD (docs) | quickstart, dx.md, D66 | Reproduced: plugin 3.4.0 generates project-less on Maven 3.9.9; 3.1.2 runs post-generate Groovy; "requires a project" appears when PowerShell splits unquoted `-Da.b=…` | misattributed failure | docs corrected; 3.1.2 kept; PowerShell quoting documented; Groovy ruled out (3.4.0's Groovy fails on Java 25 classes) | gate uses 3.1.2 | FIXED (docs) |
| F13 | P2 | CD (documented, not implemented) | `LogSanitizer` (logging-api) | Static: javadoc says platform log-enrichment applies sanitizer beans; no component does | enrichment never built | not fixed — needs an encoder/MDC hook design | generated `StructuredLoggingTest` and gate prove bearer tokens stay out of logs (not a universal redaction claim) | OPEN |
| F14 | P3 | OH | token relay | Static: relay applies to every client/host the factory builds | by design | documented in `docs/modules/restclient.md`; cross-origin redirect verified not to forward (`credentialsSetByAnInterceptorAreNotForwardedToAnotherOriginOnRedirect`) | — | OPEN (hardening) |
| F15 | P4 | OH | `PlatformUsageRules.noDirectObjectMapperInstantiation` | Static: matches Jackson 2 FQN only; Boot 4 default is Jackson 3 (`tools.jackson`) | pre-Boot-4 rule | — | — | OPEN |
| F16 | P4 | OH | archetype POM | Static: `spring-boot-starter-web` is deprecated in 4.x for `spring-boot-starter-webmvc` | — | not changed (no behavior impact) | — | OPEN |
| F17 | — | SI | `platform-service-parent` surefire | Suspected cross-drive manifest-JAR issue (fixed for platform modules only). Not reproduced: service on D:, repository on C:, Windows PowerShell, 26/26 tests | — | none | — | CLOSED (not reproduced) |
| F18 | P3 | OH | pre-existing, outside the three capabilities | `messaging` feature = in-memory transport with no prod guard; `release.sh` runs the gate without its `-Drevision`; `smoke-matrix.sh` uses a fixed port and `/tmp` log | — | not changed | — | OPEN |
| F20 | P2 | CD | `examples/example-golden-path` | Reproduced after the F4 fix: `OrderFlowTest` 500 "Table ORDERS not found" (Flyway now owns the schema; Hibernate DDL is off) | the example had no migration; F4 had hidden it (and `-Ppg` could never have created the table) | `db/migration/V1__create_orders.sql` | `OrderFlowTest`, `OrderProblemResponseTest` (reactor) | FIXED |
| F21 | P2 | CD | git mode of `tooling/scripts/golden-path.sh` | Static: tracked as `100644`; `release.sh` and `release.yml` call `./tooling/scripts/golden-path.sh`, which fails with "Permission denied" on Linux | executable bit never recorded (Windows checkout) | new CI job calls `bash tooling/scripts/golden-path.sh`; the mode itself is left for the committer (`git update-index --chmod=+x tooling/scripts/golden-path.sh`) | — | PARTIAL |
| F19 | — | EB | this workstation | `mvn -T1C` full reactor hits the OS native-thread limit here (prior-session record); long background JVMs get reaped | environment | full builds run at `-T1`, foreground, in chunks | — | see §8 |

## 7. The gate

`tooling/scripts/golden-path.sh` (+ `tooling/scripts/support/GateSupport.java`, JDK-only, compiled against
the installed `platform-test-api`) — see [dx.md](modules/dx.md#golden-path-toolingscriptsgolden-pathsh). Staged
lifecycle: isolated repository → platform install (tests skipped; the tested build is the reactor
gate) → installed-archetype identity + metadata check → negative inputs → 8 scenarios generated outside
the checkout → generated `mvn verify` with per-class report checks (no zero-test, failed, or skipped
classes) → jar manifest/content checks → `java -jar` on a free port, readiness with process-identity
(unique run id in `/actuator/info`) → HTTP/auth/capability/log probes → teardown of every owned process
(trap on exit). Evidence (`GP_EVIDENCE`): per-scenario generate/verify/app logs, surefire XML, summary.
The per-service SLA (generate → verify → packaged boot, warm repository) is 600 s; the whole matrix is
longer and is bounded by the CI job's 75-minute timeout.

## 8. Verification

PASS / FAIL / BLOCKED / NOT_RUN. All local runs on the Windows workstation above; CI provenance: none.

### Baseline (base commit, before any edit)

| Scenario | Command (abridged) | Exit | Tests | Result |
|---|---|---|---|---|
| Reactor | `mvn -B -T1 clean install -Dmaven.repo.local=D:\dcpa\m2` (cold) | 0 | 932 run / 0 fail / 0 skip (259 suites; `docker`-tagged excluded by config) | PASS, 20 min |
| Omitted defaults | `archetype:generate` without `platformVersion`/`features`, then `mvn verify` | 0 / 1 | — | FAIL (parent 0.2.0-SNAPSHOT) |
| `messaging` / `data` / `messaging,data` | generate + `mvn verify` | 0 | 4 each | PASS (context load + MockMvc only) |
| `data,restclient`, `database`, `kafka` | generate | 0 | — | FAIL (silently accepted) |
| `data` jar | `java -jar`, readiness, `/hello` | — | — | readiness UP, 401; **0 Flyway log lines** |
| `data` jar, `prod` | `--spring.profiles.active=prod` | — | — | FAIL: UP on H2 mem, `/actuator/platform` 200 anonymous |
| Golden path | `bash tooling/scripts/golden-path.sh` (MAVEN_OPTS isolated repo) | 0 | 4 | PASS, messaging only, 172 s warm |
| archetype-it | `mvn -Parchetype-it -pl tooling/platform-service-archetype verify` | 1 | — | FAIL (0.2.0-SNAPSHOT parent) |

### Red → green (fix-specific)

| Check | Before fix | After fix |
|---|---|---|
| `PlatformRestClientAutoConfigurationTest` ordering case (fix reverted locally) | 1 failure | 8/8 pass |
| Generated `data,restclient` on base data/restclient artifacts | 26 run: 1 failure (relay), 4 errors (Flyway) | 26/26 pass |

### After fixes (final tree, fresh repository `D:\dcpa\m2f`)

Generated test counts per scenario = the classes `assert_tests` requires (base 14; +4 `data`, +8
`restclient`); every class must run with 0 failures/errors/skips. Gate work dirs: `D:\dcpa\final-gate-{A,B,C}`.

| Scenario | Inputs / coordinates | Command | Profile | Exit | Tests | Result |
|---|---|---|---|---|---|---|
| Tested reactor build + staging | whole tree | `mvn -B -T1 clean install -Dmaven.repo.local=D:/dcpa/m2f -pl <30-module chunk>` ×4, foreground (`D:\dcpa\evidence\final-chunk{1..4}.log`) | — | 0,0,0,0 (chunk 4's first attempt failed → F20, fixed, re-run) | 952 run / 0 fail / 0 err / 0 skip (261 suites) | PASS, 20.3 min cold |
| Invalid input | `kafka`, `database`, `data, restclient`, `none,data`, `data,data` | gate step 3 | — | ≠0 each | — | PASS: rejected with message, no buildable leftovers |
| Omitted optional properties | `features`/`platformVersion` omitted → `none`, `1.0.0-SNAPSHOT` | gate `none-defaults` | none | 0 | 14 | PASS, 63 s |
| `messaging` | `com.dc.demo:demo-messaging` | gate | none | 0 | 14 | PASS, 62 s |
| `data` | `demo-data` | gate | none | 0 | 18 | PASS, 81 s |
| `restclient` | `demo-restclient` | gate | none | 0 | 22 | PASS, 65 s |
| `messaging,data` | `demo-messaging-data` | gate | none | 0 | 18 | PASS, 76 s |
| `messaging,restclient` | `demo-messaging-restclient` | gate | none | 0 | 22 | PASS, 65 s |
| **Requested: `data,restclient`** | `com.dc.demo:demo-service` | gate | none | 0 | 26 | PASS, 82 s |
| ↳ documented launch | same | `mvn spring-boot:run -Dspring-boot.run.arguments=…` | none | — | — | PASS: ready, 401/200, JSON logs, migration |
| ↳ local profile | same jar | `java -jar … --spring.profiles.active=local` | local | — | — | PASS: ready, 200, console log format |
| ↳ prod, missing datasource / IdP / downstream | same jar | `java -jar … --spring.profiles.active=prod` (+2 of 3 settings) | prod | 1, 1, 1 | — | PASS: refused to start, message names the fix |
| ↳ prod, supplied fixture settings, twice | JWKS + `jdbc:h2:file:…` + downstream URL | `java -jar … --spring.profiles.active=prod …` ×2 | prod | — | — | PASS: run 1 applies V1, anonymous `/actuator/platform` 401; run 2 "No migration necessary" |
| Relocation (all features, any order) | `org.example.billing:billing-svc`, package `com.acme.billing.core`, dir `path with space`, `features=restclient,messaging,data` | gate `all-relocated` | none | 0 | 26 | PASS, 82 s |
| Gate install step | `GP_SKIP_INSTALL` unset, warm repo | gate (`mvn -T1C install -DskipTests`) + `none-defaults` | — | 0 | 14 | PASS; installed archetype sha256 prefix `9a5dcd2b3a615e51` equals the tested build's (reproducible) |
| archetype-it | `basic` / `full` / `service` | `mvn -B -Parchetype-it -pl tooling/platform-service-archetype verify -Dmaven.repo.local=D:/dcpa/m2f` | — | 0 | 14 / 26 / 26 | PASS |
| Windows-native | `com.acme.orders:orders-service`, `data,restclient`; repo on **C:**, project on **D:** | PowerShell, quoted `-D` args: generate, `mvn verify`, `java -jar` + `Invoke-WebRequest` | none | 0 | 26 | PASS: ready, 401, 200, correlation + migration in JSON log, token absent (run on the pre-F20 tree; the archetype/starter inputs are unchanged since) |
| Linux (CI `build` + `generator-gate`) | — | GitHub Actions | — | — | — | NOT_RUN (no push) |
| `mvn -T1C verify` full reactor | — | — | — | — | — | NOT_RUN here (workstation native-thread limit, F19); CI runs it |
| `@Tag("docker")` suites (PostgreSQL parity, brokers) | — | `mvn -Pdocker verify` | — | — | — | NOT_RUN (no Docker) |
| Offline build | — | — | — | — | — | NOT_RUN |

Timing: cold tested reactor build 20.3 min (baseline 20.0 min); gate per service 62–91 s warm (SLA
600 s); whole 8-scenario matrix ≈ 14 min warm plus the platform install (73 s warm, `-T1C`,
`-DskipTests`). The 10-minute target is per service with a warm repository; a cold repository adds the
Maven Central download.

Outcome: **PARTIAL** — every locally executable row passes on Windows; CI/Linux, the parallel `-T1C`
reactor gate, Docker-backed suites, and an offline run are NOT_RUN, and F13–F16/F18 remain open.

## 9. Reproduction (validated commands)

```bash
# JDK 25 on PATH; Maven 3.9+. From the platform checkout:
mvn -B -T1 clean install -Dmaven.repo.local=/path/to/fresh-repo          # tested build + staging
GP_MAVEN_REPO=/path/to/fresh-repo GP_SKIP_INSTALL=1 bash tooling/scripts/golden-path.sh   # full gate
mvn -B -Parchetype-it -pl tooling/platform-service-archetype verify -Dmaven.repo.local=/path/to/fresh-repo

# The requested service, from any directory outside a Maven project:
mvn -B org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate \
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform -DarchetypeArtifactId=platform-service-archetype \
  -DarchetypeVersion=1.0.0-SNAPSHOT -DgroupId=com.acme.orders -DartifactId=orders-service \
  -Dpackage=com.acme.orders -Dfeatures=data,restclient -DinteractiveMode=false
cd orders-service && mvn verify && java -jar target/orders-service-1.0-SNAPSHOT.jar \
  --spring.security.oauth2.resourceserver.jwt.jwk-set-uri=<your IdP JWKS URL>
# prod: --spring.profiles.active=prod plus jwk-set-uri, spring.datasource.url/username/password (+ driver), app.greeting.base-url
```

On Windows PowerShell quote every `-D…` argument. Add `-Dmaven.repo.local=…` everywhere when using a
task-owned repository.

## 10. Residual items

| Item | Next action |
|---|---|
| CI: `generator-gate` and `build` jobs never ran on this tree (no push). | Push a branch; confirm both jobs green on `ubuntu-latest`; keep the uploaded `golden-path-evidence`. |
| Linux execution of the gate: not run locally (Windows Git Bash only). | The CI job is the Linux run. |
| Real databases: only H2 was exercised; PostgreSQL parity is the `@Tag("docker")` `PostgresParityIT` (not run: no Docker here); Oracle untested. | `mvn -Pdocker verify -pl data/platform-data-jpa-autoconfigure` on a Docker host; add a vendor migration check before production use. |
| F13 `LogSanitizer` is documented but never applied. | Design a logback `JsonProvider`/MDC hook that applies ordered `LogSanitizer` beans, or narrow the javadoc; add a synthetic-secret test. |
| F14 token relay reaches every host of every client. | Consider `dc.platform.restclient.clients.<name>.relay-token` (default true) so third-party clients can opt out. |
| F15 conformance rule misses Jackson 3 `ObjectMapper`/`JsonMapper` construction. | Add `tools.jackson.databind.ObjectMapper` and `…json.JsonMapper` names + a fixture. |
| F16 deprecated `spring-boot-starter-web` in the template. | Switch to `spring-boot-starter-webmvc` in a separate change with a regenerated matrix. |
| F18 in-memory messaging has no prod guard; `release.sh` gate ignores `-Drevision`; `smoke-matrix.sh` fixed port/log. | Separate hardening tickets. |
| F21 `golden-path.sh` lacks the executable bit, so `release.sh`/`release.yml` would fail on Linux. | `git update-index --chmod=+x tooling/scripts/golden-path.sh` in the commit that lands this work. |
| Offline build | Not claimed; would need a pre-provisioned repository and a `-o` run. |
| Async / virtual-thread MDC propagation | Not a supported path in the generated service; not tested. |
| `mvn -T1C` full reactor on this workstation | Environment limit (native threads); CI's `-T1C` run is the evidence once it runs. |
