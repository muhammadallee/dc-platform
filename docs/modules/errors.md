# Errors

RFC-9457 problem responses with stable, machine-readable error codes: throw a
`BusinessException`, get a correct `application/problem+json` body with `code`, `correlationId`,
and `timestamp` — correlated with the JSON log line.

## What you get

- **Business exception hierarchy** — `BusinessException` (422 by default), `NotFoundException`
  (404), `ConflictException` (409); each carries an `ErrorCode` (`DC-<CAP>-<NNNN>`) that becomes
  the problem `type`/`title` and the `code` extension.
- **Platform exception mapping** — any other `PlatformException` maps to 500 with its own code;
  anything unmapped maps to 500 with `DC-CORE-0500` and a generic detail (the message is never
  leaked — the full exception goes to the log, correlated via MDC).
- **Validation mapping** — `@Valid` body failures and method-validation
  `ConstraintViolationException`s map to 400 with an `errors[]` extension
  (`field`, `message`, `rejectedValue`); rejected values for password/secret/token-shaped fields
  are `REDACTED`.
- **Customization SPI-lite** — `ProblemDetailCustomizer` beans applied in `@Order` to every
  outgoing problem body.
- **Error-code registry** — a build-time gate asserts code format and platform-wide uniqueness
  and exports `target/error-codes.csv` (feeds the phase-14 reference).

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
  "correlationId": "0123456789abcdef0123456789abcdef",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.errors.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.errors.include-stacktrace` | `false` | Add a `stacktrace` extension (debug only). |
| `dc.platform.errors.type-base-uri` | `https://errors.dc.com/` | Base URI forming the problem `type`. |
| `dc.platform.errors.map-validation` | `true` | Map validation failures to 400 + `errors[]`. |

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

- Define a bean named `platformExceptionHandler` to replace the main advice, or
  `platformValidationExceptionHandler` to replace the constraint-violation advice.
- `dc.platform.errors.map-validation=false` reverts validation failures to Spring's default
  problem body.
- `dc.platform.errors.enabled=false` switches the capability off wholesale.

## Error codes

| Code | Meaning |
|---|---|
| `DC-CORE-0400` | Request validation failed (`errors[]` lists the violations). |
| `DC-CORE-0500` | Unhandled exception; generic body, full details in the log. |

Applications register their own codes by declaring `static final ErrorCode` constants — the
registry gate picks them up automatically.

## Testing

The advices are plain beans:
`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler)` exercises mappings
without a full context. Open a `RequestContext` (try-with-resources) around the call to see the
`correlationId` extension. Inject a fixed `Clock` bean to make `timestamp` deterministic.

## Local dev notes

No Docker, no network. Quick smoke check: `curl -s localhost:8080/nonexistent | jq .type` — a
`https://errors.dc.com/…` URI proves the advice is active; Spring's default body means the
starter is missing or the kill switch is off.
