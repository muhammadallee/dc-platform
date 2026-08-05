# Local Development vs Production

> The local-first guarantee, what changes in production, and how to keep the gap from surprising you.

Every chapter in this book has a version of the same warning: *the local provider behaves differently
from the production one*. This chapter collects them into one place, so you can review the whole gap at
once rather than rediscovering it capability by capability.

---

## 1. The guarantee

> **`mvn -T1 clean verify` passes on a laptop with no Docker, no network beyond Maven Central, and no
> credentials.**

That is a non-negotiable constraint on the platform, and it is why every multi-provider capability
ships a local provider first.

It sounds like developer convenience. It is really a **correctness** property, for three reasons:

- **A test suite that needs infrastructure is a test suite that gets skipped.** Guarantees verified
  only in CI are guarantees verified late.
- **A fast inner loop is a used inner loop.** A minute-long build gets run; a ten-minute one gets
  batched, and defects are found further from their cause.
- **Onboarding is bounded.** Clone, build, run — no credential request, no infrastructure ticket. The
  [golden-path script](../chapters/14-testing-dx.md) tests that path against a 10-minute SLA.

Docker-backed tests exist for the real backends. They are tagged `@Tag("docker")`, excluded by default,
and run with `mvn -Pdocker verify`.

---

## 2. What changes, capability by capability

The complete table. **Bold** rows are the gaps that have caused real production surprises.

| Capability | Local | Production | The gap that matters |
|---|---|---|---|
| Logging | Console (`local` profile) | JSON | Format only — same fields |
| Observability | Prometheus on, **OTLP off** | OTLP on | **No traces at all unless you enable it** |
| Security | Archetype placeholder issuer | Real IdP | **The placeholder validates nothing** |
| Messaging | in-memory | RabbitMQ | No broker topology, no redelivery, no partitions |
| **Cache** | Caffeine, per-instance | Redis, shared | **Invalidation is instance-local with Caffeine** |
| **Rate limiting** | in-memory, per-JVM | Redis, cluster-wide | **Effective limit is N× the configured one** |
| **Storage** | filesystem, `${java.io.tmpdir}` | S3 | **Per-instance, and a temp directory** |
| Locking | JDBC on H2 | JDBC on PostgreSQL, or Redis | H2 relaxes strict concurrent exclusion |
| Idempotency | JDBC on H2 | JDBC or Redis | Same store semantics |
| Data | H2 | PostgreSQL | Dialect differences; `IDENTITY` batching |
| Flags | in-memory, from properties | OpenFeature | Per-instance, lost on restart |
| **Audit** | log sink | JDBC or messaging | **A silent fallback looks like compliance** |
| Resilience | Same tuning | Same tuning | None — it is configuration |
| Errors, validation, core, OpenAPI | Identical | Identical | None |

---

## 3. The five gaps worth reviewing before every deployment

### 3.1 OTLP is off by default

The single most commonly missed setting in the platform. `dc.platform.observability.otlp.enabled`
defaults to `false` so a laptop never dials a collector — which means a service deployed with untouched
defaults has logs, probes, and Prometheus metrics, and **no traces**.

Put it in the deployment template, not in each service's `application.yml`. See
[Observability strategy](observability-strategy.md) §4.

### 3.2 The in-memory rate limiter multiplies your limit

Three replicas with `permits = 100` gives an effective cluster limit of **300**, because each JVM counts
independently. The platform logs a WARN in the `prod` profile saying exactly this — because the failure
is otherwise silent: your quota is simply more permissive than configured, and nothing fails.

Use the Redis provider for a genuine cluster-wide limit.

### 3.3 Caffeine invalidation is instance-local

With three replicas, an eviction on instance 1 leaves instances 2 and 3 serving stale data until their
own entries expire.

This works perfectly in development, where there is one instance. If correctness depends on
invalidation being seen fleet-wide, Caffeine is the wrong provider regardless of how well it performs.

### 3.4 `storage-fs` with multiple replicas is broken

Uploads land on whichever instance served the request; downloads fail from the others. **Roughly a
two-in-three failure rate with three replicas**, and it works flawlessly with one.

Compounding it: `fs.root` defaults to `${java.io.tmpdir}/dc-storage`, which is not durable and is often
cleared on reboot.

### 3.5 The audit sink falls back silently

The chain is messaging → JDBC → log. A service in an environment missing its intended sink falls back
to the log sink and keeps working — so a regime requiring database-backed audit is quietly unmet.

[Chapter 12](../chapters/12-audit.md) §5.2 shows asserting the sink at startup, which converts a silent
compliance gap into a deployment failure.

!!! warning "The pattern across all five"
    Each works *better* locally than it will in production, and each fails **silently** rather than
    loudly. That combination is what makes them worth a deliberate pre-deployment review rather than
    trusting that a green build means a correct deployment.

---

## 4. Profiles, and their one legitimate job

The platform uses profiles for exactly one thing: **making local development pleasant without changing
production behaviour**.

| Profile | Effect |
|---|---|
| `local` | Console logging (unless `format` is set explicitly) |
| `test` | Activated by every platform slice — console logging, in-memory providers |
| `prod` | Triggers advisory warnings, such as the in-memory rate limiter's |

!!! warning "No profile changes a security decision — by construction"
    `mode: disabled` requires an explicit property. There is no profile, no environment variable
    convention, and no convenience flag that disables platform security.

    This defends against a documented breach pattern: a `local` profile reaching production through a
    misconfigured environment variable and taking authentication with it. The platform makes it
    impossible to express.

!!! note "The `local` heuristic tests membership, not exclusivity"
    `--spring.profiles.active=local,integration` gives console logs, because the check is "is `local`
    among the active profiles". Usually what you want; occasionally a surprise in CI. Set
    `dc.platform.logging.format` explicitly there.

---

## 5. A realistic profile layout

```yaml
# application.yml — always applies
spring:
  application:
    name: orders-service          # the service log field AND meter tag

dc:
  platform:
    files:
      allowed-types: [application/pdf]     # narrowed to what the business accepts
      max-file-size: 5MB

---
spring:
  config:
    activate:
      on-profile: local
dc:
  platform:
    errors:
      include-stacktrace: true             # ONLY here
    storage:
      fs:
        root: ./target/storage             # not the temp directory, even locally

---
spring:
  config:
    activate:
      on-profile: prod
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${IDP_ISSUER_URI}    # a Vault-populated placeholder
dc:
  platform:
    observability:
      otlp:
        enabled: true                      # THE setting to not forget
    messaging:
      rabbit:
        quorum-queues: true                # classic queues lose messages on node failure
management:
  tracing:
    sampling:
      probability: 0.1
```

Note what is **not** in the `prod` block: no security mode, no kill switches, no profile-conditional
authorization. Production differs from local in *infrastructure* and *telemetry*, never in *behaviour*.

---

## 6. Running real infrastructure locally

`docker-compose.local.yml` at the repo root provides per-backend profiles for exploration:

```bash
docker compose -f docker-compose.local.yml up rabbitmq
docker compose -f docker-compose.local.yml up redis postgres
```

Then run against them:

```bash
mvn -Prabbit -pl examples/example-event-driven/producer spring-boot:run
mvn -Pdocker verify        # the @Tag("docker") certifications
```

!!! success "Best practice — run the Docker profile before anything that matters"
    Before a release, before adopting a new capability, before a change to a provider. The default
    build proves *your code's* semantics; the Docker profile proves the *transport's*. Both matter, and
    only one runs by default.

---

## 7. Which gaps a test can catch

Some of these are provable in the default build. Others need the Docker profile or a real deployment —
and knowing which is which is the point of this section.

| Gap | Catchable Docker-free? | How |
|---|---|---|
| OTLP disabled | **Yes** | Assert the bound property in a `prod`-profile context test |
| Placeholder issuer in a deployed profile | **Yes** | Assert `issuer-uri` does not contain the placeholder |
| `include-stacktrace` outside `local` | **Yes** | Assert the bound `ErrorsProperties` |
| Audit sink not the expected type | **Yes** | An `ApplicationRunner` that throws — [Ch. 12](../chapters/12-audit.md) §5.2 |
| `@LockedSchedule` without a `LockManager` | **Yes** | Assert the bean exists in a `prod`-profile context |
| `@RequiresPermission` advisor missing | **Yes** | Assert `requiresPermissionAdvisor` exists |
| Cluster-wide rate limiting | Partly | Assert the Redis provider is selected; the *behaviour* needs two instances |
| Caffeine staleness across instances | No | Needs multiple instances |
| `storage-fs` across replicas | No | Needs multiple instances |
| Broker topology and redelivery | No | `@Tag("docker")` TCK runs |
| Database dialect differences | No | Testcontainers PostgreSQL |

!!! success "Write the assertions for the first six"
    They are cheap context tests, they run in the default build, and each converts a silent production
    gap into a red build. That is a much better trade than a review checklist item that someone will
    eventually skip.

    ```java
    @PlatformTest
    @ActiveProfiles("prod")
    class ProductionConfigurationTest {

        @Autowired ObservabilityProperties observability;
        @Autowired ErrorsProperties errors;

        @Test
        void tracingIsExportedInProduction() {
            assertThat(observability.otlp().enabled()).isTrue();
        }

        @Test
        void stackTracesAreNeverExposed() {
            assertThat(errors.includeStacktrace()).isFalse();
        }
    }
    ```

---

## 8. Promotion checklist

Before a service reaches its first shared environment:

**Telemetry**

- [ ] `otlp.enabled: true` and collector endpoints set
- [ ] A sampling probability set
- [ ] `spring.application.name` set
- [ ] `format` is `json` (the default) — not overridden to console

**Security**

- [ ] `issuer-uri` points at the real IdP — **not** the archetype placeholder
- [ ] `mode` is `resource-server`
- [ ] `include-stacktrace` is `false` — asserted by a test
- [ ] The metrics scraper is authenticated or excepted
- [ ] `platformflags` exposure is a deliberate decision

**Providers**

- [ ] Cache: Redis if invalidation must be fleet-wide
- [ ] Rate limiting: Redis if the limit must be cluster-wide
- [ ] Storage: S3, or a genuinely shared filesystem — and `fs.root` is not a temp directory
- [ ] Messaging: `quorum-queues: true` on a real Rabbit cluster
- [ ] Audit: the required sink, **asserted at startup**
- [ ] Locking: a provider is present if anything uses `@LockedSchedule`

**Data**

- [ ] Flyway present; `require-migrations` left at `true`
- [ ] `ddl-auto` unset
- [ ] Connection pool sized against expected concurrency
- [ ] Tested against the production database engine, not only H2

**Operations**

- [ ] Retention configured on the audit destination
- [ ] Object-storage lifecycle policy set — nothing expires by itself
- [ ] Redis `maxmemory-policy: volatile-lru`, not `allkeys-lru`
- [ ] `mvn -Pdocker verify` passes
- [ ] `./tooling/scripts/golden-path.sh` passes

---

## 9. The mental model

```
   LOCAL                                    PRODUCTION
   -----                                    ----------
   in-memory, per-instance, ephemeral  ->   shared, durable, cluster-wide
   no infrastructure                   ->   infrastructure you must configure
   one instance                        ->   N instances
   console logs, no traces             ->   JSON logs, sampled traces
   fails loudly and immediately        ->   the SAME behaviour, on real backends

   Deliberately identical: error shapes, validation, correlation, security posture,
   the API contract, and every guarantee your code depends on.
```

!!! success "The single sentence to remember"
    **Local and production differ in *infrastructure* and *telemetry*, never in *behaviour*.** Every
    gap in §2 is a property of a provider or an exporter — not of the platform's contract. When you
    find yourself writing `if (isProduction())` in application code, something has gone wrong.

---

**Next:** [Extending the Chassis](extending.md) — what to do when a default genuinely is not enough.

**Related:** [Chapter 3 — Logging and Observability](../chapters/03-logging-observability.md) ·
[Chapter 9 — Caching and Redis](../chapters/09-cache-redis.md) ·
[Chapter 11 — Storage and Files](../chapters/11-storage-files.md) ·
[Chapter 14 — Testing and Developer Experience](../chapters/14-testing-dx.md) ·
[Runbook: local development](../../runbooks/local-dev.md)

[Back to the book](../index.md)
