# Chapter 1 — Core: Request Context and the Capability Model

> **Capabilities covered:** `core`
>
> The request-scoped context every other capability reads from, and how the platform reports what is
> live.
>
> **Starter:** `platform-starter-core` · **Reference:** [modules/core.md](../../modules/core.md)

---

## 1. Introduction and Business Value

Core is the smallest capability in the platform and the one everything else stands on. It does three
things:

1. Gives every request a **correlation identifier** and makes it available everywhere without you
   passing it anywhere.
2. Defines the **exception and error-code taxonomy** every other capability throws from.
3. Makes the running instance **self-describing** — what platform behaviour is actually live, with
   which provider.

### The problem it solves

A user reports that their order failed at 14:32. In a fleet of services, answering "what happened?"
means correlating across the gateway's log, your service's log, the payments service's log, the message
the order published, and the scheduled job that later retried it. Without a shared token you are
matching timestamps by eye across five log stores — and the events you most want, from the failing edge
cases, are the hardest to line up.

With a correlation id, that whole investigation is one query.

### Why the platform's version is worth understanding

Correlation ids are not a novel idea, and most teams implement some version of them. Two details
separate the platform's implementation from the version teams usually write.

**The filter runs at the highest precedence in the chain — ahead of security.** This sounds like a
detail and is not. A hand-rolled correlation filter is usually registered wherever it lands, which in
practice is *after* the security filter chain. The result: any request rejected by authentication
produces log lines with no correlation id. That is precisely the population you most need to trace —
failed logins, expired tokens, permission denials, the traffic an incident is usually about — and it is
exactly the population that gets missed.

**The id is a typed value, not a string.** `CorrelationId` validates its format (32 lowercase hex — a
UUID without dashes) at construction. A malformed inbound header cannot poison your logs, your
downstream calls, or your message headers, because it never becomes a `CorrelationId` in the first
place.

### Impact of absence

| Without core | What actually happens |
|---|---|
| No correlation id | Cross-service debugging degrades to timestamp matching. Time to diagnose goes up by hours, not minutes |
| No id on auth failures | The failure population you most need to trace is the one you cannot |
| No shared exception model | Every capability invents its own exception type and HTTP mapping; response shapes diverge per endpoint |
| No error-code taxonomy | Runbooks and alerts have nothing stable to key on, so they key on message strings, which change |
| No capability report | "Is the platform even active?" costs a debug-mode restart and a condition-report read |

!!! success "Best practice — core is not optional"
    Every service should have `platform-starter-core`. Every other platform starter assumes it. The
    [archetype](../../quickstart.md) includes it, and there is no realistic reason to remove it.

---

## 2. Core Concepts and Underlying Principles

### 2.1 Servlet filters, and why order is everything

A servlet **filter** wraps request handling. Filters form a chain; each one can do work before and
after calling the next.

```
  request
     |
     v
  [ CorrelationIdFilter ]   <-- HIGHEST_PRECEDENCE (this chapter)
     |
     v
  [ Spring Security filter chain ]
     |                                  <-- 401/403 are produced HERE,
     v                                      before DispatcherServlet
  [ other filters ]
     |
     v
  [ DispatcherServlet ]  ->  @RestController  ->  @RestControllerAdvice
     |
     v
  response
```

Two consequences fall out of that diagram, and both shape the platform:

- The correlation filter must be **outermost**, so that everything inside it — including the security
  chain — runs with a correlation id already established.
- `@RestControllerAdvice` sits *inside* `DispatcherServlet`, so it **cannot** see failures produced by
  the security chain. That is why the [errors](02-errors-validation.md) and
  [security](05-security-authz.md) capabilities install a separate entry-point and denied-handler pair
  to render 401 and 403 — covered in those chapters, and a direct consequence of this ordering.

Spring Boot registers a filter through a `FilterRegistrationBean`, which is where the order is set:

```java
@Bean
FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
    FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
}
```

`OncePerRequestFilter`, which the platform's filter extends, guarantees the filter body runs exactly
once per request even when the request is forwarded or included internally — without it, a `FORWARD`
dispatch would open a second correlation scope.

### 2.2 MDC — how a log line gets a field you never passed

SLF4J's **Mapped Diagnostic Context** is a thread-local `Map<String, String>` that logging frameworks
consult when formatting each event. Put `correlationId` into the MDC at the start of a request, and
every log statement on that thread carries it — with no change to any logging call site.

```java
MDC.put("correlationId", "9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7");
log.info("archiving invoice");   // the field is attached automatically
```

This is why correlation "just works" in this platform. `RequestContext` publishes into the MDC, the
[logging capability](03-logging-observability.md) lifts every MDC entry into a JSON field, and the
result is a queryable `correlationId` on every line — including lines written by Spring, Hibernate, and
third-party libraries that have never heard of the platform.

!!! warning "MDC is thread-confined, and that is a real constraint"
    An MDC entry belongs to the thread that set it. Hand work to an `@Async` method, a thread pool, a
    `CompletableFuture`, or a reactive scheduler and **the correlation id does not follow**. Section 6
    covers what to do about it. This is the single most common way correlation is silently lost.

### 2.3 Scoping and the stack

A naive implementation sets the MDC on the way in and clears it on the way out. That breaks in one
common case: **nested contexts**. A message handler executing inside a web request, or a test that
opens a context around code that opens another, would clear the outer context when the inner one
finished.

`RequestContext` therefore maintains a **stack of scopes** per thread. Each scope remembers the MDC
values it shadowed, so closing it restores the previous state *exactly* rather than clearing. Reads
resolve against the innermost scope.

```
  open(A)         stack: [A]         MDC: correlationId=A
    open(B)       stack: [B, A]      MDC: correlationId=B   (A's value shadowed)
    close(B)      stack: [A]         MDC: correlationId=A   (restored, not cleared)
  close(A)        stack: []          MDC: (removed)
```

!!! note "Why not just clear the MDC on close?"
    Because the MDC is shared with anything else on the thread — including MDC entries your own code
    set. Clearing is a destructive operation on state you do not own. Restoring what was shadowed is
    the only correct behaviour, and it is what makes nested scopes safe.

### 2.4 Error codes as a support contract

An error code is not an exception type. It is a **stable, machine-readable identifier that appears in
problem responses, logs, dashboards, alerts, and runbooks** — which means it can never be renamed or
reused, in the same way a public method signature cannot.

The platform's format is `DC-<CAP>-<NNNN>`, validated by regex at construction, with a numbering
convention:

| Range | Meaning | Example |
|---|---|---|
| `0001`–`0399` | Business failure — the domain said no | `DC-ORDER-0012` |
| `0400`–`0499` | Client failure — the caller sent something wrong | `DC-ORDER-0404` |
| `0500`–`0599` | Infrastructure failure — something we depend on broke | `DC-MSG-0500` |

Uniqueness across the whole platform is checked **at build time**, and codes are exported to
`target/error-codes.csv`, which feeds the generated
[error-code reference](../../reference/error-codes.md). Two capabilities cannot ship the same code,
because an alert keyed on a duplicated code silently misroutes.

!!! success "Best practice — give your own domain a code prefix"
    `DC-ORDER-*`, `DC-INV-*`. Then your runbooks, dashboards, and alerts key on codes rather than on
    message text, and rewording an error message stops being a breaking change for operations.

### 2.5 A self-describing runtime

Each capability's auto-configuration contributes a `CapabilityDescriptor` bean — a
`(name, status, detail)` triple. Core collects them through an `ObjectProvider` and renders them two
ways:

- **A startup banner**, one INFO line, capabilities sorted by name.
- **`/actuator/platform`**, the same information as JSON, served by the
  [observability](03-logging-observability.md) capability.

This exists because "why isn't X happening?" is otherwise answered by classpath archaeology. The
descriptor is **runtime truth** — what is live in *this* instance, with *this* provider — as opposed to
build-time inference from a POM that may not match what was deployed.

---

## 3. Feature Reference

### 3.1 Public API

Everything in `ae.gov.dubaicustoms.platform.core` and its subpackages. All types are
`@API(status = STABLE, since = "0.1.0")`.

| Type | Kind | Purpose |
|---|---|---|
| `CorrelationId` | record | The identifier. Validates `^[0-9a-f]{32}$` at construction |
| `RequestContext` | final class | Static, read-only access to the current context |
| `PlatformException` | abstract class | Root of the platform exception hierarchy; carries an `ErrorCode` |
| `ErrorCode` | record | Stable identifier. Validates `^DC-[A-Z]{2,8}-\d{4}$` |
| `CapabilityDescriptor` | record | `(name, status, detail)` — one active capability |
| `@PlatformApi` | annotation | Marks a SemVer-guaranteed public type |
| `@PlatformInternal` | annotation | Marks a type or method with no compatibility guarantee |

#### `CorrelationId`

| Member | Signature | Notes |
|---|---|---|
| Constructor | `CorrelationId(String value)` | Throws `IllegalArgumentException` unless 32 lowercase hex characters |
| `value()` | `String value()` | The identifier text |
| `random()` | `static CorrelationId random()` | A fresh id — `UUID.randomUUID()` with dashes stripped |

#### `RequestContext`

| Member | Signature | Notes |
|---|---|---|
| `correlationId()` | `static Optional<CorrelationId> correlationId()` | Innermost open scope; empty when none. **Never returns null** |
| `asMap()` | `static Map<String, String> asMap()` | Immutable snapshot: correlation id plus extras, innermost wins. Empty map when no scope |
| `open(id, extras)` | `static AutoCloseable open(CorrelationId, Map<String,String>)` | **`@PlatformInternal`.** Opens a scope. Platform filters and tests only — always try-with-resources |

#### `PlatformException`

| Member | Signature | Notes |
|---|---|---|
| Constructor | `protected PlatformException(ErrorCode, String message)` | `message` must not be null |
| Constructor | `protected PlatformException(ErrorCode, String message, Throwable cause)` | `cause` may be null |
| `code()` | `ErrorCode code()` | Never null |

#### `CapabilityDescriptor`

| Component | Type | Notes |
|---|---|---|
| `name` | `String` | Capability name, e.g. `core`, `messaging` |
| `status` | `String` | Conventionally `ACTIVE`; `INACTIVE` when a capability is present but unconfigured |
| `detail` | `String` | Selected provider or a one-line note. **May be empty, never null** |

Rendered as `name[status]` when `detail` is empty, `name[status] (detail)` otherwise.

### 3.2 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.core.enabled` | Boolean | `true` | Kill switch for the whole capability | Essentially never. Disabling removes correlation from everything downstream |
| `dc.platform.core.banner-enabled` | Boolean | `true` | Log the startup capability banner | When your log ingest treats the multi-capability line as noise. `/actuator/platform` still works |
| `dc.platform.core.correlation.header-name` | String | `X-Correlation-Id` | The HTTP header carrying the id | When an existing gateway or CDN already standardises on another name |
| `dc.platform.core.correlation.generate-if-missing` | Boolean | `true` | Mint a fresh id when the request carries none | When an upstream gateway is authoritative and a missing header should mean "no context", not "new context" |

!!! warning "`generate-if-missing: false` means no context at all"
    When the header is absent *and* generation is off, the filter passes the request through **without
    opening a scope**. `RequestContext.correlationId()` returns empty, and nothing downstream —
    logging, messaging, audit, outbound calls — has an id to propagate. Only set this when something
    upstream is guaranteed to supply the header.

### 3.3 Auto-configuration classes

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `CoreContextAutoConfiguration` | Servlet web app **and** `CorrelationId` on classpath **and** `core.enabled != false` | `correlationIdFilter`, `platformCorrelationFilterRegistration` | You define a `CorrelationIdFilter` bean (the platform registration then wraps *yours*), or a `FilterRegistrationBean` named `platformCorrelationFilterRegistration` |
| `PlatformBannerAutoConfiguration` | `CorrelationId` on classpath **and** `core.enabled != false` | `coreCapabilityDescriptor`, `platformBannerRunner` | You define an `ApplicationRunner` bean named `platformBannerRunner`; the runner is additionally gated on `banner-enabled` |

!!! note "The descriptor is contributed even when the banner is off"
    `coreCapabilityDescriptor` is not gated on `banner-enabled` — only the runner is. That way
    `/actuator/platform` and any other descriptor consumer still see `core[ACTIVE]` when you have
    silenced the startup line.

!!! note "Non-web applications get no filter"
    `CoreContextAutoConfiguration` is `@ConditionalOnWebApplication(SERVLET)`. A batch job or a
    message-only consumer gets the exception model, the error codes, and the banner, but nothing opens
    a scope for it. See §6.2 for establishing context in those applications.

### 3.4 Extension points

| Extension | How | Effect |
|---|---|---|
| Contribute a capability to the report | Declare a `CapabilityDescriptor` bean | Appears in the banner and `/actuator/platform` |
| Replace the filter's behaviour | Declare a `CorrelationIdFilter` bean | The platform's registration wraps yours, at highest precedence |
| Take over registration entirely | Declare a `FilterRegistrationBean` named `platformCorrelationFilterRegistration` | You control order and URL patterns |
| Replace the banner | Declare an `ApplicationRunner` named `platformBannerRunner` | Your runner logs instead |
| Extend the exception taxonomy | Subclass `PlatformException` with your own `ErrorCode` | Rendered as a problem response by [errors](02-errors-validation.md) |

---

## 4. How-to Guide

### 4.1 Add the capability

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-core</artifactId>
</dependency>
```

No version — the [BOM](../../reference/bom.md) manages it. No configuration. Start the app and you
should see:

```
platform: core[ACTIVE]
```

If you do not, jump to §5.3.

### 4.2 Verify correlation end to end

```bash
# Let the platform generate an id
curl -i localhost:8080/orders/123
# < X-Correlation-Id: 9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7

# Or supply your own — 32 lowercase hex
curl -i -H "X-Correlation-Id: 9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7" localhost:8080/orders/123
```

The response echoes the header, and every log line for that request carries the same value. The echo
happens **before** the chain runs, so the header survives even when the response commits early — which
is the case on most error paths.

!!! tip "A malformed inbound id is ignored, not rejected"
    Send `X-Correlation-Id: not-a-real-id` and the filter logs at DEBUG, discards it, and generates a
    fresh one. It does not fail the request: an upstream caller's formatting bug should not become your
    outage. If you are debugging "my id isn't propagating", turn on DEBUG for `CorrelationIdFilter` and
    you will see exactly why.

### 4.3 Read the correlation id in your own code

You will need this rarely — logging, messaging, audit, and outbound calls all pick it up
automatically. When you do need it explicitly, for instance to hand it to a component that leaves the
request thread:

```java snippet:book-01-request-context
@Service
class InvoiceService {

    private final InvoiceArchive archive;

    InvoiceService(InvoiceArchive archive) {
        this.archive = archive;
    }

    void archive(String invoiceId) {
        String correlation = RequestContext.correlationId()
                .map(CorrelationId::value)
                .orElse("none");
        archive.submit(invoiceId, correlation);
    }
}

interface InvoiceArchive {
    void submit(String invoiceId, String correlationId);
}
```

For everything in the context, not only the id:

```java
Map<String, String> context = RequestContext.asMap();
// {"correlationId": "9f2c...", "tenant": "dxb"}  — extras included
```

!!! warning "Do not log the correlation id explicitly"
    ```java
    log.info("archiving invoice {} correlation={}", id, correlation);   // redundant
    ```
    It is already on the line, as a structured field, put there by the MDC. Adding it to the message
    duplicates it and makes the message text harder to search on. Log the thing that is *not* already
    there.

### 4.4 Define your own error codes and exceptions

Domain code should throw *intent*, not HTTP. Subclass `PlatformException` and give it a stable code:

```java snippet:book-01-platform-exception
final class InvoiceArchiveException extends PlatformException {

    static final ErrorCode ARCHIVE_FAILED = new ErrorCode("DC-INV-0500");

    InvoiceArchiveException(String invoiceId, Throwable cause) {
        super(ARCHIVE_FAILED, "could not archive invoice " + invoiceId, cause);
    }
}
```

`0500`, because this is an infrastructure failure. When the [errors](02-errors-validation.md)
capability is present this renders as a problem response carrying `DC-INV-0500` and the correlation
id — and the message goes to the log, not to the caller.

!!! success "Best practice — codes are constants, declared once"
    Put them in one class per domain area, `static final`. A code inlined at a throw site gets
    copy-pasted, and then two different failures share an identifier a dashboard cannot tell apart.

### 4.5 Report your own capability

If your team builds a shared library with its own auto-configuration, contribute a descriptor so it
shows up alongside the platform's:

```java snippet:book-01-capability-descriptor
@Configuration
class InvoiceArchiveConfiguration {

    @Bean
    CapabilityDescriptor invoiceArchiveCapabilityDescriptor() {
        return new CapabilityDescriptor("invoice-archive", "ACTIVE", "s3");
    }
}
```

```
platform: core[ACTIVE], invoice-archive[ACTIVE] (s3), storage[ACTIVE] (s3)
```

### 4.6 Change the header name

When a gateway already standardises on a different header:

```yaml
dc:
  platform:
    core:
      correlation:
        header-name: X-Request-Id
```

That one key changes the header read, the header echoed, **and** the header the
[REST client](06-restclient-resilience.md) sends outbound — the three stay consistent because they all
read the same property.

### 4.7 Establish context in a test

`RequestContext.open` is `@PlatformInternal`, which means the platform does not promise its signature
across versions — but it is deliberately usable from tests, because otherwise context-dependent code
would be untestable. Always try-with-resources:

```java
try (AutoCloseable scope = RequestContext.open(CorrelationId.random(), Map.of("tenant", "dxb"))) {
    invoiceService.archive("INV-1");
    // assertions run inside the scope
}
```

For controller tests, prefer the real filter — `@PlatformWebTest` wires it, or for a standalone
`MockMvc` setup:

```java
MockMvcBuilders.standaloneSetup(controller)
        .addFilters(new CorrelationIdFilter("X-Correlation-Id", true))
        .build();
```

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

Almost nothing. Core is one of the few capabilities with no meaningful production tuning.

| Environment | Setting | Why |
|---|---|---|
| All | Defaults | The header name and generation policy are fleet-wide conventions. Change them fleet-wide or not at all |
| Behind an authoritative gateway | `generate-if-missing: false` | Only if the gateway is *guaranteed* to set the header. See the warning in §3.2 |
| Noisy log ingest | `banner-enabled: false` | `/actuator/platform` still reports everything |

!!! warning "Do not vary `header-name` per service"
    Correlation only works if every hop agrees on the header. A service that reads `X-Request-Id` while
    its callers send `X-Correlation-Id` silently starts a new trace on every inbound call, and the
    break is invisible — each service's logs look fine in isolation.

### 5.2 What to monitor

Core emits no metrics of its own; it is plumbing. What you monitor is whether the *plumbing is
working*:

| Check | How | Alert when |
|---|---|---|
| Correlation coverage | Proportion of log lines with a non-empty `correlationId` field | It drops. A sudden fall means a filter ordering change or a thread-boundary leak |
| Capability drift | `/actuator/platform` across the fleet | A service reports a capability set that differs from its intended profile |
| Trace continuity | The same id appearing in caller and callee logs | Ids stop crossing a specific hop |

!!! tip "Correlation coverage is the single most valuable core health signal"
    A log-store query for `NOT _exists_: correlationId` on your service's index tells you immediately
    whether something is escaping the context — a new async path, a new thread pool, a filter someone
    registered ahead of the platform's.

### 5.3 Troubleshooting

**The banner does not appear at startup.**

1. Is the starter on the classpath? `mvn dependency:tree | grep platform-starter-core`
2. Is it disabled? `curl -s localhost:8080/actuator/env/dc.platform.core.enabled`
3. Is only the banner off? Check `dc.platform.core.banner-enabled` — `/actuator/platform` is unaffected
4. Still nothing? `--debug`, and read the **negative matches** section for
   `PlatformBannerAutoConfiguration`

**Log lines have no `correlationId`.**

| Cause | How to confirm | Fix |
|---|---|---|
| Not a servlet web application | No `spring-boot-starter-web` on the classpath | Expected — see §6.2 |
| Work moved to another thread | The lines missing the id come from `@Async`, an executor, or a `CompletableFuture` | §6.1 |
| A filter registered ahead of the platform's | `/actuator/beans`, or log the registration order | Give yours a lower precedence than `HIGHEST_PRECEDENCE` |
| Header absent and generation off | `generate-if-missing` is `false` and the caller sent nothing | Set it back to `true`, or fix the caller |
| Logging capability absent | Logs are plain text, not JSON | Add `platform-starter-logging` — the MDC is populated, but nothing is lifting it into fields |

**The id changes mid-request.** Something opened a nested scope and did not close it, or closed it out
of order. The scope stack tolerates out-of-order closes defensively, but the symptom points at a
`RequestContext.open` call that is not in a try-with-resources.

**The response carries no `X-Correlation-Id` header.** The echo happens before the chain runs, so the
only way to lose it is a downstream component overwriting the response headers wholesale, or an error
path that constructs a fresh response. Check any `HandlerInterceptor` or filter that calls
`response.reset()`.

### 5.4 Scaling and performance

Core's cost is negligible, and worth stating precisely so it never becomes a suspect during a
performance investigation:

- **Per request:** one `UUID.randomUUID()` (only when generating), one regex match, two `ThreadLocal`
  operations, and a handful of `MDC` map writes. Tens of nanoseconds.
- **Memory:** one `Scope` object per open context, released on close. The `ThreadLocal` is removed when
  the stack empties, which matters on pooled platform threads.
- **Contention:** none. Everything is thread-confined.

!!! note "Virtual threads"
    Core is virtual-thread-safe. `ThreadLocal` works on virtual threads, and because each request gets
    its own virtual thread there is no pooled-thread leakage to worry about. The scope stack is still
    removed on close, so an unbounded number of virtual threads does not accumulate state.

### 5.5 Security considerations

**A correlation id is not a secret, and it is not a session token.** It is attacker-visible (it is
echoed on the response), attacker-supplied (a caller can set the header), and it grants nothing. That
is fine and intended — but it means:

- **Never authenticate or authorize on it.** It is a debugging token.
- **Never put sensitive data in context extras.** Extras go into the MDC, which goes into every log
  line. A tenant identifier is appropriate; a national ID number is not.
- **The format check is a defence.** Because `CorrelationId` validates `^[0-9a-f]{32}$`, an attacker
  cannot inject newlines, ANSI escapes, or JSON fragments through the header into your log store. A
  string-typed implementation would be a log-injection vector. This is the same reasoning behind
  [`@SafeText`](02-errors-validation.md).

---

## 6. Deep Dive

### 6.1 The thread-boundary problem

This is the one genuinely hard thing about correlation, and it is worth understanding rather than
discovering.

`RequestContext` is backed by a `ThreadLocal`. The moment work moves to a different thread, the context
does not follow:

```
  request thread                          pool thread
  --------------                          -----------
  open(scope)         MDC: id=9f2c...
  correlationId() -> 9f2c...
  executor.submit(task) ----------------> task runs
                                          correlationId() -> empty
                                          MDC: (nothing)
  close(scope)
```

Three ways to deal with it, in order of preference:

**1. Do not cross the boundary.** Most `@Async` in a request path is there to "make it faster" and is
better served by the platform's own async facilities, which handle propagation:
[messaging](07-messaging-events.md) for work that should outlive the request, and
[events](07-messaging-events.md) for after-commit side effects.

**2. Capture and re-establish explicitly.** Read the id on the request thread, pass it as a parameter,
and open a scope on the other side. This is what §4.3's snippet does. It is verbose but it is obvious,
and obvious wins in code that runs at 3am.

**3. Use Micrometer context propagation.** When the [observability](03-logging-observability.md)
capability is present, Micrometer's context-propagation library can carry `ThreadLocal` state across
`Executor` boundaries — wrap the executor with `ContextExecutorService`. This is the least intrusive
option and the easiest to get subtly wrong; verify it with an actual log assertion rather than
assuming.

!!! warning "Reactive code needs a different approach entirely"
    `ThreadLocal` is meaningless in a reactive pipeline, where a single logical request hops threads
    freely. The platform's core capability is servlet-oriented
    (`@ConditionalOnWebApplication(SERVLET)`). If you are building a reactive service, correlation
    needs to ride in the Reactor `Context`, and you should treat that as a design decision to make
    deliberately rather than a default to inherit.

### 6.2 Establishing context outside a web request

Batch jobs, message handlers, and scheduled tasks have no filter to open a scope for them. Some
platform capabilities handle this for you — the [messaging](07-messaging-events.md) capability opens a
scope from the inbound `correlationId` header before invoking your `@EventHandler`. For everything
else, open one yourself at the outermost boundary of the unit of work:

```java
try (AutoCloseable scope = RequestContext.open(CorrelationId.random(), Map.of("job", "nightly-reconcile"))) {
    reconciliationService.run();
}
```

!!! success "Best practice — one scope per unit of work, at the outermost boundary"
    For a scheduled job that is the whole run; for a batch, one per item is usually right, so a failure
    on item 400 is traceable independently. Opening a scope deep inside business logic makes the
    boundary invisible and the lifetime hard to reason about.

### 6.3 Filter ordering, and how to lose

`Ordered.HIGHEST_PRECEDENCE` is `Integer.MIN_VALUE`. You cannot register anything ahead of the
correlation filter through `FilterRegistrationBean` ordering alone — which is deliberate.

You *can* still end up ahead of it:

- A filter registered through `web.xml` or a servlet-container mechanism outside Spring's ordering.
- A `ServletContextInitializer` that adds a filter directly.
- The servlet container's own filters, or an APM agent's instrumentation.

If you must run something before correlation — an APM agent is the usual legitimate case — be aware
that its log lines will not carry an id, and check whether it can read the header itself.

To replace the filter but keep its precedence, declare a `CorrelationIdFilter` bean; the platform's
registration wraps yours. To control precedence too, declare the `FilterRegistrationBean` by name:

```java
@Bean
FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
    FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    registration.addUrlPatterns("/api/*");
    return registration;
}
```

!!! warning "Narrowing the URL pattern narrows your observability"
    `/*` is the default for a reason: actuator endpoints, error dispatches, and static resources all
    produce log lines. Restricting to `/api/*` means the lines from everything else are uncorrelated.

### 6.4 Why `open()` is `@PlatformInternal` but public

A deliberate tension. The method must be public — platform filters in other modules call it, and it
must be reachable from tests. But its signature is not something the platform wants to freeze: the
`extras` map in particular is a seam that may grow richer.

`@PlatformInternal` marks exactly that state: reachable, documented, but excluded from the binary
compatibility gate. Using it in test code is expected and supported. Using it in production application
code means you have taken on the risk that its signature changes in a minor release — and usually means
you should be looking at §6.2's pattern instead.

### 6.5 Why the id is 32 hex characters

Not arbitrary. A UUID with dashes stripped:

- **Round-trips cleanly.** No escaping needed in HTTP headers, AMQP properties, Kafka headers, JSON,
  SQL, or a log-store query. Dashes and braces cause trouble in at least one of those.
- **Matches W3C Trace Context.** The `trace-id` field in `traceparent` is exactly 32 lowercase hex
  characters. That alignment is what lets correlation and distributed tracing sit alongside each other
  without translation.
- **Is validatable.** A single anchored regex. That check is what makes the header safe to accept from
  an untrusted caller.

### 6.6 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Logging the id explicitly in the message | Habit from projects without MDC | Trust the field; log what is not already there |
| Passing `CorrelationId` through method signatures | Feels explicit and safe | It is thread-confined *and* already available. Only pass it across a thread boundary |
| Using the id as an idempotency key | Both are "a unique id per request" | A retry sends a *new* correlation id. Use [`@Idempotent`](10-coordination.md) with a business key |
| Putting PII in extras | Extras look like a convenient bag | Everything in extras lands in every log line, forever |
| Registering a filter that resets the response | Usually a CORS or header filter | Check anything calling `response.reset()`; it drops the echoed header |
| Catching `PlatformException` broadly | Looks like good hygiene | You are swallowing the code the errors capability needs. Let it propagate |
| Reusing an error code for a similar failure | Saves inventing a number | Two failures with one code cannot be told apart in a dashboard. Codes are cheap |
| Assuming context in an `@Async` method | Nothing warns you | §6.1 |

---

## 7. Exercises and Hands-on Labs

Starting point for all three: a service generated from the [archetype](../../quickstart.md), or
[`examples/example-minimal`](../../examples.md), which is `core` + `errors` + `logging` and nothing
else.

### Lab 1 — Basic: prove the correlation contract

**Goal.** Convince yourself that correlation works, and see the response echo.

**Steps.**

1. Boot the service and confirm the startup banner reads `platform: core[ACTIVE], ...`.
2. `curl -i` any endpoint with no correlation header. Note the `X-Correlation-Id` on the response.
3. Find that exact id in the service's log output.
4. Repeat, this time supplying `-H "X-Correlation-Id: 00000000000000000000000000000001"`.
5. Repeat with a deliberately malformed value: `-H "X-Correlation-Id: hello"`.

**Expected outcome.** Steps 2–4 show the id you supplied (or one that was generated) on the response
and on every log line for that request. Step 5 shows a *different*, freshly generated id — the
malformed value was discarded, and the request succeeded anyway.

**Hints.**

- Not seeing the id in logs? Confirm `platform-starter-logging` is present; core populates the MDC, but
  something has to render it.
- Want to see *why* the malformed value was dropped? Set
  `logging.level.ae.gov.dubaicustoms.platform.core=DEBUG`.

**How to verify.** Write a test asserting the response header is present and matches `^[0-9a-f]{32}$`,
and a second asserting a supplied valid id is echoed unchanged.

### Lab 2 — Intermediate: model a domain failure properly

**Goal.** Throw a domain exception carrying a stable code, and see it become a correlated problem
response.

**Steps.**

1. Create an `ErrorCodes` class holding `static final ErrorCode` constants for your domain. Use the
   `0001`–`0399` range for a genuine business rule.
2. Write an exception extending `PlatformException` that carries one of them.
3. Throw it from a controller path for a specific input.
4. `curl -i` that path and read the response body.
5. Find the same correlation id in the log line that recorded the failure.
6. Now deliberately create a *second* exception reusing the *same* code, and write down what a
   dashboard keyed on that code would show.

**Expected outcome.** An RFC-9457 `application/problem+json` body carrying your `code`, the
`correlationId`, and a `timestamp`. The log line has the full detail; the response does not have your
stack trace. Step 6 should make you uncomfortable — that is the point.

**Hints.**

- The response shape comes from [errors](02-errors-validation.md), not core. Core supplies the
  taxonomy; errors renders it.
- Getting a 500 instead of the status you expected? A bare `PlatformException` has no HTTP mapping.
  Chapter 2 covers `BusinessException`, `NotFoundException`, and `ConflictException`, which do.

**How to verify.** A `@PlatformWebTest` asserting the status, `content-type: application/problem+json`,
and the `code` field.

### Lab 3 — Advanced: find and fix a context leak

**Goal.** Reproduce the thread-boundary problem deliberately, then fix it three different ways and
judge the trade-offs.

**Steps.**

1. Add a service method annotated `@Async` (with `@EnableAsync`) that logs a line.
2. Call it from a controller. Compare the correlation id on the controller's log line and the async
   one.
3. **Fix A** — read the id on the request thread, pass it as a parameter, and open a scope inside the
   async method.
4. **Fix B** — wrap the executor so context propagates, and verify by log assertion rather than by
   reading the code.
5. **Fix C** — remove the `@Async` and reach for [domain events](07-messaging-events.md) instead.
6. Write down which fix you would want to find in this codebase in a year, and why.

**Expected outcome.** Step 2 shows the async line with **no** correlation id — this is the failure
mode, reproduced on purpose. All three fixes restore it. Fix C is usually the right answer, because the
`@Async` was probably solving the wrong problem.

**Hints.**

- Asserting on log output is awkward; a `ListAppender<ILoggingEvent>` attached to the logger under test
  is the clean way, and it gives you the MDC map per event.
- Fix B is the one most likely to appear to work and not. Assert it.

**How to verify.** A test that captures log events from both threads and asserts the MDC
`correlationId` is present, equal, and non-empty on both.

---

## 8. Checklist / Quick Reference

**Add it**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-core</artifactId>
</dependency>
```

**API at a glance**

```java
RequestContext.correlationId()      // Optional<CorrelationId>, never null
RequestContext.asMap()              // immutable snapshot incl. extras
CorrelationId.random()              // 32 lowercase hex
new ErrorCode("DC-INV-0500")        // ^DC-[A-Z]{2,8}-\d{4}$
new CapabilityDescriptor(name, status, detail)
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.core.enabled` | `true` |
| `dc.platform.core.banner-enabled` | `true` |
| `dc.platform.core.correlation.header-name` | `X-Correlation-Id` |
| `dc.platform.core.correlation.generate-if-missing` | `true` |

**Error-code ranges** — `0001`–`0399` business · `0400`–`0499` client · `0500`–`0599` infrastructure

**Diagnose it**

```bash
curl -s localhost:8080/actuator/platform | jq           # what is live
curl -i localhost:8080/your/endpoint                    # X-Correlation-Id echoed?
curl -s localhost:8080/actuator/env/dc.platform.core.enabled
mvn spring-boot:run -Dspring-boot.run.arguments=--debug # negative matches
```

**Rules of thumb**

- Never log the correlation id explicitly — it is already a field.
- Never authorize on it, and never put PII in extras.
- Never use it as an idempotency key — a retry brings a new one.
- Declare error codes as constants, one per distinct failure, never reused.
- Crossing a thread boundary loses the context. Assume it does; prove it does not.
- Do not vary `header-name` per service — correlation only works if every hop agrees.

**Where the platform's version differs from the one you would write**

| | Typical hand-rolled | Platform |
|---|---|---|
| Filter order | Wherever it lands, usually after security | `HIGHEST_PRECEDENCE`, ahead of security |
| Id type | `String` | Validated `CorrelationId` record |
| Malformed inbound id | Propagated, or rejects the request | Discarded at DEBUG; a fresh one generated |
| Nested contexts | Cleared on exit | Stack, with exact restore |
| Response echo | After the chain, if at all | Before the chain, so it survives early commits |

---

**Next:** [Chapter 2 — Errors and Validation](02-errors-validation.md), which turns the exception
taxonomy defined here into the responses your clients actually see.

**Reference:** [modules/core.md](../../modules/core.md) ·
[configuration properties](../../reference/properties.md) ·
[error codes](../../reference/error-codes.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
