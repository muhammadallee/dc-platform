# Phase 5 — Review round 2 (Gate D)

| Item | Value |
|---|---|
| Reviewed commit | `e9d592b` (whole revised code; isolated worktree) |
| Verbatim I/O | [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md) → [`codex/r2-review.out.md`](codex/r2-review.out.md) |
| Verdict | REJECT. Round 1: 13 right, 5 partial. 8 new defects, 3 scope gaps |

## Round 1 findings marked partial

| # | Remaining | Disposition |
|---|---|---|
| 4 | Late registration could start a consumer before its bean was fully scanned | Fixed as D1 |
| 5 | The delivery limit still cannot tell a stop from a poison message | HI-0087, Medium residual |
| 10 | Startup order and restart | Fixed as D2, D3, D4 |
| 16 | In-memory requeue with a full queue | Fixed as D6 |
| 18 | A main queue deleted during a replay | HI-0088, documented operator limit |

## New

| # | Finding | Severity | Claude | Disposition |
|---|---|---|---|---|
| D1 | A late bean's consumer started before its second handler was registered, and the message was acked as ignored | High | Agree | FIXED: the bean is scanned fully first. A handler on a not-yet-created lazy bean stays a documented limit |
| D2 | Registration, start and stop did not own subscriptions atomically: a consumer could survive a stop | High | Agree | FIXED: one lock around all three; closes happen outside it |
| D3 | Delaying subscriptions let the outbox drain before in-memory consumers existed, and the event vanished | High | Agree: introduced by round 1's fix | FIXED: consumers start before the drainer's phase and stop after it |
| D4 | A handler outliving its consumer read a restart's stop signal | Medium | Agree | FIXED: the signal belongs to the subscription |
| D5 | A second instance of a prototype bean failed bean creation | Medium | Agree | FIXED: a WARN; the first instance keeps receiving. Previously each competed for the queue |
| D6 | An in-memory requeue on a full queue lost the message silently | Medium | Agree | FIXED: logged and counted out |
| D7 | The exhausted-handler log could quote the payload through the exception's message | Medium | Agree | FIXED: class and throw location at ERROR; the whole exception at DEBUG |
| D8 | A stop worked through buffered messages rather than returning them, as R3.3 says | Medium | Agree | FIXED: `forceStop` |

## Scope gaps

| Gap | Disposition |
|---|---|
| R2.2's total bound does not cover a blocked handler | Amendment A1: backoff is bounded; the handler's own timeouts bound it; documented |
| R6: the golden-path example published directly inside `@Transactional` | FIXED: it uses the outbox, with its DDL migration |
| R8: the documented sample does not exist yet | Gate H |
| AC4 uses a requeue as a stand-in for a killed consumer | Amendment A2 |
| AC6 (slow broker) is not in this scenario | Carried by the Phase 4 consumer, rerun on this code as C1 |
| AC13's replay after an upgrade | Not exercised end to end: the version rule is tested, and replay is tested. Noted in `consumer-run.md` |

## Verification passes

| Pass | Reviewed | Verdict | Outcome |
|---|---|---|---|
| 1 ([out](codex/v1-verify.out.md)) | `7f347db` | REJECT, 6 | Race scanning a late bean, a lock cycle with bean creation, and two distinct beans of one class silently merged. **Answered by removal:** routes freeze at start, and a handler created later fails loudly (`69721ae`). DEBUG payload logging removed. Consumer phase accepted as HI-0093 |
| 2 ([out](codex/v2-verify.out.md)) | `69721ae` | REJECT, 1 | Findings 1, 2, 4, 6 confirmed right. The one remaining defect: an in-memory retry on a full queue dropped silently. Fixed in one line, not re-reviewed |

Each round found real defects on paths the consumer run did not exercise. Codex's final verdict is REJECT on that one in-memory line, now fixed; it reported nothing further against the Rabbit transport or the registrar.
