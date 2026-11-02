# Phase 6 — REST Client + Resilience: scope

| Item | Value |
|---|---|
| Phase start commit | `1162d99` (Phase 5 closed, residuals accepted, D125) |
| Capability under test | `platform-restclient-api`, `-autoconfigure`, `platform-starter-restclient`; `platform-resilience-*` |
| Owner decisions | HD2: no idempotency store, so POST and PATCH are never retried. HD5: `traceparent` only. HD6: dependencies never in readiness. HC7: breaking changes need only a CHANGELOG entry |
| Certified dependencies | core, errors, logging, observability |
| Fixtures (not certified here) | security (Phase 7), openapi, validation |
| Phase 0 gaps | G6-01 to G6-06 |

## 1. Baseline (`1162d99`)

Targeted build `restclient`, `resilience` with `-am`: 144 tests, 0 failures, 0 skipped (`evidence-raw/baseline.log`).

Reproduced with a temporary test against MockWebServer (`evidence-raw/baseline-repro.log`):

| Probe | Result | Meaning |
|---|---|---|
| 500 with a 32 MB body | 96 MB allocated on the calling thread, then cut to 1 KB | G6-01: the bound comes after the read |
| GET, connection dropped after the request | **2 requests** at the server, with no retry configured | The JDK client re-sends GET once by itself, so it multiplies any retry budget |
| POST, connection dropped after the request | 1 request | Safe, at the JDK's default settings |
| Headers delayed 5 s, read timeout 1 s | `ResourceAccessException` at 1003 ms | Timeout holds |
| Body trickling over 6 s, read timeout 1 s | `RestClientException` at 1005 ms | The read timeout caps the whole response, and a body failure has a different exception type |

By reading (each gets a test at Gate B):

| Defect | Where |
|---|---|
| `RemoteCallException` is always 500 with `retryable=false`; a timeout or refused connection is an unmapped 500 `DC-CORE-0500`. "Retries exhausted, ProblemDetail with `retryable`" does not exist | `PlatformExceptionHandler` |
| `RetryableOperation` and `@Retry` under the `default` config retry every exception, so one wrapped around a POST repeats it | `PlatformResilienceEnvironmentPostProcessor`, G6-03 |
| The docs say the time limiter caps a call at 5 s; it bounds only `CompletionStage` and `Future` returns, so it does nothing on a blocking `RestClient` call | `resilience.md`, `ResilienceDefaults` |
| `bodySnippet` is called safe to log, without redaction | `RemoteCallException`, G6-02 |
| No total bound: 3 attempts × 10 s plus backoff under `@Retry` | G6-04 |
| One `HttpClient` per `builder()` call, never closed | `DefaultPlatformRestClientFactory` |
| Javadoc still promises a correlation header | `PlatformRestClientFactory` |

## 2. Requirements

### R1 One failure type
1. Every failed call throws `RemoteCallException`, with a `Kind`: `RESPONSE`, `TIMEOUT`, `CONNECTION`, `DEADLINE`, `CIRCUIT_OPEN`, `BULKHEAD_FULL`. `status()` is 0 when there was no response. A failure while reading a success body also becomes one.
2. `retryable()` is true only if a repeat by the caller is safe and may help. A connection never made is safe for any method.

### R2 ProblemDetail (§7.7)
| Failure | Status | `retryable` |
|---|---|---|
| Upstream 429, 502, 503, 504, idempotent method | 503 | true |
| Upstream 4xx (other than 429), or 500 | 500 | false |
| Timeout or connection failure, idempotent method; or a connection never made, any method | 503 | true |
| `DEADLINE`, idempotent method | 504 | true |
| `CIRCUIT_OPEN`, `BULKHEAD_FULL`, any method: nothing was sent | 503 | true |
| POST or PATCH that may have reached the server: outcome unknown, including its timeouts and 502, 503, 504 | 500 | false |

The detail is generic: no host, URL, or upstream body.

### R3 Retry (G6-03, G6-05)
1. Runs below every interceptor and customizer, in a request factory, so each attempt carries the same headers. One observation per logical call; attempts are counted by `platform.restclient.attempts`.
2. Methods: GET, HEAD, OPTIONS, PUT, DELETE (RFC 9110 idempotent). **POST and PATCH never**, whatever the configuration or any `Idempotency-Key`; configuring them fails startup. The header passes through unchanged.
3. Retryable: connection failure, timeout, and 429, 502, 503, 504. Not other 4xx, and not 500: an idempotent 500 is usually a bug, and repeating it adds load.
4. Defaults: 3 attempts, 200 ms × 2, full jitter, 5 s cap per wait. Discarded responses are closed.
5. `Retry-After`, in seconds or as a date, replaces the backoff; if it passes the deadline the call stops and reports the failure at once.
6. The `default` Resilience4j retry config ignores `RemoteCallException` and `ResourceAccessException`, so `@Retry` or `RetryableOperation` around a platform client does not repeat a POST, or multiply its retries. A caller's own config can change this; the docs say what that costs.

### R4 Deadline (G6-04)
1. `deadline`, default 30 s, covers all attempts and waits. Each attempt's timeout is `min(read-timeout, remaining)`. Exhausted: `DEADLINE`, with no further attempt.
2. The deadline is per call. **Propagation of an inbound deadline downstream is deferred** (plan: "if implemented").

### R5 Circuit breaker
1. Per client name, Resilience4j, default on: 50% over 10 calls, minimum 10, 30 s open, 3 half-open calls.
2. Failures: connection, timeout, and 500 or above. A 4xx, including 429, is an answer, not a failure. A local bulkhead rejection does not count.
3. Open: `CIRCUIT_OPEN` at once, not retried. Fallback is the caller's: catch `RemoteCallException` and read `kind()`; documented.
4. Metrics from Resilience4j's Micrometer binding. Health: a `restclient` contributor, always `UP`, with each breaker's state in its details, in the `dependencies` group and never in readiness (HD6).

### R6 Bulkhead (G6-06)
1. Semaphore per client, default 25 concurrent and no wait. The permit is held until the response is closed. Full: `BULKHEAD_FULL`, not retried.
2. `bulkhead.type=THREAD_POOL` fails startup with a clear message: it is not supported. In the resilience capability, a `resilience4j.thread-pool-bulkhead` instance with `spring.threads.virtual.enabled=true` fails startup too.

### R7 Order
Outermost first: deadline, retry, circuit breaker, bulkhead, HTTP. Each attempt asks the breaker and bulkhead separately. Resilience4j's annotation order, for callers, is documented. Tested.

### R8 Error body (G6-01, G6-02)
At most 8 KiB is read, then closed; the snippet stays 1 KB, is not cut through a character, and is redacted: bearer tokens, JWTs, and values of secret-named keys. The javadoc stops saying "safe to log" without that.

### R9 Client lifetime
One `HttpClient` per client name, closed when the context closes. Connect timeouts, redirects, trace propagation and token relay keep their behaviour.

### R10 Configuration
`dc.platform.restclient.{defaults,clients.<name>}.{deadline, retry.*, circuit-breaker.*, bulkhead.*}`, each with `enabled`. Immutable records and metadata. Invalid values fail startup with the property's name.

### R11 Documentation
`restclient.md`, `resilience.md` (time limiter corrected), archetype sample and its test, `docs/samples/rest-resilience/`.

## 3. Exclusions

- Inbound deadline propagation (R4.2). Idempotency store (HD2). Client-side load balancing and rate limiting.
- The JDK client's own single re-send of GET and HEAD after a dropped connection stays: it cannot be switched off per client. It is documented and measured: up to 2 × `max-attempts` requests in the worst case. POST stays at 1, and a test fixes that.
- Replacing the request factory through a customizer removes retry, breaker and bulkhead from that client; documented.
- Reactive `WebClient`.

## 4. §7 contracts

| § | Contract | How verified |
|---|---|---|
| 7.1 | Override `PlatformRestClientFactory` | Backs off, tested |
| 7.2 | Default snapshot | New defaults test |
| 7.3 | `DEFAULT_ON`; capability and per-feature switches | Tested |
| 7.4 | Slow, no response, refused, DNS failure | Consumer; the request's response recorded |
| 7.5 | Health | Liveness and readiness unaffected with the downstream down |
| 7.6 | `traceparent` the same on every attempt | Tested, consumer |
| 7.7 | R2 | Consumer |
| 7.8 | R8; no URL query or body in logs or tags | Tested |
| 7.9 | Low-cardinality tags only | Tested |
| 7.10 | Shutdown during a backoff and with a call in flight; clients closed | Consumer |
| 7.11 | No connection at startup, so a down dependency cannot delay it | Consumer, both times recorded |

## 5. Acceptance criteria

| AC | Level | Criterion |
|---|---|---|
| AC1 | CONSUMER | A normal call returns the body, with a `traceparent` on the same trace |
| AC2 | CONSUMER | Slow and no response: bounded by the timeout; 503 `retryable=true` |
| AC3 | CONSUMER | Refused connection and unknown host: the same, within the budget |
| AC4 | SLICE, CONSUMER | 4xx: one request. 503: 3 requests, then 503 `retryable=true`. 500: one request |
| AC5 | SLICE, CONSUMER | A large error body: at most 8 KiB read; the snippet bounded and redacted |
| AC6 | SLICE | `Retry-After` honoured, in both forms; past the deadline, fails at once |
| AC7 | SLICE | Exhausted deadline: no further attempt; `DEADLINE`, 504 |
| AC8 | CONSUMER | Breaker closed, open, half-open, closed, with its metrics; open sends nothing |
| AC9 | SLICE, CONSUMER | Bulkhead saturated under concurrency: the excess rejected at once, 503; the permit released on close |
| AC10 | SLICE, CONSUMER | **POST and PATCH: exactly one request** at the stub, on 503, on a reset after sending, and with `Idempotency-Key`, whose value arrives unchanged. Unknown outcome: 500 `retryable=false` |
| AC11 | SLICE | Configuring POST as retryable, or a thread-pool bulkhead, fails startup naming the property |
| AC12 | SLICE | Order: an open breaker is not retried, each attempt takes and returns a permit, the token and trace are on every attempt |
| AC13 | SLICE | `@Retry` and `RetryableOperation` under `default` do not repeat a failed platform client call |
| AC14 | CONSUMER | Readiness and liveness stay up with the downstream down; the `dependencies` group shows the breaker |
| AC15 | CONSUMER | SIGTERM during a backoff and with a call in flight: stops in time, exit 143, in-flight answered |
| AC16 | SLICE | Failure while reading a success body: `RemoteCallException`, not a raw `RestClientException` |
| AC17 | CONSUMER | Concurrent calls keep separate traces and no MDC bleed |

P0: R1, R2, R3, R4, R8. P1: R5, R6, R7, R9. Deferred: R4.2.

## 6. Environment

Docker with Toxiproxy (image present). Stubs: MockWebServer in tests; WireMock 3.9.1 inside the consumer run's stub, or the probe's own stub. No root reactor build. Phase 1, 2 regressions rerun, since `errors` and `observability` change, and the archetype's `GreetingClientTest` changes.

## 7. Decisions to record

| ID | Decision |
|---|---|
| D126 | Resilience in the REST client runs in a request factory, below interceptors, not an interceptor or aspect: a retried interceptor chain can skip later interceptors, and a factory also sees only real sends |
| D127 | `restclient-autoconfigure` uses Resilience4j's breaker and bulkhead directly; retry is the platform's own short loop, because it must honour `Retry-After`, the deadline and method rules together |
| D128 | Retry on 429, 502, 503, 504, not 500; PUT and DELETE retried by default, as RFC 9110 idempotent |
| D129 | Remote failures map to 503, 504, or 500 through a status hint on `PlatformException`, so `errors` stays independent of `restclient` |
| D130 | An unknown outcome is never `retryable`, matching HI-0071's rule for commits |

## 8. Reproduce

`mvn -o -T1 -pl restclient/platform-restclient-autoconfigure,resilience/platform-resilience-autoconfigure -am verify`
