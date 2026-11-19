# Errors

Every error a service returns is an RFC 9457 `application/problem+json` body with a stable `code`, the request's
`traceId`, a `timestamp` and a `retryable` hint. Your code throws Spring's own exceptions; the platform adds the rest.
No platform type is involved.

## What the platform configures

- **One advice for everything a controller lets through**, ordered last so your own `@RestControllerAdvice` wins for
  its exceptions:

  | Exception | Status | `code` |
  |---|---|---|
  | Your `ErrorResponseException` (or subclass) | its status | the `code` property you set; otherwise `DC-CORE-0499` (4xx) or `DC-CORE-0500` (5xx) |
  | `ResponseStatusException`, or an exception annotated `@ResponseStatus` | its status | `DC-CORE-0499` / `DC-CORE-0500` |
  | Bean Validation: `@Valid` body (`MethodArgumentNotValidException`), method validation (`HandlerMethodValidationException`, `ConstraintViolationException`) | 400 | `DC-CORE-0400`, with `errors[]` of `field` and `message` |
  | Unreadable or malformed body | 400 | `DC-CORE-0410` |
  | Spring MVC's own rejections: 404, 405, 406, 415, … | theirs | `DC-CORE-0499` |
  | A data-access failure anywhere in the cause chain | 409 / 503 | `DC-DATA-0409`, `DC-DATA-0410`, `DC-DATA-0503` |
  | Anything else | 500 | `DC-CORE-0500` |

- **No 5xx body carries an exception message.** Every 5xx detail is `An unexpected error occurred.`; the full
  exception is logged at ERROR on the request's trace. A 4xx keeps the detail you wrote.
- **Data-access failures never name SQL, tables or constraints.** Spring's translated exceptions and the raw
  Hibernate, JPA and JDBC ones (from code that uses the `EntityManager` directly) are recognised: a constraint
  violation is 409 `DC-DATA-0409`, a concurrent update or lock is 409 `DC-DATA-0410`, an unavailable database or a
  timeout is 503 `DC-DATA-0503` (`retryable: true`). A connection lost while committing is a 500: the outcome is
  unknown, so it is not retryable. A SQL bug stays a 500.
- **`retryable`** is `true` for 429, 502, 503 and 504.
- **`rejectedValue` is omitted** from `errors[]` by default: it is raw user input and may be personal data. With
  `include-rejected-values=true`, fields named like a password, secret or token are still `REDACTED`.
- **The servlet error page too.** A filter's `sendError(…)` or an exception escaping the filter chain is rendered as
  `application/problem+json`, whatever the `Accept` header, with `instance` set to the original URI and the request's
  `traceId`.
- **Spring Security exceptions pass through** to Spring Security's own handling instead of becoming a 500.

## Throw an error from your code

Put your service's code on the body Spring's exception already carries:

```java snippet:errors-service-error
@Service
class OrderService {

    static final String ORDER_PAID = "ORD-0409";

    void cancel(long orderId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "order %d is already paid".formatted(orderId));
        problem.setProperty("code", ORDER_PAID);
        throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }
}
```

```json
{
  "type": "urn:dc:problem:ORD-0409",
  "title": "ORD-0409",
  "status": 409,
  "detail": "order 42 is already paid",
  "instance": "/orders/42/cancel",
  "code": "ORD-0409",
  "traceId": "0123456789abcdef0123456789abcdef",
  "timestamp": "2026-01-01T00:00:00Z",
  "retryable": false
}
```

The platform keeps a `code` only when it is a `String`. Set your own `type` and the platform keeps your `type` and
`title` too. A subclass of `ErrorResponseException` per business outcome reads well and keeps the code in one place.

## Add a field to every error body

Use Spring's `ErrorResponse.Interceptor`. Each one sees the finished body and, as the response's cause, the exception
that caused it:

```java snippet:errors-interceptor
@Configuration
class ErrorBodyConfiguration implements WebMvcConfigurer {

    @Override
    public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
        interceptors.add((body, response) -> {
            Throwable cause = response instanceof ErrorResponseException e ? e.getCause() : null;
            if (cause instanceof java.util.concurrent.TimeoutException) {
                body.setProperty("hint", "try again in a minute");
            }
        });
    }
}
```

Interceptors from every `WebMvcConfigurer` run in order. The platform's error page runs them too, so they also see a
`sendError` (with no cause) and an exception that escaped a filter.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-errors</artifactId>
</dependency>
```

The starter brings no web stack; outside a servlet web app it does nothing.

## Zero-config behaviour

| With only the starter | You set nothing for it |
|---|---|
| Every controller error, framework rejection and error-page response is a problem body with `code`, `traceId`, `timestamp`, `retryable` | ✓ |
| Bean Validation failures are 400 with field paths | ✓ (add `spring-boot-starter-validation` for a validator) |
| Data-access failures are 409/503 without internals | ✓ |
| Your interceptors run on every error body | ✓ once you add one |

## Change it with Spring

| To change | Use |
|---|---|
| A field on every error body | `ErrorResponse.Interceptor` through `WebMvcConfigurer.addErrorResponseInterceptors` |
| The handling of your own exception | your own `@RestControllerAdvice`: it runs before the platform's |
| The status and code of a business outcome | `ErrorResponseException` with a `code` property, `ResponseStatusException`, or `@ResponseStatus` |
| The servlet error page | your own `ErrorController` bean replaces the platform's |

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.errors.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.errors.include-stacktrace` | `false` | Add a `stacktrace` extension (debugging only). |
| `dc.platform.errors.type-base-uri` | `urn:dc:problem:` | Prefix of the problem `type`; the code follows it. |
| `dc.platform.errors.map-validation` | `true` | Map validation failures to 400 with `errors[]`. |
| `dc.platform.errors.include-rejected-values` | `false` | Echo invalid values as `errors[].rejectedValue`; credentials stay redacted. |

`urn:dc:problem:` is a neutral identifier nobody tries to fetch. When your organisation publishes error pages, set the
`https://…/` base so each `type` links to its page.

## Back-off limits

- **An interceptor that throws** stops the interceptors after it. Spring swallows the exception without a log line;
  the platform's error page logs it at WARN. The response is sent either way, with the platform's body.
- **Your own `@RestControllerAdvice`** handles its exceptions completely: the platform adds nothing to those bodies
  unless you return an `ErrorResponse` or `ProblemDetail`, in which case your interceptors still run, but the platform's
  `code`, `traceId`, `timestamp` and `retryable` do not.
- **Your own `ErrorController`** replaces the error page: `sendError` responses lose the platform contract and your
  interceptors.
- Replacing the advice: a bean named `platformExceptionHandler` (or a `PlatformExceptionHandler` subclass), or
  `platformValidationExceptionHandler` for the constraint-violation advice. Both are platform types; prefer an
  interceptor.
- `map-validation=false` returns Spring's default validation body (with the platform's extensions).
- `spring.mvc.problemdetails.enabled=true` changes nothing: Boot's own problem-details advice backs off to the
  platform's, which keeps the contract.
- `dc.platform.errors.enabled=false` switches the capability off; Boot's default error handling returns.

## Error codes

| Code | Meaning |
|---|---|
| `DC-CORE-0400` | Request validation failed; `errors[]` lists the violations. |
| `DC-CORE-0410` | Request body unreadable or malformed. |
| `DC-CORE-0499` | Any other client error without a code of its own (404, 405, 415, a 4xx `@ResponseStatus`, …). |
| `DC-CORE-0500` | Server error without a code of its own; generic detail, full exception in the log. |
| `DC-DATA-0409` | The request conflicts with stored data (unique, foreign-key, NOT NULL or check constraint). |
| `DC-DATA-0410` | The data was changed or is locked by another request; reload and retry. |
| `DC-DATA-0503` | The database is unavailable or timed out. Retryable. |

Platform codes are checked at build time: each `DC-<CAP>-<NNNN>` value is declared once across every platform module.

## Testing

`MockMvc` or a real server: assert `code`, `status` and `traceId` in the body. Send a `traceparent` to know the trace
ID in advance; inject a fixed `Clock` bean to make `timestamp` deterministic.

## Local dev notes

Nothing to run. Quick check: `curl -s localhost:8080/nonexistent` returns a body whose `type` starts with
`urn:dc:problem:`; Spring's default body means the starter is missing or switched off.
