# Operations

## Posture

| Downstream state | What the request gets | Verified |
|---|---|---|
| Healthy | The answer | CONSUMER |
| Answers 429, 502, 503, 504 | GET: retried, then 503 `retryable: true`. POST: 500 `retryable: false` | CONSUMER |
| Slow or silent | GET: bounded by the deadline, 503 or 504. POST: 500, outcome unknown | CONSUMER |
| Down, refused, unknown host | 503 `retryable: true` | CONSUMER |
| Failing repeatedly | The breaker opens: 503 at once, nothing sent | CONSUMER |
| Too many calls at once | The excess refused at once: 503 | CONSUMER |
| Down at startup | The service starts: a client connects on its first call | CONSUMER |

This capability fails fast, not durably: nothing is queued. For work that must survive an outage, use the outbox.

## Health

| Group | Includes this? | Why |
|---|---|---|
| liveness | No | A remote down is not this pod broken |
| readiness | No | It would take every replica out at once (HD6) |
| dependencies | Yes: `restclient` | For dashboards. Always `UP`, with each breaker's state and bulkhead's room in its details |

## Metrics, and alerts to set

| Metric | Alert when |
|---|---|
| `resilience4j_circuitbreaker_state{name="rest-<client>",state="open"}` | 1 for more than a couple of minutes |
| `platform_restclient_attempts_total{client,outcome}` | `circuit_open` or `bulkhead_full` rising; `timeout` or `connection` above normal |
| `resilience4j_bulkhead_available_concurrent_calls{name="rest-<client>"}` | At 0 for long: too small, or a caller not closing responses |
| `http_client_requests_seconds{client_name}` | Latency by client. One per call, not per attempt |

A log line per failure: `platform exception [DC-RCLIENT-0500]` at ERROR, on the request's trace, with the failure's type and stack and no transport message.

## Switches

Tier `DEFAULT_ON`, no break-glass. `dc.platform.restclient.enabled=false` removes the factory, and anything injecting it fails at startup. Per client or by default: `retry.enabled`, `circuit-breaker.enabled`, `bulkhead.enabled`. The deadline is always on.

## Shutdown

Calls in flight finish during the web server's drain. A call waiting in a backoff finishes too, within its deadline, so keep the deadline under the drain's 30 s if a stop must be quick: at the default 30 s it can use all of it. After that, clients get 5 s in total, then their remaining calls are cut.

## How to replace it

Define your own `PlatformRestClientFactory` bean, and the default backs off; metrics and health then report nothing. Replacing the request factory on one builder removes retry, deadline, breaker and bulkhead from that client.

## Cost

Each client name holds two JDK `HttpClient`s, so a selector thread and a connection pool each, created when first asked for. Take client names from a fixed set.
