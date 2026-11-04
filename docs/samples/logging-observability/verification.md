# Verification — what was proven, and at which level

Platform commit `5b8e820`. Consumer evidence (3 consecutive runs, 75/75 each):
[`../../production-readiness/phase-02-logging-observability/evidence/consumer/`](../../production-readiness/phase-02-logging-observability/evidence/consumer/).
Each run's `assertions.tsv` lists every check; `check-*.json` hold the counts quoted here.

| # | Expected | Actual (runs 1–3) | Level |
|---|---|---|---|
| AC1 | Every line has the required fields; request lines carry the response's `traceId`/`spanId` | As expected; a 500 body and its ERROR line (with `stack_trace`) on the inbound trace | SLICE + CONSUMER |
| AC2 | CR/LF, quotes and forged JSON in a message or exception stay one event, one `message` | 1 event, 1 raw line, 0 forged events (message and exception) | UNIT + CONSUMER |
| AC3 | Platform writes only `traceId`/`spanId` to MDC; consumer keys pass through, credential-named ones masked; strict mode writes only listed keys | As expected (source-scan test; default and strict containers) | UNIT + SLICE + CONSUMER |
| AC4 | 200 parallel requests × 3: every line's trace belongs to its request, incl. `@Async` | 600 requests, 1 200 lines, 0 mismatched, 0 wrong bodies | SLICE + CONSUMER |
| AC5 | JWT, `Bearer`, `Basic`, password, `Authorization`, Emirates ID, PAN, e-mail, phone, credential MDC absent; negatives kept | 13 planted values absent from all output (stdout and stderr), 35 `****`; epoch millis, order ID, trace ID, UUID, timestamp present | UNIT + CONSUMER |
| AC6 | `LogSanitizer` beans applied to MDC and structured arguments (field and `message`) | As expected; seven Codex verification passes, final pass: no normal-API leak | UNIT + SLICE |
| AC7 | Prometheus: 401 anonymous, 200 with token; common tags; no tag value is a trace/span ID, UUID or JWT | 215 series, 0 problems | SLICE + CONSUMER |
| AC8 | 400 literal outbound URIs → ≤ 100 client `uri` values, one WARN; server unmatched paths collapse | 100 client values, 100 echo values, exactly one cap WARN, no leaked unmatched path | SLICE + CONSUMER |
| AC9 | Exposure: `health`, `info`, `prometheus`; others 401 anonymous / 404 with token; `/actuator/platform` 401 / 200 | As expected for `env`, `beans`, `heapdump`, `metrics`, `configprops`, `loggers`, `threaddump`; platform report lists observability ACTIVE | SLICE + CONSUMER |
| AC10 | DB stopped: readiness and liveness UP, `dependencies` DOWN; DB requests fail cleanly; DB back → UP | As expected; the DB request's 500 problem body is on its trace with no SQL/JDBC text | CONSUMER |
| AC11 | Real collector receives spans and metrics with `service.name`; outbound call on the same trace | 5 703 spans, 1 315–1 466 metric points, `service.name=hardening-obs`, 11 spans incl. 1 client span on the probed trace | CONSUMER |
| AC12 | Collector down / 10 s slow: status and latency unaffected, memory bounded, warnings bounded; startup without collector does not hang | All 300 × 4 requests 200; p99 7.5–11.6 ms vs baseline 11.2–12.1 ms; memory +5 to +34 MiB; 8 warnings down, 3–5 slow; startup 13 s vs 13–18 s healthy | CONSUMER |
| AC13 | Override, kill-switch and config-default snapshot tests for every bean and EPP default | As expected (logging 150 tests, observability 37) | SLICE |
| AC14 | SIGTERM: shutdown lines on stdout, exit 143; every stdout line JSON | Exit 143 in 2–11 s; ~1 280 stdout events, 0 non-JSON; `Graceful shutdown complete` is a JSON line | CONSUMER |
| AC15 | Downstream `-amd test-compile`; golden path 8/8 | 9 modules (every dependent: both starters, four examples, docs) exit 0; golden path 8/8, 1 073 s (`golden-path-summary-5b8e820.tsv`) | build |
| AC16 | Sampling posture documented with tail-sampling snippet; Boot default `0.1` verified | As expected; posture OPEN (deployment responsibility, D103) | docs + UNIT |

The Phase 1 consumer (92 checks) also passed against the Phase 2 code:
[`../../production-readiness/phase-02-logging-observability/evidence/phase-01-regression/`](../../production-readiness/phase-02-logging-observability/evidence/phase-01-regression/).

## Limitations

- No ENVIRONMENT-level evidence (OpenShift, real Prometheus scrape config): Phase 8.
- The Prometheus scrape is authenticated with a token until the management access decision (Phase 7).
- Redaction is pattern- and name-based. The residuals (Luhn-valid numeric IDs, landlines without
  prefix, deep or cyclic values, bean `toString` text, unparseable `raw(...)` text, custom markers) are
  listed in [`../../modules/logging.md`](../../modules/logging.md) and the phase review record.
- Database, security, openapi, restclient and validation starters are fixtures here; they are
  certified in their own phases.
