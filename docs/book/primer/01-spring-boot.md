# Primer 1 — Spring Boot Foundations

> Everything the rest of this book assumes about Spring Boot: the application context,
> auto-configuration, conditions, starters, configuration properties, profiles, and Actuator.

This chapter exists because the platform is *built entirely out of standard Spring Boot mechanisms*.
There is no platform runtime, no registry, no custom lifecycle, no proprietary dependency-injection
container. That is a deliberate architectural decision ([ADR-004](../../decisions/adr-004.md)), and it
has a direct consequence for you: **once you understand these seven mechanisms, you understand how the
entire chassis works, and you debug it with the same tools you would use on any Boot application.**

If you already know Boot well, skim the callouts — they mark where the platform makes a specific
choice — and move on to [Primer 2](02-microservices.md).

!!! note "You do not need Spring Cloud"
    The platform deliberately does not use Spring Cloud Config, Netflix Eureka, or Spring Cloud
    Gateway. Where an industry problem is solved by Spring Cloud in other stacks (configuration,
    resilience, tracing), this book explains what the platform does instead, and why.

---

## 1. The application context, beans, and injection

A Spring application is a bag of objects — **beans** — that Spring creates, wires together, and
manages. The bag is the **application context**. You almost never create it yourself;
`SpringApplication.run()` does.

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

Beans get into the context two ways:

**Component scanning.** `@SpringBootApplication` implies `@ComponentScan` of the package it lives in
and everything below it. Any class annotated `@Component`, `@Service`, `@Repository`, `@Controller`,
or `@RestController` in that subtree becomes a bean.

**Explicit declaration.** A `@Configuration` class with `@Bean` methods. The method's return value is
the bean; the method name is the bean name.

```java
@Configuration
class ClockConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

Beans that need other beans declare them as **constructor parameters**. Spring resolves them by type.

```java
@Service
class OrderService {
    private final OrderRepository repository;
    private final Clock clock;

    // No @Autowired needed: a single constructor is implicitly autowired.
    OrderService(OrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }
}
```

!!! success "Best practice — constructor injection, always"
    Field injection (`@Autowired` on a field) hides the dependency from the compiler, lets an object
    exist in a half-built state, and makes the class impossible to instantiate in a plain unit test.
    Every platform bean uses constructor injection, and the platform's
    [conformance rules](../chapters/14-testing-dx.md) fail your build if yours do not.

!!! note "Why the platform never component-scans its own packages"
    Platform beans are declared explicitly in auto-configuration classes, never picked up by scanning.
    If the platform scanned `ae.gov.dubaicustoms.platform.*`, adding a jar to your classpath would
    silently add beans to your context — with no condition, no ordering, and no way to opt out. See
    [conventions](../../concepts/conventions.md).

### `ObjectProvider` — the "maybe, and in order" injection

Ordinary injection is mandatory: no bean, no context. Two variants relax that, and the platform uses
both heavily.

```java
// Zero or one, resolved lazily. getIfAvailable() returns null when absent.
ObjectProvider<MeterRegistry> registries;

// Zero to many, in @Order sequence.
ObjectProvider<ProblemDetailCustomizer> customizers;
customizers.orderedStream().forEach(c -> c.customize(problem));
```

This is the mechanism behind the platform's entire **customizer tier**. When you contribute a
`ProblemDetailCustomizer`, `SecurityCustomizer`, `PlatformRestClientCustomizer`, or `LogSanitizer`
bean, the platform picks it up through an `ObjectProvider` and applies it in `@Order` — you *add*
behaviour to a platform default without *replacing* the bean that owns it.

---

## 2. Auto-configuration — the mechanism the whole platform rides on

This is the single most important section in this primer.

**Auto-configuration is conditional bean declaration that happens after your own beans are
registered.** A library ships classes annotated `@AutoConfiguration` and lists them in a file:

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Boot reads that file from every jar on the classpath, evaluates each class's conditions, and
registers the beans whose conditions hold.

```java
@AutoConfiguration
@ConditionalOnClass(ObjectStore.class)
@ConditionalOnProperty(prefix = "dc.platform.storage", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(StorageProperties.class)
public class FsObjectStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore fsObjectStore(StorageProperties properties) {
        return new FsObjectStore(properties.fs().root());
    }
}
```

Read that class the way an operator reads it, top to bottom:

1. It is an auto-configuration, so it runs late — after user beans.
2. It applies only if `ObjectStore` is on the classpath (`@ConditionalOnClass`).
3. It applies only unless someone set `dc.platform.storage.enabled=false` (`@ConditionalOnProperty`
   with `matchIfMissing = true` — the platform's universal kill-switch idiom).
4. It contributes an `ObjectStore` **only if the application has not already defined one**
   (`@ConditionalOnMissingBean`).

Point 4 is the entire extension model, in one annotation. Every platform bean carries it.

!!! success "Best practice — override by declaration, never by forking"
    To replace platform behaviour, declare your own bean of the same type. The platform's
    `@ConditionalOnMissingBean` sees it and stands down. You never edit, patch, or fork a platform
    module. This is [ADR-008](../../decisions/adr-008.md), and it is why the platform describes itself
    as "a set of defaults, not a cage".

### The conditions you will actually meet

| Condition | Fires when | Where the platform uses it |
|---|---|---|
| `@ConditionalOnClass` | A type is on the classpath | Guards optional integrations — observability touches Micrometer only if it is present |
| `@ConditionalOnMissingClass` | A type is absent | Rare; fallback providers |
| `@ConditionalOnBean` | A bean of that type already exists | The authz advisor activates only once a `CurrentUserAccessor` exists |
| `@ConditionalOnMissingBean` | No bean of that type exists | **Every platform bean**, without exception |
| `@ConditionalOnProperty` | A property has a given value | Every capability's `dc.platform.<cap>.enabled` kill switch |
| `@ConditionalOnWebApplication` | Running as a servlet or reactive web app | Filters, error advice, the security chain |

!!! warning "Bean conditions are evaluated in registration order, and the order is not obvious"
    `@ConditionalOnBean` and `@ConditionalOnMissingBean` are evaluated *at the moment the
    auto-configuration is processed*, against the beans registered so far. This is why
    `@ConditionalOnMissingBean` works reliably against **your** beans — yours are registered first —
    but is fragile between two auto-configurations. When two auto-configurations must be ordered, the
    platform says so explicitly with `@AutoConfiguration(before = …, after = …)` rather than hoping.

### Debugging auto-configuration: the condition report

When a platform bean does not appear, do not read platform source. Ask Boot:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--debug
```

Boot prints a **condition evaluation report** in three parts:

- **Positive matches** — auto-configurations that applied, and why.
- **Negative matches** — auto-configurations that did *not* apply, **and the exact condition that
  failed**. This is the section you want. It reads like
  `did not match: @ConditionalOnClass did not find required class 'software.amazon.awssdk.services.s3.S3Client'`.
- **Exclusions** — anything explicitly excluded.

!!! tip "The three-question debugging sequence"
    When a capability is not doing what you expect, ask in this order:

    1. Is the starter on the classpath? (`mvn dependency:tree`)
    2. Is the capability enabled? (`dc.platform.<cap>.enabled` — and `/actuator/env` to see who set it)
    3. Did the condition match? (`--debug`, negative-matches section)

    Nine times out of ten the answer is question 1. The platform also ships
    [`FailureAnalyzer`s](../chapters/01-core.md) that turn the most common misconfigurations into a
    plain-English *Description / Action* block instead of a stack trace.

---

## 3. Starters — packaging, not code

A **starter** is a POM with no code in it. Its only job is to pull in a coherent set of dependencies
so that adding one line to your `pom.xml` gives you a working capability.

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-storage-s3</artifactId>
</dependency>
```

That starter brings the storage API, the storage SPI, the S3 provider implementation, the
auto-configuration, and the AWS SDK. You add one artifact; auto-configuration does the rest.

!!! note "No version number"
    Platform starters never carry a `<version>`. The [platform BOM](../../reference/bom.md), imported
    once in your parent, manages every platform artifact and every third-party version the platform
    touches. If you find yourself writing a version number for a platform artifact, something is
    wrong — see [Appendix D](../appendix/d-compatibility.md).

The platform's rule is stricter than Boot's: **one starter per capability; starters contain no code
and never depend on other starters.** That prevents the classic starter-chaining problem where adding
a "small" starter drags half the platform onto your classpath. A capability you do not add costs you
nothing.

Where a capability has multiple providers, the *provider* is the starter, and you pick exactly one:

- `platform-starter-cache-caffeine` **or** `platform-starter-cache-redis`
- `platform-starter-storage-fs` **or** `platform-starter-storage-s3`
- `platform-starter-locking-jdbc` **or** `platform-starter-locking-redis`
- `platform-starter-messaging-inmemory`, `-rabbit`, **or** `-kafka`

---

## 4. Configuration properties — typed, bound, documented

Reading configuration with `@Value("${some.key}")` scattered across classes does not scale: no type
safety, no defaults in one place, no IDE completion, no documentation. Boot's answer is
`@ConfigurationProperties` — one class per configuration group, bound from the environment.

The platform's flavour is an **immutable record**:

```java
@ConfigurationProperties(prefix = "dc.platform.restclient")
public record RestClientProperties(
        boolean enabled,
        Duration connectTimeout,
        Duration readTimeout,
        Map<String, ClientProperties> clients) {

    public RestClientProperties {
        // Defaults live in code, next to the type that uses them.
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }
}
```

Three things matter here, and all three are platform conventions:

- **Immutable.** Bound once at startup through constructor binding. Nothing mutates platform
  configuration at runtime, so there is no "who changed my timeout?" class of bug.
- **Prefixed.** Everything lives under `dc.platform.<capability>`, so platform configuration can never
  collide with yours and is trivially greppable.
- **Metadata-backed.** Each property also appears in `spring-configuration-metadata.json`, which is
  what gives you **IDE autocompletion and inline documentation** for every `dc.platform.*` key, and
  what generates the [property reference](../../reference/properties.md).

### Relaxed binding

Boot matches property names loosely. All of these bind to `connectTimeout`:

```
dc.platform.restclient.connect-timeout   # kebab-case — canonical, use this
dc.platform.restclient.connectTimeout    # camelCase
DC_PLATFORM_RESTCLIENT_CONNECTTIMEOUT    # environment variable
```

!!! success "Best practice — write kebab-case in YAML"
    It is the canonical form, it is what the documentation and metadata use, and it is what the
    `upgrade-check` goal scans for when it looks for deprecated keys.

### Durations and data sizes

`Duration` and `DataSize` bind from readable strings, which is why platform timeouts read the way
they do:

```yaml
dc:
  platform:
    restclient:
      connect-timeout: 2s
      read-timeout: 10s
    messaging:
      handler:
        retry:
          backoff: 500ms
```

### Property precedence — who wins

Boot resolves a key from an ordered list of **property sources**. Later-listed sources lose. Roughly,
highest precedence first:

```
  command-line arguments                       --server.port=9000
  SPRING_APPLICATION_JSON
  OS environment variables
  application-{profile}.yml                    (profile-specific)
  application.yml                              (your service)
  ─────────────────────────────────────────────────────────────────
  platform EnvironmentPostProcessor sources          ← lowest
```

That bottom line is a platform mechanism worth understanding. The platform holds opinions about
**third-party** keys — `logging.config`, `management.*`, `spring.jpa.*`, `resilience4j.*` — that it
cannot express with `@ConfigurationProperties`, because it does not own those prefixes. It contributes
them through an `EnvironmentPostProcessor` as **named, lowest-precedence property sources**:

| Source name | Contributes |
|---|---|
| `platform-logging-defaults` | `logging.config` → the shipped `logback-platform.xml` |
| `platform-observability-defaults` | Endpoint exposure, probes, health groups, baggage fields |
| `platform-data-jpa-defaults` | `open-in-view=false`, batch size 50, UTC JDBC time zone |

Because they sit at the bottom, **your configuration always wins** — you never have to fight the
platform for a key. And because they are named, `/actuator/env` tells you exactly which source
supplied the winning value:

```bash
curl -s localhost:8080/actuator/env/spring.jpa.open-in-view | jq
```

!!! tip "`/actuator/env` answers 'why is this value what it is?'"
    It lists every source with an opinion on a key, in precedence order, winner first. When a setting
    is not taking effect, this endpoint is faster than reading anyone's code.

!!! note "Why not just hard-code the opinions?"
    Because then they would not be overridable, and a platform you cannot override is a platform teams
    fork. Lowest-precedence sources give you opinion *and* escape hatch, visibly. See
    [ADR-010](../../decisions/adr-010.md).

### Deprecating a property

Property keys are a **public API surface**. A rename that "compiles fine" silently drops a production
setting — a timeout or a security control stops applying, with no error. The platform therefore
requires a declared deprecation window in metadata, and the `upgrade-check` goal scans a service's
YAML for keys deprecated in the target version. See [Chapter 14](../chapters/14-testing-dx.md).

---

## 5. Profiles — environment-shaped configuration

A **profile** is a named set of configuration that can be switched on. Beans and property files can
both be profile-scoped.

```yaml
# application.yml — always applies
spring:
  application:
    name: orders-service

---
spring:
  config:
    activate:
      on-profile: local
dc:
  platform:
    logging:
      format: console      # human-readable logs on a laptop

---
spring:
  config:
    activate:
      on-profile: prod
dc:
  platform:
    ratelimit:
      enabled: true
```

Activate with `--spring.profiles.active=local` or `SPRING_PROFILES_ACTIVE=local`.

The platform uses profiles for exactly one thing: **making local development pleasant without changing
production behaviour.** The `local` profile switches logs to console format; that is the pattern.

!!! warning "Never let a profile change a security decision"
    The security capability's disable switch is `mode=disabled`, and it is **never** implied by a
    profile. Profile-triggered security disabling is a well-documented production-breach pattern: a
    `local` profile leaks into a production deployment through a misconfigured environment variable,
    and authentication silently disappears. The platform makes that impossible by construction. Read
    [Chapter 5](../chapters/05-security-authz.md) before you touch security configuration.

---

## 6. Actuator — the operational surface

Spring Boot Actuator exposes management endpoints over HTTP. The platform configures a sensible set by
default (through `platform-observability-defaults`, so you can override any of it).

| Endpoint | Answers |
|---|---|
| `/actuator/health` | Is the app up? Aggregates health indicators |
| `/actuator/health/liveness` | Should the orchestrator restart me? |
| `/actuator/health/readiness` | Should the orchestrator send me traffic? |
| `/actuator/info` | Build metadata — version, commit, build time |
| `/actuator/env` | Every property, every source, in precedence order |
| `/actuator/metrics`, `/actuator/prometheus` | Micrometer meters |
| `/actuator/configprops` | Bound `@ConfigurationProperties`, with values |
| `/actuator/platform` | **Platform-specific:** which capabilities are live, with which providers |
| `/actuator/platformflags` | **Platform-specific:** read and flip feature flags at runtime |

!!! tip "`/actuator/platform` is the first thing to curl"
    It is a custom endpoint the platform adds, and it answers in one request the question that
    classpath archaeology otherwise takes twenty minutes to answer: *what platform behaviour is
    actually live in this running instance, with which provider?*

    ```bash
    curl -s localhost:8080/actuator/platform | jq
    ```

    You will also see a one-line version of the same thing in the startup log:
    `platform: core[ACTIVE], messaging[ACTIVE] (rabbit), storage[ACTIVE] (fs)`.

**Liveness versus readiness** is worth getting right, because getting it wrong causes outages.
*Liveness* failing means "this process is broken, restart it". *Readiness* failing means "I am alive
but cannot serve — stop sending me traffic". A database blip should fail readiness, not liveness;
restarting the pod will not fix the database, and a restart loop makes the incident worse. The
platform contributes both groups on any platform, not only Kubernetes, and includes `db`, `rabbit`,
and `redis` in readiness **only when present**.

!!! warning "Actuator endpoints are not public by default — and `platformflags` writes"
    The platform's security chain permits `health` and `info` and authenticates everything else. The
    `/actuator/platformflags` endpoint accepts `POST` and `DELETE` to flip flags at runtime; that is
    documented as a deliberate write operation you must expose and secure knowingly. See
    [Chapter 13](../chapters/13-ratelimit-flags.md).

---

## 7. Testing — slices, not full contexts

Booting the whole application for every test is slow and couples tests to unrelated configuration.
Boot's answer is **test slices**: annotations that build a context containing only the layer under
test.

| Boot slice | Loads |
|---|---|
| `@SpringBootTest` | Everything. Slowest; use for smoke tests |
| `@WebMvcTest` | MVC layer only — controllers, converters, filters |
| `@DataJpaTest` | JPA layer only, against an embedded database |

The platform ships its own slices that add the platform's cross-cutting behaviour to Boot's, so a
controller test exercises real correlation IDs, real problem responses, and real security:

| Platform slice | Use for |
|---|---|
| `@PlatformTest` | Full context with the platform wired — smoke tests |
| `@PlatformWebTest` | Controller tests with correlation, errors, and security active |
| `@AutoConfigureTestTransport` | Swaps messaging to a deterministic in-memory transport |

Alongside them the platform ships `TestTokens` (mint a JWT for a test without an IdP) and
`PlatformUsageRules` (an ArchUnit rule set your build runs to catch hand-rolled implementations of
things the platform already owns).

!!! success "Best practice — never `Thread.sleep` in a messaging test"
    The in-memory transport gives you `InMemoryEventTransport.awaitIdle(Duration)` and
    `TestEventTransport` for deterministic assertions. A sleeping test is a test that will flake in CI
    at the worst possible moment. [Chapter 14](../chapters/14-testing-dx.md) covers the full kit.

---

## 8. Putting it together — the life of a platform bean

Here is the whole mechanism in one sequence. You add a starter and set nothing else:

```
1.  pom.xml gains platform-starter-storage-fs
        |
2.  The starter's POM pulls in: storage-api, storage-spi,
    storage-fs (impl), storage-autoconfigure
        |
3.  SpringApplication.run() begins building the context
        |
4.  EnvironmentPostProcessors run FIRST -- before any bean exists.
    platform-logging-defaults sets logging.config, so even
    startup lines are already structured.
        |
5.  Your @Component / @Bean definitions are registered.
        |
6.  Boot reads every AutoConfiguration.imports on the classpath
    and evaluates conditions:
        @ConditionalOnClass(ObjectStore)         [ok] on classpath
        @ConditionalOnProperty(storage.enabled)  [ok] matchIfMissing
        @ConditionalOnMissingBean(ObjectStore)   [ok] you declared none
        |
7.  FsObjectStore is registered, bound to StorageProperties.
        |
8.  The capability contributes a CapabilityDescriptor.
        |
9.  Startup banner:  platform: core[ACTIVE], storage[ACTIVE] (fs)
    /actuator/platform reports the same thing, as JSON.
```

Now change one thing — you declare your own `ObjectStore` bean. Step 6's third condition fails, the
platform's bean is never registered, and yours is the only one in the context. Nothing was patched,
nothing was excluded, no configuration flag was set. That is the extension model, and it is just
`@ConditionalOnMissingBean`.

---

## Checklist — the Spring Boot concepts this book assumes

| Concept | One-line summary | Platform relevance |
|---|---|---|
| Application context | The managed bag of beans | Everything the platform contributes is a bean in yours |
| Constructor injection | Dependencies as constructor parameters | Mandatory; conformance rules enforce it |
| `ObjectProvider` | Optional / ordered injection | The customizer extension tier |
| Auto-configuration | Conditional beans registered after yours | How every capability activates |
| `@ConditionalOnMissingBean` | Back off if the user declared one | The override mechanism — the whole extension model |
| `@ConditionalOnProperty` | Property-gated activation | Every `dc.platform.<cap>.enabled` kill switch |
| Condition report (`--debug`) | Why a bean did or did not appear | First stop when a capability seems dead |
| Starter | A POM with no code | One line per capability; never versioned by you |
| `@ConfigurationProperties` | Typed, bound, documented configuration | Immutable records under `dc.platform.<cap>` |
| Relaxed binding | `connect-timeout` = `connectTimeout` = `CONNECT_TIMEOUT` | Write kebab-case |
| Property precedence | Sources ordered; highest wins | Platform defaults sit lowest, so you always win |
| `EnvironmentPostProcessor` | Contributes sources before the context exists | How the platform holds opinions on third-party keys |
| Profiles | Named configuration sets | Local ergonomics only — never security decisions |
| Actuator | HTTP management surface | `/actuator/platform` and `/actuator/env` are your friends |
| Liveness vs readiness | Restart me / stop routing to me | Getting this wrong causes restart-loop outages |
| Test slices | A context containing only the layer under test | `@PlatformWebTest`, `@PlatformTest` |

---

**Next:** [Primer 2 — Microservices Foundations](02-microservices.md) — the fleet-level problems these
mechanisms are pointed at.

[Back to the book](../index.md)
