# Appendix A — Configuration Property Reference

> How platform configuration works, the keys that actually need a decision, and where the exhaustive
> list lives.

!!! warning "This appendix does not list every property, on purpose"
    The complete, always-current list is
    **[reference/properties.md](../../reference/properties.md)** — generated from every module's
    `spring-configuration-metadata.json` at build time, and gated by a test that fails the build if any
    key is undocumented.

    A hand-written copy would drift the moment a property changed. What this appendix adds is the
    *judgement* the generated table cannot carry: which keys need a decision, which defaults are wrong
    outside a laptop, and how precedence resolves.

---

## 1. How to read platform configuration

### The namespace

```
dc.platform.<capability>.<key>
```

Every platform property. One namespace, so it can never collide with your application's configuration
and is trivially greppable.

Anything **not** under `dc.platform.*` that the platform influences is a *third-party* key —
`spring.jpa.*`, `management.*`, `resilience4j.*`, `logging.*` — contributed at lowest precedence
(§4). Where the ecosystem already has a good property, the platform uses it rather than inventing a
parallel dialect.

### Types

| Type | Written as | Example |
|---|---|---|
| `Duration` | Spring/ISO-8601 duration | `2s`, `500ms`, `PT1M`, `24h` |
| `DataSize` | Size with unit | `10MB`, `512KB` |
| `List<String>` | YAML list — **replaces** the default | See §5 |
| `Map<String,…>` | YAML map — merges by key | `dc.platform.cache.caches.<name>.ttl` |
| Enum | The constant, case-insensitive | `json`, `resource-server`, `BEARER_JWT` |

### Relaxed binding

All three forms bind identically:

```
dc.platform.restclient.connect-timeout    # kebab-case — canonical, use this
dc.platform.restclient.connectTimeout
DC_PLATFORM_RESTCLIENT_CONNECTTIMEOUT
```

Write kebab-case: it is what the metadata, the documentation, and the `upgrade-check` deprecation scan
all use.

### IDE support

Every key ships metadata, so your IDE autocompletes `dc.platform.` and shows the description and
default inline. If it does not, the platform starters are not on the classpath yet.

---

## 2. Kill switches

Every capability has one, all default `true`, all `matchIfMissing = true`:

```
dc.platform.audit.enabled          dc.platform.locking.enabled        dc.platform.redis.enabled
dc.platform.authz.enabled          dc.platform.logging.enabled        dc.platform.resilience.enabled
dc.platform.cache.enabled          dc.platform.messaging.enabled      dc.platform.restclient.enabled
dc.platform.core.enabled           dc.platform.observability.enabled  dc.platform.scheduling.enabled
dc.platform.data.jpa.enabled       dc.platform.openapi.enabled        dc.platform.security.enabled *
dc.platform.errors.enabled         dc.platform.ratelimit.enabled      dc.platform.storage.enabled
dc.platform.events.enabled         dc.platform.flags.enabled          dc.platform.validation.enabled
dc.platform.files.enabled          dc.platform.idempotency.enabled
```

\* security also has `dc.platform.security.mode`, which is the switch you would actually use.

!!! warning "A kill switch stops the platform contributing — it does not turn the feature off"
    What remains is the underlying library's default, not nothing:

    | Disabled | You actually get |
    |---|---|
    | `resilience` | Resilience4j's **library** defaults — no exponential backoff |
    | `cache` | Boot's `CacheManager` — no key convention, no per-cache policy |
    | `logging` | Boot's console logging — unstructured |
    | `openapi` | springdoc's own document — no error model |
    | `security` (`mode: disabled`) | Boot's default chain, **and** authorization silently inert |
    | `audit` | Genuinely nothing — and the absence is invisible |

    Know the fallback before flipping one during an incident. `audit` is the dangerous one.

---

## 3. The keys that need a decision

Everything else has a default that is right. These do not.

### 3.1 You must set these

| Key | Why | Chapter |
|---|---|---|
| `spring.application.name` | The `service` log field **and** the `service` meter tag. Unset means `application` everywhere | [3](../chapters/03-logging-observability.md) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Authentication does nothing without it. Prefer `issuer-uri` over `jwk-set-uri` — it validates the issuer claim too | [5](../chapters/05-security-authz.md) |
| `spring.datasource.*` | If you use JPA | [8](../chapters/08-data.md) |

### 3.2 Defaults that are wrong outside a laptop

| Key | Default | Set it to | Why |
|---|---|---|---|
| `dc.platform.observability.otlp.enabled` | **`false`** | `true` | **No traces or OTLP metrics at all otherwise.** The most-missed setting in the platform |
| `dc.platform.storage.fs.root` | `${java.io.tmpdir}/dc-storage` | A durable path | A temp directory is not durable and is often cleared on reboot |
| `dc.platform.messaging.rabbit.quorum-queues` | `false` | `true` | Classic queues can lose unacknowledged messages on a node failure |
| `dc.platform.files.allowed-types` | 6 types | Only what you accept | Every extra type is unused attack surface |

### 3.3 Never true outside `local`

| Key | Default | Why it matters |
|---|---|---|
| `dc.platform.errors.include-stacktrace` | `false` | Puts class names, file paths, and often data in the **response body** |

Assert this one in a test rather than trusting review —
[Local vs production](../crosscutting/local-vs-production.md) §7 has the assertion.

### 3.4 Depends on your deployment shape

| Key | Decide when |
|---|---|
| `dc.platform.ratelimit.http.enabled` | No upstream gateway limits traffic |
| `dc.platform.ratelimit.http.key-by` | `USER` if callers are authenticated; `IP` only if `X-Forwarded-For` is trustworthy |
| `dc.platform.idempotency.http.enabled` | Clients send `Idempotency-Key` and you want it enforced |
| `dc.platform.events.relay.enabled` | You want domain events bridged — **and have read the outbox limitation** |
| `dc.platform.audit.queue-capacity` | Your burst rate exceeds 1,000 events |
| `dc.platform.data.jpa.require-migrations` | Only for a read-only service against someone else's schema |
| `dc.platform.security.permit-paths` | Prefer a `SecurityCustomizer` — this **replaces** the list |
| `dc.platform.cache.caches.<name>.ttl` | **Always.** An unset TTL means never expires |

---

## 4. Precedence, and the platform's third-party defaults

```
  command-line arguments                     --server.port=9000        HIGHEST
  SPRING_APPLICATION_JSON
  OS environment variables
  application-{profile}.yml
  application.yml
  ───────────────────────────────────────────────────────────────
  platform EnvironmentPostProcessor sources                        LOWEST
```

Four named, lowest-precedence sources contribute the platform's opinions on keys it does not own:

| Source | Contributes |
|---|---|
| `platform-logging-defaults` | `logging.config` → the shipped Logback configuration, plus the resolved service name |
| `platform-observability-defaults` | Endpoint exposure, health probes and groups, baggage fields, the OTLP-off posture |
| `platform-data-jpa-defaults` | `open-in-view=false`, batch size 50 with ordered writes, UTC JDBC time zone |
| `platform-resilience-defaults` | Retry (3 × 200 ms exponential), breaker (50% over 10 calls), time limiter (5 s) |

Because they sit at the bottom, **your configuration always wins** — you never fight the platform for a
key. Because they are named, you can always find out who won:

```bash
curl -s localhost:8080/actuator/env/spring.jpa.open-in-view | jq
curl -s localhost:8080/actuator/configprops | jq '.contexts[].beans | keys'
```

!!! success "`/actuator/env` answers 'why is this value what it is?'"
    It lists every source with an opinion on a key, in precedence order, winner first. Faster than
    reading anyone's code.

---

## 5. Two binding behaviours that catch people

### Lists replace, they do not merge

```yaml
# WRONG — health probes now require authentication
dc.platform.security.permit-paths:
  - /webhooks/**
```

Setting a `List<String>` property **discards the default entirely**. Standard Spring Boot behaviour,
and particularly sharp for `permit-paths` (breaks health checks) and `files.allowed-types` (usually
what you want, but be deliberate).

For `permit-paths`, prefer a `SecurityCustomizer` — it adds without owning the platform's list forever.

### Annotation values must be compile-time constants

Some things are deliberately **not** properties, because they are per-operation decisions:
`@LockedSchedule(atMost)`, `@Idempotent(keyExpression, ttl)`, `@RateLimited(permits, window)`,
`@Audited(action, resourceExpression)`.

A consequence worth knowing: durations on annotations are **strings** (`"5m"`, `"PT30S"`), because
`Duration` is not a constant expression. Same reason `CacheNames.of(...)` cannot name a cache in
`@Cacheable` — see [Chapter 9](../chapters/09-cache-redis.md) §4.2.

---

## 6. Deprecation and upgrades

Property keys are a **public API surface**. A rename that compiles fine silently drops a production
setting — a timeout or a security control stops applying, with no error.

The platform therefore requires a declared deprecation window in metadata, and gives you a tool:

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check \
    -Dplatform.target=1.0.0
```

It scans your `application*.{yml,yaml,properties}` for keys deprecated in the target version — read
from the target platform jars' own metadata — and diffs managed versions. Output: a console summary and
`target/platform-upgrade-report.md`.

Run it **before planning** an upgrade, not before merging one.

---

## 7. A worked configuration

```yaml
# application.yml — always applies
spring:
  application:
    name: orders-service                    # service log field AND meter tag

dc:
  platform:
    files:
      allowed-types: [application/pdf]       # narrowed from the default six
      max-file-size: 5MB
    cache:
      caches:
        tariff.by-code:
          ttl: 1h                            # never leave a TTL unset
          max-size: 10000
    restclient:
      clients:
        idp:
          connect-timeout: 500ms             # on the critical path of every request
          read-timeout: 2s
        reporting:
          read-timeout: 60s                  # legitimately slow

---
spring:
  config:
    activate:
      on-profile: local
dc:
  platform:
    errors:
      include-stacktrace: true               # ONLY here
    storage:
      fs:
        root: ./target/storage               # not the temp directory, even locally

---
spring:
  config:
    activate:
      on-profile: prod
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${IDP_ISSUER_URI}      # a Vault-populated placeholder
dc:
  platform:
    observability:
      otlp:
        enabled: true                        # THE setting not to forget
    messaging:
      rabbit:
        quorum-queues: true
management:
  otlp:
    metrics:
      export:
        url: http://otel-collector:4318/v1/metrics
  tracing:
    export:
      otlp:
        endpoint: http://otel-collector:4318/v1/traces
    sampling:
      probability: 0.1
```

Note what the `prod` block does **not** contain: no security mode, no kill switches, no
profile-conditional behaviour. Production differs in *infrastructure* and *telemetry*, never in
*behaviour*.

---

## 8. Capability index

Each links to its chapter (the *why*) and the generated table (the *complete list*).

| Capability | Chapter | Notable keys |
|---|---|---|
| `core` | [1](../chapters/01-core.md) | `correlation.header-name`, `correlation.generate-if-missing`, `banner-enabled` |
| `errors` | [2](../chapters/02-errors-validation.md) | `type-base-uri`, `map-validation`, **`include-stacktrace`** |
| `validation` | [2](../chapters/02-errors-validation.md) | `enabled` only |
| `logging` | [3](../chapters/03-logging-observability.md) | `format`, `include-mdc`, `service-name` |
| `observability` | [3](../chapters/03-logging-observability.md) | **`otlp.enabled`**, `common-tags.enabled`, `health.groups.enabled`, `platform-endpoint.enabled`, `capability-metrics.enabled` |
| `openapi` | [4](../chapters/04-openapi.md) | `title`, `version`, `security-scheme` |
| `security` | [5](../chapters/05-security-authz.md) | **`mode`**, `permit-paths` |
| `authz` | [5](../chapters/05-security-authz.md) | `roles-claim` |
| `restclient` | [6](../chapters/06-restclient-resilience.md) | `defaults.connect-timeout`, `defaults.read-timeout`, `clients.<name>.*`, `propagate-correlation` |
| `resilience` | [6](../chapters/06-restclient-resilience.md) | `enabled` only — tuning is `resilience4j.*` |
| `messaging` | [7](../chapters/07-messaging-events.md) | `handler.retry.*`, `dlq.suffix`, `correlation.propagate`, **`rabbit.quorum-queues`** |
| `events` | [7](../chapters/07-messaging-events.md) | **`relay.enabled`**, `relay.destination-prefix` |
| `data` | [8](../chapters/08-data.md) | `jpa.require-migrations` |
| `cache` | [9](../chapters/09-cache-redis.md) | **`caches.<name>.ttl`**, `caches.<name>.max-size` |
| `redis` | [9](../chapters/09-cache-redis.md) | `key-prefix` |
| `locking` | [10](../chapters/10-coordination.md) | `enabled` only — `atMost` is on the annotation |
| `scheduling` | [10](../chapters/10-coordination.md) | `enabled` only |
| `idempotency` | [10](../chapters/10-coordination.md) | `http.enabled`, `http.header-name`, `http.ttl` |
| `storage` | [11](../chapters/11-storage-files.md) | **`fs.root`**, `checksum.enabled` |
| `files` | [11](../chapters/11-storage-files.md) | **`allowed-types`**, `max-file-size`, `max-request-size` |
| `audit` | [12](../chapters/12-audit.md) | `queue-capacity` |
| `ratelimit` | [13](../chapters/13-ratelimit-flags.md) | `http.enabled`, `http.key-by`, `http.permits`, `http.window` |
| `flags` | [13](../chapters/13-ratelimit-flags.md) | `static.<flag>` |

**Bold** keys are ones this appendix says need a decision.

---

## 9. Quick checklist

- [ ] `spring.application.name` set
- [ ] `issuer-uri` set, and **not** the archetype placeholder
- [ ] `otlp.enabled: true` in every deployed environment
- [ ] `include-stacktrace: false` outside `local` — asserted by a test
- [ ] `storage.fs.root` is durable, or you are on S3
- [ ] `quorum-queues: true` on a real Rabbit cluster
- [ ] `files.allowed-types` narrowed
- [ ] A TTL on every cache
- [ ] Timeouts per named REST client
- [ ] `base-config: default` on every named Resilience4j instance
- [ ] No list property set without re-listing the defaults you still want
- [ ] `upgrade-check` run before planning the next upgrade

---

**The complete generated list:** [reference/properties.md](../../reference/properties.md) ·
**Next:** [Appendix B — Glossary](b-glossary.md)

[Back to the book](../index.md)
