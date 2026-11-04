# Phase 4 — Review round 2 (Gate D)

| Item | Value |
|---|---|
| Reviewed commit | `311d488` (whole revised code; isolated worktree) |
| Verbatim I/O | [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md) → [`codex/r2-review.out.md`](codex/r2-review.out.md) |
| Round 1 | F3, F4, F6, F9, F11, F13 (different fix accepted), F14, F15, F16, F18, F19, F22, F23 confirmed fixed; the partials are answered below; F7's downgrade and amendment A3 accepted |

## Findings

| # | Finding | Severity | Claude | Disposition |
|---|---|---|---|---|
| R2-1 | `REQUIRED` in an `afterCommit` callback passed the post-commit guard | High | Agree: an interceptor frame cannot prove a new transaction | FIXED, simpler: the publisher refuses anywhere under such a callback, so `REQUIRES_NEW` there is refused too. Loud, never silent. Disabled mode publishes immediately there |
| R2-2 | The dispatcher ran handlers before an inner transaction's outcome | High | Agree | FIXED: the nearest frame decides; a transaction opened in the callback defers, and a rollback skips. `TransactionalDispatcherPhaseTest` |
| R2-3 | A non-auto-commit pool, a second manager and a priming read pass the ownership check | High → **Medium** | Partly agree. Needs all three together; Spring exposes no supported ownership check (`isTransactionActive` is protected, and unset under JPA) | **RESIDUAL** (HI-0079): documented, "one transaction manager per outbox database" |
| R2-4 | A timeout bounds the caller, not the outstanding sends | High | Agree | FIXED: at most 64 senders in flight; past that a send fails at once as an outage |
| R2-5 | The publish moved threads, losing thread-bound routing | High → **Medium** | Partly agree | FIXED for routing (`AbstractRoutingConnectionFactory` refused at startup). **RESIDUAL**: a mandatory expression depending on thread context (HI-0082) |
| R2-6 | The pool could reconnect to a different database | High | Agree | FIXED: a Hikari pool with `dataSource` or `dataSourceClassName` is refused |
| R2-7 | A restart could revive an unfinished batch | High | Agree | FIXED: `start` refuses while the last worker lives; maintenance is joined |
| R2-8 | An Oracle `DriverManagerDataSource` escaped the bounds; `read-timeout=0` accepted | High | Agree | FIXED: built from its URL with the bounds; embedded databases only are wrapped; zero refused |
| R2-9 | `pool-size=1` starves maintenance | Medium | Agree | FIXED: at least 2, refused at startup |
| R2-10 | The parking WARN lost its trace | Medium | Agree | FIXED: restored around it |

## Scope changes

R4.1 reads "a stop ends the batch after the send in flight and commits what was sent". AC8 excepts a timed-out send, which may yet arrive and repeat.

## Tests

`-pl events/platform-events-autoconfigure,messaging/platform-messaging-outbox-autoconfigure,messaging/platform-messaging-rabbit -am verify`: BUILD SUCCESS, coverage gates met (`evidence-raw/r2-fixes.log`). Oracle and RabbitMQ container tests re-run at Gate F on the final code.
