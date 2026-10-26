# Phase 1 — Core + Error Handling: closure

**Verdict: PENDING OWNER DECISION** on scope amendments A1 and A3 (all gates green). If both are
accepted the verdict is **PASS**; if A3 is rejected, F12 (High) re-opens and the phase is blocked until a
type-based override is designed or an exception is approved.

| Item | Value |
|---|---|
| Phase start / final commit | `f231689` / `f8d5682` (consumer + golden path evidence on `e2be16d`; later commits change only tests, an out-of-scope in-memory messaging fix, and docs) |
| Modules changed | core-api, core-autoconfigure, starter-core, errors-autoconfigure, security-autoconfigure, restclient-api, restclient-autoconfigure, observability-autoconfigure, logging-autoconfigure, test-api, openapi-autoconfigure, archetype templates, example-minimal, golden-path gate, hardening harness |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | done | [`scope.md`](scope.md) (incl. amendments A1–A3) |
| B implement + targeted tests | core 53 ×3, errors 58 ×3, security 20, restclient 23, observability 28, example-minimal 8 — all green, 0 skipped | module logs (`evidence-raw/`) |
| C round 1 | 10 findings (4 High) → all FIXED; +2 High from the consumer run → FIXED | [`review-round-1.md`](review-round-1.md) |
| D round 2 + verification | 5 findings (2 High) → FIXED (F12 functionally; A3 pending) | [`review-round-2.md`](review-round-2.md) |
| E register | HI-0003…HI-0028 | [`../issue-register.md`](../issue-register.md) |
| F consumer | 3 consecutive runs, **92/92** each, `e2be16d`; 600 concurrent context calls + 600 concurrent error bodies per run, 0 bleed; SIGTERM exit 143 | [`evidence/consumer/`](evidence/consumer/) |
| Downstream (§3.9) | `-amd test-compile` 86 modules incl. Kafka/audit, exit 0 | `evidence-raw/downstream-compile-final.log` sha256 `dc68a4de…6955` |
| Golden path | 8/8, 815 s, `e2be16d` | [`evidence/golden-path-summary-e2be16d.tsv`](evidence/golden-path-summary-e2be16d.tsv) |
| H samples + operations | written | [`../../samples/core-errors/`](../../samples/core-errors/) |
| CI (PR #2) | **green on `f8d5682`**: full reactor `mvn -B -T1C verify` with all gates + generator gate (run 40280337049). Red runs before it were HI-0029 (test-order log pollution, mine) and HI-0030 (pre-existing in-memory messaging race) — both fixed | GitHub Actions |

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | type + name for platform types; name for shared types (A3) — tests per bean, started-container registration check |
| 7.2 default snapshot | core, errors |
| 7.3 kill switch | core, errors `DEFAULT_ON`, tested |
| 7.4 outage posture | N/A (no backing dependency) |
| 7.5 health | N/A (Phase 2) |
| 7.6 propagation | `@Async`, Boot executors, MVC ASYNC/ERROR dispatches, snapshot isolation |
| 7.7 error surface | every path → problem+json with `code`/`traceId`, no 5xx messages |
| 7.8 redaction | no 5xx messages, no rejected values by default, no header content in logs, no tokens in logs |
| 7.9 observability | trace ID never a metric tag; framework 5xx logged |
| 7.10 graceful shutdown | in-flight request completes across SIGTERM, exit 143 |
| 7.11 startup | 8–9 s to ready in the container (no dependency) |

## Consumer-visible changes

See CHANGELOG `### Breaking (capability hardening, phase 1)`: `X-Correlation-Id` removed; `correlationId`
→ `traceId` in logs and ProblemDetail; `retryable`; no 5xx messages; `rejectedValue` off by default;
removed properties deprecated at level `error`; `CorrelationIdFilter(Tracer)`; core starter brings the
OTel tracing bridge (no exporter).

## Open (non-blocking)

HI-0004 (book narrative), HI-0010 (problem-type namespace), HI-0028/A1 (pending owner).
