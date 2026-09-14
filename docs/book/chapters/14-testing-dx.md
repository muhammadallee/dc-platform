# Chapter 14 — Testing and Developer Experience

> **Capabilities covered:** `testing`, `dx`
>
> Test slices, TCKs, the archetype, conformance rules, and the tooling that keeps adoption cheap.
>
> **Starter:** `platform-starter-test` (test scope) ·
> **Reference:** [testing.md](../../testing.md) · [modules/dx.md](../../modules/dx.md)

---

## 1. Introduction and Business Value

This chapter is about the two things that decide whether a platform is *adopted* or merely *available*:
how easy it is to test a service built on it, and how easy it is to start one.

- **Testing** — slice annotations, fixtures, and TCKs, all of it **Docker-free by default**.
- **Developer experience** — the archetype, conformance rules, `upgrade-check`, and the golden-path
  script that verifies the whole onboarding path continuously.

### The problem

A platform can be technically excellent and still fail, in two familiar ways.

**Testing is harder than not using it.** If testing a service that uses platform messaging requires a
broker, teams either skip those tests or write them with `Thread.sleep` and watch them flake. Either
way the platform's guarantees go unverified in the services that depend on them.

**Onboarding costs a week.** If a new service means assembling a dozen starters, rediscovering every
convention, and copying `application.yml` from whichever service was nearest, then teams copy a
*service* instead — inheriting its stale conventions and its bugs, permanently.

### The guarantee that shapes everything here

> **`mvn -T1C verify` passes on a laptop with no Docker, no network beyond Maven Central, and no
> credentials.**

That constraint is why every multi-provider capability ships a local provider first: in-memory
messaging, filesystem storage, JDBC-on-H2 locking, Caffeine caching, in-memory flags and rate limiting.
Docker-backed tests exist, are tagged `@Tag("docker")`, and are excluded by default.

It sounds like a convenience. It is actually a correctness property: **a test suite that needs
infrastructure is a test suite that gets skipped**, and a platform whose guarantees are only verified
in CI is a platform whose guarantees are verified late.

### What each piece buys you

| Piece | Without it |
|---|---|
| Slice annotations | Every test boots the whole application, slowly, or mocks the platform away and tests nothing real |
| `TestEventTransport` | Messaging tests need a broker, or sleep and flake |
| `TestTokens` | Security tests need a live IdP, so they get skipped |
| Problem assertions | Error-shape assertions are string comparisons that break on rewording |
| TCKs | A provider "works" until it meets an invariant nobody wrote down |
| The archetype | New services are copied from old services, inheriting their mistakes |
| Conformance rules | Teams hand-roll what the platform already owns, silently |
| `upgrade-check` | Upgrades are all-or-nothing gambles, so services stay on old trains |
| The golden-path script | Onboarding breaks silently between releases; the next new team pays |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Slices, and why the platform ships its own

Spring Boot's slices (`@WebMvcTest`, `@DataJpaTest`) load one layer. They do not know about the
platform, so a `@WebMvcTest` gives you a controller with **no correlation filter, no problem-shaped
errors, and no security chain** — which means your test exercises a stack that does not resemble
production.

The platform's slices compose Boot's with the platform's own auto-configuration:

| Annotation | Boots | Use for |
|---|---|---|
| `@PlatformTest` | The whole platform with test defaults | Wiring and smoke tests |
| `@PlatformWebTest` | Mock web environment, `MockMvc`, **plus** the full error/validation/security stack | Controller tests |
| `@PlatformDataTest` | Boot's JPA slice on H2 **plus** platform JPA conventions | Repository and entity tests |
| `@PlatformMessagingTest` | Messaging auto-configuration **plus** a `@Primary` `TestEventTransport` | Publish and handler assertions |

All four activate the `test` profile and in-memory providers — so no Docker, no network, no
credentials. Logging stays in the deployment JSON format under `test` (only `local` switches to
console), so a test can parse and assert the events the service really emits.

!!! success "Best practice — `@PlatformWebTest` over `@WebMvcTest`, always"
    A `@WebMvcTest` asserting a 404 body proves your controller returns *something*. A
    `@PlatformWebTest` proves it returns an RFC-9457 body with the right `code` and a `correlationId`
    — the contract your clients actually consume. The second is the test worth having.

### 2.2 Deterministic asynchrony

The single most common source of flaky tests is asynchronous work asserted with a sleep:

```java
service.place("order-1");
Thread.sleep(500);              // too short -> flaky. too long -> slow. usually both.
assertThat(received).hasSize(1);
```

The platform's answer is a transport that can tell you when it is idle:

```java
transport.awaitIdle(Duration.ofSeconds(2));
assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
```

`awaitIdle` returns **as soon as** the transport has drained — so the test is both deterministic and
fast, rather than trading one for the other. The bound is a timeout, not a delay.

!!! note "The platform's own conformance rules ban `Thread.sleep` in production code"
    `noThreadSleepInProduction` is one of the `PlatformUsageRules`. It does not police tests, but the
    principle is the same: a sleep is almost always a missing synchronisation point wearing a disguise.

### 2.3 Test against the in-memory transport regardless of production

A counterintuitive recommendation that is worth arguing properly: **test messaging against the
in-memory transport even when production uses RabbitMQ.**

The objection is obvious — that is not what runs in production. The answer is that
[Chapter 7](07-messaging-events.md) §6.1 made the retry-and-DLQ behaviour **transport-agnostic on
purpose**. The delivery semantics your code depends on are implemented once, at the platform layer, so
an in-memory test genuinely rehearses the production behaviour of *your code*.

What the in-memory transport does **not** rehearse is the broker: topology declaration, connection
recovery, partition assignment, redelivery on nack. Those are the platform's and the broker's concern,
and they are certified separately by the `@Tag("docker")` TCK runs.

```
   your code's semantics       ->  in-memory tests   (fast, deterministic, always run)
   the transport's semantics   ->  TCK + Docker      (slower, opt-in, run in CI)
```

### 2.4 TCKs — a contract you can execute

A **Technology Compatibility Kit** is an abstract test class encoding a capability's contract.

```java
class MyTransportTckTest extends EventTransportTck {
    @Override protected EventTransport transport() { return new MyTransport(...); }
}
```

One override, and you inherit the whole suite. **A provider is platform-certified if and only if its
TCK passes.**

That definition is what makes the SPIs real. Without a TCK, "implements `EventTransport`" means the
method signatures compile — and says nothing about whether `send` blocks until acknowledged, whether
an unacknowledged message is redelivered, or whether `close` releases its threads. Those invariants are
prose in the interface javadoc; the TCK is the same prose, executable.

| Capability | TCK | Certified Docker-free | Under `@Tag("docker")` |
|---|---|---|---|
| messaging | `EventTransportTck` | in-memory | Kafka, RabbitMQ |
| storage | `ObjectStoreTck` | filesystem | S3 / LocalStack |
| locking | `LockProviderTck` | JDBC on H2 | Redis, PostgreSQL |
| flags | `FlagProviderTck` | in-memory | — |
| rate limiting | `RateLimiterProviderTck` | in-memory | Redis |
| idempotency | `IdempotencyStoreTck` | JDBC on H2 | Redis |
| audit | `AuditSinkTck` | log sink | JDBC, messaging |

### 2.5 Relaxable invariants, and why that is honest

Some backends genuinely cannot satisfy an invariant. The TCKs handle this with a documented `protected
boolean` hook a subclass overrides, rather than by weakening the contract for everyone.

The worked example from [Chapter 10](10-coordination.md) §2.5: `LockProviderTck` relaxes strict
*concurrent* exclusion on H2, whose engine cannot reliably serialise reclaim-then-insert across
connections. Sequential exclusion and fencing still prove mutual exclusion Docker-free; strict
concurrency is certified against PostgreSQL under `@Tag("docker")`.

The alternative would have been a green build that certifies nothing, or a Docker requirement on every
developer's machine.

!!! success "Best practice — this is the shape to copy for any guarantee you cannot fully verify"
    Name the invariant. Verify what you can, cheaply and always. Verify the rest expensively and
    explicitly. **Make the gap visible in code** — a named boolean hook someone must deliberately flip —
    rather than in a comment nobody reads.

### 2.6 Conformance rules — teaching, enforced

`PlatformUsageRules` is a set of ArchUnit rules a *service* runs over its **own** production classes,
through the generated `PlatformConformanceTest`:

| Rule | Bans | Because |
|---|---|---|
| `noDirectMessagingInfrastructure` | `KafkaTemplate`, `RabbitTemplate`, listener containers | Use `EventPublisher` / `@EventHandler` ([Ch. 7](07-messaging-events.md)) |
| `noHandRolledExceptionHandler` | `@RestControllerAdvice` shaping errors | Throw `PlatformException` subtypes ([Ch. 2](02-errors-validation.md)) |
| `noResponseEntityExceptionHandlerSubclass` | Subclassing Spring's handler | Same — it silently disables the platform's |
| `noSystemGetenv` | `System.getenv(...)` | Spring `${...}` placeholders, populated by Vault |
| `noDirectObjectMapperInstantiation` | `new ObjectMapper()` | Use the configured bean, or serialisation diverges |
| `noThreadSleepInProduction` | `Thread.sleep` | Almost always a missing synchronisation point |

Each failure message **names the platform alternative**. That is the design: the rules are not there to
forbid, they are there to teach at the moment someone reaches for the wrong tool — which is the only
moment the lesson lands.

!!! note "Rules match by fully-qualified name, so they cost nothing when unused"
    A service with neither Kafka nor Rabbit on its classpath still compiles and runs
    `noDirectMessagingInfrastructure`. The rule simply finds nothing.

!!! warning "The escape hatch is deleting the test — and that is the point"
    A team with a documented reason may delete `PlatformConformanceTest`. There is no annotation, no
    suppression list, no `@SuppressPlatformRule`. Deleting a whole test is **visible in a diff and
    requires a sentence in a pull request**, whereas a suppression annotation accumulates silently
    across a codebase until nobody knows which rules are live.

### 2.7 The golden path as an executable contract

`tooling/scripts/golden-path.sh` runs the entire onboarding path end to end:

```
   install the platform into an isolated Maven repository
        -> reject invalid feature input
        -> for all 8 feature combinations, outside the checkout:
             generate -> mvn verify (every expected test class ran, none skipped)
             -> java -jar -> readiness, auth (401/200 via a loopback JWKS), capability report, JSON logs
        -> for data,restclient: spring-boot:run, the local profile, prod fail-fast and prod fixture boots
        -> stop every process it started
   ... and FAIL when one service takes more than 10 minutes to generate, verify and boot.
```

That last clause is the interesting one. **The developer experience has a budget, and the budget is
tested.** Not "we aim for a fast onboarding" in a wiki, but a script that goes red when a service takes
more than ten minutes. It runs in CI as the `generator-gate` job.

Without it, the onboarding path breaks silently between releases — an archetype property renamed, a
starter that no longer resolves — and the next new team discovers it, at the worst possible moment for
their first impression of the platform.

---

## 3. Feature Reference

### 3.1 The test starter

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

Brings the platform test kit, `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito, spring-test), the
recording messaging transport, and `json-path`. **One dependency**, matching the platform's
one-starter-per-capability rule.

### 3.2 Slice annotations

| Annotation | Package | Notes |
|---|---|---|
| `@PlatformTest` | `…test.junit` | Full platform, test defaults |
| `@PlatformWebTest` | `…test.junit` | `MockMvc` + errors + validation + security |
| `@PlatformDataTest` | `…test.junit` | JPA slice on H2 + platform conventions |
| `@PlatformMessagingTest` | `…test.junit` | `@Primary` `TestEventTransport` |

!!! note "`@PlatformDataTest` needs the data capability on the test classpath"
    It is declared **optional** on `platform-test-api`, so a non-JPA service is not forced to pull
    Hibernate in. If the annotation does not resolve, that is why.

### 3.3 Fixtures

| Fixture | Purpose |
|---|---|
| `TestTokens` | JWT identities for `MockMvc` — `TestTokens.user("alice").roles("VIEWER").jwt()` |
| `PlatformAssertions.assertThatProblem(...)` | AssertJ over RFC-9457 bodies, including `code` and `correlationId` |
| `ProblemDetailAssert` | The fluent assertion type behind it |
| `TestEventTransport` | Records published events; `awaitIdle(Duration)` |
| `EventsAssert.assertThatEvents(...)` | Fluent assertions over recorded events |
| `Containers` | Shared, lazily-started singleton Testcontainers |
| `DockerAvailable.check()` | Guard for `@Tag("docker")` tests |

### 3.4 Conformance rules

`ae.gov.dubaicustoms.platform.test.arch.PlatformUsageRules.all()` returns fresh, independent
`ArchRule` instances. The six rules are listed in §2.6.

### 3.5 Developer-experience tooling

| Tool | What it does |
|---|---|
| **Service archetype** | Generates a running service: parent, conformance test, slice tests, profile-aware `application.yml`, `CLAUDE.md`, `AGENTS.md`, `.mcp.json`, `catalog-info.yaml` |
| **`new-module` goal** | Scaffolds a constitution-compliant platform module (contributors, not consumers) |
| **`upgrade-check` goal** | Version diff against a target BOM + a deprecated-property scan of your YAML |
| **`golden-path.sh`** | The end-to-end onboarding contract, with a 10-minute SLA |
| **`smoke-matrix.sh`** | Golden path across capability combinations |
| **MCP server + Claude Skill** | Grounds AI-assisted development in a build-time `platform-index.json` |

**Archetype properties**

| Property | Default | Meaning |
|---|---|---|
| `platformVersion` | the archetype's own version | The platform version to build against |
| `features` | `none` | `none` or a comma-separated subset of `messaging`, `data`, `restclient` — adds starters, sample code **and** its tests; anything else fails generation |

Feature samples are conditional: when a feature is off its sample source renders empty (Velocity
`#if`) and its starter is omitted — so a `basic` project has no dead sample files.

### 3.6 Build parents

| Parent | For | Provides |
|---|---|---|
| `platform-parent` | Platform modules | Compiler, surefire/failsafe (`@Tag("docker")` excluded by default), jacoco, checkstyle, japicmp, enforcer |
| `platform-service-parent` | **Consumer services** | The same docker-tag convention, `build-info` generation, a jacoco report **with no imposed threshold**, and banned-dependency rules |

!!! success "Note what `platform-service-parent` deliberately does not impose"
    A coverage **threshold**. It generates the report and leaves the number to the team. The platform
    has an opinion about *habits* — that the report exists, that docker tests are tagged, that
    build-info is generated — and no opinion about a percentage that would be gamed within a sprint.

    `build-info` matters more than it sounds: it feeds `/actuator/info` and the
    [OpenAPI](04-openapi.md) document's version. A service without it reports `dev` in production.

---

## 4. How-to Guide

### 4.1 Generate a service

```bash
mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -B \
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform \
  -DarchetypeArtifactId=platform-service-archetype \
  -DarchetypeVersion=<platform-version> \
  -DgroupId=com.acme.orders -DartifactId=orders-service -Dpackage=com.acme.orders \
  -Dfeatures=data,restclient
```

!!! note "Plugin version and PowerShell"
    `maven-archetype-plugin:3.1.2` is the documented, gate-tested version (3.4.0 also generates
    project-less). On Windows PowerShell quote every `-D` argument: unquoted `-DgroupId=com.acme.orders`
    is split at the first dot, which Maven reports as "The goal you specified requires a project".

Then:

```bash
cd orders-service
mvn verify              # platform slices + PlatformConformanceTest, no Docker
mvn spring-boot:run
```

### 4.2 Test a controller

```java
@PlatformWebTest
class OrderControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void unauthenticatedIsRejectedAsAProblem() throws Exception {
        mvc.perform(get("/orders/42"))
           .andExpect(status().isUnauthorized())
           .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void unknownOrderIsA404Problem() throws Exception {
        var response = mvc.perform(get("/orders/nope")
                        .with(TestTokens.user("alice").roles("VIEWER").jwt()))
                .andReturn().getResponse();

        assertThatProblem(response).hasStatus(404).hasCode("DC-ORDER-0404");
    }
}
```

Note what this covers that a `@WebMvcTest` would not: the security chain produced the 401 as a problem
body, and the 404 carries the platform's `code` extension.

!!! success "Best practice — assert on `code`, never on `detail`"
    `hasCode("DC-ORDER-0404")` survives every rewording of the message. An assertion on the message
    text breaks the first time someone improves the wording — and then gets "fixed" by copying the new
    text, which teaches everyone that the test asserts nothing.

### 4.3 Test messaging without a broker

```java
@PlatformMessagingTest
class OrderEventsTest {

    @Autowired TestEventTransport transport;
    @Autowired OrderService service;

    @Test
    void placingAnOrderPublishesTheEvent() {
        service.place("order-1");

        transport.awaitIdle(Duration.ofSeconds(2));
        assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
    }
}
```

!!! warning "Assert on the `eventType`, not the payload class"
    `withType("OrderPlaced")` asserts the **wire contract** — the `@EventType` value consumers bind to.
    Asserting on the Java class name would pass even after a rename that breaks every consumer, which is
    precisely the failure [Chapter 7](07-messaging-events.md) §2.3 exists to prevent.

### 4.4 Test the repository layer

```java
@PlatformDataTest
class OrderRepositoryTest {

    @Autowired OrderRepository repository;

    @Test
    void auditColumnsArePopulated() {
        OrderEntity saved = repository.save(new OrderEntity("REF-1"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");   // no authenticated request
    }
}
```

H2, platform JPA conventions, no Docker. The `"system"` auditor is
[Chapter 8](08-data.md) §2.3's no-security branch.

### 4.5 Write a Docker-tagged test

```java
@PlatformTest
@Tag("docker")
class OrdersRabbitIT {

    @BeforeAll
    static void docker() {
        assumeTrue(DockerAvailable.check());
    }

    @TestConfiguration
    static class Infra {
        @Bean @ServiceConnection RabbitMQContainer rabbit() { return Containers.rabbit(); }
    }
}
```

Two guards, deliberately belt-and-braces: the **tag** excludes it from the default build, and
`assumeTrue(DockerAvailable.check())` makes it *skip* rather than *fail* if someone runs `-Pdocker`
without a daemon.

```bash
mvn verify              # excluded
mvn -Pdocker verify     # included, if Docker is running
```

### 4.6 Certify a provider against its TCK

```java
class EncryptingObjectStoreTckTest extends ObjectStoreTck {

    @Override
    protected ObjectStore store() {
        return new EncryptingFsObjectStore(new FsObjectStore(tempRoot), key);
    }
}
```

That is the whole certification. [`example-extension-provider`](../../examples.md) does exactly this,
and pairs it with a back-off test asserting the platform's default stepped aside — the two tests
together are what "platform-certified provider" means.

### 4.7 Assess an upgrade before doing it

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check \
    -Dplatform.target=1.0.0
```

Produces a console summary and `target/platform-upgrade-report.md`: managed-version diffs, plus a scan
of your `application*.{yml,yaml,properties}` for keys **deprecated in the target version** — read from
the target platform jars' own metadata.

!!! success "Best practice — run it before you plan the work, not before you merge"
    Its value is turning "we should upgrade sometime" into a report someone can size. A property
    deprecation you learn about *before* touching code is a task; the same one discovered after
    deploying is an incident. Note the v1 scope: version diff and property deprecations. Binary
    compatibility is future work, so a `japicmp` surprise is still possible.

### 4.8 Run the conformance rules

The archetype generates this. If you are retrofitting an existing service, add it:

```java
class PlatformConformanceTest {

    @Test
    void servicePrefersPlatformApis() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.acme.orders");

        for (ArchRule rule : PlatformUsageRules.all()) {
            rule.check(classes);
        }
    }
}
```

!!! tip "Expect it to fail on a retrofit, and read the failures as a to-do list"
    Each message names the platform alternative. Working through them *is* the migration — the rules
    are a checklist for adopting the platform, not just a gate for staying on it.

---

## 5. Operations and Management Guide

### 5.1 Running the suites

| Command | Runs |
|---|---|
| `mvn -T1 clean verify` | Everything Docker-free. The default contract |
| `mvn -Pdocker verify` | Adds `@Tag("docker")` certifications |
| `mvn -pl <module> -am verify` | One module and its dependencies |
| `./tooling/scripts/golden-path.sh` | The onboarding contract, with its SLA |
| `./tooling/scripts/smoke-matrix.sh` | Golden path across capability combinations |

!!! note "Parallelism has a machine-specific ceiling"
    `-T1C` (one thread per core) spawns many forked test JVMs and can hit an OS native-thread limit on
    constrained machines — which surfaces confusingly as a `NoClassDefFoundError` in an ArchUnit test
    rather than as an obvious resource error. If you see that, drop to `-T1`.

### 5.2 What to watch in CI

| Signal | Alert when |
|---|---|
| Golden-path duration | Approaching the 10-minute SLA — DX is degrading before it breaks |
| Docker-profile TCK results | A provider certification fails; the SPI contract regressed |
| Conformance-test deletions | A `PlatformConformanceTest` disappears from a service — §2.6 |
| Coverage trend | Falling, on services where the team set a target |
| `upgrade-check` reports | Services drifting several trains behind |
| Flaky test rate | Rising — usually a sleep, or a shared singleton container leaking state |

!!! tip "Alert on golden-path *duration*, not just failure"
    It fails past ten minutes. A trend from four minutes to eight is the interesting signal: onboarding
    is getting slower and nobody has noticed, because it is still green. Treat the SLA as a budget with
    a burn rate.

### 5.3 Troubleshooting

**A slice annotation does not resolve.** `@PlatformDataTest` needs the data capability on the test
classpath (§3.2). The others need only the test starter.

**A test passes alone and fails in a suite.** Usually shared state: a singleton `Containers` instance
carrying data between tests, or an in-memory provider not reset. The in-memory providers are
context-scoped, so `@DirtiesContext` is the blunt fix and cleaning up in `@AfterEach` is the good one.

**Messaging assertions are flaky.** Something is sleeping instead of using `awaitIdle`, or `awaitIdle`
is being called with too short a timeout. It is a timeout, not a delay — raising it costs nothing when
the transport drains quickly.

**A Docker test fails in CI and passes locally.** The `assumeTrue(DockerAvailable.check())` guard makes
it *skip* locally without Docker — so "passes locally" may mean "never ran". Check for a skip rather
than a pass.

**`PlatformConformanceTest` fails after adding a library.** Working as intended. Read which rule and
which class; the message names the platform alternative. If the rule is genuinely wrong for your case,
that is a conversation with the platform team, not a suppression.

**The archetype fails with "requires a project".** On PowerShell, quote the `-D` arguments (§4.1).

**Generation fails with "unsupported features value".** Use `none` or a comma-separated subset of
`messaging`, `data`, `restclient`, with no spaces.

**`/actuator/info` is empty and OpenAPI says `dev`.** `build-info` is not being generated — the service
is not inheriting `platform-service-parent`, or the goal was disabled.

### 5.4 Scaling the build

- **Slices are much cheaper than full contexts.** Spring caches contexts by configuration, so tests
  sharing an annotation share a context. **Adding `@MockitoBean` to one test creates a new context** —
  a common and invisible cause of slow suites.
- **`@DirtiesContext` discards the cached context**, forcing a rebuild for the next test. Use it only
  when you must.
- **Docker tests dominate wall time.** Keeping them tagged and out of the default build is what keeps
  the inner loop fast.
- **`Containers` singletons are shared and lazily started**, so a `-Pdocker` run starts each container
  once rather than per class.

### 5.5 Testing and security

| Consideration | Detail |
|---|---|
| `TestTokens` mints unsigned test identities | Test-scope only. It must never reach a production classpath |
| The archetype's placeholder issuer | Boots offline and validates nothing. Replace before any shared environment ([Ch. 5](05-security-authz.md) §5.1) |
| Test fixtures with realistic data | A fixture built from a production export is a data leak in your repository |
| `noSystemGetenv` | Enforces the Vault placeholder path — the rule exists because environment variables leak into process listings and crash dumps |
| Docker tests with real credentials | Testcontainers should use ephemeral credentials, never shared ones |

---

## 6. Deep Dive

### 6.1 Why slices exist rather than "just use `@SpringBootTest`"

Two reasons, and the second is the one people miss.

**Speed** is the obvious one: fewer beans, faster context.

**Isolation** is the important one. `@SpringBootTest` loads *your whole application*, so a controller
test fails when an unrelated `@Scheduled` bean cannot reach a database. That is a test that fails for a
reason unrelated to what it asserts — and after it happens twice, people start mocking things out
until the test proves nothing.

A slice fails only for reasons in its layer. That is what makes a failure informative.

!!! warning "The context cache is the hidden variable in build times"
    Spring caches contexts keyed by their configuration. Every distinct combination of annotations,
    properties, and `@MockitoBean` declarations is a **new context**. A suite with fifty distinct
    configurations builds fifty contexts. If your build is slow, count the distinct configurations
    before optimising anything else.

### 6.2 Why the TCK is an abstract class, not an annotation

An annotation-driven TCK (`@VerifyEventTransport`) would need an extension, a discovery mechanism, and
a way to pass the implementation in. An abstract class needs one `extends` and one override.

More importantly, **the abstract class is readable**. A provider author opens `EventTransportTck` and
sees every invariant they must satisfy, as ordinary test methods with ordinary names. There is no
framework between the contract and its reader.

The `protected boolean` relaxation hooks (§2.5) follow from the same choice: a subclass overriding a
method is the most obvious extension point Java has.

### 6.3 Conformance rules run over *your* code, and the inversion that makes them work

Most architecture rules run over the codebase that ships them. These ship in `platform-test-api` and
run over **the consuming service's** production classes.

That inversion is what makes them useful. The platform cannot stop you writing
`new KafkaTemplate(...)` — but it can hand you a test that notices, in your own build, with a message
naming `EventPublisher`.

It also explains why the rules are *usage* rules rather than *architecture* rules. They do not enforce
your layering or your package structure — that is your business. They enforce exactly one thing: **that
you use the platform rather than reimplementing it.**

!!! note "Contrast with `PlatformArchRules`"
    The platform's own modules run a *different* rule set enforcing the
    [dependency constitution](../../concepts/constitution.md). Two rule sets, two audiences: one keeps
    the platform honest, one keeps consumers on the rails.

### 6.4 Why `platform-service-parent` imposes no coverage threshold

The platform has strong opinions about many things. Coverage percentage is deliberately not one.

A threshold is trivially gamed — tests that execute code without asserting anything satisfy it
perfectly — and the number that is right for a payments service is wrong for an internal reporting
tool. Imposing one fleet-wide would produce compliance rather than testing.

What the parent *does* impose is the **habit**: the report is generated, so a team that wants a
threshold can set one and a reviewer can always see the number. Provide the instrument, let the team
choose the reading.

This is the same instinct as `default-destination-prefix` being a convention rather than an
enforcement ([Chapter 7](07-messaging-events.md) §6.5): the platform defaults and documents, and
polices only what has a *correctness* consequence.

### 6.5 The escape hatch is a deletion, on purpose

Most rule frameworks offer suppression: an annotation, a baseline file, an exclusion list. The platform
offers deleting the test.

Suppressions accumulate. A `@SuppressWarnings("platform")` on one class becomes twelve within a year,
each individually justified and collectively meaning nobody knows which rules are live. The baseline
file grows and is never revisited.

Deleting `PlatformConformanceTest` is **one visible act, in one diff, needing one sentence in a pull
request**. It is all-or-nothing, which is a feature: partial conformance that nobody can see is worse
than a documented decision to opt out.

!!! success "Best practice worth stealing"
    When you design an escape hatch, ask whether it accumulates. A hatch that is easy to use twice is a
    hatch that will be used two hundred times. Make the exceptional path *visible* rather than
    *convenient*.

### 6.6 Why the golden path has a time budget at all

Ten minutes is not a performance target. It is a **product decision about the developer experience**,
encoded as a test.

The insight is that onboarding friction is invisible to the platform team, who never onboard. It is
felt entirely by people who are new, once, and who have no standing to complain about it. Left
unmeasured, it degrades monotonically — a slower archetype here, an extra manual step there — and
nobody notices until adoption stalls for reasons nobody can name.

A timed script converts that into a build failure. It is the same move as the broken-link gate and the
compile-checked snippets that protect [this very book](../index.md): **if a quality matters, make it a
gate.**

### 6.7 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| `@WebMvcTest` instead of `@PlatformWebTest` | It is the Boot default people know | No correlation, no problem bodies, no security. Tests a stack that is not production |
| `Thread.sleep` in a messaging test | It is the obvious thing | `awaitIdle` — deterministic *and* faster |
| Asserting on `detail` text | It reads naturally | Breaks on every rewording. Assert on `code` |
| Asserting on a payload class name | It is what you see in the IDE | Passes after a rename that breaks consumers. Assert `eventType` |
| `@MockitoBean` scattered widely | Each looks harmless | Each creates a new cached context. Slow suites |
| Assuming a Docker test ran locally | It passed | `assumeTrue` **skips** without Docker. Check for skips |
| Suppressing a conformance rule | It blocks a merge | There is no suppression. Fix it, or delete the test deliberately |
| Skipping `upgrade-check` | The upgrade "looks small" | A deprecated property silently stops applying |
| Using the archetype's placeholder issuer beyond local | It boots fine | It validates nothing |
| Newer `maven-archetype-plugin` | It is the latest | 3.2.0+ fails project-less on Maven 3.9.x |
| Expecting `platform-service-parent` to enforce coverage | Other gates are enforced | It generates the report; the threshold is yours |

---

## 7. Exercises and Hands-on Labs

Starting point: an empty directory and the platform installed locally
(`mvn -T1 clean install`).

### Lab 1 — Basic: generate, build, run, probe

**Goal.** Experience the onboarding path, and time it.

**Steps.**

1. Generate a service with `-Dfeatures=messaging,data`. **Time it.**
2. Inspect what was generated — list every file and say what each is for.
3. `mvn verify`. What tests ran? Find `PlatformConformanceTest` in the output.
4. `mvn spring-boot:run`, then probe `/actuator/health` and `/actuator/platform`.
5. Generate a second service with no `features`. Diff the two. What changed besides the POM?
6. Add up your total elapsed time from step 1 to step 4. How does it compare to the 10-minute SLA?

**Expected outcome.** A running, tested, conformant service well inside the budget. Step 5 shows the
Velocity `#if` conditionals — the `basic` project has **no empty sample files**, which is the detail
that separates a good archetype from a template.

**Hints.**

- Read the generated `CLAUDE.md` and `application.yml` closely; both are teaching material.
- Step 6 is the exercise. If it took you twenty minutes, work out where the time went — that is exactly
  what the golden-path script measures.

**How to verify.** `./tooling/scripts/golden-path.sh` does all of this automatically. Run it and
compare its time with yours.

### Lab 2 — Intermediate: the four slices, and one flaky test

**Goal.** Use each slice for what it is for, and fix a flake properly.

**Steps.**

1. Write a `@PlatformWebTest` asserting an unauthenticated request gets a 401 **problem body**.
2. Rewrite it as a plain `@WebMvcTest`. What breaks, and what does that tell you?
3. Write a `@PlatformDataTest` asserting audit columns are populated. What is `created_by`, and why?
4. Write a `@PlatformMessagingTest` using `Thread.sleep(100)`. Run it 20 times. Any failures?
5. Replace the sleep with `awaitIdle`. Run 20 times again. Compare **both** reliability and total time.
6. Assert on the payload class name, then on `eventType`. Rename the record. Which test caught it?
7. Add `@MockitoBean` to one test. Count contexts created in the log before and after.

**Expected outcome.** Step 2 loses the platform stack entirely. Step 4 flakes or is slow — often both.
**Step 5 is faster *and* reliable**, which is the point: this is not a trade-off. Step 6 shows the class
name assertion passing after a breaking rename.

**Hints.**

- `logging.level.org.springframework.test.context.cache=DEBUG` makes step 7 visible.
- For step 4, `mvn test -Dsurefire.rerunFailingTestsCount=0` and a loop, or an IDE repeat runner.

**How to verify.** All four slice tests green, the messaging test passing 20/20, and a note on how much
time `awaitIdle` saved.

### Lab 3 — Advanced: certify a provider, then break the contract

**Goal.** Use a TCK the way a provider author does, and find out what it actually enforces.

**Steps.**

1. Write a trivial in-memory `ObjectStore`.
2. Extend `ObjectStoreTck` against it. Run. Which invariants fail first?
3. Fix them one at a time, noting each invariant you had not thought of.
4. Now **deliberately break** one — make `delete` always return `true`. Does the TCK catch it?
5. Look at a relaxable hook on `LockProviderTck`. What does it relax, and what still holds when relaxed?
6. Write a back-off test proving the platform's default `ObjectStore` stepped aside when yours is
   present. (See [`example-extension-provider`](../../examples.md).)
7. Add `PlatformUsageRules` to a service and deliberately violate two rules — `new ObjectMapper()` and
   `System.getenv`. Read both messages. Do they tell you what to do instead?
8. Delete `PlatformConformanceTest`. Write the pull-request sentence you would need to justify it.

**Expected outcome.** Step 2 fails on invariants you did not consider — usually `delete` semantics for
an absent object, and `list` returning empty rather than null. Step 4 is caught. **Step 5 is the
lesson**: a relaxed invariant is *named*, and the remaining ones still prove the property. Step 8
should feel uncomfortable to write, which is the design working (§6.5).

**Hints.**

- The TCK is `platform-tck-storage`. Read the class before running it — it is the contract in
  executable form.
- Step 7's messages are the teaching mechanism; judge them as documentation.

**How to verify.** Your provider passes the full `ObjectStoreTck` — the platform's own definition of
certified — and your back-off test proves the default stood down.

---

## 8. Checklist / Quick Reference

**Add it**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

**Slices**

| Annotation | Use for |
|---|---|
| `@PlatformTest` | Wiring and smoke tests |
| `@PlatformWebTest` | Controllers — with errors, validation, security |
| `@PlatformDataTest` | Repositories on H2 |
| `@PlatformMessagingTest` | Publish and handler assertions |

**Fixtures**

```java
TestTokens.user("alice").roles("VIEWER").jwt()
assertThatProblem(response).hasStatus(404).hasCode("DC-ORDER-0404");
transport.awaitIdle(Duration.ofSeconds(2));
assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
assumeTrue(DockerAvailable.check());
```

**Commands**

```bash
mvn -T1 clean verify                  # everything, Docker-free
mvn -Pdocker verify                   # adds @Tag("docker") certifications
./tooling/scripts/golden-path.sh      # onboarding contract, 10-minute SLA
mvn …:upgrade-check -Dplatform.target=1.0.0
```

**Conformance rules**

`noDirectMessagingInfrastructure` · `noHandRolledExceptionHandler` ·
`noResponseEntityExceptionHandlerSubclass` · `noSystemGetenv` ·
`noDirectObjectMapperInstantiation` · `noThreadSleepInProduction`

No suppression mechanism. The escape hatch is deleting the test — visibly.

**TCK certification**

```java
class MyStoreTckTest extends ObjectStoreTck {
    @Override protected ObjectStore store() { return new MyStore(...); }
}
```

Certified **iff** the TCK passes.

**Rules of thumb**

- `@PlatformWebTest`, never `@WebMvcTest` — the platform stack is the thing worth testing.
- `awaitIdle`, never `Thread.sleep`. It is deterministic *and* faster.
- Assert on `code` and `eventType` — the contract — not on `detail` or class names.
- Test messaging in-memory regardless of your production transport.
- Two guards on Docker tests: the tag **and** `assumeTrue(DockerAvailable.check())`.
- "Passed locally" may mean "skipped locally". Check for skips.
- Every `@MockitoBean` combination is a new cached context.
- Run `upgrade-check` before planning an upgrade, not before merging one.
- Generate with `maven-archetype-plugin:3.1.2`; quote `-D` arguments on PowerShell.
- Replace the archetype's placeholder issuer before any shared environment.
- If a conformance rule fails, read the message — it names the alternative.

---

**Next:** the cross-cutting chapters, starting with
[Patterns and Anti-patterns](../crosscutting/patterns.md) — the shapes that recur across every
capability you have now read about.

**Reference:** [testing.md](../../testing.md) · [modules/dx.md](../../modules/dx.md) ·
[examples](../../examples.md) · [feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
