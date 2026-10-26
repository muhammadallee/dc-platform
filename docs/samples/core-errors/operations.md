# Operations — core + error handling

## Posture

| Dependency / state | Behaviour | What the request returns |
|---|---|---|
| None (in-process capability) | — | — |
| Tracing disabled (`management.tracing.enabled=false`) or bridge removed | No correlation context; nothing invented | Normal responses; error bodies without `traceId` |
| Invalid inbound `traceparent` | New trace; DEBUG marker `rejected inbound traceparent (length=N)` | Normal response; the new trace ID in error bodies |
| Unsampled trace (sampling 0.0) | Trace ID still present | `traceId` present |

Core has no backing service, so the §5.4 degradation states (slow / erroring / unavailable) do not apply.

## Kill-switch tier

| Switch | Tier | Effect of `false` |
|---|---|---|
| `dc.platform.core.enabled` | `DEFAULT_ON` | No platform correlation filter or executor propagation default; tracing still runs; errors lose `traceId` |
| `dc.platform.errors.enabled` | `DEFAULT_ON` | Spring's default error bodies (no `code`/`traceId`, framework messages); no platform error page |

No break-glass property: neither capability is `MANDATORY`.

## Health

Core and errors contribute no health indicators (nothing external to check). Health groups are a Phase 2
concern.

## What to watch

| Signal | Where | Why |
|---|---|---|
| `"level":"ERROR"` with `"logger":"…PlatformExceptionHandler"` | logs | every unexpected exception, with `traceId` and stack trace |
| `servlet error dispatch [DC-CORE-0500]` | logs | exceptions escaping the filter chain |
| HTTP 5xx rate by `code` | access logs / metrics | `DC-CORE-0500` spikes are unmapped failures |
| DEBUG `rejected inbound traceparent` (temporarily) | logs | a caller sends a broken `traceparent` |

Never alert on a trace ID or use it as a metric tag (unbounded cardinality).

## Graceful shutdown

On SIGTERM the app stops accepting connections, finishes in-flight requests within
`spring.lifecycle.timeout-per-shutdown-phase` (default 30 s) and exits with code 143. Verified: a 6 s
request in flight across `docker stop` completed with 200; shutdown took 7 s. Set the orchestrator's
termination grace above the phase timeout (the harness uses 45 s).

## How to replace a bean

| Bean | Replace by |
|---|---|
| `CorrelationIdFilter` | your own `CorrelationIdFilter` bean (keep it after the observation filter) |
| filter registration | bean named `platformCorrelationFilterRegistration` (the platform then creates no filter) |
| main advice | a `PlatformExceptionHandler` subclass bean, or any bean named `platformExceptionHandler` |
| validation advice | a `PlatformConstraintViolationHandler` bean, or any bean named `platformValidationExceptionHandler` |
| error page | any `ErrorController` bean |
| problem body additions | `ProblemDetailCustomizer` beans (`@Order`), `source` is never null |
