# Redis

Direct Redis access with platform conventions — distinct from the [cache](cache.md) capability,
which uses Redis as a `@Cacheable` backend. This capability is for services that talk to Redis
directly via `StringRedisTemplate`.

## What you get

- **Per-service key prefix** — every `StringRedisTemplate` key is namespaced with a prefix (default
  `<spring.application.name>:`), so services sharing a Redis never step on each other's keys. Applied
  by a `BeanPostProcessor` that installs a prefixing key serializer on the template (Boot's or your
  own), so your code uses logical keys (`"k"`) while the wire key is `"svc:k"`.
- **`CapabilityDescriptor`** — reports the active prefix in the startup banner.

Connection tuning (host, port, timeout, pool) stays on Boot's own `spring.data.redis.*` keys.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-redis</artifactId>
</dependency>
```

## Usage

```java
@Service
class Sessions {
    private final StringRedisTemplate redis;

    Sessions(StringRedisTemplate redis) { this.redis = redis; }

    void put(String id, String value) {
        redis.opsForValue().set(id, value);   // stored at "<app>:<id>"
    }
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.redis.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.redis.key-prefix` | `<spring.application.name>:` | Prefix prepended to every key. |

## Caveat: server-side scans

`SCAN`/`KEYS` operate on the raw stored keys, so any pattern you build must include the prefix — the
prefixing serializer only rewrites keys flowing through the `StringRedisTemplate` value/key API, not
literal patterns.

## Local dev notes

No Docker for the default build; the prefix round-trip against a real Redis is a `@Tag("docker")`
test run under `-Pdocker`.
