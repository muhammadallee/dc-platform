# REST Client

Platform-conventional outbound REST calls: inject `PlatformRestClientFactory` instead of
`RestClient.Builder` to get W3C trace propagation, timeouts and a deadline, safe retries, a circuit
breaker, a bulkhead and one failure type on every outbound call.

## What you get

- **`PlatformRestClientFactory`** — `builder(String clientName)` returns a pre-configured
  `RestClient.Builder` on the JDK `HttpClient` request factory.
- **Trace propagation** — every client is instrumented with the observation registry, so the
  current trace travels as the standard `traceparent` header (the platform's only correlation id).
  No custom correlation header is sent or read (the former `X-Correlation-Id` behaviour is gone).
- **Per-client settings** — everything below has a default, overridable per client name.
- **One failure type** — every failure throws `RemoteCallException`; `kind()` says which (see below).
  At most 8 KiB of an error body is read. The 1KB snippet has common credential forms removed: authorization and
  cookie lines, auth schemes, JWTs, and secret-named values in JSON and forms; a body with a secret-named XML element
  loses its whole snippet. This is best effort, by pattern:
  an encoded or oddly named secret, or personal or business data, can remain. It also errs the safe way, so
  "basic validation failed" loses a word. Treat `bodySnippet()` as untrusted: never return it to your own callers.
  `remoteCorrelationId()` is deprecated and always empty for platform-created exceptions:
  caller and callee share one trace, so the caller's own `traceId` joins both sides' logs.
- **Retries, deadline, breaker, bulkhead** — see [Resilience](#resilience).
- **`PlatformRestClientCustomizer` extension point** — ordered beans that further customize a
  named client's builder.
- **Observations** — every call records an `http.client.requests` observation (metrics/tracing via
  the application's `ObservationRegistry`), tagged `client.name=<clientName>`.
- **Guarded token relay** — when the security capability is present and a request is
  authenticated, the incoming bearer token is relayed to outbound calls automatically; a
  restclient-only consumer never pulls in security. The auto-configuration is ordered after the
  security capability's, so the relay registers in real applications (not only in tests that supply
  a `CurrentUserAccessor` bean themselves). The relay applies to **every** client the factory builds —
  use the factory for services that should see the caller's token, and a `PlatformRestClientCustomizer`
  or a separate client for third parties. A redirect to another origin does not carry the header
  (JDK `HttpClient` behavior, covered by a test).

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-restclient</artifactId>
</dependency>
```

## Zero-config behavior

```java
@Service
class OrdersClient {
    private final RestClient client;

    OrdersClient(PlatformRestClientFactory factory) {
        this.client = factory.builder("orders").baseUrl("https://orders.internal").build();
    }

    Order get(String id) {
        try {
            return client.get().uri("/orders/{id}", id).retrieve().body(Order.class);
        } catch (RemoteCallException e) {
            throw new UpstreamOrdersFailure(e.status());
        }
    }
}
```

## Resilience

From the outside in: **deadline, retry, circuit breaker, bulkhead, HTTP**. Each attempt asks the breaker
and the bulkhead separately. All of it runs below the client's interceptors, so every attempt carries
the same token and `traceparent`, and one call is one `http.client.requests` observation.

- **Retry.** GET, HEAD, OPTIONS, PUT and DELETE: on a connection failure, a timeout, or 429, 502, 503,
  504. Not on other 4xx, and not on 500, which is usually a bug that repeating only loads further.
  Default 3 attempts, waits jittered up to 200 ms, 400 ms, capped at 5 s. `Retry-After` replaces the wait;
  if it passes the deadline the call stops at once.
- **POST and PATCH are never retried**, with or without an `Idempotency-Key`, which passes through
  unchanged. Listing them in `retry.methods` fails startup.
- **PUT and DELETE are retried** because RFC 9110 calls them idempotent. If yours is not, remove them from
  `retry.methods`.
- **The JDK client re-sends a GET or HEAD once by itself** after a dropped connection, and this cannot be
  switched off per client. So the worst case is 2 × `max-attempts` requests. POST is not re-sent, and
  `jdk.httpclient.enableAllMethodRetry`, which would change that, stops the application from starting, as a system
  property or in the JDK's `conf/net.properties`. The check reads the file when the context starts; restart the JVM after changing it.
- **Only known idempotent methods follow redirects.** A POST, PATCH or custom method gets the 3xx back, since a 307 or
  308 would send it again. Under HTTP/2, the JDK can re-send a stream the server said it never processed.
- **Request bodies are held in memory** for the length of the call, so a streaming upload is buffered. Use something
  else for large uploads.
- **Deadline.** Always on. Covers all attempts and waits, from the moment a call reaches the client's transport: not
  the time your own interceptors or a slow upload callback take first. An attempt's timeout is the smaller of `read-timeout` and
  what remains. The read timeout covers the whole response, body included, so raise it for a large download.
- **Circuit breaker.** Per client name: opens at 50% failures over 10 calls, stays open 30 s, then allows 3
  trial calls. A failure is a connection failure, a timeout, or 500 and above. A 4xx is an answer. Open means
  nothing is sent and nothing is retried.
- **Bulkhead.** A semaphore per client name, default 25 calls and no waiting. The permit is held until the
  response closes. A thread-pool bulkhead is refused: it would pin blocking calls to platform threads.

A fallback is the caller's choice:

```java
try {
    return client.get().uri("/orders/{id}", id).retrieve().body(Order.class);
} catch (RemoteCallException e) {
    if (e.kind() == RemoteCallException.Kind.CIRCUIT_OPEN) {
        return cachedOrder(id);
    }
    throw e;
}
```

### What the caller of your service sees

An uncaught `RemoteCallException` becomes a ProblemDetail with code `DC-RCLIENT-0500` and a generic detail:

| Failure | Status | `retryable` |
|---|---|---|
| 429, 502, 503, 504, idempotent method | 503 | true |
| Any other 4xx, or 500 | 500 | false |
| Timeout or connection failure, idempotent method | 503 | true |
| Connection never made, any method | 503 | true |
| Deadline ran out, idempotent method | 504 | true |
| Breaker open, bulkhead full, any method: nothing was sent | 503 | true |
| POST or PATCH that may have arrived: the outcome is unknown | 500 | false |

### Metrics and health

- `platform.restclient.attempts{client,outcome}`: one per attempt. Outcomes: `success`, `status_4xx`,
  `status_5xx`, `timeout`, `connection`, `deadline`, `circuit_open`, `bulkhead_full`.
- `resilience4j.circuitbreaker.*` and `resilience4j.bulkhead.*`, tagged `name=rest-<client>`.
- The `restclient` health contributor is always `UP`, with each breaker's state in its details. It is in the
  `dependencies` group and never in readiness: a remote being down must not take every replica out.

Client names name breakers and tags, so take them from a fixed set. Query strings and URL credentials are left out of
the `uri` tag and the span's `http.url`.

Malformed JSON in a **success** body: the JSON library's own message can quote the bad token, and Spring keeps it in the
exception chain, which your error log prints. A downstream that sends credentials in a broken JSON body could put them there.

A failure inside a JSON body can arrive as Spring's `RestClientException`, wrapping a `RemoteCallException`, when the JSON
library wraps what it reads through. It then reaches your caller as a 500, `retryable=false`. Otherwise it is the
`RemoteCallException` itself, and the table above applies.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.restclient.enabled` | `true` | Kill switch for the whole capability. |
| ~~`dc.platform.restclient.propagate-correlation`~~ | — | Removed (deprecated, level `error`): `traceparent` is always propagated. |
| `dc.platform.restclient.defaults.connect-timeout` | `2s` | Default connect timeout. |
| `dc.platform.restclient.defaults.read-timeout` | `10s` | Bound on one attempt's whole response, body included. |
| `dc.platform.restclient.defaults.deadline` | `30s` | Bound on one call, all attempts and waits included. |
| `….retry.enabled` | `true` | Retry idempotent methods. |
| `….retry.max-attempts` | `3` | Attempts, the first included. |
| `….retry.initial-backoff` | `200ms` | Upper bound of the first wait. |
| `….retry.multiplier` | `2.0` | Growth of the bound. |
| `….retry.max-backoff` | `5s` | Largest bound. `Retry-After` is limited by the deadline instead. |
| `….retry.methods` | `GET,HEAD,OPTIONS,PUT,DELETE` | A subset of these. |
| `….circuit-breaker.enabled` | `true` | Use the breaker. |
| `….circuit-breaker.failure-rate-threshold` | `50` | Percentage that opens it. |
| `….circuit-breaker.sliding-window-size` | `10` | Calls measured. |
| `….circuit-breaker.minimum-calls` | `10` | Calls needed before it can open. |
| `….circuit-breaker.open-duration` | `30s` | How long it stays open. |
| `….circuit-breaker.half-open-calls` | `3` | Trial calls after that. |
| `….bulkhead.enabled` | `true` | Use the bulkhead. |
| `….bulkhead.type` | `SEMAPHORE` | `THREAD_POOL` fails startup. |
| `….bulkhead.max-concurrent` | `25` | Calls at once. A full bulkhead refuses at once. |
| `dc.platform.restclient.clients.<name>.*` | (defaults) | Any of the above except `enabled` at the top, for one client. Unset values inherit. |

`…` is `dc.platform.restclient.defaults` or `dc.platform.restclient.clients.<name>`.

(Hand-written until the generated reference lands in phase 14.)

## Customize

```java
@Bean
@Order(10)
PlatformRestClientCustomizer apiKeyCustomizer() {
    return (name, builder) -> {
        if ("orders".equals(name)) {
            builder.defaultHeader("X-Api-Key", apiKey);
        }
    };
}
```

## Replace / Disable

- Define your own `PlatformRestClientFactory` bean to replace the default entirely.
- `dc.platform.restclient.enabled=false` switches the capability off wholesale.
- Calling `requestFactory(...)` on a builder, or in a customizer, removes retry, deadline, breaker and bulkhead
  from that client, along with the mapping of connection failures.

## Testing

Point a client at a `MockWebServer`/`WireMock`/JDK `HttpServer` instance via its base URL; no Docker
or network beyond your own test double is required. The service archetype's `restclient` feature
generates `client/GreetingClientTest`, which drives the real factory-built client against a loopback
stub (JSON both ways, empty bodies, 404/503 mapping, a bounded read timeout, a refused connection, and
correlation + token relay from a real authenticated inbound request).

## Local dev notes

No Docker, no network beyond the remote services you actually call.
