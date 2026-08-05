# Appendix B — Glossary

> Platform, Spring, and distributed-systems terms as this book uses them.

Terms are defined **as this platform uses them**, which occasionally differs from general usage — where
it does, the entry says so. Each links to the chapter that covers it properly.

---

## A

**Advisor** — A Spring AOP pairing of a *pointcut* (which methods) and an *interceptor* (what to do).
Every platform annotation is implemented as one. Proxy-based, hence the self-invocation caveat.

**Allow-list** — An enumeration of what is permitted; everything else is refused. The platform uses
allow-lists for upload content types and permitted paths, never deny-lists, because a deny-list is
unbounded and always incomplete. → [11](../chapters/11-storage-files.md)

**At-least-once** — A delivery guarantee: a message arrives one **or more** times. Never fewer, possibly
more. The platform's messaging semantic, which is why every handler must be idempotent.
→ [7](../chapters/07-messaging-events.md)

**`atMost`** — The lease on a distributed lock, after which it auto-expires even if the holder is still
working. Sized above the **worst-case** runtime, not the average. → [10](../chapters/10-coordination.md)

**Auto-configuration** — Conditional bean declaration that runs **after** your own beans are registered.
The mechanism the entire platform rides on. Listed in
`META-INF/spring/…AutoConfiguration.imports`. → [Primer 1](../primer/01-spring-boot.md)

---

## B

**Back-off** — A platform bean standing down because you declared your own. Implemented as
`@ConditionalOnMissingBean`. The platform's entire extension model in one annotation.
→ [Patterns](../crosscutting/patterns.md)

**Baggage** — W3C Trace Context's mechanism for propagating key-value pairs alongside a trace across
process boundaries. How the platform carries the correlation id between services **without** making it
a metric tag. → [3](../chapters/03-logging-observability.md)

**BOM** *(Bill of Materials)* — A POM importing version management for a set of artifacts. Importing
`platform-bom` once pins every platform artifact and every third-party version the platform touches,
so you never write a version for a platform dependency.

**Bulkhead** — Isolating resources so one failing dependency cannot consume all of them. Named for ship
compartments. → [6](../chapters/06-restclient-resilience.md)

---

## C

**Capability** — One cross-cutting concern, delivered as up to five modules and consumed as one
starter. The platform has 24. → [Overview](../overview.md)

**`CapabilityDescriptor`** — A `(name, status, detail)` bean each capability contributes, rendered as
the startup banner and at `/actuator/platform`. Runtime truth about what is live.
→ [1](../chapters/01-core.md)

**Cardinality** — The number of distinct values a metric tag takes. Every combination is a separate
time series, stored forever, on every instance. Unbounded cardinality is the failure mode that takes
down metrics backends. → [3](../chapters/03-logging-observability.md)

**Chassis** — The layer handling cross-cutting concerns so a new service starts with them solved.
This platform. → [Primer 2](../primer/02-microservices.md)

**Circuit breaker** — A state machine that stops calling a failing dependency. `CLOSED` → `OPEN` (fail
fast, no network) → `HALF_OPEN` (probe) → back. Protects the caller's threads *and* gives the callee
room to recover. → [6](../chapters/06-restclient-resilience.md)

**Constitution** *(dependency constitution)* — The enforced rules governing which platform module may
depend on which. Implemented as a Maven enforcer rule plus ArchUnit in every module, so a violation
fails the build. → [Extending](../crosscutting/extending.md)

**Correlation id** — One token per request, propagated across every hop, on every log line, error
response, message header, and audit event. 32 lowercase hex — a UUID without dashes, matching W3C Trace
Context's `trace-id`. **Not** a metric tag. → [1](../chapters/01-core.md)

**Customizer** — A bean that *adds* behaviour to a platform default without *replacing* it. Collected
via `ObjectProvider.orderedStream()`, applied in `@Order`. Must be thread-safe, fast, and must never
throw. → [Patterns](../crosscutting/patterns.md)

---

## D

**Deny-by-default** — Everything requires authentication unless explicitly permitted. Fails *loudly* on
the developer's machine, where allow-by-default fails *silently* in production months later.
→ [5](../chapters/05-security-authz.md)

**DLQ** *(dead-letter queue)* — Where a message goes after bounded retries are exhausted. The platform's
DLQ republish is transport-agnostic, so behaviour is identical across in-memory, RabbitMQ, and Kafka.
A DLQ nobody consumes is slow-motion data loss. → [7](../chapters/07-messaging-events.md)

**Domain event** — Something that happened **inside one process**, dispatched to handlers in the same
deployable, **after the transaction commits**. Distinct from an integration event.
→ [7](../chapters/07-messaging-events.md)

---

## E

**`EnvironmentPostProcessor`** — Runs before any bean exists, so it can configure things (like logging)
that are initialised before the application context. The platform uses four to contribute
lowest-precedence defaults on third-party keys. → [Appendix A](a-configuration.md)

**`@EventType`** — Declares an event's **wire identity**, independent of its Java class name. Without
it, a producer-side rename silently breaks every consumer.
→ [7](../chapters/07-messaging-events.md)

**EXPERIMENTAL** — An apiguardian `@API` status meaning the platform reserves the right to change the
contract in a minor release. Most SPIs carry it; consumer-facing APIs do not.

---

## F

**Fail-open / fail-closed** — What a control does when it cannot decide. The platform's rate limiter
fails **open** (a limiter must not become an availability incident); authorization should fail
**closed** (permitting the unauthorised is worse than rejecting the legitimate). The direction is
chosen from what a wrong answer costs. → [Patterns](../crosscutting/patterns.md)

**Fail-safe-off** — An unknown feature flag evaluates to `false`; an unknown value returns your default.
A typo can never enable unfinished code. → [13](../chapters/13-ratelimit-flags.md)

**`FailureAnalyzer`** — A Spring Boot hook turning a startup exception into a *Description / Action*
block naming the fix. The platform ships several for its most common misconfigurations.

**Fencing** *(token fencing)* — Tagging each lock acquisition with a token so a holder whose lease has
already expired cannot release a lock now owned by someone else. Prevents lock *theft*; does not
prevent the overlap an expired lease already caused. → [10](../chapters/10-coordination.md)

**Fixed window / sliding window** — Rate-limiting algorithms. Fixed counts per calendar window (cheap,
allows a 2× burst across a boundary); sliding counts over a trailing window (accurate, more memory).
The platform's Redis provider uses fixed; the in-memory one uses sliding.
→ [13](../chapters/13-ratelimit-flags.md)

---

## G — I

**Golden path** — The end-to-end onboarding sequence — install, generate, build, boot, probe — verified
by a script with a **10-minute SLA**. The developer experience has a budget, and the budget is tested.
→ [14](../chapters/14-testing-dx.md)

**Idempotent** — Performing an operation *n* times has the same effect as performing it once. Mandatory
for message handlers under at-least-once delivery. → [10](../chapters/10-coordination.md)

**Integration event** — Something that happened, published **across service boundaries** over a broker,
to services you do not deploy with. Distinct from a domain event.
→ [7](../chapters/07-messaging-events.md)

**`.internal`** — A package carrying **no compatibility guarantee**: excluded from javadoc and from the
japicmp gate. One of the platform's three package families.

---

## J — L

**japicmp** — The build gate diffing public API and SPI packages against the last release, so SemVer is
a checked promise rather than an intention.

**JWT** *(JSON Web Token)* — The bearer token format the platform's security capability validates
locally against the IdP's published keys — no per-request call to the IdP.
→ [5](../chapters/05-security-authz.md)

**Kill switch** — `dc.platform.<capability>.enabled`, defaulting to `true`. Stops the platform
*contributing*; what remains is the underlying library's default, not nothing.
→ [Appendix A](a-configuration.md) §2

**`Kv`** — A `(key, value)` record for structured log arguments. Renders as `key=value` on a console and
becomes a JSON field in production, from the same call site.
→ [3](../chapters/03-logging-observability.md)

**Liveness / readiness** — Two probes with different consequences. Liveness failing means *restart me*;
readiness failing means *stop routing to me*. A dependency blip belongs in readiness — restarting will
not fix the database. → [3](../chapters/03-logging-observability.md)

**Local-first** — Every multi-provider capability ships a provider needing no infrastructure, so
`mvn verify` passes with no Docker, no network, and no credentials. A correctness property, not a
convenience: a suite that needs infrastructure is a suite that gets skipped.
→ [Local vs production](../crosscutting/local-vs-production.md)

---

## M — O

**MDC** *(Mapped Diagnostic Context)* — SLF4J's thread-local map that logging frameworks consult when
formatting each event. How `correlationId` reaches every log line without any call site mentioning it.
→ [1](../chapters/01-core.md)

**Micrometer** — The metrics façade — the SLF4J of metrics. Your code records against `MeterRegistry`;
the backend is a separate concern.

**Outbox** — A pattern writing an event to a table **inside** the same transaction as the data, relayed
separately, so the event is as durable as the data. The platform's domain-event relay is an outbox
**precursor**, not an outbox: a crash between commit and publish loses the event.
→ [7](../chapters/07-messaging-events.md)

---

## P — R

**`platform-index.json`** — A build-time inventory of capabilities, properties, error codes, API types,
and snippets, consumed by the MCP server and the Claude Skill so AI-assisted development is grounded in
platform facts.

**Problem detail** — An RFC-9457 `application/problem+json` body. The platform adds `code`,
`correlationId`, and `timestamp` to the five standard members.
→ [2](../chapters/02-errors-validation.md)

**Provider** — One implementation of a capability's SPI — a transport, a store, a lock backend.
Certified by passing the capability's TCK. → [Extending](../crosscutting/extending.md)

**Release train** — All platform artifacts sharing one version, released together. Import one BOM,
upgrade with one property change. Kills the N×M compatibility matrix.

**`RequestContext`** — Static, read-only access to the current correlation id and extras, backed by a
per-thread stack of scopes that restores shadowed MDC values exactly on close.
→ [1](../chapters/01-core.md)

**RFC 9457** — The IETF standard for HTTP problem responses (formerly RFC 7807). One media type, five
standard members, open extensions. → [2](../chapters/02-errors-validation.md)

---

## S

**Self-invocation** — Calling an annotated method from another method **on the same bean** (`this.x()`),
which bypasses the proxy and therefore the annotation. Affects every platform annotation. Costs
performance for `@Cacheable`; costs **correctness** for `@RequiresPermission` and `@Idempotent`.
→ [Patterns](../crosscutting/patterns.md)

**Slice** — A test annotation building a context containing only the layer under test. The platform's
slices add its cross-cutting behaviour to Boot's, so a controller test exercises real correlation, real
problem responses, and real security. → [14](../chapters/14-testing-dx.md)

**SPI** *(Service Provider Interface)* — The contract a *provider* implements, as opposed to the API a
*consumer* calls. The platform creates one only where a second provider is genuinely plausible **and
the contracts differ**. → [Extending](../crosscutting/extending.md)

**STABLE** — An apiguardian `@API` status meaning binary compatibility is promised within the major
version and checked by japicmp.

**Starter** — A POM with **no code**, aggregating the dependencies for one capability. One line in your
`pom.xml`, never with a version. Starters never depend on other starters.

---

## T — Z

**TCK** *(Technology Compatibility Kit)* — An abstract test class encoding a capability's contract. A
provider is **platform-certified if and only if its TCK passes**. What makes an SPI real rather than a
set of signatures. → [14](../chapters/14-testing-dx.md)

**Three-audience model** — Three package families with three compatibility promises: `…<cap>` for
consumers (STABLE), `…<cap>.spi` for providers, `…<cap>.internal` for nobody.

**ULID** — A 26-character Crockford base32 identifier, first character `0`–`7`. Validated by the
platform's `@Ulid` constraint. → [2](../chapters/02-errors-validation.md)

**Virtual thread** — A JVM-managed lightweight thread. Blocking one costs a few hundred bytes rather
than a megabyte of stack, which is why the platform's scheduler gives each firing its own.
→ [10](../chapters/10-coordination.md)

---

## Platform annotations at a glance

| Annotation | Capability | Chapter |
|---|---|---|
| `@Audited` | audit | [12](../chapters/12-audit.md) |
| `@DomainEventHandler` | events | [7](../chapters/07-messaging-events.md) |
| `@EventHandler` | messaging | [7](../chapters/07-messaging-events.md) |
| `@EventType` | messaging | [7](../chapters/07-messaging-events.md) |
| `@FeatureGate` | flags | [13](../chapters/13-ratelimit-flags.md) |
| `@FutureInstant` | validation | [2](../chapters/02-errors-validation.md) |
| `@Idempotent` | idempotency | [10](../chapters/10-coordination.md) |
| `@LockedSchedule` | scheduling | [10](../chapters/10-coordination.md) |
| `@NotBlankTrimmed` | validation | [2](../chapters/02-errors-validation.md) |
| `@PlatformApi` / `@PlatformInternal` | core | [1](../chapters/01-core.md) |
| `@RateLimited` | ratelimit | [13](../chapters/13-ratelimit-flags.md) |
| `@RequiresPermission` | authz | [5](../chapters/05-security-authz.md) |
| `@SafeText` | validation | [2](../chapters/02-errors-validation.md) |
| `@Ulid` | validation | [2](../chapters/02-errors-validation.md) |

Test-only: `@PlatformTest`, `@PlatformWebTest`, `@PlatformDataTest`, `@PlatformMessagingTest`,
`@AutoConfigureTestTransport`. → [14](../chapters/14-testing-dx.md)

## Error-code ranges

`DC-<CAP>-<NNNN>` — `0001`–`0399` business · `0400`–`0499` client · `0500`–`0599` infrastructure.
Registry: [reference/error-codes.md](../../reference/error-codes.md)

---

**Next:** [Appendix C — Troubleshooting Cookbook](c-troubleshooting.md)

[Back to the book](../index.md)
