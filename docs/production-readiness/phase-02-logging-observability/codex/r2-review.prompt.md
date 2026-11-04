# Independent review — DC Platform capability hardening, Phase 2 (Logging + Observability), Round 2

You are the independent reviewer for a production-hardening phase of an internal Spring Boot 4.1 /
Java 25 platform ("chassis"). Your working directory is a checkout of the reviewed commit `0ffc3e1`; read
any file you need. This is round 2: round 1 reviewed `482ea5a` and raised seven findings, which the
implementer fixed. Review the WHOLE phase diff (phase start `a33168d` .. `0ffc3e1`) afresh, and in
addition:

1. **Verify each round-1 disposition** (table below): is the fix real and complete in the code and
   tests, or only partial? Answer per finding: `CONFIRMED` / `PARTIAL (why)` / `NOT FIXED (why)`.
2. **Hunt regressions introduced by the fixes** — in particular in `PlatformValueMasker` (new regexes:
   false positives on common values listed in R3.3, catastrophic backtracking, Basic/Bearer/JWT/cookie
   handling), `SanitizedMessageView` (a dynamic proxy over `ILoggingEvent`: behaviour with other
   encoders/appenders, arguments that are not `Kv`/markers, `{}` placeholder handling, exceptions,
   performance), the credential-ancestor logic for structured values, and the OTLP exporter dependency
   (startup with OTLP off, classpath conflicts, out-of-scope modules).
3. Hunt for any defect round 1 missed.

Hunt for real defects: correctness, security (log injection, secrets/PII reaching logs, redaction gaps and
false positives, information leakage through actuator endpoints), concurrency and MDC bleed, misleading
health signals, broken back-off/override, ordering bugs, untested claims, regressions in modules that
depend on the changed ones, and places where the implementation does not meet a stated acceptance
criterion. Items explicitly assigned to the consumer harness (Gate F, listed under "Acceptance criteria
after round 1") are known to be pending; do not report their absence, but do report anything in the code
that would make them fail.

## Severity scale

- **Critical** — data loss/corruption, security bypass, cross-caller data exposure, a defect that takes
  down all replicas.
- **High** — incorrect behaviour under a realistic failure (outage, slowness, restart, concurrency),
  leaked internals in responses/logs, broken back-off/override, misleading health signal.
- **Medium** — degraded operability/DX with a workaround; missing observability on a failure path.
- **Low** — cosmetic, docs, naming.

## Required output format

First a section `## Round-1 dispositions` with one line per F1–F7 (and per re-evaluation concern):
`F<n>: CONFIRMED | PARTIAL | NOT FIXED — <reason with file:line>`.

Then, for EACH new finding (number them R2-F1, R2-F2, …):

```
### R2-F<n> — <short title>
- Severity: Critical | High | Medium | Low
- Location: <path>:<line>
- Failure scenario: <concrete inputs/state → wrong output/crash>
- Proof or reasoning: <code references; why it happens>
- Proposed fix: <specific>
```

Then a final section `## Acceptance criteria not met` listing any AC from the scope that the diff does
not satisfy or does not test at its stated SLICE/UNIT level, with the reason. If you find nothing for a
category, say so explicitly. Do not pad with style nitpicks. Your sandbox may not run the JVM; reason
from source and say where a claim is unverified.

## Phase scope (requirements + acceptance criteria)

## 1. Requirements

### R1 Structured logs (G2-05 verify, G2-04)
1. Every line in JSON mode is one JSON object with `@timestamp`, `level`, `logger`, `message`, `service`,
   and on request-scoped lines `traceId` and `spanId` (delivered in Phase 1; re-verified here as a logging
   contract, not a core one). `stack_trace` on errors.
2. JSON mode is the default outside the `local` profile; `console` is for humans only.
3. **Log injection:** user-controlled text (message arguments, MDC values, exception messages) cannot
   forge a second line or a field. JSON mode: guaranteed by JSON string escaping — tested with CR, LF,
   `"`, ` `, and an embedded `{"level":"ERROR"…}`. Console mode: CR/LF in the message are replaced
   (`%replace`) so one event stays one line.

### R2 MDC lifecycle and field allow-list (§7.8 "allow-list where the platform controls field emission")
1. The allow-list governs what **platform code** emits: the platform writes only `traceId` and `spanId`
   to the MDC (asserted by a test that scans every `MDC.put` in platform main code — today only
   `RequestContext`). Consumer MDC keys keep passing through to JSON (unchanged default), **with masking
   applied** (R3). A strict mode is opt-in: `dc.platform.logging.mdc.include` (list) — when set, only
   `traceId`, `spanId` and the listed keys are written. `include-mdc` stays as is. No breaking change.
   Decision to log: **D101**.
2. MDC is empty after each request on request and pool threads (Phase 1 AC9 re-asserted under the logging
   encoder) — concurrency test with ≥ 200 parallel requests, zero bleed in log lines.

### R3 Redaction (G2-03, §7.8)
1. The JSON encoder masks, in every string field it writes (message, MDC values, structured arguments,
   exception message and `stack_trace`):
   - JWTs (`eyJ…​.​…​.​…`), `Bearer <token>`, `Basic <b64>` credentials;
   - values of keys named (case-insensitive) `password`, `passwd`, `secret`, `token`, `access_token`,
     `refresh_token`, `client_secret`, `authorization`, `cookie`, `set-cookie`, `api-key`/`apikey`
     in structured arguments and in `key=value` / JSON-ish text;
   - the default PII list: Emirates ID (`784-YYYY-NNNNNNN-C`, with or without dashes), payment card
     numbers (13–19 digits passing Luhn, bounded by non-digits; also when logged as a number), e-mail
     addresses, phone numbers (international with `+`/`00`; UAE mobile in local form). Decision to log:
     **D102** (default list; services extend it; e-mail and phone added by the owner 2026-10-26).
2. Masked text is replaced by `****` (fixed marker; length not preserved).
3. **No false positives** on common values: epoch millis (13 digits), numeric order IDs, 32-hex trace IDs,
   16-hex span IDs, UUIDs, ISO timestamps, HTTP status codes and durations are **not** masked (tested;
   a Luhn-valid numeric ID is a known residual risk, documented).
4. `LogSanitizer` beans (public API, `STABLE`) are **currently applied nowhere** (no "platform
   log-enrichment component" exists). Either they are applied by the encoder to MDC and structured-argument
   values, as their javadoc promises, or the type is deprecated with a reason. Preferred: apply them
   (keeps the published contract). Decided at Gate B with a design note.
5. Redaction holds when `logging.config` is the platform's; a service that sets its own `logging.config`
   opts out (documented, not enforced).

### R4 Tracing and sampling (G2-07, G2-08)
1. Instrumentation point is the Observation API (Boot's). The platform's own work (capability gauge,
   correlation filter) adds no custom spans with high-cardinality key-values.
2. Sampling policy: head-based `management.tracing.sampling.probability` (Boot default `0.1`, verified at
   Gate B). Head sampling cannot know a request will fail, so "always record errored requests" is
   delivered by **collector tail sampling** (OTel Collector `tail_sampling`, `status_code: ERROR`
   policy) — documented with a ready collector snippet; platform posture recorded as **OPEN (deployment
   responsibility)** per plan §9 Phase 2. Trace ID in logs and ProblemDetail is independent of sampling
   (Phase 1 AC6). Decision to log: **D103**.
3. OTLP export: off by default (no collector locally); `dc.platform.observability.otlp.enabled=true`
   turns on metrics and traces export through Boot's properties. The on-switch path is verified against a
   real collector. Resource attribute `service.name` = `spring.application.name`.
4. Collector unavailable or slow → requests unaffected (latency and status), exporter memory bounded,
   failure logged without flooding.

### R5 Metrics (G2-06, §7.9)
1. Prometheus endpoint `/actuator/prometheus` serves platform and Boot meters with common tags
   `service`, `env`, `platform.version`.
2. Tag-cardinality guard: Boot 4.1 already caps `uri` values for server and client HTTP meters
   (`management.metrics.web.server.max-uri-tags` / `…client.max-uri-tags`, default 100, deny + log once,
   `WebMvcObservationAutoConfiguration$MeterFilterConfiguration`; verified in the
   `spring-boot-micrometer-metrics-4.1.0` metadata). The platform relies on it, adds no `uri` filter of
   its own, and tests that it is active in a platform app. Unmatched paths already collapse to
   `NOT_FOUND`, so the test that **fails without the cap** is the outbound one: 1 000 REST-client calls to
   literal (non-templated) URIs → ≤ 100 `http.client.requests` series + one WARN. Platform-owned meters
   (`platform.capability.active`) use only bounded tags (capability names). Consumer scan: no tag value
   in `/actuator/prometheus` matches a trace ID, span ID, UUID, or JWT.
3. `env` tag = first active profile (low cardinality) — kept; documented.

### R6 Health (G2-01, §7.5, D6)
1. `liveness` = `livenessState` only. `readiness` = `readinessState` only (pod-local). Shared
   dependencies (`db`, `rabbit`, `redis`, later JWKS/downstream) move to a separate group
   **`dependencies`** for dashboards (`validate-group-membership=false` stays, members are optional).
2. Demonstrated: DB down → `readiness` UP, `liveness` UP, `dependencies` DOWN; requests that need the DB
   fail with ProblemDetail, others succeed.
3. Group health details: `show-details` stays Boot's default (`never`) on unauthenticated access.

### R7 Actuator exposure (G2-02) and interim access posture
1. Chassis default exposure `health,info,prometheus`. `metrics` and `platform` are no longer exposed by
   the chassis default (opt-in via Boot's `management.endpoints.web.exposure.include`). Consumer-visible
   change.
2. The **archetype** opts in explicitly: generated `application.yml` sets
   `management.endpoints.web.exposure.include: health,info,prometheus,platform`, because the generated
   README/AGENTS/CLAUDE.md, the golden path (`golden-path.sh:305-313`, anonymous 401 / authenticated 200
   in prod) and the smoke matrix read `/actuator/platform`. Access stays governed by security (prod: not
   in `permit-paths`). `metrics` is not opted in; book chapters that `curl /actuator/metrics/…` get a
   note on how to enable it.
3. **Interim posture until Phase 7 (recorded, not final):** with the security fixture, `/actuator/health/**`
   and `/actuator/info` are public; `/actuator/prometheus` requires a token (not in `permitPaths`).
   Phase 2 tests the scrape **with a token from the harness JWKS issuer**. A separate management port
   (`management.server.port`) or a permit rule for scraping is **documented only** and decided in
   Phase 7 (G7-02), including whether the platform security chain applies on a child management context.

### R8 Overrides, kill switches, descriptors (§7.1–7.3, GX-04)
1. Every platform bean in logging/observability has a tested override: by type for single beans
   (`PlatformEndpoint`, `CapabilityGaugeRegistrar`, new filter/guard beans), by name for one-of-many
   types (`MeterRegistryCustomizer`, `CapabilityDescriptor`, `MeterFilter`) — D97/D98.
2. `observabilityCapabilityDescriptor` gets its missing `@ConditionalOnMissingBean(name=…)` guard (GX-04;
   `loggingCapabilityDescriptor` is already guarded).
3. Kill switches `dc.platform.logging.enabled`, `dc.platform.observability.enabled` (+ nested toggles):
   tier `DEFAULT_ON`; `enabled=false` restores Boot defaults (tested).

### R9 Shutdown
1. SIGTERM: the last log lines (`Graceful shutdown complete`, context close) reach stdout; nothing is
   lost from an async appender (none is used — synchronous `ConsoleAppender`; asserted).

### R10 Consumer-visible changes (CHANGELOG + migration notes)
R6.1 readiness no longer includes `db`/`rabbit`/`redis` (new `dependencies` group); R7.1 `metrics` and
`platform` not exposed by the chassis default; R3 masked values in logs; R2.1 strict MDC mode (opt-in).

## 2. Exclusions (and where they are handled)

| Item | Where |
|---|---|
| Actuator authentication / management port final decision | Phase 7 (G7-02) |
| DB, Rabbit, Redis health contributors themselves | Phases 3, 5 (only group membership here) |
| Outbox/messaging trace propagation | Phases 4, 5 |
| REST-client trace propagation (done in Phase 1), client metrics cardinality beyond the guard | Phase 6 |
| OpenShift probes against real replicas | Phase 8 step 7 |
| Audit logging module | outside the initiative |

## 3. Applicable §7 contracts

| § | Contract | Phase 2 position |
|---|---|---|
| 7.1 | Real override | R8.1 |
| 7.2 | Config-default snapshot | logging, observability (incl. EPP defaults) |
| 7.3 | Kill-switch tier | both `DEFAULT_ON`, R8.3 |
| 7.4 | Outage posture | collector down/slow → fail-open for requests (R4.4); Prometheus is pull — N/A |
| 7.5 | Health semantics | R6 |
| 7.6 | Context propagation | R2.2 (MDC), trace across inbound + outbound test call |
| 7.7 | Error surface | unchanged (Phase 1); actuator 401/404 bodies checked |
| 7.8 | Redaction | R3, R2.1 |
| 7.9 | Observability | R5.2, R4.1 |
| 7.10 | Graceful shutdown | R9 |
| 7.11 | Startup under adversity | startup with OTLP on and collector down: no hang, time recorded |

## 4. Acceptance criteria

| ID | Criterion | Level |
|---|---|---|
| AC1 | JSON line shape: required fields present; request lines carry `traceId`/`spanId` equal to the response ProblemDetail/`traceparent` trace | SLICE + CONSUMER |
| AC2 | Log injection: CR/LF/quote/forged-JSON input yields exactly one line, one `message` field (JSON); console mode one line | UNIT + CONSUMER |
| AC3 | Platform code emits only `traceId`/`spanId` to MDC (source scan); a consumer MDC key passes through (masked) by default; with `dc.platform.logging.mdc.include` set, unlisted keys are absent and listed ones present | UNIT + SLICE + CONSUMER |
| AC4 | 200 parallel requests × 3 rounds: every log line's `traceId` belongs to its request; MDC empty afterwards | SLICE + CONSUMER |
| AC5 | Redaction: JWT, `Bearer`, `Basic`, `password=`, `Authorization` key, Emirates ID, Luhn PAN absent from all log output (message, MDC, args, exception, stack trace); `****` present. Negative: epoch millis, numeric order ID, trace/span ID, UUID **not** masked | UNIT + CONSUMER |
| AC6 | `LogSanitizer` bean applied to MDC/structured args (or deprecated with metadata — Gate B decision) | SLICE |
| AC7 | `/actuator/prometheus` (with a valid token; 401 without) has common tags; no tag value matches a trace/span ID, UUID or JWT | SLICE + CONSUMER |
| AC8 | 1 000 REST-client calls to literal URIs → ≤ 100 `http.client.requests` series and one WARN (fails if Boot's cap is disabled); server unmatched paths collapse to `NOT_FOUND` | SLICE + CONSUMER |
| AC9 | Exposure (chassis default, SLICE): `health`, `info`, `prometheus` exposed; `metrics`, `platform`, `env`, `beans`, `heapdump` not. Consumer (security fixture): unexposed endpoints → 401 without a token, **404 with a valid token**; archetype app: `/actuator/platform` 401 anonymous in prod, 200 with a token | SLICE + CONSUMER |
| AC10 | Health: DB stopped → readiness UP, liveness UP, `dependencies` DOWN; DB back → `dependencies` UP | CONSUMER |
| AC11 | OTLP on + real collector: spans and metrics received with `service.name`; inbound trace ID and an outbound test call's trace ID match | CONSUMER |
| AC12 | OTLP on + collector stopped/slow: request latency/status unaffected (p99 within budget), no unbounded memory, bounded warning logs; startup does not hang | CONSUMER |
| AC13 | Override, kill-switch and config-default snapshot tests for every logging/observability bean and EPP default; descriptor guards | SLICE |
| AC14 | SIGTERM: final shutdown log lines present on stdout; exit 143 | CONSUMER |
| AC15 | Downstream `-amd test-compile` green; golden path 8/8 | build |
| AC16 | Sampling posture documented with collector tail-sampling snippet; Boot default verified; marked OPEN (deployment) | docs + UNIT |

P0: R1, R2, R3.1–3.2, R5.2, R6, R7.1, R8. P1: R3.3 (if deprecated instead), R4.3–4.4, R5.1 tags check,
R9. Deferred: R7.2 final decision (Phase 7).

## 5. Surface inventory (callers of what R2, R6, R7 change)

Grep at `a33168d` (`actuator/(metrics|platform|prometheus|health)`, `health/(readiness|liveness)`,
`include-mdc`, `MDC.put`; excluding `specs/**`, `docs/production-readiness/**`, `target/`):

| Surface | Callers | Phase 2 action |
|---|---|---|
| `/actuator/platform` | `tooling/scripts/golden-path.sh:305-313`, `tooling/scripts/smoke-matrix.sh:66`, `tooling/smoke/expectations.tsv`, archetype `application.yml` (permit list), `README.md`, `AGENTS.md`, `CLAUDE.md`; `examples/example-golden-path` (`application.yml` sets its own exposure; `PlatformSurfaceTest`); `PlatformEndpointWebTest`; messaging starter POM descriptions; docs/book ch. 5, 7, 10–14; `docs/llms-full.txt` | archetype opt-in (R7.2); module test sets exposure; docs |
| `/actuator/metrics/…` | docs/book ch. 7, 8, 9, 11, 13 (`curl` examples); `docs/llms-full.txt` | doc note: enable `metrics` exposure explicitly |
| `/actuator/prometheus` | CHANGELOG history, book ch. 5, `llms-full.txt` | none (stays exposed) |
| `/actuator/health/readiness`, `/liveness` | `tooling/hardening/lib/harness.sh:170` (`hh_wait_ready`), `phase-00-smoke.sh`, `golden-path.sh:250`, archetype `SecurityIntegrationTest`, `README.md`, `PlatformEndpointWebTest`, book ch. 8 ("db is a member", becomes wrong) | probes already use `readiness`; fix book ch. 8 |
| `/actuator/health` (top level) | `golden-path.sh:272` (exit wait), `smoke-matrix.sh:43`, archetype `SecurityIntegrationTest` | aggregate still includes `db`; no probe uses it (recorded) |
| `include-mdc` | `logback-platform.xml`, `LoggingProperties`, `LoggingAutoConfigurationTest`, `property-cheatsheet.md`, `llms-full.txt` (still says "correlationId") | kept; fix the stale "correlationId" wording |
| `MDC.put` (main code) | `core/platform-core-api/…/RequestContext.java:156,175` only | R2.1 source-scan test |

## 6. Environment prerequisites

JDK 25, Maven 3.9, Docker (consumer harness): `eclipse-temurin:25-jre`, PostgreSQL (DB fixture, image as
pinned by the harness), `otel/opentelemetry-collector-contrib` (pinned digest at Gate F), Toxiproxy for
the slow-collector state.

## 9. Decisions to record (decision log, at Gate B)

| ID | Decision |
|---|---|
| D101 | Platform emits only `traceId`/`spanId` to MDC; consumer MDC passes through masked; strict allow-list opt-in (`dc.platform.logging.mdc.include`) |
| D105 | Chassis exposure `health,info,prometheus`; archetype opts in `platform`; Prometheus scrape authenticated until Phase 7 |
| D106 | URI tag cardinality: rely on Boot's `max-uri-tags` (100) for server and client meters; no platform `uri` filter |
| D102 | Default redaction set: credential patterns + key names + Emirates ID + Luhn PAN; extendable |
| D103 | Sampling: head-based Boot default; errored-request retention via collector tail sampling (OPEN, deployment) |
| D104 | Readiness pod-local; shared dependencies in health group `dependencies` (implements D6) |


## Owner decisions

- Console log format (local profile) keeps Boot's pattern, unmasked and without CR/LF replacement (accepted).
- D102 default PII set extended with e-mail addresses and phone numbers (owner request).



## Round-1 findings and the implementer's dispositions

| # | Finding | Severity | Claude | Re-evaluation | Disposition |
|---|---|---|---|---|---|
| F1 | Quoted secrets (`password="a b"`, `'x'`) and later cookie pairs only partly masked | High | Agree | accept | FIXED — quoted values (with escapes) masked whole, quotes kept; `Cookie:`/`Set-Cookie:` masked to end of line |
| F2 | Length floors let `Basic YTpi`, `Bearer abc` and a JWT with payload `e30` through | High | Partly agree (proposed a Bearer prose heuristic) | **reject** the heuristic (RFC 6750 allows letters-only tokens); Basic and JWT changes sound | FIXED — Claude conceded: everything after `Bearer` is masked (prose too); `Basic` masked when it decodes to `user:password` or is ≥ 16 characters; JWT header `e[wy]` + structural minimum, payload ≥ 2, signature optional |
| F3 | Non-string values under credential names unmasked; numeric PANs only for `Long`/`BigInteger` | High | Agree | maintain concern: `BigDecimal("…​.00")` | FIXED — credential name (field or any ancestor) wins for every type; `Integer`/`Long`/`BigInteger`/integral `BigDecimal` checked for PANs. The `….00` case was already covered by `stripTrailingZeros()`; now pinned by a test |
| F4 | PAN pattern cuts hex trace IDs; a following number hides a card | High | Agree | maintain concern: trailing hyphen; layouts narrower than the docs said | FIXED — word boundaries on numeric patterns (trailing hyphen is a boundary); contiguous 13–19 digits or the 4-4-4-4(+1..3) / 4-6-5 layouts with a consistent separator; docs now name the layouts |
| F5 | Sanitized structured value survives in the formatted `message` | High | Agree | accept | FIXED — `SanitizedMessageView` re-renders the message from sanitized `Kv`/`kv(...)` arguments (event-local view; no mutation) |
| F6 | E-mail regex quadratic on long input | High | Agree | accept | FIXED — token-start anchored and bounded local part/domain, `@` fast path; 200 000-character inputs under 750 ms (test) |
| F7 | OTLP switch exports no traces: starter lacks `opentelemetry-exporter-otlp` | High | Agree (pre-existing) | accept | FIXED — exporter added to `platform-starter-observability`; `OtlpExportSwitchTest` (exporter bean only with the switch on). Collector receipt: Gate F (AC11) |

### Concerns raised in the re-evaluation

| Concern | Disposition |
|---|---|
| A field merely named `traceId`/`spanId` would bypass masking (`kv("traceId", "password=…")`) | Agree — FIXED: the exemption applies only when the value is a 32- or 16-character lowercase hex ID; credential ancestors still win |
| Sanitizers would run twice after the message view | Disagree, with a test: the message rendering and the structured field each apply the sanitizers once to the **raw** value; `aNonIdempotentSanitizerRunsOncePerOutput` (appending sanitizer → `x!` in both, never `x!!`) |
| JWT headers do not always start `eyJ` (RFC 7515 permits whitespace) | Agree — FIXED: `e[wy]` (`{"` / `{ ` → `ey`, `{\n` → `ew`) with a 10-character header minimum (the smallest header `{"alg":"x"}` is 15); `ewogICJhbGciOiAibm9uZSIKfQ.e30.sig` masked, `eyes.of.the storm` not |

## Acceptance criteria after round 1

| AC | Status |
|---|---|
| AC1, AC6, AC15, AC16 | accepted (AC16: `OtlpExportSwitchTest.headSamplingStaysBootsDefault` pins `0.1`) |
| AC5 | slice gaps closed by F1–F4; consumer redaction proof at Gate F |
| AC8 | slice: single WARN asserted, unmatched paths never become uri values; 400 calls per scope amendment A2; consumer at Gate F |
| AC13 | `ObservabilityProperties` and logging EPP snapshots added; running-app kill switches: observability (`PlatformEndpointWebTest$KillSwitch`: Boot's exposure, no platform groups) and logging (`JsonLogOutputTest.killSwitchRestoresBootsOwnLogging`) |
| AC14 | slice: `theOnlyRootAppenderIsASynchronousConsoleAppenderWithThePlatformEncoder`; shutdown lines at Gate F |
| AC2, AC3, AC4, AC7, AC9, AC10, AC11, AC12 | Gate F (consumer): security fixture (401/404 matrix, token scrape), real DB stop/start and recovery, real collector receipt, collector down/slow budgets, hostile exception messages, every log line correlated to its request |


## Diff: phase start a33168d .. reviewed commit 0ffc3e1 (docs/production-readiness excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index d690b84..ba13216 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -38,7 +38,24 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   Boot to every dispatch type (FORWARD and INCLUDE too); `OncePerRequestFilter` skips nested dispatches
   within one dispatch, while the ASYNC and ERROR dispatches run it again, as before.
 
+### Breaking (capability hardening, phase 2 — logging + observability)
+- **Readiness is pod-local.** `/actuator/health/readiness` no longer includes `db`, `rabbit` or
+  `redis`; they move to the new `/actuator/health/dependencies` group. Point dashboards and alerts at
+  `dependencies`; keep probes on `readiness`/`liveness` (D104).
+- **Minimal actuator exposure.** The platform default is `health,info,prometheus`; `metrics` and
+  `platform` are no longer exposed unless added to `management.endpoints.web.exposure.include`.
+  Generated services opt in `platform` in their `application.yml` (D105).
+- **JSON logs are masked.** Credentials, credential-named fields, Emirates IDs, card numbers, e-mail
+  addresses and phone numbers become `****` (D102). Turn off with `dc.platform.logging.redaction.enabled=false`.
+- `LogSanitizer` beans are now applied to MDC values and structured arguments, as documented.
+- The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
+  previously exported metrics but never traces.
+- `LoggingProperties` gains `mdc` and `redaction` components (canonical constructor changed).
+
 ### Added
+- Logging: `dc.platform.logging.mdc.include` (strict MDC mode), `dc.platform.logging.redaction.enabled`,
+  `dc.platform.logging.redaction.patterns`.
+- Observability: `dependencies` health group; docs for collector tail sampling of errored requests.
 - Errors: `PlatformErrorController` — the servlet error page (`sendError`, exceptions escaping the
   filter chain) renders the platform problem body; replaces Boot's `BasicErrorController`.
 - Core starter brings the Micrometer Tracing OTel bridge + Boot tracing auto-configuration (no
diff --git a/docs/book/chapters/08-data.md b/docs/book/chapters/08-data.md
index 6f14f71..385231c 100644
--- a/docs/book/chapters/08-data.md
+++ b/docs/book/chapters/08-data.md
@@ -904,9 +904,11 @@ PersistenceConventions.CODE_LENGTH;      // 32 · NAME_LENGTH 255 · MONEY_LENGT
 **Diagnose it**
 
 ```bash
+# metrics and env are not exposed by default: add them locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,metrics,env
 curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq
 curl -s localhost:8080/actuator/env/spring.jpa.open-in-view | jq
-curl -s localhost:8080/actuator/health/readiness | jq          # db is a member
+curl -s localhost:8080/actuator/health/dependencies | jq       # db lives here, not in readiness
 # logging.level.org.hibernate.SQL=DEBUG   to count queries
 ```
 
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index 73bfe14..b09ab23 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -661,3 +661,32 @@ One line of context per decision; details live in the commit bodies referenced.
   `dc.platform.core.correlation.*` and `dc.platform.restclient.propagate-correlation` keys are
   deprecated at level `error` with a reason only: the correlation ID is now the W3C trace ID and has no
   successor property, so naming one would mislead.
+- **D101 — the platform's own MDC emission is an allow-list; consumer MDC passes through (hardening
+  phase 2).** Platform code writes only `traceId` and `spanId` (source-scan test). Consumer MDC keys keep
+  reaching JSON logs, masked like every other value; dropping them by default would silently break
+  services. Strict mode is opt-in: `dc.platform.logging.mdc.include` (non-empty: only `traceId`,
+  `spanId` and the listed keys).
+- **D102 — default log redaction set.** JWTs, `Bearer`/`Basic` credentials, values after credential keys
+  (`password`, `secret`, `token`, `authorization`, `cookie`, `api-key`, …), whole values of
+  credential-named MDC entries and structured arguments, Emirates ID, Luhn-valid 13–19 digit card
+  numbers starting 2–6 (the issuer digit keeps epoch millis out), e-mail addresses and phone numbers
+  (international with `+`/`00`, UAE mobiles in local form; owner request 2026-10-26) become `****`. Extendable with
+  `dc.platform.logging.redaction.patterns`; `LogSanitizer` beans run first, the platform patterns last.
+  Implemented as a logstash `ValueMasker`, so it covers message, MDC, arguments and stack traces alike.
+- **D107 — console log format stays Boot's (hardening phase 2, A1, owner accepted 2026-10-26).** The
+  `console` format (the `local` profile) is human-only: it is not masked and a CR/LF in a message can
+  split a line. Overriding Boot's console pattern would fork its styling; JSON, used in every deployed
+  environment, is masked and injection-safe.
+- **D103 — sampling posture.** Head sampling stays Boot's (`management.tracing.sampling.probability`,
+  default `0.1`). Keeping every errored request's trace is delivered by collector tail sampling
+  (documented snippet); the platform position is OPEN as a deployment responsibility, as plan §9 Phase 2
+  allows. Trace IDs in logs and problem bodies are independent of sampling.
+- **D104 — readiness is pod-local (implements D6).** `readiness` = `readinessState`, `liveness` =
+  `livenessState`; `db`, `rabbit`, `redis` move to a `dependencies` group for dashboards.
+- **D105 — minimal actuator exposure.** Chassis default `health,info,prometheus`; `metrics` and
+  `platform` are opt-ins. The archetype opts in `platform` because the generated docs, the golden path
+  and the smoke matrix read it; access stays governed by security. Prometheus scraping is authenticated
+  until the security phase decides between token, management port and permit rule.
+- **D106 — uri tag cardinality is Boot's.** Boot 4.1 caps `uri` values for server and client HTTP
+  meters (`management.metrics.web.{server,client}.max-uri-tags`, 100, deny + log once). The platform adds
+  no `uri` filter of its own and keeps a test that fails if the cap is lifted.
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index 2de066b..66d941f 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -1,7 +1,8 @@
 # Logging
 
-Structured JSON logs on stdout with service identity and correlation, configured before the
-application context exists — and readable console output when a human is watching.
+Structured JSON logs on stdout with service identity and correlation, credentials and common PII
+masked, configured before the application context exists — and readable console output when a human
+is watching.
 
 ## What you get
 
@@ -11,10 +12,39 @@ application context exists — and readable console output when a human is watch
 - **Console for humans** — activate the `local` profile and output falls back to Boot's
   human-readable console format (explicit `dc.platform.logging.format` always wins).
 - **Correlation built in** — the core filter publishes the W3C `traceId` (and `spanId`) to the MDC; the encoder
-  lifts MDC entries into JSON fields, so log lines correlate with problem responses for free.
+  lifts MDC entries into JSON fields, so log lines correlate with problem responses for free. These two
+  are the only MDC keys platform code writes.
+- **Redaction** — in every string the JSON encoder writes (message, MDC values, structured arguments,
+  exception message, stack trace) the platform replaces with `****`:
+  - JWTs; whatever follows `Bearer` (so prose such as "Bearer token missing" reads
+    "Bearer **** missing"); `Basic` credentials that decode to `user:password`;
+  - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
+    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`) in `key=value`,
+    `key: value`, `"key":"value"` and `key='value'` text — quoted values whole, quotes kept;
+  - everything after `Cookie:` / `Set-Cookie:` up to the end of the line (all pairs);
+  - the whole value — of any type, nested values included — of an MDC entry or structured argument
+    whose **name** is one of those keys or `cookie` / `set-cookie`;
+  - Emirates ID numbers (`784-YYYY-NNNNNNN-C`, dashes optional) and Luhn-valid card numbers starting
+    2–6: 13–19 contiguous digits, or the printed layouts `4111 1111 1111 1111` (optionally `+1..3`
+    digits) and `3782 822463 10005`, with one consistent space or dash separator — in text and when
+    logged as a number;
+  - e-mail addresses;
+  - phone numbers: international with a `+` or `00` prefix (`+971 50 123 4567`, `00971501234567`,
+    `+44 20 7946 0958`) and UAE mobiles in local form (`050 123 4567`, `0501234567`).
+
+  Timestamps, epoch millis, trace/span IDs (also hex IDs with digit runs inside), UUIDs, IP addresses,
+  version strings, `Object@1a2b3c` identity strings and ordinary numeric IDs are left alone. The
+  `traceId`/`spanId` fields are never masked while they hold W3C hex IDs. Masking is linear in the input
+  length. Residual risks: a numeric ID that happens to
+  be a Luhn-valid 13–19 digit number starting 2–6, or a 10-digit ID starting `050`–`058`, is masked;
+  UAE landlines written without a prefix (`04 123 4567`) are **not** masked — add a pattern if you log
+  them.
+- **No forged lines** — JSON string escaping keeps CR/LF, quotes and embedded JSON inside one field, so
+  user input cannot add a line or a field.
 - **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
-- **`LogSanitizer` SPI-lite** — contribute beans to scrub sensitive values; collected by the
-  log-enrichment components of later phases.
+- **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` to MDC values and keyed arguments
+  (`Kv.of`, Logstash `kv(...)`) — both the structured field and the argument's text in `message` — but not
+  to the platform's own fields, before the platform masks run.
 
 ## Starter coordinates
 
@@ -33,7 +63,7 @@ through a lowest-precedence property source named `platform-logging-defaults` (v
 `/actuator/env`). `service` defaults to `spring.application.name`, then `application`.
 
 ```json
-{"@timestamp":"2026-01-01T00:00:00.000Z","message":"order accepted orderId=42",
+{"@timestamp":"2026-01-01T00:00:00.000Z","message":"login for alice with password=****",
  "logger":"ae.gov.dc.orders.OrderService","level":"INFO","service":"orders",
  "traceId":"0123456789abcdef0123456789abcdef","spanId":"b7ad6b7169203331"}
 ```
@@ -46,33 +76,56 @@ through a lowest-precedence property source named `platform-logging-defaults` (v
 | `dc.platform.logging.format` | `json` | `json` or `console`; unset + `local` profile ⇒ console. |
 | `dc.platform.logging.include-mdc` | `true` | Copy MDC entries into JSON fields. |
 | `dc.platform.logging.service-name` | `${spring.application.name}` | Service stamped on every line. |
+| `dc.platform.logging.mdc.include` | empty | Strict mode: when set, only `traceId`, `spanId` and these MDC keys are written. |
+| `dc.platform.logging.redaction.enabled` | `true` | Mask credentials and PII in JSON output. |
+| `dc.platform.logging.redaction.patterns` | empty | Extra regular expressions whose matches are masked. |
 
 (Hand-written until the generated reference lands in phase 14.)
 
 ## Customize
 
 - `dc.platform.logging.service-name=customs-orders` overrides the stamped service identity.
+- `dc.platform.logging.mdc.include=tenant,channel` keeps only those MDC keys (plus `traceId`/`spanId`)
+  — use it when other libraries put noisy or sensitive entries in the MDC.
+- `dc.platform.logging.redaction.patterns[0]=CASE-\d{6}` masks your own identifiers too.
+- A `LogSanitizer` bean scrubs by key:
+
+  ```java
+  @Bean
+  LogSanitizer customerNameSanitizer() {
+      return (key, value) -> "customerName".equals(key) ? "C***" : value;
+  }
+  ```
 - `dc.platform.logging.include-mdc=false` drops MDC fields (correlation included — rarely wise).
-- Contribute `LogSanitizer` beans for value scrubbing (applied by later-phase enrichment).
 
 ## Replace / Disable
 
-- Set `logging.config` to your own file — any user-set value outranks the platform default.
-- `dc.platform.logging.format=console` keeps Boot's default logging everywhere.
+- Set `logging.config` to your own file — any user-set value outranks the platform default. Your file
+  then decides about masking: reuse
+  `ae.gov.dubaicustoms.platform.logging.autoconfigure.PlatformJsonEncoder` or accept that
+  nothing is masked.
+- `dc.platform.logging.format=console` keeps Boot's default logging everywhere (no masking).
+- `dc.platform.logging.redaction.enabled=false` writes values unmasked (also removes the
+  `LogSanitizerRegistry` bean that hands your `LogSanitizer` beans to the encoder).
 - `dc.platform.logging.enabled=false` switches the capability off wholesale.
 
 ## Error codes
 
-None — logging fails soft by design; a broken logging setup must never take the service down.
+None — logging fails soft by design; a broken logging setup must never take the service down. A
+`LogSanitizer` that throws is skipped for that value; the platform masks still apply.
 
 ## Testing
 
 `OutputCaptureExtension` + a real `SpringApplicationBuilder(...).run()` exercises the whole
 chain (EPP → logback → encoder); assert JSON keys on `output.getOut()`. Logback state is
 JVM-global — `((LoggerContext) LoggerFactory.getILoggerFactory()).reset()` after such tests.
+`LogSanitizer` beans are handed to the encoder once the context has started; with several contexts in
+one JVM the most recently started one wins.
 
 ## Local dev notes
 
-Run with `--spring.profiles.active=local` for readable console lines. If production output is
-not JSON: check the banner line `logging[ACTIVE] (json|console)` — it reports the effective
-format decision, and `/actuator/env` shows whether `platform-logging-defaults` was outranked.
+Run with `--spring.profiles.active=local` for readable console lines. Console output is for humans and
+is **not** masked, and a CR/LF in a message can split a console line — never ship console format to log
+aggregation. If production output is not JSON: check the banner line `logging[ACTIVE] (json|console)` —
+it reports the effective format decision, and `/actuator/env` shows whether `platform-logging-defaults`
+was outranked.
diff --git a/docs/modules/observability.md b/docs/modules/observability.md
index 14aacdd..709ff18 100644
--- a/docs/modules/observability.md
+++ b/docs/modules/observability.md
@@ -1,25 +1,30 @@
 # Observability
 
-Metrics with a consistent identity, health probes that work on any platform, correlation that
-propagates without exploding your time-series storage, and one endpoint that tells you which
-platform capabilities a running service actually has.
+Metrics with a consistent identity, health probes that answer "can this pod serve" without turning a
+shared outage into a fleet outage, correlation that propagates without exploding your time-series
+storage, and an opt-in endpoint that tells you which platform capabilities a running service has.
 
 ## What you get
 
 - **Common meter tags** — every meter carries `service` (= `spring.application.name`),
   `env` (= first active profile) and `platform.version` (from the platform jar manifest), so
   dashboards slice uniformly across the fleet.
-- **Health groups out of the box** — `/actuator/health/liveness` and
-  `/actuator/health/readiness` exist everywhere (not only on Kubernetes); readiness additionally
-  reflects `db`, `rabbit` and `redis` indicators **when present**.
-- **`/actuator/platform`** — the `CapabilityDescriptor` report as JSON: which platform
-  capabilities are ACTIVE in this running service, with provider detail.
-- **Prometheus locally, OTLP when you have a collector** — the starter ships both registries;
-  OTLP export is OFF by default because laptops have no collector.
-- **Correlation via the trace, never metric tags** — the correlation id is the W3C trace ID,
-  high-cardinality (one value per request); it propagates downstream in `traceparent` (no custom
-  baggage header any more) and
-  is never added as a metric tag or observation key-value.
+- **Pod-local probes** — `/actuator/health/liveness` (`livenessState`) and
+  `/actuator/health/readiness` (`readinessState`) exist everywhere, not only on Kubernetes. Neither
+  checks an external system, so a database outage does not mark every replica unready at once.
+- **A `dependencies` group** — `/actuator/health/dependencies` reports `db`, `rabbit` and `redis`
+  **when present**, for dashboards and alerts.
+- **Minimal exposure** — over http only `health`, `info` and `prometheus` are exposed. `metrics`,
+  `platform` and everything else are an explicit opt-in.
+- **`/actuator/platform` (opt-in)** — the `CapabilityDescriptor` report as JSON: which platform
+  capabilities are ACTIVE in this running service, with provider detail. Generated services expose it.
+- **Prometheus locally, OTLP when you have a collector** — the starter ships both metric registries
+  and the OTLP span exporter; OTLP export is OFF by default because laptops have no collector.
+- **Bounded metric tags** — the trace ID never becomes a metric tag or observation key-value, and
+  Boot's `uri` tag cap (`management.metrics.web.server.max-uri-tags` /
+  `management.metrics.web.client.max-uri-tags`, default 100) stops a client that builds URIs by string
+  concatenation (`"/orders/" + id`) from creating one series per ID. Prefer templates
+  (`uri("/orders/{id}", id)`) anyway.
 
 ## Starter coordinates
 
@@ -37,19 +42,22 @@ property source named `platform-observability-defaults` (visible in `/actuator/e
 
 | Contributed key | Value |
 |---|---|
-| `management.endpoints.web.exposure.include` | `health,info,platform,metrics,prometheus` |
+| `management.endpoints.web.exposure.include` | `health,info,prometheus` |
 | `management.endpoint.health.probes.enabled` | `true` |
 | `management.endpoint.health.group.liveness.include` | `livenessState` |
-| `management.endpoint.health.group.readiness.include` | `readinessState,db,rabbit,redis` |
-| `management.endpoint.health.validate-group-membership` | `false` (the readiness list names optional members) |
+| `management.endpoint.health.group.readiness.include` | `readinessState` |
+| `management.endpoint.health.group.dependencies.include` | `db,rabbit,redis` |
+| `management.endpoint.health.validate-group-membership` | `false` (the dependencies list names optional members) |
 | `management.tracing.baggage.remote-fields` | not set (was `X-Correlation-Id`; removed in the hardening phase 1) |
 | `management.otlp.metrics.export.enabled` | `false` (only while `otlp.enabled=false`) |
 | `management.tracing.export.otlp.enabled` | `false` (only while `otlp.enabled=false`) |
 
-Any user-set value outranks every one of these.
+Any user-set value outranks every one of these. The top-level `/actuator/health` still aggregates every
+contributor (the database included) — point probes at `readiness` and `liveness`, never at the top level.
 
-> **Exposure note:** `platform` is exposed over http by default. It reports capability names and
-> providers — no secrets — and real endpoint security arrives with the phase-6 security baseline.
+> **Access:** with the security starter, `/actuator/health/**` and `/actuator/info` are open and
+> `/actuator/prometheus` needs a token. How Prometheus scrapes in OpenShift (token, separate management
+> port, or a permit rule) is decided with the security hardening; until then, scrape with a token.
 
 ## Properties
 
@@ -58,8 +66,8 @@ Any user-set value outranks every one of these.
 | `dc.platform.observability.enabled` | `true` | Kill switch for the whole capability. |
 | `dc.platform.observability.common-tags.enabled` | `true` | Stamp `service`/`env`/`platform.version` on every meter. |
 | `dc.platform.observability.otlp.enabled` | `false` | Enable OTLP export (metrics + traces). |
-| `dc.platform.observability.health.groups.enabled` | `true` | Contribute liveness/readiness group defaults. |
-| `dc.platform.observability.platform-endpoint.enabled` | `true` | Serve `/actuator/platform`. |
+| `dc.platform.observability.health.groups.enabled` | `true` | Contribute liveness/readiness/dependencies group defaults. |
+| `dc.platform.observability.platform-endpoint.enabled` | `true` | Serve `/actuator/platform` when it is exposed. |
 
 (Hand-written until the generated reference lands in phase 14.)
 
@@ -75,19 +83,50 @@ management:
     metrics:
       export:
         url: http://otel-collector:4318/v1/metrics
+  opentelemetry:
     tracing:
-      endpoint: http://otel-collector:4318/v1/traces
+      export:
+        otlp:
+          endpoint: http://otel-collector:4318/v1/traces
+```
+
+(`management.otlp.tracing.endpoint` was removed in Boot 4 — it no longer has any effect.)
+
+## Sampling
+
+Tracing is head-sampled: `management.tracing.sampling.probability`, Boot default `0.1`. The trace ID in
+logs and error bodies does not depend on sampling — an unsampled request still has one. A head sampler
+cannot know a request will fail, so **keeping every failed request's trace is the collector's job**: run
+the OpenTelemetry Collector with tail sampling and an error policy.
+
+```yaml
+# OpenTelemetry Collector (contrib) — keep all errored traces, 10 % of the rest.
+processors:
+  tail_sampling:
+    decision_wait: 10s
+    policies:
+      - name: errors
+        type: status_code
+        status_code: { status_codes: [ERROR] }
+      - name: baseline
+        type: probabilistic
+        probabilistic: { sampling_percentage: 10 }
 ```
 
+Tail sampling only sees spans the service exported, so with it in place set
+`management.tracing.sampling.probability: 1.0` in the service and let the collector decide.
+
 ## Customize
 
 - Define your own `MeterRegistryCustomizer` bean named `platformCommonTagsCustomizer` to replace
   the platform tags wholesale (any other bean name adds to them).
+- Expose more endpoints: `management.endpoints.web.exposure.include: health,info,prometheus,platform,metrics`.
 - Override any contributed `management.*` key in `application.yml` — user config always wins.
 
 ## Replace / Disable
 
 - `dc.platform.observability.platform-endpoint.enabled=false` removes `/actuator/platform`.
+- A bean named `observabilityCapabilityDescriptor` replaces the banner/report entry.
 - `management.endpoints.web.exposure.include=health` shrinks the http surface back to Boot's own.
 - `dc.platform.observability.enabled=false` switches the capability off wholesale.
 
@@ -97,10 +136,10 @@ None — observability fails soft; a broken metrics/tracing setup must never tak
 
 ## Testing
 
-The `ApplicationContextRunner` matrix covers activation/back-off; a `RANDOM_PORT` boot test
-proves the exposure + health-group defaults end to end (`/actuator/platform`,
-`/actuator/health/liveness|readiness`). The EnvironmentPostProcessor is unit-tested against a
-`MockEnvironment` — user values always win.
+The `ApplicationContextRunner` matrix covers activation/back-off; `RANDOM_PORT` boot tests prove the
+exposure and health-group defaults end to end (a simulated `db` outage leaves readiness UP and the
+`dependencies` group DOWN), the scrape content, and Boot's client `uri` cap. The
+EnvironmentPostProcessor is unit-tested against a `MockEnvironment` — user values always win.
 
 ## Local dev notes
 
diff --git a/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/DocsLinkCheckTest.java b/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/DocsLinkCheckTest.java
index 9038531..c5180ec 100644
--- a/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/DocsLinkCheckTest.java
+++ b/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/DocsLinkCheckTest.java
@@ -31,6 +31,9 @@ class DocsLinkCheckTest {
         try (Stream<Path> tree = Files.walk(docs)) {
             tree.filter(p -> p.toString().endsWith(".md"))
                     .filter(p -> !p.toString().replace('\\', '/').contains("/site/"))
+                    // Hardening review records are verbatim reviewer I/O: they quote other files, whose
+                    // relative links resolve from the quoted file, not from the record.
+                    .filter(p -> !p.toString().replace('\\', '/').matches(".*/production-readiness/.*/codex/[^/]+\\.md"))
                     .forEach(md -> checkFile(md, broken));
         }
 
diff --git a/logging/platform-logging-autoconfigure/pom.xml b/logging/platform-logging-autoconfigure/pom.xml
index 9ff1fec..3e1c352 100644
--- a/logging/platform-logging-autoconfigure/pom.xml
+++ b/logging/platform-logging-autoconfigure/pom.xml
@@ -44,6 +44,13 @@
       <artifactId>logstash-logback-encoder</artifactId>
       <optional>true</optional>
     </dependency>
+    <!-- PlatformJsonEncoder extends the logstash encoder, whose Logback types the encoder declares
+         provided; Boot's logging starter supplies Logback at run time. -->
+    <dependency>
+      <groupId>ch.qos.logback</groupId>
+      <artifactId>logback-classic</artifactId>
+      <optional>true</optional>
+    </dependency>
     <!-- Generates config metadata from the properties record at compile time. -->
     <dependency>
       <groupId>org.springframework.boot</groupId>
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfiguration.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfiguration.java
index 5ee6ddb..e7d0300 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfiguration.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfiguration.java
@@ -2,6 +2,9 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure;
 
 import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
 import ae.gov.dubaicustoms.platform.logging.Kv;
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry;
+import org.springframework.beans.factory.ObjectProvider;
 import org.springframework.boot.autoconfigure.AutoConfiguration;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@@ -12,12 +15,16 @@ import org.springframework.core.env.Environment;
 
 /*
  * Activates when: platform-logging-api on the classpath AND dc.platform.logging.enabled != false
- * Backs off when: user defines a bean named loggingCapabilityDescriptor
+ * Backs off when: user defines a bean named loggingCapabilityDescriptor (one-of-many type, D97);
+ *                 user defines a LogSanitizerRegistry bean (by type, D98)
  * Beans: loggingCapabilityDescriptor — one line in the startup capability banner reporting the
  *        effective format (the heavy lifting happened pre-context in
- *        PlatformLoggingEnvironmentPostProcessor; LogSanitizer beans are collected by the
- *        enrichment components of later phases)
- * Order: none — no bean interactions; the EPP already ran before the context existed.
+ *        PlatformLoggingEnvironmentPostProcessor);
+ *        logSanitizerRegistry — hands the application's LogSanitizer beans to the platform JSON
+ *        encoder once every singleton exists (Logback starts before the context, so the encoder
+ *        cannot look them up itself); only when dc.platform.logging.redaction.enabled != false
+ * Order: none — the EPP already ran before the context existed; the registry publishes after all
+ *        singletons exist, so every LogSanitizer bean is seen regardless of configuration order.
  */
 @AutoConfiguration
 @ConditionalOnClass(Kv.class)
@@ -33,4 +40,12 @@ public class LoggingAutoConfiguration {
         boolean json = PlatformLoggingEnvironmentPostProcessor.jsonFormatResolved(environment);
         return new CapabilityDescriptor("logging", "ACTIVE", json ? "json" : "console");
     }
+
+    @Bean
+    @ConditionalOnMissingBean
+    @ConditionalOnProperty(prefix = "dc.platform.logging.redaction", name = "enabled",
+                           havingValue = "true", matchIfMissing = true)
+    LogSanitizerRegistry logSanitizerRegistry(ObjectProvider<LogSanitizer> sanitizers) {
+        return new LogSanitizerRegistry(sanitizers);
+    }
 }
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java
index bf38214..de2aa87 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java
@@ -1,5 +1,6 @@
 package ae.gov.dubaicustoms.platform.logging.autoconfigure;
 
+import java.util.List;
 import org.springframework.boot.context.properties.ConfigurationProperties;
 import org.springframework.boot.context.properties.bind.DefaultValue;
 import org.springframework.validation.annotation.Validated;
@@ -18,6 +19,8 @@ import org.springframework.validation.annotation.Validated;
  * @param includeMdc copy MDC entries (traceId, spanId, …) into JSON log fields
  * @param serviceName service name stamped on every JSON log line; defaults to
  *        {@code spring.application.name}
+ * @param mdc which MDC entries reach JSON log fields
+ * @param redaction masking of credentials and PII in JSON log output
  * @since 0.1.0
  */
 @Validated
@@ -30,7 +33,39 @@ public record LoggingProperties(
         /** Copy MDC entries (traceId, spanId, ...) into JSON log fields. */
         @DefaultValue("true") boolean includeMdc,
         /** Service name stamped on every JSON line; defaults to spring.application.name. */
-        @DefaultValue("") String serviceName) {
+        @DefaultValue("") String serviceName,
+        /** Which MDC entries reach JSON log fields. */
+        @DefaultValue Mdc mdc,
+        /** Masking of credentials and PII in JSON log output. */
+        @DefaultValue Redaction redaction) {
+
+    /**
+     * MDC field selection.
+     *
+     * @param include MDC keys written besides {@code traceId} and {@code spanId}; empty (the default)
+     *        writes every MDC key, a non-empty list switches to strict mode and drops unlisted keys
+     * @since 0.1.0
+     */
+    public record Mdc(
+            /** Strict mode: MDC keys written besides traceId/spanId; empty writes every key. */
+            @DefaultValue List<String> include) {
+    }
+
+    /**
+     * Redaction of JSON log output. Credentials (JWTs, Bearer/Basic credentials, values of
+     * credential-named keys) and the default PII set (Emirates ID, Luhn-valid card numbers) are
+     * replaced by {@code ****}.
+     *
+     * @param enabled mask values (on by default)
+     * @param patterns additional regular expressions whose matches are masked
+     * @since 0.1.0
+     */
+    public record Redaction(
+            /** Mask credentials and PII in JSON log output. */
+            @DefaultValue("true") boolean enabled,
+            /** Additional regular expressions whose matches are masked. */
+            @DefaultValue List<String> patterns) {
+    }
 
     /**
      * Log output format.
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
new file mode 100644
index 0000000..f815c99
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
@@ -0,0 +1,95 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure;
+
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformValueMasker;
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.SanitizedMessageView;
+import ch.qos.logback.classic.spi.ILoggingEvent;
+import java.io.IOException;
+import java.io.OutputStream;
+import java.util.Arrays;
+import java.util.List;
+import net.logstash.logback.encoder.LogstashEncoder;
+import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
+
+/**
+ * The platform's JSON log encoder: Logstash's {@link LogstashEncoder} plus redaction of credentials
+ * and the default PII set (D102) and an opt-in strict MDC allow-list (D101). The shipped
+ * {@code logback-platform.xml} uses it; reference it from your own Logback file to keep masking when
+ * you replace {@code logging.config}:
+ * <pre>{@code
+ * <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
+ *   <encoder class="ae.gov.dubaicustoms.platform.logging.autoconfigure.PlatformJsonEncoder">
+ *     <redactionPatterns>CASE-\d{6}</redactionPatterns>
+ *   </encoder>
+ * </appender>
+ * }</pre>
+ *
+ * <p>Every {@code LogstashEncoder} option (field names, custom fields, MDC switches) is available.
+ * Not thread-safe during configuration (Logback configures it on one thread); encoding is thread-safe
+ * once started. Setters accept {@code null} as "not set".
+ *
+ * @since 0.1.0
+ */
+public class PlatformJsonEncoder extends LogstashEncoder {
+
+    /** MDC keys the platform itself writes; always included in strict mode. */
+    static final List<String> PLATFORM_MDC_KEYS = List.of("traceId", "spanId");
+
+    private String mdcInclude = "";
+    private boolean redactionEnabled = true;
+    private String redactionPatterns = "";
+
+    /**
+     * Comma-separated MDC keys to write besides {@code traceId} and {@code spanId}. Blank (the
+     * default) writes every MDC key.
+     *
+     * @param mdcInclude the keys; may be {@code null} or blank
+     */
+    public void setMdcInclude(String mdcInclude) {
+        this.mdcInclude = mdcInclude == null ? "" : mdcInclude;
+    }
+
+    /**
+     * Switches redaction on (the default) or off.
+     *
+     * @param redactionEnabled whether values are masked
+     */
+    public void setRedactionEnabled(boolean redactionEnabled) {
+        this.redactionEnabled = redactionEnabled;
+    }
+
+    /**
+     * Additional regular expressions to mask, one per line.
+     *
+     * @param redactionPatterns the patterns; may be {@code null} or blank
+     */
+    public void setRedactionPatterns(String redactionPatterns) {
+        this.redactionPatterns = redactionPatterns == null ? "" : redactionPatterns;
+    }
+
+    @Override
+    public void start() {
+        List<String> keys = Arrays.stream(mdcInclude.split(",")).map(String::strip).filter(k -> !k.isEmpty()).toList();
+        if (!keys.isEmpty()) {
+            PLATFORM_MDC_KEYS.forEach(this::addIncludeMdcKeyName);
+            keys.stream().filter(k -> !PLATFORM_MDC_KEYS.contains(k)).forEach(this::addIncludeMdcKeyName);
+        }
+        if (redactionEnabled) {
+            MaskingJsonGeneratorDecorator masking = new MaskingJsonGeneratorDecorator();
+            masking.addValueMasker(new PlatformValueMasker(PlatformValueMasker.compile(redactionPatterns)));
+            masking.start();
+            setJsonGeneratorDecorator(masking);
+        }
+        super.start();
+    }
+
+    @Override
+    public byte[] encode(ILoggingEvent event) {
+        // LogSanitizer beans also clean keyed arguments inside the formatted message (event-local view).
+        return super.encode(redactionEnabled ? SanitizedMessageView.of(event) : event);
+    }
+
+    @Override
+    public void encode(ILoggingEvent event, OutputStream outputStream) throws IOException {
+        super.encode(redactionEnabled ? SanitizedMessageView.of(event) : event, outputStream);
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java
index c03e989..6e300c0 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java
@@ -2,9 +2,12 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure;
 
 import java.util.Arrays;
 import java.util.LinkedHashMap;
+import java.util.List;
 import java.util.Locale;
 import java.util.Map;
 import org.springframework.boot.SpringApplication;
+import org.springframework.boot.context.properties.bind.Bindable;
+import org.springframework.boot.context.properties.bind.Binder;
 import org.springframework.boot.env.EnvironmentPostProcessor;
 import org.springframework.core.Ordered;
 import org.springframework.core.env.ConfigurableEnvironment;
@@ -33,6 +36,12 @@ public class PlatformLoggingEnvironmentPostProcessor implements EnvironmentPostP
     static final String CONFIG_LOCATION =
             "classpath:ae/gov/dubaicustoms/platform/logging/logback-platform.xml";
 
+    /** Flattened {@code dc.platform.logging.mdc.include}, read by logback-platform.xml. */
+    static final String MDC_INCLUDE_RESOLVED = "dc.platform.logging.internal.mdc-include";
+
+    /** Flattened {@code dc.platform.logging.redaction.patterns}, read by logback-platform.xml. */
+    static final String REDACTION_PATTERNS_RESOLVED = "dc.platform.logging.internal.redaction-patterns";
+
     @Override
     public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
         if (!environment.getProperty("dc.platform.logging.enabled", Boolean.class, true)) {
@@ -48,6 +57,13 @@ public class PlatformLoggingEnvironmentPostProcessor implements EnvironmentPostP
         // explicit dc.platform.logging.service-name > spring.application.name > "application".
         defaults.put("dc.platform.logging.service-name",
                 environment.getProperty("spring.application.name", "application"));
+        // List properties flattened for springProperty, which reads one scalar key: the strict MDC
+        // keys comma-separated, the extra redaction patterns one per line (regexes may hold commas).
+        Binder binder = Binder.get(environment);
+        defaults.put(MDC_INCLUDE_RESOLVED, String.join(",",
+                binder.bind("dc.platform.logging.mdc.include", Bindable.listOf(String.class)).orElse(List.of())));
+        defaults.put(REDACTION_PATTERNS_RESOLVED, String.join("\n",
+                binder.bind("dc.platform.logging.redaction.patterns", Bindable.listOf(String.class)).orElse(List.of())));
         environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
     }
 
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/LogSanitizerRegistry.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/LogSanitizerRegistry.java
new file mode 100644
index 0000000..040edef
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/LogSanitizerRegistry.java
@@ -0,0 +1,53 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import java.util.List;
+import java.util.Objects;
+import org.springframework.beans.factory.DisposableBean;
+import org.springframework.beans.factory.ObjectProvider;
+import org.springframework.beans.factory.SmartInitializingSingleton;
+
+/**
+ * Hands the application's {@link LogSanitizer} beans to {@link PlatformValueMasker}. Logback is
+ * configured before the application context exists, so the encoder cannot look the beans up; this
+ * bean publishes them once every singleton exists and withdraws them when the context closes.
+ *
+ * <p>One registry per JVM: with several contexts in one JVM (tests), the most recently started one
+ * wins. Thread-safe (a volatile, immutable snapshot).
+ */
+public final class LogSanitizerRegistry implements SmartInitializingSingleton, DisposableBean {
+
+    private static volatile List<LogSanitizer> current = List.of();
+
+    private final ObjectProvider<LogSanitizer> sanitizers;
+    private List<LogSanitizer> published = List.of();
+
+    /**
+     * Creates the registry.
+     *
+     * @param sanitizers the application's sanitizers, applied in {@code @Order}; never {@code null}
+     */
+    public LogSanitizerRegistry(ObjectProvider<LogSanitizer> sanitizers) {
+        this.sanitizers = Objects.requireNonNull(sanitizers, "sanitizers must not be null");
+    }
+
+    @Override
+    public void afterSingletonsInstantiated() {
+        published = sanitizers.orderedStream().toList();
+        current = published;
+    }
+
+    @Override
+    public void destroy() {
+        // Withdraw only our own snapshot: a later context may have published its own since.
+        synchronized (LogSanitizerRegistry.class) {
+            if (current == published) {
+                current = List.of();
+            }
+        }
+    }
+
+    static List<LogSanitizer> current() {
+        return current;
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
new file mode 100644
index 0000000..5c584ed
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
@@ -0,0 +1,284 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import com.fasterxml.jackson.core.JsonStreamContext;
+import java.math.BigDecimal;
+import java.math.BigInteger;
+import java.nio.charset.StandardCharsets;
+import java.util.ArrayList;
+import java.util.Base64;
+import java.util.List;
+import java.util.Locale;
+import java.util.Set;
+import java.util.function.Function;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import net.logstash.logback.mask.ValueMasker;
+
+/**
+ * Masks credentials and the default PII set (Emirates ID, card numbers, e-mail addresses, phone
+ * numbers) in every value the platform JSON encoder writes (message, MDC values, structured arguments,
+ * exception text), then applies the application's {@link LogSanitizer} beans to MDC and
+ * structured-argument fields (D102).
+ *
+ * <p>Order per value: whole-value mask when the field, or any field enclosing it, is a credential key
+ * (any value type); {@code LogSanitizer} beans (non-platform fields only); pattern masks. Pattern masks
+ * run last, so a sanitizer can neither skip them nor reintroduce a credential the patterns would catch.
+ * {@code traceId}/{@code spanId} fields holding W3C hex IDs are never pattern-masked. Every pattern is
+ * anchored to token starts and bounded, so masking stays linear in the input length. Masked text
+ * becomes {@value #MASK}.
+ *
+ * <p>Stateless apart from the immutable extra patterns; thread-safe.
+ */
+public final class PlatformValueMasker implements ValueMasker {
+
+    /** Replacement for masked text; fixed length so the original length is not disclosed. */
+    public static final String MASK = "****";
+
+    // Field names written by the encoder itself; LogSanitizers apply to everything else (MDC, args).
+    private static final Set<String> PLATFORM_FIELDS = Set.of("@timestamp", "level", "logger", "message",
+            "stack_trace", "service", "traceId", "spanId", "tags");
+
+    // Encoder-written fields: never pattern-masked.
+    private static final Set<String> ENCODER_FIELDS = Set.of("@timestamp", "level", "logger");
+    // traceId/spanId are exempt only when they hold what the platform writes there: W3C hex IDs (a hex
+    // ID may contain a Luhn-valid digit run). Anything else under those names is masked as usual.
+    private static final Pattern TRACE_OR_SPAN_ID = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{16}");
+
+    // Normalized (lower case, no '-' or '_') field names whose whole value is a credential.
+    private static final Set<String> CREDENTIAL_KEYS = Set.of("password", "passwd", "pwd", "secret",
+            "clientsecret", "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
+            "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey");
+
+    // A JWT header is base64url JSON: '{' then '"' or whitespace encodes to "ey" / "ew" (RFC 7515 allows
+    // whitespace); the smallest header ({"alg":"x"}) is 15 characters. {} as payload encodes to e30;
+    // alg=none has no signature.
+    private static final Pattern JWT =
+            Pattern.compile("(?<![A-Za-z0-9_-])e[wy][A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]*");
+    private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)([A-Za-z0-9._~+/=-]+)");
+    private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)([A-Za-z0-9+/]+={0,2})");
+    private static final String QUOTED_OR_BARE =
+            "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)";
+    // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept.
+    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])(password|passwd|pwd|secret"
+            + "|client_secret|access_token|refresh_token|id_token|token|api[-_]?key|authorization)"
+            + "(\"?\\s*[:=]\\s*)" + QUOTED_OR_BARE);
+    // A cookie header carries several pairs: everything up to the end of the line (or the quote) goes.
+    private static final Pattern COOKIE = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"?\\s*[:=]\\s*)"
+            + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\r\\n\"]+)");
+    // Emirates ID: 784-YYYY-NNNNNNN-C, dashes optional.
+    private static final Pattern EMIRATES_ID = Pattern.compile("(?<![\\w-])784-?\\d{4}-?\\d{7}-?\\d(?![\\w-])");
+    // Card numbers, issuer digit 2–6 (keeps 13-digit epoch millis out), Luhn-valid: contiguous 13–19
+    // digits, or the printed layouts 4-4-4-4 (+1..3) and 4-6-5 with one consistent space/dash separator.
+    private static final Pattern PAN_CONTIGUOUS = Pattern.compile("(?<!\\w)[2-6]\\d{12,18}(?!\\w)");
+    private static final Pattern PAN_GROUPED = Pattern.compile(
+            "(?<!\\w)(?<base>[2-6]\\d{3}(?<s>[ -])\\d{4}\\k<s>\\d{4}\\k<s>\\d{4})(?<ext>\\k<s>\\d{1,3})?(?!\\w)"
+            + "|(?<!\\w)(?<amex>3[47]\\d{2}(?<a>[ -])\\d{6}\\k<a>\\d{5})(?!\\w)");
+    private static final Pattern DIGITS = Pattern.compile("[2-6]\\d{12,18}");
+    // E-mail: local part starts a token and is bounded (linear scanning); dotted domain keeps Java's
+    // Object@1a2b3c identity strings out.
+    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}"
+            + "@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,24}(?![A-Za-z0-9-])");
+    // Phone numbers: international with a + or 00 prefix (8–15 digits, single space/dash separators),
+    // and UAE mobile numbers in local form (05X XXX XXXX). Local landlines without a prefix are not
+    // matched: too close to ordinary numeric IDs.
+    private static final Pattern PHONE_INTERNATIONAL =
+            Pattern.compile("(?<![\\w+-])(?:\\+|00)[1-9](?:[ -]?\\d){7,14}(?![\\w-])");
+    private static final Pattern PHONE_UAE_MOBILE =
+            Pattern.compile("(?<![\\w-])05[02-8][ -]?\\d{3}[ -]?\\d{4}(?![\\w-])");
+
+    private final List<Pattern> extraPatterns;
+
+    /**
+     * Creates the masker.
+     *
+     * @param extraPatterns additional regular expressions whose matches are masked; never {@code null}
+     */
+    public PlatformValueMasker(List<Pattern> extraPatterns) {
+        this.extraPatterns = List.copyOf(extraPatterns);
+    }
+
+    @Override
+    public Object mask(JsonStreamContext context, Object value) {
+        if (value == null) {
+            return null;
+        }
+        if (underCredentialKey(context)) {
+            return MASK;
+        }
+        if (value instanceof Number number) {
+            // kv("card", 4111111111111111L): a card number written as a JSON number.
+            String digits = integralDigits(number);
+            return digits != null && DIGITS.matcher(digits).matches() && luhn(digits) ? MASK : null;
+        }
+        if (!(value instanceof CharSequence text)) {
+            return null;
+        }
+        String field = context.getCurrentName();
+        String original = text.toString();
+        if (field != null && (ENCODER_FIELDS.contains(field)
+                || ("traceId".equals(field) || "spanId".equals(field)) && TRACE_OR_SPAN_ID.matcher(original).matches())) {
+            return null;
+        }
+        String result = original;
+        if (field != null && !PLATFORM_FIELDS.contains(field)) {
+            result = sanitize(field, result);
+        }
+        result = maskPatterns(result);
+        return result.equals(original) ? null : result;
+    }
+
+    /** Applies the pattern masks (credentials, PII, extra patterns) to free text. */
+    String maskPatterns(String text) {
+        String result = JWT.matcher(text).replaceAll(MASK);
+        // RFC 6750 tokens may be letters only: whatever follows "Bearer" goes, prose included.
+        result = BEARER.matcher(result).replaceAll("$1$2" + MASK);
+        result = replace(result, BASIC, m -> basicCredential(m.group(3)) ? m.group(1) + m.group(2) + MASK : m.group());
+        result = replace(result, KEY_VALUE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
+        result = replace(result, COOKIE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
+        result = EMIRATES_ID.matcher(result).replaceAll(MASK);
+        result = replace(result, PAN_GROUPED, PlatformValueMasker::groupedCard);
+        result = replace(result, PAN_CONTIGUOUS, m -> luhn(m.group()) ? MASK : m.group());
+        if (result.indexOf('@') >= 0) {
+            result = EMAIL.matcher(result).replaceAll(MASK);
+        }
+        result = PHONE_INTERNATIONAL.matcher(result).replaceAll(MASK);
+        result = PHONE_UAE_MOBILE.matcher(result).replaceAll(MASK);
+        for (Pattern pattern : extraPatterns) {
+            result = pattern.matcher(result).replaceAll(MASK);
+        }
+        return result;
+    }
+
+    /**
+     * Applies the registered {@link LogSanitizer} beans to one keyed value, in order; a throwing
+     * sanitizer is skipped.
+     *
+     * @param key the field name; never {@code null}
+     * @param value the value; may be {@code null}
+     * @return the sanitized value
+     */
+    static String sanitize(String key, String value) {
+        String result = value;
+        for (LogSanitizer sanitizer : LogSanitizerRegistry.current()) {
+            try {
+                String sanitized = sanitizer.sanitize(key, result);
+                if (sanitized != null) {
+                    result = sanitized;
+                }
+            } catch (RuntimeException ignored) {
+                // A throwing sanitizer must not suppress the line (LogSanitizer contract); the pattern
+                // masks still run on the value.
+            }
+        }
+        return result;
+    }
+
+    private static boolean underCredentialKey(JsonStreamContext context) {
+        for (JsonStreamContext c = context; c != null; c = c.getParent()) {
+            String name = c.getCurrentName();
+            if (name != null && CREDENTIAL_KEYS.contains(normalize(name))) {
+                return true;
+            }
+        }
+        return false;
+    }
+
+    private static String integralDigits(Number number) {
+        if (number instanceof Long || number instanceof Integer || number instanceof BigInteger) {
+            return number.toString();
+        }
+        if (number instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
+            return decimal.toBigInteger().toString();
+        }
+        return null;
+    }
+
+    private static String maskValue(String value) {
+        char first = value.charAt(0);
+        return first == '"' || first == '\'' ? first + MASK + first : MASK;
+    }
+
+    // RFC 7617: base64 of user-id ":" password — masked whatever its length when it decodes to one.
+    private static boolean basicCredential(String token) {
+        try {
+            return new String(Base64.getDecoder().decode(token), StandardCharsets.ISO_8859_1).indexOf(':') >= 0
+                    || token.length() >= 16;
+        } catch (IllegalArgumentException notBase64) {
+            return token.length() >= 16;
+        }
+    }
+
+    private static String groupedCard(Matcher m) {
+        String amex = m.group("amex");
+        if (amex != null) {
+            return luhn(amex) ? MASK : m.group();
+        }
+        String base = m.group("base");
+        String ext = m.group("ext");
+        if (ext != null && luhn(base + ext)) {
+            return MASK;
+        }
+        if (luhn(base)) {
+            // A following short number belongs to the text, not the card ("card 4111 … 1111 12").
+            return ext == null ? MASK : MASK + ext;
+        }
+        return m.group();
+    }
+
+    private static String replace(String text, Pattern pattern, Function<Matcher, String> replacement) {
+        Matcher matcher = pattern.matcher(text);
+        if (!matcher.find()) {
+            return text;
+        }
+        StringBuilder out = new StringBuilder(text.length());
+        do {
+            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(matcher)));
+        } while (matcher.find());
+        matcher.appendTail(out);
+        return out.toString();
+    }
+
+    private static boolean luhn(String candidate) {
+        int sum = 0;
+        boolean doubled = false;
+        for (int i = candidate.length() - 1; i >= 0; i--) {
+            char c = candidate.charAt(i);
+            if (c < '0' || c > '9') {
+                continue;
+            }
+            int digit = c - '0';
+            if (doubled) {
+                digit *= 2;
+                if (digit > 9) {
+                    digit -= 9;
+                }
+            }
+            sum += digit;
+            doubled = !doubled;
+        }
+        return sum % 10 == 0;
+    }
+
+    private static String normalize(String field) {
+        return field.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
+    }
+
+    /**
+     * Compiles newline-separated extra patterns; blank lines are ignored.
+     *
+     * @param lines the patterns, one per line; may be {@code null}
+     * @return the compiled patterns
+     */
+    public static List<Pattern> compile(String lines) {
+        List<Pattern> patterns = new ArrayList<>();
+        if (lines != null) {
+            for (String line : lines.split("\n")) {
+                if (!line.isBlank()) {
+                    patterns.add(Pattern.compile(line.strip()));
+                }
+            }
+        }
+        return patterns;
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
new file mode 100644
index 0000000..828939e
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -0,0 +1,98 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.logging.Kv;
+import ch.qos.logback.classic.spi.ILoggingEvent;
+import java.lang.reflect.InvocationTargetException;
+import java.lang.reflect.Method;
+import java.lang.reflect.Proxy;
+import java.util.Objects;
+import net.logstash.logback.marker.SingleFieldAppendingMarker;
+import org.slf4j.helpers.MessageFormatter;
+
+/**
+ * Applies {@code LogSanitizer} beans to keyed message arguments ({@link Kv} and Logstash
+ * {@code kv(...)}/{@code StructuredArguments} markers) before the message is rendered, so a sanitized
+ * value does not survive in the formatted {@code message} while its structured field is clean.
+ *
+ * <p>Returns an event-local view: the logged event and its argument array are never mutated. Without
+ * registered sanitizers, or when no argument changes, the original event is returned. Thread-safe.
+ */
+public final class SanitizedMessageView {
+
+    private static final Method FIELD_VALUE = fieldValueAccessor();
+
+    private SanitizedMessageView() {
+    }
+
+    /**
+     * Returns the event to encode.
+     *
+     * @param event the logged event; never {@code null}
+     * @return {@code event}, or a view whose formatted message is rendered from sanitized arguments
+     */
+    public static ILoggingEvent of(ILoggingEvent event) {
+        Object[] arguments = event.getArgumentArray();
+        if (arguments == null || arguments.length == 0 || LogSanitizerRegistry.current().isEmpty()) {
+            return event;
+        }
+        Object[] display = arguments.clone();
+        boolean changed = false;
+        for (int i = 0; i < display.length; i++) {
+            Object sanitized = sanitized(display[i]);
+            if (sanitized != display[i]) {
+                display[i] = sanitized;
+                changed = true;
+            }
+        }
+        if (!changed) {
+            return event;
+        }
+        String message = MessageFormatter.arrayFormat(event.getMessage(), display).getMessage();
+        return (ILoggingEvent) Proxy.newProxyInstance(ILoggingEvent.class.getClassLoader(),
+                new Class<?>[] {ILoggingEvent.class}, (proxy, method, args) -> {
+                    if ("getFormattedMessage".equals(method.getName()) && method.getParameterCount() == 0) {
+                        return message;
+                    }
+                    try {
+                        return method.invoke(event, args);
+                    } catch (InvocationTargetException e) {
+                        throw e.getCause();
+                    }
+                });
+    }
+
+    private static Object sanitized(Object argument) {
+        if (argument instanceof Kv kv) {
+            String raw = String.valueOf(kv.value());
+            String clean = PlatformValueMasker.sanitize(kv.key(), raw);
+            return clean.equals(raw) ? argument : Kv.of(kv.key(), clean);
+        }
+        if (argument instanceof SingleFieldAppendingMarker marker && FIELD_VALUE != null) {
+            String raw = String.valueOf(fieldValue(marker));
+            String clean = PlatformValueMasker.sanitize(marker.getFieldName(), raw);
+            // Keeps the marker's own rendering ("key=value", "value", custom pattern) with the clean value.
+            return clean.equals(raw) ? argument : marker.toString().replace(raw, clean);
+        }
+        return argument;
+    }
+
+    private static Object fieldValue(SingleFieldAppendingMarker marker) {
+        try {
+            return FIELD_VALUE.invoke(marker);
+        } catch (ReflectiveOperationException e) {
+            return null;
+        }
+    }
+
+    // getFieldValue() is protected in logstash-logback-encoder; read it rather than re-implementing
+    // every marker type's rendering.
+    private static Method fieldValueAccessor() {
+        try {
+            Method method = SingleFieldAppendingMarker.class.getDeclaredMethod("getFieldValue");
+            method.setAccessible(true);
+            return Objects.requireNonNull(method);
+        } catch (ReflectiveOperationException | RuntimeException e) {
+            return null;
+        }
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml b/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml
index cd521d5..0564f8b 100644
--- a/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml
+++ b/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml
@@ -1,17 +1,25 @@
 <?xml version="1.0" encoding="UTF-8"?>
 <!-- Platform JSON logging: one logstash-encoded line per event on stdout, ECS-ish field names
-     (@timestamp, level, logger, message, service, traceId/spanId via MDC, stack_trace).
+     (@timestamp, level, logger, message, service, traceId/spanId via MDC, stack_trace), with
+     credentials and the default PII set masked (D102) and an opt-in strict MDC allow-list (D101).
      Activated by PlatformLoggingEnvironmentPostProcessor defaulting logging.config to this file;
-     Boot processes it, so <springProperty> works. Setting logging.config yourself replaces it. -->
+     Boot processes it, so <springProperty> works. Setting logging.config yourself replaces it,
+     redaction included. -->
 <configuration>
 
   <springProperty name="service" source="dc.platform.logging.service-name" defaultValue="application"/>
   <springProperty name="includeMdc" source="dc.platform.logging.include-mdc" defaultValue="true"/>
+  <springProperty name="mdcInclude" source="dc.platform.logging.internal.mdc-include" defaultValue=""/>
+  <springProperty name="redactionEnabled" source="dc.platform.logging.redaction.enabled" defaultValue="true"/>
+  <springProperty name="redactionPatterns" source="dc.platform.logging.internal.redaction-patterns" defaultValue=""/>
 
   <appender name="PLATFORM_JSON" class="ch.qos.logback.core.ConsoleAppender">
-    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
+    <encoder class="ae.gov.dubaicustoms.platform.logging.autoconfigure.PlatformJsonEncoder">
       <!-- traceId/spanId (and everything else the core filter publishes) ride in via MDC. -->
       <includeMdc>${includeMdc}</includeMdc>
+      <mdcInclude>${mdcInclude}</mdcInclude>
+      <redactionEnabled>${redactionEnabled}</redactionEnabled>
+      <redactionPatterns>${redactionPatterns}</redactionPatterns>
       <customFields>{"service":"${service}"}</customFields>
       <fieldNames>
         <logger>logger</logger>
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
index 233b584..ef33a2c 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
@@ -5,6 +5,12 @@ import static org.assertj.core.api.Assertions.assertThat;
 import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
 import ae.gov.dubaicustoms.platform.core.context.RequestContext;
 import ch.qos.logback.classic.LoggerContext;
+import ch.qos.logback.classic.spi.ILoggingEvent;
+import ch.qos.logback.core.Appender;
+import ch.qos.logback.core.AsyncAppenderBase;
+import ch.qos.logback.core.ConsoleAppender;
+import java.util.ArrayList;
+import java.util.List;
 import java.util.Map;
 import org.junit.jupiter.api.AfterEach;
 import org.junit.jupiter.api.Test;
@@ -83,6 +89,30 @@ class JsonLogOutputTest {
         assertThat(jsonLine).contains("\"stack_trace\":").contains("IllegalStateException");
     }
 
+    @Test
+    void theOnlyRootAppenderIsASynchronousConsoleAppenderWithThePlatformEncoder() {
+        // AC14: nothing is buffered in an async appender, so shutdown cannot drop the last lines.
+        try (ConfigurableApplicationContext context = boot()) {
+            Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
+            List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
+            ((ch.qos.logback.classic.Logger) root).iteratorForAppenders().forEachRemaining(appenders::add);
+            assertThat(appenders).singleElement()
+                    .isInstanceOfSatisfying(ConsoleAppender.class, console ->
+                            assertThat(console.getEncoder()).isInstanceOf(PlatformJsonEncoder.class))
+                    .isNotInstanceOf(AsyncAppenderBase.class);
+        }
+    }
+
+    @Test
+    void killSwitchRestoresBootsOwnLogging(CapturedOutput output) {
+        try (ConfigurableApplicationContext context = boot("--dc.platform.logging.enabled=false")) {
+            LoggerFactory.getLogger("off.probe").info("kill switch probe");
+        }
+        String line = output.getOut().lines().filter(l -> l.contains("kill switch probe")).findFirst()
+                .orElseThrow(() -> new AssertionError("no line captured"));
+        assertThat(line).doesNotStartWith("{");
+    }
+
     @Test
     void localProfileFallsBackToHumanReadableConsole(CapturedOutput output) {
         try (ConfigurableApplicationContext context = boot("--spring.profiles.active=local")) {
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfigurationTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfigurationTest.java
index 847d4e7..17d3a45 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfigurationTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingAutoConfigurationTest.java
@@ -4,7 +4,17 @@ import static org.assertj.core.api.Assertions.assertThat;
 
 import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
 import ae.gov.dubaicustoms.platform.logging.Kv;
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry;
+import java.io.IOException;
+import java.io.UncheckedIOException;
+import java.nio.file.Files;
+import java.nio.file.Path;
+import java.util.List;
+import java.util.Map;
+import java.util.stream.Stream;
 import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.support.DefaultListableBeanFactory;
 import org.springframework.boot.autoconfigure.AutoConfigurations;
 import org.springframework.boot.test.context.FilteredClassLoader;
 import org.springframework.boot.test.context.runner.ApplicationContextRunner;
@@ -72,4 +82,71 @@ class LoggingAutoConfigurationTest {
                     assertThat(properties.serviceName()).isEqualTo("orders");
                 });
     }
+
+    @Test
+    void sanitizerRegistryActiveByDefaultAndOffWithRedaction() {
+        runner.run(context -> assertThat(context).hasSingleBean(LogSanitizerRegistry.class));
+        runner.withPropertyValues("dc.platform.logging.redaction.enabled=false")
+                .run(context -> assertThat(context).doesNotHaveBean(LogSanitizerRegistry.class));
+    }
+
+    @Test
+    void sanitizerRegistryBacksOffForAUserRegistryUnderAnyName() {
+        LogSanitizerRegistry mine = new LogSanitizerRegistry(new DefaultListableBeanFactory().getBeanProvider(LogSanitizer.class));
+        runner.withBean("myRegistry", LogSanitizerRegistry.class, () -> mine)
+                .run(context -> assertThat(context.getBean(LogSanitizerRegistry.class)).isSameAs(mine));
+    }
+
+    @Test
+    void unrelatedDescriptorsDoNotSuppressTheLoggingOne() {
+        runner.withBean("otherCapabilityDescriptor", CapabilityDescriptor.class,
+                        () -> new CapabilityDescriptor("other", "ACTIVE", ""))
+                .run(context -> assertThat(context).hasBean("loggingCapabilityDescriptor"));
+    }
+
+    @Test
+    void configurationDefaultsSnapshot() {
+        // Plan §7.2: a changed default fails here until the snapshot is consciously updated.
+        runner.run(context -> {
+            LoggingProperties p = context.getBean(LoggingProperties.class);
+            assertThat(Map.of("enabled", p.enabled(), "format", p.format(), "include-mdc", p.includeMdc(),
+                    "service-name", p.serviceName(), "mdc.include", p.mdc().include(),
+                    "redaction.enabled", p.redaction().enabled(), "redaction.patterns", p.redaction().patterns()))
+                    .isEqualTo(Map.of("enabled", true, "format", LoggingProperties.Format.JSON, "include-mdc", true,
+                            "service-name", "", "mdc.include", List.of(),
+                            "redaction.enabled", true, "redaction.patterns", List.of()));
+        });
+    }
+
+    @Test
+    void platformCodeWritesOnlyTraceAndSpanIdsToTheMdc() throws IOException {
+        // Phase-02 R2.1 (D101): the platform's own MDC emission is an allow-list. Every MDC.put in
+        // platform main code is in RequestContext (key traceId plus the caller's extras), and the only
+        // caller that opens a context is the correlation filter, whose extras are spanId.
+        Path root = Path.of("../..").toAbsolutePath().normalize();
+        List<Path> writers = mainSourcesContaining(root, "MDC.put(");
+        assertThat(writers).extracting(f -> f.getFileName().toString()).containsExactly("RequestContext.java");
+        assertThat(read(writers.get(0))).contains("CORRELATION_ID_KEY = \"traceId\"");
+        List<Path> openers = mainSourcesContaining(root, "RequestContext.open(");
+        assertThat(openers).extracting(f -> f.getFileName().toString()).containsExactly("CorrelationIdFilter.java");
+        assertThat(read(openers.get(0))).contains("Map.of(\"spanId\", trace.spanId())");
+    }
+
+    private static List<Path> mainSourcesContaining(Path root, String needle) throws IOException {
+        try (Stream<Path> files = Files.walk(root)) {
+            return files.filter(f -> f.toString().endsWith(".java"))
+                    .filter(f -> f.toString().replace('\\', '/').contains("/src/main/java/"))
+                    .filter(f -> !f.toString().replace('\\', '/').contains("/target/"))
+                    .filter(f -> read(f).contains(needle))
+                    .toList();
+        }
+    }
+
+    private static String read(Path file) {
+        try {
+            return Files.readString(file);
+        } catch (IOException e) {
+            throw new UncheckedIOException(e);
+        }
+    }
 }
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java
index f1939e0..0106ea3 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java
@@ -95,4 +95,32 @@ class PlatformLoggingEnvironmentPostProcessorTest {
         assertThat(environment.getPropertySources()
                 .contains(PlatformLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
     }
+
+    @Test
+    void contributedDefaultsSnapshot() {
+        // Plan §7.2 for the pre-context defaults, including the flattened list properties.
+        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "orders");
+        postProcessor.postProcessEnvironment(environment, null);
+
+        assertThat(environment.getProperty("logging.config")).isEqualTo(PlatformLoggingEnvironmentPostProcessor.CONFIG_LOCATION);
+        assertThat(environment.getProperty("dc.platform.logging.service-name")).isEqualTo("orders");
+        assertThat(environment.getProperty(PlatformLoggingEnvironmentPostProcessor.MDC_INCLUDE_RESOLVED)).isEmpty();
+        assertThat(environment.getProperty(PlatformLoggingEnvironmentPostProcessor.REDACTION_PATTERNS_RESOLVED)).isEmpty();
+    }
+
+    @Test
+    void listPropertiesAreFlattenedForLogback() {
+        MockEnvironment environment = new MockEnvironment()
+                .withProperty("dc.platform.logging.mdc.include[0]", "tenant")
+                .withProperty("dc.platform.logging.mdc.include[1]", "channel")
+                .withProperty("dc.platform.logging.redaction.patterns[0]", "A,B\\d")
+                .withProperty("dc.platform.logging.redaction.patterns[1]", "CASE-\\d{6}");
+        postProcessor.postProcessEnvironment(environment, null);
+
+        assertThat(environment.getProperty(PlatformLoggingEnvironmentPostProcessor.MDC_INCLUDE_RESOLVED))
+                .isEqualTo("tenant,channel");
+        // One pattern per line: a regex may contain commas.
+        assertThat(environment.getProperty(PlatformLoggingEnvironmentPostProcessor.REDACTION_PATTERNS_RESOLVED))
+                .isEqualTo("A,B\\d\nCASE-\\d{6}");
+    }
 }
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/RedactedJsonLogOutputTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/RedactedJsonLogOutputTest.java
new file mode 100644
index 0000000..ef9348f
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/RedactedJsonLogOutputTest.java
@@ -0,0 +1,211 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure;
+
+import static net.logstash.logback.argument.StructuredArguments.kv;
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.logging.Kv;
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import ch.qos.logback.classic.LoggerContext;
+import com.fasterxml.jackson.databind.JsonNode;
+import com.fasterxml.jackson.databind.ObjectMapper;
+import java.util.List;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.slf4j.MDC;
+import org.springframework.boot.WebApplicationType;
+import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
+import org.springframework.boot.builder.SpringApplicationBuilder;
+import org.springframework.boot.test.system.CapturedOutput;
+import org.springframework.boot.test.system.OutputCaptureExtension;
+import org.springframework.context.ConfigurableApplicationContext;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+
+/**
+ * Phase-02 R1–R3 through a real boot of logback-platform.xml: masking in every field (message, MDC,
+ * structured arguments, exception message, stack trace), LogSanitizer beans applied, strict MDC mode,
+ * and injection-safe lines.
+ */
+@ExtendWith(OutputCaptureExtension.class)
+class RedactedJsonLogOutputTest {
+
+    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";
+    private static final ObjectMapper JSON = new ObjectMapper();
+
+    private final Logger log = LoggerFactory.getLogger("redaction.probe");
+
+    @Configuration(proxyBeanMethods = false)
+    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
+    static class Empty {
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
+    static class WithSanitizer {
+
+        @Bean
+        LogSanitizer customerNameSanitizer() {
+            return (key, value) -> "customerName".equals(key) ? "C***" : value;
+        }
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
+    static class WithAppendingSanitizer {
+
+        @Bean
+        LogSanitizer appendingSanitizer() {
+            return (key, value) -> "note".equals(key) ? value + "!" : value;
+        }
+    }
+
+    @AfterEach
+    void reset() {
+        MDC.clear();
+        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
+    }
+
+    private ConfigurableApplicationContext boot(Class<?> config, String... args) {
+        return new SpringApplicationBuilder(config).web(WebApplicationType.NONE)
+                .properties("spring.application.name=orders").run(args);
+    }
+
+    @Test
+    void masksEveryFieldTheEncoderWrites(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(Empty.class)) {
+            MDC.put("authorization", "Bearer abcdefgh12345678");
+            MDC.put("payer", "card 4111111111111111");
+            MDC.put("contact", "fatima@customs.gov.ae, +971 50 123 4567");
+            log.error("login for {} with password=hunter2 token {}", kv("emiratesId", "784-1990-1234567-1"), JWT,
+                    new IllegalStateException("rejected Bearer abcdefgh12345678"));
+        }
+        String line = line(output, "login for");
+        JsonNode event = JSON.readTree(line);
+        assertThat(line).doesNotContain(JWT).doesNotContain("hunter2").doesNotContain("abcdefgh12345678")
+                .doesNotContain("4111111111111111").doesNotContain("1234567");
+        assertThat(event.get("message").asText()).contains("password=****").contains("token ****");
+        assertThat(event.get("authorization").asText()).isEqualTo("****");
+        assertThat(event.get("payer").asText()).isEqualTo("card ****");
+        assertThat(event.get("contact").asText()).isEqualTo("****, ****");
+        assertThat(event.get("emiratesId").asText()).isEqualTo("****");
+        assertThat(event.get("stack_trace").asText()).contains("IllegalStateException").contains("Bearer ****");
+    }
+
+    @Test
+    void cardNumbersLoggedAsNumbersAreMasked(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(Empty.class)) {
+            log.info("numeric {} {}", kv("card", 4111111111111111L), kv("orderId", 1793001600123L));
+        }
+        String line = line(output, "numeric");
+        JsonNode event = JSON.readTree(line);
+        assertThat(line).doesNotContain("4111111111111111");
+        assertThat(event.get("card").asText()).isEqualTo("****");
+        assertThat(event.get("orderId").asLong()).isEqualTo(1793001600123L);
+    }
+
+    @Test
+    void ordinaryValuesAreNotMasked(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(Empty.class)) {
+            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
+            MDC.put("orderId", "400000000000001");
+            log.info("took {} ms at {}", 250, 1793001600123L);
+        }
+        JsonNode event = JSON.readTree(line(output, "took 250 ms"));
+        assertThat(event.get("message").asText()).isEqualTo("took 250 ms at 1793001600123");
+        assertThat(event.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
+        assertThat(event.get("orderId").asText()).isEqualTo("400000000000001");
+    }
+
+    @Test
+    void appliesLogSanitizerBeansToMdcAndArguments(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(WithSanitizer.class)) {
+            assertThat(context.getBeanNamesForType(
+                    ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry.class)).hasSize(1);
+            MDC.put("customerName", "Fatima");
+            log.info("hello {} and {}", kv("customerName", "Fatima"), Kv.of("customerName", "Fatima"));
+            log.info("unrelated {}", kv("city", "Dubai"));
+        }
+        String line = line(output, "hello");
+        JsonNode event = JSON.readTree(line);
+        assertThat(event.get("customerName").asText()).isEqualTo("C***");
+        // Round 1 F5: the formatted message no longer carries the raw value either.
+        assertThat(event.get("message").asText()).isEqualTo("hello customerName=C*** and customerName=C***");
+        assertThat(line).doesNotContain("Fatima");
+        assertThat(JSON.readTree(line(output, "unrelated")).get("message").asText()).isEqualTo("unrelated city=Dubai");
+    }
+
+    @Test
+    void aNonIdempotentSanitizerRunsOncePerOutput(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(WithAppendingSanitizer.class)) {
+            log.info("note {}", kv("note", "x"));
+        }
+        JsonNode event = JSON.readTree(line(output, "note"));
+        assertThat(event.get("note").asText()).isEqualTo("x!");
+        assertThat(event.get("message").asText()).isEqualTo("note note=x!");
+    }
+
+    @Test
+    void redactionCanBeSwitchedOffAndExtended(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(Empty.class, "--dc.platform.logging.redaction.enabled=false")) {
+            assertThat(context.getBeanNamesForType(
+                    ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry.class)).isEmpty();
+            log.info("off password=hunter2");
+        }
+        assertThat(line(output, "off password")).contains("password=hunter2");
+        reset();
+        try (ConfigurableApplicationContext context = boot(Empty.class,
+                "--dc.platform.logging.redaction.patterns[0]=CASE-\\d{6}")) {
+            log.info("extended CASE-123456");
+        }
+        assertThat(JSON.readTree(line(output, "extended")).get("message").asText()).isEqualTo("extended ****");
+    }
+
+    @Test
+    void strictMdcModeWritesOnlyPlatformAndListedKeys(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(Empty.class, "--dc.platform.logging.mdc.include=tenant")) {
+            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
+            MDC.put("spanId", "00f067aa0ba902b7");
+            MDC.put("tenant", "dubai");
+            MDC.put("internalDebug", "x");
+            log.info("strict probe");
+        }
+        JsonNode event = JSON.readTree(line(output, "strict probe"));
+        assertThat(event.has("traceId")).isTrue();
+        assertThat(event.has("spanId")).isTrue();
+        assertThat(event.get("tenant").asText()).isEqualTo("dubai");
+        assertThat(event.has("internalDebug")).isFalse();
+    }
+
+    @Test
+    void consumerMdcPassesThroughByDefault(CapturedOutput output) throws Exception {
+        try (ConfigurableApplicationContext context = boot(Empty.class)) {
+            MDC.put("internalDebug", "x");
+            log.info("default mdc probe");
+        }
+        assertThat(JSON.readTree(line(output, "default mdc probe")).get("internalDebug").asText()).isEqualTo("x");
+    }
+
+    @Test
+    void userInputCannotForgeLinesOrFields(CapturedOutput output) throws Exception {
+        String hostile = "a\r\nb\"c {\"level\":\"ERROR\",\"message\":\"forged\"}";
+        try (ConfigurableApplicationContext context = boot(Empty.class)) {
+            MDC.put("input", hostile);
+            log.info("injection probe {}", hostile);
+        }
+        List<String> lines = output.getOut().lines().filter(l -> l.contains("injection probe")).toList();
+        assertThat(lines).hasSize(1);
+        assertThat(output.getOut().lines().filter(l -> l.startsWith("{\"level\":\"ERROR\""))).isEmpty();
+        JsonNode event = JSON.readTree(lines.get(0));
+        assertThat(event.get("level").asText()).isEqualTo("INFO");
+        assertThat(event.get("message").asText()).isEqualTo("injection probe " + hostile);
+        assertThat(event.get("input").asText()).isEqualTo(hostile);
+    }
+
+    private static String line(CapturedOutput output, String marker) {
+        return output.getOut().lines().filter(l -> l.contains(marker)).reduce((a, b) -> b)
+                .orElseThrow(() -> new AssertionError("no line with " + marker));
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
new file mode 100644
index 0000000..c7e7bfb
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
@@ -0,0 +1,243 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import com.fasterxml.jackson.core.JsonStreamContext;
+import java.math.BigDecimal;
+import java.math.BigInteger;
+import java.time.Duration;
+import java.util.List;
+import java.util.regex.Pattern;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.params.ParameterizedTest;
+import org.junit.jupiter.params.provider.CsvSource;
+import org.junit.jupiter.params.provider.ValueSource;
+import org.springframework.beans.factory.support.DefaultListableBeanFactory;
+
+/**
+ * Pattern, key and sanitizer masking of {@link PlatformValueMasker} (D102), including the phase-02
+ * round-1 findings (quoted values, cookie headers, short credentials, numeric credentials, PAN
+ * boundaries next to hex IDs, linear e-mail scanning) and false positives.
+ */
+class PlatformValueMaskerTest {
+
+    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";
+    private static final String MASK = PlatformValueMasker.MASK;
+
+    private final PlatformValueMasker masker = new PlatformValueMasker(List.of());
+
+    @AfterEach
+    void clearSanitizers() {
+        publish();
+    }
+
+    @ParameterizedTest
+    @ValueSource(strings = {
+            "token " + JWT + " seen",
+            "Authorization: Bearer abcdefgh12345678",
+            "header Basic dXNlcjpwYXNzd29yZA==",
+            "login password=hunter2 ok",
+            "{\"client_secret\":\"s3cr3t-value\"}",
+            "api-key: k-123456",
+            "id 784-1990-1234567-1 checked",
+            "id 784199012345671 checked",
+            "card 4111111111111111 charged",
+            "card 4111 1111 1111 1111 charged",
+            "card 5500-0000-0000-0004 charged",
+            "mail fatima.ali+orders@customs.gov.ae sent",
+            "call +971 50 123 4567 now",
+            "call +971501234567 now",
+            "call 00971501234567 now",
+            "call 050-123-4567 now",
+            "call 0501234567 now",
+            "intl +44 20 7946 0958 ok"})
+    void masksCredentialsAndPii(String text) {
+        String masked = masker.maskPatterns(text);
+        assertThat(masked).contains(MASK)
+                .doesNotContain(JWT).doesNotContain("abcdefgh12345678").doesNotContain("dXNlcjpwYXNzd29yZA==")
+                .doesNotContain("hunter2").doesNotContain("s3cr3t-value").doesNotContain("k-123456")
+                .doesNotContain("1234567").doesNotContain("4111").doesNotContain("0004")
+                .doesNotContain("@customs").doesNotContain("4567").doesNotContain("0958");
+    }
+
+    // Round 1 F1: quoted values (with escapes) are masked whole; every pair of a cookie header goes.
+    @ParameterizedTest
+    @CsvSource(delimiter = '|', value = {
+            "password=\"correct horse battery staple\" ok | password=\"****\" ok",
+            "password='hunter2' ok                      | password='****' ok",
+            "secret: \"a \\\"quoted\\\" b\" end           | secret: \"****\" end",
+            "{\"password\":\"p w\",\"user\":\"ali\"}      | {\"password\":\"****\",\"user\":\"ali\"}",
+            "Cookie: foo=abc; session=deadbeef          | Cookie: ****",
+            "Set-Cookie: sid=abc123; Path=/; HttpOnly   | Set-Cookie: ****",
+            "{\"cookie\":\"a=1; b=2\",\"n\":1}            | {\"cookie\":\"****\",\"n\":1}"})
+    void masksQuotedValuesAndWholeCookieHeaders(String text, String expected) {
+        assertThat(masker.maskPatterns(text)).isEqualTo(expected);
+    }
+
+    // Round 1 F2: no length floors; Basic is masked when it decodes to user:password.
+    @ParameterizedTest
+    @CsvSource(delimiter = '|', value = {
+            "Basic YTpi           | Basic ****",
+            "Bearer abc           | Bearer ****",
+            "Bearer token missing | Bearer **** missing",
+            "jwt eyJhbGciOiJub25lIn0.e30. done | jwt **** done",
+            "jwt ewogICJhbGciOiAibm9uZSIKfQ.e30.sig done | jwt **** done"})
+    void masksShortCredentials(String text, String expected) {
+        assertThat(masker.maskPatterns(text)).isEqualTo(expected);
+    }
+
+    @Test
+    void basicWordInProseStaysWhenItIsNotACredential() {
+        assertThat(masker.maskPatterns("Basic auth is disabled")).isEqualTo("Basic auth is disabled");
+    }
+
+    @Test
+    void keepsTheKeyAndSchemeVisible() {
+        assertThat(masker.maskPatterns("password=hunter2")).isEqualTo("password=****");
+        assertThat(masker.maskPatterns("Bearer abcdefgh12345678")).isEqualTo("Bearer ****");
+    }
+
+    // Round 1 F4: hex identifiers with an embedded Luhn-valid run stay intact; a following number does
+    // not hide a card; a trailing hyphen is a boundary.
+    @ParameterizedTest
+    @CsvSource(delimiter = '|', value = {
+            "trace abcd4111111111111111abcdefabcdef | trace abcd4111111111111111abcdefabcdef",
+            "card 4111111111111111 12               | card **** 12",
+            "card 4111 1111 1111 1111 12            | card **** 12",
+            "card 4111111111111111- end             | card ****- end",
+            "amex 3782 822463 10005 ok               | amex **** ok"})
+    void cardNumberBoundaries(String text, String expected) {
+        assertThat(masker.maskPatterns(text)).isEqualTo(expected);
+    }
+
+    @ParameterizedTest
+    @ValueSource(strings = {
+            "at 1793001600123 ms",                       // epoch millis
+            "order 4000000001 created",                  // numeric order id (10 digits)
+            "order 400000000000001 created",             // 15 digits, not Luhn-valid
+            "trace 4bf92f3577b34da6a3ce929d0e0e4736",     // trace id
+            "span 00f067aa0ba902b7",                      // span id
+            "uuid 3f2504e0-4f89-11d3-9a0c-0305e82c3301",
+            "uuid 12345678-1234-5678-1234-567812345678",
+            "at 2026-10-26T18:07:36.818Z took PT0.25S status 503",
+            "object com.example.Order@6d06d69c",            // identity hash
+            "trace 0012345678abcdef0123456789abcdef",       // trace id starting 00 + digits
+            "uuid 3f2504e0-0012-3456-7890-0305e82c3301",
+            "version 1.2.3 build 20261026",
+            "ip 10.0.12.7 port 8080",
+            "landline 04 123 4567 not matched",            // documented gap
+            "eyes.of.the storm",                           // not a JWT
+            "tokens: 5, passwords rotated"})
+    void leavesOrdinaryValuesAlone(String text) {
+        assertThat(masker.maskPatterns(text)).isEqualTo(text);
+    }
+
+    @Test
+    void traceAndSpanIdFieldsKeepTheirHexIdsButNotOtherContent() {
+        assertThat(masker.mask(field("traceId"), "abcd4111111111111111abcdefabcdef")).isNull();
+        assertThat(masker.mask(field("spanId"), "4111111111111111")).isNull();
+        assertThat(masker.mask(field("traceId"), "password=hunter2")).isEqualTo("password=****");
+        assertThat(masker.mask(field("level"), "password=hunter2")).isNull();
+    }
+
+    @Test
+    void masksWholeValueOfCredentialNamedFields() {
+        assertThat(masker.mask(field("Authorization"), "anything at all")).isEqualTo(MASK);
+        assertThat(masker.mask(field("x-api-key"), "abc")).isEqualTo(MASK);
+        assertThat(masker.mask(field("refresh_token"), "abc")).isEqualTo(MASK);
+    }
+
+    // Round 1 F3: credential names win whatever the value type, also for nested values.
+    @Test
+    void credentialNamesWinForEveryValueTypeAndNesting() {
+        assertThat(masker.mask(field("password"), 123456)).isEqualTo(MASK);
+        assertThat(masker.mask(field("token"), true)).isEqualTo(MASK);
+        assertThat(masker.mask(nested("password", "inner"), "plain")).isEqualTo(MASK);
+        assertThat(masker.mask(nested("details", "inner"), "plain")).isNull();
+    }
+
+    @Test
+    void masksCardNumbersWrittenAsNumbers() {
+        assertThat(masker.mask(field("card"), 4111111111111111L)).isEqualTo(MASK);
+        assertThat(masker.mask(field("card"), new BigInteger("4111111111111111"))).isEqualTo(MASK);
+        assertThat(masker.mask(field("card"), new BigDecimal("4111111111111111.00"))).isEqualTo(MASK);
+        assertThat(masker.mask(field("card"), new BigDecimal("4111111111111111.5"))).isNull();
+    }
+
+    @Test
+    void returnsNullWhenNothingChangesOrValueIsNotText() {
+        assertThat(masker.mask(field("message"), "plain")).isNull();
+        assertThat(masker.mask(field("count"), 42)).isNull();
+        assertThat(masker.mask(field("at"), 1793001600123L)).isNull();
+        assertThat(masker.mask(field("id"), 4111111111111112L)).isNull(); // not Luhn-valid
+        assertThat(masker.mask(field(null), "plain")).isNull();
+        assertThat(masker.mask(field("x"), null)).isNull();
+    }
+
+    // Round 1 F6: long inputs are scanned in linear time.
+    @Test
+    void longInputsWithoutMatchesAreScannedQuickly() {
+        String noAt = "a".repeat(200_000);
+        String trailingAt = "a.".repeat(100_000) + "@";
+        String digits = "1".repeat(200_000);
+        long start = System.nanoTime();
+        assertThat(masker.maskPatterns(noAt)).isEqualTo(noAt);
+        assertThat(masker.maskPatterns(trailingAt)).isEqualTo(trailingAt);
+        assertThat(masker.maskPatterns(digits)).isEqualTo(digits);
+        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(750));
+    }
+
+    @Test
+    void appliesSanitizersToNonPlatformFieldsOnly() {
+        publish((key, value) -> "customerName".equals(key) ? "C***" : value);
+        assertThat(masker.mask(field("customerName"), "Fatima")).isEqualTo("C***");
+        assertThat(masker.mask(field("message"), "customerName")).isNull();
+    }
+
+    @Test
+    void patternsStillRunAfterSanitizersAndAThrowingSanitizerIsIgnored() {
+        publish((key, value) -> { throw new IllegalStateException("broken"); },
+                (key, value) -> value + " Bearer abcdefgh12345678");
+        assertThat(masker.mask(field("note"), "hello")).isEqualTo("hello Bearer ****");
+    }
+
+    @Test
+    void extraPatternsAreMasked() {
+        PlatformValueMasker custom = new PlatformValueMasker(PlatformValueMasker.compile("CASE-\\d{6}\n\n  "));
+        assertThat(custom.maskPatterns("see CASE-123456")).isEqualTo("see ****");
+        assertThat(PlatformValueMasker.compile(null)).isEmpty();
+        assertThat(PlatformValueMasker.compile(" a+ \n")).extracting(Pattern::pattern).containsExactly("a+");
+    }
+
+    static void publish(LogSanitizer... sanitizers) {
+        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
+        for (int i = 0; i < sanitizers.length; i++) {
+            beans.registerSingleton("s" + i, sanitizers[i]);
+        }
+        new LogSanitizerRegistry(beans.getBeanProvider(LogSanitizer.class)).afterSingletonsInstantiated();
+    }
+
+    private static JsonStreamContext field(String name) {
+        return context(name, null);
+    }
+
+    private static JsonStreamContext nested(String outer, String inner) {
+        return context(inner, context(outer, null));
+    }
+
+    private static JsonStreamContext context(String name, JsonStreamContext parent) {
+        return new JsonStreamContext(JsonStreamContext.TYPE_OBJECT, 0) {
+            @Override
+            public JsonStreamContext getParent() {
+                return parent;
+            }
+
+            @Override
+            public String getCurrentName() {
+                return name;
+            }
+        };
+    }
+}
diff --git a/observability/platform-observability-autoconfigure/pom.xml b/observability/platform-observability-autoconfigure/pom.xml
index 201b4bf..3cc5927 100644
--- a/observability/platform-observability-autoconfigure/pom.xml
+++ b/observability/platform-observability-autoconfigure/pom.xml
@@ -79,5 +79,33 @@
       <artifactId>spring-boot-starter-web</artifactId>
       <scope>test</scope>
     </dependency>
+    <!-- /actuator/prometheus content (common tags, no high-cardinality tag values) and Boot's
+         client uri-tag cap (phase-02 R5, D106) asserted end to end. -->
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-registry-prometheus</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-starter-restclient</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <!-- The starter's tracing classpath, for the OTLP on/off switch and sampling-default tests. -->
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-tracing-bridge-otel</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-micrometer-tracing-opentelemetry</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>io.opentelemetry</groupId>
+      <artifactId>opentelemetry-exporter-otlp</artifactId>
+      <scope>test</scope>
+    </dependency>
   </dependencies>
 </project>
diff --git a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfiguration.java b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfiguration.java
index 4966055..dbe2cbf 100644
--- a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfiguration.java
+++ b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfiguration.java
@@ -15,9 +15,11 @@ import org.springframework.context.annotation.Bean;
 /*
  * Activates when: spring-boot-actuator on the classpath AND
  *                 dc.platform.observability.enabled != false
- * Backs off when: user defines a PlatformEndpoint bean
+ * Backs off when: user defines a PlatformEndpoint bean (by type); user defines a bean named
+ *                 observabilityCapabilityDescriptor (one-of-many type, D97)
  * Beans: platformEndpoint — actuator @Endpoint(id="platform") returning the CapabilityDescriptor
- *                           list as JSON (only when the endpoint is exposed AND
+ *                           list as JSON (only when the endpoint is exposed — not by the platform
+ *                           default, D105 — AND
  *                           dc.platform.observability.platform-endpoint.enabled != false);
  *        observabilityCapabilityDescriptor — reports observability[ACTIVE] into the banner with
  *                                            the metrics-export posture as detail
@@ -41,6 +43,7 @@ public class PlatformInfoEndpointAutoConfiguration {
     }
 
     @Bean
+    @ConditionalOnMissingBean(name = "observabilityCapabilityDescriptor")
     CapabilityDescriptor observabilityCapabilityDescriptor(ObservabilityProperties properties) {
         return new CapabilityDescriptor("observability", "ACTIVE",
                 properties.otlp().enabled() ? "prometheus+otlp" : "prometheus");
diff --git a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java
index 9951a77..ec7b5cb 100644
--- a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java
+++ b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java
@@ -9,11 +9,11 @@ import org.springframework.core.env.ConfigurableEnvironment;
 import org.springframework.core.env.MapPropertySource;
 
 /**
- * Contributes the platform's management-plane defaults — actuator exposure, liveness/readiness
- * health groups, and the local OTLP-off posture — as a LOWEST-precedence
- * property source named {@code platform-observability-defaults}, so user configuration always
- * wins and the source name is visible in {@code /actuator/env} for debuggability
- * (property-conventions §7).
+ * Contributes the platform's management-plane defaults — minimal actuator exposure, pod-local
+ * liveness/readiness groups plus a {@code dependencies} group, and the local OTLP-off posture — as a
+ * LOWEST-precedence property source named {@code platform-observability-defaults}, so user
+ * configuration always wins and the source name is visible in {@code /actuator/env} for
+ * debuggability (property-conventions §7).
  *
  * <p>Cardinality rationale (mandatory, phase-05 spec): the correlation id — the W3C trace ID — is
  * HIGH-cardinality, one value per request. It must never become a metric tag or observation
@@ -30,25 +30,32 @@ public class PlatformObservabilityEnvironmentPostProcessor implements Environmen
     /** Property-source name; keep stable — operators grep for it in /actuator/env. */
     static final String PROPERTY_SOURCE_NAME = "platform-observability-defaults";
 
+    /** Default actuator web exposure. */
+    static final String EXPOSURE = "health,info,prometheus";
+
+    /** Members of the {@code dependencies} health group: shared infrastructure, never readiness. */
+    static final String DEPENDENCIES = "db,rabbit,redis";
+
     @Override
     public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
         if (!environment.getProperty("dc.platform.observability.enabled", Boolean.class, true)) {
             return;
         }
         Map<String, Object> defaults = new LinkedHashMap<>();
-        // Actuator exposure conventions: the platform's operational surface over http. The
-        // platform endpoint itself carries no secrets; real authn arrives with phase 6 security.
-        defaults.put("management.endpoints.web.exposure.include",
-                "health,info,platform,metrics,prometheus");
+        // Minimal exposure (D105): probes, build info and the scrape endpoint. Everything else,
+        // including metrics and the platform capability report, is an explicit opt-in.
+        defaults.put("management.endpoints.web.exposure.include", EXPOSURE);
         if (environment.getProperty("dc.platform.observability.health.groups.enabled", Boolean.class, true)) {
             // probes.enabled makes the livenessState/readinessState contributors exist on every
             // platform (Boot only auto-enables them on Kubernetes).
             defaults.put("management.endpoint.health.probes.enabled", "true");
             defaults.put("management.endpoint.health.group.liveness.include", "livenessState");
-            defaults.put("management.endpoint.health.group.readiness.include",
-                    "readinessState,db,rabbit,redis");
-            // The readiness list names OPTIONAL members ("when present", phase-05 spec); with
-            // membership validation on, a service without a DB would fail startup.
+            // Readiness is pod-local (D6/D104): a shared dependency in it would mark every replica
+            // unready at once. Shared dependencies get their own group, for dashboards.
+            defaults.put("management.endpoint.health.group.readiness.include", "readinessState");
+            defaults.put("management.endpoint.health.group.dependencies.include", DEPENDENCIES);
+            // The dependencies list names OPTIONAL members (present only with the matching
+            // capability); with membership validation on, a service without a DB would fail startup.
             defaults.put("management.endpoint.health.validate-group-membership", "false");
         }
         if (!environment.getProperty("dc.platform.observability.otlp.enabled", Boolean.class, false)) {
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/OtlpExportSwitchTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/OtlpExportSwitchTest.java
new file mode 100644
index 0000000..c912c05
--- /dev/null
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/OtlpExportSwitchTest.java
@@ -0,0 +1,53 @@
+package ae.gov.dubaicustoms.platform.observability.autoconfigure;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
+import org.junit.jupiter.api.Test;
+import org.springframework.boot.SpringBootConfiguration;
+import org.springframework.boot.WebApplicationType;
+import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
+import org.springframework.boot.builder.SpringApplicationBuilder;
+import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
+import org.springframework.context.ConfigurableApplicationContext;
+
+/**
+ * Phase-02 round 1 F7 and AC16 with the observability starter's tracing classpath (bridge, Boot
+ * companion module, OTLP exporter), through a real boot so the EnvironmentPostProcessor runs: the
+ * OTLP span exporter exists only when dc.platform.observability.otlp.enabled=true, and head sampling
+ * stays Boot's default 0.1 (D103).
+ */
+class OtlpExportSwitchTest {
+
+    @SpringBootConfiguration
+    @EnableAutoConfiguration
+    static class App {
+    }
+
+    private static ConfigurableApplicationContext boot(String... args) {
+        return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).run(args);
+    }
+
+    @Test
+    void spanExportIsOffByDefault() {
+        try (ConfigurableApplicationContext context = boot()) {
+            assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).isEmpty();
+        }
+    }
+
+    @Test
+    void theSwitchTurnsOnTheOtlpSpanExporter() {
+        // Points at a closed local port: the exporter is created lazily and nothing is sent here.
+        try (ConfigurableApplicationContext context = boot("--dc.platform.observability.otlp.enabled=true",
+                "--management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces")) {
+            assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).hasSize(1);
+        }
+    }
+
+    @Test
+    void headSamplingStaysBootsDefault() {
+        try (ConfigurableApplicationContext context = boot()) {
+            assertThat(context.getBean(TracingProperties.class).getSampling().getProbability()).isEqualTo(0.1f);
+        }
+    }
+}
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java
index f034806..a244391 100644
--- a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java
@@ -6,53 +6,106 @@ import java.net.URI;
 import java.net.http.HttpClient;
 import java.net.http.HttpRequest;
 import java.net.http.HttpResponse;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import org.junit.jupiter.api.Nested;
 import org.junit.jupiter.api.Test;
 import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.boot.SpringBootConfiguration;
 import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
+import org.springframework.boot.health.contributor.Health;
+import org.springframework.boot.health.contributor.HealthIndicator;
 import org.springframework.boot.test.context.SpringBootTest;
+import org.springframework.context.annotation.Bean;
 import org.springframework.core.env.Environment;
 
 /**
- * Boots a real web application (EnvironmentPostProcessor included) and asserts the phase-05
- * observability surface: /actuator/platform serves the capability report and the liveness/
- * readiness health groups exist — proving the exposure and health-group DEFAULTS work end to end.
+ * Boots a real web application (EnvironmentPostProcessor included) and asserts the phase-02
+ * observability surface end to end: minimal default exposure (D105), pod-local readiness with a
+ * separate {@code dependencies} group (D6/D104), and the Prometheus scrape content.
  */
-@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
-        classes = PlatformEndpointWebTest.App.class,
-        properties = "spring.application.name=observability-web-test")
 class PlatformEndpointWebTest {
 
-    @Autowired
-    private Environment environment;
+    private static final Pattern LABEL_VALUE = Pattern.compile("=\"([^\"]*)\"");
 
-    @Test
-    void platformEndpointServesTheCapabilityReport() throws Exception {
-        HttpResponse<String> response = get("/actuator/platform");
+    @Nested
+    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = App.class,
+            properties = "spring.application.name=observability-web-test")
+    class Defaults {
 
-        assertThat(response.statusCode()).isEqualTo(200);
-        assertThat(response.body())
-                .contains("\"name\":\"observability\"")
-                .contains("\"status\":\"ACTIVE\"");
+        @Autowired
+        private Environment environment;
+
+        @Test
+        void onlyHealthInfoAndPrometheusAreExposed() throws Exception {
+            assertThat(get(environment, "/actuator/health").statusCode()).isEqualTo(503); // db is DOWN
+            assertThat(get(environment, "/actuator/info").statusCode()).isEqualTo(200);
+            assertThat(get(environment, "/actuator/prometheus").statusCode()).isEqualTo(200);
+            for (String hidden : new String[] {"platform", "metrics", "env", "beans", "heapdump", "configprops"}) {
+                assertThat(get(environment, "/actuator/" + hidden).statusCode()).as(hidden).isEqualTo(404);
+            }
+        }
+
+        @Test
+        void aSharedDependencyOutageLeavesReadinessUp() throws Exception {
+            assertThat(get(environment, "/actuator/health/readiness").body()).contains("\"status\":\"UP\"");
+            assertThat(get(environment, "/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
+            HttpResponse<String> dependencies = get(environment, "/actuator/health/dependencies");
+            assertThat(dependencies.statusCode()).isEqualTo(503);
+            assertThat(dependencies.body()).contains("\"status\":\"DOWN\"");
+        }
+
+        @Test
+        void scrapeCarriesCommonTagsAndNoHighCardinalityValues() throws Exception {
+            get(environment, "/actuator/health/readiness");
+            String scrape = get(environment, "/actuator/prometheus").body();
+            assertThat(scrape).contains("service=\"observability-web-test\"").contains("platform_version=\"")
+                    .contains("env=\"default\"");
+            Matcher labels = LABEL_VALUE.matcher(scrape);
+            while (labels.find()) {
+                assertThat(labels.group(1)).as("label value")
+                        .doesNotMatch("[0-9a-f]{32}|[0-9a-f]{16}|[0-9a-f]{8}-[0-9a-f]{4}-.*|eyJ.*");
+            }
+        }
     }
 
-    @Test
-    void readinessGroupExistsWithoutAnyUserConfiguration() throws Exception {
-        HttpResponse<String> response = get("/actuator/health/readiness");
+    @Nested
+    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = App.class,
+            properties = "management.endpoints.web.exposure.include=health,platform")
+    class OptIn {
+
+        @Autowired
+        private Environment environment;
 
-        assertThat(response.statusCode()).isEqualTo(200);
-        assertThat(response.body()).contains("\"status\":\"UP\"");
+        @Test
+        void platformEndpointServesTheCapabilityReportWhenExposed() throws Exception {
+            HttpResponse<String> response = get(environment, "/actuator/platform");
+
+            assertThat(response.statusCode()).isEqualTo(200);
+            assertThat(response.body()).contains("\"name\":\"observability\"").contains("\"status\":\"ACTIVE\"");
+        }
     }
 
-    @Test
-    void livenessGroupExistsWithoutAnyUserConfiguration() throws Exception {
-        HttpResponse<String> response = get("/actuator/health/liveness");
+    @Nested
+    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = App.class,
+            properties = "dc.platform.observability.enabled=false")
+    class KillSwitch {
+
+        @Autowired
+        private Environment environment;
 
-        assertThat(response.statusCode()).isEqualTo(200);
-        assertThat(response.body()).contains("\"status\":\"UP\"");
+        @Test
+        void bootsOwnManagementDefaultsComeBack() throws Exception {
+            // Boot's own exposure (health only) and no platform health groups or common tags.
+            assertThat(get(environment, "/actuator/health").statusCode()).isEqualTo(503);
+            assertThat(get(environment, "/actuator/prometheus").statusCode()).isEqualTo(404);
+            assertThat(get(environment, "/actuator/info").statusCode()).isEqualTo(404);
+            assertThat(get(environment, "/actuator/health/dependencies").statusCode()).isEqualTo(404);
+            assertThat(environment.getProperty("management.endpoint.health.group.readiness.include")).isNull();
+        }
     }
 
-    private HttpResponse<String> get(String path) throws Exception {
+    private static HttpResponse<String> get(Environment environment, String path) throws Exception {
         // Plain JDK client: TestRestTemplate left spring-boot-test in Boot 4.
         String port = environment.getProperty("local.server.port");
         HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
@@ -64,5 +117,11 @@ class PlatformEndpointWebTest {
     @SpringBootConfiguration
     @EnableAutoConfiguration
     static class App {
+
+        // Stands in for a shared database that is down: contributor name "db", like Boot's own.
+        @Bean
+        HealthIndicator dbHealthIndicator() {
+            return () -> Health.down().withDetail("reason", "simulated outage").build();
+        }
     }
 }
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfigurationTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfigurationTest.java
index 37a42d9..ac06665 100644
--- a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfigurationTest.java
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformInfoEndpointAutoConfigurationTest.java
@@ -72,4 +72,31 @@ class PlatformInfoEndpointAutoConfigurationTest {
                 .run(ctx -> assertThat(ctx.getBean(CapabilityDescriptor.class).detail())
                         .isEqualTo("prometheus+otlp"));
     }
+
+    @Test
+    void configurationDefaultsSnapshot() {
+        // Plan §7.2: a changed default fails here until the snapshot is consciously updated.
+        runner.run(ctx -> {
+            ObservabilityProperties p = ctx.getBean(ObservabilityProperties.class);
+            assertThat(java.util.Map.of("enabled", p.enabled(), "common-tags.enabled", p.commonTags().enabled(),
+                    "otlp.enabled", p.otlp().enabled(), "health.groups.enabled", p.health().groups().enabled(),
+                    "platform-endpoint.enabled", p.platformEndpoint().enabled()))
+                    .isEqualTo(java.util.Map.of("enabled", true, "common-tags.enabled", true, "otlp.enabled", false,
+                            "health.groups.enabled", true, "platform-endpoint.enabled", true));
+        });
+    }
+
+    @Test
+    void descriptorBacksOffWhenUserDescriptorPresentUnderItsName() {
+        CapabilityDescriptor mine = new CapabilityDescriptor("observability", "ACTIVE", "custom");
+        runner.withBean("observabilityCapabilityDescriptor", CapabilityDescriptor.class, () -> mine)
+                .run(ctx -> assertThat(ctx.getBean(CapabilityDescriptor.class)).isSameAs(mine));
+    }
+
+    @Test
+    void unrelatedDescriptorsDoNotSuppressTheObservabilityOne() {
+        runner.withBean("otherCapabilityDescriptor", CapabilityDescriptor.class,
+                        () -> new CapabilityDescriptor("other", "ACTIVE", ""))
+                .run(ctx -> assertThat(ctx).hasBean("observabilityCapabilityDescriptor"));
+    }
 }
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java
index d5906e9..6f979b2 100644
--- a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java
@@ -19,12 +19,14 @@ class PlatformObservabilityEnvironmentPostProcessorTest {
         assertThat(environment.getPropertySources()
                 .contains(PlatformObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
         assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
-                .isEqualTo("health,info,platform,metrics,prometheus");
+                .isEqualTo("health,info,prometheus");
         assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
         assertThat(environment.getProperty("management.endpoint.health.group.liveness.include"))
                 .isEqualTo("livenessState");
         assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
-                .isEqualTo("readinessState,db,rabbit,redis");
+                .isEqualTo("readinessState");
+        assertThat(environment.getProperty("management.endpoint.health.group.dependencies.include"))
+                .isEqualTo("db,rabbit,redis");
         assertThat(environment.getProperty("management.endpoint.health.validate-group-membership"))
                 .isEqualTo("false");
         // HD5: no custom correlation header is mirrored into baggage; traceparent is the only carrier.
@@ -50,9 +52,10 @@ class PlatformObservabilityEnvironmentPostProcessorTest {
         postProcessor.postProcessEnvironment(environment, null);
 
         assertThat(environment.getProperty("management.endpoint.health.group.liveness.include")).isNull();
+        assertThat(environment.getProperty("management.endpoint.health.group.dependencies.include")).isNull();
         assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isNull();
         assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
-                .isEqualTo("health,info,platform,metrics,prometheus");
+                .isEqualTo("health,info,prometheus");
     }
 
     @Test
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/UriTagCardinalityWebTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/UriTagCardinalityWebTest.java
new file mode 100644
index 0000000..0c04b1a
--- /dev/null
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/UriTagCardinalityWebTest.java
@@ -0,0 +1,87 @@
+package ae.gov.dubaicustoms.platform.observability.autoconfigure;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import io.micrometer.core.instrument.Meter;
+import io.micrometer.core.instrument.MeterRegistry;
+import java.util.Set;
+import java.util.stream.Collectors;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.SpringBootConfiguration;
+import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
+import org.springframework.boot.test.context.SpringBootTest;
+import org.springframework.boot.test.system.CapturedOutput;
+import org.springframework.boot.test.system.OutputCaptureExtension;
+import org.springframework.context.annotation.Import;
+import org.springframework.core.env.Environment;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.RestController;
+import org.springframework.web.client.RestClient;
+
+/**
+ * Phase-02 R5.2 / D106: the platform relies on Boot's uri-tag cap. Outbound calls built from literal
+ * (non-templated) URIs are the realistic unbounded source — each id becomes a uri value — so 400
+ * such calls must leave at most {@code max-uri-tags} (100) client series, while the templated server
+ * side stays at one. This test fails if the cap is removed or disabled.
+ */
+@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
+        classes = UriTagCardinalityWebTest.App.class)
+@ExtendWith(OutputCaptureExtension.class)
+class UriTagCardinalityWebTest {
+
+    private static final int CALLS = 400;
+
+    @Autowired
+    private Environment environment;
+
+    @Autowired
+    private RestClient.Builder builder;
+
+    @Autowired
+    private MeterRegistry registry;
+
+    @Test
+    void literalOutboundUrisAreCappedAndTemplatedServerUrisStayBounded(CapturedOutput output) {
+        RestClient client = builder.baseUrl("http://localhost:" + environment.getProperty("local.server.port")).build();
+        for (int i = 0; i < CALLS; i++) {
+            client.get().uri("/orders/" + i).retrieve().toBodilessEntity();
+        }
+        for (int i = 0; i < 50; i++) {
+            // Unmatched server paths: one collapsed uri value, never the raw path.
+            client.get().uri("/missing/" + i).retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();
+        }
+
+        Set<String> clientUris = uris("http.client.requests");
+        assertThat(clientUris).isNotEmpty().hasSizeLessThanOrEqualTo(100);
+        Set<String> serverUris = uris("http.server.requests");
+        assertThat(serverUris).contains("/orders/{id}").hasSizeLessThanOrEqualTo(3)
+                .noneMatch(uri -> uri.startsWith("/missing/") || uri.matches("/orders/\\d+"));
+        // Boot's deny filter warns once when the cap is reached, not once per denied series.
+        assertThat(output.getOut().split("Reached the maximum number of", -1)).hasSize(2);
+    }
+
+    private Set<String> uris(String meter) {
+        return registry.find(meter).meters().stream()
+                .map(Meter::getId)
+                .map(id -> id.getTag("uri"))
+                .collect(Collectors.toSet());
+    }
+
+    @SpringBootConfiguration
+    @EnableAutoConfiguration
+    @Import(Orders.class)
+    static class App {
+    }
+
+    @RestController
+    static class Orders {
+
+        @GetMapping("/orders/{id}")
+        String order(@PathVariable String id) {
+            return id;
+        }
+    }
+}
diff --git a/observability/platform-starter-observability/pom.xml b/observability/platform-starter-observability/pom.xml
index 84ad229..6f2882c 100644
--- a/observability/platform-starter-observability/pom.xml
+++ b/observability/platform-starter-observability/pom.xml
@@ -46,6 +46,13 @@
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-micrometer-tracing-opentelemetry</artifactId>
     </dependency>
+    <!-- The OTLP span exporter: Boot's OtlpTracingAutoConfiguration is conditional on
+         OtlpHttpSpanExporter, so without it dc.platform.observability.otlp.enabled=true exports no
+         traces (hardening phase 2, round 1 F7). Version managed by Boot's OpenTelemetry BOM. -->
+    <dependency>
+      <groupId>io.opentelemetry</groupId>
+      <artifactId>opentelemetry-exporter-otlp</artifactId>
+    </dependency>
     <!-- Both metric registries (phase-05 spec: keep both): prometheus scrapes locally with zero
          infra; OTLP pushes to a collector once dc.platform.observability.otlp.enabled=true. -->
     <dependency>
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/application.yml b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/application.yml
index 2f5f940..fade3ae 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/application.yml
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/resources/application.yml
@@ -9,6 +9,15 @@ spring:
   application:
     name: ${artifactId}
 
+management:
+  endpoints:
+    web:
+      exposure:
+        # Platform default is health,info,prometheus; this service also exposes the platform
+        # capability report (/actuator/platform). Who may read it is decided by dc.platform.security
+        # permit-paths below (open outside prod, authenticated in prod).
+        include: health,info,prometheus,platform
+
 # Profiles: local (developer laptop, human-readable logs), test (CI slices), prod (real infrastructure).
 ---
 # Every profile EXCEPT prod (including no profile at all): offline-friendly development defaults. None of
diff --git a/tooling/platform-skill/skill/resources/property-cheatsheet.md b/tooling/platform-skill/skill/resources/property-cheatsheet.md
index 809f70d..0eda17a 100644
--- a/tooling/platform-skill/skill/resources/property-cheatsheet.md
+++ b/tooling/platform-skill/skill/resources/property-cheatsheet.md
@@ -35,6 +35,9 @@
 | `dc.platform.logging.enabled` | Boolean | `true` |
 | `dc.platform.logging.format` | ae.gov.dubaicustoms.platform.logging.autoconfigure.LoggingProperties$Format | `json` |
 | `dc.platform.logging.include-mdc` | Boolean | `true` |
+| `dc.platform.logging.mdc.include` | List<String> | `[]` |
+| `dc.platform.logging.redaction.enabled` | Boolean | `true` |
+| `dc.platform.logging.redaction.patterns` | List<String> | `[]` |
 | `dc.platform.logging.service-name` | String | `` |
 | `dc.platform.messaging.correlation.propagate` | Boolean | `true` |
 | `dc.platform.messaging.default-destination-prefix` | String | `dc.` |
```
