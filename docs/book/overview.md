# Chassis Overview

> The high-level architecture, all 24 capabilities in one paragraph each, and how they relate to one
> another. Read this once end to end; come back to it as a map.

---

## 1. What the platform is, in one page

An internal Spring Boot **chassis**: 24 capabilities, delivered as ~120 Maven modules on a single
release train behind one BOM. You add starters; auto-configuration does the rest. Nothing is a
framework you build inside — every capability is ordinary Spring beans contributed by ordinary
auto-configuration, which is why [Primer 1](primer/01-spring-boot.md) is the whole prerequisite.

```
                     YOUR SERVICE
        business logic, controllers, entities
                          |
        ------------------+------------------
        |     you add one starter per        |
        |     capability you actually want   |
        ------------------+------------------
                          |
   +----------------------+----------------------+
   |            THE CHASSIS (24 capabilities)     |
   |                                              |
   |  ALWAYS-ON SPINE  (every service has these)  |
   |    core - errors - logging - observability   |
   |                                              |
   |  INBOUND EDGE          OUTBOUND EDGE         |
   |    validation            restclient          |
   |    security              resilience          |
   |    authz                 messaging           |
   |    openapi               events              |
   |    ratelimit                                 |
   |                                              |
   |  STATE                 CROSS-CUTTING         |
   |    data                  audit               |
   |    cache                 flags               |
   |    redis                 idempotency         |
   |    storage               locking             |
   |    files                 scheduling          |
   |                                              |
   |  DEVELOPMENT                                 |
   |    testing - dx                              |
   +----------------------------------------------+
                          |
              SPRING BOOT 4.x / JAVA 25
```

The grouping above is pedagogical, not architectural — capabilities do not know about these boxes. It
is how this book's chapters are organised, and how you should think about what to add when.

---

## 2. The architecture: Hybrid Starter + SPI

Seven architectures were evaluated when the platform was designed. Three were scored seriously, and
**Hybrid Starter + SPI** won by a clear margin (8.48 weighted, against 7.42 for pure starters and 6.58
for feature-modular). The full reasoning is in [concepts/architecture](../concepts/architecture.md); the
short version is the trade-off table:

| Candidate | Strength | Fatal flaw |
|---|---|---|
| **Pure starter-based** (mirror Spring Boot exactly) | Best simplicity and learning curve | No contractual extension point. Enterprises patch or fork; internals leak; compatibility cannot be promised |
| **Feature-modular** (independently versioned mini-products) | Maximum team autonomy | An N×M version-compatibility matrix at consumer scale — a support nightmare |
| **Hybrid Starter + SPI** (chosen) | Starter DX *plus* a contractual extension model | Module count, mitigated by scaffolding |

Hexagonal architecture was **rejected as the global style but adopted as an internal tactic**. The
platform has no business domain, so forcing every capability through a technology-free core produces
abstraction for its own sake. But where multiple providers genuinely exist — messaging, storage, cache,
locking — ports-and-adapters is exactly right, and it survives as the api/spi/provider split.

### The five module kinds

A capability `X` is built from up to five module kinds. You depend on exactly one of them.

| Module | Category | Contains | Do you touch it? |
|---|---|---|---|
| `platform-X-api` | API | Consumer interfaces, annotations, value types, exceptions | **Yes** — this is what you import |
| `platform-X-spi` | SPI | Provider contracts (`XProvider`, `XCustomizer`) | Only if you write a provider |
| `platform-X-<provider>` | Implementation | One provider, e.g. `platform-messaging-rabbit` | No — the starter brings it |
| `platform-X-autoconfigure` | Auto Configuration | `@AutoConfiguration`, properties, conditions | No |
| `platform-starter-X[-provider]` | Starter | **No code** — a POM | **Yes** — this is what you declare |

Simple capabilities with one plausible implementation collapse to `api + autoconfigure + starter`. The
SPI module exists **only where a second provider is plausible** — never speculatively. That rule is what
keeps the abstraction count honest.

### The dependency constitution

The module graph is governed by rules that are **enforced by the build**, not documented and hoped for:

```
   api      ->  core-api only
   spi      ->  same-capability api
   impl     ->  same-capability spi/api  +  its own third-party library
   autoconf ->  same-capability api/spi
                (+ optional impls, + other capabilities' API guarded
                   by @ConditionalOnClass)
   starter  ->  autoconfigure + named impl(s)

   FORBIDDEN:  api depending downward - impl -> impl - starter -> starter
               anything -> another module's .internal - any cycle
```

A custom Maven enforcer rule (`PlatformLayerRule`) plus an ArchUnit `ArchConstitutionTest` in every
module make a violating change fail to build. See [the constitution](../concepts/constitution.md) and
[ADR-007](../decisions/adr-007.md).

### The three-audience package model

| Package | Audience | Compatibility promise |
|---|---|---|
| `ae.gov.dubaicustoms.platform.<cap>` | Consumers | Stable. japicmp diffs it against the last release on every build |
| `…<cap>.spi` | Provider authors | Stable contract, evolves more freely |
| `…<cap>.internal` | Nobody | **None.** Excluded from javadoc and japicmp; ArchUnit fails your build if you import it |

Public types also carry apiguardian `@API(status, since)` — `STABLE`, `EXPERIMENTAL`, `DEPRECATED`,
`INTERNAL` — so you can read a type's stability from the jar at code time, before opening any
documentation.

---

## 3. The thirteen things every capability does the same way

These are the "constitution" features. They apply everywhere, and knowing them means you can predict
how an unfamiliar capability behaves.

| Convention | What it means for you |
|---|---|
| **One starter per capability** | "What do I add?" is always a one-line answer |
| **Auto-configuration only** | Standard Boot debugging works: `--debug`, `exclude=`, the condition report |
| **`@ConditionalOnMissingBean` everywhere** | Declare your own bean and the platform's backs off. No forking, ever |
| **Kill switch per capability** | `dc.platform.<cap>.enabled=false` disables it wholesale, config-only, during an incident |
| **Immutable prefixed properties** | Everything under `dc.platform.<cap>`, with IDE autocompletion from shipped metadata |
| **Property deprecation contract** | A rename requires a declared deprecation window; `upgrade-check` scans your YAML for them |
| **Lowest-precedence third-party defaults** | Platform opinions on `logging.*`, `management.*`, `spring.jpa.*` never beat yours, and `/actuator/env` shows who won |
| **Three-audience packages** | api / spi / internal, with three different promises |
| **Executable dependency constitution** | Layering violations fail the build, naming the edge and the fix |
| **Binary-compatibility gate** | japicmp makes SemVer a checked promise, so BOM upgrades are safe |
| **One release train, one BOM** | Upgrade the whole platform with one property change |
| **`CapabilityDescriptor` self-reporting** | Startup banner + `/actuator/platform` tell you what is live, with which provider |
| **`FailureAnalyzer` diagnostics** | Common misconfigurations produce a *Description / Action* block, not a stack trace |
| **Ordered `*Customizer` tier** | Add behaviour to a platform default without replacing the bean |

!!! tip "The one command that answers 'what is actually running?'"
    ```bash
    curl -s localhost:8080/actuator/platform | jq
    ```
    Every capability contributes a descriptor with its name, status, and active provider. This is
    runtime truth, not build-time inference.

---

## 4. The 24 capabilities

Each entry: what it is, what you add, and where the chapter is. Starter coordinates omit the version —
the [BOM](../reference/bom.md) manages it.

### The always-on spine

Four capabilities that every service should have, and that the archetype gives you by default.

**Core** — `platform-starter-core` · [Chapter 1](chapters/01-core.md)
The foundation everything else reads from. A correlation-ID filter at the highest precedence in the
chain reads `X-Correlation-Id` or generates a 32-hex id, publishes it to MDC, exposes it through
`RequestContext`, and echoes it on the response — so every log line and every error, *including
authentication failures that never reach your controller*, carries the same token. Core also defines
`PlatformException` and the `ErrorCode` taxonomy (`DC-<CAP>-<NNNN>`) that every capability throws from
and the errors capability renders, plus the startup capability banner.

**Errors** — `platform-starter-errors` · [Chapter 2](chapters/02-errors-validation.md)
Turns every failure into an RFC-9457 `application/problem+json` body with `type`, `title`, `status`,
`detail`, `instance`, plus `code`, `correlationId`, and `timestamp` extensions. Ships a business
exception hierarchy (`BusinessException` 422, `NotFoundException` 404, `ConflictException` 409) so
domain code throws *intent* rather than HTTP, maps validation failures to a redacted `errors[]` array,
and catches everything unmapped as a 500 with a generic detail — the real message goes only to the
correlated log, never to the caller. Application `@RestControllerAdvice` always wins; the platform is
the last resort.

**Logging** — `platform-starter-logging` · [Chapter 3](chapters/03-logging-observability.md)
Structured JSON logs by default, one line per event, with `@timestamp`, `level`, `logger`, `message`,
`service`, `correlationId`, and `stack_trace`. Configured through an `EnvironmentPostProcessor` so it
takes effect *before the application context exists* — which is what makes early startup and
failure-analysis lines structured too, the very lines that matter during an incident. The `local`
profile falls back to Boot's readable console format. All MDC entries become JSON fields; `Kv.of(k, v)`
gives you structured arguments that stay readable on a console; contributed `LogSanitizer` beans scrub
sensitive values centrally. Deliberately fail-soft: a broken logging setup must never take a service
down.

**Observability** — `platform-starter-observability` · [Chapter 3](chapters/03-logging-observability.md)
Every meter carries `service`, `env`, and `platform.version` so fleet dashboards work without
per-service forks. Contributes `/actuator/health/liveness` and `/readiness` on any platform — not only
Kubernetes — with `db`, `rabbit`, and `redis` included *when present*. Serves `/actuator/platform`.
Prometheus is local and on; OTLP export is off by default so a laptop never dials a collector.
Correlation propagates through tracing **baggage** and is explicitly excluded from metric tags, because
a per-request tag is an unbounded-cardinality incident. A `platform.capability.active{capability}` gauge
feeds the organisation's adoption dashboard.

### The inbound edge

What happens between the network and your controller method.

**Validation** — `platform-starter-validation` · [Chapter 2](chapters/02-errors-validation.md)
The constraints every service needs, defined once with tested semantics: `@NotBlankTrimmed`, `@Ulid`
(26-char Crockford base32), `@SafeText` (rejects ISO control characters — a cheap first line of defence
against log injection and terminal-escape smuggling), and `@FutureInstant` (measured against the
validator's `ClockProvider`, so tests inject a fixed clock instead of sleeping). Method validation is
enabled, so `@Validated` service-layer preconditions throw `ConstraintViolationException` and are mapped
to a 400 with field-level detail. A platform message bundle supplies defaults you override per key.

**Security** — `platform-starter-security` · [Chapter 5](chapters/05-security-authz.md)
A secure-by-default filter chain: stateless, security headers on, method security enabled,
`anyRequest().authenticated()` with a short explicit permit list (actuator health/info, api-docs,
swagger). JWT resource-server wiring uses the *standard* `spring.security.oauth2.resourceserver.jwt.*`
keys — no platform dialect to learn. Because the chain runs before `DispatcherServlet`, a dedicated
entry-point/denied-handler pair writes RFC-9457 bodies for 401 and 403, so auth failures look like every
other error. `CurrentUser` gives you a token-format-neutral principal, which is what audit, flags, data
auditing, and restclient depend on rather than on JWT internals. Disabling is explicit
(`mode=disabled`) and never implied by a profile.

**Authorization** — `platform-starter-security-authz` · [Chapter 5](chapters/05-security-authz.md)
`@RequiresPermission("orders:read")` on a method or type, enforced by a Spring AOP advisor, so
authorization intent sits next to the code it protects and is reviewable in a diff. The
`PermissionEvaluatorProvider` SPI makes evaluation pluggable — the default reads a configurable claim
(`roles`) off `CurrentUser`, and multiple providers compose with any-grant-wins — so migrating to a
central entitlement service does not mean editing every annotated method. Unauthenticated yields 401,
authenticated-but-unpermitted yields 403. The starter does *not* transitively pull security in; the
advisor activates only once a `CurrentUserAccessor` exists, so there is no silently permissive
half-wired state.

**OpenAPI** — `platform-starter-openapi` · [Chapter 4](chapters/04-openapi.md)
A springdoc document at `/v3/api-docs` and Swagger UI at `/swagger-ui.html` with **zero controller
annotations required**. Title and version default from `spring.application.name` and `info.app.version`.
The platform appends the `ProblemDetail` schema and the standard 400/401/403/404/409/422/500 responses
to every operation automatically — never overwriting a status you declared yourself — so generated
clients have a real error model. The bearer-JWT scheme is documented by default, matching the security
capability. Fail-soft: documents are generated per request, so a bad customizer can only break the
document, never request handling.

**Rate limiting** — `platform-starter-ratelimit` · [Chapter 13](chapters/13-ratelimit-flags.md)
`@RateLimited(name, permits, window, keyExpression)` protects an expensive *method*, not only the edge;
a programmatic `RateLimiter` returning `Decision(allowed, retryAfter)` handles dynamic keys such as
per-tenant quotas. An optional filter limits every inbound request by IP or user. Every 429 carries
`Retry-After` and an RFC-9457 body, so well-behaved clients back off instead of hot-looping. Two
providers by classpath: an in-memory Caffeine sliding window (which WARNs in `prod` that the effective
limit multiplies by instance count — an honest statement of its inaccuracy) or Redis via an atomic
`INCR` + `PEXPIRE` Lua script. The Redis provider **fails open**: a Redis error allows the request and
logs a warning, because a rate limiter must not become an availability incident.

### The outbound edge

How your service talks to everything else.

**REST client** — `platform-starter-restclient` · [Chapter 6](chapters/06-restclient-resilience.md)
`PlatformRestClientFactory.builder(clientName)` returns a pre-configured `RestClient.Builder` on the JDK
`HttpClient`. One injection point gives you correlation propagation (`X-Correlation-Id` outbound),
per-name timeouts (2 s connect, 10 s read by default — unbounded outbound calls are the classic
cascading-failure trigger), and `RemoteCallException` mapping that preserves status, a 1 KB-truncated
body, and the *remote* correlation id so both sides' logs join. Bearer-token relay is guarded: when
security is present and the request is authenticated, the token is relayed — but a restclient-only
consumer never pulls security in.

**Resilience** — `platform-starter-resilience` · [Chapter 6](chapters/06-restclient-resilience.md)
Tuned Resilience4j `default` configurations — retry at 3 attempts with 200 ms exponential backoff, a
circuit breaker at 50% failure over a 10-call window, a 5-second time limiter — contributed as
lowest-precedence `resilience4j.*` properties. Deliberately **no wrapper annotations**: you use
Resilience4j's own `@Retry`, `@CircuitBreaker`, and `@TimeLimiter`, so upstream documentation applies
verbatim and there is no parallel vocabulary to maintain. `RetryableOperation.call(name, supplier)`
covers programmatic and dynamic-policy call sites. Micrometer binding is on by default, because a
circuit breaker you cannot see is worse than none.

**Messaging** — `platform-starter-messaging-{inmemory,rabbit,kafka}` · [Chapter 7](chapters/07-messaging-events.md)
Integration events across a broker. `EventPublisher.publish(destination, event)` is at-least-once and
blocks until the transport acknowledges; `@EventHandler(destination, eventType)` receives either the
payload or an `EventEnvelope<T>` when you need headers. Event identity comes from `@EventType` on the
payload rather than the Java class name, so a producer-side rename cannot silently break consumers.
Bounded retry then DLQ republish is **transport-agnostic** and behaves identically over every provider.
Correlation rides as a `correlationId` header. RabbitMQ topology — durable topic exchange, bound queue,
dead-letter exchange and queue — is declared automatically, including the dead-letter path teams
routinely forget.

!!! warning "RabbitMQ is the supported broker; Kafka is experimental"
    The Kafka transport has Testcontainers round-trip tests, but no example uses it, it is absent from
    the smoke matrix, and platform infrastructure does not yet provide Kafka. Adopt
    `platform-starter-messaging-kafka` deliberately, knowing there is no infrastructure, example, or
    on-call knowledge behind it yet.

**Events** — `platform-starter-events` · [Chapter 7](chapters/07-messaging-events.md)
In-process **domain** events, which are a different problem from integration events and frequently
conflated with them. `DomainEventPublisher` bridges to `ApplicationEventPublisher`, and
`@DomainEventHandler` methods run **after the enclosing transaction commits** — never on rollback,
immediately when no transaction is active. That single property prevents the classic bug of emailing the
world about a change that was subsequently rolled back. An opt-in relay re-publishes `@EventType`-marked
domain events as integration events on the same after-commit callback; it is documented plainly as an
outbox *precursor*, not an outbox — a crash between commit and publish loses the event.

### State

**Data / JPA** — `platform-starter-data-jpa` · [Chapter 8](chapters/08-data.md)
`platform-data-api` carries `Money`, `EntityId<T>`, and `PersistenceConventions` with **no datastore
dependency at all**, so domain code can name a money amount without depending on Hibernate. `Money` is
an immutable amount plus ISO-4217 currency whose same-currency arithmetic **fails loudly** on mismatch,
turning a silent-corruption bug class into a test failure. `EntityId<T>` makes passing an `OrderId`
where a `CustomerId` belongs a compile error. JPA auditing populates created/modified columns with the
authenticated subject when security is present and `"system"` otherwise — a data-only service never
pulls in a security type. Hibernate defaults arrive at lowest precedence: `open-in-view=false`, batch
size 50 with ordered writes, UTC JDBC time zone. A Flyway-presence guard plus `FailureAnalyzer` fails
startup with an actionable message when JPA is configured without migrations.

**Cache** — `platform-starter-cache-{caffeine,redis}` · [Chapter 9](chapters/09-cache-redis.md)
No platform cache annotation — the programming model stays Spring's own `@Cacheable`. What the platform
adds is a `CacheKeyConvention` composing `<appName>:<cacheName>[:<part>]*`, which prevents two services
writing different values to the same key in a shared backend (a silent, extremely hard-to-diagnose
corruption); `CacheNames` with a validating factory; and per-cache TTL and max-size policy that applies
identically whichever provider is active. Caffeine is the default; `RedisCacheManager` takes precedence
when Spring Data Redis is present. Because the `CacheManager` is a plain bean, Boot's cache metrics
instrument it automatically — and hit ratio is the only way to know whether a cache is helping or just
adding a hop.

**Redis** — `platform-starter-redis` · [Chapter 9](chapters/09-cache-redis.md)
*Direct* Redis access — sessions, counters, operational state — deliberately kept distinct from caching,
because cache eviction destroying operational state is a real failure mode. A `BeanPostProcessor`
installs a prefixing key serializer on `StringRedisTemplate` (default `<spring.application.name>:`), so
code uses logical keys and the wire key is namespaced automatically: multi-tenant Redis without
per-call-site discipline. Connection tuning stays on Boot's own `spring.data.redis.*`. The one sharp
edge is documented rather than hidden: `SCAN` and `KEYS` operate on raw stored keys, so your patterns
must include the prefix.

**Storage** — `platform-starter-storage-{fs,s3}` · [Chapter 11](chapters/11-storage-files.md)
A streaming-first `ObjectStore` addressed by `(bucket, key)`: `put` returns an `ObjectRef`, `get`
returns `Optional<StoredObject>`, `list` returns a `Stream<ObjectSummary>`. There is deliberately **no
`byte[]` overload** — callers must wrap in-memory content themselves, which makes heap buffering an
explicit, reviewable decision instead of the path of least resistance that OOMs on a 2 GB upload.
`StoredObject` and the list stream are `AutoCloseable`, because unclosed S3 streams exhaust connection
pools in ways that surface hours later. SHA-256 is computed on put and stored as a `sha256` tag.
`KeyValidator` rejects traversal segments and the filesystem provider additionally verifies the resolved
path stays inside its root — defence in depth, because blob keys are frequently user-derived. Filesystem
by default; S3 when you supply an `S3Client`.

**Files** — `platform-starter-files` · [Chapter 11](chapters/11-storage-files.md)
Safe handling of what users upload. `ContentTypeValidator` sniffs **magic bytes** against a strict
allow-list (PDF, PNG, JPEG, ZIP, CSV, text) — extension and client-supplied content type are not
trusted, because both are trivially forged and a renamed executable passing upload validation is a
direct malware-delivery path. `SafeFilename.sanitize` strips directory components, null bytes, and
control characters. `FileUploadPolicy` carries size and type rules as one reviewable object, and servlet
multipart limits are set ahead of Boot's defaults so oversized uploads are rejected before bytes are
buffered. `StreamingDownloads.write(...)` streams a stored object at constant memory with a sanitised
`Content-Disposition`.

### Cross-cutting concerns

**Locking** — `platform-starter-locking-{jdbc,redis}` · [Chapter 10](chapters/10-coordination.md)
`LockManager.withLock(name, atMost, action)` runs an action under a cluster-wide lock and returns its
result, or `Optional.empty()` when the lock is held elsewhere — making "skipped" an explicit outcome you
must handle rather than a silent no-op. Acquisition is **non-blocking**: waiting would pile threads up
behind a contended lock until the pool is exhausted. Locks auto-expire so a crashed holder never wedges
the cluster, and both providers **fence with a token** so a slow holder whose lock already expired cannot
release someone else's. JDBC (with a `platform_lock` table whose Flyway migration location is appended
automatically) is the default; Redis when a `StringRedisTemplate` is present.

**Scheduling** — `platform-starter-scheduling` · [Chapter 10](chapters/10-coordination.md)
`@EnableScheduling` on by default with a **virtual-thread** `TaskScheduler`, so one blocking job cannot
starve the pool and delay every other scheduled task. `@LockedSchedule(name, atMost)` sits beside
`@Scheduled` and runs the method under a cluster-wide lock, so scaling to N replicas does not multiply
your nightly reconciliation by N. Without a `LockManager` it runs unlocked and warns once at startup —
visible degradation rather than a silent one. No `aspectjweaver` required.

**Idempotency** — `platform-starter-idempotency` · [Chapter 10](chapters/10-coordination.md)
`@Idempotent(keyExpression, ttl)` derives a key from SpEL over the method arguments, records it, and
rejects a duplicate within the TTL as a 409. An opt-in filter does the same for the industry-standard
`Idempotency-Key` header on POST. JDBC-backed by default (`platform_idempotency`, expiry-column TTL,
migration shipped), Redis via `SET NX PX` when available. Scope is documented honestly: this is
**reject-duplicate**, not response replay — a client that treats 409 as failure will report an error for
an operation that succeeded.

**Audit** — `platform-starter-audit` · [Chapter 12](chapters/12-audit.md)
`@Audited(action, resourceExpression)` records an event whether the method **returns or throws**
(`SUCCESS` / `FAILURE`) — failed privileged operations are the most security-relevant records there are,
and capturing only successes misses the attack. Actor comes from the current user, correlation id from
the request context, resource from SpEL. A programmatic `Auditor` covers what the annotation cannot
express, on the same event model. The sink chain degrades `messaging → jdbc → log`, with a startup WARN
naming the live sink, so a service always has a working audit destination. A bounded queue feeds one
daemon worker; under sustained overload records are **shed with a warning** rather than adding latency
to the request path — an explicit, documented trade of completeness for availability, with the knob to
reverse it.

**Feature flags** — `platform-starter-flags` · [Chapter 13](chapters/13-ratelimit-flags.md)
`FeatureFlags.enabled(flag)` and `value(flag, default)` with **fail-safe-off** semantics: an unknown
flag returns `false` or your default, so a typo can never enable an unfinished path in production.
`@FeatureGate("flag")` skips a whole method when off and returns a neutral value. The in-memory provider
is seeded from `dc.platform.flags.static.*` and flippable at runtime through
`/actuator/platformflags` — documented as a write operation you must expose and secure deliberately. An
OpenFeature adapter is selected automatically when an OpenFeature `Client` bean is present, so
LaunchDarkly, Flagsmith, and similar plug in without touching call sites. When security is present, the
current subject and tenant enter the evaluation context, which is what percentage and cohort rollouts
require.

### Development

**Testing** — `platform-starter-test` · [Chapter 14](chapters/14-testing-dx.md)
The slices and fixtures that make platform behaviour testable without infrastructure: `@PlatformTest`
and `@PlatformWebTest` build contexts with the platform's cross-cutting behaviour active;
`@AutoConfigureTestTransport` and `TestEventTransport` make messaging assertions deterministic and
sleep-free; `TestTokens` mints a JWT without an IdP; `PlatformUsageRules` is an ArchUnit rule set that
fails your build when you hand-roll something the platform already owns. Each multi-provider capability
also ships a **TCK** — an abstract test class a provider extends to prove conformance against the same
contract the platform's own providers pass.

**Developer experience** — [Chapter 14](chapters/14-testing-dx.md)
Not a starter but a capability nonetheless: `platform-parent` centralises the quality bar for 120
modules; `platform-service-parent` gives consumer builds the same habits without the platform's release
cadence; `platform-bom` makes dependency management one line. A `new-module` goal scaffolds
constitution-compliant modules; `upgrade-check` diffs versions and scans your YAML for deprecated keys
*before* you change code, producing `target/platform-upgrade-report.md`. A service archetype generates a
running service — parent, conformance test, slice tests, profile-aware `application.yml`, `CLAUDE.md`,
`AGENTS.md` — in one command, and the golden-path script verifies that whole path end to end against a
10-minute SLA. An MCP server and Claude Skill ground AI-assisted development in a build-time
`platform-index.json` so agents produce platform-idiomatic rather than vanilla-Spring code.

---

## 5. How the capabilities relate

Capabilities are independent artifacts, but they are not unaware of each other. Three kinds of
relationship exist, and telling them apart matters when you are deciding what to add.

**Hard dependency** — the capability does not work without the other. Rare by design.

**Optional enrichment** — guarded by `@ConditionalOnClass` or `@ConditionalOnBean`. Both capabilities
work alone; together they do more. This is the dominant pattern, and it is what "a capability you don't
add costs you nothing" means in practice.

**Shared convention** — no code dependency at all; they simply agree on something, such as the
correlation id or the `ErrorCode` format.

```
                                 core
                     (RequestContext, correlation id,
                      PlatformException, ErrorCode)
                                   |
        +--------------+-----------+-----------+--------------+
        |              |                       |              |
     errors         logging               observability     restclient
   (renders          (MDC ->              (baggage,         (X-Correlation-Id
    ErrorCode)        JSON fields)         NOT a tag)        outbound)
        |
        +-- validation      (violations -> errors[] in the problem body)
        +-- security        (401/403 rendered as problem+json)
        +-- ratelimit       (429 written by an MVC advice, and by the filter)
        +-- idempotency     (duplicate -> ConflictException -> 409)

     security ....> audit          (actor = current subject, else "anonymous")
     security ....> data           (auditor = current subject, else "system")
     security ....> flags          (subject + tenant in the evaluation context)
     security ....> restclient     (guarded bearer-token relay)
             (all four are optional enrichment: each works without security)

     locking <---- scheduling      (@LockedSchedule needs a LockManager;
                                    without one it warns and runs unlocked)

     messaging <-- events          (opt-in relay: domain event -> integration event)
     messaging <-- audit           (messaging sink, when present)

     redis ......> cache           (provider selection by classpath)
     redis ......> locking         (provider selection by classpath)
     redis ......> ratelimit       (provider selection by classpath)
     redis ......> idempotency     (provider selection by classpath)

     storage <---- files           (files validates and streams; storage persists)
```

Read `....>` as "enriches when present" and `<----` as "uses when present". Note what is *not* there:
no capability requires another capability's implementation, nothing forms a cycle, and every arrow into
`security` is optional — which is why a data-only batch service can use JPA auditing without pulling a
single security type onto its classpath.

!!! success "Best practice — add capabilities when the problem appears, not in advance"
    The archetype gives you the spine plus security and OpenAPI. Everything else should be added the
    week you need it. A starter you add "because we might" is a dependency you upgrade, a CVE you
    triage, and a behaviour you have to explain during an incident.

---

## 6. What to add, and when

| You are about to… | Add | Chapter |
|---|---|---|
| Start any service at all | `core`, `errors`, `logging`, `observability` (the archetype does this) | [1](chapters/01-core.md), [2](chapters/02-errors-validation.md), [3](chapters/03-logging-observability.md) |
| Accept a request body | `validation` | [2](chapters/02-errors-validation.md) |
| Expose the API to anyone else | `openapi` | [4](chapters/04-openapi.md) |
| Handle a real user | `security`; add `security-authz` when roles are not enough | [5](chapters/05-security-authz.md) |
| Call another service | `restclient`, and `resilience` the moment it matters | [6](chapters/06-restclient-resilience.md) |
| Tell other services something happened | `messaging` (pick a transport starter) | [7](chapters/07-messaging-events.md) |
| React to your own writes after commit | `events` | [7](chapters/07-messaging-events.md) |
| Store relational data | `data-jpa` — and set up Flyway before you start | [8](chapters/08-data.md) |
| Stop recomputing something expensive | `cache-caffeine`, then `cache-redis` when you scale out | [9](chapters/09-cache-redis.md) |
| Keep counters or sessions in Redis | `redis` (not `cache` — different lifecycle) | [9](chapters/09-cache-redis.md) |
| Run a scheduled job on >1 replica | `scheduling` **and** a `locking` provider | [10](chapters/10-coordination.md) |
| Accept a retryable POST | `idempotency` | [10](chapters/10-coordination.md) |
| Store documents or images | `storage-fs` locally, `storage-s3` in production | [11](chapters/11-storage-files.md) |
| Accept an upload from a browser | `files` **and** `storage` | [11](chapters/11-storage-files.md) |
| Answer "who did this?" to an auditor | `audit` | [12](chapters/12-audit.md) |
| Protect an expensive operation | `ratelimit` | [13](chapters/13-ratelimit-flags.md) |
| Ship code before it is switched on | `flags` | [13](chapters/13-ratelimit-flags.md) |
| Write a test of any of the above | `platform-starter-test` (test scope) | [14](chapters/14-testing-dx.md) |

---

## 7. Where to go next

- **Building your first service?** [Quickstart](../quickstart.md), then
  [Chapter 1](chapters/01-core.md).
- **Want to see it working?** The four [example services](../examples.md) build inside the reactor and
  consume the platform exactly as a real service does. `example-golden-path` is the one to read first.
- **Operating it?** The *Operations* section of each chapter, then the
  [cross-cutting chapters](crosscutting/observability-strategy.md).
- **Extending it?** [Extending the chassis](crosscutting/extending.md) and the
  [extension model](../concepts/extension-model.md).
- **Want the full inventory with rationale?** [The feature catalog](../reference/feature-catalog.md)
  lists every feature of every capability with why it exists and what breaks without it.

---

[Back to the book](index.md) · **Next:** [Chapter 1 — Core](chapters/01-core.md)
