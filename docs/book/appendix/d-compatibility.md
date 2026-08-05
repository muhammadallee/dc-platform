# Appendix D — Version Compatibility Matrix

> The release train, what it guarantees, what each capability drags in, and how to upgrade.

!!! warning "Versions in this appendix are illustrative; the build is authoritative"
    Exact managed versions live in `build/platform-dependencies/pom.xml` and are published in the
    generated **[BOM reference](../../reference/bom.md)**. The formal compatibility promise is in
    **[reference/compatibility.md](../../reference/compatibility.md)**.

    What this appendix adds is the *shape*: what "one train" means for you, which third-party choices
    are load-bearing, and how to plan an upgrade.

---

## 1. One train, one BOM, one version

Every `platform-*` artifact shares one version and is released together. You import one BOM and
upgrade with one property change.

```xml
<properties>
  <platform.version>1.0.0</platform.version>          <!-- the ONLY version you write -->
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ae.gov.dubaicustoms.platform</groupId>
      <artifactId>platform-bom</artifactId>
      <version>${platform.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>ae.gov.dubaicustoms.platform</groupId>
    <artifactId>platform-starter-core</artifactId>     <!-- never a version -->
  </dependency>
</dependencies>
```

### Why a train rather than independent versioning

The alternative — each capability versioned independently — produces an **N×M compatibility matrix**:
does messaging 2.3 work with security 1.8 and core 3.1? At consumer scale that question is asked
constantly, answered inconsistently, and eventually stalls every upgrade.

One train answers it by construction: all the artifacts at version *X* work together, because they were
built and tested together.

**The cost is real:** a capability cannot ship a fix independently. That is
[ADR-005](../../decisions/adr-005.md)'s accepted trade, with an incubator track as the mitigation for a
chronically unstable capability.

!!! warning "If you are writing a version number for a platform artifact, something is wrong"
    The BOM manages every one. A hand-written version means you have either not imported the BOM, or
    you are overriding it — which reintroduces exactly the skew the train exists to prevent.

---

## 2. The foundation

| Component | Version | Notes |
|---|---|---|
| **Java** | **25** | `maven.compiler.release=25`. Virtual threads are used by the platform scheduler |
| **Spring Boot** | **4.1.x** | The platform's parent inherits `spring-boot-dependencies` |
| **Maven** | 3.9+ | 3.9.x specifically for the archetype pin — §6 |
| Spring Framework, Security, Data | Boot's | Never independently pinned |

!!! warning "Java 25 and Spring Boot 4 are both hard floors"
    The enforcer's `RequireJavaVersion` fails the build on an older JDK, and Spring Boot 4 carries
    breaking changes from 3.x throughout the ecosystem. Neither is negotiable per service — the
    platform was built against them.

---

## 3. Third-party libraries the platform manages

Only what Boot does **not** manage is pinned here. Everything else — Jackson, Hibernate, Tomcat,
Micrometer, the Spring modules — comes from `spring-boot-dependencies` and moves when Boot moves.

| Library | Pinned | Used by | Why the platform pins it |
|---|---|---|---|
| **springdoc-openapi** | 3.0.3 | [openapi](../chapters/04-openapi.md) | Boot does not manage it, and springdoc's 3.x line is the Boot-4-compatible one |
| **Resilience4j** | 2.4.0 (BOM) | [resilience](../chapters/06-restclient-resilience.md) | Not Boot-managed; the BOM aligns retry, breaker, time limiter, and the Micrometer binding |
| **AWS SDK v2** | 2.x (BOM) | [storage-s3](../chapters/11-storage-files.md) | Only reaches you via the S3 starter |
| **logstash-logback-encoder** | 8.x | [logging](../chapters/03-logging-observability.md) | The JSON encoder |
| **ArchUnit** | pinned | [testing](../chapters/14-testing-dx.md) | Conformance rules and the constitution gates |
| **apiguardian** | 1.1.2 | Every api/spi module | `@API(status, since)` markers |
| **RabbitMQ amqp-client** | 5.x | [messaging-rabbit](../chapters/07-messaging-events.md) | Only via the Rabbit starter |
| **prometheus-metrics** | 1.x (BOM) | [observability](../chapters/03-logging-observability.md) | |
| **OpenRewrite** | 8.x (BOM) | Migration recipes | Tooling only |

!!! success "A capability you do not add costs you nothing"
    The AWS SDK reaches your classpath **only** if you add `platform-starter-storage-s3`. The RabbitMQ
    client only via `platform-starter-messaging-rabbit`. This is not incidental — optional scopes and
    `@ConditionalOnClass` are enforced by the dependency constitution, so your CVE surface is a function
    of the starters you chose.

**Dependency convergence is enforced**: one version per third-party artifact across the whole reactor.
A transitive conflict fails the build rather than resolving silently to whichever version won.

---

## 4. What each starter drags in

Useful when triaging a CVE or explaining a dependency tree.

| Starter | Beyond Boot |
|---|---|
| `core`, `errors`, `validation` | Nothing — Boot and the JDK |
| `logging` | logstash-logback-encoder |
| `observability` | Micrometer (Boot), prometheus-metrics, OTLP exporters |
| `openapi` | springdoc |
| `security`, `security-authz` | Spring Security (Boot) |
| `restclient` | Nothing — the JDK `HttpClient` |
| `resilience` | Resilience4j |
| `messaging-inmemory` | Nothing |
| `messaging-rabbit` | Spring AMQP + amqp-client |
| `messaging-kafka` | Spring Kafka + kafka-clients |
| `events` | Nothing — Spring's `ApplicationEventPublisher` |
| `data-jpa` | Spring Data JPA + Hibernate (Boot). **You add Flyway** |
| `cache-caffeine` | Caffeine |
| `cache-redis`, `redis` | Spring Data Redis + Lettuce |
| `locking-jdbc`, `idempotency` | Spring JDBC (Boot) |
| `locking-redis` | Spring Data Redis |
| `storage-fs` | Nothing — `java.nio` |
| `storage-s3` | **AWS SDK v2** |
| `files` | Nothing — magic bytes are hand-rolled, **Tika is deliberately not a dependency** |
| `audit` | Nothing (log sink); Spring JDBC or messaging for the others |
| `ratelimit` | Caffeine (in-memory) or Spring Data Redis |
| `flags` | Nothing (in-memory); OpenFeature SDK for the adapter |
| `test` | spring-boot-starter-test, ArchUnit, json-path |

!!! note "Two deliberate non-dependencies"
    **Tika** ([11](../chapters/11-storage-files.md)) — hundreds of formats and a large transitive tree
    with its own CVE cadence, for a handful of types a government service actually accepts. Supply a
    `ContentTypeValidator` if you need its breadth.

    **aspectjweaver** ([10](../chapters/10-coordination.md)) — every platform annotation uses plain
    Spring AOP auto-proxying, so there is no load-time weaving, no agent flags, and none of the
    class-loading failure modes that come with them.

---

## 5. The compatibility promise

> Within major version **N**, an application or extension compiled against platform **N.x** runs
> unmodified on every **N.y** where y ≥ x.

| Surface | Package | Promise |
|---|---|---|
| **Public API** | `…<cap>`, config keys | Binary-compatible within a major. **Checked by japicmp** |
| **SPI** | `…<cap>.spi` | Binary-compatible within a major; **new `default` methods allowed** in minors |
| Auto-configuration class names | `…<cap>.autoconfigure` | Semi-public (used in `exclude=`); renamed only at majors, with a deprecated forwarder for one minor |
| **Internal** | `…<cap>.internal` | **No guarantee.** May change in a patch. Excluded from japicmp and javadoc |

Public types carry apiguardian `@API(status, since)` — `STABLE`, `EXPERIMENTAL`, `DEPRECATED`,
`INTERNAL` — so stability is readable from the jar before you open documentation.

!!! warning "Most SPIs are EXPERIMENTAL, and that is deliberate"
    `EventTransport`, `EventSerializer`, `LockProvider`, `RateLimiterProvider`, `FlagProvider`,
    `AuditSink`, `PermissionEvaluatorProvider`, `KeyValidator` — all EXPERIMENTAL. The platform reserves
    the right to evolve provider contracts as more backends appear.

    If you only *call* the APIs, this does not affect you. If you *write a provider*, expect to revisit
    it — and let the [TCK](../chapters/14-testing-dx.md) tell you when, which is the whole point of
    certifying against it.

!!! warning "Configuration keys are API too"
    A renamed key that "compiles fine" silently drops a production setting — a timeout or a security
    control stops applying, with no error. Renames therefore require a declared deprecation window in
    metadata, which `upgrade-check` reads.

---

## 6. Toolchain pins worth knowing

Two version constraints that will bite if you do not know them.

**`maven-archetype-plugin:3.1.2`** — 3.2.0 and later make `archetype:generate` fork a lifecycle that
fails when run outside a project on Maven 3.9.x. The [quickstart](../../quickstart.md) and the
golden-path script both pin it. → [14](../chapters/14-testing-dx.md) §4.1

**`-T1`, not `-T1C`, on constrained machines** — one thread per core spawns many forked test JVMs and
can hit an OS native-thread limit, which surfaces confusingly as a `NoClassDefFoundError` in an ArchUnit
test rather than as an obvious resource error.

---

## 7. Upgrading

### Assess first

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check \
    -Dplatform.target=1.0.0
```

Produces a console summary and `target/platform-upgrade-report.md`:

- Managed-version diffs between your current train and the target
- **Deprecated properties** found in your `application*.{yml,yaml,properties}`, read from the target
  platform jars' own metadata
- Links to the train's release notes

!!! success "Run it before *planning* the work, not before merging it"
    Its value is turning "we should upgrade sometime" into something a person can size. A property
    deprecation you learn about before touching code is a task; the same one discovered after deploying
    is an incident.

!!! warning "v1 scope: version diff and property deprecations"
    Binary-compatibility (japicmp) checking is **future work**. A `NoSuchMethodError` surprise is still
    possible on a major upgrade — read the release notes, and rely on your own test suite.

### The sequence

1. **Assess** — `upgrade-check` against the target.
2. **Read the [upgrade note](../../upgrade/0.2.0.md)** for the target train.
3. **Bump one property** — `<platform.version>`. Nothing else.
4. **Build** — `mvn -T1 clean verify`. Your `PlatformConformanceTest` and slice tests run.
5. **Fix deprecated properties** the report named.
6. **Check `/actuator/platform`** after boot — the capability set and providers should be unchanged.
7. **Promote** through environments as usual.

!!! note "Step 3 really is one property"
    That is the payoff of the train. If an upgrade requires touching more than `<platform.version>` and
    the deprecations the report named, that is worth raising — either a genuine breaking change that
    should have been in the release notes, or a service that has coupled to something it should not
    have (an `.internal` type is the usual culprit).

### Minor versus major

| | Minor (N.x → N.y) | Major (N → N+1) |
|---|---|---|
| API compatibility | Guaranteed, japicmp-checked | May break |
| Effort | A property bump plus deprecated keys | A migration guide, plus OpenRewrite recipes |
| SPI | New `default` methods possible — providers may need a look | May change |
| `.internal` | May change — do not couple to it | May change |
| Cadence | Regular | Rare, announced |

---

## 8. Compatibility with your own code

Things that can break across a **minor**, despite the promise:

| Your code | Risk | Mitigation |
|---|---|---|
| Imports a `.internal` type | **Real** — no guarantee at all | See [8](../chapters/08-data.md) §6.2 for the one case the docs push you toward, and its alternative |
| Implements an SPI | Moderate — new `default` methods are allowed | Certify against the TCK; it will tell you |
| Uses a `@Deprecated` API | Removed at the next major | Fix when the deprecation appears, not when it is removed |
| Relies on a *default* value | Low, but defaults can change | Set explicitly anything you depend on |
| Depends on a bean name for back-off | Low | Name-based back-off is documented per capability |
| Overrides a third-party version | **Real** — you bypassed convergence | Do not, unless you own the consequence |

!!! success "The single best insurance"
    Keep `PlatformConformanceTest` and your platform slice tests green. Together they exercise the
    surfaces the platform actually promises, so a real break shows up as a red build rather than as a
    production incident.

---

## 9. Checklist

**Adopting**

- [ ] Java 25, Spring Boot 4.1.x, Maven 3.9+
- [ ] `platform-bom` imported; `<platform.version>` is the only version you write
- [ ] `platform-service-parent` as your parent — build-info, docker-tag convention, banned dependencies
- [ ] No hand-written versions on platform artifacts
- [ ] No overridden third-party versions the platform manages

**Upgrading**

- [ ] `upgrade-check` run and the report read
- [ ] The upgrade note for the target train read
- [ ] `<platform.version>` bumped — and nothing else
- [ ] `mvn -T1 clean verify` green, including `PlatformConformanceTest`
- [ ] Deprecated properties fixed
- [ ] `/actuator/platform` unchanged after boot
- [ ] Provider TCKs still pass, if you maintain one

**Ongoing**

- [ ] No `.internal` imports — audit occasionally
- [ ] Deprecation warnings fixed as they appear
- [ ] The `platform.version` meter tag watched, so drift is visible before it is a project

---

**The authoritative sources:** [reference/compatibility.md](../../reference/compatibility.md) ·
[reference/bom.md](../../reference/bom.md) ·
[ADR-005 — release-train versioning](../../decisions/adr-005.md) ·
[Upgrade runbook](../../runbooks/upgrade.md)

---

**This is the end of the book.** If you have read this far, the
[learning paths](../index.md) suggest what to do with it — and
[Extending the chassis](../crosscutting/extending.md) is where to go when a default is not enough.

[Back to the book](../index.md)
