package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
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
 * is one JSON object on stdout carrying the agreed fields, the inbound correlation id reaches the
 * application log, pooled request threads never leak a previous request's id, and bearer tokens never
 * reach the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest extends HttpIntegrationTestSupport {

    private static final String GREETING_EVENT = "Composing greeting";

    @Test
    void requestEventIsJsonWithServiceAndInboundCorrelation(CapturedOutput output) throws Exception {
        String correlationId = CorrelationId.random().value();
        String token = ISSUER.token("alice");

        HttpResponse<String> response = get("/hello?name=alice", token, correlationId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains(correlationId);
        Map<String, Object> event = singleEvent(output, GREETING_EVENT, correlationId);
        assertThat(event)
                .containsEntry("level", "INFO")
                .containsEntry("logger", HelloService.class.getName())
                .containsEntry("service", "${artifactId}")
                .containsEntry("message", GREETING_EVENT);
        assertThat(OffsetDateTime.parse((String) event.get("@timestamp"))).isNotNull();
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void invalidInboundCorrelationIdIsReplacedNotEchoed(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/hello", ISSUER.token("alice"), "not-a-valid-id");

        String issued = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(issued).matches("[0-9a-f]{32}");
        assertThat(singleEvent(output, GREETING_EVENT, issued)).isNotNull();
        assertThat(output.getAll()).doesNotContain("\"correlationId\":\"not-a-valid-id\"");
    }

    @Test
    void concurrentRequestsKeepTheirOwnCorrelationAndPooledThreadsDoNotLeakIt(CapturedOutput output) {
        String token = ISSUER.token("alice");
        List<String> ids = IntStream.range(0, 8).mapToObj(i -> CorrelationId.random().value()).toList();

        List<HttpResponse<String>> responses = ids.stream()
                .map(id -> http().sendAsync(request("/hello?name=c", token, id), HttpResponse.BodyHandlers.ofString()))
                .toList().stream().map(CompletableFuture::join).toList();

        for (int i = 0; i < ids.size(); i++) {
            assertThat(responses.get(i).statusCode()).isEqualTo(200);
            assertThat(responses.get(i).headers().firstValue("X-Correlation-Id")).contains(ids.get(i));
        }
        Set<String> logged = events(output).stream()
                .filter(event -> GREETING_EVENT.equals(event.get("message")))
                .map(event -> (String) event.get("correlationId"))
                .filter(ids::contains)
                .collect(Collectors.toSet());
        assertThat(logged).containsExactlyInAnyOrderElementsOf(ids);

        // A follow-up request without the header gets a FRESH id on a reused worker thread.
        String fresh = http().sendAsync(request("/hello", token, null), HttpResponse.BodyHandlers.ofString())
                .join().headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(ids).doesNotContain(fresh);
        assertThat(singleEvent(output, GREETING_EVENT, fresh)).isNotNull();
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

    private static Map<String, Object> singleEvent(CapturedOutput output, String message, String correlationId) {
        List<Map<String, Object>> matches = events(output).stream()
                .filter(event -> message.equals(event.get("message")))
                .filter(event -> correlationId.equals(event.get("correlationId")))
                .toList();
        assertThat(matches).as("events '%s' with correlationId %s", message, correlationId).hasSize(1);
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
