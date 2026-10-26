# Phase 1 — Core + Error Handling: scope

| Item | Value |
|---|---|
| Phase start commit | `f231689` (tag `hardening/phase-00-closed`) |
| Capability under test | Core (request context, correlation) + Errors (ProblemDetail contract) |
| Fixtures (not certified here) | logging, observability (tracing bridge), security, openapi, validation, restclient — archetype baseline + `restclient` feature for the outbound check (STATE.md, Gate F resolution) |

## 1. Requirements

### R1 Correlation = W3C trace ID only (HD5)
1. The trace ID of the current span is the only correlation identifier. Inbound: a valid
   `traceparent` continues that trace; missing or invalid starts a new trace. Parsing and propagation are
   done by the tracing bridge (Micrometer Tracing + OpenTelemetry W3C propagator), not by platform code.
2. A present-but-invalid `traceparent` (bad format, > 55 chars for version `00`, any control character)
   is never logged verbatim; the platform logs a sanitized, length-capped marker at DEBUG.
3. `X-Correlation-Id` is neither read, generated, echoed, nor propagated anywhere (core filter, REST
   client, tracing baggage). A caller-sent `X-Correlation-Id` is ignored.
4. `CorrelationId` (public, `@API STABLE`) and `RequestContext.correlationId()` are kept; the value is
   the current trace ID (W3C trace-id is 32 lowercase hex, which the existing validation already
   enforces). No public API is removed in this phase.
5. The platform request filter runs **after** Boot's `ServerHttpObservationFilter`
   (`HIGHEST_PRECEDENCE + 1`) and **before** the Spring Security filter chain, so a span exists when it
   reads the trace ID and 401/403 responses carry it.
6. Works at any sampling probability, including `0.0` (unsampled spans still carry a valid trace ID).

### R2 Context propagation (§7.6)
1. Request context (trace ID) and MDC survive `@Async` and the Boot-built `applicationTaskExecutor`
   (Boot's `spring.task.execution.propagate-context` turned on by platform default + a
   `ThreadLocalAccessor` for `RequestContext`).
2. MDC carries `traceId` and `spanId` on every request-scoped line; the platform no longer publishes a
   `correlationId` MDC key. MDC is empty after each request on both the request thread and pool threads.
3. ≥ 200 concurrent requests, each with its own `traceparent`: zero bleed of trace ID between requests in
   response, MDC, async thread, or ProblemDetail.

### R3 Error contract (§7.7)
1. Every error response is RFC 9457 `application/problem+json` with `type`, `title`, `status`,
   `detail`, `instance`, stable `code`, `traceId`, `timestamp`. The `correlationId` property is replaced
   by `traceId` (consumer-visible change).
2. 5xx responses never carry exception messages: unknown exceptions and `PlatformException`s with
   status ≥ 500 return a generic `detail`; full detail is logged with the trace ID.
3. Validation errors list field paths and messages; `rejectedValue` is **omitted by default**
   (`dc.platform.errors.include-rejected-values=false`), and when enabled stays redacted for
   secret-like names.
4. Malformed JSON → 400 ProblemDetail with a stable code and no parser internals (no Jackson message,
   no class names).
5. 401/403 bodies from the security entry points carry `code` and `traceId` too (body shape only — JWT
   semantics are Phase 7).
6. `retryable` (boolean) on every ProblemDetail: `true` for 429/502/503/504, `false` otherwise.
   `retryAfter` is deferred to Phase 6 (it needs the REST client's `Retry-After` handling).
7. Error codes are unique across all modules at build time.

### R4 Configuration and override contracts (§7.1–7.3)
1. Removed properties keep deprecation metadata (`level: error`, reason, replacement):
   `dc.platform.core.correlation.header-name`, `dc.platform.core.correlation.generate-if-missing`,
   `dc.platform.restclient.propagate-correlation`.
2. Config-default snapshot tests for `dc.platform.core.*` and `dc.platform.errors.*`.
3. Override tests: a consumer bean of each platform `@Bean` type in core and errors wins.
4. Kill-switch tier: core `DEFAULT_ON`, errors `DEFAULT_ON`; `enabled=false` behaviour tested.

## 2. Exclusions (and where they are handled)

| Item | Phase |
|---|---|
| Messaging header propagation (`correlationId` header in `DefaultEventPublisher` / `EventHandlerRegistrar`) → `traceparent` | 5 (G5-07). In Phase 1 its value becomes the trace ID automatically. |
| JSON log redaction, metric cardinality guard, health groups, actuator exposure | 2 |
| JPA `CorrelationIdConverter`, audit `correlationId` fields | 3 / not certified (audit). Type kept, so they compile and pass unchanged. |
| JWT validation semantics, actuator authentication | 7 |
| `retryAfter`, REST retry classification, error-body bounding | 6 |
| `type` base URI (`https://errors.dc.com/`, a domain the platform does not own, G1-08) | **OPEN — owner to supply the canonical URI**; unchanged in Phase 1 |

## 3. Correlation surface (every file mentioning correlation, by phase)

Changed in Phase 1: `core/platform-core-api` (`CorrelationId`, `RequestContext` javadoc/MDC key),
`core/platform-core-autoconfigure` (`CoreProperties`, `CorrelationIdFilter`, `CoreContextAutoConfiguration`),
`errors/platform-errors-autoconfigure` (`ProblemDetailFactory`, `PlatformExceptionHandler`),
`security/platform-security-autoconfigure` (`ProblemResponses`), `restclient/*` (`DefaultPlatformRestClientFactory`,
`RestClientProperties`, `RemoteCallException.remoteCorrelationId` deprecated),
`observability/platform-observability-autoconfigure` (baggage default removed),
`logging/platform-logging-autoconfigure` (`logback-platform.xml` comment, `LoggingProperties` doc),
`test/platform-test-api` (`ProblemDetailAssert`), archetype resources (`HttpIntegrationTestSupport`,
`StructuredLoggingTest`, `GreetingClientTest`, `GreetingClient`, `Application`, `HelloService`, README/AGENTS/CLAUDE),
`examples/example-minimal`, `tooling/scripts/golden-path.sh` + `support/GateSupport.java` (correlation echo probe → traceparent check),
docs (`docs/modules/core.md`, `errors.md`, `restclient.md`, `logging.md`, `observability.md`, `reference/properties.md` via generator, book chapters 01/02/03/06, quickstart, testing).

Unchanged in Phase 1 (later phase or out of scope): messaging api/autoconfigure (5), data JPA converter (3),
audit modules and TCK (not certified), openapi `ProblemDetailOpenApiCustomizer` (schema property rename only if tests require),
`specs/**` (historical record, never edited), examples `event-driven`, `golden-path` (follow messaging in 5).

## 4. Applicable §7 contracts

| § | Contract | Phase 1 position |
|---|---|---|
| 7.1 | Real override | tests per `@Bean` in core + errors |
| 7.2 | Config-default snapshot | core + errors |
| 7.3 | Kill-switch tier | both `DEFAULT_ON`; `enabled=false` tested |
| 7.4 | Outage posture | N/A — no backing dependency (recorded) |
| 7.5 | Health semantics | N/A (Phase 2) |
| 7.6 | Context propagation | R2 |
| 7.7 | Error surface | R3 |
| 7.8 | Redaction | ProblemDetail: no messages on 5xx, rejected values omitted; invalid `traceparent` sanitized in logs |
| 7.9 | Observability | trace ID never used as a metric tag (asserted) |
| 7.10 | Graceful shutdown | consumer harness: in-flight slow request across SIGTERM completes |
| 7.11 | Startup under adversity | N/A dependency; startup time recorded |

## 5. Acceptance criteria

| ID | Criterion | Level |
|---|---|---|
| AC1 | Valid `traceparent` → response ProblemDetail `traceId` and log MDC `traceId` equal the inbound trace ID | SLICE + CONSUMER |
| AC2 | Missing `traceparent` → a new 32-hex trace ID, present in ProblemDetail and MDC | SLICE + CONSUMER |
| AC3 | Malformed / oversized / control-character `traceparent` → new trace; raw value absent from all logs; sanitized marker present at DEBUG | UNIT + CONSUMER |
| AC4 | `X-Correlation-Id` request header is ignored (no echo, trace ID unaffected, absent from outbound requests) | SLICE + CONSUMER |
| AC5 | Outbound REST call carries `traceparent` with the same trace ID and no `X-Correlation-Id` | SLICE + CONSUMER |
| AC6 | `traceId` present in ProblemDetail and MDC with `management.tracing.sampling.probability=0.0` | CONSUMER |
| AC7 | `@Async` method sees the caller's trace ID in `RequestContext` and MDC | SLICE + CONSUMER |
| AC8 | 200 parallel requests: zero trace-ID bleed (response, MDC, async, ProblemDetail); 3 consecutive passes | SLICE + CONSUMER |
| AC9 | MDC empty after each request on request and pool threads | UNIT/SLICE |
| AC10 | Malformed JSON → 400, stable code, no parser internals | SLICE + CONSUMER |
| AC11 | Validation error → 400 with field paths, no `rejectedValue` by default | SLICE + CONSUMER |
| AC12 | Unknown exception and 5xx `PlatformException` → 500 generic detail; logs carry the stack trace and trace ID | SLICE + CONSUMER |
| AC13 | 401/403 ProblemDetail carries `code` and `traceId` | SLICE + CONSUMER |
| AC14 | `retryable` correct for 4xx/5xx/429/503 | UNIT |
| AC15 | Deprecated properties present in metadata with `level=error`; setting them has no effect | UNIT |
| AC16 | Config-default snapshot, override and kill-switch tests pass | SLICE |
| AC17 | Downstream compile check (`-amd test-compile`) green incl. Kafka/audit; golden-path gate green | build |
| AC18 | SIGTERM with an in-flight slow request: the request completes with 200, shutdown completes, exit 143 within grace | CONSUMER |

## 6. Environment prerequisites

JDK 25, Maven 3.9, Docker (consumer harness only), images `eclipse-temurin:25-jre`. No backing infrastructure.

## 7. Reproducing current behaviour (baseline `f231689`)

- `curl -H 'X-Correlation-Id: 0123456789abcdef0123456789abcdef' …` → echoed header; ProblemDetail `correlationId`.
- Platform filter at `Ordered.HIGHEST_PRECEDENCE` runs before Boot's observation filter (Boot 4.1
  `WebMvcObservationAutoConfiguration` sets `HIGHEST_PRECEDENCE + 1`), so it cannot see a span.
- 401 body: `{"status":401,"title":"Unauthorized","detail":"Full authentication is required…"}` — no `code`, no trace ID
  (Phase 0 harness evidence `http/unauthenticated.body`).

## 8. Baseline targeted tests (Gate A, before any change)

`mvn -B -T1 -pl core/platform-core-api,core/platform-core-autoconfigure,errors/platform-errors-api,errors/platform-errors-autoconfigure,restclient/platform-restclient-autoconfigure,observability/platform-observability-autoconfigure,logging/platform-logging-autoconfigure -am verify`
→ exit 0, BUILD SUCCESS, 12 modules, 0 failures, 0 errors, **0 skipped** (raw log
`evidence-raw/gate-a-baseline-tests.log`, not committed).
