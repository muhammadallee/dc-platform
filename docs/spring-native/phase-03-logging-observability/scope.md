# Phase 3 — Logging + observability, Spring-native — Scope

Gate A of the capability recipe (plan §8). Phase start commit: `a0f58d5` (`develop`, the Phase 2 merge). Branch
`native/phase-03-logging-observability`.

## 1. What this phase does

Service code stops using the platform's logging types, and the platform's JSON logging becomes Spring Boot's own
structured logging:

| A service used to… | Now it… |
|---|---|
| log `Kv.of("orderId", id)` as an argument | logs with SLF4J's fluent API: `log.atInfo().addKeyValue("orderId", id).log("…")` |
| declare a `LogSanitizer` bean to mask its own data | lists extra regular expressions in `dc.platform.logging.redaction.patterns` (kept from v3) |
| get JSON from the platform's Logback file and Logstash encoder | gets Boot's structured logging (`logging.structured.format.console=logstash`), which the platform switches on and masks |

The platform keeps:
- **Output:** JSON by default, and readable console lines under the `local` profile or with `dc.platform.logging.format=console`. One line per event, and no banner in JSON mode.
- **Field names:** `@timestamp`, `level`, `logger`, `message`, `service`, `traceId`, `spanId`, `stack_trace`.
- **Masking:** credentials and the default personal-data set, plus extra patterns.
- **The strict MDC allow-list.**
- **Observability:** the management defaults (exposure, health groups, the OTLP switch), common tags, the capability gauge and `/actuator/platform`.

**New in this phase:**
- **Management port (ND13):** actuator moves to its own port, 8081 by default.
- **`dependencies` health group:** it drops the cut `redis` and the parked `restclient` (HI-0112).

**Excluded:**
- the REST client's own observations (Phase 9);
- messaging's trace header (Phase 6);
- span-attribute redaction for capabilities not yet ported (each capability's phase, contract 8).

## 2. Decisions taken at this gate

Recorded as D141 in [`../../decisions/decision-log.md`](../../decisions/decision-log.md).

1. **Boot's structured logging, with platform masking as a members customizer.** The plan asked Gate A to choose
   between the platform's Logback configuration and Boot's structured logging with masking as a Logback component. A spike
   ([`evidence/gateA-structured-logging-spike.txt`](evidence/gateA-structured-logging-spike.txt), test
   `S5bStructuredMaskingTest` in the Phase 0 spike project) ran Boot 4.1.2's Logstash format with a
   `StructuredLoggingJsonMembersCustomizer`. Its value processor is called for:
   - every member: the message, `stack_trace`, MDC keys and key-value pairs;
   - nested map members, with their names (`nested.password`);
   - list elements, and non-string values such as a `Double`.

   It also showed:
   - **Objects:** a record arrives as an object, and Boot writes its `toString()`, so the processor must turn unknown
     objects into masked text.
   - **Configuration:** the customizer can take the `Environment` in its constructor, which is how the extra patterns
     reach it.
   - **Field names:** `logging.structured.json.rename`, `exclude` and `add` keep the platform's field names without code.

   So the platform:
   - sets `logging.structured.format.console=logstash` and `logging.structured.format.file=logstash`, the renames and
     the `service` field as lowest-precedence defaults (rule 12, step 1). Setting the file format matters: a service that
     sets `logging.file.name` would otherwise get Boot's plain pattern file, unmasked;
   - registers its customizer in `META-INF/spring.factories`, which Boot 4.1.2 loads
     (`StructuredLogFormatterFactory.JsonMembersCustomizerBuilder`). The customizer reuses the v3 masker
     (`PlatformValueMasker`) with every boundary case Codex found in v3.

   **Removed:** `logback-platform.xml`, `PlatformJsonEncoder`, the raw-JSON and `Kv` serializers, and the
   `logstash-logback-encoder` dependency. The other option would keep a Logstash encoder that services cannot configure
   with Boot properties, and a second JSON writer to review.
2. **Masking cannot be dropped by accident.** Boot loads a customizer from `spring.factories` alongside any a service names
   in `logging.structured.json.customizer`. The spike proved both apply. So a service adds its own customizer without
   naming a platform class, and no startup check is needed.
   - **When it does nothing:** the customizer reads the `Environment`. It is a no-op when
     `dc.platform.logging.enabled=false` (Boot's own logging, unchanged) or `dc.platform.logging.redaction.enabled=false`.
   - **Limit:** a service's own Logback file (`logging.config`, `logback-spring.xml`) bypasses Boot's structured logging,
     as it bypassed the v3 file. That stays a documented limit, with a startup WARN.
3. **`Kv` and `LogSanitizer` are removed.**
   - **`Kv`:** SLF4J's `addKeyValue` covers it.
   - **`LogSanitizer`:** its use, masking a service's own data, is covered by `dc.platform.logging.redaction.patterns`. A
     service that needs code can add its own customizer next to the platform's.
   - **Module:** `platform-logging-api` goes (ND15), and so does `LogSanitizerRegistry`.
4. **The strict MDC allow-list is kept** (`dc.platform.logging.mdc.include`, D101). Boot writes MDC keys and key-value
   pairs as top-level members alike, so in strict mode the customizer's path filter keeps only:
   - Boot's standard fields;
   - `traceId` and `spanId`;
   - the listed keys, whether MDC or key-value.

   The change is written on the module page.
5. **Actuator on its own port (ND13).**
   - **Default:** the observability defaults set `management.server.port=8081`. Probes and the Prometheus scrape live
     there, and the application port serves only the API. Gate F proves `/actuator/prometheus` is a 404 on the
     application port.
   - **Random ports:** when `server.port` is `0` (tests with a random port), the default is `0` as well, so parallel
     tests do not collide.
   - **Disabling it:** a service that wants actuator on the application port sets `management.server.port` itself,
     which is a documented choice.
6. **The `dependencies` health group is `db,rabbit`** (HI-0112). Iteration 2 adds the REST client back when it unparks.
7. **The consumer floor is 65**: the v3 count 76 minus the 11 assertions Phase 1 dropped with JWT security
   ([`../phase-01-scope-cut/scope.md`](../phase-01-scope-cut/scope.md#drop-ledger)).

## 3. How the platform gives each behaviour (rule 12)

| Behaviour | Step | Mechanism |
|---|---|---|
| JSON lines, platform field names, `service`, no banner | 1 | `PlatformLoggingEnvironmentPostProcessor`: `logging.structured.format.console`, `logging.structured.json.rename.*`, `exclude`, `add.service`, `spring.main.banner-mode=off`, in `platform-logging-defaults` |
| Masking, extra patterns, strict MDC | Boot | an internal `StructuredLoggingJsonMembersCustomizer` in `META-INF/spring.factories`; Boot instantiates it with the `Environment`, and it reads the kill switches |
| A user Logback file warned about | 3 | a startup WARN from `LoggingAutoConfiguration`, kill switch `dc.platform.logging.enabled` |
| Readable console under `local` | 1 | the post-processor contributes nothing; Boot's own console logging |
| Exposure, health groups, OTLP switch, management port | 1 | `PlatformObservabilityEnvironmentPostProcessor`, in `platform-observability-defaults` |
| Common tags | 2 | a `MeterRegistryCustomizer` bean (unchanged) |
| Capability gauge, `/actuator/platform` | 3 | unchanged beans, `@ConditionalOnMissingBean`, kill switches |

No step-4 type remains in service code.

## 4. Contracts that apply (plan §7)

| # | Contract | How this phase meets it |
|---|---|---|
| 1 | Real override | every platform bean keeps its back-off test (`LoggingAutoConfigurationTest`, `CommonTagsAutoConfigurationTest`, `CapabilityMetricsAutoConfigurationTest`, `PlatformInfoEndpointAutoConfigurationTest`) |
| 2 | Default snapshot | `configurationDefaultsSnapshot` (logging properties), `contributedDefaultsSnapshot` (both post-processors) |
| 3 | Kill switch | `dc.platform.logging.enabled`, `dc.platform.observability.enabled`, both `DEFAULT_ON`, tested |
| 4 | Outage posture | collector down or slow: fail-open, requests unaffected (AC12) |
| 5 | Health | readiness pod-local, `dependencies` group (AC10) |
| 6 | Context propagation | every log line on its request's trace under load (AC4) |
| 7 | Error surface | the error body on the inbound trace, no internals (AC1, AC10) |
| 8 | Redaction | AC5 on Boot's output, every v3 masker case |
| 9 | Observability | URI-tag cardinality (AC8), no ID-like label values (AC7) |
| 10 | Graceful shutdown | AC14 |
| 11 | Startup under adversity | startup with the collector unreachable (AC12) |
| 12 | Spring types only | the ported probe imports no platform type; the harness greps for it |
| 13 | Back-off to the user's Spring bean | the user's own `MeterRegistryCustomizer` and `logging.structured.*` values win |
| 14 | Zero-config acceptance | an app with only the logging and observability starters logs masked JSON and serves the management port |

## 5. Acceptance criteria

Every v3 criterion of the logging and observability run stays (AC1–AC16 of
[`../../production-readiness/phase-02-logging-observability/scope.md`](../../production-readiness/phase-02-logging-observability/scope.md)),
with these changes:

- **AC3:** strict mode filters key-value pairs as well as MDC keys (decision 4).
- **AC6:** `LogSanitizer` is gone (decision 3). The extra-patterns property carries the use case, and so does a
  service's own customizer, added next to the platform's.
- **AC9:** exposure is checked on the management port. **New:** `/actuator/prometheus`, `/actuator/health` and
  `/actuator/info` are a 404 on the application port.
- **AC13:** adds two cases (decision 2): a service's own customizer adds to the masking, and a user Logback file logs
  a WARN.

**New criteria:**

- **AC17:** A `local` profile, or `dc.platform.logging.format=console`, gives Boot's console logging with no JSON and no platform customizer.
- **AC18:** No module, archetype file or probe references `Kv`, `LogSanitizer`, `PlatformJsonEncoder` or
  `logstash-logback-encoder`. A source scan proves it.

## 6. Carry-over table

Every `FIXED` or `ACCEPTED` logging or observability issue, and the §7 contracts above.

| Guarantee | Source | Lives today in | Lives after in | Proof | Kind |
|---|---|---|---|---|---|
| Credentials and the default PII set masked in message, MDC, arguments, exceptions and stack traces | HI-0033, D102, AC5 | `PlatformJsonEncoder`, `PlatformValueMasker`, `CredentialFieldMasker` | the platform customizer + `PlatformValueMasker` | `RedactedJsonLogOutputTest.masksEveryFieldTheEncoderWrites` (ported to Boot's output) | CARRIED |
| Every v3 masker boundary case (quoted values, cookies, short credentials, `basic Basic`, compound names, long inputs) | HI-0040, HI-0042, HI-0044, D102 addendum | `PlatformValueMasker` | `PlatformValueMasker` | `PlatformValueMaskerTest` (all), `EncoderRedactionTest` rows ported | CARRIED |
| Card numbers logged as numbers (`double`, arrays) masked | HI-0050 | `PlatformValueMasker`, encoder | customizer value processor (sees `Double`, spike) | `cardNumbersLoggedAsNumbersAreMasked` | CARRIED |
| A credential-named field is masked whatever its value type or nesting | HI-0049, D102 | `CredentialFieldMasker`, `KvJsonSerializer` | customizer, by member path name | `credentialNamesWinForEveryValueTypeAndNesting` | CARRIED |
| An object value (record, POJO) reaches the log only as masked text | spike S5b | Jackson through the Logstash encoder | customizer: unknown objects rendered with `toString()` and masked | new `objectValuesAreRenderedAsMaskedText` | NEW RISK |
| `raw(...)` JSON and Logstash markers cannot bypass masking | HI-0047, HI-0048 | `RawJsonMaskingGenerator`, `SanitizedMessageView` | — (no Logstash markers or raw JSON on the classpath) | AC18 source scan | RETIRED |
| `LogSanitizer` beans applied to MDC and arguments | HI-0034, AC6 | `LogSanitizerRegistry`, `SanitizedMessageView` | `dc.platform.logging.redaction.patterns`; a service's own customizer | `redactionCanBeSwitchedOffAndExtended` | RETIRED |
| Masking applies alongside a service's own customizer | decision 2 | — | `META-INF/spring.factories` | new `aServicesOwnCustomizerAddsToThePlatformMasking` | NEW RISK |
| A log file (`logging.file.name`) is masked JSON, not plain text | contract 8 | `logback-platform.xml` (no file appender) | post-processor `logging.structured.format.file` | new `aLogFileIsMaskedJson` | NEW RISK |
| A service's own Logback file bypasses masking (as in v3) | decision 2 | `logback-platform.xml` comment | module page + startup WARN | new `aUserLogbackFileLogsAWarning` | NEW RISK |
| One JSON line per event; CR/LF, quotes and forged JSON stay inside one event | AC2 | Logstash encoder | Boot's JSON writer | `userInputCannotForgeLinesOrFields` (ported), AC2 | CARRIED |
| Platform field names (`logger`, `service`, `traceId`, `spanId`, `stack_trace`) | AC1 | `logback-platform.xml` | post-processor renames | `logsJsonWithEcsIshFieldsAndCorrelation`, `stackTracesLandInTheStackTraceField` (ported) | CARRIED |
| No ASCII banner in JSON mode | HI-0045, D108 | logging post-processor | logging post-processor | `contributedDefaultsSnapshot`, AC14 | CARRIED |
| Platform code writes only `traceId`/`spanId` to the MDC; consumer MDC passes through, masked; strict mode keeps only listed keys | D101, AC3 | encoder `mdcInclude` | customizer path filter | `platformCodeWritesOnlyTraceAndSpanIdsToTheMdc`, `strictMdcModeWritesOnlyPlatformAndListedKeys`, `consumerMdcPassesThroughByDefault` | CARRIED |
| `local` profile and `format=console` give readable console lines | phase-04 spec, AC17 | logging post-processor | logging post-processor | `localProfileFallsBackToHumanReadableConsole`, `explicitConsoleFormatContributesNothing` | CARRIED |
| Console output is not masked, and CR/LF can split a console line | HI-0037 (ACCEPTED) | — | — | module page (documented limit) | CARRIED |
| Every log line on its own request's trace under concurrency | AC4, contract 6 | core filter + MDC | unchanged | AC4 (consumer), `TraceCorrelationTest` | CARRIED |
| A service with core + logging only still has a tracer | HI-0018 | `platform-starter-core` | unchanged | `TraceCorrelationTest` | CARRIED |
| Readiness is pod-local; shared dependencies in their own group | HI-0031, D104 | observability post-processor | unchanged | `aSharedDependencyOutageLeavesReadinessUp`, AC10 | CARRIED |
| The `dependencies` group names only shipped capabilities | HI-0112 | observability post-processor (`db,rabbit,redis,restclient`) | `db,rabbit` | `contributedDefaultsSnapshot` | NEW (fix) |
| Minimal exposure: `health,info,prometheus` | HI-0032, D105 | observability post-processor | unchanged | `onlyHealthInfoAndPrometheusAreExposed` | CARRIED |
| Actuator unreachable on the application port | ND13 | — | observability post-processor (`management.server.port`) | new `managementRunsOnItsOwnPort`; AC9 (consumer) | NEW RISK |
| Tests with a random port get a random management port | decision 5 | — | observability post-processor | new `aRandomServerPortGivesARandomManagementPort` | NEW RISK |
| `otlp.enabled=true` really exports spans | HI-0039 | starter + post-processor | unchanged | `theSwitchTurnsOnTheOtlpSpanExporter` | CARRIED |
| The capability gauge exists when Boot creates the registry | HI-0043 | `CapabilityMetricsAutoConfiguration` | unchanged | `scrapeCarriesCommonTagsAndNoHighCardinalityValues` | CARRIED |
| The observability descriptor backs off under its name | HI-0036 | `PlatformInfoEndpointAutoConfiguration` | unchanged | `descriptorBacksOffWhenUserDescriptorPresentUnderItsName` | CARRIED |
| URI-tag cardinality capped; unmatched server paths collapse | AC8 | Boot defaults, verified | unchanged | `literalOutboundUrisAreCappedAndTemplatedServerUrisStayBounded`, AC8 | CARRIED |
| Collector down or slow: requests unaffected, memory and warnings bounded, startup does not hang | AC12, contracts 4 and 11 | Boot OTLP exporters | unchanged | AC12 (consumer) | CARRIED |
| The documented collector property is current | HI-0035 | `docs/modules/observability.md` | unchanged | AC11 | CARRIED |
| Shutdown lines are JSON on stdout; exit 143 | AC14, contract 10 | encoder | Boot's writer | AC14 (consumer) | CARRIED |

**Mitigations for the `NEW RISK` rows:**
- **Object values:** objects are rendered as masked text; records keep their component names. A service that needs
  field-name masking logs a `Map`. The module page says so.
- **A service's own customizer:** the platform's comes from `spring.factories`, so it cannot be listed away.
- **Log file:** the file format defaults to the same masked JSON.
- **A service's own Logback file:** a WARN at startup, and a documented limit.
- **Management port:** the archetype's manifests and probes move to 8081, and the module page says how to turn the
  separate port off.
- **Random-port tests:** the default follows `server.port=0`.

Each row gets an issue-register entry at Gate E.

## 7. Test disposition

The baseline has 192 tests in the three modules: logging-api 5, logging-autoconfigure 150 (counting parameterised rows)
and observability 37. Here is what happens to each test method:

| Test class | Kept | Ported (to Boot's output or the customizer) | Retired, with its reason |
|---|---|---|---|
| `KvTest` (4) | — | — | all: `Kv` is removed |
| `ArchConstitutionTest` (logging-api) | — | — | the module is removed |
| `ArchConstitutionTest` (logging-autoconfigure, observability) | both | — | — |
| `PlatformValueMaskerTest` | `masksCredentialsAndPii`, `masksQuotedValuesAndWholeCookieHeaders`, `masksShortCredentials`, `basicWordInProseStaysWhenItIsNotACredential`, `keepsTheKeyAndSchemeVisible`, `cardNumberBoundaries`, `leavesOrdinaryValuesAlone`, `traceAndSpanIdFieldsKeepTheirHexIdsButNotOtherContent`, `masksWholeValueOfCredentialNamedFields`, `credentialNamesWinForEveryValueTypeAndNesting`, `masksCardNumbersWrittenAsNumbers`, `returnsNullWhenNothingChangesOrValueIsNotText`, `longInputsWithoutMatchesAreScannedQuickly`, `extraPatternsAreMasked` | — | `appliesSanitizersToNonPlatformFieldsOnly`, `patternsStillRunAfterSanitizersAndAThrowingSanitizerIsIgnored`: `LogSanitizer` is removed |
| `EncoderRedactionTest` | — | `aSchemeWordIsItselfACredentialUnlessAnotherSchemeFollows`, `ordinaryValuesNestedWithinTheTraversalDepthStayReadable`, `unchangedArrayContentsAndTheDepthBoundaryAreKept`, `changedArraysInsideCollectionsRenderByContent`, `cardNumbersInPrimitiveArraysAreMaskedInTheMessage`, `objectArraysWithNullsStayReadable`, `nothingSecretReachesTheEncodedBytes`, `aCredentialFieldIsReplacedWholeWithNoPartialObjectWritten`, `aThrowingToStringCostsOnlyThatArgument`, `anyThrowableFromToStringIsContainedAndNothingIsRenderedTwice`, `longQuotedValuesDoNotOverflowTheStack`, `hugeDecimalExponentsAreNotExpanded`, `encoderFieldsAndOrdinaryValuesStayReadable` | Logstash markers (`aThrowingValueInsideALogstashMarkerNeverReachesTheStatusManager`, `aMarkerNestedInAValueIsRenderedUnderTheSameGuard`, `chainedMarkersKeepTheirReferencesInTheMessage`, `markersInsideFieldsAreRenderedUnderTheGuard`, `aDeferredArgumentLeaksNothing`); raw JSON (`rawJsonIsMaskedLikeAnyOtherStructuredValue`, `rawJsonKeepsNumbersAndMasksEscapedAndDecimalSecrets`, `rawJsonThatIsNotOneCleanValueIsKeptWholeUnderThePatternMasks`, `rawJsonEscapesAndDuplicatesNeverHideACredential`, `unparseableRawTextIsDecodedMaskedAndWrittenAsAString`); `Kv` (`fieldsOfAKvKeepsItsField`); `LogSanitizer` (`sanitizersRunBeforeTheNumericCardCheckInTheMessage`, `customSanitizerReachesMapsNestedValuesArraysAndNumbersInFieldAndMessage`, `aSanitizedValueBelowTheTraversalDepthIsNotShownInTheMessage`); the encoder's restart (`restartingTheEncoderKeepsOneMaskingLayerAndTheConsumerDecorator`): none of these types or the encoder remain (AC18) |
| `SanitizedMessageViewTest` (2) | — | `aContainerHoldingItselfEndsTheDescentMasked` (a self-referencing map or list through the customizer) | `aSharedButAcyclicValueIsTraversedAtEachPlace`: the message view is removed; Boot formats the message |
| `RedactedJsonLogOutputTest` | — | `masksEveryFieldTheEncoderWrites`, `cardNumbersLoggedAsNumbersAreMasked`, `ordinaryValuesAreNotMasked`, `redactionCanBeSwitchedOffAndExtended`, `strictMdcModeWritesOnlyPlatformAndListedKeys`, `consumerMdcPassesThroughByDefault`, `userInputCannotForgeLinesOrFields` | `appliesLogSanitizerBeansToMdcAndArguments`, `aNonIdempotentSanitizerRunsOncePerOutput`: `LogSanitizer` is removed |
| `JsonLogOutputTest` | `killSwitchRestoresBootsOwnLogging`, `localProfileFallsBackToHumanReadableConsole` | `logsJsonWithEcsIshFieldsAndCorrelation`, `stackTracesLandInTheStackTraceField` | `theOnlyRootAppenderIsASynchronousConsoleAppenderWithThePlatformEncoder`: the encoder is removed; replaced by a check that Boot's structured console appender is the root appender |
| `LoggingAutoConfigurationTest` | `activeByDefault`, `killSwitchDisables`, `backsOffWhenUserBeanPresent`, `bannerReportsTheEffectiveConsoleFallback`, `unknownFormatFailsStartup`, `propertiesBindFromKebabKeys`, `unrelatedDescriptorsDoNotSuppressTheLoggingOne`, `configurationDefaultsSnapshot`, `platformCodeWritesOnlyTraceAndSpanIdsToTheMdc` | `inactiveWhenLoggingApiMissing` (the condition class changes) | `sanitizerRegistryActiveByDefaultAndOffWithRedaction`, `sanitizerRegistryBacksOffForAUserRegistryUnderAnyName`: the registry is removed |
| `PlatformLoggingEnvironmentPostProcessorTest` | `serviceNameDefaultsToSpringApplicationName`, `serviceNameFallsBackToApplicationWhenUnnamed`, `explicitConsoleFormatContributesNothing`, `userBannerModeWins`, `localProfileFallsBackToConsole`, `explicitJsonBeatsTheLocalProfileFallback`, `killSwitchDisablesEntirely`, `listPropertiesAreFlattenedForLogback` (for the customizer) | `defaultsLoggingConfigToThePlatformXml` (now the structured-format defaults), `userLoggingConfigAlwaysWins` (a user's structured values win), `contributedDefaultsSnapshot` | — |
| Observability (all eight classes) | all | `contributedDefaultsSnapshot` and `onlyHealthInfoAndPrometheusAreExposed` gain the management port and the new `dependencies` list | — |

## 8. ND13 beyond observability

Moving actuator to port 8081 changes every generated service. Rule 15 needs every consumer run and lab already ported to
pass at the merge. These read actuator on the application port and move to the management port in Gate B:
- **Harness:** `tooling/hardening/lib/harness.sh` (`hh_wait_ready`, the container's port mapping).
- **Scripts:** `tooling/scripts/golden-path.sh`, `tooling/scripts/smoke-matrix.sh`, `tooling/smoke/expectations.tsv`.
- **Lab:** `docs/labs/first-service/index.md`.
- **Examples:** `examples/example-golden-path` (`PlatformSurfaceTest`, `application.yml`), `examples/example-minimal`.
- **Archetype:** its `application.yml`, `README.md`, `AGENTS.md` and `CLAUDE.md`.

**At closure,** on the final Phase 3 commit, Phase 2's core-errors run (floor 77) and the `first-service` lab run again,
besides this phase's own run and lab.

## 9. Removal list

| Type or file | Module | Call sites (from the Phase 0 removal map and `grep`) |
|---|---|---|
| `Kv`, `LogSanitizer` (whole module `platform-logging-api`) | `logging/platform-logging-api` | `LoggingAutoConfiguration`, `PlatformJsonEncoder`, `KvJsonSerializer`, `LogSanitizerRegistry`, `SanitizedMessageView`, `PlatformValueMasker`, the logging tests, the consumer probe `ObservabilityProbe`, the BOM, the starter, the archetype and the OpenRewrite recipe |
| `PlatformJsonEncoder`, `logback-platform.xml` | `logging/platform-logging-autoconfigure` | the logging post-processor; docs |
| `KvJsonSerializer`, `RawJson`, `RawJsonMaskingGenerator`, `SanitizedMessageView`, `LogSanitizerRegistry`, `CredentialFieldMasker` | the same (`.internal`) | internal only |
| `net.logstash.logback:logstash-logback-encoder` | `platform-starter-logging`, platform parent `dependencyManagement` | the starter POM |

These go into [`../expected-removals.md`](../expected-removals.md) at Gate B.

## 10. Consumer run port plan

- **Run:** `tooling/hardening/scenarios/phase-02-logging-observability.sh`, probe `phase-02/probe/ObservabilityProbe.java`.
- **Floor:** 65.
- **Probe changes:**
  - **To Spring and SLF4J types:**
    - `Kv.of(…)` and Logstash `kv(…)` become `addKeyValue`;
    - `RequestContext` and `CorrelationId` give way to Micrometer's `Tracer`;
    - the parked `PlatformRestClientFactory` gives way to Boot's `RestClient.Builder`.
  - **Dependency:** the parked REST-client starter is no longer added. AC8 measures Boot's own client observation.
- **Script changes:**
  - Actuator requests go to the management port.
  - **New:** three assertions that the application port answers 404 for `/actuator/prometheus`, `/actuator/health` and
    `/actuator/info`.
  - **New:** a `grep` failing the run if the probe imports `ae.gov.dubaicustoms.platform` (contract 12).
- **Unchanged:** infrastructure (PostgreSQL as the data fixture, the OpenTelemetry Collector behind Toxiproxy), faults and
  every other assertion.
- **Repository:** a fresh one via `native-repo.sh native-03`.
- **To pass:** three passes in a row at or above 65.

## 11. Environment prerequisites

- **Tools:** JDK 25 (Corretto), Maven 3.9, and Docker with about 3 GB free for the consumer run. The run uses PostgreSQL
  17, the OpenTelemetry Collector contrib 0.115.1 and Toxiproxy 2.9.0, pinned by digest in the run identity.
- **The lab:** "logging and observability" needs no container beyond the app itself. It uses H2, and an optional
  collector is shown but not required.
