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

## Verification pass 1 ([out](codex/v1-verify.out.md), `6741388`): REJECT, 10 and 9 test gaps

| # | Finding | Severity | Claude | Disposition |
|---|---|---|---|---|
| 1 | A metrics listener failing after a send lost a successful outcome and leaked its permit | High | Agree | FIXED: a listener can no longer fail a call. The most serious thing this pass found |
| 2 | XML secrets in truncated or repeated CDATA, and mixed quotes | High | Agree | FIXED by replacing the shape-by-shape patterns: a secret-named element keeps only its name |
| 3 | The guard cannot see the JDK's cached value if `net.properties` is edited while the JVM runs | High | Disagree: Low. Editing a JDK config file on a live JVM, then restarting only the context. Docs say to remove it, and a JVM restart follows any such edit | ACCEPTED, residual HI-0096 |
| 4 | An illegal header's value, and URL credentials with a space, in exceptions | High | Agree | FIXED: nothing built is reported without its message; a message quoting a URL goes whole |
| 5 | URL fragments in observations | High | Agree | FIXED |
| 6 | A builder publishing a transport could then cut another's call | Medium | Agree | FIXED: creation and close exclude each other |
| 7, 8 | Two composites sharing an exporter double-count; closed registries retained | Medium, Low | Partly agree: both need unusual registry setups, and Boot's own single composite is unaffected | ACCEPTED, residual HI-0097 |
| 9 | A reset within 2 ms of the deadline is called the deadline | Medium | Partly agree: Low. 504 instead of 503 in a 2 ms window, both `retryable`, and never for a POST | ACCEPTED, residual HI-0098 |
| 10 | An unrelated bad line in `net.properties`, or a whitespace value, failed construction | Medium | Agree | FIXED: the JDK's own semantics |

Test gaps: AC6's margin widened; AC8 holds its trial for 5 s; AC12 asserts the attempts reported; AC13 now sends a real POST through a real client inside the real
retry; AC16 asserts the `RemoteCallException` beneath; AC15 waits inside the backoff and uses a longer-timeout client for its in-flight send; §7.7 checks each refusal's body, and
a POST refused by a full bulkhead. Transport termination is not asserted, only the clean exit.
