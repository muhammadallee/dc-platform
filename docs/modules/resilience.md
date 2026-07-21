# Resilience

Fault-tolerance for calls to unreliable dependencies, built on [Resilience4j](https://resilience4j.readme.io/).
The platform contributes sensible default tuning and a small programmatic helper; the programming
model is Resilience4j's own annotations — the platform ships **no** wrapper annotations over them.

## What you get

- **Default instance tuning** — the `default` named configuration for each Resilience4j registry,
  contributed as lowest-precedence `resilience4j.*` environment defaults (your `application.yml`
  always wins):
  - retry: 3 attempts, exponential backoff (200ms × 2)
  - circuit breaker: opens at a 50% failure rate over a 10-call count window
  - time limiter: 5s
- **`RetryableOperation`** — a programmatic retry helper (`<T> T call(String name, Supplier<T>)`) for
  call sites that cannot use the `@Retry` annotation (dynamic policy names, non-Spring call sites).
  The `name` resolves against the same retry registry the annotations use.
- **`ResilienceDefaults`** — the default values as constants, so application code and the platform
  share one source of truth.
- **Metrics** — Resilience4j's Micrometer binding is on by default, so retries, breaker state, and
  time-limiter events are instrumented when Micrometer/actuator are present.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-resilience</artifactId>
</dependency>
```

## Usage

Declarative (preferred) — Resilience4j's own annotations, tuned by the platform defaults:

```java
@Retry(name = "inventory")
@CircuitBreaker(name = "inventory")
public InventoryLevel check(String sku) { ... }
```

Programmatic — when a policy name is dynamic or the call site is not a Spring bean:

```java
String body = retryableOperation.call("inventory", () -> restClient.get(uri).body(String.class));
```

Override any default with Resilience4j's native keys:

```yaml
resilience4j:
  retry:
    instances:
      inventory:
        max-attempts: 5
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.resilience.enabled` | `true` | Kill switch for the whole capability. |
| `resilience4j.retry.configs.default.*` | 3 attempts, exp backoff | Platform retry defaults (Resilience4j keys; overridable). |
| `resilience4j.circuitbreaker.configs.default.*` | 50% / 10-call window | Platform breaker defaults. |
| `resilience4j.timelimiter.configs.default.*` | 5s | Platform time-limiter default. |

The `resilience4j.*` keys and their metadata are documented by Resilience4j itself; the platform only
supplies the `default`-instance values at lowest precedence.

## Replace / Disable

- Define your own `RetryableOperation` bean to replace the platform helper (it backs off).
- Override any `resilience4j.*` key in `application.yml` — your value always wins over the defaults.
- `dc.platform.resilience.enabled=false` switches the capability off and skips the default
  contribution entirely.

## Local dev notes

No Docker required. Retry, circuit-breaker, and time-limiter behavior are proven with in-process
Resilience4j registries.
