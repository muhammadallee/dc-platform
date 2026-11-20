# Chapter 2 — Errors and Validation: The Failure Contract

> **Capabilities covered:** `errors`, plus Spring's Bean Validation
>
> One machine-readable shape for every failure a service returns, built from Spring's own error types.
>
> **Starters:** `platform-starter-errors`, and Spring's `spring-boot-starter-validation` ·
> **Reference:** [modules/errors.md](../../modules/errors.md)

---

## 1. Introduction and Business Value

### The problem it solves

A client of your service sees four kinds of failure: its own mistakes (a bad field), business refusals (the order is
already paid), framework rejections (wrong method, unknown path) and your bugs. Without a contract, each arrives in a
different shape: Spring's default JSON, a hand-built map, an HTML error page, a stack trace. Every client writes
four parsers, and some of them leak internals to the caller.

The errors capability gives all four one shape: an RFC 9457 `ProblemDetail` with four additions.

| Field | What it is for |
|---|---|
| `code` | A stable, machine-readable reason. Clients branch on it; dashboards count it |
| `traceId` | The W3C trace ID of the request ([chapter 1](01-core.md)): one search finds its log lines |
| `timestamp` | When it failed, as an ISO-8601 string |
| `retryable` | `true` for 429, 502, 503, 504: whether repeating the same request later may work |

### Two rules worth internalising

1. **A 5xx body never carries an exception message.** Server messages name hosts, SQL and file paths. The body says
   `An unexpected error occurred.`; the exception goes to the log on the request's trace, which the `traceId` finds.
2. **Validation never echoes what the user typed** unless you ask, and never for a password, secret or token.

---

## 2. Core Concepts

### 2.1 Throw Spring's exception, set your code

Spring already has an exception that carries an RFC 9457 body: `ErrorResponseException`. Its body is a
`ProblemDetail`, and a `ProblemDetail` can carry any property. Your service's code is one of them:

```java snippet:book-02-service-error
final class OrderAlreadyPaid extends ErrorResponseException {

    static final String CODE = "ORD-0409";

    OrderAlreadyPaid(long orderId) {
        super(HttpStatus.CONFLICT, body(orderId), null);
    }

    private static ProblemDetail body(long orderId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "order %d is already paid".formatted(orderId));
        problem.setProperty("code", CODE);
        return problem;
    }
}
```

One subclass per business outcome keeps the code in one place and reads well at the throw site:
`throw new OrderAlreadyPaid(42)`. For a one-off, `ResponseStatusException` or a `@ResponseStatus` annotation on your
own exception works too; their bodies get the generic `DC-CORE-0499`.

### 2.2 Where the platform steps in

The platform registers one `@RestControllerAdvice`, ordered last, so any advice of yours handles its exceptions first.
For everything else it builds the body (table on the [module page](../../modules/errors.md)), then adds `code`,
`traceId`, `timestamp` and `retryable`, replaces a 5xx detail, and returns it.

What it returns is itself an `ErrorResponseException`, whose **cause** is the exception your code threw. That detail
matters for the next section.

### 2.3 Adding to every body: `ErrorResponse.Interceptor`

Spring calls each registered `ErrorResponse.Interceptor` just before an error body is written, with the body and the
`ErrorResponse` it came from. Because the platform's response carries the original exception as its cause, an
interceptor can decide by exception type:

```java snippet:book-02-interceptor
@Configuration
class TenantOnErrors implements WebMvcConfigurer {

    @Override
    public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
        interceptors.add((body, response) -> body.setProperty("tenant", "dxb"));
        interceptors.add((body, response) -> {
            Throwable cause = response instanceof ErrorResponseException e ? e.getCause() : null;
            if (cause instanceof OrderAlreadyPaidElsewhere) {
                body.setProperty("hint", "check the payments service");
            }
        });
    }

    static final class OrderAlreadyPaidElsewhere extends RuntimeException {
    }
}
```

Interceptors compose: every `WebMvcConfigurer` adds to one list, and they run in order. The platform's error page runs
the same list, so a filter's `sendError` (no cause) and an exception that escaped a filter get them too.

!!! warning "An interceptor that throws stops the rest"
    Spring catches the throwable, skips the remaining interceptors and says nothing. The response still goes out with
    the platform's body. Keep interceptors trivial.

### 2.4 Validation: two entry points, one shape

Add `spring-boot-starter-validation` and annotate. A failed `@Valid @RequestBody` raises
`MethodArgumentNotValidException`; a constraint on a parameter, or on a method of a `@Validated` bean, raises
`HandlerMethodValidationException` or `ConstraintViolationException`. All three become the same 400:

```java snippet:book-02-validated-request
@RestController
class ApplicantController {

    record Applicant(@NotBlank String name, @Email String email) {
    }

    @PostMapping("/applicants")
    String apply(@Valid @RequestBody Applicant applicant) {
        return applicant.name();
    }
}
```

```json
{
  "status": 400,
  "code": "DC-CORE-0400",
  "detail": "Validation failed.",
  "errors": [
    {"field": "email", "message": "must be a well-formed email address"},
    {"field": "name", "message": "must not be blank"}
  ],
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "timestamp": "2026-01-01T00:00:00Z",
  "retryable": false
}
```

Without `spring-boot-starter-validation` the annotations compile but nothing checks them. That is the one silent
failure here: the archetype adds the starter for you.

---

## 3. Feature Reference

The codes, properties and back-off limits are on the [module page](../../modules/errors.md). The shapes to remember:

| Failure | Status | `code` |
|---|---|---|
| Your `ErrorResponseException` | yours | yours |
| `ResponseStatusException`, `@ResponseStatus` | theirs | `DC-CORE-0499` (4xx), `DC-CORE-0500` (5xx) |
| Validation | 400 | `DC-CORE-0400` |
| Unreadable body | 400 | `DC-CORE-0410` |
| Spring MVC rejections (404, 405, …) | theirs | `DC-CORE-0499` |
| Database conflict / lock / outage | 409 / 409 / 503 | `DC-DATA-0409` / `DC-DATA-0410` / `DC-DATA-0503` |
| Anything else | 500 | `DC-CORE-0500` |

---

## 4. How-to Guide

### 4.1 Add the capability

Every generated service has `platform-starter-errors` and `spring-boot-starter-validation`.

### 4.2 Choose codes

Give each business outcome a code of your own, such as `ORD-0409`, declared once as a constant. The platform's own
codes start with `DC-CORE-` and `DC-DATA-`; avoid those two prefixes.

### 4.3 Handle one exception yourself

Write an ordinary `@RestControllerAdvice`. It runs before the platform's, and it owns the bodies it returns: the
platform adds nothing to them. Return an `ErrorResponse` if you want your interceptors to run on it.

### 4.4 Test it

```java
mockMvc.perform(post("/orders/42/cancel"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORD-0409"));
```

---

## 5. Operations

- **What to alert on:** the rate of `DC-CORE-0500` and `DC-DATA-0503` bodies, from the logs. Client errors are
  traffic, not incidents: they are logged at DEBUG.
- **Finding a failure:** take the `traceId` from the body a client reports and search the logs for it. The 5xx log
  line carries the full exception.
- **Never in production:** `dc.platform.errors.include-stacktrace=true`.

---

## 6. Deep Dive

### 6.1 Why the advice returns an exception

Returning a plain `ResponseEntity<ProblemDetail>` would work for the client, but Spring then gives interceptors a
`null` instead of an `ErrorResponse`, and they could not see what went wrong. Returning an `ErrorResponseException`
whose cause is the original costs nothing and keeps the interceptor useful.

### 6.2 Why `title` is the code

RFC 9457 says `title` is a short, human-readable summary that does not change between occurrences. A code is exactly
that, and it keeps `title` stable across languages. The human sentence is `detail`.

### 6.3 Why data failures name no table

A unique-constraint message says which constraint, on which table, often with the offending value. That is schema
and possibly personal data. The body says what kind of conflict it was; the log, on the same trace, says the rest.

---

## 7. Exercise

Throw `OrderAlreadyPaid` from a controller, call it with a `traceparent`, and check the body's `code`, `detail` and
`traceId`. Then add an interceptor that adds a `hint` only for that exception, and check it appears there and nowhere
else.

---

## 8. Checklist

- [ ] Business outcomes are `ErrorResponseException` subclasses (or `@ResponseStatus`), with a `code` constant.
- [ ] No `@RestControllerAdvice` that builds bodies the platform already builds.
- [ ] Shared fields go in an `ErrorResponse.Interceptor`, and the interceptor cannot throw.
- [ ] `spring-boot-starter-validation` is on the classpath.
- [ ] Nobody relies on a 5xx `detail`.

---

**Next:** [Chapter 3 — Logging and Observability](03-logging-observability.md).

**Reference:** [modules/errors.md](../../modules/errors.md) ·
[error codes](../../reference/error-codes.md) ·
[configuration properties](../../reference/properties.md)

[Back to the book](../index.md)
