# Operations

## Posture

| Situation | Behaviour | Verified |
|---|---|---|
| OTLP collector unreachable | Requests unaffected (status and latency); export failures logged as bounded WARNs; startup does not wait for it | CONSUMER (Toxiproxy disabled; 300 × 2 requests all 200, p99 ≤ 8.9 ms; 8 WARNs) |
| OTLP collector slow (10 s latency) | Same: export runs off the request path | CONSUMER (p99 ≤ 11.6 ms; 3–5 WARNs; memory +5 to +34 MiB) |
| OTLP export off (default) | No collector needed; Prometheus pull only | SLICE |
| Database down | `readiness` and `liveness` UP (pod stays in rotation), `dependencies` DOWN, DB requests → 500 problem body without SQL/JDBC text, other requests 200 | CONSUMER |
| Prometheus scrape | Pull, so no outage path; needs a bearer token under the security starter (interim) | CONSUMER |
| Logging misconfigured | Fails soft: a throwing `LogSanitizer` is skipped and the platform masks still run; an argument whose rendering throws reads `[unrenderable]` | UNIT |

## Health groups

| Group | Members | Use it for |
|---|---|---|
| `/actuator/health/liveness` | `livenessState` | liveness probe |
| `/actuator/health/readiness` | `readinessState` | readiness probe (pod-local: a shared outage does not empty the fleet) |
| `/actuator/health/dependencies` | `db`, `rabbit`, `redis` when present | dashboards and alerts, never a probe |
| `/actuator/health` | everything | humans only; do not probe it |

Details are hidden on unauthenticated access (Boot default `show-details: never`).

## What to watch

| Signal | Where | Why |
|---|---|---|
| `dependencies` status DOWN | `/actuator/health/dependencies` | a shared system is down while pods keep serving |
| `platform.capability.active` | Prometheus | a capability expected ACTIVE is missing after a deploy |
| `http.server.requests` 5xx rate, p99 by `uri` | Prometheus | errors and latency; `uri` is templated and capped at 100 values |
| WARN `Reached the maximum number of 'uri' tags` | logs | a client builds URIs by concatenation; fix it with a URI template |
| WARN/ERROR from the OTLP exporter | logs | the collector is unreachable or slow (requests are unaffected) |
| `****` count rising sharply in a logger | log platform | someone is logging credentials or personal data; fix the call site |

Never use `traceId`/`spanId` as a metric tag or alert key (unbounded cardinality); search logs by them.

Keep every failed request's trace with collector tail sampling. Set the service's
`management.tracing.sampling.probability: 1.0` and let the collector decide:

```yaml
processors:
  tail_sampling:
    decision_wait: 10s
    policies:
      - { name: errors, type: status_code, status_code: { status_codes: [ERROR] } }
      - { name: baseline, type: probabilistic, probabilistic: { sampling_percentage: 10 } }
```

## Kill-switch tier

| Switch | Tier | Effect of `false` |
|---|---|---|
| `dc.platform.logging.enabled` | `DEFAULT_ON` | Boot's default logging: no JSON encoder, **no masking** |
| `dc.platform.logging.redaction.enabled` | `DEFAULT_ON` | JSON without masking and without `LogSanitizer` beans: a break-glass for debugging only, never in production |
| `dc.platform.observability.enabled` | `DEFAULT_ON` | No common tags, no health-group, exposure or OTLP-off defaults (Boot's own apply), no `/actuator/platform`, no capability gauge |
| `dc.platform.observability.otlp.enabled` | opt-in (`false`) | No OTLP export |

There is no break-glass property beyond these: neither capability is `MANDATORY`.

## Graceful shutdown

On SIGTERM the app finishes in-flight work, logs `Graceful shutdown complete` as a JSON line on
stdout and exits with code 143 (verified: 2–11 s). The appender is a synchronous `ConsoleAppender`, so
no buffered lines are lost. Set the orchestrator's termination grace above
`spring.lifecycle.timeout-per-shutdown-phase` (default 30 s).

## How to replace bean X

| Bean | Replace by |
|---|---|
| JSON encoder | your own `logging.config` using `PlatformJsonEncoder` (any `LogstashEncoder` option works) |
| `LogSanitizerRegistry` | a bean of that type; add rules with `LogSanitizer` beans instead |
| common tags | a `MeterRegistryCustomizer` bean named `platformCommonTagsCustomizer` (other names add to them) |
| `CapabilityGaugeRegistrar` | a bean of that type, or `dc.platform.observability.capability-metrics.enabled=false` |
| `PlatformEndpoint` | a bean of that type, or `dc.platform.observability.platform-endpoint.enabled=false` |
| capability descriptors | a bean named `loggingCapabilityDescriptor` / `observabilityCapabilityDescriptor` |
| any `management.*` default | set the key yourself: user values outrank `platform-observability-defaults` |
