# Verification — what was proven, and at which level

Spring-native Phase 3, platform commit `7464f08` (code as of `6613528`). The service uses Boot's structured logging, SLF4J's key-value pairs,
Micrometer's `Tracer` and Boot's `RestClient.Builder`; the probe imports no platform type (checked in every run).

Consumer evidence (3 consecutive runs, **69/69 each**, floor 65):
[`../../spring-native/phase-03-logging-observability/evidence/`](../../spring-native/phase-03-logging-observability/evidence/).
Each run's `assertions.tsv` lists every check, and the summary is in `gateF-consumer-runs.txt`.

| # | Expected | Actual (runs 1–3) | Level |
|---|---|---|---|
| AC1 | Every line has the required fields; request lines carry the response's `traceId`/`spanId` | As expected; a 500 body and its ERROR line (with `stack_trace`) on the inbound trace | SLICE + CONSUMER |
| AC2 | CR/LF, quotes and forged JSON in a message or exception stay one event, one `message` | 1 event, 1 raw line, 0 forged events (message and exception) | UNIT + CONSUMER |
| AC3 | Platform writes only `traceId`/`spanId` to MDC; consumer MDC and key-value pairs pass through, credential-named ones masked; strict mode keeps only listed keys, key-value pairs included | As expected (default and strict containers, 0 problems) | UNIT + SLICE + CONSUMER |
| AC4 | 200 parallel requests × 3: every line's trace belongs to its request, incl. `@Async` | 600 requests, 1 200 lines, 0 mismatched, 0 wrong bodies | SLICE + CONSUMER |
| AC5 | JWT, `Bearer`, `Basic`, password, `Authorization`, Emirates ID, PAN, e-mail, phone, credential MDC absent; negatives kept | 13 planted values absent from all output, 31 `****`; all 5 negatives present | UNIT + CONSUMER |
| AC6 | A service masks its own data, numbers included: `dc.platform.logging.redaction.patterns`, or its own customizer next to the platform's | As expected (`extraPatternsReachNumbers`, `aServicesOwnCustomizerAddsToThePlatformMasking`) | UNIT + SLICE |
| AC7 | Prometheus: common tags; no tag value is a trace/span ID, UUID or JWT | 131 series, 0 problems | SLICE + CONSUMER |
| AC8 | 400 literal outbound URIs → ≤ 100 client `uri` values; server unmatched paths collapse | 100 client values, 100 echo values, no leaked unmatched path | SLICE + CONSUMER |
| AC9 | Management port: `health`, `info`, `prometheus` exposed, the rest 404; **application port: `/actuator/*` 404** | `env`, `beans`, `heapdump`, `metrics`, `configprops`, `loggers`, `threaddump`, `platform` 404; `info` 200; `prometheus`, `health`, `info` 404 on the application port | SLICE + CONSUMER |
| AC10 | DB stopped: readiness and liveness UP, `dependencies` DOWN; DB requests fail cleanly; DB back → UP | As expected; 503 `DC-DATA-0503` on the trace, no SQL/JDBC text | CONSUMER |
| AC11 | Real collector receives spans and metrics with `service.name`; outbound call on the same trace | 1 443 spans, 827–896 metric points, `service.name=hardening-obs`, 3 spans incl. 1 client span on the probed trace | CONSUMER |
| AC12 | Collector down / 10 s slow: requests unaffected, p99 within the bound, memory and warnings bounded; startup without collector does not hang | All 300 × 4 requests 200; p99 7.2–10.1 ms (bound 1 000 ms) vs baseline 8.1–14.3 ms; memory +2 to +28 MiB; warnings 7–8 down, 1–2 slow; startup 11 s vs 13–23 s healthy | CONSUMER |
| AC13 | Override, kill-switch and config-default tests; a service customizer adds to the masking; a user Logback file logs a WARN | As expected (logging 180 tests, observability 51) | SLICE |
| AC14 | SIGTERM: exit 143; every stdout line JSON; `Graceful shutdown complete` a JSON line | Exit 143 in 2–12 s; ~1 280 stdout events, 0 non-JSON | CONSUMER |
| AC15 | Golden path; downstream modules build | CI `build` and `generator-gate` green at `7464f08`; full reactor `verify` exit 0 | build |
| AC16 | Sampling posture documented with tail-sampling snippet | As expected; posture OPEN (deployment responsibility, D103) | docs |
| AC17 | `local` profile or `dc.platform.logging.format=console` gives Boot's console logging | As expected (`localProfileFallsBackToHumanReadableConsole`, `explicitConsoleFormatContributesNothing`) | UNIT |
| AC18 | No code references `Kv`, `LogSanitizer`, `PlatformJsonEncoder` or `logstash-logback-encoder` | Source scan: no use (`ac18-source-scan.txt`) | scan |

Phase 2's core-errors consumer (floor 77) also passed against this code: 89/89
(`phase-02-regression-core-errors-*`). Both labs, `first-service` and `logging-observability`, pass
`tooling/labs/run-lab.sh`.

Logging a library object no longer runs its getters: before the Gate H fix (HI-0166), Hikari's "Added connection"
line walked a JDBC connection's metadata. The runs above have no such line content.

## Limitations

- No ENVIRONMENT-level evidence (OpenShift, real Prometheus scrape config): Phase 8.
- The management port has no authentication; it is meant to stay inside the cluster (ND13).
- Redaction is pattern- and name-based. Its limits (Logback's own message formatting, a service's own Logback file or
  customizer processors, mixed ECS/GELF setups) are listed in [`../../modules/logging.md`](../../modules/logging.md)
  and the phase review records.
- The database starter is a fixture here; it is certified in Phase 4.
