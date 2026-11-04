# Phase 5 — RabbitMQ Messaging: closure

**Verdict: PASS, with three residuals accepted by the owner** (below). Earlier owner decisions: HC7 (no users, so breaking
changes need only a CHANGELOG entry), HI-0071, HI-0079 and HI-0086 parked.

| Item | Value |
|---|---|
| Phase start / final code commit | `dfb3524` / `5cafcf3` (consumer 3×, Phases 1–4 regressions, golden path and downstream compile on `5cafcf3`) |
| Modules changed | messaging-api, -spi, -rabbit, -inmemory, -autoconfigure, -outbox, -outbox-autoconfigure, example-golden-path, `tooling/scripts/rabbit-dlq-replay.py`, hardening harness, docs |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | a handler set to 3 attempts ran 9 times, reproduced on a real broker; amendments A1, A2 | [`scope.md`](scope.md) |
| B implement + targeted tests | D120–D124 | `evidence-raw/` |
| C round 1 | REJECT, 18 findings: 16 fixed, 2 with residuals | [`review-round-1.md`](review-round-1.md) |
| D round 2 and verification | REJECT, 8 defects and 3 gaps, fixed; pass 1 REJECT, answered by removing runtime handler registration; pass 2 REJECT on one in-memory line, fixed and not re-reviewed | [`review-round-2.md`](review-round-2.md) |
| E register | HI-0080, HI-0082 fixed; HI-0087 … HI-0093 | [`../issue-register.md`](../issue-register.md) |
| F consumer | three closing runs **63/63** each on `5cafcf3` | [`consumer-run.md`](consumer-run.md), [`evidence/gate-f-closing-1..3`](evidence/) |
| F regressions | Phase 1 **92/92**, Phase 2 **75/75**, Phase 3 **92/92**, Phase 4 **106/106** | [`evidence/`](evidence/) |
| Targeted tests | messaging-autoconfigure 58 + 1 real-broker, rabbit 23 + 12 real-broker, outbox 30 + 8 Oracle, outbox-autoconfigure 25 + 3, events 30; 0 failures, coverage gates met | `evidence-raw/v1-fixes.log`, `final-verify.log` |
| Downstream (§3.9) | `-amd test-compile`: 23 modules, BUILD SUCCESS, sha256 `eb80644e966bfae3…` | `evidence-raw/downstream-compile-5cafcf3.log` |
| Golden path | **9/9** on `5cafcf3` | [`evidence/golden-path-summary-5cafcf3.tsv`](evidence/golden-path-summary-5cafcf3.tsv) |
| G composition C1 | passes; one limit stated | [`composition-c1.md`](composition-c1.md) |
| H samples + operations | written | [`../../samples/rabbitmq-messaging/`](../../samples/rabbitmq-messaging/) |
| CI (PR #2) | green through `5cafcf3`; to be confirmed on the closure commit before tagging | GitHub Actions |

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | by type: `EventPublisher`, `EventSerializer`, `EventTransport`, `EventHandlerRegistrar` |
| 7.2 default snapshot | `PlatformMessagingDefaultsTest` |
| 7.3 kill switch | `dc.platform.messaging.enabled`, tested |
| 7.4 outage posture | broker down and slow, at startup and running; reconnect; lost definitions |
| 7.5 health | the broker affects neither readiness nor liveness |
| 7.6 propagation | `traceparent` on both publish paths; the handler continues the trace; `producer` on every message |
| 7.7 error surface | direct publish failure: `EventPublishException` |
| 7.8 redaction | credential-named headers refused; no payload or exception message in failure logs at any level |
| 7.9 observability | `platform.messaging.handled` with success, retry, dlq, ignored; `published` |
| 7.10 graceful shutdown | in-flight finishes, buffered returned, nothing dead-lettered, exit 143 |
| 7.11 startup | starts without the broker; a queue of the wrong type fails naming it |

## Consumer-visible changes

See CHANGELOG `## [Unreleased]`, phase 5.

## Owner acceptance (2026-11-02, D125)

1. **HI-0087: accepted.** A message returned by stopping consumers counts toward the delivery limit. Default raised to 20.
2. **HI-0088: accepted, to be reviewed again at the end of all phases, before the Phase 8 release decision.** A main queue deleted
   during a replay loses what the shovel moves afterwards. It is the only one of the three that loses data.
3. **HI-0093: accepted.** Consumers start before default-phase lifecycle beans, so a drained event finds its queue declared.

The failure scenarios behind HI-0087 (returns from a stopping consumer) and HI-0088 (a queue deleted mid-replay) were reasoned from
the code and not run, and HI-0088's warning is in the script's help and the docs only, not at runtime. The owner was told both.

Amendments A1, A2: for information.

## Open (non-blocking)

- HI-0071, HI-0079, HI-0086: parked by the owner, to fix before production use.
- Quorum failover across nodes unverified (one broker node); a real consumer-killing message; AC13's replay after an upgrade end to end.
- HI-0057: Oracle RAC `BLOCKED` (HD4). HD13: 23ai Free in 19 mode. HI-0081: Phase 8.
