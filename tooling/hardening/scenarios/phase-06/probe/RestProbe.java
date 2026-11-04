package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Consumer-side probe for the hardening phase-06 harness (never part of a real service). Each endpoint makes one call through a
 * named platform client and lets any failure reach the platform's error handling, except {@code /kind}, which reports it. Public
 * API and documented configuration only.
 */
@RestController
@RequestMapping("/probe/{client}")
public class RestProbe {

    private static final Logger log = LoggerFactory.getLogger(RestProbe.class);

    private final PlatformRestClientFactory factory;
    private final String baseUrl;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public RestProbe(PlatformRestClientFactory factory, @Value("${probe.base-url}") String baseUrl) {
        this.factory = factory;
        this.baseUrl = baseUrl;
    }

    private RestClient client(String name) {
        return clients.computeIfAbsent(name, key -> factory.builder(key).baseUrl(switch (key) {
            case "refused" -> "http://toxiproxy:1";
            case "unknownhost" -> "http://no-such-host.invalid";
            default -> baseUrl;
        }).build());
    }

    @GetMapping("/get")
    public String get(@PathVariable String client, @RequestParam(defaultValue = "0") String id) {
        log.info("probe id={}", id);
        return client(client).get().uri("/data?id={id}", id).retrieve().body(String.class);
    }

    @GetMapping("/json")
    public Map<?, ?> json(@PathVariable String client) {
        return client(client).get().uri("/data").retrieve().body(Map.class);
    }

    @GetMapping("/write")
    public ResponseEntity<String> write(@PathVariable String client, @RequestParam String method,
            @RequestParam(defaultValue = "0") String id, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        RestClient.RequestBodySpec spec = client(client).method(HttpMethod.valueOf(method)).uri("/data?id={id}", id);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        int status = spec.body("payload").retrieve().toBodilessEntity().getStatusCode().value();
        return ResponseEntity.ok("remote=" + status);
    }

    @GetMapping("/kind")
    public Map<String, Object> kind(@PathVariable String client, @RequestParam(defaultValue = "GET") String method) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            client(client).method(HttpMethod.valueOf(method)).uri("/data").retrieve().toBodilessEntity();
            result.put("kind", "none");
        } catch (RemoteCallException e) {
            result.put("kind", e.kind().name());
            result.put("status", e.status());
            result.put("retryable", e.retryable());
            result.put("snippet", e.bodySnippet());
            log.warn("remote failure, snippet: {}", e.bodySnippet());
        }
        return result;
    }
}
