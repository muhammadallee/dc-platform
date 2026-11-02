# Phase 6 — Review round 1 (Gate C)

| Item | Value |
|---|---|
| Reviewed commit | `05fc5c0` (isolated worktree) |
| Verbatim I/O | [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md) → [`codex/r1-review.out.md`](codex/r1-review.out.md) |
| Verdict | REJECT, 21 findings: 19 fixed, 2 partly fixed with a stated limit |

| # | Finding | Severity | Claude | Disposition |
|---|---|---|---|---|
| 1 | POST re-sent by a 307 or 308, or by `-Djdk.httpclient.enableAllMethodRetry` | High | Agree | FIXED: a method not known to be idempotent uses a client that follows no redirects; the property stops startup. HTTP/2's re-send of a stream the server says it never processed stays, documented |
| 2 | A `ConnectException` on a redirect target after a POST was called safe to repeat | High | Agree | FIXED by 1: such a method never follows a redirect |
| 3 | "Not POST or PATCH" treated a custom method, or lowercase `post`, as idempotent | High | Agree | FIXED: an allowlist |
| 4 | Closing a streamed body leaked its bulkhead permit for good | High | Agree | FIXED: closing the stream closes the response |
| 5 | `Retry-After: 9223372037` overflowed, escaping with a leaked permit | High | Agree | FIXED: compares `Duration`s |
| 6 | Waiting in a bulkhead passed the deadline | High | Agree | FIXED by removal: `max-wait` is gone, a full bulkhead refuses at once |
| 7 | A streaming upload is buffered, unbounded, before the deadline starts | High | Partly agree: Medium. Any interceptor, including the token relay, already buffers, and a retry needs the bytes | PARTLY: the deadline starts when the request is created; buffering documented. A blocked callback of the caller's stays the caller's. Residual HI-0094 |
| 8 | A failure inside a JSON body escapes as `RestClientException`, so an outer `@Retry` could repeat a POST | High | Agree | FIXED: the default retry ignores `RestClientException`; documented that this path is 500 `retryable=false`. R1.1 amended |
| 9 | Bodies that fail were breaker successes | High | Agree | FIXED: the verdict on a non-failing status waits for the body. A half-open permission now waits too |
| 10 | Credentials survived redaction: `Basic`, escaped quotes, XML, later cookies | High | Agree, except percent-encoded names | FIXED for the rest, and the docs now say best effort. Encoded secrets stay, residual HI-0095 |
| 11 | Cubic backtracking on a hostile body | Medium | Agree | FIXED: repeats bounded or possessive; a test with six hostile bodies; a `StackOverflowError` fails closed |
| 12 | Query strings and URL credentials in the `uri` tag and `http.url` | High | Agree | FIXED: stripped from both |
| 13 | A bad redirect target's query in the logged cause | Medium | Agree | FIXED: the cause is rebuilt without query or credentials; deeper causes dropped |
| 14 | A timed-out body was reported as `CONNECTION` | Medium | Agree | FIXED: the attempt's clock decides, giving `TIMEOUT` or `DEADLINE` |
| 15 | A `Retry-After` past the deadline still read the body first | Medium | Agree | FIXED: fails at once, with an empty snippet |
| 16 | The REST starter alone had no resilience metrics | Medium | Agree | FIXED: the starter brings `resilience4j-micrometer` |
| 17 | Binding metrics twice replaced the listener or double-counted | Medium | Agree | FIXED: binds once |
| 18 | Stop waited 5 s per client, not in total | Medium | Agree | FIXED: one wait shared |
| 19 | A closed factory could still make clients | Medium | Agree | FIXED: refuses |
| 20 | `connect-timeout=0s` and `read-timeout=-1s` passed binding | Medium | Agree | FIXED |
| 21 | The thread-pool guard refused a metrics-only setting | Medium | Agree | FIXED: only `instances` and `configs` count |

## Found by the tests for these fixes

| Finding | Fix |
|---|---|
| Spring's `JdkClientHttpResponse.close` reads the rest of the body first, so abandoning a stalled or huge one still waited for its timeout. **The 8 KiB read limit limited memory, but not the work** | Close the stream first. A body already read to its end keeps its connection |
| An attempt's expiry was counted from the response's headers, not the send, so a body timeout looked like a lost connection | Counted from the send |

Codex's areas with nothing found are listed at the end of its output.
