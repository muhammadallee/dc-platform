package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import ${package}.hello.HelloService;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Deployment-mode structured logging, asserted on the events this service actually emits: every event
 * is one JSON object on stdout carrying the agreed fields, the caller's W3C trace ID (sent in
 * {@code traceparent}) reaches the application log as {@code traceId}, pooled request threads never
 * leak a previous request's trace, no custom correlation header is echoed, and bearer tokens never
 * reach the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest extends HttpIntegrationTestSupport {

    private static final String GREETING_EVENT = "Composing greeting";

    @Test
    void requestEventIsJsonWithServiceAndTheCallersTrace(CapturedOutput output) throws Exception {
        String traceId = randomTraceId();
        String token = ISSUER.token("alice");

        HttpResponse<String> response = get("/hello?name=alice", token, traceId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty();
        Map<String, Object> event = singleEvent(output, GREETING_EVENT, traceId);
        assertThat(event)
                .containsEntry("level", "INFO")
                .containsEntry("logger", HelloService.class.getName())
                .containsEntry("service", "${artifactId}")
                .containsEntry("message", GREETING_EVENT)
                .doesNotContainKey("correlationId");
        assertThat((String) event.get("spanId")).matches("[0-9a-f]{16}");
        assertThat(OffsetDateTime.parse((String) event.get("@timestamp"))).isNotNull();
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void invalidTraceparentStartsANewTraceAndIsNeverLoggedRaw(CapturedOutput output) throws Exception {
        String invalid = "00-not-a-trace-INJECTED-01";
        HttpResponse<String> response = http().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/hello"))
                        .header("Authorization", "Bearer " + ISSUER.token("alice"))
                        .header("traceparent", invalid)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(events(output).stream().filter(event -> GREETING_EVENT.equals(event.get("message"))))
                .anySatisfy(event -> assertThat((String) event.get("traceId")).matches("[0-9a-f]{32}"));
        assertThat(output.getAll()).doesNotContain(invalid).doesNotContain("INJECTED");
    }

    @Test
    void concurrentRequestsKeepTheirOwnTraceAndPooledThreadsDoNotLeakIt(CapturedOutput output) {
        String token = ISSUER.token("alice");
        List<String> ids = IntStream.range(0, 8).mapToObj(i -> randomTraceId()).toList();

        List<HttpResponse<String>> responses = ids.stream()
                .map(id -> http().sendAsync(request("/hello?name=c", token, id), HttpResponse.BodyHandlers.ofString()))
                .toList().stream().map(CompletableFuture::join).toList();

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(200);
        }
        Set<String> logged = greetingTraceIds(output);
        assertThat(logged).containsAll(ids);

        // A follow-up request without traceparent gets a FRESH trace on a reused worker thread.
        http().sendAsync(request("/hello", token, null), HttpResponse.BodyHandlers.ofString()).join();
        Set<String> after = greetingTraceIds(output);
        after.removeAll(logged);
        assertThat(after).singleElement().satisfies(fresh -> {
            assertThat(fresh).matches("[0-9a-f]{32}");
            assertThat(ids).doesNotContain(fresh);
        });
    }

    @Test
    void multilineUnicodeMessageAndExceptionStayOneEscapedJsonEvent(CapturedOutput output) {
        String message = "probe ✓ \"quoted\"\nsecond line";

        LoggerFactory.getLogger("${package}.LoggingProbe").error(message, new IllegalStateException("probe failure"));

        Map<String, Object> event = events(output).stream()
                .filter(e -> message.equals(e.get("message")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("probe event not emitted as a JSON line"));
        assertThat(event).containsEntry("level", "ERROR");
        assertThat((String) event.get("stack_trace"))
                .contains("java.lang.IllegalStateException: probe failure")
                .contains("\n");
    }

    private static Set<String> greetingTraceIds(CapturedOutput output) {
        return events(output).stream()
                .filter(event -> GREETING_EVENT.equals(event.get("message")))
                .map(event -> (String) event.get("traceId"))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    private static Map<String, Object> singleEvent(CapturedOutput output, String message, String traceId) {
        List<Map<String, Object>> matches = events(output).stream()
                .filter(event -> message.equals(event.get("message")))
                .filter(event -> traceId.equals(event.get("traceId")))
                .toList();
        assertThat(matches).as("events '%s' with traceId %s", message, traceId).hasSize(1);
        return matches.getFirst();
    }

    /** Every stdout line that is a JSON object, parsed; a line starting with '{' that fails to parse fails the test. */
    private static List<Map<String, Object>> events(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(line -> JsonPath.parse(line).<Map<String, Object>>json())
                .toList();
    }
}
