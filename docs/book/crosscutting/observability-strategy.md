# Observability Strategy

> Correlation to logs to metrics to traces: what the platform emits, and how to turn it into answers.

[Chapter 3](../chapters/03-logging-observability.md) covered the mechanisms. This chapter covers the
*strategy*: which signal answers which question, what every capability emits, and how to go from a page
at 03:00 to a root cause.

---

## 1. Three signals, three jobs

| Signal | Answers | Cardinality | Retention | Cost driver |
|---|---|---|---|---|
| **Logs** | "What happened in *this* request?" | Unbounded — one event per occurrence | Days to weeks | Volume |
| **Metrics** | "What is the rate, error ratio, and latency distribution?" | **Must stay bounded** | Months to years | Series count |
| **Traces** | "Where did the time go across services?" | Sampled | Days | Sampling rate |

The most common design mistake is using one for another's job: counting log lines to compute a rate
(expensive and slow), or putting per-request detail in a metric tag (unbounded cardinality).

**Use each for what it is cheap at, and join them with the correlation id.**

---

## 2. The join key

```
   HTTP request
     |  X-Correlation-Id  (read, or generated as 32 lowercase hex)
     v
   CorrelationIdFilter    HIGHEST_PRECEDENCE — ahead of security
     |
     +--> RequestContext ----> MDC
                                |
      +--------------+----------+----------+------------------+
      |              |                     |                  |
   every log     problem              message           audit events
   line          responses            headers           (correlationId field)
      |              |                     |                  |
      +--------------+---------------------+------------------+
                     |
              outbound REST calls  (X-Correlation-Id)
                     |
              and back: RemoteCallException.remoteCorrelationId()
                     |
              across services: tracing BAGGAGE
```

Every arrow in that diagram exists so an investigation can start anywhere and reach everywhere.

Two properties are load-bearing and easy to lose:

**The filter is outermost.** Ahead of the security chain, so a rejected authentication still produces a
correlated log line — the population an incident is usually about, and the one most hand-rolled
implementations miss.

**The id is a validated type.** 32 lowercase hex, matching W3C Trace Context's `trace-id` field. A
malformed inbound header is discarded rather than propagated, so an attacker cannot inject newlines or
escape sequences into your log store through it.

!!! warning "And it is deliberately absent from exactly one place"
    **Metric tags.** A correlation id is unique per request; tagging a meter with it creates one time
    series per request. It propagates through tracing **baggage**
    (`management.tracing.baggage.remote-fields`) instead. This is the single most important rule in
    this chapter — see §5.

---

## 3. What the platform emits

### Log fields, on every line

| Field | Source |
|---|---|
| `@timestamp`, `level`, `logger`, `message` | Logback |
| `service` | `logging.service-name` → `spring.application.name` → `application` |
| `correlationId` | MDC, via the core filter |
| `stack_trace` | On `log.error(msg, throwable)` |
| *anything in MDC* | Lifted automatically |
| *`Kv` arguments* | Lifted by the encoder |

Deliberately dropped: `thread`, `version`, `levelValue`.

### Meter tags, on every meter

`service` · `env` (the **first** active profile) · `platform.version` (from the jar manifest)

Those three are what make a fleet dashboard possible without per-service forks — and
`platform.version` is adoption telemetry for free: one query, grouped by that tag, tells the platform
team exactly who has upgraded.

### Capability-specific signals

| Capability | Emits | Chapter |
|---|---|---|
| Core | *(plumbing — no meters)* | [1](../chapters/01-core.md) |
| Errors | The `code` field on every problem body and correlated log | [2](../chapters/02-errors-validation.md) |
| Observability | `platform.capability.active{capability}` 0/1 gauge | [3](../chapters/03-logging-observability.md) |
| REST client | `http.client.requests`, tagged by client name | [6](../chapters/06-restclient-resilience.md) |
| Resilience | `resilience4j_circuitbreaker_state`, `_failure_rate`, `_not_permitted_calls_total`, `resilience4j_retry_calls_total` | [6](../chapters/06-restclient-resilience.md) |
| Messaging | `platform.messaging.published{outcome}`, `platform.messaging.handled{outcome}` | [7](../chapters/07-messaging-events.md) |
| Data | HikariCP pool metrics, Hibernate statistics | [8](../chapters/08-data.md) |
| Cache | `cache_gets_total{result}`, `cache_size`, `cache_evictions_total` | [9](../chapters/09-cache-redis.md) |
| Storage | `dc.platform.storage` observation, tagged `operation` and `provider` | [11](../chapters/11-storage-files.md) |
| Audit | The `DC-AUDIT-0500` dropped-event WARN, with a running total | [12](../chapters/12-audit.md) |
| Rate limiting | `dc.platform.ratelimit.decisions{name, outcome}`, and `DC-RATELIMIT-0500` | [13](../chapters/13-ratelimit-flags.md) |

### Endpoints

| Endpoint | Answers |
|---|---|
| `/actuator/health/liveness` | Should the orchestrator restart me? |
| `/actuator/health/readiness` | Should it send me traffic? |
| `/actuator/platform` | **What platform behaviour is live here, with which provider?** |
| `/actuator/env` | Why is this property's value what it is? |
| `/actuator/prometheus` | Every meter |
| `/actuator/configprops` | Bound configuration, with values |

---

## 4. The setting everyone misses

`dc.platform.observability.otlp.enabled` defaults to **`false`**.

That is a *laptop* default — it exists so a local run never dials a collector that is not there. The
consequence is that a service deployed with untouched defaults has structured logs, working health
probes, and Prometheus metrics, and **no traces and no OTLP metrics at all**.

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

!!! success "Put this in your deployment template, not in each service's `application.yml`"
    It is a platform-wide operational decision, and per-service configuration means one service will
    always be missing it — usually the one you need traces from.

Second most-missed: **`spring.application.name` unset**. It is the `service` log field *and* the
`service` meter tag. Unset means every meter is tagged `service=application`, which makes a fleet
dashboard useless in a way nobody notices until they need it.

---

## 5. The cardinality rule

Metrics cost **series count**, and every distinct combination of tag values is a series, stored forever,
on every instance.

### How a cardinality incident actually unfolds

1. Someone adds `Timer.builder("order.processing").tag("orderId", id)`. Reasonable-looking; fine in
   development with twelve orders.
2. Production has 50,000 orders a day — 50,000 new series a day, per instance.
3. Prometheus memory climbs. Scrapes slow, because `/actuator/prometheus` serialises every series.
4. Scrapes time out. **You now have gaps in your metrics during the incident the metrics were supposed
   to explain.**
5. The backend hits its series limit and rejects writes — often for *other* services sharing it.

The tell is a slow, monotonic climb in backend memory correlating with a deploy.

!!! success "The tag test"
    Before adding a tag: *can I write down the complete set of values it will ever take?*

    | Legitimate | Never |
    |---|---|
    | `status` (a handful) | `orderId`, `userId`, `email` |
    | `outcome` (two) | `correlationId` |
    | `provider` (three) | A URI with the path parameter interpolated |
    | `operation` (a few) | An exception message |

    Note that Spring's own `uri` tag uses the **template** (`/orders/{id}`), which is bounded. A
    hand-rolled URI tag usually is not.

### Finding the offender

```bash
curl -s localhost:8080/actuator/metrics | jq -r '.names[]' | while read m; do
  echo "$(curl -s "localhost:8080/actuator/metrics/$m" | jq '[.availableTags[].values|length] | add // 0') $m"
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
| **Circuit breaker → `open`** | Fires *before* your error rate climbs, and names the dependency |
| **`platform.messaging.handled{outcome="dlq"}`** | A handler is consistently failing. Faster and more actionable than DLQ depth |
| **`DC-AUDIT-0500`** | An action happened and was **not recorded**. Alert on the first one |
| **`DC-RATELIMIT-0500`** | Failing open — you have no rate limiting *right now*, and requests succeed |
| **Correlation coverage dropping** | A context leak; something escaped the request thread |
| **Absence of a scheduled job's success** | A dead-man's switch — see below |
| Readiness flapping | A dependency health indicator is unstable |

!!! tip "The alert nobody sets up: 'nobody ran the job'"
    With [`@LockedSchedule`](../chapters/10-coordination.md), every instance logging "skipped" looks
    healthy per instance and means the work did not happen. Emit a metric **on successful completion**
    and alert on its *absence*. That is the only signal distinguishing "another instance did it" from
    "no instance did it".

!!! warning "Three alerts that are silent by construction"
    `DC-RATELIMIT-0500` (fail-open produces successes), `DC-AUDIT-0500` (shedding produces successes),
    and the `@LockedSchedule` unlocked warning (the job still runs). None degrades anything visible.
    All three need an alert rather than a dashboard, because nobody will go looking.

---

## 7. The 03:00 runbook

You are paged: error rate up. Work in this order.

**1. Is it real, and how bad?** — *metrics*

```bash
# error ratio and p99, by service
sum(rate(http_server_requests_seconds_count{status=~"5..",service="orders-service"}[5m]))
  / sum(rate(http_server_requests_seconds_count{service="orders-service"}[5m]))
```

**2. What is actually deployed?** — `/actuator/platform`

```bash
curl -s https://orders-service/actuator/platform | jq
```

Which capabilities, which providers. Two minutes here saves twenty later when it turns out the service
is running the in-memory transport in production.

**3. Which failure?** — *logs, by `code`*

```
service:"orders-service" AND code:*  | count by code
```

`DC-CORE-0500` means unmapped — a defect. `DC-RCLIENT-0500` means a dependency. A business code
spiking means a client changed.

**4. Is a dependency failing?** — *breaker state*

```bash
curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq
```

An open breaker names the dependency and usually *is* the answer.

**5. One failing request, end to end** — *the correlation id*

```
correlationId:"9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7"
```

Every line, every service, in order. If the failure crossed a service boundary,
`RemoteCallException.remoteCorrelationId()` in the caller's log gives you the callee's id.

**6. Where did the time go?** — *the trace*

Only useful if §4 was done. If there are no traces, that is your finding for tomorrow.

**7. Was it a person?** — *audit*

```
action:"declaration.approve" AND at:[now-1h TO now]
```

Includes `FAILURE` outcomes — often the more interesting ones.

!!! success "Steps 2 and 3 are the ones people skip, and they are the cheapest"
    `/actuator/platform` and a count-by-`code` take under a minute and routinely eliminate half the
    hypotheses. Reaching for traces first is the common instinct and usually the slowest path.

---

## 8. Cost control

| Lever | Effect |
|---|---|
| **Log level** | `DEBUG` on a hot logger multiplies volume, ingest cost, and encoding cost at once. The most common self-inflicted cost problem |
| **Sampling** | 1–10% is typical. 100% is affordable only in a low-traffic service |
| **Cardinality** | §5. The dominant metrics cost, and the one that fails catastrophically rather than gradually |
| **Percentile histograms** | Far more expensive than counters. Enable per meter, never globally |
| **Dropped encoder fields** | Three fields × billions of lines × every service is real storage |
| **Retention tiering** | Logs days, metrics months, audit years — do not pay log rates for audit retention |

!!! warning "A sudden drop in log volume is a signal, not a cost saving"
    [Logging is fail-soft](../chapters/03-logging-observability.md): a broken setup degrades quietly and
    pages nobody. Monitor log volume per service, and treat a cliff as an incident.

---

## 9. Building a dashboard that works fleet-wide

Because every meter carries `service`, `env`, and `platform.version`, **one** dashboard template works
for every service. Suggested rows:

1. **RED** — rate, error ratio, p99, from `http_server_requests_seconds`.
2. **Dependencies** — `http_client_requests_seconds` by client name, plus breaker states.
3. **Async** — `platform.messaging.published` and `.handled` by outcome; DLQ depth.
4. **Data** — `hikaricp_connections_active` / `_pending`, query latency.
5. **Cache** — hit ratio from `cache_gets_total{result}`. The number nobody checks.
6. **Platform** — `platform.capability.active` and the `platform.version` distribution.

!!! tip "The fleet view worth building once"
    `count by (platform_version) (count by (service, platform_version) (up))` — services grouped by
    platform train. It is the platform team's adoption dashboard, and it costs nothing because the tag
    is already on every meter.

---

## 10. Checklist

**Configuration**

- [ ] `spring.application.name` set — it is the `service` field *and* meter tag
- [ ] `otlp.enabled: true` in **every** deployed environment, from the deployment template
- [ ] Collector endpoints and a sampling probability configured
- [ ] `format: json` everywhere except local
- [ ] Audit routed to its own appender with its own retention

**Instrumentation**

- [ ] Meters built in constructors, not per call
- [ ] Every tag passes the tag test
- [ ] `Kv.of(...)` instead of string concatenation
- [ ] The correlation id is never logged explicitly — it is already a field
- [ ] A success metric exists for every scheduled job

**Alerting**

- [ ] RED alerts per service
- [ ] `DC-CORE-0500` — any sustained rate
- [ ] Circuit breaker → `open`
- [ ] `outcome="dlq"` — any occurrence
- [ ] `DC-AUDIT-0500` — **first** occurrence
- [ ] `DC-RATELIMIT-0500` — **first** occurrence
- [ ] Correlation coverage dropping
- [ ] Absence of scheduled-job success
- [ ] Log volume cliff

**Hygiene**

- [ ] Cardinality audited after each release
- [ ] No `DEBUG` left on a hot logger
- [ ] Cache hit ratios reviewed — a 2% cache is costing you
- [ ] Retention tiered: logs / metrics / audit

---

**Next:** [Local Development vs Production](local-vs-production.md) — where the defaults in this
chapter are deliberately different, and what that means for you.

**Related:** [Chapter 1 — Core](../chapters/01-core.md) ·
[Chapter 3 — Logging and Observability](../chapters/03-logging-observability.md) ·
[Chapter 12 — Audit](../chapters/12-audit.md)

[Back to the book](../index.md)
