# Phase 4 — Transactional Outbox: closure

**Verdict: PASS with parked issues** (2026-11-01; see Owner decisions). Tag pending CI on the closure commit. Earlier owner decisions: platform JDBC module (HD11), no platform users so
breaking changes need only a CHANGELOG entry (HC7), Oracle only (HD3), prod on 19c (HD13).

| Item | Value |
|---|---|
| Phase start / final code commit | `39e354d` / `03dee13` (consumer 3x, Phases 1-3 regressions, golden path and downstream compile on `03dee13`; harness final at `6bbf91c`) |
| Modules changed | new `platform-messaging-outbox`, `platform-messaging-outbox-autoconfigure`, `platform-starter-messaging-outbox`; messaging-api (`OutboxPublisher`, `EventEnvelope.eventId`), messaging-rabbit (publisher confirms), Rabbit starter, messaging-autoconfigure, events-autoconfigure (relay), BOM, hardening harness, docs |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | done; amendments A1 (Oracle only), A2 (no archetype feature), A3 (park on `max-rejections`) | [`scope.md`](scope.md) |
| B implement + targeted tests | event id, Rabbit confirms, outbox module, autoconfiguration, relay; D115-D119 | `evidence-raw/` |
| C round 1 | 23 findings: 21 fixed, F7 downgraded to Medium (HI-0078), F17 partly done | [`review-round-1.md`](review-round-1.md) |
| D round 2 | fixed or accepted as residuals | [`review-round-2.md`](review-round-2.md) |
| E register | HI-0072 ... HI-0086 | [`../issue-register.md`](../issue-register.md) |
| F consumer | three closing runs **106/106** each on `03dee13`; trials found HI-0083, HI-0084, HI-0085 | [`consumer-run.md`](consumer-run.md), [`evidence/gate-f-closing-1..3`](evidence/) |
| F regressions | Phase 1 **92/92**, Phase 2 **75/75**, Phase 3 **92/92** on `03dee13` | [`evidence/`](evidence/) |
| Targeted tests | Oracle IT 8 tests, Rabbit IT 3 tests, relevant unit and slice suites green with `-Pdocker` | `evidence-raw/v1-fixes.log` |
| Downstream (§3.9) | `-amd test-compile`: 23 modules, BUILD SUCCESS, sha256 `88b5f4d3092a...` | `evidence-raw/downstream-compile-03dee13.log` |
| Golden path | **9/9** on `03dee13` (no new scenario, A2) | [`evidence/golden-path-summary-03dee13.tsv`](evidence/golden-path-summary-03dee13.tsv) |
| G composition | N/A (checkpoints at Phases 5 and 7) | — |
| H samples + operations | written | [`../../samples/outbox/`](../../samples/outbox/) |
| CI (PR #2) | green on `03dee13`; re-confirmed on the closure commit before tagging | GitHub Actions |

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | by type: `OutboxPublisher`, `OutboxContext`, `OutboxDrainer`, `OutboxConnections`; by name: `outboxCapabilityDescriptor` |
| 7.2 default snapshot | properties default snapshot and config metadata |
| 7.3 kill switch | `dc.platform.messaging.outbox.enabled`: `DEFAULT_ON`, startup WARN, publish after commit, no drainer; tested at consumer level |
| 7.4 outage posture | RabbitMQ down and slow: no request impact, recovery delivers all. Database down: fast 503, drainer survives, recovery without restart. Database slow: bounded, own pool. Rejected: parked, others flow |
| 7.5 health | neither the broker nor the backlog affects readiness or liveness |
| 7.6 propagation | `traceparent` and subject stored and sent; failure logs inside the restored scope (F17) |
| 7.7 error surface | a failed outbox write rolls back with the business write and follows the Phase 3 mapping |
| 7.8 redaction | credential-named headers refused at write; `last_error` holds a class name only; payload not masked, documented; no token or password in evidence |
| 7.9 observability | backlog gauges from a periodic query, counters, staleness and drainer-running meters; no event ids or destinations in labels |
| 7.10 graceful shutdown | batch ends after the send in flight and commits, exit 143, nothing lost or repeated |
| 7.11 startup | missing table and no transport: clear failure; unreachable database at start is an outage, not a missing table |

## Consumer-visible changes

See CHANGELOG `## [Unreleased]`, phase 4: the `EventEnvelope` constructor gains `eventId`; Rabbit publisher confirms on by default; a publish to an unroutable destination now fails; the outbox starter, API and properties.

## Owner decisions

1. **HI-0078: accepted.** Production does not rotate database credentials without a restart for now.
2. **HI-0071, HI-0079, HI-0086: parked, to be fixed later** (owner, 2026-11-01). The owner first chose to fix them. The attempt
   (`4ef7bfc`) failed verification (Codex `codex/v2-verify.out.md`, REJECT, 7 findings; not yet individually checked, one
   confirmed in part by a test: `afterCompletion` runs after Spring clears synchronizations). It was reverted, so the final
   code stays `03dee13`, the code all the evidence covers. Any later fix must answer those findings. **These limits are
   open, not accepted:** they should be resolved before production use.
3. Amendments A1-A3: for information.

## Open (non-blocking)

- HI-0080, HI-0082: Phase 5. HI-0081: Phase 8.
- HI-0057: Oracle RAC failover `BLOCKED` (HD4). HD13: certified on 23ai Free in 19 mode, not on 19c itself.
- The Kafka starter was not checked for the `spring-boot-amqp`-style gap of HI-0083 (not certified).
- HI-0004 (book narrative).
