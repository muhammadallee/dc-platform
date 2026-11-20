# Chapter 3 — Logging and Observability

> **Spring-native 1.0.0 (Phase 3, D141).** JSON logs are Spring Boot's structured logging, switched on and masked
> by the platform; services log with SLF4J only (`addKeyValue` for fields). `Kv` and `LogSanitizer` are gone. Actuator
> runs on its own port, 8081. The correlation ID is the W3C trace ID (`traceId`, `spanId`). Where this chapter and the
> module pages differ, [modules/logging.md](../../modules/logging.md) and
> [modules/observability.md](../../modules/observability.md) are authoritative.

> **Capabilities covered:** `logging`, `observability`
>
> Structured logs, metrics, and traces, stitched together by one trace IDentifier.
>
> **Starters:** `platform-starter-logging`, `platform-starter-observability` ·
> **Reference:** [modules/logging.md](../../modules/logging.md) ·
> [modules/observability.md](../../modules/observability.md)

---

## 1. Introduction and Business Value

Logging and observability are one chapter because they answer one question from two directions.
Logging tells you **what happened in this specific request**. Observability tells you **what is
happening across all of them**. Neither is sufficient; the join between them — the trace ID from
[Chapter 1](01-core.md) — is what turns two datasets into an investigation.

### The problem it solves

At 03:00 the on-call engineer gets paged: error rate up. They need, in this order:

1. **Is it real, and how bad?** — a metric. Rate, ratio, and latency distribution across every
   instance.
2. **Which requests?** — a query over structured logs, filtered by error code, tenant, endpoint.
3. **Why that one?** — a single request's full story, joined by trace ID across every service it
   touched.
4. **Where did the time go?** — a trace.

Each step needs the previous one's output as its input. That chain only works if every service emits
the same field names, the same meter tags, and the same identifier.

### Why "just add a logger" is not enough

Two things go wrong at fleet scale, and both are structural rather than a matter of effort.

**Free-text logs cannot be queried.** `log.info("Order " + id + " failed for customer " + customer)`
produces a line no log store can index by order or customer. You end up writing a grok pattern per
service, the patterns break when someone rewords a message, and the fields you most want to alert on
were never fields. Structured logging — one JSON object per event, with named fields — makes the same
information queryable, and it costs nothing at the call site.

**Logging must be configured before the application context exists.** This is the subtle one. Logging
is initialised very early in Boot's startup, well before `@Configuration` classes are processed. A
capability that configures logging with a bean is too late: everything logged during startup —
including the `FailureAnalyzer` output explaining why the app is about to die — comes out unstructured.
Those are precisely the lines that matter during an incident. The platform therefore configures
logging through an `EnvironmentPostProcessor`, which runs before any bean exists.

### And why metrics need their own discipline

Metrics have a property logs do not: **cardinality is a cost**. Every distinct combination of tag
values is a separate time series, stored forever, on every instance. Tag a meter with a user id and a
million users become a million time series. Tag it with a trace ID — unique per request — and the
series count grows without bound until the metrics backend falls over, usually at the worst moment,
usually with a memorable invoice attached.

This is why the trace ID travels in the `traceparent` header and the log fields, and is explicitly excluded
from metric tags. It is not an oversight; it is the single most important rule in this chapter.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| Free-text logs | A grok pattern per service, brittle ingest, and fields you cannot alert on |
| Logging configured by a bean | Startup and failure-analysis lines are unstructured — the ones you need most |
| No MDC lift-through | Correlation must be passed explicitly at every call site, and is inevitably forgotten |
| No common meter tags | Every service tags differently; a fleet dashboard cannot be built generically |
| No health groups | Kubernetes restarts pods that are merely warming up, or routes traffic to instances whose DB is down |
| Correlation as a metric tag | Unbounded cardinality. A metrics-backend outage and a large, sudden cost event |
| No `/actuator/platform` | Deployment composition is inferred from build artifacts that may not match what is running |

---

## 2. Core Concepts and Underlying Principles

### 2.1 The three signals, and what each is for

| Signal | Answers | Cardinality | Retention | Cost driver |
|---|---|---|---|---|
| **Logs** | "What happened in this request?" | Unbounded — one event per occurrence | Days to weeks | Volume |
| **Metrics** | "What is the rate, ratio, and distribution?" | **Must stay bounded** | Months to years | Series count |
| **Traces** | "Where did the time go across services?" | Sampled | Days | Sampling rate |

The most common design mistake is using one for another's job: computing a rate by counting log lines
(expensive and slow), or putting per-request detail in a metric tag (unbounded cardinality). Use each
for what it is cheap at, and join them with the trace ID.

### 2.2 Structured logging, and what the platform adds to Boot's

Spring Boot writes structured JSON logs itself (`logging.structured.format.console`). The platform switches that on,
names its own format (Boot's Logstash layout with the platform's field names), and registers its masking with Boot.
One JSON object per event, on stdout:

```json
{"@timestamp":"2026-08-04T10:15:30.123+04:00","message":"order accepted",
 "logger":"ae.gov.dubaicustoms.orders.OrderService","level":"INFO",
 "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","orderId":"8812",
 "service":"orders-service"}
```

`service` is resolved once at startup: explicit `dc.platform.logging.service-name`, falling back to
`spring.application.name`, falling back to `application`.

`traceId` and `orderId` arrive by two different routes, which is worth understanding:

- **`traceId` and `spanId` come from the MDC**, published by the core filter. Every MDC entry becomes a JSON field
  automatically, which is why no logging call site ever mentions them.
- **`orderId` comes from a key-value pair** on the event, `log.atInfo().addKeyValue("orderId", order.id())`, added at
  the call site.

### 2.3 Key-value pairs are SLF4J's own

```java
log.atInfo().addKeyValue("orderId", order.id()).log("order accepted");
```

SLF4J 2's fluent API carries key-value pairs on the event. Boot writes each as a JSON field; the platform masks it by
its name and structure first (a credential name masks the whole value, at any depth). Domain code needs only SLF4J.

!!! success "Best practice — log the identifiers, not the sentence"
    ```java
    log.info("order {} failed for customer {}", orderId, customerId);          // not queryable
    log.atInfo().addKeyValue("orderId", orderId).addKeyValue("customerId", customerId).log("order failed");
    ```
    The second is one indexed event you can filter, aggregate, and alert on. The first is a string.

### 2.4 `EnvironmentPostProcessor` and the startup ordering problem

Boot's startup, simplified:

```
  SpringApplication.run()
     |
     v
  EnvironmentPostProcessors run          <-- platform-logging-defaults set logging.structured.format.* HERE
     |                                       platform-observability-defaults set management.* HERE
     v
  Logging system initialised             <-- reads logging.structured.format.*
     |
     v
  Bean definitions loaded
     |
     v
  Auto-configuration evaluated           <-- too late to configure logging
     |
     v
  Beans instantiated -> application ready
```

Both capabilities contribute **named, lowest-precedence property sources**:

| Source | Contributes |
|---|---|
| `platform-logging-defaults` | `logging.structured.format.console` and `.file` → the platform's formatter, the context switch, the banner off |
| `platform-observability-defaults` | The management port (8081), actuator exposure, health probes and groups, the OTLP-off posture |

Lowest precedence means **your configuration always wins**. Named means `/actuator/env` tells you
exactly which source supplied a value. Both properties matter: an opinionated default you cannot
override gets forked, and one you cannot trace gets misdiagnosed.

!!! note "Both post-processors run at `LOWEST_PRECEDENCE` among post-processors"
    Deliberately *after* `ConfigDataEnvironmentPostProcessor`, because they need to read your
    `application.yml` and active profiles to make their decisions — the format choice depends on
    whether the `local` profile is active, and the OTLP posture depends on a `dc.platform.*` toggle you
    may have set.

### 2.5 Micrometer, and the meter-tag contract

Micrometer is the metrics façade — the SLF4J of metrics. Your code (and the platform's) records against
`MeterRegistry`; the backend (Prometheus, OTLP) is a separate concern.

A **meter** is identified by its name plus its tags. `http.server.requests{method=GET,uri=/orders,status=200}`
and `http.server.requests{method=GET,uri=/orders,status=404}` are two distinct time series.

The platform stamps three tags on **every** meter in the registry:

| Tag | Value | Why |
|---|---|---|
| `service` | `spring.application.name` | Which service |
| `env` | The **first** active profile | Which environment |
| `platform.version` | Read from the core-api jar manifest | Which platform train — makes fleet-wide upgrade impact visible |

!!! note "`env` is the first active profile only, on purpose"
    A dashboard slices by *one* environment dimension. If the tag were a comma-joined list of every
    active profile, `prod` and `prod,eu-west` would be different series and no dashboard query would
    match both.

!!! warning "The cardinality rule, stated once"
    **Never tag a meter with anything that varies per request or per user.** Not a trace ID, not
    a user id, not an order id, not a full URL with path parameters interpolated, not an exception
    message. If the set of possible values is not small and known in advance, it is not a tag — it is a
    log field.

### 2.6 Liveness and readiness

Two questions an orchestrator asks, with two different consequences:

| Probe | Question | Failure consequence |
|---|---|---|
| **Liveness** | Is this process broken beyond recovery? | The pod is **killed and restarted** |
| **Readiness** | Can this instance serve traffic right now? | The pod is **removed from the load balancer**, not restarted |

Getting these backwards causes outages. A database blip must fail *neither* probe: restarting will not
fix the database, and a database every replica shares would take every replica out of the load
balancer at once — a recoverable dependency blip becomes a full outage. Requests that need the database
fail on their own (with a problem response); the rest keep being served.

The platform's defaults (D104):

```
  liveness      <- livenessState
  readiness     <- readinessState                          (pod-local)
  dependencies  <- db, rabbit             (each only "when present"; for dashboards and alerts, not probes)
```

Two details make this work in practice. `management.endpoint.health.probes.enabled=true` makes the
liveness and readiness contributors exist on **any** platform — Boot only auto-enables them when it
detects Kubernetes. And `validate-group-membership=false` is what lets the `dependencies` group name
`db` and `rabbit` optionally: with validation on, a service that has no database would fail to
start because its group references a health indicator that does not exist.

### 2.7 How the trace crosses a service boundary without becoming a tag

The trace ID crosses a service boundary in the W3C `traceparent` header, which Boot's observed HTTP clients send and
Boot's server observation reads. No custom correlation header and no baggage field is configured (D104, HD5).

```
   Service A                              Service B
   ---------                              ---------
   traceId in MDC   ---- traceparent ---->  traceId in MDC
        |                                       |
        v                                       v
   log field  [yes]                        log field  [yes]
   metric tag [NO]                         metric tag [NO]
```

---

## 3. Feature Reference

### 3.1 Logging — no public API

The logging capability has no types for service code. You log with SLF4J; fields are SLF4J key-value pairs; your
own masking is the `dc.platform.logging.redaction.patterns` property, or a Boot `StructuredLoggingJsonMembersCustomizer`
of your own (the platform's masking still applies to what it adds).

### 3.2 Observability — what it contributes

The observability capability contributes no public API types; it contributes **beans, endpoints, and
defaults**.

| Contribution | Detail |
|---|---|
| Common meter tags | `service`, `env`, `platform.version` on every registry |
| `/actuator/platform` | JSON `CapabilityDescriptor` report — what is live, with which provider |
| Health groups | `liveness` and `readiness`, on any platform, with optional members |
| `platform.capability.active{capability}` | A 0/1 gauge per capability descriptor, feeding the adoption dashboard |
| Management defaults | The management port 8081, endpoint exposure, probes, OTLP posture — all at lowest precedence |
| Registries | Prometheus available locally; OTLP export **off** by default |

### 3.3 Log fields

| Field | Source | Notes |
|---|---|---|
| `@timestamp` | the event | ISO-8601 with offset |
| `message` | the log statement | rendered from its pattern, each argument masked by its structure |
| `logger` | the event | fully qualified logger name |
| `level` | the event | |
| `traceId`, `spanId` | MDC, via the core filter | present while a trace is open |
| *anything else in the MDC* | MDC | written when `include-mdc` is `true`; strict mode keeps only listed keys |
| *key-value pairs* | the call site (`addKeyValue`) | masked by name and structure |
| `tags` | markers | when the event has any |
| `stack_trace` | the event | on `log.error(msg, throwable)` |
| `service` | `logging.service-name` → `spring.application.name` → `application` | resolved once at startup |

### 3.4 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

**Logging**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.logging.enabled` | Boolean | `true` | Kill switch | Essentially never — see §6.5 |
| `dc.platform.logging.format` | `json` \| `console` | `json` | Output format. **`console` is auto-selected when the `local` profile is active and no explicit format is set** | Explicitly, to force one format regardless of profile |
| `dc.platform.logging.include-mdc` | Boolean | `true` | Copy MDC entries into JSON fields | Essentially never — this is what makes correlation free |
| `dc.platform.logging.service-name` | String | `spring.application.name` | The `service` field value | When the log-store convention differs from the Spring application name |
| `dc.platform.logging.mdc.include` | List | empty | Strict mode: only `traceId`, `spanId` and these MDC keys and key-value pairs | When other libraries put noisy or sensitive entries in the MDC |
| `dc.platform.logging.redaction.enabled` | Boolean | `true` | Mask credentials and personal data | Essentially never |
| `dc.platform.logging.redaction.patterns` | List | empty | Extra regular expressions to mask | To mask your own identifiers |

**Observability**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.observability.enabled` | Boolean | `true` | Kill switch | During an incident traced to a customizer |
| `dc.platform.observability.common-tags.enabled` | Boolean | `true` | Stamp `service`/`env`/`platform.version` | Only if you replace them with your own customizer |
| `dc.platform.observability.health.groups.enabled` | Boolean | `true` | Contribute liveness/readiness groups | When your orchestrator's probe configuration is managed elsewhere |
| `dc.platform.observability.platform-endpoint.enabled` | Boolean | `true` | Serve `/actuator/platform` | Rarely — it carries no secrets |
| `dc.platform.observability.otlp.enabled` | Boolean | **`false`** | Enable OTLP metric and span export | **In every deployed environment.** Off is a laptop default |
| `dc.platform.observability.capability-metrics.enabled` | Boolean | `true` | Emit the `platform.capability.active` gauge | If your organisation does not run the adoption dashboard |

!!! warning "`otlp.enabled` defaults to `false`, and that is a laptop default"
    Off by default so a local run never dials a collector that is not there. **You must turn it on in
    every deployed environment**, or your spans and OTLP metrics go nowhere. This is the single most
    commonly missed observability setting — see §5.1.

### 3.5 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformLoggingEnvironmentPostProcessor` | `logging.enabled != false` **and** JSON format resolved | `logging.structured.format.*` → the platform's formatter | Console format resolved — Boot's own defaults apply, nothing is contributed |
| `PlatformLogMasking` (Boot members customizer, `spring.factories`) | logging and redaction enabled | Masking in every structured format | `redaction.enabled=false` |
| `LoggingAutoConfiguration` | Logback present, `logging.enabled != false` | Capability descriptor; a WARN when your own Logback file replaces structured logging | Standard `@ConditionalOnMissingBean` |
| `PlatformObservabilityEnvironmentPostProcessor` | `observability.enabled != false` | The `management.*` defaults in §2.4, the management port 8081 | Any key you set yourself wins on precedence; `server.port=8081` alone fails startup |
| `CommonTagsAutoConfiguration` | Micrometer on classpath, both enable flags true | `platformCommonTagsCustomizer` | You define a bean named `platformCommonTagsCustomizer` |
| `PlatformInfoEndpointAutoConfiguration` | Actuator present, `platform-endpoint.enabled != false` | `platformEndpoint` (`@Endpoint(id="platform")`) | Standard back-off |
| `CapabilityMetricsAutoConfiguration` | Micrometer present, `capability-metrics.enabled != false` | The `platform.capability.active` gauge registrar | Standard back-off |

### 3.6 Extension points

| Extension | How | Effect |
|---|---|---|
| Mask your own identifiers | `dc.platform.logging.redaction.patterns` | Applied to every value of every line |
| Add fields to every line | Your own `StructuredLoggingJsonMembersCustomizer` in `logging.structured.json.customizer` | Added next to the platform's masking |
| Add or change meter tags | Declare a `MeterRegistryCustomizer` bean | Applied by Boot to every registry |
| Replace the platform's common tags | Declare a bean named `platformCommonTagsCustomizer` | The platform's backs off |
| Report your own capability | Declare a `CapabilityDescriptor` bean ([Chapter 1](01-core.md)) | Appears in `/actuator/platform` and the gauge |
| Replace the logging configuration | Set `logging.config` yourself | Your file wins on precedence, and masking applies only if it uses Boot's structured appender |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-logging</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-observability</artifactId>
</dependency>
```

Delete any `logback-spring.xml` that exists only to produce JSON: the platform switches on Spring Boot's structured
logging, and your own Logback file would replace it, masking included. Start the app; logs should be one JSON object
per line, and `/actuator/health/readiness` should answer on the management port, 8081.

### 4.2 Log something worth querying

```java snippet:book-03-structured-logging
@Service
class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    void dispatch(String shipmentId, String carrier, int parcels) {
        log.atInfo()
                .addKeyValue("shipmentId", shipmentId)
                .addKeyValue("carrier", carrier)
                .addKeyValue("parcels", parcels)
                .log("shipment dispatched");
    }
}
```

JSON:

```json
{"@timestamp":"2026-08-04T10:15:30.123Z","message":"shipment dispatched","logger":"...ShipmentService",
 "level":"INFO","traceId":"9f2c...","spanId":"4a1e...",
 "shipmentId":"SHP-1","carrier":"DHL","parcels":3,"service":"shipping-service"}
```

Console, on a laptop:

```
10:15:30.123 INFO  ...ShipmentService : shipment dispatched
```

The key-value pairs are SLF4J's own (`addKeyValue`), so the call site needs no platform type. In JSON they become
fields a log store can query; the console line keeps the message short.

!!! success "Best practice — one event, many fields"
    Prefer one log line carrying five fields over five lines carrying one each. Log stores charge by
    event, dashboards group by event, and a single event is what a correlation query returns.

### 4.3 Get readable logs locally

Nothing to configure — run with the `local` profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The format decision is: explicit `dc.platform.logging.format` wins; otherwise `console` when the
`local` profile is active, `json` otherwise.

!!! tip "Force JSON locally to debug an ingest problem"
    ```bash
    mvn spring-boot:run -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.arguments=--dc.platform.logging.format=json
    ```
    An explicit format always beats the profile heuristic.

### 4.4 Scrub sensitive values from logs

The platform already masks credentials (JWTs, `Bearer` and `Basic` credentials, values of keys such as `password`,
`token` or `apiKey`) and the default personal-data set (Emirates ID, card numbers, e-mail addresses, phone numbers) in
every JSON line. For data of your own, list regular expressions; each match becomes `****`:

```yaml
dc:
  platform:
    logging:
      redaction:
        patterns:
          - "CASE-\\d{6}"
```

!!! warning "A pattern runs on every value of every log line — keep it anchored and bounded"
    `CASE-\d{6}` is cheap; `.*secret.*` scans each value from every position. To mask a whole value by its name,
    log it as a key-value pair under a credential name (`password`, `token`, …): the platform masks those whole,
    whatever the value.

### 4.5 Turn on OTLP export

In every deployed environment:

```yaml
dc:
  platform:
    observability:
      otlp:
        enabled: true

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

Setting `dc.platform.observability.otlp.enabled=true` removes the platform's `export.enabled=false`
defaults, so Boot's own export configuration takes over — you then point it at your collector using
Boot's standard keys.

### 4.6 Add your own metric

```java snippet:book-03-custom-metric
@Service
class CustomsDeclarationService {

    private final Counter accepted;
    private final Counter rejected;

    CustomsDeclarationService(MeterRegistry registry) {
        this.accepted = Counter.builder("declarations.processed")
                .tag("outcome", "accepted")
                .register(registry);
        this.rejected = Counter.builder("declarations.processed")
                .tag("outcome", "rejected")
                .register(registry);
    }

    void record(boolean isAccepted) {
        if (isAccepted) {
            accepted.increment();
        } else {
            rejected.increment();
        }
    }
}
```

`service`, `env`, and `platform.version` are added automatically. `outcome` has exactly two possible
values, which is what makes it a legitimate tag.

!!! warning "The counters are built once, in the constructor — not per call"
    Building a meter per invocation re-resolves it in the registry on the hot path. Resolve at
    construction, increment at call time. And note what is *not* a tag here: no declaration id, no
    trader id, no trace ID.

### 4.7 Add a meter tag fleet-wide

```java
@Bean
MeterRegistryCustomizer<MeterRegistry> regionTag() {
    return registry -> registry.config().commonTags("region", "dxb-1");
}
```

Additive — the platform's three tags are unaffected. Only use a bean *named*
`platformCommonTagsCustomizer` if you intend to replace them entirely.

### 4.8 Check what is running

```bash
curl -s localhost:8081/actuator/platform | jq
curl -s localhost:8081/actuator/health/readiness | jq
curl -s localhost:8081/actuator/prometheus | grep '^http_server_requests'
```

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local | `local` profile | Console logs, no collector dialling |
| **Every deployed environment** | `otlp.enabled: true` + collector endpoints | **Otherwise spans and OTLP metrics go nowhere.** The most commonly missed setting here |
| Every deployed environment | `format: json` (the default) | Never override to `console` in a deployed environment |
| Every deployed environment | A sampling probability | 100% tracing is rarely affordable; 1–10% is typical |
| Every deployed environment | `spring.application.name` set | It is the `service` field *and* the `service` meter tag. Unset means `application` |

!!! warning "The most common misconfiguration in this chapter"
    A service deployed with the platform defaults untouched has structured logs, working health
    probes, Prometheus metrics — and **no traces and no OTLP metrics**, because `otlp.enabled` is
    `false`. Add it to your deployment template, not to each service's `application.yml`.

### 5.2 What to monitor

**The four signals that matter for every service** (the RED/USE method applied to this platform):

| Signal | Meter | Alert on |
|---|---|---|
| Rate | `http_server_requests_seconds_count` | Sudden change in either direction |
| Errors | Same, `status=~"5.."` | Ratio above your SLO error budget burn rate |
| Duration | `http_server_requests_seconds_bucket` | p99 above SLO |
| Saturation | `jvm_memory_used_bytes`, `hikaricp_connections_pending` | Pool exhaustion, heap pressure |

**Platform-specific signals:**

| Signal | Where | Alert when |
|---|---|---|
| Correlation coverage | Log query: `NOT _exists_: traceId` | Coverage drops — a context leak ([Chapter 1](01-core.md) §6.1) |
| `platform.capability.active` | Metric, tagged `capability` | A capability disappears from a service unexpectedly |
| `platform.version` tag distribution | Any meter | Services stuck on an old train |
| Readiness flapping | Probe history | Readiness oscillating means a dependency health indicator is unstable |
| Log volume per service | Ingest metrics | A sudden spike is usually a loop, a `DEBUG` left on, or a retry storm |

!!! tip "`platform.version` as a fleet dashboard"
    Because every meter carries it, one query — count of distinct services grouped by
    `platform.version` — tells the platform team exactly who has upgraded. That is adoption telemetry
    for free, and it is why the tag exists.

### 5.3 Troubleshooting

**Logs are plain text, not JSON.**

| Cause | Check |
|---|---|
| `local` profile active | `curl -s localhost:8081/actuator/env \| jq .activeProfiles` |
| Format explicitly set | `curl -s localhost:8081/actuator/env/dc.platform.logging.format` |
| Your own `logback-spring.xml` | Boot prefers it. Delete it, or set `logging.config` deliberately |
| Starter missing | `mvn dependency:tree \| grep platform-starter-logging` |
| `logging.config` overridden | `curl -s localhost:8081/actuator/env/logging.config` — the winning source is named |

**No `traceId` in log lines.** Almost always core, not logging — see
[Chapter 1](01-core.md) §5.3. Check `include-mdc` is `true` first, then go there.

**No traces anywhere.** `otlp.enabled` is `false`. §5.1.

**Metrics missing tags.** `common-tags.enabled` is off, or a bean named `platformCommonTagsCustomizer`
is replacing the platform's. Check `/actuator/beans`.

**Readiness is always down.** Readiness is `readinessState` only, so the application itself reported
`REFUSING_TRAFFIC` (during startup or shutdown, or set by your code); a database outage does not take
it down. `curl -s localhost:8081/actuator/health/dependencies | jq` names a failing `db` or `rabbit`.

**The metrics backend is struggling.** Almost certainly cardinality. Find the offender:

```bash
# metrics is not exposed by default: add it locally with
#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
curl -s localhost:8081/actuator/metrics | jq -r '.names[]' | while read m; do
  echo "$(curl -s "localhost:8081/actuator/metrics/$m" | jq '[.availableTags[].values|length] | add // 0') $m"
done | sort -rn | head
```

The top of that list is where an unbounded tag was introduced. §6.4.

### 5.4 Scaling and performance

**Logging.**

- JSON encoding costs more CPU than plain text. At normal rates it is not measurable; at tens of
  thousands of lines per second it is.
- **`DEBUG` on a hot logger is the most common self-inflicted performance problem in this chapter.**
  It multiplies volume, ingest cost, and encoding cost simultaneously.
- Guard genuinely expensive arguments: `if (log.isDebugEnabled())`. Not needed for a plain key-value pair;
  needed for anything that builds an object graph. The platform walks a logged object by its structure (bounded at
  10,000 values), so a huge collection costs time.
- Extra redaction patterns run on every value of every line. Keep them anchored and bounded.

**Metrics.**

- Cost is dominated by **series count**, not by the increment. A counter increment is a few
  nanoseconds; an unbounded tag is a persistent cost on every instance and in the backend.
- Timers with percentile histograms are far more expensive than counters. Enable them per meter, not
  globally.
- `/actuator/prometheus` serialises the whole registry on each scrape — the scrape cost is proportional
  to series count, which is the same variable as above.

**Tracing.**

- Sampling is the only real control. 100% is affordable in a low-traffic service and nowhere else.

### 5.5 Security considerations

**Logs are the most under-appreciated data-exfiltration path in a service.** They are voluminous,
long-retained, widely readable, and rarely reviewed.

| Risk | Mitigation |
|---|---|
| PII or secrets in log fields | Platform masking plus `dc.platform.logging.redaction.patterns` for your own identifiers. Log secrets, if at all, as key-value pairs under credential names. Review what you put in the MDC |
| Secrets in exception messages | The [errors](02-errors-validation.md) capability logs full exceptions at ERROR. Anything in the message is in the log |
| Log injection | [`@SafeText`](02-errors-validation.md) on single-line fields; `CorrelationId`'s format check |
| Actuator exposure | Default exposure is `health,info,prometheus` (`platform`, `metrics` are opt-ins). The security chain permits only `health` and `info` |
| `/actuator/env` leaking configuration | It shows property values. Boot masks known-sensitive keys, but a key named `myCustomThing` is not known-sensitive |
| Metric tags with user identifiers | A cardinality problem *and* a privacy problem — metrics are retained far longer than logs |

!!! warning "`/actuator/env` and `/actuator/configprops` are not public endpoints"
    They are exposed over HTTP by the platform's default and **authenticated** by the security chain.
    If you are running without security, they are open. Check that before you
    deploy.

---

## 6. Deep Dive

### 6.1 Why the encoder drops three fields rather than renaming them

`thread`, `version`, and `levelValue` are mapped to `[ignore]` in the Logstash encoder's field names.

- `thread` is meaningless once you have a trace ID, and misleading with virtual threads, where
  the name is generated per task.
- `version` is the *encoder's* schema version, not yours. It is a constant that costs bytes on every
  line, forever.
- `levelValue` is the numeric form of `level`. Two representations of the same thing; the string is
  the one anyone queries.

Dropping at the encoder rather than filtering at ingest matters at fleet scale: three fields × billions
of lines × every service is real storage, and an ingest-side filter has to be configured once per
pipeline and stays configured until someone changes it.

### 6.2 Why logging is fail-soft and has no error codes

Every other capability defines error codes. Logging deliberately does not, and the reason is a
principle worth stating: **observability is not worth an outage.**

A malformed appender configuration, an unreachable log destination, or a value that throws while it is
rendered must degrade logging, not stop the service. This is why a logged value whose `toString()` or getter throws
becomes `[unrenderable]`: it costs that value, never the line or the request.

The trade-off is honest: a broken logging setup is *quiet*. Nothing pages you. Which is exactly why
§5.2 lists log volume as a monitored signal — a sudden drop to zero is the symptom.

### 6.3 Why `service` is resolved in the post-processor, not in the XML

The Logback configuration declares:

```xml
<springProperty name="service" source="dc.platform.logging.service-name" defaultValue="application"/>
```

`springProperty` supports exactly **one** source. But the platform's resolution order has three levels:
explicit `dc.platform.logging.service-name`, then `spring.application.name`, then `application`.

So the post-processor resolves the chain itself and *writes the answer into*
`dc.platform.logging.service-name` before Logback reads it. The XML then has a single source to read.
A small piece of indirection that exists purely because of a Logback limitation — worth knowing when
you wonder why that property appears in `/actuator/env` with a value you did not set.

### 6.4 The cardinality failure mode, concretely

How it actually happens, in the order it happens:

1. Someone adds `Timer.builder("order.processing").tag("orderId", id)` — reasonable-looking, and it
   works fine in development with twelve orders.
2. Production has 50,000 orders a day. That is 50,000 new time series a day, per instance.
3. Prometheus memory climbs. Scrapes slow, because `/actuator/prometheus` serialises every series.
4. Scrapes start timing out. Now you have *gaps in your metrics* during the incident the metrics were
   supposed to explain.
5. The backend hits its series limit and starts rejecting writes — often for *other* services sharing
   it.

The tell is a slow, monotonic climb in backend memory that correlates with a deploy, and it is worth
recognising because the fix requires a code change and, usually, dropping the series.

!!! success "Best practice — the tag test"
    Before adding a tag, ask: *can I write down the complete set of values it will ever take?* `status`
    (a handful), `outcome` (two), `provider` (three) — yes. `orderId`, `userId`, `email`, `path with
    parameters interpolated` — no. If you cannot enumerate it, it belongs in a log field.

### 6.5 Why the logging kill switch is almost never the right answer

`dc.platform.logging.enabled=false` does not turn logging off — it stops the platform *configuring*
logging, so Boot's own defaults apply and you get unstructured console output.

That is occasionally what you want (a container platform that captures raw stdout and structures it
itself). It is much more often reached for during an incident, where it makes things worse: you lose
the structured fields at the moment you most need to query them.

If a specific logger is the problem, set its level. If the ingest pipeline is the problem, fix the
pipeline. The kill switch is a deployment-shape decision, not an incident tool.

### 6.6 `platform.capability.active` — adoption telemetry that cannot be gamed

A 0/1 gauge per capability descriptor, tagged with the capability name. It exists so the platform team
can answer "which services actually run messaging in production?" from telemetry rather than from a
spreadsheet someone updates quarterly.

The interesting property is that it is **derived from the same descriptors that produce the startup
banner and `/actuator/platform`**. There is no separate registration a team can forget or skip. If the
capability is active, the gauge is 1.

### 6.7 The `local` profile heuristic, and its one sharp edge

The format decision:

```java
String format = environment.getProperty("dc.platform.logging.format");
if (format != null) {
    return "json".equals(format.toLowerCase(ROOT));    // explicit always wins
}
return !activeProfiles.contains("local");              // otherwise: local -> console
```

The sharp edge: **it checks whether `local` is among the active profiles, not whether it is the only
one.** Running with `--spring.profiles.active=local,integration` gives console logs. That is usually
what you want, and occasionally a surprise in a CI job that activates `local` alongside something else.
Set the format explicitly in CI.

### 6.8 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Leaving `otlp.enabled` false in production | It is the default, and nothing fails | Put it in the deployment template |
| Tagging a meter with a request-scoped value | It seems like useful detail | §6.4. Use a log field |
| Building a meter per invocation | It reads more naturally | Build in the constructor |
| String-concatenated log messages | Habit | `addKeyValue(...)` costs nothing and makes the field queryable |
| Logging the trace ID explicitly | It looks helpful | It is already a field |
| A `logback-spring.xml` left from before adoption | Boot prefers it silently | Delete it |
| `DEBUG` left enabled on a hot logger | Someone was debugging | Volume, cost, and latency, all at once |
| An unbounded extra redaction pattern | Untested — it is on the log path | Anchor and bound every pattern |
| Failing liveness for a dependency blip | Both probes look the same | Shared dependencies belong in the `dependencies` group |
| `spring.application.name` unset | Nothing complains | Every meter is tagged `service=application` |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), which has both capabilities plus
real traffic to observe.

### Lab 1 — Basic: make a log line queryable

**Goal.** Feel the difference between a string and an event.

**Steps.**

1. Boot with the `local` profile. Note console format.
2. Boot without it. Note JSON, and find the `service` and `traceId` fields.
3. Add `log.info("order accepted for " + customerId)` and call the endpoint. Try to write a log query
   returning every line for one customer.
4. Change it to `log.atInfo().addKeyValue("customerId", customerId).log("order accepted")`. Write the
   query again.
5. View both in console format and compare readability.

**Expected outcome.** Step 3's query needs a substring match that breaks the moment anyone rewords the
message. Step 4's is a field equality match. Step 5 shows the console output is no worse — which is the
argument for always using key-value pairs.

**Hints.**

- No `traceId`? A trace has to be open — call through HTTP, not from a test main method.
- `jq` on the JSON output makes step 2 much easier: `mvn spring-boot:run | jq .`

**How to verify.** A test with a `ListAppender<ILoggingEvent>` asserting the event has an argument
whose key is `customerId`.

### Lab 2 — Intermediate: instrument a business outcome and probe it

**Goal.** Add a metric that answers a business question, and wire the health probes correctly.

**Steps.**

1. Add a `Counter` named `declarations.processed` tagged `outcome` (`accepted`/`rejected`). Build both
   counters in the constructor.
2. Drive traffic and confirm both series in `/actuator/prometheus`.
3. Confirm `service`, `env`, and `platform.version` were added automatically. Where did
   `platform.version` come from? (§2.5)
4. `curl` `/actuator/health/liveness` and `/actuator/health/readiness`. Note which components each
   includes.
5. Stop the database. Watch both probes. Which changed?
6. Write down what would happen if `db` were in the *liveness* group instead, across ten replicas.

**Expected outcome.** Two series, five tags each. Readiness goes down on step 5; liveness stays up.
Step 6 should produce the sentence "every replica restarts in a loop and the outage gets worse".

**Hints.**

- `platform.version` reads `Implementation-Version` from the core-api jar manifest, so it is `unknown`
  on an exploded classpath. That is expected in tests.
- Readiness names `db` optionally — it appears only because a `DataSource` is present.

**How to verify.** A `@PlatformTest` asserting the counter exists with both `outcome` values and
carries the three common tags.

### Lab 3 — Advanced: cause a cardinality incident, then diagnose it

**Goal.** Reproduce the failure mode from §6.4 at small scale, and learn the diagnostic that finds it.

**Steps.**

1. Add a `Timer` tagged with a per-request identifier — the trace ID is the purest form of the
   mistake.
2. Drive 1,000 requests.
3. `curl -s localhost:8081/actuator/metrics/<name> | jq '.availableTags'` (expose `metrics` first). Count the values.
4. Measure `/actuator/prometheus` response size and time before and after. Extrapolate to 10 million
   requests.
5. Run the cardinality-ranking command from §5.3. Confirm your meter is at the top.
6. Fix it: remove the tag, and add the identifier as a **log field** on the same operation instead.
7. Now do the subtler version: tag with the request URI *including* an interpolated path parameter
   (`/orders/8812`). Explain why this is the same bug, and what Spring's own
   `http.server.requests` does differently.

**Expected outcome.** 1,000 tag values from 1,000 requests, a visibly larger and slower scrape, and a
diagnostic that finds it in one command. Step 7 is the real lesson — Spring's `uri` tag uses the
*template* (`/orders/{id}`), which is bounded, and hand-rolled URI tags usually are not.

**Hints.**

- Do this against a local Prometheus if you can; watching the memory curve is the part that makes it
  stick.
- Step 6's fix is not "use a shorter id". There is no cardinality budget that makes a per-request tag
  acceptable.

**How to verify.** A test asserting that no meter in the registry has more than N distinct values for
any tag — a genuinely useful guard to keep in a real codebase.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-logging</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-observability</artifactId>
</dependency>
```

**Log**

```java
log.atInfo().addKeyValue("shipmentId", id).log("shipment dispatched");   // queryable
```

**Meter**

```java
Counter.builder("declarations.processed").tag("outcome", "accepted").register(registry);
// service, env, platform.version are added for you
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.logging.format` | `json` (`console` when `local` profile is active) |
| `dc.platform.logging.include-mdc` | `true` |
| `dc.platform.logging.service-name` | `spring.application.name` |
| `dc.platform.observability.otlp.enabled` | **`false`** — turn it on in every deployed environment |
| `dc.platform.observability.common-tags.enabled` | `true` |
| `dc.platform.observability.health.groups.enabled` | `true` |
| `dc.platform.observability.platform-endpoint.enabled` | `true` |

**Endpoints**

```bash
curl -s localhost:8081/actuator/platform | jq            # what is live
curl -s localhost:8081/actuator/health/readiness | jq    # can I serve?
curl -s localhost:8081/actuator/health/liveness | jq     # should I be restarted?
curl -s localhost:8081/actuator/prometheus               # meters
curl -s localhost:8081/actuator/env/logging.config       # who set the log config?
```

**Signal routing**

| Want to know | Use |
|---|---|
| What happened in one request | Logs, filtered by `traceId` |
| Rate, error ratio, latency distribution | Metrics |
| Where the time went across services | Traces |
| What is deployed here | `/actuator/platform` |

**Rules of thumb**

- Never tag a meter with anything per-request or per-user. If you cannot enumerate the values, it is a
  log field.
- `otlp.enabled: true` in every deployed environment. It is `false` by default for laptops.
- Set `spring.application.name`. It is the `service` field *and* the `service` meter tag.
- Build meters in the constructor, not per call.
- Shared dependencies belong in the `dependencies` group, never in readiness or liveness.
- An extra redaction pattern must be anchored and bounded: it runs on every value of every line.
- Delete `logback-spring.xml` — Boot prefers it and it silently disables the platform's format.
- The `local` profile check is "is `local` among the active profiles", not "is it the only one".

---

**Next:** [Chapter 4 — OpenAPI](04-openapi.md), which publishes the contract that
[Chapter 2](02-errors-validation.md)'s error shape belongs to.

**Reference:** [modules/logging.md](../../modules/logging.md) ·
[modules/observability.md](../../modules/observability.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md) ·
[observability strategy](../crosscutting/observability-strategy.md)

[Back to the book](../index.md)
