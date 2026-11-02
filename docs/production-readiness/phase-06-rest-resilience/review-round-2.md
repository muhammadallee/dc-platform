# Phase 6 — Review round 2 (Gate D)

| Item | Value |
|---|---|
| Reviewed commit | `471db62` (whole revised code; isolated worktree) |
| Verbatim I/O | [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md) → [`codex/r2-review.out.md`](codex/r2-review.out.md) |
| Verdict | REJECT. Round 1: 15 right, 6 partial. 9 new defects, 9 gaps in what the scenario proves |

## Round 1 findings marked partial

| # | Remaining | Disposition |
|---|---|---|
| 1 | The all-method-retry check missed `conf/net.properties` | Fixed as D1 |
| 7 | The deadline excludes interceptors before the transport | D2 |
| 10 | CDATA and attribute XML secrets | Fixed as D3 |
| 13 | Text after a space in a quoted URL, and fragments | Fixed as D4 |
| 14 | A real reset near expiry reported as the deadline | Fixed as D5 |
| 17, 21 | Fixed as D7, D8 | |

## New

| # | Finding | Severity | Claude | Disposition |
|---|---|---|---|---|
| D1 | `jdk.httpclient.enableAllMethodRetry` in `conf/net.properties` passed the check | High | Agree | FIXED: both sources read |
| D2 | The deadline starts after interceptors, and a retrying interceptor resets it | High | Partly agree: Low. The caller's own interceptors are the caller's; the platform's token relay is immediate | DOCUMENTED. Residual HI-0094 with F7 |
| D3 | CDATA and `value="…"` XML secrets survive | High | Agree | FIXED |
| D4 | The cause kept text after a space, and fragments | Medium | Agree | FIXED: everything from `?` or `#` on goes |
| D5 | A 100 ms margin turned real resets into `DEADLINE` | Medium | Agree | FIXED: 2 ms, enough for the timer's truncation. Never makes a POST retryable |
| D6 | Resilience4j's default slow-call rule could open the breaker on a stream held open | Medium | Agree: introduced by round 1's deferred verdict | FIXED: the slow-call rule is off, R5 counts failures only |
| D7 | Binding only the first registry dropped a second user composite | Medium | Agree | FIXED: once per registry |
| D8 | The guard missed the `backends` alias | Medium | Agree | FIXED |
| D9 | A builder racing the close cut other clients' calls short | Medium | Agree | FIXED: only a transport it just made |

"basic validation failed" losing a word: Claude accepts it. A redaction that errs safe costs a little diagnosis, and the snippet is not the record of the failure. Documented.

## Scenario gaps

| Gap | Disposition |
|---|---|
| AC5 did not prove 8 KiB | Slice counts the bytes read: exactly 8192 |
| AC6 date form parser-tested only | Slice waits on a real call |
| AC8 never saw half-open | Slice holds a trial open and sees the refusal; the consumer reads the metric during a trial |
| AC12, AC13 weaker than stated | Slices added for order and permits; AC12's token and trace stay consumer level. **No test runs the `@Retry` aspect**: scope amended and stated |
| AC16 probe did not read a body | Replaced by String and JSON cases at consumer level; slice for a real JSON failure |
| AC15 did not separate its cases | A SIGTERM during a backoff, and another with a send in flight, each synchronised on what the downstream received |
| §7.7 assertion accepted a disclosure | Exact generic detail, 429, 502, 504 added, and a POST against an open breaker |
| AC17 could trip the default bulkhead | That client's bulkhead raised |
| §7.11 cannot prove no connection at startup | Accepted: true by construction; the times are recorded, stated in the scope |
| R9, R10 wording | Scope amended: a pair of clients per name; the deadline has no switch |
