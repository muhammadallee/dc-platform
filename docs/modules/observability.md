# Observability

Metrics with a consistent identity, health probes that work on any platform, correlation that
propagates without exploding your time-series storage, and one endpoint that tells you which
platform capabilities a running service actually has.

## What you get

- **Common meter tags** — every meter carries `service` (= `spring.application.name`),
  `env` (= first active profile) and `platform.version` (from the platform jar manifest), so
  dashboards slice uniformly across the fleet.
- **Health groups out of the box** — `/actuator/health/liveness` and
  `/actuator/health/readiness` exist everywhere (not only on Kubernetes); readiness additionally
  reflects `db`, `rabbit` and `redis` indicators **when present**.
- **`/actuator/platform`** — the `CapabilityDescriptor` report as JSON: which platform
  capabilities are ACTIVE in this running service, with provider detail.
- **Prometheus locally, OTLP when you have a collector** — the starter ships both registries;
  OTLP export is OFF by default because laptops have no collector.
- **Correlation via baggage, never metric tags** — the correlation id is high-cardinality (one
  value per request); it propagates downstream through tracing baggage (`X-Correlation-Id`) and
  is never added as a metric tag or observation key-value.

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
| `management.endpoints.web.exposure.include` | `health,info,platform,metrics,prometheus` |
| `management.endpoint.health.probes.enabled` | `true` |
| `management.endpoint.health.group.liveness.include` | `livenessState` |
| `management.endpoint.health.group.readiness.include` | `readinessState,db,rabbit,redis` |
| `management.endpoint.health.validate-group-membership` | `false` (the readiness list names optional members) |
| `management.tracing.baggage.remote-fields` | `X-Correlation-Id` |
| `management.otlp.metrics.export.enabled` | `false` (only while `otlp.enabled=false`) |
| `management.tracing.export.otlp.enabled` | `false` (only while `otlp.enabled=false`) |

Any user-set value outranks every one of these.

> **Exposure note:** `platform` is exposed over http by default. It reports capability names and
> providers — no secrets — and real endpoint security arrives with the phase-6 security baseline.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.observability.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.observability.common-tags.enabled` | `true` | Stamp `service`/`env`/`platform.version` on every meter. |
| `dc.platform.observability.otlp.enabled` | `false` | Enable OTLP export (metrics + traces). |
| `dc.platform.observability.health.groups.enabled` | `true` | Contribute liveness/readiness group defaults. |
| `dc.platform.observability.platform-endpoint.enabled` | `true` | Serve `/actuator/platform`. |

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
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
```

## Customize

- Define your own `MeterRegistryCustomizer` bean named `platformCommonTagsCustomizer` to replace
  the platform tags wholesale (any other bean name adds to them).
- Override any contributed `management.*` key in `application.yml` — user config always wins.

## Replace / Disable

- `dc.platform.observability.platform-endpoint.enabled=false` removes `/actuator/platform`.
- `management.endpoints.web.exposure.include=health` shrinks the http surface back to Boot's own.
- `dc.platform.observability.enabled=false` switches the capability off wholesale.

## Error codes

None — observability fails soft; a broken metrics/tracing setup must never take the service down.

## Testing

The `ApplicationContextRunner` matrix covers activation/back-off; a `RANDOM_PORT` boot test
proves the exposure + health-group defaults end to end (`/actuator/platform`,
`/actuator/health/liveness|readiness`). The EnvironmentPostProcessor is unit-tested against a
`MockEnvironment` — user values always win.

## Local dev notes

`/actuator/prometheus` is live locally with the starter (scrape it, no collector needed). If a
management default is not taking effect, `/actuator/env` shows whether
`platform-observability-defaults` was outranked by your configuration.
