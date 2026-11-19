# Chapter 1 — Core: One Trace ID, Everywhere

> **Capabilities covered:** `core`
>
> How one identifier follows a request through every log line, error body and thread, and why your code reads it
> from Spring's tracer rather than from the platform.
>
> **Starter:** `platform-starter-core` · **Reference:** [modules/core.md](../../modules/core.md)

---

## 1. Introduction and Business Value

### The problem it solves

A customer reports a failed declaration and quotes a reference from the error page. Support needs every log line
that request produced, across three services and a background job. Without one shared identifier, that is an
afternoon of guessing by timestamp.

Core makes the W3C trace ID that identifier. It arrives in the `traceparent` header (or a new one is started), and
from then on it is in every log line, in every error body as `traceId`, and on every thread the request's work moves
to. The support reference *is* the trace ID: one search finds everything.

### Why there is nothing for you to call

Earlier versions of the platform had their own `RequestContext` and `CorrelationId` types and their own header. Both
are gone. Spring Boot already carries a trace through Micrometer Tracing, and OpenTelemetry already defines the
header. Core configures those, adds what they lack (isolation between tasks, the trace on error pages), and gets out
of the way.

### Impact of absence

| Without core | What happens |
|---|---|
| No correlation | Log lines of one request cannot be joined; support works from timestamps |
| A custom correlation header | Each hop must copy it; one forgotten hop breaks the chain |
| Trace not on the error page | The body a customer quotes cannot be found in the logs |
| No isolation on executor threads | A background task logs under another caller's trace, sending support down the wrong path |

---

## 2. Core Concepts

### 2.1 The trace ID is the correlation ID

`traceparent` looks like `00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01`: a version, a 32-hex trace ID, a
16-hex span ID and flags. Boot's observation filter reads it before anything else runs. A valid header continues the
caller's trace; a missing or invalid one starts a new trace. The platform never parses the header itself, so it
never disagrees with the propagator.

When the propagator rejects a header the platform logs a fixed marker with the header's length only:
`rejected inbound traceparent (length=37)`. The header's content never reaches a log, because a header is attacker
input.

### 2.2 How a log line gets a field you never passed

SLF4J's MDC is a per-thread map that the logging layout copies onto every line. While a request is served, the
`traceId` and `spanId` keys hold its trace. Core's servlet filter puts them there, after Boot's observation filter
(so a span exists) and before any security filter (so a 401 already carries them). When the dispatch ends, the
filter restores the MDC to exactly what it was, so the next request on that thread starts clean.

The filter runs on three kinds of dispatch:
- **REQUEST**, the normal case;
- **ASYNC**, when a `Callable` or `CompletableFuture` finishes on a later redispatch;
- **ERROR**, when a filter calls `sendError` or an exception escapes the filter chain and the container renders the
  error page. No span is in scope there, so the filter takes the trace from the observation Boot stored on the
  request.

### 2.3 Moving work to another thread, and keeping it apart

An `@Async` method runs on a pool thread that knows nothing about the request. Spring's `ContextPropagatingTaskDecorator`
fixes that: when the task is submitted it captures the caller's context (the current observation, the MDC entries),
and when it runs it applies them, then restores the pool thread's own.

The subtle case is a task captured with **no** context: a scheduled job, or work submitted from a background thread.
If it runs on a thread that is busy serving caller A (a caller-runs rejection policy does exactly this), it must not
see A's trace. Boot's default decorator skips context it did not capture, so the task silently inherits A's span. The
platform's decorator is built to clear what was not captured, and the task runs with no trace at all.

### 2.4 Reading the trace in code

You rarely need to. When you do, the trace is a property of the current span:

```java snippet:book-01-read-trace
@Service
class SupportReference {

    private final Tracer tracer;

    SupportReference(Tracer tracer) {
        this.tracer = tracer;
    }

    String current() {
        Span span = tracer.currentSpan();
        return span == null ? "none" : span.context().traceId();
    }
}
```

---

## 3. Feature Reference

### 3.1 What core configures

| Piece | Spring type | Notes |
|---|---|---|
| Correlation filter | `FilterRegistrationBean<CorrelationIdFilter>` | Order `HIGHEST_PRECEDENCE + 10`; REQUEST, ASYNC and ERROR dispatches |
| Task decorator | `ContextPropagatingTaskDecorator` (bean `platformContextPropagatingTaskDecorator`) | Snapshot factory with `clearMissing(true)`; Boot's executors use it |
| MDC propagation | a Micrometer `ThreadLocalAccessor`, registered through `META-INF/services` | Captures "no context" explicitly, so the MDC is isolated even under a decorator that does not clear |
| Tracer | Micrometer Tracing with the OpenTelemetry bridge, from the starter | No exporter; the observability starter adds one |
| Banner | one `ApplicationRunner` | `platform: core[ACTIVE], errors[ACTIVE] (rfc9457)` |

### 3.2 Configuration properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.core.enabled` | `true` | Kill switch for the whole capability |
| `dc.platform.core.banner-enabled` | `true` | Log the startup banner |

### 3.3 Extension points

All Spring: your own `TaskDecorator` bean, your own filter registration, and Boot's `management.tracing.*`
properties. The [module page](../../modules/core.md) lists what each one costs you.

---

## 4. How-to Guide

### 4.1 Add the capability

It is already there: every generated service has `platform-starter-core`.

### 4.2 Check correlation end to end

```bash
curl -s -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' localhost:8080/does-not-exist
```

The body's `traceId` is `0af7651916cd43dd8448eb211c80319c`, and so is the `traceId` field of the request's log lines.
Without the header the service starts a trace of its own, and the body and the log still agree.

### 4.3 Hand work to another thread

`@Async` methods and Boot-built executors need nothing. An executor you build yourself does:

```java snippet:book-01-own-executor
@Configuration
class ReportExecutorConfiguration {

    @Bean
    ThreadPoolTaskExecutor reportExecutor(TaskDecorator decorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setTaskDecorator(decorator);
        return executor;
    }
}
```

`TaskDecorator` here is the platform's bean, so the executor gets the same isolation.

!!! warning "Two decorators means none"
    Boot's executor builder applies a `TaskDecorator` only when exactly one bean exists. Declaring a second one, or
    setting `spring.task.execution.propagate-context=true` next to your own, switches propagation off for Boot's
    executors without a warning.

---

## 5. Operations

- **The banner** is the quickest check that platform auto-configuration ran: no banner, no platform.
- **A trace that does not continue** across a hop: turn on DEBUG for `ae.gov.dubaicustoms.platform.core` and look for
  `rejected inbound traceparent`. The usual cause is a gateway that rewrites or truncates the header.
- **No `traceId` in error bodies**: tracing is off or absent. Core never invents an ID.
- **Sampling** does not matter: an unsampled span still has a trace ID.

---

## 6. Deep Dive: why not `RequestContextHolder`?

Spring's `RequestContextHolder` holds the current *web request's* attributes. A RabbitMQ listener or the outbox
drainer has no web request, so it could never carry the trace there. The trace belongs to the observation, which
Micrometer already propagates; the MDC copy is for log lines. That is why both the old `RequestContext` type and
`RequestContextHolder` are the wrong home for it.

---

## 7. Exercise

Generate a service, add an `@Async` method that logs a line, call an endpoint that invokes it with a `traceparent`,
and check that the pool thread's log line has the request's `traceId`. Then call it without the header and confirm
the trace is new but still shared by both lines.

---

## 8. Checklist

- [ ] Callers send `traceparent`; nothing sends a custom correlation header.
- [ ] Code that needs the trace asks `Tracer`, and never logs it explicitly.
- [ ] Executors you build use the application's `TaskDecorator`.
- [ ] No second `TaskDecorator` bean.

---

**Next:** [Chapter 2 — Errors and Validation](02-errors-validation.md), which puts this trace ID into every error
body.

**Reference:** [modules/core.md](../../modules/core.md) ·
[configuration properties](../../reference/properties.md)

[Back to the book](../index.md)
