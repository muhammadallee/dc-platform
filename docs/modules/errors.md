# Errors

RFC-9457 problem responses with stable, machine-readable error codes: throw a
`BusinessException`, get a correct `application/problem+json` body with `code`, `traceId`,
`timestamp` and `retryable` — correlated with the JSON log line by the W3C trace ID.

## What you get

- **Business exception hierarchy** — `BusinessException` (422 by default), `NotFoundException`
  (404), `ConflictException` (409); each carries an `ErrorCode` (`DC-<CAP>-<NNNN>`) that becomes
  the problem `type`/`title` and the `code` extension.
- **Platform exception mapping** — any other `PlatformException` maps to 500 with its own code;
  anything unmapped maps to 500 with `DC-CORE-0500`. **No 5xx body ever carries an exception
  message** — every 5xx gets the generic detail `An unexpected error occurred.`; the full exception
  goes to the log with the trace ID. 4xx business exceptions keep their application-authored message.
- **Framework errors get the same contract** — Spring MVC's own rejections (404, 405, 406, 415, …) keep
  their statuses and gain `code` (`DC-CORE-0499`), `traceId`, `timestamp`, `retryable`; an unreadable
  or malformed body is `DC-CORE-0410` with a fixed detail (the JSON parser's message, which names
  classes and offsets, never reaches the caller).
- **`retryable`** — `true` for 429, 502, 503, 504, otherwise `false`: a hint for clients deciding
  whether to retry the identical request after backing off.
- **The servlet error page too** — `PlatformErrorController` replaces Boot's `BasicErrorController`:
  a filter's `sendError(…)` or an exception escaping the filter chain is rendered as
  `application/problem+json` (whatever the `Accept` header) with `instance` = the original URI and
  the request's `traceId`. Define your own `ErrorController` bean to replace it.
- **Security denials pass through** — an `AccessDeniedException`/`AuthenticationException` thrown in
  a controller (e.g. method security) is handed back to Spring Security, which renders the platform
  403/401, instead of being swallowed into a 500.
- **Validation mapping** — `@Valid` body failures and method-validation
  `ConstraintViolationException`s map to 400 with an `errors[]` extension
  (`field`, `message`). `rejectedValue` is **omitted by default** — rejected values are raw user
  input and may be personal data; turn it on with `dc.platform.errors.include-rejected-values=true`,
  and even then password/secret/token-shaped fields are `REDACTED`.
- **Customization SPI-lite** — `ProblemDetailCustomizer` beans applied in `@Order` to every
  outgoing problem body.
- **Error-code registry** — build-time gates assert code format and platform-wide uniqueness:
  one scans the classpath and exports `target/error-codes.csv` (feeds the reference), the other reads
  the production sources of every platform module, so a duplicate anywhere fails the build.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-errors</artifactId>
</dependency>
```

The starter brings no web stack — your service already chooses one; outside servlet web apps the
advice backs off entirely.

## Zero-config behavior

On a servlet web app, two `@RestControllerAdvice` beans register: the constraint-violation
advice at `@Order(0)` and the catch-all platform advice at lowest precedence, so your own advice
beans always win for your exceptions. Framework exceptions (404 no-handler, 405, 415, …) keep
their proper statuses — the platform advice extends `ResponseEntityExceptionHandler`.

```java
throw new NotFoundException(new ErrorCode("DC-ORDER-0404"), "order 42 not found");
```

```json
{
  "type": "https://errors.dc.com/DC-ORDER-0404",
  "title": "DC-ORDER-0404",
  "status": 404,
  "detail": "order 42 not found",
  "instance": "/orders/42",
  "code": "DC-ORDER-0404",
  "traceId": "0123456789abcdef0123456789abcdef",
  "timestamp": "2026-01-01T00:00:00Z",
  "retryable": false
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.errors.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.errors.include-stacktrace` | `false` | Add a `stacktrace` extension (debug only). |
| `dc.platform.errors.type-base-uri` | `https://errors.dc.com/` | Base URI forming the problem `type`. |
| `dc.platform.errors.map-validation` | `true` | Map validation failures to 400 + `errors[]`. |
| `dc.platform.errors.include-rejected-values` | `false` | Echo invalid values as `errors[].rejectedValue` (raw user input; credentials stay redacted). |

The `type` base URI (`https://errors.dc.com/`) is a placeholder on a domain the platform does not own;
set `type-base-uri` to your organisation's problem-type namespace (open issue G1-08).

(Hand-written until the generated reference lands in phase 14.)

## Customize

```java
@Bean
@Order(10)
ProblemDetailCustomizer tenantCustomizer() {
    return (detail, source) -> detail.setProperty("tenant", TenantContext.current());
}
```

Customizers run last, in order, on every problem body the platform emits.

## Replace / Disable

- Define a `PlatformExceptionHandler` (subclass) bean, or any bean named `platformExceptionHandler`,
  to replace the main advice; likewise `PlatformConstraintViolationHandler` /
  `platformValidationExceptionHandler` for the constraint-violation advice; any `ErrorController` bean
  replaces the error page; a bean named `errorsCapabilityDescriptor` replaces the banner line.
- `dc.platform.errors.map-validation=false` reverts validation failures to Spring's default
  problem body.
- `dc.platform.errors.enabled=false` switches the capability off wholesale.

## Error codes

| Code | Meaning |
|---|---|
| `DC-CORE-0400` | Request validation failed (`errors[]` lists the violations). |
| `DC-CORE-0410` | Request body unreadable or malformed (e.g. invalid JSON). |
| `DC-CORE-0499` | Any other request the servlet stack rejected (404, 405, 406, 415, …); status preserved. |
| `DC-CORE-0500` | Unhandled exception; generic body, full details in the log. |
| `DC-SEC-0401` | Missing or unacceptable credentials (security entry point). |
| `DC-SEC-0403` | Authenticated, but lacking the required authority (security denied handler). |

Applications register their own codes by declaring `static final ErrorCode` constants — the
registry gate picks them up automatically.

## Testing

The advices are plain beans:
`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler)` exercises mappings
without a full context. Open a `RequestContext` (try-with-resources) around the call to see the
`traceId` extension. Inject a fixed `Clock` bean to make `timestamp` deterministic.

## Local dev notes

No Docker, no network. Quick smoke check: `curl -s localhost:8080/nonexistent | jq .type` — a
`https://errors.dc.com/…` URI proves the advice is active; Spring's default body means the
starter is missing or the kill switch is off.
