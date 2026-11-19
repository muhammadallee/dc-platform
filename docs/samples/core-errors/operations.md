# Operations — core + errors

## Posture

| Dependency or state | Behaviour | What the request gets |
|---|---|---|
| None (in-process capability) | — | — |
| Tracing disabled or the bridge removed | No correlation context; nothing invented | Normal responses; error bodies without `traceId` |
| Invalid inbound `traceparent` | New trace; DEBUG marker `rejected inbound traceparent (length=N)` | Normal response; the new trace ID in error bodies |
| Unsampled trace (sampling 0.0) | Trace ID still present | `traceId` present |
| Client gone, or the response already committed | Nothing more is written | What was already sent |

Core and errors have no backing service, so the slow, erroring and unavailable states do not apply. Data-access
failures are mapped (409/503) but belong to the data capability's posture.

## Kill switches

| Switch | Tier | Effect of `false` |
|---|---|---|
| `dc.platform.core.enabled` | `DEFAULT_ON` | No correlation filter, no task decorator, no banner; tracing still runs |
| `dc.platform.errors.enabled` | `DEFAULT_ON` | Boot's default error handling: no `code`, `traceId`, `timestamp`, `retryable`; no platform error page |

Neither is `MANDATORY`, so there is no break-glass property.

## Health

No health indicators: nothing external to check.

## What to watch

| Signal | Where | Why |
|---|---|---|
| `"level":"ERROR"` from `…PlatformExceptionHandler` | logs | every unexpected exception and every 5xx, with `traceId` and stack trace |
| `servlet error dispatch [DC-CORE-0500]` | logs | exceptions escaping the filter chain |
| `error-response interceptor … failed` (WARN) | logs | a service interceptor threw on the error page |
| HTTP 5xx rate by `code` | access logs, metrics | `DC-CORE-0500` and `DC-DATA-0503` spikes |
| DEBUG `rejected inbound traceparent`, temporarily | logs | a caller sends a broken `traceparent` |

Never alert on a trace ID or use it as a metric tag (unbounded cardinality).

## Graceful shutdown

On SIGTERM the app stops accepting connections, finishes in-flight requests within
`spring.lifecycle.timeout-per-shutdown-phase` (default 30 s) and exits with code 143. Verified in each consumer run: a
6 s request in flight across `docker stop` completed with 200. Set the orchestrator's termination grace above the
phase timeout.

## How to change or replace a bean (Spring types only)

| To change | Use |
|---|---|
| A field on every error body | `ErrorResponse.Interceptor` through `WebMvcConfigurer.addErrorResponseInterceptors` |
| Your own exception's handling | your own `@RestControllerAdvice` (it runs first) |
| The servlet error page | any `ErrorController` bean |
| Executor propagation | your own `TaskDecorator` bean (the platform's backs off) |
| The correlation filter | a bean named `platformCorrelationFilterRegistration`, for example a `FilterRegistrationBean` of your own filter |
| What the banner and `/actuator/platform` report | `dc.platform.core.capabilities.<name>.status` and `.detail` |
| The banner itself | `dc.platform.core.banner-enabled=false` |
