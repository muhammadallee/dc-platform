# Observability

Metrics with a consistent identity, health probes that answer "can this pod serve" without turning a
shared outage into a fleet outage, correlation that propagates without exploding your time-series
storage, and an opt-in endpoint that tells you which platform capabilities a running service has.

## What you get

- **Common meter tags** — every meter carries `service` (= `spring.application.name`),
  `env` (= first active profile) and `platform.version` (from the platform jar manifest), so
  dashboards slice uniformly across the fleet.
- **Pod-local probes** — `/actuator/health/liveness` (`livenessState`) and
  `/actuator/health/readiness` (`readinessState`) exist everywhere, not only on Kubernetes. Neither
  checks an external system, so a database outage does not mark every replica unready at once.
- **A `dependencies` group** — `/actuator/health/dependencies` reports `db`, `rabbit` and `redis`
  **when present**, for dashboards and alerts.
- **Minimal exposure** — over http only `health`, `info` and `prometheus` are exposed. `metrics`,
  `platform` and everything else are an explicit opt-in.
- **`/actuator/platform` (opt-in)** — the `CapabilityDescriptor` report as JSON: which platform
  capabilities are ACTIVE in this running service, with provider detail. Generated services expose it.
- **Prometheus locally, OTLP when you have a collector** — the starter ships both registries;
  OTLP export is OFF by default because laptops have no collector.
- **Bounded metric tags** — the trace ID never becomes a metric tag or observation key-value, and
  Boot's `uri` tag cap (`management.metrics.web.server.max-uri-tags` /
  `management.metrics.web.client.max-uri-tags`, default 100) stops a client that builds URIs by string
  concatenation (`"/orders/" + id`) from creating one series per ID. Prefer templates
  (`uri("/orders/{id}", id)`) anyway.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-observability</artifactId>
</dependency>
```

## Zero-config behavior

An `EnvironmentPostProcessor` contributes management defaults through a lowest-precedence
property source named `platform-observability-defaults` (visible in `/actuator/env`):

| Contributed key | Value |
|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus` |
| `management.endpoint.health.probes.enabled` | `true` |
| `management.endpoint.health.group.liveness.include` | `livenessState` |
| `management.endpoint.health.group.readiness.include` | `readinessState` |
| `management.endpoint.health.group.dependencies.include` | `db,rabbit,redis` |
| `management.endpoint.health.validate-group-membership` | `false` (the dependencies list names optional members) |
| `management.tracing.baggage.remote-fields` | not set (was `X-Correlation-Id`; removed in the hardening phase 1) |
| `management.otlp.metrics.export.enabled` | `false` (only while `otlp.enabled=false`) |
| `management.tracing.export.otlp.enabled` | `false` (only while `otlp.enabled=false`) |

Any user-set value outranks every one of these. The top-level `/actuator/health` still aggregates every
contributor (the database included) — point probes at `readiness` and `liveness`, never at the top level.

> **Access:** with the security starter, `/actuator/health/**` and `/actuator/info` are open and
> `/actuator/prometheus` needs a token. How Prometheus scrapes in OpenShift (token, separate management
> port, or a permit rule) is decided with the security hardening; until then, scrape with a token.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.observability.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.observability.common-tags.enabled` | `true` | Stamp `service`/`env`/`platform.version` on every meter. |
| `dc.platform.observability.otlp.enabled` | `false` | Enable OTLP export (metrics + traces). |
| `dc.platform.observability.health.groups.enabled` | `true` | Contribute liveness/readiness/dependencies group defaults. |
| `dc.platform.observability.platform-endpoint.enabled` | `true` | Serve `/actuator/platform` when it is exposed. |

(Hand-written until the generated reference lands in phase 14.)

## Exporting to a collector

Set `dc.platform.observability.otlp.enabled=true` and Boot's own export configuration takes
over — point it at your collector:

```yaml
dc.platform.observability.otlp.enabled: true
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
```

(`management.otlp.tracing.endpoint` was removed in Boot 4 — it no longer has any effect.)

## Sampling

Tracing is head-sampled: `management.tracing.sampling.probability`, Boot default `0.1`. The trace ID in
logs and error bodies does not depend on sampling — an unsampled request still has one. A head sampler
cannot know a request will fail, so **keeping every failed request's trace is the collector's job**: run
the OpenTelemetry Collector with tail sampling and an error policy.

```yaml
# OpenTelemetry Collector (contrib) — keep all errored traces, 10 % of the rest.
processors:
  tail_sampling:
    decision_wait: 10s
    policies:
      - name: errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: baseline
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }
```

Tail sampling only sees spans the service exported, so with it in place set
`management.tracing.sampling.probability: 1.0` in the service and let the collector decide.

## Customize

- Define your own `MeterRegistryCustomizer` bean named `platformCommonTagsCustomizer` to replace
  the platform tags wholesale (any other bean name adds to them).
- Expose more endpoints: `management.endpoints.web.exposure.include: health,info,prometheus,platform,metrics`.
- Override any contributed `management.*` key in `application.yml` — user config always wins.

## Replace / Disable

- `dc.platform.observability.platform-endpoint.enabled=false` removes `/actuator/platform`.
- A bean named `observabilityCapabilityDescriptor` replaces the banner/report entry.
- `management.endpoints.web.exposure.include=health` shrinks the http surface back to Boot's own.
- `dc.platform.observability.enabled=false` switches the capability off wholesale.

## Error codes

None — observability fails soft; a broken metrics/tracing setup must never take the service down.

## Testing

The `ApplicationContextRunner` matrix covers activation/back-off; `RANDOM_PORT` boot tests prove the
exposure and health-group defaults end to end (a simulated `db` outage leaves readiness UP and the
`dependencies` group DOWN), the scrape content, and Boot's client `uri` cap. The
EnvironmentPostProcessor is unit-tested against a `MockEnvironment` — user values always win.

## Local dev notes

`/actuator/prometheus` is live locally with the starter (scrape it, no collector needed). If a
management default is not taking effect, `/actuator/env` shows whether
`platform-observability-defaults` was outranked by your configuration.
