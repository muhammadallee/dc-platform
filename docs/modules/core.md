# Core

Every generated service has it. Core makes one ID follow a request everywhere: the W3C trace ID. It is in every log
line, in every error body, and on every thread the request's work runs on. Your code reads it from Micrometer's
`Tracer`. There is no platform type to learn.

## What the platform configures

- **Correlation is the trace ID.** A request with a valid `traceparent` continues that trace; a missing or invalid one
  starts a new trace. Boot's observation filter and the OpenTelemetry W3C propagator decide that; the platform never
  parses the header. No custom correlation header is read, generated, echoed or sent: a caller's `X-Correlation-Id`
  is ignored.
- **The trace ID is in the MDC on every dispatch.** A servlet filter puts `traceId` and `spanId` into the SLF4J MDC for
  the request, its ASYNC redispatch (a `Callable` or `CompletableFuture` that finishes later) and its ERROR dispatch
  (`sendError`, an exception escaping the filter chain), and restores the MDC exactly afterwards. On an ERROR
  dispatch no span is in scope, so it takes the trace from the observation Boot stored on the request.
- **A rejected `traceparent` leaves a marker, not a value.** When the propagator did not continue the header's trace,
  the filter logs `rejected inbound traceparent (length=N)` at DEBUG. No header content ever reaches a log line.
- **Unsampled traces still correlate.** An unsampled span has a valid trace ID, so correlation does not depend on
  `management.tracing.sampling.probability`.
- **Work on other threads keeps the trace, and only its own.** The platform supplies Spring's
  `ContextPropagatingTaskDecorator` as the application's `TaskDecorator`. Boot's task executors (`@Async`, async MVC,
  any executor built with Boot's builders) use it, so a task sees the caller's trace in the `Tracer` and the MDC. Its
  snapshot factory clears missing values: a task captured with no trace runs with none, even on a thread that is
  serving another caller. Boot's own decorator does not clear them.
- **A tracer is always there.** The starter brings the Micrometer Tracing OpenTelemetry bridge with no exporter; spans
  are shipped only when the observability starter adds one. Without a tracer the platform never invents an ID:
  requests run without a correlation context and error bodies have no `traceId`.
- **One banner line at startup** lists the active capabilities: `platform: core[ACTIVE], errors[ACTIVE] (rfc9457)`.

## Read the trace in your code

You rarely need to: logs, error bodies, outbound calls and messages carry it already. When you do, ask the tracer:

```java snippet:core-read-trace
@Service
class InvoiceArchiver {

    private final Tracer tracer;

    InvoiceArchiver(Tracer tracer) {
        this.tracer = tracer;
    }

    String supportReference() {
        Span span = tracer.currentSpan();
        return span == null ? "none" : span.context().traceId();
    }
}
```

Do not add the trace ID to a log message: it is already a field on the line.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-core</artifactId>
</dependency>
```

## Zero-config behaviour

| With only the starter | You set nothing for it |
|---|---|
| Inbound `traceparent` continued; a new trace otherwise | ✓ |
| `traceId` and `spanId` in the MDC on REQUEST, ASYNC and ERROR dispatches | ✓ (servlet apps) |
| The trace follows `@Async` and Boot-built executors, isolated per task | ✓ |
| Startup banner | ✓ |
| Spans exported somewhere | ✗ add the observability starter |

## Change it with Spring

| To change | Use |
|---|---|
| How executors propagate context | Declare your own `TaskDecorator` bean. The platform's backs off. Setting `spring.task.execution.propagate-context=true` also makes it back off, to Boot's decorator, which does **not** isolate a context-free task |
| An executor you build yourself | `executor.setTaskDecorator(decorator)` with the application's `TaskDecorator` bean, or `ContextSnapshotFactory.builder().clearMissing(true).build().captureAll().wrap(task)` |
| The trace itself (sampling, propagation format) | Boot's `management.tracing.*` properties |
| The correlation filter's registration | Declare a `FilterRegistrationBean<CorrelationIdFilter>`, or any `CorrelationIdFilter` bean. Keep it after the observation filter (`HIGHEST_PRECEDENCE + 1`), or there is no span yet |

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.core.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.core.banner-enabled` | `true` | Log the startup capability banner. |

Removed (deprecated, level `error`, no effect): `dc.platform.core.correlation.header-name`,
`dc.platform.core.correlation.generate-if-missing`.

## Back-off limits

- **Your own `TaskDecorator`** replaces the platform's. If it does not clear missing values, a context-free task run on
  a thread serving another caller sees that caller's span through `Tracer`. The MDC stays isolated either way: the
  platform's context-propagation accessor captures "no context" explicitly.
- **Two `TaskDecorator` beans** mean Boot's executor builder uses neither. The platform never adds a second one.
- **Your own filter registration** keeps the platform's position only if you give it the same order
  (`CoreContextAutoConfiguration.FILTER_ORDER`) and the REQUEST, ASYNC and ERROR dispatcher types.
- `dc.platform.core.enabled=false` switches correlation, isolation and the banner off. Tracing itself keeps working.

## Error codes

None.

## Testing

Send a `traceparent` (`00-<32 hex trace id>-<16 hex span id>-01`) and assert the same trace ID in the response's
`traceId` or the JSON log's `traceId`. The archetype's tests do exactly that.

## Local dev notes

Nothing to run: core is in-process. The banner is the quickest check that platform auto-configuration ran at all.
