# Phase 1 — Core + Error Handling: closure

**Verdict: PASS** (2026-10-26). Owner decisions: A1 accepted (D100); A3 narrowed and accepted — the
correlation filter registration and the banner runner override by type (D98), capability descriptors by
name (D97); HI-0010 fixed with the neutral default `urn:dc:problem:` (D99).

| Item | Value |
|---|---|
| Phase start / final commit | `f231689` / `76cfcc7` (consumer 3× and golden path evidence on `76cfcc7`) |
| Modules changed | core-api, core-autoconfigure, starter-core, errors-autoconfigure, security-autoconfigure, restclient-api, restclient-autoconfigure, observability-autoconfigure, logging-autoconfigure, test-api, openapi-autoconfigure, archetype templates, example-minimal, golden-path gate, hardening harness |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | done | [`scope.md`](scope.md) (amendments A1–A3, all accepted) |
| B implement + targeted tests | core 53 ×3, errors 58 ×3, security 20, restclient 23, observability 28, example-minimal 8; after the owner decisions core 58, errors 58, security 20 — all green, 0 skipped | module logs (`evidence-raw/`) |
| C round 1 | 10 findings (4 High) → all FIXED; +2 High from the consumer run → FIXED | [`review-round-1.md`](review-round-1.md) |
| D round 2 + verification | 5 findings (2 High) → FIXED; v2 verification after the owner decisions: A3/F12/AC16 and HI-0010 accepted, one regression (banner log category) fixed in `9fc9067` and accepted | [`review-round-2.md`](review-round-2.md) |
| E register | HI-0003…HI-0030 | [`../issue-register.md`](../issue-register.md) |
| F consumer | 3 consecutive runs, **92/92** each, `76cfcc7`; 600 concurrent context calls + 600 concurrent error bodies per run, 0 bleed; SIGTERM exit 143 | [`evidence/consumer/`](evidence/consumer/) |
| Downstream (§3.9) | `-amd test-compile` 86 modules incl. Kafka/audit, exit 0, on `cf16aa8` (later commits: banner logger field + docs) | `evidence-raw/downstream-compile-a3.log` sha256 `8c6e416a…3e93` |
| Golden path | 8/8, `76cfcc7` | [`evidence/golden-path-summary-76cfcc7.tsv`](evidence/golden-path-summary-76cfcc7.tsv) |
| H samples + operations | written | [`../../samples/core-errors/`](../../samples/core-errors/) |
| CI (PR #2) | **green on `76cfcc7`** (run 40359609085), full reactor `mvn -B -T1C verify` with all gates + generator gate | GitHub Actions |

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | by type for single platform beans (`FilterRegistrationBean<CorrelationIdFilter>` / `CorrelationIdFilter` via `@ConditionalOnMissingFilterBean`, `PlatformBannerRunner`, errors beans); by name for one-of-many `CapabilityDescriptor` beans (D97, Boot health-indicator precedent). Negative tests: unrelated filter registrations, runners and descriptors do not suppress the platform's; started-container registration check |
| 7.2 default snapshot | core, errors |
| 7.3 kill switch | core, errors `DEFAULT_ON`, tested |
| 7.4 outage posture | N/A (no backing dependency) |
| 7.5 health | N/A (Phase 2) |
| 7.6 propagation | `@Async`, Boot executors, MVC ASYNC/ERROR dispatches, snapshot isolation |
| 7.7 error surface | every path → problem+json with `code`/`traceId`, no 5xx messages; type `urn:dc:problem:<code>` |
| 7.8 redaction | no 5xx messages, no rejected values by default, no header content in logs, no tokens in logs |
| 7.9 observability | trace ID never a metric tag; framework 5xx logged |
| 7.10 graceful shutdown | in-flight request completes across SIGTERM, exit 143 |
| 7.11 startup | 8–9 s to ready in the container (no dependency) |

## Consumer-visible changes

See CHANGELOG `### Breaking (capability hardening, phase 1)`: `X-Correlation-Id` removed; `correlationId`
→ `traceId` in logs and ProblemDetail; `retryable`; no 5xx messages; `rejectedValue` off by default;
removed properties deprecated at level `error`; `CorrelationIdFilter(Tracer)`; core starter brings the
OTel tracing bridge (no exporter); problem type default `urn:dc:problem:`; correlation filter and banner
override by type (no injectable default filter bean).

## Open (non-blocking)

- HI-0004 (book narrative).
- GX-04: 21 capability-descriptor beans in 20 modules still lack the D97 name guard; in-scope ones are
  fixed in their own phases ([`../phase-00-baseline/gap-inventory.md`](../phase-00-baseline/gap-inventory.md)).
- Ratelimit's own `about:blank` problem type (out of scope, noted by Codex v2-verify).
