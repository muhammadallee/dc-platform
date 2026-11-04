# Logging + observability — sample

## What it solves

Your service writes **one JSON object per stdout line**, and each line is tied to its request by
`traceId`/`spanId`. Credentials and common personal data never reach the log. Bearer tokens, JWTs,
`Basic` credentials and `password=`-style values are masked, and so are Emirates IDs, card numbers,
e-mail addresses and phone numbers. Each one becomes `****`, wherever it appears: message, arguments,
MDC, exception, stack trace. User input cannot forge a second line or field.

Next to the logs, you get:
- Prometheus metrics with the same three tags on every meter (`service`, `env`, `platform.version`).
- Health probes that keep a database outage from taking every pod out of rotation.
- Optional OTLP export to a collector, which keeps requests fast even when the collector is down.

## When to use / not use

- **Use** it for every service: logging and observability are part of the archetype baseline.
- **Do not** ship `console` format (the `local` profile) to log aggregation. It is for humans: not
  masked, and a CR/LF in a message can split a line.
- **Do not** point Kubernetes/OpenShift probes at `/actuator/health`: it still includes the database.
  Use `/actuator/health/readiness` and `/actuator/health/liveness`.
- **Do not** replace `logging.config` with your own Logback file unless it uses
  `PlatformJsonEncoder`. Without it, nothing is masked.

## Minimal real setup

Generate a service (see [`commands.md`](commands.md)); nothing to add for logs, metrics and probes. Log
with structured arguments:

```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("order {} accepted for {}", kv("orderId", order.id()), kv("customerEmail", order.email()));
```

gives (illustrative line; the consumer run asserts these properties, not this exact text):

```json
{"@timestamp":"2026-10-28T15:11:20.104Z","message":"order orderId=42 accepted for customerEmail=****",
 "logger":"ae.gov.dc.orders.OrderService","level":"INFO","service":"orders",
 "traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"b7ad6b7169203331",
 "orderId":42,"customerEmail":"****"}
```

To export traces and metrics to an OpenTelemetry Collector, as the consumer run did:

```yaml
dc.platform.observability.otlp.enabled: true
management:
  tracing.sampling.probability: 1.0        # let the collector tail-sample (see operations.md)
  opentelemetry.tracing.export.otlp.endpoint: http://otel-collector:4318/v1/traces
  otlp.metrics.export.url: http://otel-collector:4318/v1/metrics
```

## Walkthrough

1. **Format.** Outside the `local` profile, the platform's `logback-platform.xml` writes JSON through
   `PlatformJsonEncoder`; Boot's banner is off, so every stdout line parses.
2. **Correlation.** The core filter puts `traceId` and `spanId` in the MDC (the only MDC keys the
   platform writes). Your own MDC keys pass through and are masked by name, so a key like
   `session-token` is masked. To keep only listed keys, set
   `dc.platform.logging.mdc.include=tenant,channel`.
3. **Redaction.** It applies to every string the encoder writes, and by field name at any depth.
   `LogSanitizer` beans run first, for your own rules. The exact rules and known limits are in
   [`../../modules/logging.md`](../../modules/logging.md).
4. **Metrics.** `/actuator/prometheus` (token required under the security starter until Phase 7).
   Boot caps `uri` tag values at 100 per meter, so `"/orders/" + id` URIs cannot explode your series.
5. **Health.** `readiness` and `liveness` are pod-local. Shared systems (`db`, `rabbit`, `redis`) report
   in the `dependencies` group, for dashboards and alerts.
6. **Exposure.** Only `health`, `info`, `prometheus` by default; generated services add `platform`.

See [`verification.md`](verification.md) for what was proven, [`operations.md`](operations.md) for
running it, [`troubleshooting.md`](troubleshooting.md) when something looks off.
