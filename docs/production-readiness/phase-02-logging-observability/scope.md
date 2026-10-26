# Phase 2 — Logging + Observability: scope

| Item | Value |
|---|---|
| Phase start commit | `a33168d` (tag `hardening/phase-01-closed`) |
| Capability under test | Logging (JSON logs, MDC, redaction, injection) + Observability (tracing/OTLP, metrics/Prometheus, health groups, actuator exposure) |
| Certified dependencies | core, errors (Phase 1) |
| Fixtures (not certified here) | DB (plan §8 Gate F), security, openapi, validation (archetype baseline, STATE.md Gate F resolution) |
| Phase 0 gaps | G2-01 … G2-08, GX-04 (logging/observability descriptors) |

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
   - the default PII list: Emirates ID (`784-YYYY-NNNNNNN-C`, with or without dashes) and payment card
     numbers (13–19 digits passing Luhn, bounded by non-digits). Decision to log: **D102** (default list;
     services extend it).
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

## 7. Reproducing current behaviour (baseline `a33168d`)

- `PlatformObservabilityEnvironmentPostProcessor.java:41-49`: exposure `health,info,platform,metrics,prometheus`;
  readiness includes `db,rabbit,redis` → DB outage marks every replica unready.
- `logback-platform.xml`: `LogstashEncoder` with `includeMdc=true` (whole MDC) and no masking — a
  `log.info("auth {}", "Bearer eyJ…")` line is written verbatim.
- `LogSanitizer`: no code applies it (grep: only its own declaration, `Kv`, package-info).
- `PlatformInfoEndpointAutoConfiguration.observabilityCapabilityDescriptor`: no back-off guard.
- `SecurityProperties.permitPaths`: `/actuator/prometheus` not public under the security fixture.

## 8. Baseline targeted tests (Gate A, before any change)

`mvn -B -T1 -pl logging/platform-logging-api,logging/platform-logging-autoconfigure,observability/platform-observability-autoconfigure -am verify`
→ exit 0, BUILD SUCCESS, 7 reactor modules; tests: logging-api 5, logging-autoconfigure 19,
observability-autoconfigure 28 (build-tools 38, core-api 37), 0 failures, 0 errors, **0 skipped**
(raw log `evidence-raw/gate-a-baseline-tests.log`, sha256 `4302e1a4…3b82`, not committed).

## 9. Decisions to record (decision log, at Gate B)

| ID | Decision |
|---|---|
| D101 | Platform emits only `traceId`/`spanId` to MDC; consumer MDC passes through masked; strict allow-list opt-in (`dc.platform.logging.mdc.include`) |
| D105 | Chassis exposure `health,info,prometheus`; archetype opts in `platform`; Prometheus scrape authenticated until Phase 7 |
| D106 | URI tag cardinality: rely on Boot's `max-uri-tags` (100) for server and client meters; no platform `uri` filter |
| D102 | Default redaction set: credential patterns + key names + Emirates ID + Luhn PAN; extendable |
| D103 | Sampling: head-based Boot default; errored-request retention via collector tail sampling (OPEN, deployment) |
| D104 | Readiness pod-local; shared dependencies in health group `dependencies` (implements D6) |
