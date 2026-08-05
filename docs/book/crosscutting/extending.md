# Extending the Chassis

> The customizer tier, the provider tier, TCK certification, and how to add a capability.

The platform's central promise is that you never have to fork it. This chapter is how that promise is
kept: four escalating levels of extension, when each is appropriate, and how to know you have gone one
level too far.

---

## 1. The four levels

Work down this list. Stop at the first level that solves your problem.

```
   1. CONFIGURE          a property                       no code
   2. CUSTOMIZE          a *Customizer bean               add to a default
   3. REPLACE            a bean of the platform's type    own that one thing
   4. PROVIDE            an SPI implementation + TCK      a new backend
   ---------------------------------------------------------------
   X. FORK                                                never
```

| Level | Effort | Blast radius | You now maintain |
|---|---|---|---|
| Configure | Minutes | The property | Nothing |
| Customize | An hour | What you added | Your customizer |
| Replace | A day | Everything that bean did | **Everything that bean did** |
| Provide | Days | Your backend | Your provider, against the TCK |
| Fork | Weeks | Everything | A divergent platform, forever |

!!! success "The rule that matters"
    **Each level down costs an order of magnitude more maintenance than the one above it.** Most
    perceived needs for level 3 are level 2 needs in disguise, and most perceived needs for level 4 are
    level 3.

    Before descending, write one sentence describing what the level above cannot do. If you cannot,
    you are at the wrong level.

---

## 2. Level 1 — Configure

Every capability has a kill switch and a small set of properties, all under
`dc.platform.<capability>`, all documented in the
[generated reference](../../reference/properties.md).

More usefully: the platform's opinions on **third-party** keys are contributed at *lowest precedence*,
so your `application.yml` always wins without any special mechanism.

```yaml
spring:
  jpa:
    open-in-view: true          # beats platform-data-jpa-defaults
resilience4j:
  retry:
    instances:
      orders:
        base-config: default    # inherit the platform's tuning, override one thing
        max-attempts: 5
```

```bash
curl -s localhost:8080/actuator/env/spring.jpa.open-in-view | jq   # which source won?
```

!!! warning "A kill switch stops the platform contributing — it does not turn the feature off"
    Disable `resilience` and you get Resilience4j's *library* defaults, not no resilience. Disable
    `cache` and you get Boot's `CacheManager` without the key convention. Disable `audit` and you get
    genuinely nothing, silently. Know the fallback before flipping one.

---

## 3. Level 2 — Customize

Contribute a `*Customizer` bean. The platform collects them via `ObjectProvider.orderedStream()` and
applies them in `@Order`. You **add** behaviour without owning the bean that does the rest.

| Customizer | Adjusts | Chapter |
|---|---|---|
| `ProblemDetailCustomizer` | Every outgoing error body | [2](../chapters/02-errors-validation.md) |
| `LogSanitizer` | Values on their way to a log field | [3](../chapters/03-logging-observability.md) |
| `OpenApiCustomizer` | The generated document | [4](../chapters/04-openapi.md) |
| `SecurityCustomizer` | The filter chain, **before** `anyRequest()` | [5](../chapters/05-security-authz.md) |
| `PlatformRestClientCustomizer` | A named client's builder | [6](../chapters/06-restclient-resilience.md) |
| `MeterRegistryCustomizer` | Every meter (Spring's own type) | [3](../chapters/03-logging-observability.md) |

```java
@Bean
@Order(10)
ProblemDetailCustomizer serviceNameCustomizer() {
    return (detail, source) -> detail.setProperty("service", "orders-service");
}
```

**The universal contract:** thread-safe, fast, and **must not throw**. A customizer runs on a hot path
or an error path, and a throwing one breaks the thing it was decorating — a mapped 404 becomes a 500, a
log line is lost, a builder fails for every client.

!!! success "Two customizer facts worth knowing before you write one"
    **`SecurityCustomizer` runs before `anyRequest()`** — not a style choice. Spring Security forbids
    adding matchers after a terminal one, so this is the only ordering that works, and it structurally
    prevents a customizer from issuing a blanket permit.

    **`PlatformRestClientCustomizer` runs for every client.** It receives the client name; without an
    `if`, your API key goes to every service you call.

---

## 4. Level 3 — Replace

Declare a bean of the platform's type. Its `@ConditionalOnMissingBean` sees yours and stands down.

```java
@Bean
CacheKeyConvention cacheKeyConvention() {
    return (cacheName, parts) -> "acme:" + cacheName + ":" + join(parts);
}
```

Commonly replaced, low risk:

| Replace | To |
|---|---|
| `CurrentUserAccessor` | Change identity for **five** capabilities at once |
| `AuditorAware<String>` | Record tenant/subject rather than subject |
| `CacheKeyConvention` | Match an existing key taxonomy |
| `ContentTypeValidator` | Broader detection (Tika) |
| `EventSerializer` | Avro, Protobuf, a schema registry |
| `Auditor` | Synchronous auditing |

!!! warning "Three replacements that quietly cost you more than you expect"
    **`SecurityFilterChain`** — you lose deny-by-default, the RFC-9457 auth bodies, the stateless
    posture, and the security headers. Copied from a tutorial to add one CORS rule, this is the most
    damaging accidental change in the platform. Use a `SecurityCustomizer`.

    **`OpenAPI`** — you own identity and servers. (The error customizer is a *separate* bean and keeps
    running, which is usually what you want.)

    **`EventSerializer`** — a wire-format change. Every consumer of every destination must read the new
    format. Plan it as a coordinated migration: dual-publish, migrate consumers, then switch.

!!! warning "Name-based back-off is a foot-gun worth knowing about"
    Several beans back off by **name**, not type: `platformCorrelationFilterRegistration`,
    `platformBannerRunner`, `platformProblemDetailOpenApiCustomizer`, `platformCommonTagsCustomizer`,
    `platformExceptionHandler`.

    A bean that happens to share one of those names replaces platform behaviour silently. Conversely, a
    customizer you *meant* to run alongside the platform's must use a different name.

---

## 5. Level 4 — Provide

Implement a capability's SPI and certify it against the TCK.

### The pattern, in full

```java
// 1. Implement the SPI
public class EncryptingFsObjectStore implements ObjectStore {
    private final ObjectStore delegate;
    // ... encrypt on put, decrypt on get ...
}

// 2. Contribute it, ordered BEFORE the platform's default
@AutoConfiguration(before = FsObjectStoreAutoConfiguration.class)
public class EncryptingStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore encryptingObjectStore(...) { ... }
}

// 3. Certify against the TCK — this is what makes it "platform-certified"
class EncryptingFsObjectStoreTckTest extends ObjectStoreTck {
    @Override protected ObjectStore store() { return new EncryptingFsObjectStore(...); }
}

// 4. Prove the platform's default stood down
@PlatformTest
class StorageBackOffTest {
    @Test void customProviderWins(@Autowired ObjectStore store) {
        assertThat(store).isInstanceOf(EncryptingFsObjectStore.class);
    }
}
```

Steps 3 and 4 together are what "platform-certified provider" means.
[`example-extension-provider`](../../examples.md) is exactly this, end to end — read it before writing
your own.

### The SPIs

| Capability | SPI | Status | TCK |
|---|---|---|---|
| Messaging | `EventTransport`, `EventSerializer` | EXPERIMENTAL | `EventTransportTck` |
| Storage | `ObjectStore` (the API **is** the contract), `KeyValidator` | Mixed | `ObjectStoreTck` |
| Locking | `LockProvider`, `LockHandle` | EXPERIMENTAL | `LockProviderTck` |
| Rate limiting | `RateLimiterProvider` | EXPERIMENTAL | `RateLimiterProviderTck` |
| Flags | `FlagProvider`, `FlagValue`, `EvaluationContext` | EXPERIMENTAL | `FlagProviderTck` |
| Idempotency | `IdempotencyStore` (in the api module) | STABLE | `IdempotencyStoreTck` |
| Audit | `AuditSink` | EXPERIMENTAL | `AuditSinkTck` |
| Authorization | `PermissionEvaluatorProvider` | EXPERIMENTAL | — |

!!! note "Most SPIs are EXPERIMENTAL, deliberately"
    Consumer-facing APIs are STABLE and japicmp-checked. Provider contracts evolve more freely — several
    javadocs say new `default` methods may appear in minor versions. If you write a provider, expect to
    revisit it, and let the TCK tell you when.

### The TCK is the contract

```java
class MyTransportTckTest extends EventTransportTck {
    @Override protected EventTransport transport() { return new MyTransport(...); }
}
```

**A provider is platform-certified if and only if its TCK passes.**

That is what makes an SPI real. Without it, "implements `EventTransport`" means the signatures compile —
it says nothing about whether `send` blocks until acknowledged, whether an unacknowledged message is
redelivered, or whether `close` releases its threads. Those invariants are prose in the javadoc; the TCK
is the same prose, executable.

!!! tip "Read the TCK before writing the provider"
    It is the specification. Writing the implementation first means discovering invariants by failing
    tests — which works, and is slower than reading them.

    Expect the first surprises to be around edge semantics: `delete` returning `false` for an absent
    object, `list` returning an empty stream rather than null, `tryAcquire` returning empty rather than
    blocking.

### Relaxable invariants

Some backends genuinely cannot satisfy an invariant. The TCKs handle that with a documented
`protected boolean` hook a subclass overrides — never by weakening the contract for everyone.

The worked example: `LockProviderTck` relaxes strict *concurrent* exclusion on H2, whose engine cannot
reliably serialise reclaim-then-insert across connections. Sequential exclusion and fencing still prove
mutual exclusion Docker-free; strict concurrency is certified against PostgreSQL under `@Tag("docker")`.

!!! success "The shape to copy for any guarantee you cannot fully verify"
    Name the invariant. Verify what you can, cheaply and always. Verify the rest expensively and
    explicitly. **Make the gap a named boolean someone must deliberately flip** — not a comment nobody
    reads.

---

## 6. Adding a capability to the platform

Level 5, effectively — and it means becoming a platform contributor rather than a consumer. The full
procedure is the [new-capability runbook](../../runbooks/new-capability.md); this is the shape and the
decisions.

### The module split

```
   platform-<cap>-api            consumer types      STABLE, japicmp-checked
   platform-<cap>-spi            provider contract   ONLY if a 2nd provider is plausible
   platform-<cap>-<provider>     one implementation
   platform-<cap>-autoconfigure  @AutoConfiguration, properties, conditions
   platform-starter-<cap>        POM only, NO code
```

`mvn …:new-module` scaffolds a constitution-compliant skeleton.

Simple capabilities collapse to `api + autoconfigure + starter`.

### The constitution the build enforces

```
   api      -> core-api only
   spi      -> same-capability api
   impl     -> same-capability spi/api + its own third-party library
   autoconf -> same-capability api/spi (+ optional impls, + other capabilities' API
               guarded by @ConditionalOnClass)
   starter  -> autoconfigure + named impl(s)

   FORBIDDEN: api depending downward - impl -> impl - starter -> starter
              anything -> another module's .internal - any cycle
```

A custom Maven enforcer rule plus ArchUnit in every module make a violating change **fail to build**.

!!! note "The constitution visibly shapes the code, and that is the point"
    Three examples from this book:
    [`StreamingDownloads`](../chapters/11-storage-files.md) lives in `files-autoconfigure` rather than
    the api module, because its signature needs Spring MVC. The
    [audit messaging sink](../chapters/12-audit.md) is its own module, because an impl may not reach
    another capability. [Authz](../chapters/05-security-authz.md) orders itself by class **name**
    rather than a class literal, because the literal would require a forbidden dependency.

    Each looks slightly odd until you know why. The alternative was a convenient exception — and an
    architecture with no exceptions is one you can still reason about in three years.

### The per-capability definition of done

- [ ] `api` with full javadoc: purpose, usage snippet, thread-safety, nullability, `@since`
- [ ] `spi` **only** if a second provider is genuinely plausible *and the contracts differ*
- [ ] Immutable `record` properties under `dc.platform.<cap>`, defaults in code **and** metadata
- [ ] A kill switch: `dc.platform.<cap>.enabled`, `matchIfMissing = true`
- [ ] Every bean `@ConditionalOnMissingBean`, constructor injection, no field injection
- [ ] An autoconfigure comment block listing activation conditions and back-off behaviour
- [ ] The **5-case ContextRunner matrix**: enabled by default, `enabled=false`, class missing, user-bean
      back-off, customizer ordering
- [ ] A `CapabilityDescriptor` bean, so it appears in the banner and `/actuator/platform`
- [ ] A local provider needing no infrastructure; real-infra tests `@Tag("docker")`
- [ ] A TCK if multi-provider
- [ ] A `FailureAnalyzer` for the likely misconfiguration
- [ ] `docs/modules/<cap>.md`, **in the same pull request**
- [ ] Error codes registered as `DC-<CAP>-<NNNN>`
- [ ] A starter POM with no code

!!! success "The two items teams skip, and why they matter most"
    **The ContextRunner matrix** is what proves the capability is a *default and not a cage* — the
    back-off case is the one that keeps the platform's promise.

    **The docs page in the same PR** is what stops documentation debt existing at all. A capability
    documented "next sprint" is a capability documented never.

---

## 7. Choosing a level — worked examples

| You want to… | Level | How |
|---|---|---|
| Add a `tenant` field to every error body | 2 | `ProblemDetailCustomizer` |
| Open `/webhooks/**` without auth | 2 | `SecurityCustomizer` — **not** `permit-paths`, which replaces the list |
| Redact a custom PII field from logs | 2 | `LogSanitizer` |
| Send an API key to one downstream | 2 | `PlatformRestClientCustomizer`, with a name check |
| Change the audit actor format | 3 | `AuditorAware<String>` |
| Read identity from something other than a JWT | 3 | `CurrentUserAccessor` — five capabilities follow |
| Detect more upload content types | 3 | `ContentTypeValidator` wrapping Tika |
| Use Avro on the wire | 3 | `EventSerializer` — and a coordinated migration |
| Encrypt objects at rest | 4 | `ObjectStore` + `ObjectStoreTck` |
| Use Pulsar | 4 | `EventTransport` + `EventTransportTck` |
| Authorize against an entitlement service | 3 | `PermissionEvaluatorProvider` — or compose two |
| Send audit events to a SIEM | 3 | `AuditSink` — one replaces all three shipped sinks |
| Add a whole new cross-cutting concern | 5 | The new-capability runbook |

---

## 8. When extension is the wrong answer

Three cases where the honest move is not to extend.

**The platform is wrong, not incomplete.** If a default is wrong for *everyone*, extending in your
service means every other service keeps the wrong default. Raise it with the platform team — a fix in
the platform is one change; twelve services each working around it is twelve.

**You are reimplementing a capability that exists.** If your customizer is growing into a parallel
implementation of something the platform ships, you have found either a genuine gap (talk to the
platform team) or a misunderstanding (read the chapter again). The
[conformance rules](../chapters/14-testing-dx.md) catch the most common instances of this.

**You need a different platform.** Reactive rather than servlet, for instance. The core capability is
`@ConditionalOnWebApplication(SERVLET)`, and correlation in a reactive pipeline needs the Reactor
`Context` rather than a `ThreadLocal`. That is a platform-level decision, not an extension.

!!! warning "The one thing that is never the answer"
    **Forking a platform module.** You inherit every future upgrade as a merge, the
    [dependency constitution](../../concepts/constitution.md) no longer protects you, japicmp no longer
    tells you what broke, and no TCK certifies what you now have.

    Every mechanism in this chapter exists so forking is never necessary. If you believe it is,
    that is a conversation with the platform team — and a bug report about the extension model.

---

## 9. Checklist

**Before extending**

- [ ] Is there a property? (Level 1)
- [ ] Is there a customizer? (Level 2)
- [ ] Written one sentence on what the level above cannot do?
- [ ] Checked whether the platform already ships this?

**Writing a customizer**

- [ ] Thread-safe, fast, and it **cannot throw**
- [ ] `@Order` set if ordering matters
- [ ] A **different bean name** from the platform's, if you mean to run alongside it
- [ ] Name-checked, for a `PlatformRestClientCustomizer`

**Replacing a bean**

- [ ] You accept owning everything that bean did
- [ ] Not a `SecurityFilterChain` for a small change
- [ ] If it is `EventSerializer`, a migration plan exists
- [ ] A test asserts the platform's default backed off

**Writing a provider**

- [ ] Read the SPI javadoc's implementation requirements
- [ ] **Read the TCK before implementing**
- [ ] Auto-configuration ordered `before` the platform's default
- [ ] `@ConditionalOnMissingBean` so a *third* provider could still win
- [ ] The TCK passes
- [ ] A back-off test proves the default stood down
- [ ] Any relaxed invariant is a named hook, documented

**Adding a capability**

- [ ] The [new-capability runbook](../../runbooks/new-capability.md), followed
- [ ] `mvn …:new-module` used for the skeleton
- [ ] The 5-case ContextRunner matrix
- [ ] A local provider needing no infrastructure
- [ ] `docs/modules/<cap>.md` in the same PR
- [ ] `mvn -T1 clean verify` green

---

**Next:** the appendices — the [full property reference](../appendix/a-configuration.md),
[glossary](../appendix/b-glossary.md),
[troubleshooting cookbook](../appendix/c-troubleshooting.md), and
[compatibility matrix](../appendix/d-compatibility.md).

**Related:** [Extension model](../../concepts/extension-model.md) ·
[Dependency constitution](../../concepts/constitution.md) ·
[ADR-008 — Extension via back-off](../../decisions/adr-008.md) ·
[Chapter 14 — Testing and Developer Experience](../chapters/14-testing-dx.md) ·
[example-extension-provider](../../examples.md)

[Back to the book](../index.md)
