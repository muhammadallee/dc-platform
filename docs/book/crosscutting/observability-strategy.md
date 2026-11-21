# Observability Strategy

> Logs to metrics to traces, joined by the trace ID: what the platform emits, and how to turn it into answers.

[Chapter 3](../chapters/03-logging-observability.md) covered the mechanisms. This chapter covers the
*strategy*: which signal answers which question, what the platform emits, and how to go from a page at 03:00 to a
root cause.

---

## 1. Three signals, three jobs

| Signal | Answers | Cardinality | Retention | Cost driver |
|---|---|---|---|---|
| **Logs** | "What happened in *this* request?" | Unbounded — one event per occurrence | Days to weeks | Volume |
| **Metrics** | "What is the rate, error ratio, and latency distribution?" | **Must stay bounded** | Months to years | Series count |
| **Traces** | "Where did the time go across services?" | Sampled | Days | Sampling rate |

The most common design mistake is using one for another's job: counting log lines to compute a rate (expensive and
slow), or putting per-request detail in a metric tag (unbounded cardinality).

**Use each for what it is cheap at, and join them with the trace ID.**

---

## 2. The join key

```
   HTTP request
     |  traceparent  (W3C Trace Context: continued if valid, otherwise a new trace)
     v
   Micrometer Tracing (Boot's observation of the request)
     |
     +--> MDC: traceId, spanId
     |
     +--------------+---------------------+
     |              |                     |
  every log     problem bodies       outbound calls
  line          (traceId field)      (RestClient.Builder: traceparent)
     |              |                     |
     +--------------+---------------------+
                    |
           the collector: one trace across services
```

Every arrow exists so an investigation can start anywhere and reach everywhere. A service reads the current trace
from Micrometer's `Tracer`; it never writes `traceId` or `spanId` itself.

Two properties are load-bearing and easy to lose:

**Outbound calls go through Boot's `RestClient.Builder`.** A `RestClient.create()` or a hand-built HTTP client has no
observation, so the callee starts a new trace and the join breaks at that hop.

**Work handed to another thread keeps the trace only through a context-propagating executor.** `@Async` on Boot's
executor does; a raw `new Thread` does not. A log line with no `traceId` in a request path is the symptom.

!!! warning "And it is deliberately absent from exactly one place"
    **Metric tags.** A trace ID is unique per request; tagging a meter with it creates one time series per request.
    The platform never makes it a tag or an observation key-value. Cross-service context that must travel goes in
    tracing **baggage** (`management.tracing.baggage.remote-fields`). This is the single most important rule in
    this chapter — see §5.

---

## 3. What the platform emits

### Log fields, on every line

| Field | Source |
|---|---|
| `@timestamp`, `level`, `logger`, `message` | Boot's structured logging, with the platform's format |
| `service` | `dc.platform.logging.service-name` → `spring.application.name` → `application` |
| `traceId`, `spanId` | MDC, from Micrometer Tracing |
| `stack_trace` | On `log.error(msg, throwable)` |
| *your MDC entries* | Written as they are (strict mode keeps only listed keys) |
| *key-value pairs* | `addKeyValue` at the call site |

Every value is masked by name and pattern before it is written: credentials, the default PII set and your own
`dc.platform.logging.redaction.patterns`. The [logging page](../../modules/logging.md) lists the limits.

### Meter tags, on every meter

`service` · `env` (the **first** active profile) · `platform.version` (from the jar manifest)

Those three are what make a fleet dashboard possible without per-service forks — and `platform.version` is adoption
telemetry for free: one query, grouped by that tag, tells the platform team exactly who has upgraded.

### Capability-specific signals

| Capability | Emits | Chapter |
|---|---|---|
| Core | *(plumbing — no meters)* | [1](../chapters/01-core.md) |
| Errors | The `code` and `traceId` fields on every problem body, and the correlated log line | [2](../chapters/02-errors-validation.md) |
| Observability | `platform.capability.active{capability}` 0/1 gauge | [3](../chapters/03-logging-observability.md) |
| Data | HikariCP pool metrics; `DC-DATA-0503` when the database is unreachable | [8](../chapters/08-data.md) |
| Messaging, REST client | Described in their chapters as their phases certify them | [7](../chapters/07-messaging-events.md), [6](../chapters/06-restclient-resilience.md) |

### Endpoints — on the management port, 8081

| Endpoint | Answers | Exposed by default |
|---|---|---|
| `/actuator/health/liveness` | Should the orchestrator restart me? | yes |
| `/actuator/health/readiness` | Should it send me traffic? (pod-local: no external check) | yes |
| `/actuator/health/dependencies` | Is a shared dependency (`db`, `rabbit`) down? | yes |
| `/actuator/prometheus` | Every meter | yes |
| `/actuator/info` | Build and service information | yes |
| `/actuator/platform` | **What platform behaviour is live here, with which provider?** | opt-in |
| `/actuator/metrics` | One meter and its tag values | opt-in |

The application port answers 404 for `/actuator/*`. 1.0.0 has no web security, so keep 8081 inside the cluster and
never route it through the gateway.

---

## 4. The setting everyone misses

`dc.platform.observability.otlp.enabled` defaults to **`false`**.

That is a *laptop* default — it exists so a local run never dials a collector that is not there. The consequence is
that a service deployed with untouched defaults has structured logs, working health probes, and Prometheus metrics,
and **no traces and no OTLP metrics at all**.

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
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/traces
  tracing:
    sampling:
      probability: 0.1
```

(`management.otlp.tracing.endpoint` was removed in Boot 4 and no longer has any effect.)

!!! success "Put this in your deployment template, not in each service's `application.yml`"
    It is a platform-wide operational decision, and per-service configuration means one service will always be
    missing it — usually the one you need traces from.

Second most-missed: **`spring.application.name` unset**. It is the `service` log field *and* the `service` meter
tag. Unset means every meter is tagged `service=application`, which makes a fleet dashboard useless in a way nobody
notices until they need it.

---

## 5. The cardinality rule

Metrics cost **series count**, and every distinct combination of tag values is a series, stored for as long as you
keep metrics, for every instance.

### How a cardinality incident actually unfolds

1. Someone adds `Timer.builder("order.processing").tag("orderId", id)`. Reasonable-looking; fine in development with
   twelve orders.
2. Production has 50,000 orders a day — 50,000 new series a day, per instance.
3. Prometheus memory climbs. Scrapes slow, because `/actuator/prometheus` serialises every series.
4. Scrapes time out. **You now have gaps in your metrics during the incident the metrics were supposed to explain.**
5. The backend hits its series limit and rejects writes — often for *other* services sharing it.

The tell is a slow, monotonic climb in backend memory correlating with a deploy.

!!! success "The tag test"
    Before adding a tag: *can I write down the complete set of values it will ever take?*

    | Legitimate | Never |
    |---|---|
    | `status` (a handful) | `orderId`, `userId`, `email` |
    | `outcome` (two) | `traceId` |
    | `provider` (three) | A URI with the path parameter interpolated |
    | `operation` (a few) | An exception message |

    Spring's own `uri` tag uses the **template** (`/orders/{id}`), which is bounded, and Boot caps the number of
    distinct `uri` values at 100 (`management.metrics.web.server.max-uri-tags`, `...client.max-uri-tags`). A
    hand-rolled URI tag has no cap.

### Finding the offender

Expose `metrics` where you investigate (`management.endpoints.web.exposure.include=health,info,prometheus,metrics`),
then:

```bash
curl -s localhost:8081/actuator/metrics | jq -r '.names[]' | while read m; do
  echo "$(curl -s "localhost:8081/actuator/metrics/$m" | jq '[.availableTags[].values|length] | add // 0') $m"
done | sort -rn | head
```

The top of that list is where an unbounded tag was introduced.

---

## 6. What to alert on

Not everything worth measuring is worth alerting on. This is the set that earns a page.

### Every service — the RED signals

| Signal | Meter | Alert when |
|---|---|---|
| Rate | `http_server_requests_seconds_count` | Sudden change either direction |
| Errors | The same, `status=~"5.."` | Error-budget burn rate |
| Duration | `http_server_requests_seconds_bucket` | p99 above SLO |
| Saturation | `hikaricp_connections_pending`, `jvm_memory_used_bytes` | Pool exhaustion, heap pressure |

### Platform-specific — the highest-value alerts

| Alert | Why it earns a page |
|---|---|
| **`DC-CORE-0500` rate** | Something reached the catch-all that nobody anticipated. Every occurrence is a bug |
| **`DC-DATA-0503` rate** | The database is unreachable; readiness stays up by design, so this is where it shows |
| **`dependencies` group DOWN** | A shared dependency is failing for the whole fleet |
| **Log lines without `traceId` in request paths** | A context leak: work escaped the request's thread without propagation |
| **Absence of a scheduled job's success** | A dead-man's switch — see below |
| Readiness flapping | The pod itself is unstable (readiness checks nothing external) |

!!! tip "The alert nobody sets up: 'nobody ran the job'"
    With several instances and a scheduled job, every instance that skips the run looks healthy. Emit a metric
    **on successful completion** and alert on its *absence*. That is the only signal distinguishing "another
    instance did it" from "no instance did it".

---

## 7. The 03:00 runbook

You are paged: error rate up. Work in this order.

**1. Is it real, and how bad?** — *metrics*

```bash
# error ratio, by service
sum(rate(http_server_requests_seconds_count{status=~"5..",service="orders-service"}[5m]))
  / sum(rate(http_server_requests_seconds_count{service="orders-service"}[5m]))
```

**2. What is actually deployed?** — `/actuator/platform` (where exposed), from inside the cluster

```bash
curl -s http://orders-service-pod:8081/actuator/platform | jq
```

Which capabilities, which providers. Two minutes here saves twenty later.

**3. Which failure?** — *logs, by `code`*

```
service:"orders-service" AND code:*  | count by code
```

`DC-CORE-0500` means unmapped — a defect. `DC-DATA-0503` means the database. A business code spiking means a client
changed.

**4. Is a shared dependency failing?** — *the `dependencies` group*

```bash
curl -s http://orders-service-pod:8081/actuator/health/dependencies | jq
```

**5. One failing request, end to end** — *the trace ID*

The problem body the client received carries `traceId`. Search for it:

```
traceId:"4bf92f3577b34da6a3ce929d0e0e4736"
```

Every line, every service that propagated the trace, in order.

**6. Where did the time go?** — *the trace*

Only useful if §4 was done. If there are no traces, that is your finding for tomorrow.

!!! success "Steps 2 and 3 are the ones people skip, and they are the cheapest"
    `/actuator/platform` and a count-by-`code` take under a minute and routinely eliminate half the hypotheses.
    Reaching for traces first is the common instinct and usually the slowest path.

---

## 8. Cost control

| Lever | Effect |
|---|---|
| **Log level** | `DEBUG` on a hot logger multiplies volume, ingest cost and encoding cost at once. The most common self-inflicted cost problem |
| **Sampling** | 1–10% is typical. 100% is affordable only in a low-traffic service, or when the collector tail-samples |
| **Cardinality** | §5. The dominant metrics cost, and the one that fails catastrophically rather than gradually |
| **Percentile histograms** | Far more expensive than counters. Enable per meter, never globally |
| **Dropped fields** | The platform's format leaves out `thread` and Logback's level value; a few fields × billions of lines is real storage |
| **Retention tiering** | Logs days, metrics months — do not pay log rates for long-lived records |

!!! warning "A sudden drop in log volume is a signal, not a cost saving"
    A broken logging setup degrades quietly and pages nobody. Monitor log volume per service, and treat a cliff as
    an incident.

---

## 9. Building a dashboard that works fleet-wide

Because every meter carries `service`, `env` and `platform.version`, **one** dashboard template works for every
service. Suggested rows:

1. **RED** — rate, error ratio, p99, from `http_server_requests_seconds`.
2. **Dependencies** — `http_client_requests_seconds` by client name; the `dependencies` health group.
3. **Data** — `hikaricp_connections_active` / `_pending`, query latency.
4. **Platform** — `platform.capability.active` and the `platform.version` distribution.

!!! tip "The fleet view worth building once"
    `count by (platform_version) (count by (service, platform_version) (up))` — services grouped by platform train.
    It is the platform team's adoption dashboard, and it costs nothing because the tag is already on every meter.

---

## 10. Checklist

**Configuration**

- [ ] `spring.application.name` set — it is the `service` field *and* meter tag
- [ ] `otlp.enabled: true` in **every** deployed environment, from the deployment template
- [ ] Collector endpoints and a sampling probability configured
- [ ] JSON logging everywhere except local (the default outside the `local` profile)
- [ ] Probes and the Prometheus scrape pointed at 8081; 8081 not routed through the gateway

**Instrumentation**

- [ ] Meters built in constructors, not per call
- [ ] Every tag passes the tag test
- [ ] `addKeyValue(...)` instead of string concatenation
- [ ] The trace ID is never logged explicitly — it is already a field
- [ ] Outbound calls through Boot's `RestClient.Builder`
- [ ] A success metric exists for every scheduled job

**Alerting**

- [ ] RED alerts per service
- [ ] `DC-CORE-0500` — any sustained rate
- [ ] `DC-DATA-0503` and the `dependencies` group
- [ ] Log lines without `traceId`
- [ ] Absence of scheduled-job success
- [ ] Log volume cliff

**Hygiene**

- [ ] Cardinality audited after each release
- [ ] No `DEBUG` left on a hot logger
- [ ] Retention tiered: logs / metrics

---

**Next:** [Local Development vs Production](local-vs-production.md) — where the defaults in this chapter are
deliberately different, and what that means for you.

**Related:** [Chapter 1 — Core](../chapters/01-core.md) ·
[Chapter 3 — Logging and Observability](../chapters/03-logging-observability.md)

[Back to the book](../index.md)
