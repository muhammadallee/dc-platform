# Phase 6 — Consumer run (Gate F)

| Item | Value |
|---|---|
| Code | `78636c0` |
| Scenario | `tooling/hardening/scenarios/phase-06-rest.sh`, probe `phase-06/probe/RestProbe.java`, downstream `phase-06/stub.py` |
| Consumer | Archetype-generated, `features=restclient`, prod profile, public API only. Security is a fixture |
| Infrastructure | Toxiproxy 2.9.0 in Docker; the downstream on the host, through Docker's host gateway |
| Closing runs | **182 of 182, three times** (`rest-20261102-155736`, `-160213`, `-160639`); [`evidence/gate-f-closing-1..3`](evidence/) |
| Startup | Ready in 9 to 14 s with the downstream up, 9 s with it down |
| Stop | Exit 143, in 3 to 5 s, with a call in a backoff and another in flight |
| Cleanup | No containers left, checked by the harness |

## Acceptance criteria

| AC | Result | How |
|---|---|---|
| AC1 | pass | Body, trace and relayed token, read at the downstream |
| AC2 | pass | Toxiproxy latency and `timeout` toxics |
| AC3 | pass | Refused, unknown host, POST to a refused connection |
| AC4 | pass | 404 and 500 once; 429, 502, 503, 504 three times; recovery on the second; PUT retried |
| AC5 | pass | 64 MiB body returned in seconds, and not all sent; slice: exactly 8192 bytes read; a password gone from snippet and logs |
| AC6 | pass | Seconds at consumer level, a date in a slice; past the deadline, at once in a slice |
| AC7 | pass | 504 within the bound |
| AC8 | pass | Open, refused with nothing sent, half-open during a held trial, closed, by metric and health |
| AC9 | pass | 2 of 6 answered, 4 refused quickly with a correct body, 2 sent |
| AC10 | pass | POST and PATCH: 503, with a key, lost after sending, timed out, 307: one request each, key unchanged |
| AC11 | pass | Slices: a startup failure naming the property |
| AC12 | pass | Attempts reported in order, an open breaker not retried (slice); trace and token on each attempt (consumer) |
| AC13 | pass | A real POST through a real client inside the real `RetryableOperation`: one request. **The `@Retry` aspect is not run** |
| AC14 | pass | Readiness and liveness up with the breaker open; `dependencies` shows it |
| AC15 | pass | SIGTERM in a backoff, then with a send in flight: both answered |
| AC16 | pass | String body at consumer level and slice; JSON at consumer level; a slice shows the wrapped form |
| AC17 | pass | 30 concurrent, each with its own trace at the downstream and in the log |

## Trials before the closing runs

| Trial | Result | Lesson |
|---|---|---|
| 1 | Stopped at 103 | Harness: JSON over-escaped in a script |
| 2 | 155 of 160 | One real bug: a timer truncated to milliseconds made a deadline look like a lost connection. Two harness timings |
| Invalid | Discarded | The repository rebuild failed on stale coverage (HI-0003), so the run used baseline code. Closing runs started only after a clean rebuild |
| 3 | 160 of 160 | Then Codex round 2 showed what this run did not prove |
| 4 | 180 of 182 | The JSON library does not always wrap: my check, not the platform |

**A clean run was not the finish.** Trial 3 passed 160 of 160 on code Codex then rejected, and its next passes found a lost outcome from a failing metrics listener.

## Limits

See [`verification`](../../samples/rest-resilience/verification.md): no TLS, no HTTP/2, no load, the `@Retry` aspect not run, and SIGTERM states reached by waiting.
