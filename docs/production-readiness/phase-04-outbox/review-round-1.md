# Phase 4 — Review round 1 (Gate C)

| Item | Value |
|---|---|
| Reviewed commit | `de67f30` (main code from `39e354d`; isolated worktree) |
| Verbatim I/O | [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md) → [`codex/r1-review.out.md`](codex/r1-review.out.md); response [`codex/r1-response.md`](codex/r1-response.md) |
| Codex scope note | Static review: no JVM or Docker in Codex's sandbox. Fixes verified locally on real Oracle and RabbitMQ |
| Fix commit | `6f875c8` |

## Outcome

23 findings: 21 fixed, F7 downgraded to Medium and documented (credential rotation; the platform has no rotation mechanism), F17 partly done (failure logs inside the restored scope; no tracing scope opened, since the stored `traceparent` is what is sent). Per-finding dispositions are in the response file.

Most consequential:

| # | Defect | Why it mattered |
|---|---|---|
| F1 | A connection merely synchronized under another database's transaction passed the guard | The insert would auto-commit and survive the business rollback |
| F2 | Publish in an `afterCommit` callback | Row written to a transaction that never commits again: silent loss |
| F9 | A synthesized nack from a closing channel counted as a rejection | Repeated outages would park good events, and recovery would not deliver them |
| F10 | A blocked publish was not bounded by the confirm timeout | Alarms on the broker could hang the drainer, and direct publishers |
| F14 | The relay's condition ran before messaging (pre-existing) | Relay silently absent: no integration events at all |
| F16 | A tracing type in an always-present bean's signature | Startup failure on a classpath without tracing |

## Decisions

- **Scope amendment A3:** rows park on `max-rejections`, not the plan's "max attempts", because attempts also count outages.
- A stop ends the batch after the send in flight and commits, rather than sizing timeouts to finish a full batch (F13).
- The after-commit phase is detected by the nearest stack frame, because Spring has no flag; it fails closed.

## Acceptance criteria after round 1

| AC | Status |
|---|---|
| AC1 | Container: commit, rollback, deferred constraint at commit. Consumer pending |
| AC2 | Slice: no transaction, other database, primed foreign connection, proxy, after-commit, new transaction in a callback |
| AC3 | Slice: no extra `DataSource` or transaction manager |
| AC4 | Container: full different batches at sizes 1, 7 and 10, with LOB rows |
| AC5, AC6, AC7, AC8, AC10, AC14 | Consumer pending (Gate F) |
| AC9 | Container: rejection parks, replay keeps the id. Consumer pending |
| AC11 | Container: retention deletes only old sent rows, on a no-auto-commit source too |
| AC12, AC13, AC15, AC16 | Slice or container done; consumer pending |
| AC17 | Container: unroutable and missing exchange on real RabbitMQ; unit: nack classes, timeout, blocked publish |
| AC18 | Pending |

## Carried to Gate E

- F7: credentials rotated at runtime reach the drainer only after a restart.
- `platform-messaging-autoconfigure` fails without Micrometer or Jackson on the classpath, though it declares them optional (pre-existing; found by `RelayOrderingTest`): Phase 5.
- japicmp is inert: no released baseline exists to compare against.
