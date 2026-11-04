# REST client and resilience

Call another service without your service going down with it, and without charging a customer twice.

## What it solves

A plain HTTP call can hang forever, repeat a payment, exhaust your threads when a neighbour slows down, and turn every failure into a confusing 500. The platform client gives you a deadline, safe retries, a circuit breaker and a concurrency limit for each downstream, and one failure type.

## When to use it

For any call from a service to another HTTP service. **Not** for large uploads: request bodies are held in memory. Not for reactive code.

## Minimal setup

Generate a service with the feature, or add `platform-starter-restclient`:

```bash
mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate \
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform -DarchetypeArtifactId=platform-service-archetype \
  -DgroupId=com.example -DartifactId=orders -Dfeatures=restclient -DinteractiveMode=false
```

```java
@Component
class StockClient {
    private final RestClient client;

    StockClient(PlatformRestClientFactory factory, @Value("${app.stock.base-url}") String baseUrl) {
        this.client = factory.builder("stock").baseUrl(baseUrl).build();
    }

    Stock get(String sku) {
        return client.get().uri("/stock/{sku}", sku).retrieve().body(Stock.class);
    }

    void reserve(Reservation reservation) {
        client.post().uri("/reservations").body(reservation).retrieve().toBodilessEntity();
    }
}
```

That is all. `get` is retried on a transient failure. `reserve` is a POST: it is sent once, whatever happens.

## Walkthrough: what happens when the downstream fails

| The stock service… | `get` | `reserve` (POST) | Your caller sees |
|---|---|---|---|
| Answers 503 | Tried 3 times, with jittered waits | Sent once | GET: 503, `retryable: true`. POST: 500, `retryable: false` |
| Answers 404 | Sent once | Sent once | 500, `retryable: false`. Catch it if a 404 means something to you |
| Hangs | Each attempt times out at 10 s; the call stops at 30 s | Times out once | GET: 503 or 504, `retryable: true`. POST: 500, `retryable: false`: it may have worked |
| Is down | Refused, 3 tries | Refused, once | 503, `retryable: true`: a connection never made is safe to repeat |
| Keeps failing | The breaker opens: fails at once, sends nothing, for 30 s | The same | 503, `retryable: true` |

To do something better than fail, catch it:

```java
try {
    return client.get().uri("/stock/{sku}", sku).retrieve().body(Stock.class);
} catch (RemoteCallException e) {
    if (e.status() == 404) {
        return Stock.none();
    }
    if (e.kind() == RemoteCallException.Kind.CIRCUIT_OPEN) {
        return lastKnownStock(sku);
    }
    throw e;
}
```

## Repeating a POST safely

The platform never does. A caller who gets `retryable: false` for a POST does not know whether it worked. If you need to repeat one, send an `Idempotency-Key` and have the **receiving** service deduplicate on it: the client passes the header through unchanged, and still sends only once per call.

## Tuning

Set a key only to differ from the default (`docs/modules/restclient.md` lists them all):

```yaml
dc.platform.restclient.clients.stock:
  read-timeout: 2s
  deadline: 5s
  retry.max-attempts: 2
  bulkhead.max-concurrent: 10
```

If a PUT or DELETE of yours is not really idempotent, take it out: `retry.methods: GET,HEAD`.

## Next

- [`commands.md`](commands.md): reproduce the checks. [`verification.md`](verification.md): what was measured, and its limits.
- [`operations.md`](operations.md): metrics, alerts, health. [`troubleshooting.md`](troubleshooting.md). [`open-issues.md`](open-issues.md).
