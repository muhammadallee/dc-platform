# Primer 2 — Microservices Foundations

> The fleet-level problems a chassis exists to solve, and the architectural principles the platform
> encodes. Read this to understand *what the platform is arguing about* before you read what it does.

[Primer 1](01-spring-boot.md) covered the mechanisms. This chapter covers the *problems*. Every
capability in this book is a considered answer to one of them, and the answers only make sense once you
can see the problem at fleet scale rather than service scale.

!!! note "The unit of analysis is the fleet, not the service"
    Almost everything in this chapter is uncontroversial for one service and decisive for a hundred. A
    hand-written error handler in one service is fine. The same handler, written a hundred slightly
    different ways, is a client-side integration tax that never goes away. Keep that multiplier in mind
    throughout.

---

## 1. What a microservice chassis is

A **microservice chassis** is the layer that handles the cross-cutting concerns every service needs —
configuration, logging, health checks, metrics, tracing, error handling, security, resilience — so that
a new service starts with those already solved.

The alternative shapes, and why they lose:

| Shape | What happens |
|---|---|
| **Copy-paste from a "reference service"** | The reference is stale within a quarter. Every new service inherits whatever bugs it had on the day it was copied, and the copies diverge forever after |
| **A shared "common" library of utilities** | No activation model. Teams must know a utility exists, remember to call it, and call it correctly. Adoption is voluntary, which means partial |
| **A framework services are built *inside*** | A second framework to learn on top of Spring. Every Boot upgrade becomes a platform migration project, and Boot's own diagnostics stop working |
| **A chassis of auto-configured starters** | Add a dependency, get correct behaviour. Nothing to remember, nothing new to learn, and Boot's diagnostics still apply |

The platform is the fourth. That single choice — *"Spring Boot mechanisms only, no God framework"* — is
[ADR-009](../../decisions/adr-009.md), and it is why [Primer 1](01-spring-boot.md) is the only
prerequisite for this book.

!!! success "Best practice — the chassis is a floor, not a ceiling"
    A chassis that forbids things gets forked. A chassis that provides good defaults and gets out of
    the way gets adopted. Every platform default is overridable by declaring a bean, and every
    capability has a kill switch. If you find yourself wanting to fork a platform module, that is a bug
    report, not a workflow.

---

## 2. The distributed-systems problems, and who solves them here

### 2.1 A request no longer happens in one place

A single user action fans out across services, threads, queues, and scheduled jobs. When it fails,
"what happened?" has no single log file to answer it.

The industry answer is a **correlation identifier**: one token generated at the edge, propagated across
every hop, attached to every log line, every error response, and every message header. With it, one
query reconstructs the whole story. Without it, you are matching timestamps by eye across five log
stores.

The platform's answer runs deeper than most:

- The correlation filter sits at **highest precedence** — ahead of the security filter chain — so even a
  rejected authentication carries an id. That is exactly the population you most need to trace, and
  exactly the population most implementations miss.
- The id is exposed as `RequestContext`, echoed on the response, lifted into MDC (so it lands in every
  log line for free), sent as `X-Correlation-Id` on outbound REST calls, published as a `correlationId`
  message header, and recorded on every audit event and every problem response.

Covered in [Chapter 1](../chapters/01-core.md); the end-to-end story is in
[Observability strategy](../crosscutting/observability-strategy.md).

!!! warning "Correlation is not a metric tag"
    A correlation id is unique per request. Attaching it to a metric creates one time series per
    request — an unbounded-cardinality explosion that takes down metrics backends and produces
    memorable invoices. The platform propagates it through *tracing baggage* and explicitly excludes it
    from metric tags. If you add a custom meter, do not tag it with anything per-request.

### 2.2 Failure must be machine-readable

When every service invents its own error envelope, every client writes a parser per service, and retry
logic — "is this worth retrying?" — becomes per-integration folklore.

**RFC 9457** (formerly RFC 7807) standardises this: `application/problem+json` with `type`, `title`,
`status`, `detail`, `instance`, plus extensions. One shape, one parser, for the whole estate.

The platform renders every failure that way, including the two that are hardest to get right:

- **Security failures.** 401 and 403 are produced by the security filter chain, which runs *before*
  `DispatcherServlet` — so `@RestControllerAdvice` cannot reach them. Most implementations therefore
  return Spring's default HTML or an empty body for exactly these two statuses, and every client carries
  a special case forever. The platform installs a dedicated entry-point and denied-handler pair so auth
  failures look like every other error.
- **Unmapped exceptions.** Anything unrecognised becomes a 500 with a stable code and a *generic*
  detail; the real message goes only to the correlated log. That is not politeness — leaking a stack
  trace, a SQL fragment, or an internal hostname to a caller is an information-disclosure finding.

Covered in [Chapter 2](../chapters/02-errors-validation.md).

### 2.3 Everything you call will eventually be slow

The canonical cascading failure: service A calls service B; B slows to 30 seconds; A's request threads
all block on B; A stops serving anything, including endpoints that never touch B. A is now down because
of a dependency it does not need for most of its traffic.

Three mechanisms, in increasing order of sophistication:

- **Timeouts.** Non-negotiable. An unbounded outbound call is a latent outage. The platform's REST
  client defaults to a 2-second connect and 10-second read timeout, tunable *per named client* so each
  dependency's SLA can differ.
- **Retries with backoff.** For transient failures. Retrying *immediately*, or retrying an operation
  that is not idempotent, makes things worse — see §2.5.
- **Circuit breakers.** After enough failures, stop calling and fail fast. This protects the *caller's*
  threads and gives the callee room to recover. The platform ships tuned Resilience4j defaults — 3
  attempts with exponential backoff, a breaker at 50% failure over a 10-call window, a 5-second time
  limiter — as lowest-precedence configuration you can override per name.

!!! warning "A circuit breaker without telemetry is worse than none"
    An open breaker sheds traffic silently. The incident then presents as "the dependency is healthy,
    why are we failing?" The platform binds Resilience4j to Micrometer by default, so breaker state
    transitions, retry counts, and time-limiter events are visible and alertable.

Covered in [Chapter 6](../chapters/06-restclient-resilience.md).

### 2.4 Synchronous coupling has a ceiling

If A must call B must call C for every request, availability multiplies downward and latency adds up.
**Asynchronous messaging** decouples them: A publishes an event and returns; B and C consume when they
can.

The costs are real and must be understood before you reach for it:

- **Eventual consistency.** The world is briefly inconsistent. Your product decisions have to tolerate
  that.
- **At-least-once delivery.** Brokers guarantee "at least once", not "exactly once". Duplicates *will*
  arrive. Handlers must be idempotent — see §2.5.
- **Poison messages.** One message that always fails will block its partition or queue forever unless
  something bounds the retries and moves it aside. That is what a **dead-letter queue** is for, and
  forgetting to configure the dead-letter path is one of the most common production messaging defects.

The platform gives you a transport-agnostic `EventPublisher` and `@EventHandler`, bounded retry, and an
automatic DLQ that behaves *identically* across in-memory, RabbitMQ, and Kafka — so the delivery
semantics your code depends on do not change when the transport does. It also distinguishes
**integration events** (across services, over a broker) from **domain events** (in-process,
after-commit), which are different problems that are frequently conflated.

Covered in [Chapter 7](../chapters/07-messaging-events.md).

### 2.5 At-least-once delivery makes idempotency mandatory

This deserves its own section because it is where correctness is most often lost.

Given retries, message redelivery, and impatient users double-clicking, **the same logical operation
will be executed more than once.** If executing it twice charges the card twice, ships twice, or posts
the ledger entry twice, you have a high-severity, customer-visible defect that reconciliation will find
weeks later.

An operation is **idempotent** when performing it *n* times has the same effect as performing it once.
`GET`, `PUT`, and `DELETE` are idempotent by HTTP's definition. `POST` is not, which is why the industry
convention is a client-supplied `Idempotency-Key` header.

The platform provides both a declarative `@Idempotent(keyExpression, ttl)` for method-level
deduplication and an opt-in `Idempotency-Key` HTTP filter.

!!! warning "Know exactly what guarantee you have"
    The platform's current version is **reject-duplicate**: a repeat within the TTL gets a 409. It does
    **not** replay the original response the way Stripe's API does. A client that treats 409 as a hard
    failure will show the user an error for an operation that actually succeeded. Design your client
    contract around the guarantee you actually have — the platform documents this limitation plainly
    rather than letting you assume otherwise.

Covered in [Chapter 10](../chapters/10-coordination.md).

### 2.6 Running N replicas breaks anything that assumed one

Horizontal scaling is the point of the architecture, and it quietly invalidates three assumptions:

| Assumption that breaks | Symptom | Platform answer |
|---|---|---|
| "My scheduled job runs once" | It runs on all N replicas. Nightly reconciliation double-posts; customers get N emails | `@LockedSchedule` — a cluster-wide lock, so it runs once |
| "My in-memory rate limit is the limit" | The effective limit is N× what you configured | Redis-backed limiter; the in-memory provider WARNs in `prod` about exactly this |
| "My in-memory cache is the cache" | N caches with independent, diverging contents | Redis cache provider with a namespaced key convention |

Distributed locks have their own sharp edges, and the platform's implementation is worth understanding
rather than trusting blindly:

- **Non-blocking acquisition.** If the lock is held, the call *skips* and returns `Optional.empty()`
  rather than waiting. Blocking would pile threads up behind a contended lock until the pool is
  exhausted — the standard distributed-lock outage. Making "skipped" an explicit, typed outcome means
  you have to decide what it means, rather than discovering a job silently did nothing.
- **Auto-expiry.** A crashed holder must not wedge the cluster forever. Locks expire.
- **Token fencing.** Which creates a new hazard: a slow holder whose lock has already expired must not
  release a lock now legitimately owned by someone else. Both providers fence with a token.

Covered in [Chapter 10](../chapters/10-coordination.md).

### 2.7 Configuration belongs outside the artifact

The **twelve-factor** rule: the same build artifact must run in every environment, with only the
environment differing. If your `prod` behaviour requires a `prod` build, you cannot promote a tested
artifact — you can only rebuild and hope.

The platform's position:

- Everything configurable lives under `dc.platform.<capability>`, typed and documented.
- Profiles shape *ergonomics* (console logs on a laptop), never *security*.
- Secrets never travel as environment variables. `System.getenv` is banned by the platform's
  conformance rules; secrets arrive as ordinary Spring `${...}` placeholders populated by Spring Cloud
  Vault, which gives one auditable delivery path and central rotation. Environment variables leak into
  process listings, crash dumps, and child processes, and cannot be rotated without a redeploy.

Covered in [Chapter 14](../chapters/14-testing-dx.md) and
[Local vs production](../crosscutting/local-vs-production.md).

### 2.8 Security must be deny-by-default

The most common and most damaging security defect in a service estate is not a clever exploit. It is an
endpoint that shipped unauthenticated **because nobody remembered to protect it**.

Allow-by-default fails silently: the endpoint works, the tests pass, and the gap is found by an attacker
or an auditor. Deny-by-default fails loudly, in development, on the day the endpoint is written.

The platform's chain is `anyRequest().authenticated()` with a short explicit `permit-paths` list. A new
endpoint is protected the moment it exists. The extension point (`SecurityCustomizer`) is ordered
*before* `anyRequest()`, which is not an implementation detail: Spring Security forbids adding matchers
after `anyRequest()`, so this is the only ordering that works — and it structurally prevents a customizer
from issuing an accidental blanket permit.

Beyond authentication:

- **Authorization** is `@RequiresPermission("orders:read")` next to the method it protects, so it is
  reviewable in a diff. Scattered `if` statements in method bodies are easy to omit and impossible to
  audit.
- **401 versus 403** must be distinguished correctly. 401 means "authenticate"; 403 means "you may not".
  Conflating them makes clients retry authentication loops against a permanent denial.
- **Defence in depth.** Upload content types are checked by magic bytes rather than trusting the
  extension or the client-supplied header; storage keys are validated against path traversal at two
  layers; single-line text fields reject control characters to stop log injection.

Covered in [Chapter 5](../chapters/05-security-authz.md) and the
[platform security model](../crosscutting/security-model.md).

### 2.9 An API is a contract, and contracts must be published

Consumers integrate from *something*. If it is not a machine-readable specification, it is
reverse-engineering and Slack messages, and neither survives a refactor.

The platform serves an OpenAPI document with no annotations required, defaults its identity from
`spring.application.name`, and — this is the part usually missed — **appends the platform error schema
and the standard failure responses to every operation automatically**. A generated client therefore has
a real error model instead of treating every non-2xx as opaque.

Covered in [Chapter 4](../chapters/04-openapi.md).

### 2.10 If you cannot see it, you cannot run it

Three signals, three jobs:

| Signal | Answers | Cardinality |
|---|---|---|
| **Logs** | "What happened in this specific request?" | Unbounded — one per event |
| **Metrics** | "What is the rate, error ratio, and latency distribution?" | **Must stay bounded** |
| **Traces** | "Where did the time go across services?" | Sampled |

They are only powerful *together*, joined by the correlation id. The platform emits structured JSON logs
by default (free-text logs mean a per-service grok pattern and fields you cannot alert on), tags every
meter with `service`, `env`, and `platform.version` so fleet dashboards work without per-service forks,
and propagates correlation through tracing baggage.

!!! success "Best practice — instrument for the question you will be asked at 3am"
    The question is almost never "what is the average latency". It is "which requests failed, for whom,
    and what did they have in common?" That is answered by a correlation id in a structured log, which
    is why the platform treats correlation as a first-class capability rather than a logging detail.

Covered in [Chapter 3](../chapters/03-logging-observability.md) and
[Observability strategy](../crosscutting/observability-strategy.md).

### 2.11 Compliance is a runtime requirement

In a regulated environment, "who did what, when, to which resource, and did it succeed" is not a
nice-to-have — it is the evidence an investigation runs on.

Two design points are easy to get wrong:

- **Record failures, not just successes.** A failed privileged operation is the *most*
  security-relevant record there is. The platform's `@Audited` records whether the method returns or
  throws.
- **Auditing must not take the service down.** The platform's audit worker is a bounded queue drained by
  a daemon thread; under sustained overload it sheds records with a warning rather than adding latency
  or back-pressure to the request path. That is an explicit, documented trade of completeness for
  availability — and it comes with the knob to reverse it if your regime requires the opposite.

Covered in [Chapter 12](../chapters/12-audit.md).

---

## 3. The principles the platform commits to

Five, and they explain nearly every design decision in this book.

**1. A set of defaults, not a cage.** Every bean is `@ConditionalOnMissingBean`. Every capability has a
kill switch. Every third-party opinion is a lowest-precedence property source. You can always win.

**2. Three audiences, three package families, three compatibility promises.**

| Package | Audience | Promise |
|---|---|---|
| `…<cap>` | Consumers | Stable API. Binary compatibility checked by japicmp on every build |
| `…<cap>.spi` | Extenders writing providers | Stable contract, evolves more freely than the API |
| `…<cap>.internal` | Nobody | **No guarantees.** Excluded from javadoc and compatibility checks; importing it fails your build |

Without this split, everything becomes public API and no refactor is ever safe. Enforced by ArchUnit,
not by convention.

**3. Spring Boot mechanisms only.** Standard mechanisms mean standard debugging: the `--debug` condition
report, `@ImportAutoConfiguration`, `exclude=`. Boot upgrades stay cheap.

**4. A capability you do not add costs you nothing.** No transitive reach from the logging starter into
Kafka. Optional scopes and `@ConditionalOnClass` everywhere.

**5. One train, one BOM, one truth.** Every platform module shares a version. You import one BOM and
upgrade with one property change. This is the decision that kills the N×M compatibility matrix that
sinks feature-modular platforms — see [ADR-005](../../decisions/adr-005.md).

!!! note "These principles are enforced, not aspirational"
    A documented architecture decays. The platform's **dependency constitution** — api never depends
    downward, impl never depends on impl, starters never chain, nothing imports another capability's
    `.internal`, no cycles — is a merge-blocking build gate implemented as a custom Maven enforcer rule
    plus ArchUnit tests in every module. A violating change does not get reviewed and rejected; it does
    not compile. See [the constitution](../../concepts/constitution.md) and
    [ADR-007](../../decisions/adr-007.md).

---

## 4. The trade-offs the platform accepts

A book that only lists benefits is marketing. Four honest costs:

**Release-train coupling.** One version for everything means a capability cannot ship a fix
independently. The mitigation is an incubator track for chronically unstable capabilities; the
alternative — independent versioning — produces a compatibility matrix that is worse.

**Module count.** 120 Maven modules is a lot to look at. The mitigation is that you never look at them:
you consume starters, and a `new-module` Maven goal writes the boilerplate for platform contributors.

**Upgrade coordination.** Everyone moves together. The mitigation is the `upgrade-check` goal, which
diffs versions and scans your YAML for deprecated keys *before* you change any code, so an upgrade is
assessable rather than a gamble.

**Abstraction risk.** Every abstraction can become a wrapper nobody wanted. The mitigation is a hard
rule: an SPI exists **only where a second provider is genuinely plausible**, never speculatively — and
where the industry already has a good abstraction, the platform uses it rather than wrapping it. That is
why caching stays on Spring's `@Cacheable`, resilience stays on Resilience4j's own annotations, and
tracing stays on Micrometer. Fewer platform APIs is a feature.

---

## Checklist — the problems, and where they are solved

| Problem | Platform answer | Chapter |
|---|---|---|
| A request spans many services | Correlation id at highest precedence, propagated everywhere | [1](../chapters/01-core.md) |
| Every service has its own error shape | RFC 9457 on every response, including 401/403 | [2](../chapters/02-errors-validation.md) |
| Input validation is per-service folklore | Platform constraints, method validation, redacted field errors | [2](../chapters/02-errors-validation.md) |
| Free-text logs cannot be queried | Structured JSON by default, MDC lifted to fields | [3](../chapters/03-logging-observability.md) |
| Dashboards cannot span services | Common meter tags; correlation kept out of tags | [3](../chapters/03-logging-observability.md) |
| Consumers reverse-engineer the API | OpenAPI by default, with the error model attached | [4](../chapters/04-openapi.md) |
| Endpoints ship unauthenticated | Deny-by-default chain; customizers ordered before `anyRequest()` | [5](../chapters/05-security-authz.md) |
| A slow dependency takes you down | Per-client timeouts, retries, circuit breakers, all instrumented | [6](../chapters/06-restclient-resilience.md) |
| Synchronous coupling caps availability | Transport-agnostic events with bounded retry and DLQ | [7](../chapters/07-messaging-events.md) |
| Schema drift and unmanaged migrations | Flyway-presence guard that fails startup with an actionable message | [8](../chapters/08-data.md) |
| Shared cache key collisions | `CacheKeyConvention` namespacing every key | [9](../chapters/09-cache-redis.md) |
| Scheduled jobs multiply by replica count | `@LockedSchedule` with fenced, auto-expiring locks | [10](../chapters/10-coordination.md) |
| Retries cause duplicate effects | `@Idempotent` and the `Idempotency-Key` filter | [10](../chapters/10-coordination.md) |
| Blob APIs leak into domain code | Streaming `ObjectStore` behind one contract | [11](../chapters/11-storage-files.md) |
| Uploads are trusted on extension | Magic-byte content sniffing, filename sanitising, size limits | [11](../chapters/11-storage-files.md) |
| No evidence of who did what | `@Audited` capturing success *and* failure, with a degrading sink chain | [12](../chapters/12-audit.md) |
| One client can overwhelm a service | `@RateLimited` and an optional edge filter, with `Retry-After` | [13](../chapters/13-ratelimit-flags.md) |
| Releasing means deploying | Feature flags with fail-safe-off semantics | [13](../chapters/13-ratelimit-flags.md) |
| Tests need infrastructure and flake | Local-first providers, platform slices, deterministic transports | [14](../chapters/14-testing-dx.md) |

---

**Next:** [Chassis Overview](../overview.md) — the architecture, and all 24 capabilities in one sitting.

[Back to the book](../index.md)
