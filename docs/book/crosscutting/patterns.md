# Patterns and Anti-patterns

> The shapes that recur across every capability, and the ones that look reasonable and are not.

The fourteen capability chapters each covered one thing. This chapter covers what they have in common.
If you read only one cross-cutting chapter, read this one — the patterns here explain *why the
platform looks the way it does*, and the anti-patterns are the mistakes that recur in real services.

---

## Part I — The patterns

### 1. Back off when the user has spoken

Every platform bean carries `@ConditionalOnMissingBean`. Declare your own bean of the same type and
the platform's is never registered.

```java
@Bean
@ConditionalOnMissingBean(ObjectStore.class)
ObjectStore fsObjectStore(StorageProperties properties) { ... }
```

Seen in: **every capability, without exception**.

The consequence is the platform's central promise — *a set of defaults, not a cage*. You never fork a
platform module, never patch one, never wait for the platform team. You declare a bean.

!!! warning "Back-off is by type or by name, and the difference matters"
    Most are `@ConditionalOnMissingBean(SomeType.class)`. Several are
    `@ConditionalOnMissingBean(name = "specificBeanName")` — the correlation filter registration, the
    banner runner, the OpenAPI error customizer, the common-tags customizer.

    A name-based back-off means an accidentally identical bean name replaces platform behaviour
    silently. [Chapter 4](../chapters/04-openapi.md) §7 Lab 2 has you trigger that on purpose, because
    it is the kind of thing you only really learn once.

### 2. Add without replacing — the customizer tier

Sitting under the back-off pattern is a lighter one. Contribute a `*Customizer` bean and the platform
collects it through `ObjectProvider.orderedStream()` and applies it in `@Order`:

| Customizer | Adjusts |
|---|---|
| `ProblemDetailCustomizer` | Every outgoing error body |
| `SecurityCustomizer` | The security filter chain, **before** `anyRequest()` |
| `PlatformRestClientCustomizer` | A named client's builder |
| `LogSanitizer` | Values on their way to a log field |
| `MeterRegistryCustomizer` | Every meter (Spring's own type) |
| `OpenApiCustomizer` | The generated document (springdoc's own type) |

The distinction is worth internalising:

```
   Customizer tier  ->  ADD behaviour to a platform default    (cheap, composable, common)
   Provider tier    ->  REPLACE the engine entirely            (rare, deliberate, TCK-certified)
```

!!! success "Reach for a customizer first, always"
    Replacing a bean means you now own everything it did — including the parts you did not want to
    change and will not remember to maintain. [Chapter 5](../chapters/05-security-authz.md) §5.5 has
    the sharpest version: declaring one `SecurityFilterChain` to add a CORS rule silently discards
    deny-by-default, the RFC-9457 auth bodies, and the stateless posture.

**Universal customizer contract:** thread-safe, fast, and **must not throw**. A throwing customizer
breaks the thing it was decorating — a mapped 404 becomes a 500, a log line is lost, a builder fails
for every client.

### 3. Optional cross-capability enrichment

A capability that could use another does so **only when it is actually present**, guarded by
`@ConditionalOnClass` or `@ConditionalOnBean`.

| Enriched | By | When absent |
|---|---|---|
| [Data](../chapters/08-data.md) auditing | security | Auditor is `"system"` |
| [Audit](../chapters/12-audit.md) actor | security | Actor is `"anonymous"` |
| [Flags](../chapters/13-ratelimit-flags.md) targeting | security | No subject or tenant in context |
| [REST client](../chapters/06-restclient-resilience.md) token relay | security | No relay customizer exists |
| [Scheduling](../chapters/10-coordination.md) `@LockedSchedule` | locking | Runs **unlocked**, with a startup WARN |
| [Audit](../chapters/12-audit.md) sink | messaging, then JDBC | Falls back to the log sink |

This is what "a capability you don't add costs you nothing" means concretely. A batch job with a
database and no HTTP surface gets JPA audit columns without a single Spring Security class on its
classpath.

!!! warning "Degradation is only safe when it is visible"
    Two of these degrade to something *materially weaker*: `@LockedSchedule` runs unlocked, and audit
    falls back to a sink your compliance regime may not accept. Both announce it at startup — and both
    chapters tell you to treat that announcement as an error condition rather than information. A
    silent degradation would be a bug in the pattern.

### 4. Lowest-precedence opinions on third-party keys

The platform holds views about properties it does not own — `logging.config`, `management.*`,
`spring.jpa.*`, `resilience4j.*`. It expresses them through an `EnvironmentPostProcessor` contributing
a **named, lowest-precedence property source**:

| Source | Sets |
|---|---|
| `platform-logging-defaults` | `logging.config` → the shipped Logback configuration |
| `platform-observability-defaults` | Endpoint exposure, probes, health groups, baggage, OTLP posture |
| `platform-data-jpa-defaults` | `open-in-view=false`, batch size 50, UTC time zone |
| `platform-resilience-defaults` | Retry, circuit-breaker, and time-limiter tuning |

Two properties make this work, and both are essential:

- **Lowest precedence** — your configuration always wins, so you never fight the platform for a key.
- **Named** — `/actuator/env` shows exactly which source supplied the winning value.

!!! success "The general lesson"
    Opinion without an escape hatch gets forked. An escape hatch you cannot trace gets misdiagnosed.
    When you contribute defaults to something you do not own, do both.

### 5. Local-first providers

Every multi-provider capability ships a provider that needs **no infrastructure**:

| Capability | Local | Production |
|---|---|---|
| Messaging | in-memory | RabbitMQ (Kafka experimental) |
| Storage | filesystem | S3 |
| Cache | Caffeine | Redis |
| Locking | JDBC on H2 | JDBC on PostgreSQL, or Redis |
| Idempotency | JDBC | Redis |
| Rate limiting | in-memory | Redis |
| Flags | in-memory | OpenFeature |
| Audit | log sink | JDBC or messaging |

That is what makes `mvn verify` pass with no Docker, no network, and no credentials — which is not a
convenience but a correctness property: **a test suite requiring infrastructure is a test suite that
gets skipped.**

!!! warning "Local providers differ from production ones in ways that matter"
    Caffeine is per-instance; Redis is shared. The in-memory rate limiter multiplies your limit by the
    replica count. `storage-fs` with three replicas means three separate stores. Each chapter names its
    own gap; [Local vs production](local-vs-production.md) collects them.

### 6. Annotation plus programmatic API

Nearly every capability offers both:

| Declarative | Programmatic | Use the second when |
|---|---|---|
| `@Audited` | `Auditor` | Conditional, per-item, or deep in a transaction |
| `@RateLimited` | `RateLimiter` | The quota is dynamic (per tier, per tenant) |
| `@Idempotent` | `IdempotencyStore` | You need the decision, not the rejection |
| `@LockedSchedule` | `LockManager` | Locking something other than a scheduled method |
| `@FeatureGate` | `FeatureFlags` | Branching within a method |
| `@EventHandler` | `EventPublisher` | Publishing rather than handling |
| `@Retry` | `RetryableOperation` | Dynamic policy names, non-Spring call sites |

The annotation is the common case and keeps the intent visible in the signature. The programmatic API
is the escape hatch for what an annotation cannot express — and every annotation in the platform is
implemented *over* the programmatic API, so they cannot diverge.

### 7. Per-operation settings live on the annotation

Notice what is **not** configurable in `application.yml`:

| On the annotation | Not a property | Because |
|---|---|---|
| `@LockedSchedule(atMost)` | — | The right lease is a property of the *work*, not the environment |
| `@Idempotent(keyExpression, ttl)` | — | Per-operation |
| `@RateLimited(permits, window, keyExpression)` | — | Per-operation |
| `@Audited(action, resourceExpression)` | — | Per-operation |

A single service-wide `atMost` would have to be the maximum across every locked job — so a crashed
holder of the fastest job would block it for as long as the slowest one needs.

The counter-example proves the rule: `dc.platform.idempotency.http.ttl` **is** a property, because the
HTTP filter's TTL is a property of the *client contract*, which is service-wide.

### 8. SPI only where a second provider is plausible

| Capability | Has an SPI | Why |
|---|---|---|
| messaging | `EventTransport`, `EventSerializer` | Three transports ship |
| storage | `KeyValidator` only | The **API interface is the provider contract** — a parallel `StorageProvider` would be identical |
| locking | `LockProvider`, `LockHandle` | Two providers |
| ratelimit | `RateLimiterProvider` | Two providers |
| flags | `FlagProvider`, `FlagValue`, `EvaluationContext` | Two providers |
| audit | `AuditSink` | Three sinks |
| errors, logging | none — "SPI-lite" customizers instead | One implementation; only enrichment varies |

Storage is the instructive case ([Chapter 11](../chapters/11-storage-files.md) §6.5): four providers
exist and there is still no `StorageProvider` interface, because `put`/`get`/`delete`/`list` is exactly
what a caller wants *and* exactly what a backend does. An SPI needs a **second plausible provider and
genuinely different contracts**.

### 9. The three-audience package model

```
   …<cap>            consumers      STABLE, japicmp-checked
   …<cap>.spi        providers      contract, evolves more freely, often EXPERIMENTAL
   …<cap>.internal   nobody         NO guarantees, excluded from javadoc and japicmp
```

Public types carry apiguardian `@API(status, since)`, so stability is readable from the jar before you
open any documentation.

!!! warning "One documented rough edge"
    [Chapter 8](../chapters/08-data.md) §6.2: the documented way to persist `Money` names
    `MoneyConverter`, which lives in `.internal`. The chapter fully-qualifies it so the coupling is
    visible, and offers a six-line alternative over `Money.toStorageString()`/`parse()` — which are
    STABLE precisely so that alternative is cheap.

### 10. Everything is fail-something, and the direction is a decision

The single most transferable idea in this book:

| Control | On failure | Because |
|---|---|---|
| [Rate limiter](../chapters/13-ratelimit-flags.md) | **Allows** | A limiter must not become an availability incident |
| [Feature flags](../chapters/13-ratelimit-flags.md) | **Off** | A typo must never enable unfinished code |
| [Audit](../chapters/12-audit.md) | **Sheds** | An audit-store outage must not be a service outage |
| [Logging](../chapters/03-logging-observability.md) | **Degrades** | Observability is not worth an outage |
| [OpenAPI](../chapters/04-openapi.md) | **Breaks only the document** | Documentation must not break traffic |
| [Authorization](../chapters/05-security-authz.md) | Should fail **closed** | Permitting the unauthorised is worse than rejecting the legitimate |
| [Security chain](../chapters/05-security-authz.md) | **Denies** | Deny-by-default; an unprotected endpoint is the worst outcome |

Six point one way, one points the other. There is no house style here — each direction was chosen from
**what a wrong answer costs**.

!!! success "The question to ask when you build any control"
    *If this component fails, which is worse: doing the thing, or not doing it?*

    For a limiter, rejecting everything is worse than permitting too much. For authorization,
    permitting is catastrophic and rejecting is merely annoying. Answer that question first; the
    implementation follows.

### 11. Correlation as the join key

One id, established at the highest-precedence filter, appearing everywhere:

```
   HTTP request  ->  X-Correlation-Id  ->  RequestContext  ->  MDC
                                             |
        +------------------+-----------------+---------------------+
        |                  |                 |                     |
   every log line    problem responses   message headers    audit events
        |                  |                 |                     |
        +------------------+-----------------+---------------------+
                                  |
                          outbound REST calls
                       (and back, on RemoteCallException)
```

An investigation starts anywhere in that diagram and reaches everything else.

!!! warning "And it is deliberately absent from exactly one place"
    Metric tags. A correlation id is unique per request, so tagging a meter with it is unbounded
    cardinality. It propagates through tracing **baggage** instead. See
    [Observability strategy](observability-strategy.md).

### 12. Make the guarantee executable

Wherever the platform makes a promise, something checks it:

| Promise | Gate |
|---|---|
| The dependency constitution holds | A Maven enforcer rule + ArchUnit in every module |
| Public APIs stay binary-compatible | japicmp against the last release |
| Error codes are unique | A build-time registry test |
| Every property is documented | `DocsCompletenessTest` |
| Every capability has a page | The same test |
| No broken documentation links | `DocsLinkCheckTest` |
| Documentation examples compile | `UsageSnippetCompileTest` |
| A provider honours its SPI | The capability's TCK |
| Services use the platform | `PlatformUsageRules` |
| Onboarding takes under 10 minutes | `golden-path.sh` |

!!! success "The general form"
    A documented architecture decays; an enforced one does not. When you find yourself writing "teams
    should…", ask what would make it fail the build instead — and note that this book is itself subject
    to three of those gates.

---

## Part II — The anti-patterns

### A1. Self-invocation past a proxy

**The single most common mistake in this book.** Every annotation is proxy-based:

```java
@Service
class OrderService {
    @RequiresPermission("orders:read")
    Order get(String id) { ... }

    List<Order> getAll(List<String> ids) {
        return ids.stream().map(this::get).toList();   // BYPASSES the check
    }
}
```

`this.get(...)` does not go through the proxy. Affects `@Cacheable`, `@Transactional`, `@Validated`,
`@RequiresPermission`, `@Audited`, `@RateLimited`, `@Idempotent`, `@FeatureGate`, `@LockedSchedule`,
`@Retry`, `@CircuitBreaker` — everything.

**The consequences are not equal.** A bypassed cache costs performance. A bypassed
`@RequiresPermission` is a **security bypass**; a bypassed `@Idempotent` is a duplicate charge.

**Fix:** annotate at the boundary callers actually reach, or split the class. Treat an internal call to
an annotated method as a review finding.

### A2. Replacing a bean to change one thing

```java
@Bean
SecurityFilterChain chain(HttpSecurity http) { ... }        // to add ONE CORS rule
```

You have replaced the entire chain: deny-by-default, the RFC-9457 auth bodies, the stateless posture,
the security headers — silently and completely.

**Fix:** the customizer tier (Pattern 2). Replace a bean only when you intend to own everything it did.

### A3. Remote calls inside a transaction

```java
@Transactional
void process(String orderId) {
    Order order = repository.findById(orderId).orElseThrow();
    String status = paymentClient.check(orderId);      // up to 10s, holding a connection
    order.setStatus(status);
}
```

The database connection is held for the whole call. Ten concurrent requests exhaust a default pool of
ten while the database sits idle.

Applies equally to publishing a message, acquiring a distributed lock, and writing to object storage —
anything that can block for an unbounded time.

**Fix:** read in a transaction, call outside one, write in a second transaction. Watch
`hikaricp_connections_pending`.

### A4. Unbounded cardinality in a metric tag

```java
Timer.builder("order.processing").tag("orderId", id)      // one time series per order
```

Prometheus memory climbs, scrapes slow, then time out — so you lose metrics during the incident the
metrics were meant to explain.

**Fix:** the tag test — *can I write down the complete set of values this will ever take?* If not, it
is a log field. Note that Spring's own `uri` tag uses the **template** (`/orders/{id}`), which is
bounded; a hand-rolled URI tag usually is not.

### A5. Trusting what the client tells you

| Trusted | Why it fails |
|---|---|
| File extension | Rename `evil.exe` to `invoice.pdf` |
| `Content-Type` header | `curl -F "file=@evil.exe;type=application/pdf"` |
| Filename | `../../etc/passwd`, or a newline injecting response headers |
| Declared size | Checked in the controller, after buffering |
| `X-Forwarded-For` | Client-supplied unless a proxy you control overwrites it |
| An idempotency key from an untrusted caller | Another client's key returns 409 and leaks its use |

**Fix:** sniff magic bytes, sanitise filenames on write *and* read, enforce size at the servlet layer,
and namespace idempotency keys per authenticated caller.

### A6. Assuming one instance

| Assumption | Reality with N replicas |
|---|---|
| "My scheduled job runs once" | It runs N times, simultaneously |
| "`synchronized` gives me mutual exclusion" | Per JVM. There are N JVMs |
| "My in-memory rate limit is the limit" | The effective limit is N× |
| "My in-memory cache is the cache" | N caches, diverging |
| "Invalidation is global" | Caffeine eviction is instance-local |
| "The uploaded file is here" | `storage-fs` writes to whichever instance served the request |

**Fix:** [Chapter 10](../chapters/10-coordination.md) for locking and idempotency; a shared provider
for cache, rate limiting, and storage.

### A7. Keys that are not stable across retries

```java
@Idempotent(keyExpression = "#correlationId")        // a retry brings a NEW correlation id
```

The most common idempotency mistake, and it protects nothing while looking correct.

**Fix:** a business identifier the client controls and repeats — an order reference, a client-supplied
`Idempotency-Key`. Never a correlation id, never a freshly generated UUID.

### A8. Treating a 409 as failure

The platform's idempotency is **reject-duplicate, not response replay**. A 409 on a repeat means the
**first attempt succeeded**. A client that treats it as an error shows the user a failure for an
operation that worked — and the user retries into a loop.

**Fix:** document it in your API. Clients receiving 409 should fetch the result, not retry.

### A9. Caching identity-dependent results

```java
@Cacheable("orders")
@RequiresPermission("orders:read")
Order find(String id) { ... }
```

If the cache advisor runs first, **a hit returns the value without the permission check running**.

**Fix:** never cache a method whose result depends on the caller, unless identity is part of the key.
Better: keep authorization at the boundary and cache the layer beneath it, which has no identity
dependence.

### A10. Assuming a kill switch turns the thing off

`dc.platform.<cap>.enabled=false` stops **the platform contributing**. What remains is the underlying
library's default, not nothing:

| Disabled | You actually get |
|---|---|
| `resilience` | Resilience4j's *library* defaults — no exponential backoff |
| `cache` | Boot's `CacheManager` — no key convention, no policy |
| `logging` | Boot's console logging — unstructured |
| `security` (`mode: disabled`) | Boot's default chain, **and** authz silently inert |
| `audit` | Genuinely nothing — and the gap is invisible |

**Fix:** know what you are falling back to before flipping one during an incident. Audit is the
dangerous one: nothing fails, and the absence is only discovered when someone asks for records.

### A11. Silent degradation you never checked

Three cases where the platform tells you once, at startup, and never again:

- `@LockedSchedule` with no `LockManager` → runs **unlocked**.
- `@RequiresPermission` with no `CurrentUserAccessor` → the advisor is never created; **methods are
  unprotected**.
- The audit sink fell back to `log` when compliance expected a database.

**Fix:** assert these at startup rather than monitoring for them. A bean that throws on the wrong state
turns a silent compliance gap into a deployment failure, which is where you want it.

### A12. Documentation that lies because a capability was disabled

Disable `errors` and the [OpenAPI](../chapters/04-openapi.md) document keeps promising `ProblemDetail`
bodies you no longer produce. The two capabilities are independent by design, and neither conditions on
the other.

**Fix:** if you disable errors, disable the error appending too.

### A13. Flags that never get removed

```
   1. Add flag       2. Deploy dark      3. Enable for a cohort
   4. Enable everywhere      5. *** REMOVE THE FLAG AND THE OLD PATH ***
```

Step 5 is skipped roughly always, leaving permanent dead branches and a switch someone may flip in
three years having forgotten what the other path does.

**Fix:** give every flag an expiry when you create it. A flag permanently on for a year is a dead
branch with extra steps.

### A14. Returning entities from controllers

Serialises every column — including internal bookkeeping — publishes them in your
[OpenAPI](../chapters/04-openapi.md) document, and couples your API to your schema so a migration
becomes a breaking API change.

**Fix:** a response record. It fixes the leak *and* the coupling.

### A15. Sleeping in a test

```java
service.place("order-1");
Thread.sleep(500);        // too short -> flaky. too long -> slow. usually both.
```

**Fix:** `awaitIdle(Duration)`. Deterministic **and** faster — this is not a trade-off.

---

## The one-page summary

**Do**

- Contribute a customizer before replacing a bean.
- Declare your own bean when you genuinely need to replace one — never fork.
- Put per-operation settings on annotations, per-service settings in properties.
- Choose fail-open or fail-closed by asking what a wrong answer costs.
- Assert silent degradations at startup.
- Test against local providers; certify providers against the TCK.
- Make guarantees executable.

**Don't**

- Call an annotated method from inside the same bean.
- Make a remote call while holding a transaction.
- Tag a metric with anything per-request.
- Trust an extension, a `Content-Type`, a filename, or a declared size.
- Assume one instance.
- Key idempotency on anything that changes between retries.
- Cache a result that depends on who asked.
- Flip a kill switch without knowing the fallback.
- Sleep in a test.

---

**Next:** [The Platform Security Model](security-model.md) — the security threads from Chapters 1, 2,
5, 11, 12, and 13, pulled into one story.

[Back to the book](../index.md)
