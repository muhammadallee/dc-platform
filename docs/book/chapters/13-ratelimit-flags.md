# Chapter 13 — Runtime Controls: Rate Limiting and Feature Flags

> **Capabilities covered:** `ratelimit`, `flags`
>
> Two ways to change a running system without a deploy: throttle it, or turn part of it off.
>
> **Starters:** `platform-starter-ratelimit`, `platform-starter-flags` ·
> **Reference:** [modules/ratelimit.md](../../modules/ratelimit.md) ·
> [modules/flags.md](../../modules/flags.md)

---

## 1. Introduction and Business Value

Both capabilities exist for the same reason: **the gap between "we need to change behaviour" and "we
can deploy" is measured in hours, and incidents are measured in minutes.**

- **Rate limiting** bounds how much work a caller can ask for. Protection against abuse, runaway
  retries, and one tenant crowding out the rest.
- **Feature flags** decouple *deploying* code from *releasing* it. Ship dark, enable gradually, and
  turn it off without a rollback.

### The problem rate limiting solves

An edge gateway usually protects the perimeter. That leaves two gaps the gateway cannot see:

**Not all endpoints cost the same.** A gateway limit of 1,000 requests/minute is generous for a lookup
and catastrophic for a report that runs a 30-second aggregation. The expensive operation needs its own
limit, at the method, where the cost actually is.

**Not all callers are external.** An internal service in a retry loop
([Chapter 6](06-restclient-resilience.md) §2.3) never passes through the edge. Neither does a message
handler, a scheduled job, or a batch import.

`@RateLimited` puts the limit where the expense is, rather than where the network boundary happens to
be.

### The problem feature flags solve

Without them, releasing means deploying, so:

- **Rollback is a deploy** — minutes at best, and it reverts everything else in that build too.
- **Gradual rollout is impossible.** All users get the new path at once, or none do.
- **Long-lived branches** accumulate because unfinished work cannot be merged safely, and the merge
  gets harder every day.

With flags, code ships disabled, is enabled for a cohort, and is switched off in seconds if it
misbehaves — with no deploy and no rollback.

### The two decisions worth knowing up front

**The rate limiter fails open.** If Redis is unreachable, the request is **allowed** and a warning is
logged. That is deliberate: *a rate limiter must not become an availability incident.* §2.5 argues it,
including when it is the wrong choice.

**Flags fail safe-off.** An unknown flag is `false`; an unknown value returns your default. A typo can
never accidentally enable an unfinished code path. §2.6.

Note that these two defaults point in **opposite** directions — one prefers availability, one prefers
safety — and both are right for their own risk. That contrast is the most useful thing in this chapter.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No method-level limiting | One expensive endpoint is trivially overwhelmed by a single client or retry loop |
| No `Retry-After` | Clients retry immediately, amplifying the overload the limiter was relieving |
| A fail-closed limiter | A Redis blip becomes a total outage: every request rejected |
| No flags | Releasing means deploying; rollback means deploying; gradual rollout is impossible |
| Fail-open flags | A typo'd flag name enables incomplete code in production |
| No targeting | Flags are all-or-nothing — no percentage rollout, no per-tenant enablement |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Where to limit

```
   client
     |
     v
   [ edge gateway ]        <-- volumetric protection, per IP. Coarse
     |
     v
   [ HTTP filter ]         <-- optional; per IP or per user, whole service
     |
     v
   [ @RateLimited method ] <-- per operation, per key. Where the cost is
     |
     v
   [ the expensive work ]
```

Each layer sees something the others cannot. The gateway sees traffic volume but not cost; the filter
sees the whole service but not which operation; the annotation sees exactly one operation and the
business key it should be limited by.

!!! success "Best practice — limit at the method, and treat the filter as a blunt backstop"
    The annotation is the useful one: it knows the operation, and it can key on a tenant or user rather
    than an IP. The HTTP filter (opt-in, off by default) is coarse protection for when no upstream
    gateway provides any, and it keys by IP — which is wrong behind a NAT or a proxy, and right at a
    genuine edge.

### 2.2 The window, and what the two providers actually count

| | In-memory (Caffeine) | Redis |
|---|---|---|
| Algorithm | **Sliding** window | **Fixed** window (`INCR` + `PEXPIRE`, atomic via Lua) |
| Scope | **Per JVM** | **Cluster-wide** |
| N instances | Effective limit is N × configured | The limit you configured |
| Failure mode | None — it is local | **Fails open** |

The fixed-window boundary effect is worth knowing: with a 100/minute limit, a client can send 100 at
12:00:59 and 100 more at 12:01:00 — 200 in two seconds, both windows technically respected. A sliding
window does not have that edge, which is why the in-memory provider uses one.

!!! warning "The in-memory provider multiplies your limit by the replica count"
    Three replicas, `permits = 100`, and the effective cluster limit is 300 — because each JVM counts
    independently. The platform **logs a WARN in the `prod` profile** saying exactly this, because the
    failure is otherwise silent: your quota is 3× more permissive than you configured, and nothing
    fails.

    For a genuine cluster-wide limit you need the Redis provider.

### 2.3 `Retry-After` is not a courtesy

Every 429 the platform produces carries a `Retry-After` computed from the window.

Without it, a well-behaved client has no information and retries immediately. Every rejected request
becomes another request, so the limiter *increases* load on the system it is protecting — the overload
sustains itself.

```
   Without Retry-After:   reject -> immediate retry -> reject -> ...    (a hot loop)
   With Retry-After:      reject + "wait 30s"       -> client backs off  (load drops)
```

`Decision.retryAfter()` is `Duration.ZERO` when allowed and never null, so a caller can use it
unconditionally.

### 2.4 Why 429 is not a `BusinessException`

[Chapter 2](02-errors-validation.md)'s `HttpStatusHint` enum has four values — 400, 404, 409, 422 —
and no 429. So `RateLimitExceededException` is **not** a `BusinessException`. Instead:

- A dedicated MVC advice maps it to 429 with a `Retry-After` header.
- The **filter** writes its own 429, because it runs before `DispatcherServlet` and no advice can reach
  it — the same structural fact as [security](05-security-authz.md)'s 401/403.

The platform could have stretched the hint enum to include 429. It did not, and the reasoning is worth
adopting: **429 is not a business-rule violation.** It is a capacity signal, semantically different
from "the domain said no", and conflating them would make the taxonomy less meaningful to keep one
enum tidy.

The cost is one extra advice and one extra place that writes a 429. The benefit is a taxonomy that
still means something.

### 2.5 Fail-open, and when it is wrong

```java
} catch (DataAccessException e) {
    log.warn("[DC-RATELIMIT-0500] rate-limit check failed against Redis for key={}; allowing", key, e);
    return Decision.allow();
}
```

Redis is unreachable → the request is allowed.

**For:** the limiter exists to protect against overload. If it fails closed, a Redis blip rejects
*every* request — the limiter becomes a single point of failure for a service that would otherwise be
fine. Availability of the business function outranks strict enforcement of a quota.

**Against:** during a Redis outage you have no protection at all. An attacker who can cause the outage
has also disabled your throttling.

The platform chose availability and **says so, loudly** — a warning per failure with a dedicated error
code.

!!! warning "Note the contrast with authorization"
    [Chapter 5](05-security-authz.md) §4.7 raised the same question for a remote
    `PermissionEvaluatorProvider` and pointed the other way: authorization should generally fail
    **closed**, because permitting an unauthorised action is worse than rejecting a legitimate one.

    Rate limiting fails **open** because exceeding a quota is a cost problem, and rejecting everything
    is an outage. The direction depends entirely on what a wrong answer costs — and this is the
    clearest pair of examples in the book. When you build your own control, ask that question first.

!!! success "Best practice — alert on `DC-RATELIMIT-0500`"
    Fail-open is silent by design: requests succeed. The warning is the only signal that your limits
    are not being enforced, so it needs an alert rather than a dashboard.

### 2.6 Fail-safe-off, the opposite default

```java
featureFlags.enabled("new-clerance-flow")     // typo -> false. The old path runs.
featureFlags.value("import.batch-size", 100)  // unknown -> 100
```

An unknown flag is off. An unknown or untypable value returns *your* default.

The failure this prevents is specific and severe: a typo'd flag name that returned `true` would enable
an unfinished code path in production, silently, with no deploy involved. Under fail-safe-off, a typo
means the feature never turns on — which you notice immediately, in testing, in the safest possible
way.

!!! warning "The corollary: a flag that will not turn on is usually a typo"
    "I enabled the flag and nothing happened" is almost always a name mismatch between the
    configuration and the call site. Because unknown flags are silently off, nothing tells you. Declare
    flag names as constants and reference them from both sides.

### 2.7 `@FeatureGate` and the neutral return

```java
@FeatureGate("new-clearance-flow")
public Receipt clearViaNewFlow(Declaration d) { ... }
```

When the flag is off, the method is **skipped** and a neutral value returned:

| Return type | Skipped value |
|---|---|
| `boolean` / `Boolean` | `false` |
| `Optional` | `Optional.empty()` |
| Any other reference type | **`null`** |
| `void` | nothing happens |
| Primitive number | zero; `char` → `'\0'` |

This removes the `if (flags.enabled(...))` wrapper around whole methods, and — more valuably — makes
the flag **visible in the signature** at cleanup time, so finding every gated method is a grep rather
than an audit.

!!! warning "The `null` case is the sharp edge"
    A gated method returning a reference type gives the caller `null` when the flag is off. If the
    caller does not expect that, you have converted a feature flag into an NPE.

    Design gated methods to return `Optional` or a boolean, so the neutral value is a legitimate
    outcome the caller already handles. The annotation's own javadoc says as much: *a gated method
    whose skip value cannot be expressed should return a reference or `Optional` type instead.*

### 2.8 Targeting

When [security](05-security-authz.md) is present, the platform resolves the current subject and tenant
into the provider's evaluation context.

That is what makes anything beyond on/off possible: percentage rollouts need a stable identity to hash,
and per-tenant enablement needs the tenant. Without identity in the context, a flag is all-or-nothing —
and "10% of users" silently becomes "10% of *requests*", which is a different and much worse thing,
because the same user flips between old and new paths on consecutive clicks.

Same optional-enrichment seam as [audit](12-audit.md) and [data](08-data.md): a flags-only service
works fine and simply has no identity to target on.

---

## 3. Feature Reference

### 3.1 Rate limiting — public API

Package `ae.gov.dubaicustoms.platform.ratelimit`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `@RateLimited` | annotation | `name()`, `permits() default 100`, `window() default "PT1M"`, `keyExpression() default ""` |
| `RateLimiter` | interface | `Decision tryAcquire(String key, int permits, Duration window)` |
| `Decision` | record `(boolean allowed, Duration retryAfter)` | `Decision.allow()`, `Decision.deny(Duration)` |
| `RateLimitExceededException` | exception | Mapped to 429 by a dedicated advice |

An **empty `keyExpression` means one global bucket** for the method, shared by all callers — sometimes
what you want (protecting a shared downstream), usually not.

`Decision.retryAfter()` is never null; it is `Duration.ZERO` when allowed.

### 3.2 Rate limiting — SPI

| Type | Status | Purpose |
|---|---|---|
| `RateLimiterProvider` | **EXPERIMENTAL** | The counting backend |

### 3.3 Flags — public API and SPI

| Type | Module | Status | Purpose |
|---|---|---|---|
| `FeatureFlags` | flags-api | STABLE | `boolean enabled(String)`, `<T> T value(String, T defaultValue)` |
| `@FeatureGate` | flags-api | STABLE | `String value()` — the flag key |
| `FlagProvider` | flags-**spi** | EXPERIMENTAL | The evaluation backend |
| `FlagValue` | flags-spi | EXPERIMENTAL | A resolved value |
| `EvaluationContext` | flags-spi | EXPERIMENTAL | Subject, tenant, attributes |

`value` infers its type from `defaultValue` and coerces — `value("batch-size", 100)` returns an `int`,
falling back to `100` if the flag is unknown **or its value cannot be coerced**.

!!! note "Coercion failure is indistinguishable from an unknown flag"
    Both return your default. A flag configured as `"abc"` and read with an `int` default silently
    yields the default — the misconfiguration is invisible. If a flag's value is load-bearing, assert
    it at startup rather than trusting the read.

### 3.4 Providers

| Capability | Default | Alternative | Selected by |
|---|---|---|---|
| Rate limiting | In-memory Caffeine sliding window | Redis fixed window | A `StringRedisTemplate` being present |
| Flags | In-memory, seeded from properties | OpenFeature adapter | An OpenFeature `Client` bean being present |

The OpenFeature adapter is how LaunchDarkly, Flagsmith, and similar plug in — a standards-based
integration rather than a bespoke adapter per vendor. Adopting one is a bean, not a call-site change.

### 3.5 The `platformflags` actuator endpoint

`/actuator/platformflags` — backed by the in-memory provider:

| Operation | Does |
|---|---|
| `GET` | Lists current flags |
| `POST` | Sets a flag |
| `DELETE` | Removes a flag |

!!! warning "This endpoint **writes**, and the platform documents that deliberately"
    Anyone who can reach it can change application behaviour at runtime. It is genuinely useful for
    local demos and tests, and it is an authenticated endpoint under the platform's default security
    chain ([Chapter 5](05-security-authz.md) §2.4 — only health and info are permitted).

    Before deploying: decide whether it should be exposed at all, and if so, who can reach it. It is
    not exposed by default in the platform's actuator include list — check
    `management.endpoints.web.exposure.include` before assuming either way.

### 3.6 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

**Rate limiting**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.ratelimit.enabled` | Boolean | `true` | Kill switch | To confirm a bug is a limiter bug |
| `dc.platform.ratelimit.http.enabled` | Boolean | **`false`** | Install the HTTP filter | When no upstream gateway limits traffic |
| `dc.platform.ratelimit.http.key-by` | `IP` \| `USER` | `IP` | Filter bucket dimension | `USER` when callers are authenticated — §6.3 |
| `dc.platform.ratelimit.http.permits` | Integer | `100` | Permits per window per key | To match capacity |
| `dc.platform.ratelimit.http.window` | Duration | `PT1M` | Window length | To match capacity |

Per-method limits live on the annotation, because they are per-operation decisions — the same reasoning
as [`@LockedSchedule`](10-coordination.md) §6.6.

**Flags**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.flags.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.flags.static.<flag>` | Map&lt;String,String&gt; | — | Seed flags for the in-memory provider | Local, test, and simple production cases |

### 3.7 Auto-configuration

| Class | Activates when | Contributes |
|---|---|---|
| `PlatformRateLimitAutoConfiguration` | Ratelimit API on classpath, enabled | `RateLimiter`, the `@RateLimited` advisor, the 429 advice, metrics, and — if `http.enabled` — the filter |
| `InMemoryRateLimiterAutoConfiguration` | No Redis template | Caffeine sliding-window provider |
| `RedisRateLimiterAutoConfiguration` | A `StringRedisTemplate` is present | Redis fixed-window provider |
| `PlatformFlagsAutoConfiguration` | Flags API on classpath, enabled | `FeatureFlags`, the `@FeatureGate` advisor, the actuator endpoint |
| `InMemoryFlagProviderAutoConfiguration` | No OpenFeature client | In-memory provider seeded from `flags.static.*` |
| `OpenFeatureFlagProviderAutoConfiguration` | An OpenFeature `Client` bean | The adapter |
| `FlagsSecurityAutoConfiguration` | `CurrentUserAccessor` on classpath | Subject/tenant in the evaluation context |

### 3.8 Extension points

| Extension | How | Effect |
|---|---|---|
| A limiter backend the platform does not ship | A `RateLimiterProvider` bean | Certify against the ratelimit TCK |
| A commercial flag service | An OpenFeature `Client` bean | The adapter is selected automatically |
| A bespoke flag source | A `FlagProvider` bean | Certify against the flags TCK |
| Change filter behaviour | `http.key-by`, or replace the filter | No code for the former |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-ratelimit</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-flags</artifactId>
</dependency>
```

### 4.2 Limit an expensive operation

```java snippet:book-13-rate-limited
@Service
class ReportService {

    // Per-tenant: each tenant gets its own bucket, so one cannot crowd out the rest.
    @RateLimited(name = "report.generate", permits = 5, window = "PT1M",
                 keyExpression = "#tenantId")
    public String generate(String tenantId, String reportType) {
        return "report-for-" + tenantId + "-" + reportType;
    }
}
```

Over the limit → `RateLimitExceededException` → 429 with `Retry-After`.

!!! warning "An empty `keyExpression` is one global bucket, not per-caller"
    `@RateLimited(name = "report.generate", permits = 5)` limits the method to five invocations per
    minute **across all callers** — one busy tenant consumes the whole budget. That is occasionally
    what you want (protecting a fragile downstream), and usually a bug. Key on something.

### 4.3 Limit programmatically

```java snippet:book-13-rate-limiter
@Service
class ExportService {

    private final RateLimiter rateLimiter;

    ExportService(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    void export(String tenantId, int tier) {
        // Dynamic quota by tier — an annotation cannot express this.
        int permits = tier == 1 ? 1000 : 100;
        Decision decision = rateLimiter.tryAcquire("export:" + tenantId, permits, Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw new RateLimitExceededException("export quota exceeded", decision.retryAfter());
        }
        doExport(tenantId);
    }

    private void doExport(String tenantId) {
        // ...
    }
}
```

### 4.4 Turn on the edge filter

```yaml
dc:
  platform:
    ratelimit:
      http:
        enabled: true
        key-by: USER      # IP is the default
        permits: 100
        window: PT1M
```

!!! warning "`key-by: IP` behind a proxy limits the proxy, not the client"
    Every request appears to come from the load balancer's address, so all your users share one bucket
    and the first hundred exhaust it. If you use `IP`, make sure `X-Forwarded-For` is being honoured by
    the container. `USER` avoids the problem entirely when callers are authenticated.

### 4.5 Use a flag

```java snippet:book-13-feature-flags
@Service
class ClearanceService {

    // Declare flag names once — a typo silently means "off", forever.
    static final String NEW_FLOW = "new-clearance-flow";

    private final FeatureFlags flags;

    ClearanceService(FeatureFlags flags) {
        this.flags = flags;
    }

    String clear(String declarationId) {
        if (flags.enabled(NEW_FLOW)) {
            return "new:" + declarationId;
        }
        return "legacy:" + declarationId;
    }

    int batchSize() {
        // Unknown flag, or a value that will not coerce -> 100.
        return flags.value("import.batch-size", 100);
    }
}
```

```yaml
dc:
  platform:
    flags:
      static:
        new-clearance-flow: "true"
        import.batch-size: "500"
```

### 4.6 Gate a whole method

```java snippet:book-13-feature-gate
@Service
class ClearanceGateway {

    // Optional return: the skip value (empty) is an outcome the caller already handles,
    // rather than a null that becomes an NPE.
    @FeatureGate("new-clearance-flow")
    public Optional<String> clearViaNewFlow(String declarationId) {
        return Optional.of("cleared:" + declarationId);
    }
}
```

!!! success "Best practice — gated methods should return `Optional`, `boolean`, or `void`"
    Then the off-state is a value the caller already handles. A gated method returning a bare object
    hands back `null`, and the flag becomes an NPE waiting for the day someone turns it off.

### 4.7 Flip a flag at runtime

```bash
curl -s localhost:8080/actuator/platformflags | jq
curl -X POST localhost:8080/actuator/platformflags/new-clearance-flow -d 'true'
curl -X DELETE localhost:8080/actuator/platformflags/new-clearance-flow
```

!!! warning "In-memory flags are per-instance and lost on restart"
    Flipping through the endpoint changes **that instance only**, and the change disappears on restart.
    That is fine for a local demo and wrong for production, where you want an OpenFeature-backed
    provider so a change applies fleet-wide and persists.

### 4.8 Plug in a commercial flag service

```java
@Bean
Client openFeatureClient() {
    OpenFeatureAPI.getInstance().setProvider(new LaunchDarklyProvider(sdkKey));
    return OpenFeatureAPI.getInstance().getClient();
}
```

The in-memory provider backs off. **No call site changes** — every `flags.enabled(...)` and
`@FeatureGate` now evaluates against the service, with the current subject and tenant already in the
evaluation context.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | In-memory both | No infrastructure; `flags.static.*` seeds what you need |
| **Production, >1 replica** | Redis limiter | Otherwise the effective limit is N× — §2.2 |
| Production | OpenFeature-backed flags | Fleet-wide, persistent, targetable |
| Production | Limits derived from measured capacity | Not from a round number |
| Production | Decide on `platformflags` exposure | It writes — §3.5 |
| Behind a proxy | `key-by: USER`, or honour `X-Forwarded-For` | §4.4 |

!!! warning "The in-memory limiter's `prod`-profile WARN is telling you something specific"
    It fires because the platform can detect the likely mistake but cannot fix it. If you see it and
    are running more than one replica, your quotas are silently more permissive than configured.

### 5.2 What to monitor

| Signal | Where | Alert when |
|---|---|---|
| Throttle rate | `dc.platform.ratelimit.decisions{name, outcome}` | A sustained rise — abuse, a retry storm, or a limit set too low |
| **`DC-RATELIMIT-0500`** | Logs | **Ever.** Fail-open means limits are not being enforced right now |
| 429 rate | HTTP metrics | Correlate with the throttle metric to see which limit fired |
| Flag evaluation | Provider-specific | A flag flipping unexpectedly |
| Flag staleness | OpenFeature provider health | The provider is unreachable — you are serving defaults |

!!! tip "Rising 429s are ambiguous, and the tag tells you which"
    Legitimate growth, a misbehaving client, and a limit set too low all look the same in an HTTP 5xx/4xx
    chart. The `name` tag on `dc.platform.ratelimit.decisions` names the *bucket*, so you can tell
    "everyone is hitting the report limit" from "one tenant is hammering exports".

!!! warning "`DC-RATELIMIT-0500` is invisible without an alert"
    Fail-open produces successful requests. Nothing degrades, no metric moves in a worrying direction —
    you simply have no rate limiting. It is exactly the shape of problem that needs an alert rather
    than a dashboard.

### 5.3 Troubleshooting

**The limit is not being enforced.**

| Cause | Check |
|---|---|
| Self-invocation | `this.method()` bypasses the proxy — as everywhere |
| Redis unreachable, failing open | `DC-RATELIMIT-0500` in the logs |
| In-memory provider with N replicas | Effective limit is N× — §2.2 |
| Capability disabled | `dc.platform.ratelimit.enabled` |
| Non-public method | Cannot be advised |

**Everything is being throttled.** Check the key expression — a key that evaluates to the same value
for every caller (or to `null`) collapses all traffic into one bucket. Log the derived key.

**429 with no `Retry-After`.** Something replaced the advice, or the rejection is coming from an
upstream gateway rather than the platform.

**A flag will not turn on.**

| Cause | Check |
|---|---|
| Name mismatch | The overwhelmingly common cause. Unknown flags are silently `false` |
| Wrong provider active | An OpenFeature client present means `flags.static.*` is ignored |
| Value not coercible | `value("x", 100)` with a non-numeric flag returns 100 silently |
| Flipped on one instance only | The endpoint changes that JVM only — §4.7 |
| Self-invocation | `@FeatureGate` is proxy-based too |

**A gated method returns null unexpectedly.** The flag is off and the return type is a reference type.
§2.7 — this is the documented neutral value, not a bug.

**Percentage rollout keeps flipping for the same user.** Identity is not reaching the evaluation
context, so the provider is hashing something per-request. Confirm the security capability is present
(§2.8).

### 5.4 Scaling and performance

- **In-memory limiting is nanoseconds** — a local Caffeine lookup, no network.
- **Redis limiting is one round trip per check**, on the request path. Usually sub-millisecond, but it
  is a network dependency on every limited call.
- **`@RateLimited` on a hot path adds that round trip to every request**, including the ones that are
  allowed. Limit expensive operations, not cheap ones.
- **Flag evaluation should be a local lookup.** The in-memory provider is; a remote OpenFeature
  provider without local caching would put a network call on every `enabled()` check. Most SDKs stream
  and cache locally — confirm yours does.
- **Fixed-window counting is one `INCR` plus one `PEXPIRE`**, done atomically in Lua so it is a single
  round trip.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| **Fail-open is a known, accepted gap** | An attacker who can disrupt Redis also disables your throttling |
| `key-by: IP` is spoofable | `X-Forwarded-For` is client-supplied unless the proxy overwrites it. Trust it only from a proxy you control |
| Rate limits leak information | Different limits per tier let a caller infer their tier. Minor, worth knowing |
| **The `platformflags` endpoint writes** | Anyone reaching it changes behaviour at runtime — §3.5 |
| Flags can disable security controls | A flag gating an authorization check is a permission bypass with a config toggle |
| Flag values may be sensitive | A flag named `use-new-payment-provider` leaks a roadmap in a `GET` response |
| Per-user limits need real identity | `key-by: USER` is only as trustworthy as authentication |

!!! warning "Never gate a security control behind a feature flag"
    ```java
    if (flags.enabled("enforce-permissions")) {      // do not do this
        checkPermission(user, "orders:write");
    }
    ```
    You have created a permission bypass that anyone with flag access can trigger — no deploy, no code
    review, no audit trail beyond the flag change. Security controls are not features. If you need to
    roll out a *new* control gradually, gate the **new** check while the old one stays unconditional.

---

## 6. Deep Dive

### 6.1 Sliding versus fixed windows

**In-memory: sliding.** Tracks timestamps and counts what falls inside the trailing window. Accurate,
no boundary effect, costs memory proportional to the permit count per key.

**Redis: fixed.** `INCR` a key, `PEXPIRE` it on creation, compare to the limit. Two commands, one
counter per key per window — extremely cheap, and it has the boundary effect from §2.2.

Why the asymmetry? A sliding window in Redis needs a sorted set per key and a range trim on every
check: several commands, more memory, and a larger script. The platform chose the cheap distributed
option and the accurate local one, matching each algorithm to what its store is good at.

!!! note "The boundary effect matters less than it looks"
    A client can burst 2× the limit across a window boundary. For quota enforcement over minutes that
    is noise. For protecting a fragile downstream from a precise burst, use a smaller window — 20 per
    10 seconds rather than 120 per minute — which bounds the burst proportionally.

### 6.2 The two 429 paths

```
   filter rejects   ->  writes its own 429 + Retry-After + RFC-9457 body
                        (runs BEFORE DispatcherServlet — no advice can reach it)

   annotation rejects  ->  RateLimitExceededException
                        ->  RateLimitExceptionAdvice -> 429 + Retry-After
```

Two code paths producing the same response. That duplication is not an oversight — it is the same
structural constraint that forces [security](05-security-authz.md) to install an
`AuthenticationEntryPoint`: anything rejecting before `DispatcherServlet` must write its own response.

Recognising this pattern is worth more than the specific instance. **Any platform control that runs as
a filter must duplicate the error-rendering that `@RestControllerAdvice` normally provides**, or its
responses will not match the rest of the API.

### 6.3 Why the filter defaults to `IP`

`key-by: IP` works without authentication, which makes it the only sensible default: the filter must
function for anonymous traffic, which is exactly the traffic most likely to need throttling.

`USER` is better *when available* — it survives NAT, it survives a client changing IP, and it maps to
something meaningful. But it requires the security capability and an authenticated request, and it
cannot limit the unauthenticated traffic hitting your login endpoint.

!!! success "Best practice — use both layers"
    `IP` at the filter for unauthenticated volumetric protection, and `@RateLimited` keyed on the user
    or tenant at the methods that matter. They protect against different things, and neither substitutes
    for the other.

### 6.4 Why `value()` infers its type from the default

```java
<T> T value(String flag, T defaultValue);
```

Not `<T> T value(String flag, Class<T> type, T defaultValue)`.

The default value carries the type, so the call site is `value("batch-size", 100)` rather than
`value("batch-size", Integer.class, 100)`. Less ceremony, and — more importantly — **a default is
mandatory**. There is no `value(flag)` overload returning null or throwing, so every call site has
already decided what happens when the flag is unknown.

The cost is that coercion failure and unknown-flag are indistinguishable (§3.3). That is a real
trade-off: the API optimises for the common case being safe over the rare case being diagnosable.

### 6.5 Why `@FeatureGate` returns neutral values rather than throwing

An alternative design would throw when the flag is off, forcing the caller to handle it. The platform
returns a neutral value instead.

Throwing would make every gated call site need a `try`/`catch`, which is worse than the
`if (flags.enabled(...))` the annotation exists to remove. And an exception for an *expected* state —
the flag being off is the normal case before rollout — is a misuse of exceptions.

The `null` sharp edge (§2.7) is the price. The javadoc's guidance — return `Optional` or a boolean —
resolves it, and is worth treating as a rule rather than a suggestion.

### 6.6 Flag lifecycle, and the debt nobody schedules

Flags are technical debt with a scheduled repayment date that nobody schedules.

```
   1. Add the flag, default off
   2. Deploy dark
   3. Enable for a cohort
   4. Enable everywhere
   5. *** REMOVE THE FLAG AND THE OLD PATH ***
```

Step 5 is skipped roughly always. The result is a codebase of permanently-on flags, each with a dead
branch behind it, each an `if` a reader must evaluate, and each a switch someone might flip in three
years having forgotten what the other path does.

!!! success "Best practice — give every flag an expiry when you create it"
    Put the removal date in the flag name or in a comment at the declaration, and treat an expired flag
    as a build warning or a backlog item. `@FeatureGate` helps here: gated methods are greppable, so
    "find every flag in this codebase" is one search rather than an audit of every `if`.

    A flag that has been permanently on for a year is not a flag. It is a dead branch with extra steps.

### 6.7 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| In-memory limiter across N replicas | It works in development | Effective limit is N×. The `prod` WARN says so |
| Empty `keyExpression` | It is the default | One global bucket; one caller drains it |
| `key-by: IP` behind a proxy | It is the default | Everyone shares the load balancer's bucket |
| Not alerting on `DC-RATELIMIT-0500` | Fail-open produces successes | You have no limiting and no signal |
| Typo'd flag name | Unknown flags are silently off | Declare names as constants |
| Gated method returning a bare object | It is the natural signature | `null` on the off path. Use `Optional` |
| Flipping flags via the endpoint in production | It is convenient | Per-instance, lost on restart |
| Gating a security control | It looks like a gradual rollout | A permission bypass with a config toggle |
| Never removing flags | Nothing forces it | Permanent dead branches |
| Rate-limiting a cheap method with Redis | It seems prudent | A network round trip on every call |
| Assuming a coercion failure is visible | It returns the default like an unknown flag | Assert load-bearing values at startup |

---

## 7. Exercises and Hands-on Labs

Starting point: a service from the [archetype](../../quickstart.md) with both starters. Run **two**
instances for the labs that need them.

### Lab 1 — Basic: limit, reject, and back off

**Goal.** See the limiter fire and the `Retry-After` contract work.

**Steps.**

1. Add `@RateLimited(name = "demo", permits = 3, window = "PT10S", keyExpression = "#user")`.
2. Call four times as user A. What status on the fourth, and what headers?
3. Immediately call as user B. Allowed or rejected? Why?
4. Wait for the window and retry as A.
5. Remove `keyExpression`. Repeat steps 2–3. What changed for user B?
6. Check `dc.platform.ratelimit.decisions` — what tags, what values?

**Expected outcome.** Step 2 gives 429 with `Retry-After` and an RFC-9457 body
([Chapter 2](02-errors-validation.md)). Step 3 is allowed — separate buckets. **Step 5 rejects user B
too**, because one global bucket is now shared. That contrast is the lesson.

**Hints.**

- `curl -i` to see `Retry-After`.
- Log the derived key to confirm the SpEL produced what you expected.

**How to verify.** A test asserting the fourth call throws `RateLimitExceededException` with a
non-zero `retryAfter`, and that a different key is unaffected.

### Lab 2 — Intermediate: flags, and the two ways they fail quietly

**Goal.** Use both APIs, and reproduce both silent failures.

**Steps.**

1. Seed `dc.platform.flags.static.my-feature: "true"` and branch on `flags.enabled("my-feature")`.
2. Flip it to `false` and restart. Confirm the other branch runs.
3. **Typo the flag name** at the call site. What happens? Is there any signal at all?
4. Read `flags.value("batch-size", 100)` with `batch-size: "abc"` configured. What do you get, and how
   would you have known?
5. Add `@FeatureGate` to a method returning a bare object. Turn the flag off and call it. What does the
   caller receive?
6. Change the return type to `Optional`. Repeat. Better?
7. Flip a flag through `/actuator/platformflags` on **one** of two instances. Call repeatedly through
   both. What do you observe?

**Expected outcome.** Step 3 silently takes the off path — no warning anywhere, which is fail-safe-off
working as designed and also the most common flag bug. Step 4 returns `100` indistinguishably from an
unknown flag. **Step 5 returns `null`**; step 6 returns `Optional.empty()`, which the caller handles.
Step 7 shows per-instance divergence.

**Hints.**

- For step 4, the only way to notice is to assert the value at startup.
- Step 7 is why production wants an OpenFeature-backed provider.

**How to verify.** A test asserting an unknown flag is `false` and an uncoercible value returns the
default — locking in the fail-safe semantics.

### Lab 3 — Advanced: multiply your own limit, then fail open on purpose

**Goal.** Reproduce both provider gaps and reason about the trade.

**Steps.**

1. Run **two** instances with the in-memory limiter, `permits = 5`, behind a round-robin.
2. Send 10 requests for one key. How many succeed? What did you configure?
3. Find the `prod`-profile WARN. Does it say what you just observed?
4. Switch to the Redis provider. Repeat step 2. Now how many?
5. **Stop Redis** and repeat. How many succeed? What appears in the logs?
6. Restart Redis. Confirm limiting resumes.
7. Write the paragraph you would give a security reviewer describing the fail-open behaviour and its
   risk.
8. Now build the opposite: a `RateLimiterProvider` that fails **closed**. Run step 5 again. Which
   behaviour do you want for *your* service, and why?
9. Explore the boundary effect: with a 10-second Redis window, send the full quota at second 9 and
   again at second 11. How many did you get through in two seconds?

**Expected outcome.** Step 2 allows **10**, not 5 — the N× multiplication, reproduced. Step 4 allows 5.
**Step 5 allows all 10** with `DC-RATELIMIT-0500` warnings — the fail-open trade, made concrete. Step 9
shows 2× the quota across the boundary.

**Hints.**

- Step 7 should mention that an attacker who can disrupt Redis also disables throttling.
- Step 8 has no universally right answer — that is the exercise. Compare with
  [authorization](05-security-authz.md) §4.7, which points the other way.

**How to verify.** A test with a stubbed provider throwing on every check, asserting requests are still
allowed — locking in fail-open as intentional rather than accidental.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-ratelimit</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-flags</artifactId>
</dependency>
```

**Use them**

```java
@RateLimited(name = "report.generate", permits = 5, window = "PT1M", keyExpression = "#tenantId")
Decision d = rateLimiter.tryAcquire("export:" + tenantId, 100, Duration.ofMinutes(1));
d.allowed(); d.retryAfter();          // retryAfter is ZERO when allowed, never null

flags.enabled("new-clearance-flow");  // unknown -> false
flags.value("batch-size", 100);       // unknown or uncoercible -> 100

@FeatureGate("new-clearance-flow")
Optional<Receipt> newFlow(...)        // Optional/boolean/void — NOT a bare object
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.ratelimit.http.enabled` | **`false`** — opt-in |
| `dc.platform.ratelimit.http.key-by` | `IP` (`USER` when authenticated) |
| `dc.platform.ratelimit.http.permits` / `.window` | `100` / `PT1M` |
| `dc.platform.flags.static.<flag>` | — seeds the in-memory provider |

**Failure directions — the pair worth remembering**

| Control | On failure | Because |
|---|---|---|
| **Rate limiter** | **Allows** (fail-open) | A limiter must not become an availability incident |
| **Feature flag** | **Off** (fail-safe) | A typo must never enable unfinished code |
| Authorization ([Ch. 5](05-security-authz.md)) | Should fail **closed** | Permitting the unauthorised is worse than rejecting the legitimate |

**Providers**

| | In-memory | Redis / OpenFeature |
|---|---|---|
| Rate limit | Sliding, **per JVM** — N replicas = N× the limit | Fixed window, cluster-wide, fails open |
| Flags | Per JVM, from properties, lost on restart | Fleet-wide, persistent, targetable |

**Diagnose it**

```bash
curl -i localhost:8080/limited                                     # 429 + Retry-After?
curl -s localhost:8080/actuator/metrics/dc.platform.ratelimit.decisions | jq '.availableTags'
grep "DC-RATELIMIT-0500" app.log                                   # failing open RIGHT NOW
curl -s localhost:8080/actuator/platformflags | jq                 # current flags (if exposed)
```

**Rules of thumb**

- Always set `keyExpression` — the default is one global bucket.
- The in-memory limiter multiplies your limit by the replica count. Use Redis for a real limit.
- Alert on `DC-RATELIMIT-0500`. Fail-open is silent by construction.
- `key-by: IP` behind a proxy limits the proxy. Use `USER`, or honour `X-Forwarded-For`.
- Declare flag names as constants — an unknown flag is silently off.
- Gated methods return `Optional`, `boolean`, or `void`. Never a bare object.
- **Never gate a security control behind a flag.**
- The `platformflags` endpoint writes. Decide who can reach it before deploying.
- In-memory flag flips are per-instance and lost on restart.
- Give every flag an expiry date when you create it. Step 5 is the one everyone skips.

---

**Next:** [Chapter 14 — Testing and Developer Experience](14-testing-dx.md), which is how you prove any
of the last thirteen chapters actually works in your service.

**Reference:** [modules/ratelimit.md](../../modules/ratelimit.md) ·
[modules/flags.md](../../modules/flags.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
