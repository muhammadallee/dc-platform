# Verification

On `78636c0`: three consumer runs at 182 of 182, against a real downstream process through real Toxiproxy. Request counts, headers and bytes sent are what the downstream recorded.

| Expected | Actual | Level |
|---|---|---|
| A normal call returns the body, on the inbound trace, with the token relayed | Yes | CONSUMER |
| 429, 502, 503, 504: 3 requests, then 503 `retryable: true` | Yes | CONSUMER |
| 404, 500: 1 request, then 500 `retryable: false`; the upstream body stays out | Yes | CONSUMER |
| The same trace and token on every attempt | Yes | CONSUMER |
| `Retry-After`: waited for, in seconds and as a date; past the deadline, fails at once | Yes | SLICE; seconds also CONSUMER |
| Slow, or no response, through Toxiproxy: bounded, 503 | Yes, at most 10 s with an 8 s deadline | CONSUMER |
| Deadline: stops, 504 | Yes | CONSUMER |
| Refused connection, unknown host: 503 | Yes, including a POST, since nothing was sent | CONSUMER |
| **POST, PATCH: exactly 1 request** on 503, a connection lost after sending, a timeout, a 307, with and without `Idempotency-Key`, whose value arrives unchanged; 500 `retryable: false` | Yes | CONSUMER |
| PUT retried | Yes, 3 requests | CONSUMER |
| 64 MiB error body: returns in seconds; snippet at most 1 KB; the rest not read | Yes. Exactly 8192 bytes read | CONSUMER, SLICE |
| A password in an error body: gone from the snippet and the logs | Yes | CONSUMER |
| Breaker: opens, refuses without sending, half-open during its trial, closes; metrics and health say so | Yes | CONSUMER |
| Readiness and liveness stay up with the breaker open | Yes | CONSUMER |
| Bulkhead at 2 with 6 concurrent: 2 answered, 4 refused at once with a generic retryable ProblemDetail | Yes | CONSUMER |
| 30 concurrent calls keep their own traces, in the downstream and the logs | Yes | CONSUMER |
| SIGTERM in a backoff, and with a send in flight: answered, exit 143 | Yes, in 3 to 5 s | CONSUMER |
| Ready with the downstream up and down | 9 to 14 s up, 9 s down | CONSUMER |
| Fresh service built on this (`GreetingClientTest` included) | Golden path 9 of 9 | CONSUMER |

Regressions on the same code: Phase 1 consumer 92 of 92, Phase 2 consumer 75 of 75, 93 modules compile.

## Limits

- **`@Retry` through its aspect is not run.** `RetryableOperation` is, with a real POST through a real client inside the real retry, and reads the same registry.
- **HTTP only, no TLS.** A TLS failure's mapping is by reading.
- **HTTP/1.1 in the runs.** The JDK may re-send a POST under HTTP/2 when the server says it never processed the stream.
- **The SIGTERM cases reach their state by waiting**, not by observing it inside the client. Transport termination is not asserted, only the clean exit.
- The redaction of an unusual secret is not claimed: see [`open-issues.md`](open-issues.md).
- One downstream process on the host, reached through Docker's host gateway. No load test.
