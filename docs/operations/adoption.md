# Platform adoption

How to see, per environment, which platform train each service runs and which capabilities it uses.
Everything here is derived from metrics the platform already emits — no separate reporting to wire.

## The signals

| Signal | Source | Emitted by |
|--------|--------|-----------|
| `platform.version` (common tag on every meter) | the platform jar manifest | observability common-tags (`CommonTagsAutoConfiguration`) |
| `platform.capability.active{capability=...}` gauge (0 or 1) | each capability's `CapabilityDescriptor` | observability `CapabilityMetricsAutoConfiguration` (phase-16 F.1) |
| deprecated-property warnings | Spring Boot's `spring.config` deprecation logs | log pipeline (count by service) |

`platform.capability.active` is `1` when a capability is active in a service and `0` when it is
present but inactive (for example, messaging on the classpath with no transport). Toggle it off with
`dc.platform.observability.capability-metrics.enabled=false`.

## What to track

- **Trains in production** — distinct `platform.version` values across `up` services, so you can see
  the spread and who is behind.
- **Capability adoption %** — `avg by (capability) (platform_capability_active)` across services.
- **Deprecated-property warnings** — count of Boot deprecation warnings per service, a leading
  indicator of upgrade friction.
- **Services on N‑2 or older** — anything more than two trains behind the current release, the
  support-window edge (see [upgrade](../upgrade/1.0.0-RC1.md)).

## Dashboard

`tooling/dashboards/platform-adoption.grafana.json` is an importable Grafana dashboard (Prometheus
data source) with panels for the four items above. Import it and point it at the environment's
Prometheus. The release pipeline links a per-train snapshot into the release notes once a Grafana
instance is available (phase-16 F.3).
