# Rate Limiting

Keep a caller within a permitted request rate. Limits apply **per key** (a user, tenant, or IP) over a
sliding or fixed **window**, and an over-limit request is rejected with **HTTP 429** and a
`Retry-After` hint. Two providers cover the local and the cluster-wide case.

## What you get

- **`@RateLimited(name, permits, window, keyExpression)`** — rate-limits a method. The key is
  `name` plus the SpEL `keyExpression` over the arguments (empty = one global bucket for the method).
  Over-limit calls throw `RateLimitExceededException`, mapped to 429 by the platform's MVC advice.
- **`RateLimiter`** — `tryAcquire(key, permits, window)` returns a `Decision(allowed, retryAfter)` for
  programmatic checks.
- **Optional HTTP filter** — rate-limits *every* inbound request, keyed by user or IP, answering 429
  with `Retry-After` and an RFC-9457 `application/problem+json` body. Off by default.
- **Two providers, chosen by classpath:**
  - **In-memory (default)** — a per-JVM sliding-window counter (Caffeine). Zero infrastructure; the
    effective limit multiplies by the instance count (a WARN is logged in `prod`).
  - **Redis** — a cluster-wide fixed-window counter via an atomic INCR + PEXPIRE Lua script. Selected
    when a `StringRedisTemplate` is present. Fails open if Redis is unreachable.
- **Metrics** — `dc.platform.ratelimit.decisions` (tagged `name`, `outcome`) when Micrometer is present.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-ratelimit</artifactId>
</dependency>
```

The starter wires the in-memory provider. Add `platform-ratelimit-redis` plus a `StringRedisTemplate`
(e.g. via the platform Redis starter) to switch to a cluster-wide limit.

## Usage

```java
@RateLimited(name = "search", permits = 20, window = "PT1S", keyExpression = "#user")
public List<Hit> search(String user, String query) { ... }
```

```java
if (!rateLimiter.tryAcquire("tenant:" + id, 1000, Duration.ofMinutes(1)).allowed()) {
    throw new IllegalStateException("slow down");
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.ratelimit.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.ratelimit.http.enabled` | `false` | Install the all-requests HTTP filter. |
| `dc.platform.ratelimit.http.key-by` | `IP` | `IP` or `USER` — the filter's key dimension. |
| `dc.platform.ratelimit.http.permits` | `100` | Permits per window per key for the filter. |
| `dc.platform.ratelimit.http.window` | `PT1M` | The filter's window length. |

The provider is selected by the classpath (Redis over in-memory), not by a property.

## Replace / Disable

- Define your own `RateLimiter` bean to replace the platform limiter entirely (it backs off).
- Define your own `RateLimiterProvider` bean to plug a different backend under the platform limiter.
- `dc.platform.ratelimit.enabled=false` switches the capability off wholesale.

## Design notes

- **429, not a BusinessException (decision D54).** The errors `HttpStatusHint` enum has no 429 value,
  so rejection is a distinct `RateLimitExceededException` mapped to 429 by an MVC advice; the HTTP
  filter writes its own 429 (it runs before MVC).
- **Fail-open Redis.** A rate limiter must not become an availability incident: on a Redis error the
  provider allows the request and logs a WARN.

## Local dev notes

The in-memory provider is fully tested against a mutable clock — no Docker. The Redis provider's
command behavior is unit-tested with mocks; its wire behavior is verified against a real Redis in a
`@Tag("docker")` integration test under `-Pdocker`.
