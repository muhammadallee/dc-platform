# Verification — what was proven, at which level

Spring-native Phase 2, platform commit `b3bcc7c`. Consumer runs: three in a row, **89 assertions each, 0 failed**
(floor 77), on a fresh repository with no removed module
([`../../spring-native/phase-02-foundation/evidence/`](../../spring-native/phase-02-foundation/evidence/)).

| # | Expected | Actual | Level |
|---|---|---|---|
| AC1 | Valid `traceparent` continued; the trace in the `Tracer`, the MDC, `@Async` and the error body | As expected | SLICE + CONSUMER |
| AC2 | No `traceparent` → a new 32-hex trace, the same in the body and the request's log line | As expected | SLICE + CONSUMER |
| AC3 | A rejected `traceparent` → new trace, one length-only marker, the raw value in no log | As expected for malformed, oversized and all-zero values | UNIT + CONSUMER |
| AC4 | A stray `X-Correlation-Id` ignored, never echoed or sent | As expected | SLICE + CONSUMER |
| AC5 | An instrumented `RestClient` sends `traceparent` with the same trace, no custom header | As expected | CONSUMER |
| AC6 | Sampling 0.0 keeps the trace ID in context, MDC and bodies | As expected | SLICE + CONSUMER |
| AC7 | `@Async` sees the caller's trace; a context-free task sees none, in the MDC and in `Tracer` | As expected; Boot's own decorator fails the `Tracer` half, the platform's does not | UNIT + SLICE + CONSUMER |
| AC8 | 200 parallel requests × 3, no bleed; a shared exception instance never carries one request's trace into another | As expected | SLICE + CONSUMER |
| AC9 | MDC empty after every dispatch and on pool threads; out-of-order closes unwind exactly | As expected | UNIT + SLICE |
| AC10 | Malformed JSON → 400 `DC-CORE-0410`, no parser internals | As expected | SLICE + CONSUMER |
| AC11 | Validation → 400 `DC-CORE-0400`, field paths, no `rejectedValue` | As expected | SLICE + CONSUMER |
| AC12 | Unexpected exception and a service 5xx → generic detail; the stack trace in the log on the request's trace | As expected | SLICE + CONSUMER |
| AC14 | `retryable` true only for 429, 502, 503, 504 | As expected | UNIT |
| AC15 | Removed properties deprecated at level `error`, without effect | As expected | UNIT |
| AC16 | Default snapshots, kill switches, overrides | As expected; overrides need no platform type | SLICE |
| AC18 | SIGTERM with a 6 s request in flight: 200, exit 143 within grace | As expected | CONSUMER |
| AC19 | A service's `ErrorResponse.Interceptor` sees the body and the originating exception on every path | As expected on eleven paths, two interceptors in order | SLICE + CONSUMER |
| AC20 | A service's `code` and 4xx detail unchanged; a 5xx detail replaced | As expected; status, reserved names and URI-unsafe codes also covered | SLICE + CONSUMER |
| AC21 | `@ResponseStatus(CONFLICT)` → 409 | As expected (it was a 500) | SLICE + CONSUMER |
| AC22 | Only the starters, no properties: the contract holds | As expected | SLICE |
| AC23 | The service and the probe import no platform type | As expected | CONSUMER |

AC13 (security) went with JWT security in Phase 1. AC17 (downstream build, golden path) is in the phase closure.

## Limitations

- No ENVIRONMENT-level evidence (OpenShift).
- Logging and observability were present as fixtures; they are certified in Phase 3.
