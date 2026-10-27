package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static net.logstash.logback.argument.StructuredArguments.entries;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;
import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.logging.Kv;
import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * The real {@link PlatformJsonEncoder} on hand-built events: what reaches the encoded bytes, through
 * every generator path (string, number, boolean, binary, nested objects, arrays) and the formatted
 * message. Phase-02 round-2 reproductions live here.
 */
class EncoderRedactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HS256_SPACE_HEADER = b64url(" {\"alg\":\"HS256\"}");
    private static final String SPACED_JWT = HS256_SPACE_HEADER + "." + b64url("{\"sub\":\"alice\"}") + ".c2ln";

    // The SLF4J-bound context: it carries the MDC adapter the encoder reads.
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    @AfterEach
    void clearSanitizers() {
        publish();
    }

    static Stream<Arguments> leaks() {
        return Stream.of(
                // R2-F2: quoted cookie pairs.
                row("quoted cookie pair", "{}", new Object[] {"Cookie: sid=\"abc123\"; session=deadbeef"},
                        "abc123", "deadbeef"),
                row("quoted set-cookie", "{}", new Object[] {"Set-Cookie: sid=\"abc123\"; Path=/; session=deadbeef"},
                        "abc123", "deadbeef"),
                // R2-F3: boolean and binary values under credential keys, at depth 1 and 2.
                row("boolean credential", "stored", new Object[] {kv("token", true)}, "\"token\":true"),
                row("binary credential", "stored", new Object[] {kv("secret", "hunter2-bytes".getBytes(StandardCharsets.UTF_8))},
                        Base64.getEncoder().encodeToString("hunter2-bytes".getBytes(StandardCharsets.UTF_8))),
                row("nested binary credential", "stored",
                        new Object[] {kv("auth", Map.of("password", "hunter2-bytes".getBytes(StandardCharsets.UTF_8)))},
                        Base64.getEncoder().encodeToString("hunter2-bytes".getBytes(StandardCharsets.UTF_8))),
                // R2-F4: credential-keyed structured arguments rendered in the message.
                row("kv password with spaces", "{}", new Object[] {kv("password", "correct horse battery staple")},
                        "horse", "staple"),
                row("Kv password with spaces", "{}", new Object[] {Kv.of("password", "correct horse battery staple")},
                        "horse", "staple"),
                row("value-only password", "{}", new Object[] {v("password", "hunter2")}, "hunter2"),
                // R2-F6: reserved names are not an exemption for consumer values.
                row("nested logger field", "probe", new Object[] {kv("payload", Map.of("logger", "password=hunter2"))},
                        "hunter2"),
                row("nested level field", "probe", new Object[] {kv("payload", Map.of("level", "card 4111111111111111"))},
                        "4111111111111111"),
                // R2-F7: a JWT whose header has leading JSON whitespace.
                row("whitespace-led JWT header", "token {}", new Object[] {SPACED_JWT}, HS256_SPACE_HEADER),
                // Verification pass: quoted-key cookie forms, and a credential below the traversal depth.
                row("quoted cookie key with =", "{}", new Object[] {"\"cookie\"=\"sid=abc123\""}, "abc123"),
                row("quoted cookie key, bare value", "{}", new Object[] {"{\"cookie\":12345678}"}, "12345678"),
                // Gate F trial: a non-credential "basic"/"bearer" word before the real scheme, and
                // compound credential names in text, MDC-like fields and arguments.
                row("basic before Basic", "header {}", new Object[] {"basic Basic YWxpY2U6czNjcjN0LTJiMDgwMA=="},
                        "YWxpY2U6czNjcjN0LTJiMDgwMA=="),
                row("bearer before Bearer", "header {}", new Object[] {"bearer Bearer abcdefgh12345678"}, "abcdefgh12345678"),
                row("compound key in text", "{}", new Object[] {"session_token=st-123456 db-password: pw-987 smtpSecret=sx-1"},
                        "st-123456", "pw-987", "sx-1"),
                row("compound credential field", "stored", new Object[] {kv("sessionToken", "st-abcdef"), Kv.of("x-auth-token", "xa-1")},
                        "st-abcdef", "xa-1"),
                row("credential 17 levels deep", "{}",
                        new Object[] {kv("payload", wrap(Map.of("password", "correct horse battery staple"), 16))},
                        "horse", "staple"),
                row("credential below the traversal depth", "{}",
                        new Object[] {kv("payload", wrap(Map.of("password", "correct horse battery staple"), 64))},
                        "horse", "staple"),
                // Verification pass 2: compound cookie names in text.
                row("compound cookie key in text", "{}", new Object[] {"sessionCookie=abc123"}, "abc123"),
                row("compound cookie JSON field", "{}", new Object[] {"{\"sessionCookie\":\"sid=abc123\"}"}, "abc123"));
    }

    @Test
    void aSchemeWordIsItselfACredentialUnlessAnotherSchemeFollows() throws Exception {
        // Verification pass 2: the overlap repair must not exempt "Bearer bearer".
        assertThat(message("Bearer bearer")).isEqualTo("Bearer ****");
        assertThat(message("Bearer BEARER")).isEqualTo("Bearer ****");
        assertThat(message("bearer Bearer abcdefgh12345678")).isEqualTo("bearer Bearer ****");
        assertThat(message("basic Basic YWxpY2U6czNjcjN0LTJiMDgwMA==")).isEqualTo("basic Basic ****");
    }

    private String message(String text) throws Exception {
        return JSON.readTree(encode(event("{}", new Object[] {text}))).get("message").asText();
    }

    @Test
    void ordinaryValuesNestedWithinTheTraversalDepthStayReadable() throws Exception {
        // Verification pass 2: R3.3 common values are not masked merely for being nested.
        String line = encode(event("{}", new Object[] {kv("payload", wrap(1793001600123L, 32))}));
        assertThat(JSON.readTree(line).get("message").asText()).contains("1793001600123");
    }

    @Test
    void aThrowingValueInsideALogstashMarkerNeverReachesTheStatusManager() throws Exception {
        // Verification pass 2: StructuredArguments.toString reports the exception, unmasked, as a status.
        HostileBean bad = new HostileBean();
        int before = context.getStatusManager().getCount();
        String line = encode(event("{} {} {}", new Object[] {
                kv("items", new Object[] {null, bad}), kv("entity", bad), net.logstash.logback.argument.StructuredArguments.fields(bad)}));
        assertThat(JSON.readTree(line).get("message").asText())
                .isEqualTo("[unrenderable] [unrenderable] [unrenderable]");
        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
    }

    @Test
    void aMarkerNestedInAValueIsRenderedUnderTheSameGuard() throws Exception {
        // Verification pass 3: a marker inside a structured value went through its own toString.
        int before = context.getStatusManager().getCount();
        String line = encode(event("{} {}", new Object[] {
                kv("payload", wrap(kv("entity", new HostileBean()), 16)),
                kv("wrapper", List.of(kv("password", "hunter2"), Kv.of("token", "tk-1")))}));
        // (The text masks then take a bare value up to whitespace , ; or } — the closing ] included.)
        assertThat(JSON.readTree(line).get("message").asText())
                .startsWith("[unrenderable] wrapper=[password=****, token=****")
                .doesNotContain("hunter2").doesNotContain("tk-1");
        // A nested Kv is written {"token": …}, so the credential-name field masker applies.
        assertThat(line).doesNotContain("hunter2").doesNotContain("tk-1");
        assertThat(JSON.readTree(line).get("wrapper").get(1).get("token").asText()).isEqualTo("****");
        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
    }

    @Test
    void chainedMarkersKeepTheirReferencesInTheMessage() throws Exception {
        // Verification pass 3: LogstashMarker.toString appends referenced markers, ", "-joined.
        net.logstash.logback.marker.LogstashMarker chained = net.logstash.logback.marker.Markers.append("orderId", "ORD-42")
                .and(net.logstash.logback.marker.Markers.append("phase", "ready"))
                .and(net.logstash.logback.marker.Markers.append("password", "hunter2"));
        String line = encode(event("{}", new Object[] {chained}));
        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("orderId=ORD-42, phase=ready, password=****");
    }

    @Test
    void aDeferredArgumentLeaksNothing() throws Exception {
        // Pre-pass-4 sweep: defer(...) is the one StructuredArgument that is not a marker. Encoder 8.1
        // gives it no toString (the message shows its identity), and the JSON masks the supplied field.
        int before = context.getStatusManager().getCount();
        String line = encode(event("{} {}", new Object[] {
                net.logstash.logback.argument.StructuredArguments.defer(() -> v("password", "hunter2")),
                net.logstash.logback.argument.StructuredArguments.defer(() -> kv("entity", new HostileBean()))}));
        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter5");
        assertThat(JSON.readTree(line).get("password").asText()).isEqualTo("****");
        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
    }

    /** Serializable as JSON ({@code {"id":"b-1"}}); its toString throws with a secret in the message. */
    public static final class HostileBean {

        public String getId() {
            return "b-1";
        }

        @Override
        public String toString() {
            throw new IllegalStateException("password=hunter5");
        }
    }

    private static Object wrap(Object value, int times) {
        Object wrapped = value;
        for (int i = 0; i < times; i++) {
            wrapped = List.of(wrapped);
        }
        return wrapped;
    }

    @Test
    void aSanitizedValueBelowTheTraversalDepthIsNotShownInTheMessage() {
        publish((key, value) -> "customerName".equals(key) ? "C***" : value);
        String line = encode(event("{}", new Object[] {kv("customerName", wrap("Fatima", 64))}));
        assertThat(line).doesNotContain("Fatima");
    }

    @Test
    void anyThrowableFromToStringIsContainedAndNothingIsRenderedTwice() throws Exception {
        Object asserting = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("boom");
            }
        };
        Object flaky = new Object() {
            private int calls;

            @Override
            public String toString() {
                if (++calls == 1) {
                    return "ok";
                }
                throw new IllegalStateException("password=hunter4");
            }
        };
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        String line;
        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            line = encode(event("{} {}", new Object[] {Kv.of("a", asserting), Kv.of("entity", flaky)}));
        } finally {
            System.setErr(originalErr);
        }
        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("[unrenderable] entity=ok");
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("hunter4");
    }

    @Test
    void objectArraysWithNullsStayReadable() throws Exception {
        String line = encode(event("{}", new Object[] {kv("items", new Object[] {"ok", null})}));
        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("items=[ok, null]");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leaks")
    void nothingSecretReachesTheEncodedBytes(String name, String message, Object[] arguments, List<String> secrets) {
        String line = encode(event(message, arguments));
        for (String secret : secrets) {
            assertThat(line).as(name).doesNotContain(secret);
        }
    }

    @Test
    void aCredentialFieldIsReplacedWholeWithNoPartialObjectWritten() throws Exception {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("kind", "refresh");
        token.put("expires", 3600);
        String line = encode(event("stored", new Object[] {kv("config", Map.of("token", token, "region", "dxb"))}));
        JsonNode config = JSON.readTree(line).get("config");
        assertThat(config.get("token").asText()).isEqualTo("****");
        assertThat(config.get("region").asText()).isEqualTo("dxb");
        assertThat(line).doesNotContain("refresh").doesNotContain("3600");
    }

    @Test
    void customSanitizerReachesMapsNestedValuesArraysAndNumbersInFieldAndMessage() throws Exception {
        // R2-F5: one traversal for field output and message rendering.
        publish((key, value) -> "customerName".equals(key) ? "C***" : value,
                (key, value) -> "accountNo".equals(key) ? "A***" : value);
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("customerName", "Fatima");
        String line = encode(event("{} {} {} {}", new Object[] {
                entries(Map.of("customerName", "Fatima")),
                kv("person", person),
                kv("customerName", new String[] {"Fatima", "Omar"}),
                kv("accountNo", 12345678L)}));
        assertThat(line).doesNotContain("Fatima").doesNotContain("Omar").doesNotContain("12345678");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).contains("C***").contains("A***");
    }

    @Test
    void aThrowingToStringCostsOnlyThatArgument() throws Exception {
        // R2-F8: a sibling's toString failure must not lose the event or the credential mask.
        publish((key, value) -> value);
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("lazy state unavailable, password=hunter3");
            }
        };
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        String line;
        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            line = encode(event("{} {}", new Object[] {kv("password", "hunter2"), Kv.of("entity", hostile)}));
        } finally {
            System.setErr(originalErr);
        }
        assertThat(line).isNotEmpty().doesNotContain("hunter2").doesNotContain("hunter3");
        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("password=**** [unrenderable]");
        // Not handed to SLF4J's formatter, which would report the exception text on stderr, unmasked.
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("hunter3");
    }

    @Test
    void longQuotedValuesDoNotOverflowTheStack() {
        // R2-F1: a long quoted credential and a long escaped one, on the logging thread.
        String plain = "password=\"" + "x".repeat(200_000) + "\"";
        String escaped = "password=\"" + "\\x".repeat(100_000) + "\"";
        String unterminated = "password=\"" + "x".repeat(200_000);
        for (String text : List.of(plain, escaped, unterminated)) {
            String line = encode(event("{}", new Object[] {text}));
            assertThat(line).contains("password=").doesNotContain("xxxxxxxxxx");
        }
    }

    @Test
    void hugeDecimalExponentsAreNotExpanded() {
        // R2-F9: 1E+100000000 must be rejected by digit count, not by expanding it.
        long started = System.nanoTime();
        String line = encode(event("{}", new Object[] {kv("amount", new BigDecimal("1E+100000000"))}));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(line).contains("\"amount\":1E+100000000");
    }

    @Test
    void encoderFieldsAndOrdinaryValuesStayReadable() throws Exception {
        // Negatives for the reserved-name change (R2-F6) and the JWT change (R2-F7).
        String stack = "java.lang.IllegalStateException: boom\n\tat ae.gov.dubaicustoms.platform.logging.autoconfigure"
                + ".internal.PlatformValueMasker.mask(PlatformValueMasker.java:57)\n\tat org.springframework.web.servlet"
                + ".DispatcherServlet.doDispatch(DispatcherServlet.java:1089)\n\tat java.base/java.util.concurrent"
                + ".ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)";
        String text = "host orders.customs.svc.cluster.local file backup.tar.gz version 4.1.0-SNAPSHOT exceptions.internal.Handler "
                + "exception.handler.registry ewallet.payments.provider at 2026-10-27T05:00:00.123+04:00";
        LoggingEvent event = event("{} | {}", new Object[] {text, stack});
        String line = encode(event);
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo(text + " | " + stack);
        assertThat(node.get("logger").asText()).isEqualTo("ae.gov.dubaicustoms.platform.orders.OrderService");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
    }

    private static Arguments row(String name, String message, Object[] arguments, String... secrets) {
        return Arguments.of(name, message, arguments, List.of(secrets));
    }

    private LoggingEvent event(String message, Object[] arguments) {
        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("ae.gov.dubaicustoms.platform.orders.OrderService"),
                Level.INFO, message, null, arguments);
        event.setLoggerContext(context);
        return event;
    }

    private String encode(LoggingEvent event) {
        PlatformJsonEncoder encoder = new PlatformJsonEncoder();
        encoder.setContext(context);
        encoder.getFieldNames().setLogger("logger"); // as logback-platform.xml
        encoder.start();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void publish(LogSanitizer... sanitizers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < sanitizers.length; i++) {
            beans.registerSingleton("s" + i, sanitizers[i]);
        }
        new LogSanitizerRegistry(beans.getBeanProvider(LogSanitizer.class)).afterSingletonsInstantiated();
    }
}
