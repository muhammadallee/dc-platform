# Verification — what was proven, at which level

Platform commit `e2be16d`. Evidence: [`../../production-readiness/phase-01-core-errors/evidence/`](../../production-readiness/phase-01-core-errors/evidence/).

| # | Expected | Actual | Level |
|---|---|---|---|
| AC1 | Valid `traceparent` continued; `traceId` in RequestContext, MDC, `@Async`, ProblemDetail and the request's own log line | As expected; same-request body ↔ log event asserted | SLICE (real tracing, embedded server) + CONSUMER |
| AC2 | Missing `traceparent` → new 32-hex trace in body and log | As expected (continued and fresh) | SLICE + CONSUMER |
| AC3 | Rejected `traceparent` → new trace, exactly one length-only marker per request, raw value never logged | As expected for malformed, oversized and all-zero values; CR/LF/control characters are rejected by Tomcat before the app (unit-level only, amendment A2) | UNIT + CONSUMER |
| AC4 | Stray `X-Correlation-Id` ignored, never echoed or sent | As expected | SLICE + CONSUMER |
| AC5 | Platform REST client sends `traceparent` with the same trace, no custom header | As expected | SLICE + CONSUMER |
| AC6 | Sampling 0.0 keeps the trace ID in context, MDC and error bodies | As expected | SLICE + CONSUMER |
| AC7 | `@Async` sees the caller's trace; a context-free task never inherits the executing caller's context (Boot's default decorator) | As expected | UNIT + SLICE + CONSUMER |
| AC8 | 200 parallel requests × 3, zero bleed | Consumer: 600 context calls + 600 error bodies per run, 0 bleed, 3 consecutive runs; slice: 200 × 3 incl. async MDC; errors slice 200 × 3 bodies | SLICE + CONSUMER |
| AC9 | MDC empty after every REQUEST, ASYNC and ERROR dispatch and on pool threads | As expected (bounded wait for each dispatch's exit record) | SLICE |
| AC10 | Malformed JSON → 400 `DC-CORE-0410`, no parser internals | As expected | SLICE + CONSUMER |
| AC11 | Validation → 400 `DC-CORE-0400`, field paths, no `rejectedValue` (body, query parameter, native MVC method validation) | As expected | SLICE + CONSUMER |
| AC12 | Unknown exception and 5xx `PlatformException` → generic 500; stack trace in the log on the request's trace | As expected; framework 5xx (async timeout 503, conversion failure) logged once with the throwable | SLICE + CONSUMER |
| AC13 | 401 (missing, malformed, expired, untrusted-key token) and 403 carry the full contract, no rejection reason | As expected against a real JWKS issuer | SLICE + CONSUMER |
| AC14 | `retryable` true only for 429/502/503/504 | As expected | UNIT |
| AC15 | Removed properties deprecated (level `error`, reason) and without effect | As expected; no `replacement` (no successor — amendment A1, accepted D100) | UNIT |
| AC16 | Override by documented mechanism; config-default snapshots; kill switches | As expected; filter registration and banner by type, descriptors by name (D97/D98); unrelated beans do not suppress the platform's; registration override checked in a started container | SLICE |
| AC17 | Downstream `-amd test-compile` incl. Kafka/audit; golden path | 86 modules exit 0; golden path 8/8 (`golden-path-summary-76cfcc7.tsv`) | build |
| AC18 | SIGTERM with an in-flight 6 s request: 200, exit 143 within grace | As expected (bounded wait for handler entry; shutdown 7 s) | CONSUMER |

## Limitations

- No ENVIRONMENT-level evidence (OpenShift) — Phase 8.
- The logging, observability, security, openapi, validation and restclient starters were present as
  fixtures; they are certified in their own phases.
