# REST Client

Platform-conventional outbound REST calls: inject `PlatformRestClientFactory` instead of
`RestClient.Builder` to get correlation propagation, sane timeouts, and error mapping on every
outbound call for free.

## What you get

- **`PlatformRestClientFactory`** — `builder(String clientName)` returns a pre-configured
  `RestClient.Builder` on the JDK `HttpClient` request factory.
- **Correlation propagation** — the current `RequestContext` correlation id (if any) is sent as
  an `X-Correlation-Id` header on every outbound call (matches the core capability's default
  header name).
- **Per-client timeouts** — a `connect-timeout`/`read-timeout` default, overridable per client
  name.
- **Error mapping** — any non-2xx response throws `RemoteCallException` (status, 1KB-truncated
  body, remote correlation id echoed by the callee).
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
- **Failure shapes** — non-2xx: `RemoteCallException`; read timeout, refused or reset connection:
  Spring's `ResourceAccessException`. No retries are added (compose resilience explicitly).

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
            throw new UpstreamOrdersFailure(e.status(), e.bodySnippet());
        }
    }
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.restclient.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.restclient.propagate-correlation` | `true` | Send the current correlation id as an outbound header. |
| `dc.platform.restclient.defaults.connect-timeout` | `2s` | Default connect timeout. |
| `dc.platform.restclient.defaults.read-timeout` | `10s` | Default read timeout. |
| `dc.platform.restclient.clients.<name>.connect-timeout` | (defaults) | Per-client connect timeout override. |
| `dc.platform.restclient.clients.<name>.read-timeout` | (defaults) | Per-client read timeout override. |

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

## Testing

Point a client at a `MockWebServer`/`WireMock`/JDK `HttpServer` instance via its base URL; no Docker
or network beyond your own test double is required. The service archetype's `restclient` feature
generates `client/GreetingClientTest`, which drives the real factory-built client against a loopback
stub (JSON both ways, empty bodies, 404/503 mapping, a bounded read timeout, a refused connection, and
correlation + token relay from a real authenticated inbound request).

## Local dev notes

No Docker, no network beyond the remote services you actually call.
