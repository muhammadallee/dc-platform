# Core + error handling — sample

## What it solves

Every request your service handles is tied to one identifier you can quote: the **W3C trace ID**. It
comes in with the caller's `traceparent` (or starts fresh), appears on every log line as `traceId`, and
is in every error response as `traceId`. Every error — your exceptions, Spring's own 404/405/415, bad
JSON, validation failures, security 401/403, a filter's `sendError`, an exception escaping a filter —
comes back as one RFC 9457 `application/problem+json` shape with a stable `code`, and **no 5xx body ever
contains an exception message**.

## When to use / not use

- **Use** it for every HTTP service: it is part of the archetype baseline, nothing to opt into.
- **Do not** add your own `@RestControllerAdvice` for generic exceptions or build `ProblemDetail` by
  hand; throw `BusinessException` / `NotFoundException` / `ConflictException` with an `ErrorCode`.
- **Do not** send or read `X-Correlation-Id`. It is ignored. Callers send `traceparent` (any
  OpenTelemetry/W3C client does it for you).

## Minimal real setup

Generate a service (see [`commands.md`](commands.md)) — core, errors, logging and the tracing bridge are
already there. Throw a business exception:

```java
private static final ErrorCode ORDER_NOT_FOUND = new ErrorCode("DC-ORDR-0404");

Order find(String id) {
    return repository.findById(id)
            .orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND, "order " + id + " not found"));
}
```

Call it with a trace:

```bash
curl -s -H 'Authorization: Bearer <token>' \
     -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' \
     http://localhost:8080/orders/42
```

```json
{"type":"urn:dc:problem:DC-ORDR-0404","title":"DC-ORDR-0404","status":404,
 "detail":"order 42 not found","instance":"/orders/42","code":"DC-ORDR-0404",
 "traceId":"0af7651916cd43dd8448eb211c80319c","timestamp":"2026-10-26T04:00:00Z","retryable":false}
```

and the log line for that request carries `"traceId":"0af7651916cd43dd8448eb211c80319c"`.

## Walkthrough

1. **Correlation.** Boot's observation filter continues a valid `traceparent` or starts a new trace;
   the platform filter (after it, before security) puts the trace ID into `RequestContext` and the MDC
   (`traceId`, `spanId`). Unsampled traces still have an ID, so this works at any sampling rate.
2. **Background work.** `@Async` methods and Boot-built executors get the caller's trace automatically.
   Executors you create yourself need `ContextPropagatingTaskDecorator`.
3. **Errors.** 4xx keep your message as `detail`; 5xx always say `An unexpected error occurred.` — the
   full exception is logged, on the same `traceId`. `retryable` is `true` only for 429/502/503/504.
4. **Validation.** `errors[]` lists `field` and `message`; the rejected value is **not** echoed unless
   you set `dc.platform.errors.include-rejected-values=true`.

See [`verification.md`](verification.md) for what was proven, [`operations.md`](operations.md) for
running it, [`troubleshooting.md`](troubleshooting.md) when something looks off.
