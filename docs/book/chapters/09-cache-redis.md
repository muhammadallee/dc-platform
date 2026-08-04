# Chapter 9 — Caching and Redis

> **Capabilities covered:** `cache`, `redis`
>
> Cache abstraction with a platform key convention, and direct Redis access when you need it.
>
> **Starters:** `platform-starter-cache-{caffeine,redis}`, `platform-starter-redis` ·
> **Reference:** [modules/cache.md](../../modules/cache.md) ·
> [modules/redis.md](../../modules/redis.md)

---

## 1. Introduction and Business Value

Two capabilities that both involve Redis and are deliberately kept apart:

- **Cache** — Spring's `@Cacheable`, backed by Caffeine or Redis, with a platform key convention and
  per-cache TTL policy. Data here is **derived and disposable**: losing it costs latency, nothing more.
- **Redis** — direct `StringRedisTemplate` access for sessions, counters, and operational state. Data
  here is **primary**: losing it costs correctness.

§2.5 explains why merging them is a real failure mode rather than a purity argument.

### The problem cache solves

Caching itself is not the platform's contribution — Spring already has `@Cacheable`, and the platform
deliberately adds no annotation of its own. What the platform adds is the thing that goes wrong when
a hundred services share one cache backend.

```
   orders-service     @Cacheable("orders")   key = 42   ->  Redis key "orders::42"
   billing-service    @Cacheable("orders")   key = 42   ->  Redis key "orders::42"
                                                              ^^^^^^^^^^^^^^^^^^
                                                              the same key. Different data.
```

Two services, both caching "order 42", both perfectly reasonable in isolation. One overwrites the
other's value, and each then serves the other's data as its own.

This is one of the nastiest bugs in a service estate: there is no error, no exception, no metric that
moves. Billing shows an order shape it did not compute, intermittently, depending on which service
wrote last. Reproducing it requires knowing that a shared cache exists at all.

`CacheKeyConvention` prefixes every key with the application name, so the collision is structurally
impossible.

### The problem redis solves

The same problem, one layer down. A service using `StringRedisTemplate` directly writes whatever key
it asks for — so two services with a `"session:abc"` key overwrite each other.

The platform installs a **prefixing key serializer** via a `BeanPostProcessor`, so your code says
`"session:abc"` and the wire key is `"orders-service:session:abc"`. Namespacing you cannot forget,
because it is not at the call site.

### What each capability actually gives you

| | Cache | Redis |
|---|---|---|
| Programming model | Spring's `@Cacheable` — nothing new to learn | Boot's `StringRedisTemplate` — nothing new to learn |
| Platform addition | Key convention, name convention, per-cache TTL/size policy | Automatic key prefixing |
| Provider | Caffeine by default, Redis when Spring Data Redis is present | Redis, obviously |
| Metrics | Boot's cache metrics, automatically | Boot's Redis metrics |
| Local development | Caffeine — no infrastructure at all | Needs a Redis |

!!! success "Best practice — the platform adds conventions, not vocabulary"
    There is no `@PlatformCacheable`. The programming model stays Spring's, so existing knowledge,
    documentation, and tooling apply unchanged. This is the same refusal as
    [resilience](06-restclient-resilience.md) §1 — the platform's value is in the conventions around
    the abstraction, not in a new abstraction.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No key convention | Two services write different values to the same key. Silent, intermittent data corruption |
| No name convention | Free-form cache names make inventory, metrics, and eviction runbooks impossible |
| No per-cache policy | Cache tuning is provider-specific code; switching backends means rewriting it |
| No Redis key prefix | Direct-access keys collide across services in a shared instance |
| Cache and direct access merged | Cache eviction destroys session state |
| No cache metrics | A 2% hit-rate cache adds a hop and latency, and nobody notices |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Cache *names* versus cache *keys*

These are different things and the distinction matters, because they are configured separately and
collide differently.

```
   CACHE NAME                  "orders.by-id"
     |  identifies a logical cache
     |  carries the TTL / max-size policy
     |  is what you write in @Cacheable("orders.by-id")
     v
   CACHE KEY                   "orders-service:orders.by-id:42"
     |  identifies one entry within that cache
     |  is what actually lands in Redis
     |  is where cross-service collision happens
```

`CacheNames.of("orders", "by-id")` builds and validates a name. `CacheKeyConvention.key(...)` composes
a key. Configuration is per *name*: `dc.platform.cache.caches.orders.by-id.ttl`.

### 2.2 The key convention

The default composes `<appName>:<cacheName>[:<part>]*`:

```java
convention.key("orders", customerId, "summary");
// -> "orders-service:orders:42:summary"
```

The application-name prefix is the whole point: two services computing the same logical key produce
different physical keys, so a shared Redis is safe by construction rather than by everyone remembering.

!!! note "For `@Cacheable`, the convention is applied for you"
    You do not call `CacheKeyConvention` in the common case — the platform's `CacheManager` applies
    the convention. Inject it when you must compute a key **outside** Spring's SpEL: manual eviction,
    a warm-up job, or a diagnostic that inspects Redis directly. §4.4.

### 2.3 Provider selection by classpath

```
   Spring Data Redis on the classpath?
        |
        +-- YES:  RedisCacheManager     (String keys, JSON values)
        |
        +-- NO:   CaffeineCacheManager  (in-process, heap)
```

Driven by which starter you add, not by configuration. The same `@Cacheable` code and the same
`dc.platform.cache.caches.*` policy apply to both.

That is what makes local development honest: a laptop runs Caffeine with no infrastructure, production
runs Redis, and **the code and the policy are identical**. Compare the alternative — everyone runs
Redis locally, or local and production caching behave differently.

!!! warning "Caffeine and Redis are not behaviourally identical, and the difference matters at scale"
    | | Caffeine | Redis |
    |---|---|---|
    | Scope | **Per instance** | **Shared** |
    | Survives restart | No | Yes |
    | N instances | N independent caches | One cache |
    | Invalidation | Local only — other instances keep stale data | Global |

    That third row is the trap. With three replicas and Caffeine, an eviction on instance 1 leaves
    instances 2 and 3 serving stale data until their own entries expire. If your correctness depends on
    invalidation being seen fleet-wide, Caffeine is the wrong provider — regardless of how well it
    performs.

### 2.4 Why the `CacheManager` is a plain bean

The platform contributes an ordinary `CacheManager`, not a wrapper.

The payoff is that **Boot's own cache metrics instrument it automatically** — hit ratio, miss ratio,
eviction count, size — with no platform code involved. A wrapper would have hidden the underlying
cache from Boot's binder, and the platform would then have had to re-implement metrics itself, badly.

This is worth internalising as a general principle: *contribute the framework's own type wherever
possible, so the framework's own tooling keeps working.*

!!! success "Best practice — hit ratio is the only number that tells you a cache is worth having"
    A cache with a 2% hit rate is a network hop, a serialisation cost, and an invalidation problem, in
    exchange for almost nothing. Without metrics you would never know. §5.2.

### 2.5 Why cache and redis are separate capabilities

They both talk to Redis. Merging them would be one starter instead of two. The reason not to is a
lifecycle difference that becomes a correctness problem:

| | Cached data | Operational data |
|---|---|---|
| Nature | Derived — recomputable from the source of truth | Primary — not derivable from anywhere |
| Losing it costs | Latency | Correctness |
| Eviction | Expected, routine, safe | **Data loss** |
| TTL | A tuning knob | A business rule |

If session state lived in the cache manager, then a cache-wide eviction — a routine operation, a memory
pressure response, a deployment that flushes caches — would log every user out. Or a `maxmemory-policy`
of `allkeys-lru` would evict a rate-limit counter under memory pressure and silently reset someone's
quota.

Keeping them separate means the operational decisions stay separate too: you can flush caches without
touching sessions, and you can reason about Redis memory policy per key space.

!!! warning "This also constrains your Redis `maxmemory-policy`"
    `allkeys-lru` evicts *anything* under memory pressure, including your sessions and counters.
    `volatile-lru` evicts only keys with a TTL — which is the cache entries, and not the operational
    state you never gave a TTL. §5.1.

### 2.6 The `BeanPostProcessor`, and the one thing it cannot do

The redis capability installs a prefixing key serializer on `StringRedisTemplate` — Boot's, or your own
— through a `BeanPostProcessor`. Your code uses logical keys; the wire key is namespaced.

```
   your code:      redis.opsForValue().set("session:abc", value)
   on the wire:    SET orders-service:session:abc <value>
```

There is exactly one sharp edge, and the platform documents it rather than hiding it:

!!! warning "`SCAN` and `KEYS` patterns must include the prefix yourself"
    The serializer rewrites keys **flowing through the template's key API**. A server-side scan pattern
    is a *literal string argument*, not a key — so it is passed through untouched:

    ```java
    redis.keys("session:*");                    // matches NOTHING — the stored keys are prefixed
    redis.keys("orders-service:session:*");     // correct
    ```

    The failure mode is a scan that silently returns empty, which is easily misdiagnosed as data loss.
    This is the price of transparent prefixing, and it is the reason the caveat is in the class javadoc,
    the module page, and here.

---

## 3. Feature Reference

### 3.1 Cache — public API

Package `ae.gov.dubaicustoms.platform.cache`. Both STABLE, both `@PlatformApi`.

| Type | Kind | Purpose |
|---|---|---|
| `CacheKeyConvention` | interface | `String key(String cacheName, Object... parts)` |
| `CacheNames` | final class | `static String of(String... segments)`, `SEGMENT_SEPARATOR = "."` |

`CacheNames.of` validates: at least one segment, no blank segment, no segment containing `.`. It throws
`IllegalArgumentException` otherwise — so a malformed name fails where it is declared rather than
producing a cache nobody can find.

!!! warning "`CacheNames.of` runs at runtime, so it cannot name a cache in `@Cacheable`"
    An annotation value must be a compile-time constant. `static final String C = CacheNames.of(...)`
    is not one, so `@Cacheable(C)` does not compile. Declare the name as a string literal and assert it
    against `CacheNames.of` in a test — §4.2 shows the pattern. Its real uses are composing a name for
    a programmatic lookup, and validating one in a test.

### 3.2 Redis — what it contributes

The redis capability has **no public API types**. It contributes:

| Contribution | Detail |
|---|---|
| Prefixing key serializer | Installed on `StringRedisTemplate` by a `BeanPostProcessor` |
| Capability descriptor | Reports the active prefix in the startup banner |

Connection tuning stays on Boot's `spring.data.redis.*`. There is deliberately no parallel platform
dialect for host, port, timeout, or pool.

### 3.3 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

**Cache**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.cache.enabled` | Boolean | `true` | Kill switch | To diagnose whether a bug is a caching bug |
| `dc.platform.cache.caches.<name>.ttl` | Duration | — (no expiry) | Time-to-live after write for that cache | **Routinely.** Per-cache, per-environment |
| `dc.platform.cache.caches.<name>.max-size` | Long | — (unbounded) | Maximum entry count | For Caffeine, to bound heap |

**Redis**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.redis.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.redis.key-prefix` | String | `""` → `<spring.application.name>:` | The prefix on every template key | To match an existing key taxonomy — **carefully**, see §5.3 |

!!! warning "An unset `ttl` means entries never expire"
    Both components of a cache spec are optional, and an absent `ttl` means no expiry. For Caffeine
    that is a heap leak bounded only by `max-size`; for Redis it is a key that lives until evicted or
    deleted. **Always set a TTL**, and treat an unbounded cache as a decision you have to justify.

### 3.4 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformCacheAutoConfiguration` | A Spring `CacheManager` type on the classpath, `cache.enabled != false` | `platformCacheKeyConvention`, a `CacheManager` (Caffeine or Redis), capability descriptor | You define a `CacheManager` or `CacheKeyConvention` bean |
| `PlatformRedisAutoConfiguration` | Spring Data Redis on the classpath, `redis.enabled != false` | The key-prefix `BeanPostProcessor`, capability descriptor | Standard `@ConditionalOnMissingBean` |

The cache autoconfiguration is ordered `afterName` Boot's Redis autoconfiguration — so the
`RedisConnectionFactory` exists when `@ConditionalOnBean` is evaluated — and `before`
`CacheAutoConfiguration`, so the platform's manager wins the `@ConditionalOnMissingBean` race and Boot's
default never applies.

!!! note "`afterName` by string, again"
    Same reason as [Chapter 5](05-security-authz.md) §6.3: ordering by class literal would require a
    dependency the constitution does not allow. The pattern recurs throughout the platform.

### 3.5 Extension points

| Extension | How | Effect |
|---|---|---|
| Change the key convention | A `CacheKeyConvention` bean | Platform's default backs off |
| Use a different cache provider | A `CacheManager` bean | Platform's backs off entirely |
| Per-cache policy | `dc.platform.cache.caches.<name>.*` | No code |
| Change the Redis key prefix | `dc.platform.redis.key-prefix` | No code |

---

## 4. How-to Guide

### 4.1 Add the capabilities

Pick exactly one cache provider:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-cache-caffeine</artifactId>   <!-- or -cache-redis -->
</dependency>
```

Add the redis capability only if you need **direct** access, separately from caching:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-redis</artifactId>
</dependency>
```

### 4.2 Cache something

```java snippet:book-09-cacheable
@Service
class TariffService {

    // A compile-time constant, because an annotation value must be one. See the callout below.
    static final String TARIFF_CACHE = "tariff.by-code";

    @Cacheable(TARIFF_CACHE)
    String rateFor(String hsCode) {
        // Only runs on a miss.
        return expensiveLookup(hsCode);
    }

    @CacheEvict(cacheNames = TARIFF_CACHE, key = "#hsCode")
    void invalidate(String hsCode) {
        // Evicts one entry.
    }

    private String expensiveLookup(String hsCode) {
        return "rate-for-" + hsCode;
    }
}
```

Plain Spring. The platform supplies the manager, the key convention, and the policy.

!!! warning "`CacheNames.of(...)` cannot be used in an annotation"
    ```java
    static final String TARIFF_CACHE = CacheNames.of("tariff", "by-code");
    @Cacheable(TARIFF_CACHE)     // does not compile: element value must be a constant expression
    ```
    Java requires an annotation value to be a **compile-time constant**, and a method call is not one —
    even assigned to a `static final` field. So `CacheNames.of` cannot name a cache at the place you
    most want to name one.

    Use a string literal in the constant, and put `CacheNames.of` where it *can* run: a test that
    asserts your literal matches the convention.

    ```java
    @Test
    void cacheNameFollowsTheConvention() {
        assertThat(TariffService.TARIFF_CACHE).isEqualTo(CacheNames.of("tariff", "by-code"));
    }
    ```

    That gets you the validation without fighting the language — and the test fails if someone
    later writes `tariffByCode` or `Tariff.ByCode`.

!!! warning "`@Cacheable` is proxy-based — self-invocation bypasses it"
    `this.rateFor(code)` from another method on the same bean does not go through the proxy, so nothing
    is cached and nothing warns you. Same caveat as
    [validation](02-errors-validation.md), [authorization](05-security-authz.md),
    [idempotency](10-coordination.md), and [audit](12-audit.md). Here it costs performance rather than
    correctness — which is why it goes unnoticed for longer.

### 4.3 Set a policy

```yaml
dc:
  platform:
    cache:
      caches:
        tariff.by-code:
          ttl: 1h
          max-size: 10000
        customer.summary:
          ttl: 5m
          max-size: 1000
```

The same block applies whether the active provider is Caffeine or Redis.

!!! success "Best practice — derive the TTL from how stale the data may be, not from how often it changes"
    "Tariff rates change quarterly" does not mean a 3-month TTL is right; it means a stale rate is
    wrong for up to 3 months. Ask instead: *how out-of-date may this be before someone is harmed?*
    That is usually a much shorter number, and it is the one to configure.

### 4.4 Compute a key outside SpEL

```java snippet:book-09-key-convention
@Service
class CacheWarmer {

    private final CacheKeyConvention convention;

    CacheWarmer(CacheKeyConvention convention) {
        this.convention = convention;
    }

    String keyFor(String customerId) {
        // "orders-service:customer.summary:42:v2"
        return convention.key(CacheNames.of("customer", "summary"), customerId, "v2");
    }
}
```

Useful for warm-up jobs, manual eviction, and diagnostics that inspect the backend directly.

### 4.5 Use Redis directly

```java snippet:book-09-redis-direct
@Service
class SessionStore {

    private final StringRedisTemplate redis;

    SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void put(String sessionId, String payload) {
        // Wire key is "<app>:session:<id>" — the prefix is applied for you.
        redis.opsForValue().set("session:" + sessionId, payload, Duration.ofMinutes(30));
    }

    Optional<String> get(String sessionId) {
        return Optional.ofNullable(redis.opsForValue().get("session:" + sessionId));
    }
}
```

!!! warning "Set a TTL on operational keys yourself"
    Unlike cache entries, nothing gives these keys an expiry. A session store with no TTL grows forever
    and is the most common cause of a Redis instance filling up.

### 4.6 Scan safely

```java
String prefix = environment.getProperty("spring.application.name") + ":";
Set<String> keys = redis.keys(prefix + "session:*");     // pattern must carry the prefix
```

!!! warning "Do not run `KEYS` against a production Redis"
    `KEYS` is O(n) over the entire keyspace and **blocks the server** while it runs — on a large
    instance that is a multi-second stall for every client. Use `SCAN` (cursor-based, incremental), and
    prefer designing a secondary index over scanning at all.

### 4.7 Replace the key convention

```java
@Bean
CacheKeyConvention cacheKeyConvention() {
    return (cacheName, parts) -> "acme:" + cacheName + ":" +
            Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(":"));
}
```

!!! warning "Changing the convention invalidates every existing entry"
    Old keys are unreachable — not deleted, just orphaned. In Redis they occupy memory until they
    expire or are evicted. Treat a convention change as a cache flush plus a memory-pressure event, and
    do it deliberately.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | `cache-caffeine` | No infrastructure. Same code, same policy |
| Production, single instance | Either | Caffeine is faster and simpler |
| **Production, multiple instances with invalidation** | `cache-redis` | Caffeine invalidation is instance-local — §2.3 |
| All | An explicit `ttl` on every cache | An unset TTL means never expires |
| Redis, production | `maxmemory-policy: volatile-lru` | `allkeys-lru` will evict your sessions and counters |
| Redis, production | Monitor memory | §5.2 |

!!! warning "The `maxmemory-policy` decision is a correctness decision"
    With `allkeys-lru`, Redis evicts *any* key under memory pressure — including the ones the
    [redis capability](#) stores without a TTL, and including [locking](10-coordination.md) and
    [rate-limit](13-ratelimit-flags.md) state. `volatile-lru` evicts only keys that carry a TTL, which
    is the cache entries and nothing else. This is a server-side setting your platform team owns, not an
    application property — make sure someone has actually decided it.

### 5.2 What to monitor

| Signal | Meter | Alert when |
|---|---|---|
| **Hit ratio** | `cache_gets_total{result="hit"}` vs `"miss"` | Below ~50% — the cache may be costing more than it saves |
| Cache size | `cache_size` | Approaching `max-size` — entries are being evicted early |
| Eviction rate | `cache_evictions_total` | Climbing — the cache is too small for the working set |
| Redis memory | `redis_memory_used_bytes` (server-side) | Approaching `maxmemory` |
| Redis latency | `spring_data_redis_*` or client timers | p99 climbing — a slow cache is worse than no cache |
| Redis connection pool | Lettuce pool metrics | Exhaustion under load |

!!! tip "Hit ratio is the one number to check first, and almost nobody does"
    Because the `CacheManager` is a plain bean (§2.4), Boot instruments it for free — there is nothing
    to set up. A cache below 50% hit rate is adding a lookup, a serialisation, and an invalidation
    problem for a benefit you should measure before keeping. Check it once per cache, per environment,
    after real traffic.

### 5.3 Troubleshooting

**Nothing is being cached.**

| Cause | Check |
|---|---|
| No `@EnableCaching` | Boot enables it when a `CacheManager` exists — confirm the starter is present |
| Self-invocation | `this.method()` bypasses the proxy. §4.2 |
| Capability disabled | `dc.platform.cache.enabled` |
| Method returns `null` | By default Spring caches nulls; some configurations do not |
| `@Cacheable` on a `private` or `final` method | Cannot be proxied |

**Stale data across instances.** Caffeine is per-instance (§2.3). Either switch to
`cache-redis`, or accept the staleness window and shorten the TTL to bound it.

**Redis keys are not where I expect.** The prefix is applied by the serializer. `redis-cli KEYS '*'`
shows the *actual* wire keys — start there rather than from what your code passes.

**A `SCAN`/`KEYS` pattern returns nothing.** The pattern needs the prefix. §2.6. This is the single
most common redis-capability question, and it looks exactly like data loss.

**Changing `key-prefix` orphaned everything.** Expected — old keys are unreachable. They will expire
(cache) or persist forever (operational keys with no TTL). Plan a migration or a flush.

**Redis is out of memory.** Usually operational keys with no TTL (§4.5), or a cache with no TTL (§3.3).
`redis-cli --bigkeys` and `INFO keyspace` find the offender. Check `maxmemory-policy` before you raise
`maxmemory`.

**Cache eviction logged everyone out.** You put session state in the cache manager. §2.5.

### 5.4 Scaling and performance

- **Caffeine is in-process**: nanoseconds, no serialisation, no network — and bounded by heap. `max-size`
  is your heap protection.
- **Redis is a network hop**: sub-millisecond typically, plus serialisation both ways. For a computation
  that takes 200 µs, a Redis cache is *slower* than recomputing.
- **Serialisation cost is real.** The Redis cache manager stores JSON. A large object graph costs more
  to serialise than many queries cost to run.
- **A slow cache is worse than no cache**, because you pay the lookup *and* the miss.
- **Cache stampede**: when a hot entry expires, every concurrent request misses simultaneously and all
  of them recompute. Spring's `@Cacheable(sync = true)` serialises the recompute per key on one instance;
  across instances you need a [lock](10-coordination.md) or staggered TTLs.

!!! success "Best practice — measure before caching"
    Cache what is *both* expensive *and* frequently re-read with the same key. Something expensive but
    read once is not cacheable; something cheap but read constantly does not need it. The hit-ratio
    metric tells you afterwards whether you guessed right.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| **Cached data outlives the request** | A cached value computed for one user may be served to another if the key does not include the user. This is the most serious risk in this chapter |
| Redis is often unencrypted internally | Cached PII is PII at rest, in a store with different access controls from your database |
| The key prefix is not a security boundary | It prevents accidental collision, not deliberate access. Any client can read any key |
| Cache poisoning | An attacker who can control a cache key can seed a value other requests then read |
| `@Cacheable` on a method that checks permissions | The check runs on a miss and is **skipped on a hit** |

!!! warning "The authorization-bypass-by-cache trap"
    ```java
    @Cacheable("orders")
    @RequiresPermission("orders:read")
    Order find(String id) { ... }
    ```
    Annotation order determines which advisor runs first, and if the cache advisor wins, **a cache hit
    returns the value without the permission check running at all**. Never cache a method whose result
    depends on the caller's identity unless the caller's identity is part of the key. Better still:
    keep authorization at the boundary and cache the layer beneath it, which has no identity dependence.

---

## 6. Deep Dive

### 6.1 Why the key convention prefixes with the application name

`<appName>:<cacheName>:<parts...>`. The application name comes first, and that ordering is not
arbitrary.

Redis keys are compared and scanned left to right. A common prefix means:

- `SCAN orders-service:*` finds everything one service owns — useful for a targeted flush, or for
  attributing memory to a service.
- Redis Cluster hashes on the key (or hash tag), so a common prefix does not by itself force everything
  onto one slot, but it does make per-service key-space reasoning possible.
- `redis-cli --scan --pattern 'orders-service:*' | wc -l` is a working answer to "how many keys does
  this service own?"

Put the cache name first instead, and none of those work — you would be scanning across services to
find one service's data.

### 6.2 The `BeanPostProcessor` approach, and its alternatives

The redis capability could have prefixed keys three ways:

| Approach | Cost |
|---|---|
| Ask developers to prefix at every call site | Forgotten exactly once, and the bug is silent |
| Wrap `StringRedisTemplate` in a platform type | A new API to learn; loses every Boot convenience method |
| **Post-process the bean and swap the key serializer** | Transparent; the one caveat in §2.6 |

The third wins because it is invisible in the common case and works on *Boot's* template or *yours* —
the post-processor does not care where the bean came from.

The cost is the scan caveat, which is inherent: a `SCAN` pattern is a string argument, not a key, so
nothing distinguishes it from any other string at the serializer boundary. No implementation of
transparent prefixing can fix that, which is why the platform documents it in three places rather than
pretending it away.

### 6.3 Why there is no platform cache annotation

`@Cacheable`, `@CacheEvict`, `@CachePut`, and `@Caching` already exist, are well documented, and are
understood by every Spring developer. A `@PlatformCacheable` would need to:

- reimplement SpEL key derivation, conditions, `unless`, and sync;
- stay at feature parity with Spring's as it evolves;
- be documented and taught;
- and invalidate every existing answer on the internet.

For what? The platform's actual contribution — key namespacing and policy — is delivered through the
`CacheManager`, which is *underneath* the annotation. The annotation layer needed no change at all.

!!! success "Best practice — when tempted to wrap, ask what the wrapper adds"
    If the answer is "consistency" or "so we control it", that is not a feature — it is a maintenance
    obligation. The platform wraps where it adds a guarantee (`EventPublisher` hides broker APIs) and
    refuses where it would only add a name.

### 6.4 What `dc.platform.cache.enabled=false` actually does

The same shape as every platform kill switch ([Chapter 6](06-restclient-resilience.md) §6.4): it stops
the platform contributing a `CacheManager`. It does **not** disable caching.

Boot's `CacheAutoConfiguration` — which the platform's is ordered `before` — is then free to apply, and
you get Boot's default manager with **no key convention and no platform policy**. Caching still happens;
the namespacing does not.

If you want to genuinely disable caching for a diagnosis, `spring.cache.type=none` is the honest
setting.

### 6.5 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Caching a method whose result depends on the caller | The key is the arguments, and the caller is not one | Include identity in the key, or do not cache it. §5.5 |
| `@Cacheable` above `@RequiresPermission` | Annotation order is invisible | A hit skips the permission check |
| Self-invocation | Nothing warns you | Nothing is cached, silently |
| No TTL | Both spec components are optional | Never expires. Heap leak or Redis growth |
| Caffeine with multi-instance invalidation | It works in development with one instance | Instance-local eviction leaves peers stale |
| `KEYS` in production | It is the obvious API | O(n) and it blocks the server |
| Scan pattern without the prefix | The prefixing is transparent everywhere else | Returns empty; looks like data loss |
| Session state in the cache manager | Both are "Redis" | A cache flush is now a logout event |
| `allkeys-lru` on a shared Redis | It is a common default | Evicts sessions, locks, and counters |
| Changing `key-prefix` casually | It is one property | Orphans every existing key |
| Caching something cheap | It feels like an optimisation | Lookup plus miss costs more than recomputing |
| Assuming `enabled=false` disables caching | The name says so | You get Boot's manager without the conventions |
| `@Cacheable(CacheNames.of(...))` | It looks like the intended use | Not a compile-time constant. Literal + a test. §4.2 |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), which caches with Caffeine.
`docker compose -f docker-compose.local.yml up redis` when you need a real Redis.

### Lab 1 — Basic: cache, evict, and measure

**Goal.** See a cache work, and find out whether it is worth having.

**Steps.**

1. Add `@Cacheable` to an expensive method. Call it twice with the same argument and log inside the
   method. How many times does the body run?
2. Call it from another method on the **same bean** using `this.`. How many times now?
3. Set a 10-second TTL. Call, wait 11 seconds, call again.
4. Check `cache_gets_total` — what are the `result` tag values, and the ratio?
5. Add `@CacheEvict` and prove the next call misses.
6. Drive realistic traffic and compute the hit ratio. Would you keep this cache?

**Expected outcome.** Step 1 runs the body once. **Step 2 runs it every time** — the self-invocation
bypass, reproduced deliberately. Step 6 is the real exercise: a number, and a decision.

**Hints.**

- `curl -s localhost:8080/actuator/metrics/cache.gets | jq '.availableTags'`
- If step 4 shows no meters, the `CacheManager` was replaced by a wrapper — §2.4.

**How to verify.** A test asserting the method body executes once across two calls, and a second
asserting it executes again after eviction.

### Lab 2 — Intermediate: collide two services, then stop them

**Goal.** Reproduce the silent corruption the key convention exists to prevent.

**Steps.**

1. Run two instances with **different** `spring.application.name` values against one Redis, both
   caching `@Cacheable("orders")` keyed on the same id, returning different values.
2. Call service A, then service B, then A again. Is A's value still A's?
3. Inspect the actual wire keys with `redis-cli KEYS '*'`. What do you see?
4. Now replace the platform's `CacheKeyConvention` with one that **omits** the application name.
   Repeat steps 2–3.
5. Explain precisely why step 4 corrupts and step 2 does not.
6. Add the redis capability to both and write the same logical key with `StringRedisTemplate`. Inspect
   the wire keys again.
7. Try `redis.keys("session:*")` and then the prefixed pattern. Explain the difference.

**Expected outcome.** Step 2 is safe — two prefixed key spaces. **Step 4 corrupts silently**: each
service reads the other's value with no error anywhere. Step 7 shows the empty-scan trap.

**Hints.**

- Two `spring-boot:run` invocations with different `--spring.application.name` is enough.
- `redis-cli MONITOR` in a third terminal makes the wire keys visible live, which is the clearest way
  to see what the serializer did.

**How to verify.** A test asserting two convention instances with different application names produce
different keys for identical inputs.

### Lab 3 — Advanced: staleness, stampede, and the authorization trap

**Goal.** Meet the three failure modes that make caching genuinely hard.

**Steps.**

1. Run **three** instances with `cache-caffeine` behind a round-robin. Cache a value with a 5-minute
   TTL.
2. Evict on instance 1. Call repeatedly through the balancer. How often do you get stale data, and for
   how long?
3. Switch to `cache-redis` and repeat. Explain the difference in one sentence.
4. **Stampede**: cache an entry whose computation takes 2 seconds, with a 10-second TTL. Fire 50
   concurrent requests just after expiry. How many computations run?
5. Add `@Cacheable(sync = true)`. Repeat. Better? Does it help *across* instances?
6. Design a fix that works across instances. (Hint: [Chapter 10](10-coordination.md).)
7. **The security trap**: put `@Cacheable` and `@RequiresPermission` on the same method, cache-first.
   Call as an authorized user, then as an unauthorized one. What does the second caller get?
8. Fix it two ways: include the subject in the key, and move the cache below the authorization
   boundary. Argue for one.

**Expected outcome.** Step 2 shows ~2/3 stale for up to 5 minutes. Step 4 runs ~50 computations. Step
5 reduces it to one *per instance*. **Step 7 returns the cached value to a caller who is not permitted
to see it** — reproduce it once, deliberately, and you will never write that annotation pair again.

**Hints.**

- Step 6's answer is a distributed lock around the recompute, or staggered TTLs (jitter) so entries do
  not all expire together.
- Step 8: moving the cache beneath the authorization boundary is usually right, because it keeps the
  cached layer identity-free — which is a property you can reason about, rather than a key you must
  remember to extend.

**How to verify.** A test asserting an unauthorized caller receives 403 even when the value is already
cached for someone else.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<!-- exactly one cache provider -->
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-cache-caffeine</artifactId>   <!-- or -cache-redis -->
</dependency>
<!-- only if you need DIRECT Redis access as well -->
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-redis</artifactId>
</dependency>
```

**Use it**

```java
static final String CACHE = "tariff.by-code";      // a LITERAL — CacheNames.of() is not constant
// assertThat(CACHE).isEqualTo(CacheNames.of("tariff", "by-code"));   // validate in a test

@Cacheable(CACHE) String rateFor(String code) { ... }
@CacheEvict(cacheNames = CACHE, key = "#code") void invalidate(String code) { }

convention.key("orders", customerId, "summary");   // "app:orders:42:summary"
redis.opsForValue().set("session:" + id, v, Duration.ofMinutes(30));   // wire key is prefixed
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.cache.enabled` | `true` (false = Boot's manager, **not** no caching) |
| `dc.platform.cache.caches.<name>.ttl` | — **unset means never expires** |
| `dc.platform.cache.caches.<name>.max-size` | — unset means unbounded |
| `dc.platform.redis.enabled` | `true` |
| `dc.platform.redis.key-prefix` | → `<spring.application.name>:` |

**Which capability**

| Data | Use |
|---|---|
| Derived, recomputable, losing it costs latency | **cache** |
| Primary, not derivable, losing it costs correctness | **redis** |

**Provider**

| Situation | Provider |
|---|---|
| Local and test | Caffeine — no infrastructure |
| One instance | Caffeine — faster, simpler |
| N instances needing shared invalidation | Redis |

**Diagnose it**

```bash
curl -s localhost:8080/actuator/metrics/cache.gets | jq '.availableTags'   # hit ratio
curl -s localhost:8080/actuator/metrics/cache.size | jq
redis-cli --scan --pattern 'orders-service:*' | head        # actual wire keys
redis-cli INFO keyspace ; redis-cli --bigkeys               # what is using memory
```

**Rules of thumb**

- Always set a TTL. An unset one means never expires.
- Check the hit ratio. Below ~50%, the cache may be costing more than it saves.
- Never cache a method whose result depends on the caller, unless identity is in the key.
- Never put `@Cacheable` above `@RequiresPermission` — a hit skips the check.
- Self-invocation bypasses the proxy, silently.
- Caffeine invalidation is instance-local. With N replicas, that is N caches.
- Scan patterns must include the Redis prefix. An empty result is not data loss.
- `CacheNames.of()` cannot name a cache in an annotation. Use a literal; assert it in a test.
- Never run `KEYS` in production.
- Session state does not belong in the cache manager.
- `maxmemory-policy: volatile-lru`, not `allkeys-lru`, on a shared Redis.
- Changing `key-prefix` or the key convention orphans every existing entry.

---

**Next:** [Chapter 10 — Coordination: Locking, Scheduling and Idempotency](10-coordination.md), which
is where the "N instances" problem from §2.3 gets solved properly.

**Reference:** [modules/cache.md](../../modules/cache.md) ·
[modules/redis.md](../../modules/redis.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
