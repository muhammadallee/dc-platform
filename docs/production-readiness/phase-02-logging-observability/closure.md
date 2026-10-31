# Phase 2 — Logging + Observability: closure

**Verdict: PASS** (2026-10-28). The owner accepted the redaction residuals below on 2026-10-28. Earlier
owner decisions: amendment A1 (console format, D107) accepted 2026-10-26; e-mail and phone added to the
default PII list (D102) 2026-10-26.

| Item | Value |
|---|---|
| Phase start / final code commit | `a33168d` / `5b8e820` (consumer 3×, Phase 1 regression, golden path and downstream compile on `5b8e820`) |
| Modules changed | logging-autoconfigure, observability-autoconfigure, starter-observability, archetype templates, docs (book, modules, llms-full), hardening harness |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | done; A1 accepted (D107), A2 recorded | [`scope.md`](scope.md) |
| B implement + targeted tests | logging-autoconfigure 150, observability-autoconfigure 37, logging-api 5; 0 failures, 0 skipped, JaCoCo gates met | `evidence-raw/d17.log`, `evidence-raw/d4.log` |
| C round 1 | all findings dispositioned and fixed | [`review-round-1.md`](review-round-1.md) |
| D round 2 + 7 verification passes | round-2 findings fixed; passes 1–6 each found 3–5 items (mostly in code the previous pass added); pass 7: 4/4 accepted, one unusual-composition regression fixed (`81d0aa0`); pre-written outcome rule → Gate D closed | [`review-round-2.md`](review-round-2.md) |
| E register | HI-0031 … HI-0050 (HI-0041 OPEN → Phase 3) | [`../issue-register.md`](../issue-register.md) |
| F consumer | 3 consecutive runs, **75/75** each, `5b8e820`; trial 3 found a harness defect (stderr judged as stdout, `d0d7a13`) | [`evidence/consumer/`](evidence/consumer/) |
| F Phase 1 regression | Phase 1 consumer **92/92** on Phase 2 code | [`evidence/phase-01-regression/`](evidence/phase-01-regression/) |
| Downstream (§3.9) | `-amd test-compile` of the three changed modules: 9 modules (every dependent), exit 0 | `evidence-raw/downstream-compile-5b8e820.log` sha256 `4e9ab166…b9ae5` |
| Golden path | 8/8, 1 073 s, `5b8e820` | [`evidence/golden-path-summary-5b8e820.tsv`](evidence/golden-path-summary-5b8e820.tsv) |
| G composition | N/A (checkpoints at Phases 5 and 7) | — |
| H samples + operations | written | [`../../samples/logging-observability/`](../../samples/logging-observability/) |
| CI (PR #2) | **green on `dcadac2`** (run 40722527043): build + generator gate; re-confirmed on the closure commit before tagging (HI-0038 lesson) | GitHub Actions |

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | by type: `LogSanitizerRegistry`, `CapabilityGaugeRegistrar`, `PlatformEndpoint`; by name: `platformCommonTagsCustomizer`, `loggingCapabilityDescriptor`, `observabilityCapabilityDescriptor` (GX-04 guard added, HI-0036) |
| 7.2 default snapshot | logging and observability EPP defaults |
| 7.3 kill switch | `dc.platform.logging.enabled`, `dc.platform.observability.enabled`: `DEFAULT_ON`, tested; nested toggles |
| 7.4 outage posture | collector down / slow: fail-open for requests (p99 unchanged, bounded WARNs, memory bounded); Prometheus pull N/A |
| 7.5 health | readiness/liveness pod-local; `dependencies` group (D104); DB stopped → readiness UP, dependencies DOWN |
| 7.6 propagation | MDC on request and `@Async` lines, 600 requests × 2 lines, 0 mismatched; inbound and outbound spans on one trace at the collector |
| 7.7 error surface | unchanged from Phase 1; actuator 401/404 checked; DB failure body without SQL/JDBC text |
| 7.8 redaction | D102 set in message, arguments, MDC, exception, stack trace; 13 planted values absent, negatives kept; residuals below |
| 7.9 observability | common tags; no ID-shaped tag values; `uri` cap 100 (D106) |
| 7.10 graceful shutdown | `Graceful shutdown complete` as a JSON line, exit 143 |
| 7.11 startup | 13–18 s to ready; with the collector unreachable 13 s (no hang) |

## Consumer-visible changes

See CHANGELOG `## [Unreleased]`:
- readiness no longer includes `db`/`rabbit`/`redis` (new `dependencies` group);
- the chassis exposes `health,info,prometheus` only (the archetype opts in `platform`);
- credentials and personal data are masked in JSON logs; strict MDC mode is opt-in;
- `LogSanitizer` beans are applied;
- the banner is off in JSON mode;
- `raw(...)` and nested-`Kv` JSON shapes change;
- the observability starter brings the OTLP exporter.

## Owner acceptance (ACCEPTED 2026-10-28)

Redaction residuals, proposed in [`review-round-2.md`](review-round-2.md) (pass 7):

1. In `message`, a value nested more than 64 levels deep, or a container that contains itself, reads `****`.
2. Bean `toString` prose in `message` gets the text masks only.
3. The top-level W3C `traceId`/`spanId` fields are exempt.
4. Unparseable `raw(...)` text gets the text masks only (D109).
5. Console format is unmasked (D107, already accepted).
6. Custom `LogstashMarker` subclasses and custom serializers are not certified.

Also documented, not new: Luhn-valid numeric IDs and `050`–`058` 10-digit IDs are masked; UAE
landlines without a prefix are not.

## Open (non-blocking)

- HI-0041: `flyway-database-<db>` module for generated `data` services (Phase 3).
- G7-02: Prometheus scrape access (token until Phase 7).
- D103: errored-trace retention via collector tail sampling (deployment, OPEN).
- HI-0004 (book narrative).
