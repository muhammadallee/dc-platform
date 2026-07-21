# Cache

Platform caching conventions on top of Spring's own `@Cacheable`: a key convention that keeps
services from colliding in a shared backend, per-cache TTL/size policy, and a Caffeine (default) or
Redis `CacheManager` chosen by what's on the classpath.

## What you get

- **`CacheKeyConvention`** — `key(cacheName, parts...)` composes `<appName>:<cacheName>[:<part>]*`.
  Inject it where you compute a key outside `@Cacheable` SpEL.
- **`CacheNames`** — dot-separated cache-name conventions with a validating `CacheNames.of(...)`.
- **Per-cache policy** — `dc.platform.cache.caches.<name>.ttl` / `.max-size`, applied to whichever
  provider is active.
- **Caffeine (default)** — an in-memory `CaffeineCacheManager` when Caffeine is on the classpath.
- **Redis** — a `RedisCacheManager` with String keys and JSON values when Spring Data Redis is on
  the classpath (takes precedence over Caffeine when both are present).
- **Metrics** — the platform `CacheManager` is a normal bean, so Boot's cache metrics instrument it
  automatically when Micrometer/actuator are present.

No custom cache annotation: the programming model stays Spring's `@Cacheable`/`@CacheEvict`.

## Starter coordinates

Pick one provider:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-cache-caffeine</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-cache-redis</artifactId>
</dependency>
```

## Usage

```java
@Cacheable("orders.by-id")
public Order find(String id) { ... }
```

```yaml
dc:
  platform:
    cache:
      caches:
        orders.by-id:
          ttl: 5m
          max-size: 10000
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.cache.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.cache.caches.<name>.ttl` | (none) | Time-to-live after write for that cache. |
| `dc.platform.cache.caches.<name>.max-size` | (none) | Maximum entry count (Caffeine). |

## Replace / Disable

- Define your own `CacheManager` bean to replace the platform default entirely (it backs off).
- Define your own `CacheKeyConvention` bean to override the key format.
- `dc.platform.cache.enabled=false` switches the capability off wholesale.

## Local dev notes

The default build and the Caffeine provider need no Docker. The Redis provider is verified against a
real Redis via a `@Tag("docker")` test run under `-Pdocker`.
