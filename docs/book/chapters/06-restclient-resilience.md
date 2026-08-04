# Chapter 6 — Outbound Calls: REST Client and Resilience

> **Capabilities covered:** `restclient`, `resilience`
>
> Calling other services with propagation, timeouts, retries, and circuit breakers you did not have
> to write.
>
> **Starters:** `platform-starter-restclient`, `platform-starter-resilience` ·
> **Reference:** [modules/restclient.md](../../modules/restclient.md) ·
> [modules/resilience.md](../../modules/resilience.md)

---

## 1. Introduction and Business Value

Chapters 1–5 were about requests arriving. This chapter is about requests leaving — and about the
uncomfortable fact that **your service's availability is bounded by the availability of everything it
calls synchronously**, unless you do something about it.

Two capabilities:

- **REST client** makes every outbound call carry the platform's conventions: correlation propagation,
  bounded timeouts, token relay, and errors mapped to one exception type.
- **Resilience** ships tuned Resilience4j configuration so retries, circuit breakers, and time limiters
  behave consistently across the fleet — without inventing a single new annotation.

### The problem it solves

The canonical cascading failure, in five steps:

```
  1.  Service B slows from 50ms to 30s (GC pause, a bad query, a dependency of its own)
  2.  Service A calls B with no timeout. Request threads block.
  3.  A's thread pool fills with threads waiting on B.
  4.  A stops serving EVERY endpoint — including the 90% that never touch B.
  5.  A's callers now block on A. The failure walks up the call graph.
```

Nothing in step 2 looks like a bug in review. `restTemplate.getForObject(url, Order.class)` is
perfectly ordinary code. The defect is the *absence* of a timeout, and absences do not show up in
diffs.

The platform closes that gap by construction: a builder from `PlatformRestClientFactory` **always** has
a connect and read timeout, because they come from configuration rather than from the caller
remembering.

### What each capability actually buys you

| Without | With |
|---|---|
| Every service configures `RestClient.Builder` from scratch, differently | One injection point, one set of conventions |
| Timeouts forgotten, or copied wrong | 2s connect / 10s read by default, tunable **per named client** |
| The trace stops at each hop | Correlation propagated outbound, remote id echoed back on failure |
| Every service hand-writes token relay, often leaking tokens to the wrong host | Guarded relay that activates only when security is actually present |
| Failures surface as raw framework exceptions with unbounded bodies | `RemoteCallException` with status, a 1 KB-truncated body, and the remote correlation id |
| Every team tunes retry and breaker settings from scratch, or leaves library defaults | Tuned `default` configs at lowest precedence, overridable per name |
| A circuit breaker opens and nobody knows | Micrometer binding on by default |

### The decision worth noticing: no wrapper annotations

The resilience capability contributes **no annotations of its own**. You use Resilience4j's own
`@Retry`, `@CircuitBreaker`, and `@TimeLimiter`.

That is a deliberate refusal. A `@PlatformRetry` annotation would have to be documented, maintained,
and kept at feature parity with Resilience4j forever — and every Stack Overflow answer, every piece of
upstream documentation, and every existing team's knowledge would stop applying. The platform's
contribution is *tuning*, not vocabulary.

!!! success "Best practice — fewer platform APIs is a feature"
    This is the same reasoning that keeps [caching](09-cache-redis.md) on Spring's `@Cacheable` and
    [tracing](03-logging-observability.md) on Micrometer. Where the industry already has a good
    abstraction, the platform configures it rather than wrapping it. When you are tempted to wrap a
    library "for consistency", ask what the wrapper adds beyond a name.

---

## 2. Core Concepts and Underlying Principles

### 2.1 The three timeouts, and which one you are missing

"Add a timeout" is not one decision. There are at least three, and they fail differently:

| Timeout | Bounds | Platform default | What its absence looks like |
|---|---|---|---|
| **Connect** | Establishing the TCP connection | `2s` | A dead host hangs until the OS gives up — potentially minutes |
| **Read** | Waiting for response bytes | `10s` | A *slow* host holds your thread indefinitely. The classic cascade |
| **Total call** | The whole operation, retries included | `5s` via `@TimeLimiter` | Three retries × 10s read = 30s worst case, despite "a 10-second timeout" |

That last row is the one that surprises people. A read timeout does not bound a *retried* call. If you
configure a 10-second read timeout and a 3-attempt retry, your worst case is 30 seconds plus backoff —
and the caller upstream, who was told "10 seconds", has long since given up or piled up threads of
their own.

!!! warning "Retry multiplies your timeout budget"
    Always reason about the *total* time an operation can consume, not the per-attempt time. If a
    caller's SLA is 5 seconds, then 3 attempts at a 10-second read timeout is not a resilience
    strategy — it is a slower failure. Either lower the per-attempt timeout or use a `@TimeLimiter`.

### 2.2 The circuit breaker state machine

A circuit breaker is a state machine that stops you from calling something that is clearly broken.

```
                failure rate >= 50% over 10 calls
      CLOSED  ----------------------------------->  OPEN
        ^                                            |
        |                                            | wait duration elapses
        |  probe calls succeed                       v
        +-----------------------------------  HALF_OPEN
                                                     |
                                                     | probe calls fail
                                                     v
                                                   OPEN
```

- **CLOSED** — normal. Calls pass through; outcomes are recorded.
- **OPEN** — calls fail *immediately* with `CallNotPermittedException`, without touching the network.
- **HALF_OPEN** — after a wait, a limited number of probe calls are allowed. Success closes the
  breaker; failure reopens it.

The value is bidirectional and both directions matter:

- **It protects the caller.** Failing in microseconds instead of blocking for 10 seconds keeps your
  thread pool available for the endpoints that do not depend on the broken service.
- **It protects the callee.** A struggling service that is also being hammered by retries from twenty
  callers cannot recover. Backing off gives it room.

!!! note "Why the platform's window is count-based, not time-based"
    `sliding-window-type: COUNT_BASED`, size 10. A time-based window on a low-traffic endpoint behaves
    unpredictably — two calls in a minute, both failing, is a 100% failure rate on a sample of two. A
    count-based window guarantees the breaker has actually seen ten outcomes before it decides, so its
    behaviour is the same at 1 request per minute and 1,000 per second.

### 2.3 When retry helps and when it makes things worse

Retry is the most misapplied resilience pattern. Three conditions must all hold:

| Condition | Why | If violated |
|---|---|---|
| The failure is **transient** | A retry only helps if the next attempt might differ | Retrying a 400 is pure waste — the request is wrong and will stay wrong |
| The operation is **idempotent** | The first attempt may have succeeded before the response was lost | You double-charge, double-ship, double-post. See [Chapter 10](10-coordination.md) |
| The dependency has **capacity** | Retries multiply load on the thing that is already struggling | A retry storm turns a brownout into an outage |

The third is the one that causes incidents. When a dependency degrades, every caller retries at once —
so the dependency's load *triples* at exactly the moment it can least afford it.

Two mitigations, both of which the platform provides:

- **Exponential backoff** — 200 ms, then 400 ms, then 800 ms. Spreads retries out in time.
- **A circuit breaker in front of the retry** — once the breaker opens, retries stop entirely.

!!! warning "The platform's defaults do not know whether your operation is idempotent"
    `@Retry` on a `POST` that creates an order will happily create three. The retry configuration is
    tuned for you; the *decision to retry* is yours, and it is a correctness decision, not a tuning one.
    Pair retries on non-idempotent operations with [`@Idempotent`](10-coordination.md).

### 2.4 Bulkheads, and the one the platform gives you for free

The bulkhead pattern isolates resources so one failing dependency cannot consume all of them — named
for ship compartments that stop one breach from sinking the vessel.

Resilience4j has a `@Bulkhead` annotation for explicit concurrency limits. But the platform gives you
a large part of the benefit without it: **the JDK `HttpClient` on virtual threads**. A blocked virtual
thread costs a few hundred bytes rather than a megabyte of stack, so "all my threads are blocked on
service B" is a far less catastrophic condition than it is on a platform-thread pool.

That does not eliminate the problem — a blocked call still holds whatever *else* it is holding, like a
database connection — but it substantially raises the ceiling.

!!! warning "Virtual threads do not make a database connection pool bigger"
    The classic remaining bulkhead failure: a request holds a JDBC connection while making an outbound
    HTTP call. The connection pool has 10 connections regardless of how many virtual threads you have.
    **Never make a remote call while holding a database transaction.** See
    [Chapter 8](08-data.md).

### 2.5 Named clients

`factory.builder("orders")` — the name is not cosmetic. It is the key for three things:

1. **Configuration lookup** — `dc.platform.restclient.clients.orders.*` overrides the defaults.
2. **Metric tagging** — so you can see latency and error rate per dependency, not aggregated.
3. **Customizer targeting** — a `PlatformRestClientCustomizer` receives the name and can apply
   per-dependency headers.

This is what makes per-dependency tuning possible. Your identity provider might warrant a 500 ms read
timeout; a reporting service that legitimately takes 30 seconds needs a different one. One global
timeout cannot serve both, and a global timeout large enough for the slow one leaves you exposed on the
fast one.

!!! success "Best practice — one client name per remote dependency"
    Not per endpoint, not per method. The name identifies *the thing that can fail independently*,
    which is the service, because that is the granularity at which timeouts, breakers, and dashboards
    make sense.

### 2.6 Correlation, in both directions

```
   Service A                                    Service B
   ---------                                    ---------
   RequestContext: 9f2c...
        |
        |  X-Correlation-Id: 9f2c...      -->   filter reads it, opens scope
        |                                            |
        |                                            | fails
        |  <-- 500 + X-Correlation-Id: 9f2c...       v
        v
   RemoteCallException
     .status()               = 500
     .bodySnippet()          = first 1KB of B's problem body
     .remoteCorrelationId()  = "9f2c..."
```

The outbound half is what most implementations do. The **return** half is the platform's addition:
`RemoteCallException` captures the correlation id the callee echoed, so a single log query joins both
sides of a failed call.

Note that when B is also on the platform, the id is the *same* id — B's correlation filter accepted
the inbound header rather than generating a new one ([Chapter 1](01-core.md)). One token, the whole
call graph.

### 2.7 Guarded token relay

Propagating the caller's identity downstream is the common need in an internal estate. The naive
implementation makes `restclient` depend on `security`, which would violate the platform's rule that a
capability you do not add costs you nothing — a batch job calling a public API should not drag Spring
Security onto its classpath.

The platform's answer is a **guarded optional edge**: a nested configuration that activates only when
a Jwt-shaped resource server is on the classpath *and* the security capability's `CurrentUserAccessor`
bean is actually registered. Absent either, the relay customizer simply does not exist.

```java
builder.requestInterceptor((request, body, execution) -> {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
        request.getHeaders().setBearerAuth(jwt.getTokenValue());
    }
    return execution.execute(request, body);
});
```

!!! warning "The relay is unconditional on destination"
    Read that interceptor carefully: if the current request is authenticated, the token goes on
    **every** outbound call from every platform-built client — including one pointed at a third party.
    A bearer token sent to an external host is a credential disclosure. If a client calls outside your
    trust boundary, build it *without* the factory, or add a customizer that strips the header for that
    client name. §5.5.

---

## 3. Feature Reference

### 3.1 REST client — public API

Package `ae.gov.dubaicustoms.platform.restclient`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `PlatformRestClientFactory` | interface | `RestClient.Builder builder(String clientName)` |
| `RemoteCallException` | final class extends `PlatformException` | Non-2xx outcome. Code `DC-RCLIENT-0500` |
| `PlatformRestClientCustomizer` | functional interface | `void customize(String name, RestClient.Builder builder)` |

#### `RemoteCallException`

| Member | Returns | Notes |
|---|---|---|
| `status()` | `int` | The HTTP status from the remote service |
| `bodySnippet()` | `String` | **Truncated to 1 KB.** Never null, may be empty. Safe to log directly |
| `remoteCorrelationId()` | `Optional<String>` | The id the callee echoed, if any |
| `code()` | `ErrorCode` | Always `DC-RCLIENT-0500` (inherited from `PlatformException`) |

!!! note "One code for every remote failure, and why that is right"
    A 404 from a dependency is not *your* 404. `RemoteCallException` always carries
    `DC-RCLIENT-0500` — an infrastructure failure from your service's perspective — and if left
    unhandled it becomes a 500 to your caller. That is usually correct: your caller cannot act on a
    downstream 404. When it *should* map to something else, catch it and translate. §4.5.

**Customizer contract:** thread-safe, and **must not throw** — a throwing customizer fails builder
creation for *every* client, not just the one being built.

### 3.2 Resilience — public API

Package `ae.gov.dubaicustoms.platform.resilience`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `RetryableOperation` | interface | `<T> T call(String name, Supplier<T> action)` |
| `ResilienceDefaults` | final class | The default values as constants |

`ResilienceDefaults`:

| Constant | Value |
|---|---|
| `RETRY_MAX_ATTEMPTS` | `3` (initial call **plus** retries) |
| `RETRY_INITIAL_INTERVAL` | `200ms` |
| `RETRY_BACKOFF_MULTIPLIER` | `2.0` |
| `CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD` | `50.0` (percent) |
| `CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE` | `10` (count-based) |
| `TIME_LIMITER_TIMEOUT` | `5s` |

!!! note "`RetryableOperation` is deliberately narrow"
    Retry only — no circuit-breaker or time-limiter equivalent. It exists for call sites annotations
    cannot reach (dynamic policy names, non-Spring-managed objects). For everything else, use
    Resilience4j's annotations directly. Adding programmatic wrappers for the other patterns would be
    the wrapper-vocabulary mistake §1 describes.

### 3.3 What you use from Resilience4j directly

| Annotation | Guards | Default config |
|---|---|---|
| `@Retry(name = "...")` | Transient failures | 3 attempts, 200 ms × 2 exponential |
| `@CircuitBreaker(name = "...")` | A failing dependency | 50% over a 10-call count window |
| `@TimeLimiter(name = "...")` | Unbounded duration | 5 s |
| `@Bulkhead(name = "...")` | Concurrency | Not tuned by the platform |
| `@RateLimiter(name = "...")` | Outbound call rate | Not tuned. See [Chapter 13](13-ratelimit-flags.md) for *inbound* limiting |

All configured under `resilience4j.*`, which the platform seeds at lowest precedence.

### 3.4 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

**REST client**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.restclient.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.restclient.defaults.connect-timeout` | Duration | `2s` | Default connect timeout | Rarely — connect should be fast or fail |
| `dc.platform.restclient.defaults.read-timeout` | Duration | `10s` | Default read timeout | When your fleet's typical dependency is faster or slower |
| `dc.platform.restclient.clients.<name>.*` | Map | — | Per-client overrides | **Routinely.** This is the main tuning surface |
| `dc.platform.restclient.propagate-correlation` | Boolean | `true` | Send the correlation id outbound | Essentially never — it breaks distributed tracing |

**Resilience**

| Key | Type | Default | Meaning |
|---|---|---|---|
| `dc.platform.resilience.enabled` | Boolean | `true` | Kill switch. `false` also suppresses the `resilience4j.*` defaults |

Everything else is Resilience4j's own namespace:

```yaml
resilience4j:
  retry:
    instances:
      orders:
        base-config: default
        max-attempts: 5
  circuitbreaker:
    instances:
      orders:
        base-config: default
        wait-duration-in-open-state: 30s
```

!!! success "Best practice — always set `base-config: default`"
    Without it, a named instance starts from Resilience4j's *library* defaults, not the platform's
    tuned ones — and you silently lose exponential backoff and the count-based window. With it, you
    inherit the platform's tuning and override only what you name.

### 3.5 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformRestClientAutoConfiguration` | `RestClient` on classpath, `restclient.enabled != false` | `platformRestClientFactory`, `restclientCapabilityDescriptor`, and — guarded — `platformTokenRelayCustomizer` | You define a `PlatformRestClientFactory` bean |
| `PlatformResilienceEnvironmentPostProcessor` | `resilience.enabled != false` | The `resilience4j.*` defaults as `platform-resilience-defaults` | Any `resilience4j.*` key you set wins on precedence |
| `PlatformResilienceAutoConfiguration` | Resilience4j on classpath, `resilience.enabled != false` | `retryableOperation`, capability descriptor | Standard `@ConditionalOnMissingBean` |

The token-relay customizer's guard is a nested configuration requiring **both** a Jwt-shaped resource
server on the classpath and a registered `CurrentUserAccessor` bean — an optional edge to the security
capability, never a hard dependency.

### 3.6 Extension points

| Extension | How | Effect |
|---|---|---|
| Per-client headers or interceptors | `PlatformRestClientCustomizer` bean | Applied in `@Order`, receives the client name |
| Replace the factory | `PlatformRestClientFactory` bean | Platform's backs off — you own every convention |
| Tune one dependency | `dc.platform.restclient.clients.<name>.*` | No code |
| Tune one policy | `resilience4j.<pattern>.instances.<name>.*` | No code |
| Programmatic retry | Inject `RetryableOperation` | Dynamic names, non-Spring call sites |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-restclient</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-resilience</artifactId>
</dependency>
```

### 4.2 Build a client

```java snippet:book-06-rest-client
@Service
class OrdersClient {

    private final RestClient client;

    OrdersClient(PlatformRestClientFactory factory) {
        this.client = factory.builder("orders")
                .baseUrl("https://orders.internal")
                .build();
    }

    String fetchRaw(String orderId) {
        return client.get()
                .uri("/orders/{id}", orderId)
                .retrieve()
                .body(String.class);
    }
}
```

That client already has: correlation propagation, a 2 s connect and 10 s read timeout, observation
instrumentation, non-2xx mapped to `RemoteCallException`, and — when security is present and the
request is authenticated — bearer-token relay.

!!! success "Best practice — build the client once, in the constructor"
    `factory.builder(...)` creates a *new* builder each call. Building a `RestClient` per request
    rebuilds interceptors and the request factory on the hot path. Build once; `RestClient` is
    thread-safe.

!!! warning "Inject the factory, not `RestClient.Builder`"
    Injecting Boot's `RestClient.Builder` directly gets you a client with **no platform conventions** —
    no timeouts, no correlation, no error mapping. It compiles and works, which is what makes it
    dangerous. The [conformance rules](14-testing-dx.md) flag it.

### 4.3 Tune per dependency

```yaml
dc:
  platform:
    restclient:
      defaults:
        connect-timeout: 2s
        read-timeout: 10s
      clients:
        orders:
          read-timeout: 3s        # fast internal service
        reporting:
          read-timeout: 60s       # legitimately slow
        idp:
          connect-timeout: 500ms
          read-timeout: 2s        # on the critical path of every request
```

!!! success "Best practice — derive the timeout from the caller's SLA, not the callee's p99"
    A dependency's p99 tells you how long it *usually* takes. Your timeout should express how long you
    are *willing* to wait before failing, which is bounded by what your own caller expects. If your SLA
    is 2 seconds, a 10-second read timeout on a dependency means you will blow your SLA and then still
    wait 8 more seconds.

### 4.4 Add retry and a circuit breaker

```java snippet:book-06-resilience-annotations
@Service
class InventoryService {

    private final RestClient client;

    InventoryService(PlatformRestClientFactory factory) {
        this.client = factory.builder("inventory").baseUrl("https://inventory.internal").build();
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory", fallbackMethod = "unavailable")
    String checkStock(String sku) {
        return client.get().uri("/stock/{sku}", sku).retrieve().body(String.class);
    }

    // Fallback signature: the guarded method's parameters, plus the Throwable.
    String unavailable(String sku, Throwable cause) {
        return "UNKNOWN";
    }
}
```

Annotation order matters: `@Retry` is outside `@CircuitBreaker` here, so retries happen and the
breaker sees the *final* outcome. §6.3 covers the alternative and when you want it.

!!! warning "A fallback must not do anything that can fail"
    It runs when things are already broken. Calling another remote service, hitting the database, or
    reading a file from a fallback turns one failure into two — and the second one has no fallback.
    Return a cached value, a sensible default, or an empty result.

### 4.5 Handle a remote failure meaningfully

```java snippet:book-06-remote-call-exception
@Service
class CustomerLookup {

    private final RestClient client;

    CustomerLookup(PlatformRestClientFactory factory) {
        this.client = factory.builder("customers").baseUrl("https://customers.internal").build();
    }

    Optional<String> findName(String customerId) {
        try {
            return Optional.ofNullable(
                    client.get().uri("/customers/{id}", customerId).retrieve().body(String.class));
        } catch (RemoteCallException e) {
            if (e.status() == 404) {
                // A downstream 404 is a domain outcome here, not an infrastructure failure.
                return Optional.empty();
            }
            throw e;
        }
    }
}
```

!!! tip "`bodySnippet()` is already bounded — log it directly"
    It is truncated to 1 KB at construction, so `log.warn("call failed: {}", e.bodySnippet())` cannot
    dump a 40 MB response into your log store. `remoteCorrelationId()` is the field that makes the
    callee's logs findable.

### 4.6 Retry programmatically

For dynamic policy names or non-Spring call sites:

```java snippet:book-06-retryable-operation
@Service
class TariffLookup {

    private final RetryableOperation retryable;
    private final RestClient client;

    TariffLookup(RetryableOperation retryable, PlatformRestClientFactory factory) {
        this.retryable = retryable;
        this.client = factory.builder("tariff").baseUrl("https://tariff.internal").build();
    }

    String lookup(String region, String code) {
        // Policy name chosen at runtime — an annotation cannot express this.
        return retryable.call("tariff-" + region,
                () -> client.get().uri("/tariffs/{code}", code).retrieve().body(String.class));
    }
}
```

An unconfigured name falls back to the `default` instance configuration, so a new region works without
a config change.

### 4.7 Add a per-client header

```java snippet:book-06-restclient-customizer
@Configuration
class OutboundHeaders {

    @Bean
    @Order(10)
    PlatformRestClientCustomizer partnerApiKey() {
        return (name, builder) -> {
            if ("partner".equals(name)) {
                builder.defaultHeader("X-Api-Key", System.getProperty("partner.api.key", ""));
            }
        };
    }
}
```

!!! warning "The name check is not optional"
    Customizers run for **every** client. Without the `if`, that API key goes to every service you
    call. Note also that a real key comes from a Spring `${...}` placeholder populated by Vault —
    `System.getenv` is banned by the [conformance rules](14-testing-dx.md), and this snippet uses a
    system property only to stay self-contained.

### 4.8 Reference the defaults in your own code

```java
Duration budget = ResilienceDefaults.TIME_LIMITER_TIMEOUT;
int attempts = ResilienceDefaults.RETRY_MAX_ATTEMPTS;
```

One source of truth, so your code and the platform's configuration cannot drift.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| All | A named client per dependency, with a deliberate timeout | The main tuning surface |
| All | `base-config: default` on every named Resilience4j instance | Otherwise you lose the platform's tuning |
| Production | Longer breaker `wait-duration-in-open-state` | A dependency that just failed needs recovery room |
| Test | Shorter timeouts | A test that waits 10 s for a timeout is a slow test |
| Local | Defaults | Nothing to tune when nothing is remote |

!!! warning "Do not disable resilience to make tests pass"
    `dc.platform.resilience.enabled=false` suppresses the `resilience4j.*` defaults entirely, so
    annotations fall back to library defaults rather than being disabled. If a test is slow because of
    retries, configure a short-attempt instance for the test profile instead.

### 5.2 What to monitor

Resilience without telemetry is worse than none, because a breaker sheds traffic **silently**. The
Micrometer binding is on by default; use it.

| Signal | Meter | Alert when |
|---|---|---|
| Breaker state | `resilience4j_circuitbreaker_state` | Any transition to `open`. This is your earliest warning of a dependency failing |
| Breaker failure rate | `resilience4j_circuitbreaker_failure_rate` | Approaching the threshold — a leading indicator before it opens |
| Calls not permitted | `resilience4j_circuitbreaker_not_permitted_calls_total` | Non-zero means you are shedding traffic right now |
| Retry outcomes | `resilience4j_retry_calls_total{kind}` | `failed_with_retry` climbing means retries are not helping |
| Outbound latency | `http_client_requests_seconds` tagged by client name | p99 above your budget |
| `DC-RCLIENT-0500` rate | The `code` log field | A spike names the failing dependency |

!!! tip "The single most valuable alert in this chapter"
    A circuit breaker transitioning to `open`. It fires *before* your error rate climbs — the breaker
    opened precisely to prevent that — and it names the dependency. Compare with a 5xx alert, which
    tells you something is wrong somewhere.

!!! warning "Do not tag resilience metrics with anything per-request"
    The name tag is bounded (one per dependency). Adding a URI with an interpolated path parameter, or
    a tenant id, reintroduces the cardinality problem from [Chapter 3](03-logging-observability.md) §6.4.

### 5.3 Troubleshooting

**Calls hang far longer than the configured timeout.**

| Cause | Check |
|---|---|
| Retry multiplying the budget | attempts × read-timeout + backoff. §2.1 |
| The client was not built by the factory | An injected `RestClient.Builder` has no timeouts |
| Timeout set on the wrong client name | `curl -s localhost:8080/actuator/configprops \| jq '.. \| .restclient?'` |
| DNS resolution hanging | Not covered by connect timeout on all JDK versions. Check resolver configuration |

**The circuit breaker never opens.** The window is **count-based, size 10** — it needs ten calls before
it evaluates. On a low-traffic endpoint that can take a while. Confirm with
`resilience4j_circuitbreaker_buffered_calls`.

**The circuit breaker opens constantly.** The 50% threshold over 10 calls is sensitive on a dependency
with a legitimately high error rate — a validation-heavy API returning 4xx routinely, for instance. Two
fixes: raise `failure-rate-threshold`, or configure `ignore-exceptions` so 4xx does not count as a
failure. The second is usually correct — **a 400 is not the dependency failing**.

**Retries are not happening.** Self-invocation (`this.method()`) bypasses the proxy — the same caveat
as [Chapter 2](02-errors-validation.md) §4.4 and [Chapter 5](05-security-authz.md) §2.8. Also check
that the exception type is one Resilience4j retries; by default it retries all exceptions, but a
`retry-exceptions` list narrows it.

**The token is not being relayed.** Both guards must hold: a Jwt-shaped resource server on the
classpath, *and* a registered `CurrentUserAccessor`. Check `/actuator/beans` for
`platformTokenRelayCustomizer`. Also confirm the current request is actually authenticated — the relay
reads `SecurityContextHolder`, which is empty on a background thread.

**A token is going to the wrong host.** Working as implemented, and a real risk. §5.5.

### 5.4 Scaling and performance

- **The JDK `HttpClient` pools connections per client instance.** Building a client per request defeats
  pooling entirely and shows up as connection-establishment latency on every call.
- **Virtual threads change the arithmetic of blocking**, but not of finite resources — connection
  pools, database connections, and file handles are still bounded. §2.4.
- **A breaker in `OPEN` state is nearly free** — it fails in microseconds without touching the network.
  That is the point.
- **Retry with backoff holds a thread for the whole sequence**, including the sleeps. Three attempts
  with 200 ms and 400 ms backoff is ~600 ms of held thread on top of the call time.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| **Token relay is destination-blind** | Any authenticated request relays the bearer token to every platform-built client. A client pointed at a third party leaks a credential |
| `bodySnippet()` may contain sensitive data | It is the remote's response body. If they return PII in an error, it is now in your exception and your logs |
| Correlation ids propagate outbound | Harmless internally. Externally, it leaks that you use this convention — minor, worth knowing |
| Base URLs are configuration | An attacker who can change one redirects your traffic and your relayed tokens. Treat them as security-relevant configuration |
| No certificate pinning by default | Standard JDK trust store. Fine internally; consider pinning at an external boundary |

!!! warning "The most important control in this section"
    If a client calls **outside your trust boundary**, do not build it with
    `PlatformRestClientFactory` — or add a customizer that strips `Authorization` for that client name:

    ```java
    @Bean
    PlatformRestClientCustomizer stripAuthForExternal() {
        return (name, builder) -> {
            if (name.startsWith("external-")) {
                builder.requestInterceptor((request, body, execution) -> {
                    request.getHeaders().remove("Authorization");
                    return execution.execute(request, body);
                });
            }
        };
    }
    ```

    Adopt a naming convention that makes the boundary visible in the client name, and review it.

---

## 6. Deep Dive

### 6.1 Why the body snippet is truncated at construction

```java
this.bodySnippet = body.length() > 1024 ? body.substring(0, 1024) : body;
```

Truncation happens when the exception is *built*, not when it is logged. That ordering is the whole
point: the exception object itself is bounded, so it cannot become a memory problem no matter how many
of them exist, and no downstream logging mistake can dump a 40 MB response body.

Consider the alternative — storing the full body and truncating at the log call site. Under a failure
storm you would hold thousands of full response bodies in flight, at exactly the moment the service is
already degraded. Bounding at the boundary is a small decision that prevents a memory-pressure incident
during an outage.

1 KB is enough for an RFC-9457 problem body, which is what a platform service returns. It is not enough
for a stack-trace-laden HTML error page, which is the point.

### 6.2 Why `RemoteCallException` extends `PlatformException`

It inherits into the taxonomy from [Chapter 1](01-core.md), which means the
[errors](02-errors-validation.md) capability maps it automatically: not a `BusinessException`, so it is
an infrastructure failure, so 500 with `DC-RCLIENT-0500`.

The consequence is worth being deliberate about. If you do nothing, a downstream failure of any kind
becomes a 500 to your caller. That is often right — your caller cannot act on a downstream 404 — but it
is a decision you are making by omission. Where a downstream status has domain meaning, catch and
translate (§4.5), and you will find the translation is usually a `NotFoundException` or a
`BusinessException`.

### 6.3 Annotation ordering: retry outside breaker, or inside

Resilience4j applies its annotations in a fixed order:
`Bulkhead → TimeLimiter → RateLimiter → CircuitBreaker → Retry`, with Retry outermost.

So with both annotations present, the default is **retry wraps breaker**:

```
  Retry
    +-- attempt 1: CircuitBreaker -> call
    +-- attempt 2: CircuitBreaker -> call
    +-- attempt 3: CircuitBreaker -> call
```

Each attempt is recorded by the breaker, so three failed attempts count as three failures. The breaker
opens after ~4 failed operations rather than 10 — sensitive, which is usually what you want for a
genuinely broken dependency.

The alternative — breaker outside retry — records one outcome per *operation*, so the breaker is less
twitchy but takes longer to notice. It requires manual composition rather than annotations.

!!! success "Best practice — start with the default ordering and watch the breaker metrics"
    If your breaker opens on transient blips, the retry is amplifying them into the breaker's window.
    That is the signal to reconsider, not a reason to pre-emptively hand-compose.

### 6.4 Why `enabled=false` does not disable resilience

`dc.platform.resilience.enabled=false` makes the `EnvironmentPostProcessor` return early, contributing
no `resilience4j.*` defaults. It does **not** disable Resilience4j.

So `@Retry` still retries — with Resilience4j's *library* defaults: 3 attempts, a fixed 500 ms wait, no
exponential backoff. You have not turned resilience off; you have swapped tuned behaviour for untuned
behaviour, silently.

To genuinely disable a policy, configure it: `resilience4j.retry.instances.foo.max-attempts: 1`.

!!! warning "This is the general shape of platform kill switches"
    A kill switch stops the *platform* contributing, and what remains is the underlying library's
    default, not nothing. [Logging](03-logging-observability.md) §6.5 has the same property. Before
    flipping one during an incident, know what you are falling back to.

### 6.5 The token relay's silent dependency on the request thread

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
```

`SecurityContextHolder` is thread-local. On a `@Scheduled` job, an `@Async` method, or a message
handler, it is empty — so the relay silently does nothing and your call goes out unauthenticated,
producing a 401 the logs attribute to the callee.

This is the same thread-boundary problem as [`RequestContext`](01-core.md) §6.1, and it has the same
shape of fix: for background work, obtain a service-account token deliberately rather than expecting to
inherit a user's.

!!! success "Best practice — background work should never relay a user token anyway"
    Even if propagation worked, a token captured at request time may have expired by the time a
    scheduled job runs, and acting on a user's behalf hours later is rarely the intent. Use a client
    credentials grant for machine-to-machine work.

### 6.6 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Injecting `RestClient.Builder` directly | It compiles and works | No timeouts, no correlation, no error mapping |
| Building a client per request | It reads naturally in a method | Defeats connection pooling. Build in the constructor |
| Retrying a non-idempotent POST | The annotation is easy to add | Duplicate effects. Pair with [`@Idempotent`](10-coordination.md) |
| Forgetting `base-config: default` | Named instances look complete | You silently lose the platform's tuning |
| Timeout tuned to the callee's p99 | It seems responsive | Tune to *your* caller's SLA |
| A fallback that calls something | It looks like graceful degradation | It runs when things are broken. Return a constant |
| Counting 4xx as breaker failures | The default counts all exceptions | `ignore-exceptions`. A 400 is not the dependency failing |
| Self-invocation past `@Retry` | Nothing warns you | Same proxy caveat as everywhere else |
| Relaying tokens to third parties | The relay is destination-blind | §5.5 |
| Expecting relay in a scheduled job | It works in a request | `SecurityContextHolder` is thread-local |
| One client name for many dependencies | Fewer names to manage | You lose per-dependency tuning and dashboards |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), plus a second process (or a
`MockWebServer`) to call.

### Lab 1 — Basic: watch the conventions apply

**Goal.** See what a factory-built client does that a plain one does not.

**Steps.**

1. Build a client with `PlatformRestClientFactory` against a slow endpoint (a stub sleeping 30 s).
2. Call it and time the failure. Which timeout fired?
3. Build a second client by injecting `RestClient.Builder` directly. Call the same endpoint. Time it.
4. Point the factory client at an endpoint returning 500 with a problem body. Catch the exception and
   print `status()`, `bodySnippet()`, and `remoteCorrelationId()`.
5. Inspect the outbound request headers the stub received. Which did you not set yourself?

**Expected outcome.** Step 2 fails at ~10 s. **Step 3 does not fail** — it waits for the full 30 s,
which is the defect the capability removes. Step 4 shows the remote correlation id. Step 5 shows
`X-Correlation-Id`, and `Authorization` if the call was made inside an authenticated request.

**Hints.**

- `MockWebServer` (already a platform test dependency) makes the slow stub trivial.
- For step 5, `recordedRequest.getHeaders()` lists everything.

**How to verify.** A test asserting the factory-built client throws within 11 s and the outbound
request carried `X-Correlation-Id`.

### Lab 2 — Intermediate: open a circuit breaker on purpose

**Goal.** Drive the state machine and watch it in metrics.

**Steps.**

1. Annotate a method `@CircuitBreaker(name = "flaky")` against a stub that always fails.
2. Call it once. Check `resilience4j_circuitbreaker_state`. Which state?
3. Call it ten times. Now which state? Explain the delay in terms of §2.2.
4. Call once more and time it. Compare with call one. What exception type?
5. Add `@Retry(name = "flaky")`. Repeat. How many *calls* did it take to open now, and why?
6. Configure `wait-duration-in-open-state: 5s`, wait, and call again. Observe `HALF_OPEN`.
7. Add a `fallbackMethod`. Call while open. What does the caller see now?

**Expected outcome.** Step 2 is `closed` — one failure is not a rate. Step 3 opens it. Step 4 fails in
microseconds with `CallNotPermittedException`. Step 5 opens after roughly four *operations*, because
each made three recorded attempts (§6.3). Step 7 turns a failure into a degraded success.

**Hints.**

- `curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq`
- The fallback signature must be the guarded method's parameters plus a `Throwable`. A mismatch fails
  silently at runtime, not at compile time.

**How to verify.** A test that drives ten failures, asserts the eleventh throws
`CallNotPermittedException`, and asserts it returns in under 50 ms.

### Lab 3 — Advanced: budget a call chain, and stop a token leak

**Goal.** Reason about total time across a chain, then find and fix a real security exposure.

**Steps.**

1. Build A → B → C, each with the platform defaults, each stub sleeping 8 s.
2. Compute A's worst-case latency on paper *before* running it. Include retries and backoff.
3. Run it and compare with your estimate. Explain any gap.
4. Give A an SLA of 3 seconds. Configure timeouts and retry counts down the chain so the budget holds.
   Write down what you gave up.
5. Add `@TimeLimiter` at A. Does it bound the whole operation including retries? Verify, do not assume.
6. Now the security half: make C an *external* host. Call A inside an authenticated request and capture
   what C received. Is the bearer token there?
7. Fix it with the naming convention and stripping customizer from §5.5. Verify C receives no
   `Authorization` header while B still does.
8. Add a test that fails if any client whose name starts with `external-` sends an `Authorization`
   header.

**Expected outcome.** Step 3 shows a worst case far above the naive sum — retries at each hop multiply.
Step 4 forces a real trade: fewer retries, or tighter timeouts, or fewer hops. **Step 6 shows the token
reaching an external host**, which is the exposure §2.7 warns about, reproduced on purpose. Step 8 is
the deliverable.

**Hints.**

- Worst case per hop: attempts × read-timeout + total backoff. Then compound down the chain.
- Step 5's answer depends on where `@TimeLimiter` sits relative to `@Retry` (§6.3). Measure it.
- Step 8 is worth keeping in a real codebase — it is a security regression test, not a lab artifact.

**How to verify.** The chain completes or fails within A's 3 s budget, and the external-client test
fails when you remove the stripping customizer.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-restclient</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-resilience</artifactId>
</dependency>
```

**Build a client**

```java
this.client = factory.builder("orders").baseUrl("https://orders.internal").build();  // in the constructor
```

**Guard a call**

```java
@Retry(name = "orders")
@CircuitBreaker(name = "orders", fallbackMethod = "fallback")
String call() { ... }
String fallback(Throwable cause) { return "DEFAULT"; }   // must not do anything that can fail
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.restclient.defaults.connect-timeout` | `2s` |
| `dc.platform.restclient.defaults.read-timeout` | `10s` |
| `dc.platform.restclient.clients.<name>.*` | — (the main tuning surface) |
| `dc.platform.restclient.propagate-correlation` | `true` |
| `dc.platform.resilience.enabled` | `true` (false = library defaults, not "off") |

**Platform defaults**

| Policy | Value |
|---|---|
| Retry | 3 attempts, 200 ms × 2 exponential |
| Circuit breaker | 50% failure over a 10-call **count-based** window |
| Time limiter | 5 s |

Always `base-config: default` on a named instance, or you lose all of the above.

**Exception**

```java
catch (RemoteCallException e) {
    e.status();                 // remote HTTP status
    e.bodySnippet();            // truncated to 1KB — safe to log
    e.remoteCorrelationId();    // Optional<String> — joins both sides' logs
}
```

**Diagnose it**

```bash
curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq
curl -s localhost:8080/actuator/metrics/http.client.requests | jq '.availableTags'
curl -s localhost:8080/actuator/configprops | jq '.. | .restclient? // empty'
curl -s localhost:8080/actuator/beans | jq '.. | select(.=="platformTokenRelayCustomizer")?'
```

**Rules of thumb**

- Inject `PlatformRestClientFactory`, never `RestClient.Builder`.
- Build the client in the constructor. One client name per remote dependency.
- Retry multiplies your timeout budget. Reason about total time, not per attempt.
- Only retry what is transient *and* idempotent *and* whose dependency has headroom.
- Always `base-config: default`.
- A fallback must not do anything that can fail.
- 4xx is not the dependency failing — `ignore-exceptions`.
- Alert on breaker state transitions. It is the earliest signal you have.
- **Token relay is destination-blind.** Never build an external client with the factory unguarded.
- The relay needs the request thread. Background work needs its own credentials.
- Never make a remote call while holding a database transaction.

---

**Next:** [Chapter 7 — Messaging and Events](07-messaging-events.md), which removes the synchronous
coupling this chapter has been defending against.

**Reference:** [modules/restclient.md](../../modules/restclient.md) ·
[modules/resilience.md](../../modules/resilience.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
