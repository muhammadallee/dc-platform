# Chapter 2 — Errors and Validation: The Failure Contract

> **Capabilities covered:** `errors`, `validation`
>
> One machine-readable shape for every failure a service can return, and the bean-validation wiring
> that feeds it.
>
> **Starters:** `platform-starter-errors`, `platform-starter-validation` ·
> **Reference:** [modules/errors.md](../../modules/errors.md) ·
> [modules/validation.md](../../modules/validation.md)

---

## 1. Introduction and Business Value

These two capabilities are one chapter because they are one contract. Validation decides *what is
rejected*; errors decides *what the rejection looks like on the wire*. Split them across two chapters
and you learn each half without the join.

Together they guarantee: **every non-2xx response your service produces has the same shape, carries a
stable machine-readable code, and is correlated to a log line that has the full story.** Every one —
including the two that are usually special cases, 401 and 403.

### The problem it solves

Consider a client integrating with twelve internal services. Without a shared error contract:

- Twelve error envelopes. Some return `{"error": "..."}`, some `{"message": "..."}`, some a bare
  string, some HTML. The client writes twelve parsers.
- No stable identifier. The client's retry logic and the operations team's alerts key on **message
  text**, so rewording "Order not found" to "No such order" silently breaks both.
- Field-level validation feedback in a different shape per service, so a form cannot bind errors
  generically.
- Somewhere in those twelve, a 500 that helpfully includes the SQL statement, the internal hostname,
  or a stack trace. That is an information-disclosure finding waiting for an auditor.

### What RFC 9457 buys you

`application/problem+json` is an IETF standard (RFC 9457, formerly RFC 7807) for exactly this. One
media type, five standard members, and an open extension mechanism:

```json
{
  "type": "https://errors.dc.com/DC-ORDER-0404",
  "title": "DC-ORDER-0404",
  "status": 404,
  "detail": "order 8812 not found",
  "instance": "/orders/8812",
  "code": "DC-ORDER-0404",
  "correlationId": "9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7",
  "timestamp": "2026-08-04T10:15:30.123Z"
}
```

The first five members are the standard. The last three are the platform's extensions, and they are
what make the response operationally useful: `code` is the stable identifier a dashboard keys on,
`correlationId` joins this response to the log line that has the full detail, and `timestamp` is the
server's clock, not the client's.

### Two decisions worth internalising

**Domain code throws intent, not HTTP.** `throw new NotFoundException(code, message)` — not
`ResponseEntity.status(404)`. Status mapping lives in exactly one place, so the same domain failure
cannot return 404 from one endpoint and 400 from another. It also means your service layer is
testable without a servlet stack.

**Unmapped exceptions never leak.** Anything the platform does not recognise becomes a 500 with
`DC-CORE-0500` and the fixed detail `"An unexpected error occurred."`. The real exception — message,
type, and stack — goes to the log, correlated. This is not politeness; a `NullPointerException`
message routinely contains a file path, a `SQLException` contains your schema, and a connection
failure contains an internal hostname.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No shared error envelope | Every client writes a parser per service; integration cost never amortises |
| No stable codes | Alerts and runbooks key on message text, and rewording a message becomes a breaking change |
| No safe catch-all | Stack traces, SQL, and hostnames reach callers. A compliance finding, and a reconnaissance gift |
| No validation mapping | Clients get an opaque 400 with no field information, or the framework's default HTML |
| No redaction | A rejected password is echoed back in the error body *and* logged |
| No platform constraints | Each service writes its own ULID regex and "not blank" check, with different edge-case behaviour |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Where an exception can be caught, and where it cannot

This is the single most important structural fact in this chapter.

```
  request
     |
     v
  [ CorrelationIdFilter ]
     |
     v
  [ Spring Security filter chain ]
     |
     |  ***  401 and 403 are produced HERE  ***
     |  AuthenticationEntryPoint / AccessDeniedHandler write the response
     |  directly. @RestControllerAdvice is not in scope. It cannot help.
     v
  [ DispatcherServlet ]
     |
     v
  [ HandlerMapping ] -> [ @RestController method ]
     |                        |
     |                        | throws
     |                        v
     |                  [ @RestControllerAdvice chain ]   <-- errors capability
     |                        - application advice (yours)
     |                        - PlatformConstraintViolationHandler  @Order(0)
     |                        - PlatformExceptionHandler  @Order(LOWEST_PRECEDENCE)
     v
  response
```

Two consequences:

1. Everything thrown from your controller or below is reachable by `@RestControllerAdvice`, and the
   errors capability handles it.
2. **401 and 403 are not.** They are produced before `DispatcherServlet` exists. This is why almost
   every hand-rolled implementation returns Spring's default HTML or an empty body for those two
   statuses while returning nice JSON for everything else — and why every client of that service
   carries a special case forever. The [security](05-security-authz.md) capability solves it with a
   dedicated `AuthenticationEntryPoint` and `AccessDeniedHandler` pair that write the *same* problem
   shape. Chapter 5 covers the wiring; this chapter is why it matters.

### 2.2 Advice ordering, and why the platform is last

Spring picks the **first** `@RestControllerAdvice` bean that has a handler method matching the thrown
type. The platform's catch-all advice declares `@ExceptionHandler(Exception.class)`, which matches
*everything*. If it ran first, it would swallow exceptions your application intended to handle.

So it is `@Order(Ordered.LOWEST_PRECEDENCE)`. Your `@RestControllerAdvice` — which has no explicit
order and therefore sits at default precedence — always wins.

```
  @Order(0)                     PlatformConstraintViolationHandler
                                  handles ConstraintViolationException only
  (default, unordered)          YOUR @RestControllerAdvice
  @Order(LOWEST_PRECEDENCE)     PlatformExceptionHandler
                                  handles PlatformException + Exception (catch-all)
```

!!! note "Why the constraint handler is a separate bean at `@Order(0)`"
    Two reasons, both structural. It must only load when `jakarta.validation` is on the classpath, so
    it cannot be a method on the catch-all advice. And it must run **ahead** of the catch-all —
    because Spring picks the first advice with *any* matching handler, the catch-all's
    `Exception` mapping would otherwise capture `ConstraintViolationException` and turn a 400 into a
    500.

### 2.3 `ResponseEntityExceptionHandler` — keeping framework errors intact

`PlatformExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`. That base class already
handles the servlet stack's own exceptions — `NoHandlerFoundException` (404),
`HttpRequestMethodNotSupportedException` (405), `HttpMediaTypeNotAcceptableException` (406),
`HttpMediaTypeNotSupportedException` (415), and more.

Without extending it, a `Exception.class` catch-all would sweep all of those into 500s. A wrong HTTP
method would report a server error. Extending it means the framework's statuses survive, and the
platform only takes over what nothing else claimed.

### 2.4 The two validation entry points

Bean Validation (Jakarta Validation) fires in two different places, throws two different exceptions,
and needs two different handlers. Knowing which is which saves an afternoon.

| | Request-body validation | Method validation |
|---|---|---|
| Trigger | `@Valid`/`@Validated` on a controller `@RequestBody` parameter | `@Validated` on a class, constraints on method parameters or return value |
| Thrown | `MethodArgumentNotValidException` | `ConstraintViolationException` |
| Handled by | `PlatformExceptionHandler.handleMethodArgumentNotValid` (inherited hook) | `PlatformConstraintViolationHandler` `@Order(0)` |
| Reaches | Controller boundary only | Any Spring bean — service layer included |

Both produce the **same** 400 problem body with the same `errors[]` array. That symmetry is
deliberate: a client cannot tell, and should not care, which layer rejected the request.

!!! success "Best practice — validate at the boundary, enforce invariants in the domain"
    `@Valid` on the request body catches shape problems. Method validation on a service catches
    contract problems at a layer a controller test cannot bypass. Neither replaces a domain invariant
    — if "an order cannot be modified after shipping" is a rule, that is a `BusinessException`, not a
    constraint annotation.

### 2.5 Why constraint annotations belong to the platform

A constraint is a small thing with surprisingly slippery semantics. "Not blank" — does `"  "` pass?
Does `null`? A ULID regex — 26 characters, but which alphabet, and is the first character bounded?
Every service that writes these from scratch gets a slightly different answer, and the differences
only surface when two services disagree about the same input.

The platform defines four, once, with tested edge cases:

| Constraint | Rejects | `null` is | Notable detail |
|---|---|---|---|
| `@NotBlankTrimmed` | Empty or whitespace-only strings | **invalid** | The name states the intent; leading/trailing whitespace never counts as content |
| `@Ulid` | Non-ULID strings | valid | 26 Crockford base32 (no I, L, O, U); first character `0`–`7` so the timestamp fits 128 bits; case-insensitive per spec |
| `@SafeText` | Strings containing ISO control characters | valid | Tab and newline included. A log-injection defence, **not** an XSS sanitizer |
| `@FutureInstant` | Instants not in the future | valid | Measured against the validator's `ClockProvider`, so tests inject a fixed clock instead of sleeping |

!!! warning "`null` is valid for three of the four"
    This is the Bean Validation convention, not a platform choice: a constraint validates a value's
    *shape*, and presence is `@NotNull`'s job. `@Ulid String id` accepts `null`. If the field is
    required, write `@NotNull @Ulid`. `@NotBlankTrimmed` is the exception — rejecting `null` is the
    whole point of it.

### 2.6 `@SafeText` and why control characters matter

A newline inside a single-line text field, once written to a log store, is a **forged log entry**. An
attacker who can put `\n2026-08-04 10:00:00 INFO Payment approved` into a "remarks" field has written
a line your log store will index as its own event. ANSI escape sequences in the same field can
corrupt an operator's terminal when someone `tail`s the file.

Rejecting ISO control characters at the boundary is a cheap, complete defence against both. It is the
same reasoning behind [`CorrelationId`](01-core.md)'s format check.

!!! warning "`@SafeText` is not an HTML sanitizer"
    It does not stop `<script>alert(1)</script>` — that string contains no control characters. XSS is
    prevented by **output encoding** at the consumer, not by input validation. Do not let `@SafeText`
    give you false confidence about a field that will be rendered in a browser.

---

## 3. Feature Reference

### 3.1 Errors — public API

Package `ae.gov.dubaicustoms.platform.errors`. All types `@API(status = STABLE, since = "0.1.0")`.

| Type | Kind | Purpose |
|---|---|---|
| `BusinessException` | class extends `PlatformException` | A business-rule violation. Carries an `HttpStatusHint`, default 422 |
| `NotFoundException` | class extends `BusinessException` | The addressed resource does not exist. 404 |
| `ConflictException` | class extends `BusinessException` | The request clashes with current state. 409 |
| `HttpStatusHint` | enum | The closed set of statuses a business failure may map to |
| `ProblemDetailCustomizer` | functional interface | Ordered mutation of every outgoing problem body |

#### The exception hierarchy

```
  RuntimeException
    └── PlatformException            (core — carries ErrorCode)
          └── BusinessException      (errors — adds HttpStatusHint, default 422)
                ├── NotFoundException      404
                └── ConflictException      409
```

Anything extending `PlatformException` but **not** `BusinessException` is treated as an
infrastructure failure and maps to 500 — including platform exceptions thrown by other capabilities.
That is the rule that makes the taxonomy work without a mapping table.

#### `HttpStatusHint`

| Value | Status | Meaning |
|---|---|---|
| `BAD_REQUEST` | 400 | The request itself is malformed |
| `NOT_FOUND` | 404 | The addressed resource does not exist |
| `CONFLICT` | 409 | The request clashes with current resource state |
| `UNPROCESSABLE` | 422 | Syntactically fine, semantically rejected — **the default** |

!!! note "The hint set is deliberately closed, and deliberately 4xx-only"
    The enum wraps the raw status int so exception types stay free of servlet-stack enums, and it
    contains no 5xx values because infrastructure failures are not *hinted* — they are 500 by
    definition. Note there is no 429: [rate limiting](13-ratelimit-flags.md) has its own exception
    type for exactly this reason, rather than stretching this taxonomy.

#### `ProblemDetailCustomizer`

```java
@FunctionalInterface
public interface ProblemDetailCustomizer {
    void customize(ProblemDetail detail, Throwable source);
}
```

Contributed as beans, applied in `@Order` after the platform has fully populated the body.

**Implementation requirements**, from the interface's own contract: implementations must be
thread-safe and fast — they run on the request thread for every error response. They may overwrite
platform-set fields (that is the point). They **must not throw**: a throwing customizer turns a mapped
error into an unmapped 500.

### 3.2 Validation — public API

Package `ae.gov.dubaicustoms.platform.validation`. All four are standard Bean Validation constraints,
usable anywhere Jakarta Validation is.

| Constraint | Applies to | Message key |
|---|---|---|
| `@NotBlankTrimmed` | `String` | `{dc.platform.validation.NotBlankTrimmed.message}` |
| `@Ulid` | `String` | `{dc.platform.validation.Ulid.message}` |
| `@SafeText` | `String` | `{dc.platform.validation.SafeText.message}` |
| `@FutureInstant` | `Instant` | `{dc.platform.validation.FutureInstant.message}` |

All four target `METHOD`, `FIELD`, `ANNOTATION_TYPE`, `CONSTRUCTOR`, `PARAMETER`, and `TYPE_USE`, and
all four support `groups()` and `payload()` like any standard constraint.

### 3.3 The problem body

| Member | Source | Notes |
|---|---|---|
| `type` | `type-base-uri` + code | e.g. `https://errors.dc.com/DC-ORDER-0404` |
| `title` | The error code | Deliberately the code, not prose — prose is not stable |
| `status` | `HttpStatusHint`, or 500 | |
| `detail` | The exception message | **Only for mapped exceptions.** Unmapped ones get a fixed generic string |
| `instance` | The request URI | |
| `code` | The error code | The extension a dashboard keys on |
| `correlationId` | `RequestContext` | Present only when a context is open |
| `timestamp` | Server clock, ISO-8601 | A string, not a temporal object — the JSON shape must not depend on which Jackson modules are present |
| `errors[]` | Validation failures | `field`, `message`, `rejectedValue` per entry |
| `stacktrace` | The exception | **Only when `include-stacktrace: true`.** Never in production |

### 3.4 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.errors.enabled` | Boolean | `true` | Kill switch | During an incident caused by a customizer, to fall back to Spring's defaults |
| `dc.platform.errors.type-base-uri` | String | `https://errors.dc.com/` | Prefix for the problem `type` URI | When your organisation publishes error documentation at another host |
| `dc.platform.errors.map-validation` | Boolean | `true` | Map validation failures to 400 with `errors[]` | When a legacy client depends on Spring's default validation response shape |
| `dc.platform.errors.include-stacktrace` | Boolean | `false` | Add a `stacktrace` extension to problem bodies | **Local debugging only.** See the warning below |
| `dc.platform.validation.enabled` | Boolean | `true` | Kill switch | Essentially never |

!!! warning "`include-stacktrace` is a production incident waiting to happen"
    It puts the full stack trace in the **response body**, which means class names, file paths, library
    versions, and often data values go to the caller. It is a genuinely useful local debugging aid and
    a serious information disclosure anywhere else. Set it in a `local`-profile block, never in the
    default document, and never in a config map that could be promoted.

### 3.5 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformErrorHandlingAutoConfiguration` | Servlet web app, errors API on classpath, `errors.enabled != false` | `platformExceptionHandler`, `platformValidationExceptionHandler` (gated on `map-validation`), the problem-body factory | You define beans named `platformExceptionHandler` / `platformValidationExceptionHandler` |
| `PlatformValidationAutoConfiguration` | Validation API **and** `jakarta.validation` on classpath, a `ValidationProvider` service file present, `validation.enabled != false` | `platformValidator`, `platformMethodValidationPostProcessor`, `validationCapabilityDescriptor` | You define a `jakarta.validation.Validator` bean, or your own `MethodValidationPostProcessor` |

!!! note "The validation auto-configuration is ordered before Boot's"
    `@AutoConfiguration(beforeName = "...ValidationAutoConfiguration")` — and it names *both* the
    classic and the Boot-4 module locations. That ordering is what lets the platform validator win the
    `@ConditionalOnMissingBean` race, so its message bundle is the one that interpolates.

### 3.6 Extension points

| Extension | How | Effect |
|---|---|---|
| Enrich every problem body | Declare a `ProblemDetailCustomizer` bean | Applied in `@Order`, after population |
| Handle a specific exception yourself | Declare a `@RestControllerAdvice` | Yours wins — the platform is at `LOWEST_PRECEDENCE` |
| Replace the platform advice entirely | Declare a bean named `platformExceptionHandler` | The platform's backs off |
| Replace validation messages | `messages.properties` in your app, same keys | Your bundle is consulted first |
| Add your own constraint | Standard `@Constraint` + `ConstraintValidator` | The platform validator picks it up |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-errors</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-validation</artifactId>
</dependency>
```

Delete every `@RestControllerAdvice` in your service that exists only to shape errors. That is the
point of the capability, and the archetype's `PlatformConformanceTest` will tell you if you leave one
behind.

### 4.2 Throw domain failures

```java snippet:book-02-business-exceptions
final class OrderErrors {
    static final ErrorCode NOT_FOUND = new ErrorCode("DC-ORDER-0404");
    static final ErrorCode ALREADY_SHIPPED = new ErrorCode("DC-ORDER-0021");
    static final ErrorCode DUPLICATE_REFERENCE = new ErrorCode("DC-ORDER-0009");

    private OrderErrors() {
    }
}

@Service
class OrderService {

    private final OrderRepository orders;

    OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    Order cancel(String orderId) {
        Order order = orders.find(orderId)
                .orElseThrow(() -> new NotFoundException(
                        OrderErrors.NOT_FOUND, "order " + orderId + " not found"));

        if (order.shipped()) {
            // 422 by default: syntactically fine, semantically rejected.
            throw new BusinessException(
                    OrderErrors.ALREADY_SHIPPED, "shipped orders cannot be cancelled");
        }
        return order.cancelled();
    }
}

record Order(String id, boolean shipped) {
    Order cancelled() {
        return this;
    }
}

interface OrderRepository {
    Optional<Order> find(String id);
}
```

The controller does nothing. No `try`/`catch`, no `ResponseEntity.status(...)`, no advice:

```java
@GetMapping("/orders/{id}")
Order get(@PathVariable String id) {
    return orderService.cancel(id);      // exceptions become problem responses
}
```

```bash
curl -i localhost:8080/orders/8812
```
```
HTTP/1.1 404
Content-Type: application/problem+json
X-Correlation-Id: 9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7

{"type":"https://errors.dc.com/DC-ORDER-0404","title":"DC-ORDER-0404","status":404,
 "detail":"order 8812 not found","instance":"/orders/8812","code":"DC-ORDER-0404",
 "correlationId":"9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7","timestamp":"2026-08-04T10:15:30.123Z"}
```

!!! success "Best practice — the exception message is part of your API"
    The `detail` field is written verbatim to the response for mapped exceptions. Write messages a
    *client* should read: `"order 8812 not found"`, not `"repository lookup returned empty for pk=8812
    on shard 3"`. Diagnostics belong in the log, which already has the correlation id.

### 4.3 Validate a request body

```java snippet:book-02-validated-request
record CreateOrderRequest(
        @NotNull @Ulid String customerId,
        @NotBlankTrimmed String reference,
        @SafeText String remarks,
        @NotNull @FutureInstant Instant deliverBy) {
}
```

```java
@PostMapping("/orders")
@ResponseStatus(HttpStatus.CREATED)
Order create(@Valid @RequestBody CreateOrderRequest request) {
    return orderService.create(request);
}
```

A bad request produces a 400 with per-field detail:

```json
{
  "type": "https://errors.dc.com/DC-CORE-0400",
  "title": "DC-CORE-0400",
  "status": 400,
  "detail": "Validation failed.",
  "instance": "/orders",
  "code": "DC-CORE-0400",
  "correlationId": "9f2c...",
  "timestamp": "2026-08-04T10:15:30.123Z",
  "errors": [
    {"field": "customerId", "message": "must be a valid ULID", "rejectedValue": "abc"},
    {"field": "reference",  "message": "must not be blank",    "rejectedValue": "   "}
  ]
}
```

!!! tip "`@Valid` on the parameter, not just constraints on the record"
    Constraints on a record are inert until something triggers validation. On a controller that
    trigger is `@Valid` (or `@Validated`) on the `@RequestBody` parameter. Forgetting it is the most
    common reason "my validation isn't running".

### 4.4 Validate below the controller

Constraints on a service method fire when the class is `@Validated`:

```java snippet:book-02-method-validation
@Service
@Validated
class DeclarationService {

    Declaration lookup(@NotNull @Ulid String declarationId) {
        return new Declaration(declarationId);
    }
}

record Declaration(String id) {
}
```

Calling `lookup("nope")` throws `ConstraintViolationException`, which the platform maps to the same
400 shape as §4.3 — a client cannot tell which layer rejected it.

!!! warning "Method validation is proxy-based"
    It works through a Spring AOP proxy, so it only fires on calls that go *through* the proxy. An
    internal `this.lookup(...)` call from another method on the same bean bypasses it entirely. This
    is the standard Spring self-invocation caveat, and it applies equally to
    [`@Idempotent`](10-coordination.md), [`@RateLimited`](13-ratelimit-flags.md),
    [`@Audited`](12-audit.md), and [`@RequiresPermission`](05-security-authz.md).

### 4.5 Enrich every problem body

```java snippet:book-02-problem-customizer
@Configuration
class ProblemCustomizers {

    @Bean
    @Order(10)
    ProblemDetailCustomizer serviceNameCustomizer() {
        return (detail, source) -> detail.setProperty("service", "orders-service");
    }
}
```

Now every error response from this service carries a `service` field. Common uses: tenant identifier,
a documentation link, a support reference.

!!! warning "A customizer that throws turns a 404 into a 500"
    It runs on the error path, where there is nothing left to catch it. Never let a customizer
    dereference something that might be absent, call a remote service, or read a resource that might
    be missing. Guard everything, and default to doing nothing.

### 4.6 Handle one exception type yourself

You do not need to replace the platform advice to special-case one exception. Declare your own
advice; it wins on precedence:

```java
@RestControllerAdvice
class LegacyIntegrationAdvice {

    @ExceptionHandler(LegacySystemUnavailable.class)
    ResponseEntity<ProblemDetail> handle(LegacySystemUnavailable e) {
        ProblemDetail problem = ProblemDetail.forStatus(503);
        problem.setTitle("DC-LEGACY-0503");
        problem.setProperty("code", "DC-LEGACY-0503");
        problem.setProperty("retryAfterSeconds", 30);
        return ResponseEntity.status(503).body(problem);
    }
}
```

!!! success "Best practice — prefer a `PlatformException` subclass over a custom advice"
    A custom advice means you now own the shape: you must remember `code`, `correlationId`, and
    `timestamp`, and keep them in step with the platform. Extending `BusinessException` gets all of
    that free. Reach for an advice only when you genuinely need a status the hint enum does not carry.

### 4.7 Override a validation message

Put the platform's key in your own `messages.properties`:

```properties
dc.platform.validation.Ulid.message=must be a 26-character declaration reference
```

Your bundle is consulted first, so you override per key without copying the whole bundle.

### 4.8 Write your own constraint

Nothing platform-specific — it is standard Bean Validation, and the platform validator picks it up:

```java
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HsCodeValidator.class)
public @interface HsCode {
    String message() default "must be a valid HS code";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local | `include-stacktrace: true` | Genuinely useful. Put it in the `local` profile block, nowhere else |
| All others | `include-stacktrace: false` (the default) | Non-negotiable. See §3.4 |
| All | `type-base-uri` set fleet-wide | The `type` URI should resolve to real documentation. If it does not, it is decoration |
| Legacy client migration | `map-validation: false`, temporarily | Restores Spring's default validation shape while a client catches up. Time-box it |

### 5.2 What to monitor

The `code` extension is what makes this capability operationally valuable. Alert on codes, never on
message text.

| Signal | Where | Alert when |
|---|---|---|
| `DC-CORE-0500` rate | Log field `code`, or your HTTP 5xx metric | Any sustained non-zero rate. This code means *nothing mapped it* — every occurrence is a bug or an unhandled dependency failure |
| Per-code 4xx rates | Log field `code` | A specific business code spikes — usually a client integration change, sometimes an attack |
| `DC-CORE-0400` rate | Log field `code` | A spike right after a release means a contract change broke a client |
| 4xx/5xx ratio | HTTP server metrics | A shift from 4xx to 5xx is a regression; the reverse is usually a client |

!!! tip "`DC-CORE-0500` is the single highest-value alert in this chapter"
    Every other code was deliberately chosen by someone. `DC-CORE-0500` means an exception reached the
    catch-all that nobody anticipated. Treat a non-zero rate as a defect queue, not as background
    noise — and note that the response body deliberately tells the client nothing, so the log is your
    only source. Search by `correlationId`.

### 5.3 Log levels, and why business failures are DEBUG

The platform logs mapped failures at two different levels, deliberately:

| Failure | Level | Reasoning |
|---|---|---|
| `BusinessException` (4xx) | `DEBUG`, message only | A 404 is expected traffic, not an incident. Logging it at ERROR would drown real alerts |
| `PlatformException` (5xx) | `ERROR`, with stack | Infrastructure failed |
| Unmapped `Exception` | `ERROR`, with stack | Always a defect |

!!! warning "Turn on DEBUG for the handler when investigating a 4xx"
    Because business failures log at DEBUG with no stack, a report of "the client gets a 422 and I
    can't see why" needs
    `logging.level.ae.gov.dubaicustoms.platform.errors=DEBUG`. This is a deliberate trade: quiet by
    default, verbose on demand.

### 5.4 Troubleshooting

**Errors come back as HTML, or as Spring's default JSON.**

| Cause | Check |
|---|---|
| Starter missing | `mvn dependency:tree \| grep platform-starter-errors` |
| Capability disabled | `curl -s localhost:8080/actuator/env/dc.platform.errors.enabled` |
| An application advice is catching it first | Search for `@RestControllerAdvice` in your code — yours wins by design |
| The failure is a 401/403 | Expected. Those come from the security chain — add `platform-starter-security` ([Chapter 5](05-security-authz.md)) |

**Validation is not firing.**

| Cause | Check |
|---|---|
| No `@Valid` on the parameter | The most common cause by a wide margin |
| Class not `@Validated`, for method validation | The annotation goes on the *class* |
| Self-invocation | An internal call bypasses the proxy — §4.4 |
| Validation starter missing | No `ValidationProvider` on the classpath means the auto-configuration never activates |
| `@Ulid` accepting `null` | Working as specified. Add `@NotNull` — §2.5 |

**A rejected password appears in the response.** It should not: the platform redacts `rejectedValue`
for any field whose name contains `password`, `secret`, or `token`, case-insensitively. If it is
leaking, the field is named something else — `pwd`, `credential`, `apiKey`. Rename the field, or add a
`ProblemDetailCustomizer` that scrubs the `errors[]` array.

**`detail` is `"An unexpected error occurred."`** That is the unmapped path working correctly. The
real message is in the log at ERROR, correlated. If the exception *should* have been mapped, it is
not a `PlatformException` subclass — make it one.

### 5.5 Security considerations

This capability sits on the boundary between "useful diagnostics" and "information disclosure", so
the defaults are all on the safe side:

- **Unmapped messages are never echoed.** Fixed generic detail; the real one goes to the log.
- **`rejectedValue` is redacted** for credential-shaped field names.
- **Stack traces are off by default**, and the property name says what it is.
- **`title` is the code, not prose.** Prose in a title tends to accumulate context; a code cannot.

What remains **your** responsibility:

- **Mapped messages are echoed verbatim.** `throw new NotFoundException(code, "user " + email + " not
  found")` puts an email address in a response body and confirms account existence to an attacker.
- **Customizers can undo everything above.** A customizer that adds `detail.setProperty("exception",
  source.toString())` re-opens the leak the catch-all closed.
- **Error codes reveal structure.** Minor, but a sequential code space tells an attacker how many
  business rules exist. Not worth obfuscating; worth knowing.

!!! success "Best practice — write the `detail` as if it will be screenshotted into a ticket"
    Because it will be. It should say what the client did wrong and nothing about how you are built.

---

## 6. Deep Dive

### 6.1 Why `title` is the code

RFC 9457 says `title` should be "a short, human-readable summary". The platform puts the error code
there instead, which looks wrong until you consider what `title` is *used* for.

The RFC also says `title` should not change from occurrence to occurrence — it identifies the problem
*type*. A human-readable summary that stays stable is exactly what a code is, and prose does not stay
stable: it gets reworded, translated, and expanded with context.

Meanwhile the human-readable, occurrence-specific text has a home already: `detail`. So the platform
puts the stable identifier in the stable field and the variable text in the variable field. Clients
that display `title` show a code, which is what a support ticket needs anyway.

### 6.2 Why `timestamp` is a string

`problem.setProperty("timestamp", Instant.now(clock).toString())` — an ISO-8601 string, not the
`Instant` itself.

Serialising an `Instant` produces different JSON depending on whether `jackson-datatype-jsr310` is
registered and how `WRITE_DATES_AS_TIMESTAMPS` is configured: `"2026-08-04T10:15:30.123Z"`, or
`1785866130.123`, or `[2026,8,4,10,15,30,123000000]`. All three are valid Jackson output; only one is
a usable API contract.

Formatting to a string in the factory means the wire shape does not depend on which Jackson modules
an application happens to have. This is a small decision that prevents a very annoying class of
cross-service bug.

### 6.3 The clock is injected

`ProblemDetailFactory` takes a `Clock`. That is not ceremony — it makes the `timestamp` field
assertable in a test with a fixed clock, rather than "assert it is a string that parses". Declare a
`Clock` bean and the platform uses it.

### 6.4 Redaction is name-based, and that is a trade-off

```java
if (name.contains("password") || name.contains("secret") || name.contains("token")) {
    return "REDACTED";
}
```

Three substrings, case-insensitive. Deliberately simple, with known limits:

- **False negatives.** `pwd`, `passphrase`, `credential`, `apiKey`, `pin` are not covered.
- **False positives.** A field called `tokenCount` gets redacted. Harmless, occasionally confusing.
- **Only `rejectedValue` is covered.** A constraint *message* that interpolates the value would leak
  it, and a customizer that adds the raw request body would leak everything.

The alternative — an annotation-driven allow-list — would be more precise and would require every
service to remember to apply it, which is exactly the failure mode this capability exists to remove. A
substring check that always runs beats a precise check that is sometimes forgotten.

!!! success "Best practice — name credential fields so the default catches them"
    `password`, `clientSecret`, `refreshToken` are caught. `pwd` and `credential` are not. This is
    free defence; take it.

### 6.5 Where the two validation paths diverge

They produce the same body, but the `field` value differs in a way that will confuse you once:

| | `field` value |
|---|---|
| Request-body validation | The property path: `customerId`, `items[0].quantity` |
| Method validation | The full path including the method: `lookup.declarationId` |

That is `ConstraintViolationException`'s own property-path format, not a platform choice. If a client
binds errors to form fields by name, method-validation failures will not match. Which is a good reason
to keep the *client-facing* validation at the request-body boundary and use method validation for
internal contract enforcement.

### 6.6 Global errors and cross-field validation

Class-level constraints — "delivery date must be after order date" — produce **global** errors rather
than field errors. The platform includes them in the same `errors[]` array with `field` set to the
object name and `rejectedValue` set to `null`:

```json
{"field": "createOrderRequest", "message": "delivery must be after order date", "rejectedValue": null}
```

!!! tip "Give cross-field constraints a meaningful object name"
    The `field` value is the bean name, which is rarely useful to a client. If cross-field errors
    matter to your consumers, consider a class-level constraint that reports against a specific
    property path instead, using `ConstraintValidatorContext`'s node builder.

### 6.7 What happens when errors is absent but validation is present

A legitimate configuration — a non-web service that validates method arguments. `@Validated` still
works and `ConstraintViolationException` still throws, but nothing maps it to a response, because
there is no response. It propagates to your caller as an ordinary exception. That is correct
behaviour, and worth knowing before you conclude something is broken.

The reverse (errors without validation) is also fine: `map-validation` simply never has anything to
map.

### 6.8 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Keeping a legacy `@RestControllerAdvice` | It was there before the platform | Delete it. It wins on precedence and silently disables the platform's shape |
| `@Valid` forgotten on the parameter | Constraints on the record look sufficient | The annotation on the parameter is the trigger |
| Expecting `@Ulid` to reject `null` | Reasonable assumption, wrong | Bean Validation convention. Add `@NotNull` |
| Self-invocation defeating method validation | Nothing warns you | Call through the proxy, or move the check |
| Putting PII in a mapped exception message | The message is "just for the client" | It *is* for the client. Assume it is logged and screenshotted |
| Leaving `include-stacktrace: true` after debugging | It was so useful | Confine it to the `local` profile block |
| A customizer that throws | It is on the error path; nothing tests it | Guard everything; default to no-op |
| Reusing `DC-CORE-0400`/`0500` for your own failures | They are conveniently generic | They are platform-owned. Mint your own |
| Catching `BusinessException` to log it | Looks conscientious | The platform logs it. Catching and rethrowing adds a duplicate line |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-minimal`](../../examples.md) (`core` + `errors` + `logging`, and a
`GET /widgets/{id}` that always throws), or a service from the [archetype](../../quickstart.md).

### Lab 1 — Basic: read the contract

**Goal.** See all four response shapes the capability produces, and find the correlated log line for
each.

**Steps.**

1. `curl -i` an endpoint that throws `NotFoundException`. Record status, content type, and every JSON
   field.
2. Add an endpoint that throws a bare `BusinessException`. Note the status without an explicit hint.
3. Add an endpoint that throws `new IllegalStateException("connection to db-prod-3 refused")`.
   Compare what the *response* says with what the *log* says.
4. Add a `@Valid @RequestBody` record with `@NotBlankTrimmed` and post an invalid body.
5. For each of the four, grep the log for the `correlationId` from the response.

**Expected outcome.** Steps 1, 2, and 4 give 404, 422, and 400 with useful detail. Step 3 gives a 500
whose `detail` is `"An unexpected error occurred."` and whose *log* contains `db-prod-3`. That gap is
the whole point — write down why it matters.

**Hints.**

- Not seeing a log line for step 1? Business failures log at DEBUG. See §5.3.
- `content-type` should be `application/problem+json`, not `application/json`. If it is not, an
  application advice is intercepting.

**How to verify.** A `@PlatformWebTest` asserting status, content type, `code`, and — for step 3 —
that the response body does **not** contain `db-prod-3`.

### Lab 2 — Intermediate: design a domain error taxonomy

**Goal.** Build a small, coherent code space, and feel the difference between the layers.

**Steps.**

1. Create `OrderErrors` with `static final ErrorCode` constants for at least five distinct failures.
   Assign ranges deliberately: `0001`–`0399` business, `0400`–`0499` client, `0500`–`0599`
   infrastructure.
2. Implement each as the right exception type: `NotFoundException`, `ConflictException`, or
   `BusinessException` with an explicit `HttpStatusHint`.
3. Add a `ProblemDetailCustomizer` that appends a `documentation` property linking to your runbook,
   built from the `code`.
4. Add `@Validated` to a service and a `@Ulid` parameter constraint. Compare the `field` value in the
   response with a request-body validation failure. Explain the difference (§6.5).
5. Deliberately make the customizer throw for one code. Observe the result.

**Expected outcome.** Five distinct codes each mapping to the right status. The `documentation` link
on every body. Step 4 shows `lookup.declarationId` versus `customerId`. Step 5 turns a clean 404 into
a 500 — reproduce it once so you never write a throwing customizer again.

**Hints.**

- Codes must match `^DC-[A-Z]{2,8}-\d{4}$` or construction throws. That is intentional.
- The customizer's `source` is the originating exception — useful for conditional enrichment, and a
  trap if you dereference something on it without checking.

**How to verify.** A parameterised test over your five codes asserting `(status, code)` pairs, plus
one asserting the `documentation` property is present.

### Lab 3 — Advanced: close a leak, then measure it

**Goal.** Find every path by which sensitive data can escape through an error response, and shut them.

**Steps.**

1. Build a `ChangePasswordRequest` with fields `currentPassword`, `newPwd`, and `mfaCode`, each with a
   constraint. Post an invalid body.
2. Note which `rejectedValue`s were redacted and which were not. Explain why (§6.4).
3. Fix it two ways: rename fields so the default catches them, **and** write a
   `ProblemDetailCustomizer` that scrubs `errors[]` against your own list. Argue which belongs in a
   codebase.
4. Add a custom constraint whose *message* interpolates the rejected value (`"{value} is not a valid
   code"`). Post a secret. Observe that redaction did not help.
5. Turn on `include-stacktrace`, post something that 500s, and read the body as if you were an
   attacker. Write down three things you learned about the service.
6. Turn it off. Add a test that fails if it is ever `true` outside the `local` profile.

**Expected outcome.** `currentPassword` redacted; `newPwd` and `mfaCode` not. Step 4 shows that
redaction protects `rejectedValue` and nothing else — a constraint message is a second, independent
leak path. Step 6 is the deliverable: a test, not a note in a wiki.

**Hints.**

- Constraint messages are interpolated before the platform sees them. There is no hook to redact
  them; the fix is to not write `{value}` into a message for a sensitive field.
- For step 6, assert on the bound `ErrorsProperties`, or on `/actuator/configprops` in an integration
  test.

**How to verify.** A test asserting no response body for any invalid `ChangePasswordRequest` contains
the literal secret you posted — checked against the whole serialised body, not field by field.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-errors</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-validation</artifactId>
</dependency>
```

**Throw the right thing**

| Situation | Throw | Status |
|---|---|---|
| Resource does not exist | `NotFoundException` | 404 |
| Clashes with current state | `ConflictException` | 409 |
| Business rule says no | `BusinessException` | 422 |
| Request is malformed | `BusinessException(code, msg, BAD_REQUEST)` | 400 |
| Infrastructure failed | Any other `PlatformException` | 500 |
| Anything else | Do not catch it | 500, `DC-CORE-0500` |

**Constraints**

| Constraint | `null` | Notes |
|---|---|---|
| `@NotBlankTrimmed` | invalid | Whitespace-only fails |
| `@Ulid` | valid | 26 Crockford base32, first char `0`–`7` |
| `@SafeText` | valid | ISO control chars only. Not an XSS defence |
| `@FutureInstant` | valid | Uses the validator's `ClockProvider` |

**Properties**

| Key | Default |
|---|---|
| `dc.platform.errors.enabled` | `true` |
| `dc.platform.errors.type-base-uri` | `https://errors.dc.com/` |
| `dc.platform.errors.map-validation` | `true` |
| `dc.platform.errors.include-stacktrace` | `false` — **keep it that way outside `local`** |
| `dc.platform.validation.enabled` | `true` |

**Problem body**

`type` · `title` (= code) · `status` · `detail` · `instance` · `code` · `correlationId` · `timestamp`
· `errors[]` (validation) · `stacktrace` (never in production)

**Diagnose it**

```bash
curl -i localhost:8080/orders/nope                     # is it application/problem+json?
curl -s localhost:8080/actuator/env/dc.platform.errors.include-stacktrace
# then, when a 4xx is unexplained:
logging.level.ae.gov.dubaicustoms.platform.errors=DEBUG
```

**Rules of thumb**

- Delete every hand-written error advice. Yours wins on precedence and silently disables the platform.
- Write `detail` for a client to read; write diagnostics to the log.
- Alert on `code`, never on message text. Alert hardest on `DC-CORE-0500`.
- `@Valid` on the parameter, `@Validated` on the class. Self-invocation defeats both.
- Three of the four platform constraints accept `null`. Add `@NotNull` when presence is required.
- Name credential fields `password`/`secret`/`token` so redaction catches them for free.
- A customizer must never throw, and must never be slow.
- `include-stacktrace` belongs in the `local` profile block and nowhere else.

---

**Next:** [Chapter 3 — Logging and Observability](03-logging-observability.md), which is where the
`correlationId` in every problem body becomes a query.

**Reference:** [modules/errors.md](../../modules/errors.md) ·
[modules/validation.md](../../modules/validation.md) ·
[error codes](../../reference/error-codes.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
