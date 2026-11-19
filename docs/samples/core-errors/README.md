# Core + errors — sample

## What it solves

Every request is tied to one identifier you can quote: the **W3C trace ID**. It comes in with the caller's
`traceparent` (or starts fresh), is on every log line as `traceId`, and is in every error body as `traceId`. Every
error — your own, Spring's 404/405/415, bad JSON, validation failures, a filter's `sendError`, an exception escaping a
filter — comes back as one RFC 9457 `application/problem+json` shape with a stable `code`, and **no 5xx body ever
contains an exception message**. Your code uses Spring types only.

## When to use / not use

- **Use** it for every HTTP service: it is in the archetype, nothing to opt into.
- **Do not** write an advice that builds bodies the platform already builds. Throw Spring's `ErrorResponseException`
  with your `code`; add a field to every body with Spring's `ErrorResponse.Interceptor`.
- **Do not** send or read `X-Correlation-Id`: it is ignored. Callers send `traceparent`.

## Minimal real setup

Generate a service ([`commands.md`](commands.md), or the [first-service lab](../../labs/first-service/index.md)): core,
errors, logging and the tracing bridge are already there. Throw a business error:

```java snippet:sample-core-errors-not-found
@Service
class OrderLookup {

    static final String ORDER_NOT_FOUND = "ORD-0404";

    String find(String id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "order " + id + " not found");
        problem.setProperty("code", ORDER_NOT_FOUND);
        throw new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
```

Call it with a trace:

```bash
curl -s -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' http://localhost:8080/orders/42
```

```json
{"detail":"order 42 not found","instance":"/orders/42","status":404,"title":"ORD-0404",
 "type":"urn:dc:problem:ORD-0404","code":"ORD-0404","traceId":"0af7651916cd43dd8448eb211c80319c",
 "timestamp":"2026-11-19T12:46:42.991Z","retryable":false}
```

## Walkthrough

1. **Correlation.** Boot's observation filter continues a valid `traceparent` or starts a new trace; the platform's
   filter, right after it, puts `traceId` and `spanId` into the MDC for the request, its async redispatch and its error
   page. Your code reads the trace from Micrometer's `Tracer`. Unsampled traces still have an ID.
2. **Other threads.** `@Async` methods and Boot-built executors get the caller's trace through the platform's task
   decorator; a task captured without a trace runs without one, even on a busy thread.
3. **Errors.** A 4xx keeps your detail; a 5xx always says `An unexpected error occurred.`, and the full exception is
   logged on the same `traceId`. `retryable` is `true` only for 429, 502, 503 and 504.
4. **Validation.** `errors[]` lists `field` and `message`; the rejected value is not echoed unless
   `dc.platform.errors.include-rejected-values=true`.

[`verification.md`](verification.md) says what was proven, [`operations.md`](operations.md) how to run it,
[`troubleshooting.md`](troubleshooting.md) what to do when something looks off.
