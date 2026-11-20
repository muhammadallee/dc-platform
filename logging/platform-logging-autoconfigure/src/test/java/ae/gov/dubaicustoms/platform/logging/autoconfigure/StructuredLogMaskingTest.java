package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform's masking on Boot's own structured-logging output (D141): each event goes through Boot's
 * {@link StructuredLogEncoder} in the Logstash format, with the platform's defaults and the masking customizer Boot
 * loads from {@code spring.factories}. Ports the cases of the v3 encoder test that still apply (hardening phase 2
 * rounds 1 and 2, verification passes and the Gate F trial); those about Logstash markers, raw JSON, {@code Kv} and
 * {@code LogSanitizer} are retired with those types (scope §7).
 */
class StructuredLogMaskingTest {

    private static final String HS256_SPACE_HEADER = b64url(" {\"alg\":\"HS256\"}");
    private static final String SPACED_JWT = HS256_SPACE_HEADER + "." + b64url("{\"sub\":\"alice\"}") + ".c2ln";
    private static final String LOGGER = "ae.gov.dubaicustoms.platform.orders.OrderService";

    private LoggerContext context;
    private StructuredLogEncoder encoder;

    @BeforeEach
    void startEncoder() {
        start(new MockEnvironment());
    }

    @AfterEach
    void stopEncoder() {
        encoder.stop();
    }

    private void start(MockEnvironment environment) {
        new PlatformLoggingEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(environment.getProperty("logging.structured.format.console"));
        encoder.start();
    }

    @Test
    void aSchemeWordIsItselfACredentialUnlessAnotherSchemeFollows() {
        // Verification pass 2: the overlap repair must not exempt "Bearer bearer".
        assertThat(message("Bearer bearer")).isEqualTo("Bearer ****");
        assertThat(message("Bearer BEARER")).isEqualTo("Bearer ****");
        assertThat(message("bearer Bearer abcdefgh12345678")).isEqualTo("bearer Bearer ****");
        assertThat(message("basic Basic YWxpY2U6czNjcjN0LTJiMDgwMA==")).isEqualTo("basic Basic ****");
    }

    @Test
    void ordinaryValuesNestedWithinTheTraversalDepthStayReadable() {
        // Verification pass 2: common values are not masked merely for being nested.
        assertThat(encode(event("stored").pair("payload", wrap(1793001600123L, 32)))).contains("1793001600123");
    }

    @Test
    void unchangedArrayContentsAndTheDepthBoundaryAreKept() {
        // Verification pass 4 regressions 1 and 2.
        String line = encode(event("stored")
                .pair("payload", new Object[] {"ready", new int[] {1, 2}})
                .pair("deep", wrap(1793001600123L, 62)));
        assertThat(line).contains("\"payload\":[\"ready\",[1,2]]").contains("1793001600123");
    }

    @Test
    void changedArraysInsideCollectionsRenderByContent() {
        // Verification pass 5 regression 4.
        assertThat(encode(event("stored").pair("payload", List.<Object>of((Object) new Object[] {"ready"}))))
                .contains("\"payload\":[[\"ready\"]]");
    }

    @Test
    void cardNumbersInPrimitiveArraysAreMasked() {
        // Verification pass 6 regression 2, as a key-value pair and in the message (HI-0050).
        String line = encode(event("{}", (Object) new double[] {4111111111111111.0d})
                .pair("payload", List.of("ready", new double[] {4111111111111111.0d})));
        assertThat(line).doesNotContain("4.111111111111111E15").doesNotContain("4111111111111111")
                .contains("\"payload\":[\"ready\",[\"****\"]]");
    }

    @Test
    void objectArraysWithNullsStayReadable() {
        assertThat(encode(event("stored").pair("items", new Object[] {"ok", null}))).contains("\"items\":[\"ok\",null]");
    }

    static Stream<Arguments> leaks() {
        byte[] bytes = "hunter2-bytes".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return Stream.of(
                // R2-F2: quoted cookie pairs.
                row("quoted cookie pair", event("{}", "Cookie: sid=\"abc123\"; session=deadbeef"), "abc123", "deadbeef"),
                row("quoted set-cookie", event("{}", "Set-Cookie: sid=\"abc123\"; Path=/; session=deadbeef"),
                        "abc123", "deadbeef"),
                // R2-F3: boolean and binary values under credential keys, at depth 1 and 2.
                row("boolean credential", event("stored").pair("token", true), "\"token\":true"),
                row("binary credential", event("stored").pair("secret", bytes), encoded, "104,117"),
                row("nested binary credential", event("stored").pair("auth", Map.of("password", bytes)), encoded, "104,117"),
                // R2-F4: a credential-named pair holding spaces is masked whole.
                row("password with spaces", event("stored").pair("password", "correct horse battery staple"),
                        "horse", "staple"),
                // R2-F6: reserved names are not an exemption for consumer values.
                row("nested logger field", event("probe").pair("payload", Map.of("logger", "password=hunter2")), "hunter2"),
                row("nested level field", event("probe").pair("payload", Map.of("level", "card 4111111111111111")),
                        "4111111111111111"),
                // R2-F7: a JWT whose header has leading JSON whitespace.
                row("whitespace-led JWT header", event("token {}", SPACED_JWT), HS256_SPACE_HEADER),
                // Verification pass: quoted-key cookie forms.
                row("quoted cookie key with =", event("{}", "\"cookie\"=\"sid=abc123\""), "abc123"),
                row("quoted cookie key, bare value", event("{}", "{\"cookie\":12345678}"), "12345678"),
                // Gate F trial: a non-credential "basic"/"bearer" word before the real scheme, and compound credential
                // names in text and in key-value pairs.
                row("basic before Basic", event("header {}", "basic Basic YWxpY2U6czNjcjN0LTJiMDgwMA=="),
                        "YWxpY2U6czNjcjN0LTJiMDgwMA=="),
                row("bearer before Bearer", event("header {}", "bearer Bearer abcdefgh12345678"), "abcdefgh12345678"),
                row("compound key in text", event("{}", "session_token=st-123456 db-password: pw-987 smtpSecret=sx-1"),
                        "st-123456", "pw-987", "sx-1"),
                row("compound credential pairs", event("stored").pair("sessionToken", "st-abcdef").pair("x-auth-token", "xa-1"),
                        "st-abcdef", "xa-1"),
                row("credential 17 levels deep", event("stored")
                        .pair("payload", wrap(Map.of("password", "correct horse battery staple"), 16)), "horse", "staple"),
                row("credential below the traversal depth", event("stored")
                        .pair("payload", wrap(Map.of("password", "correct horse battery staple"), 64)), "horse", "staple"),
                // Verification pass 2: compound cookie names in text.
                row("compound cookie key in text", event("{}", "sessionCookie=abc123"), "abc123"),
                row("compound cookie JSON field", event("{}", "{\"sessionCookie\":\"sid=abc123\"}"), "abc123"),
                // An MDC credential is masked whole (D101/D102).
                row("MDC credential", event("stored").mdc("authorization", "Basic YWxpY2U6czNjcjN0").mdc("session-token", "st-9"),
                        "YWxpY2U6czNjcjN0", "st-9"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leaks")
    void nothingSecretReachesTheEncodedBytes(String name, Event event, List<String> secrets) {
        String line = encode(event);
        for (String secret : secrets) {
            assertThat(line).as(name).doesNotContain(secret);
        }
    }

    @Test
    void aCredentialFieldIsReplacedWholeWithNoPartialObjectWritten() {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("kind", "refresh");
        token.put("expires", 3600);
        String line = encode(event("stored").pair("config", Map.of("token", token, "region", "dxb")));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) parse(line).get("config");
        assertThat(config).containsEntry("token", "****").containsEntry("region", "dxb");
        assertThat(line).doesNotContain("refresh").doesNotContain("3600");
    }

    @Test
    void aThrowingToStringCostsOnlyThatValue() {
        // R2-F8: a sibling's toString failure must not lose the event or the credential mask.
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("lazy state unavailable, password=hunter3");
            }
        };
        String line = encode(event("stored").pair("password", "hunter2").pair("entity", hostile));
        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter3")
                .contains("\"entity\":\"[toString() failed: java.lang.IllegalStateException]\"");
    }

    @Test
    void anyThrowableFromToStringIsContainedAndNothingIsRenderedTwice() {
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
        String line = encode(event("stored").pair("a", asserting).pair("entity", flaky));
        assertThat(line).contains("\"a\":\"[toString() failed: java.lang.AssertionError]\"").contains("\"entity\":\"ok\"")
                .doesNotContain("hunter4");
    }

    @Test
    void longQuotedValuesDoNotOverflowTheStack() {
        // R2-F1: a long quoted credential and a long escaped one, on the logging thread.
        String plain = "password=\"" + "x".repeat(200_000) + "\"";
        String escaped = "password=\"" + "\\x".repeat(100_000) + "\"";
        String unterminated = "password=\"" + "x".repeat(200_000);
        for (String text : List.of(plain, escaped, unterminated)) {
            assertThat(encode(event("{}", text))).contains("password=").doesNotContain("xxxxxxxxxx");
        }
    }

    @Test
    void hugeDecimalExponentsAreNotExpanded() {
        // R2-F9: 1E+100000000 must be rejected by digit count, not by expanding it.
        long started = System.nanoTime();
        String line = encode(event("stored").pair("amount", new BigDecimal("1E+100000000")));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(line).contains("\"amount\":1E+100000000");
    }

    @Test
    void ordinaryFieldsAndValuesStayReadable() {
        // Negatives for the reserved-name change (R2-F6) and the JWT change (R2-F7).
        String stack = "java.lang.IllegalStateException: boom\n\tat ae.gov.dubaicustoms.platform.logging.autoconfigure"
                + ".internal.PlatformValueMasker.mask(PlatformValueMasker.java:57)\n\tat org.springframework.web.servlet"
                + ".DispatcherServlet.doDispatch(DispatcherServlet.java:1089)";
        String text = "host orders.customs.svc.cluster.local file backup.tar.gz version 4.1.0-SNAPSHOT exceptions.internal.Handler "
                + "exception.handler.registry ewallet.payments.provider at 2026-10-27T05:00:00.123+04:00";
        Map<String, Object> line = parse(encode(event("{} | {}", text, stack)));
        assertThat(line).containsEntry("message", text + " | " + stack).containsEntry("logger", LOGGER)
                .containsEntry("level", "INFO").containsEntry("service", "application")
                .doesNotContainKeys("logger_name", "@version", "thread_name", "level_value");
    }

    @Test
    void objectValuesAreRenderedAsMaskedText() {
        // A record keeps its component names, so a credential component is masked whole; any other object is its
        // masked toString().
        record Login(String user, String password) {
        }
        Object pojo = new Object() {
            @Override
            public String toString() {
                return "Session[user=ali, token=tk-123456]";
            }
        };
        String line = encode(event("stored").pair("login", new Login("ali", "hunter5")).pair("session", pojo));
        assertThat(line).contains("\"login\":{\"user\":\"ali\",\"password\":\"****\"}")
                // The v3 key=value pattern takes the value up to a space, quote or , ; } &: here also the ].
                .contains("\"session\":\"Session[user=ali, token=****\"").doesNotContain("hunter5")
                .doesNotContain("tk-123456");
    }

    @Test
    void aContainerHoldingItselfEndsTheDescentMasked() {
        Map<String, Object> loop = new HashMap<>();
        loop.put("self", loop);
        List<Object> list = new ArrayList<>();
        list.add(list);
        String line = encode(event("stored").pair("loop", loop).pair("list", list));
        assertThat(line).contains("\"****\"").contains("\"message\":\"stored\"");
    }

    @Test
    void extraPatternsAndTheRedactionSwitchApply() {
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "CASE-\\d{6}"));
        assertThat(message("see CASE-123456 password=hunter2")).isEqualTo("see **** password=****");
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.enabled", "false"));
        assertThat(message("password=hunter2")).isEqualTo("password=hunter2");
    }

    @Test
    void theKillSwitchLeavesBootsOutputUntouched() {
        encoder.stop();
        MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.logging.enabled", "false");
        environment.setProperty("logging.structured.format.console", "logstash");
        start(environment);
        assertThat(encode(event("password=hunter2"))).contains("password=hunter2").contains("\"logger_name\"");
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private String message(String text) {
        return (String) parse(encode(event("{}", text))).get("message");
    }

    private String encode(Event event) {
        return new String(encoder.encode(event.build(context)), StandardCharsets.UTF_8).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) {
        return new JsonMapper().readValue(line, Map.class);
    }

    private static Event event(String message, Object... arguments) {
        return new Event(message, arguments);
    }

    private static Arguments row(String name, Event event, String... secrets) {
        return Arguments.of(name, event, List.of(secrets));
    }

    private static Object wrap(Object value, int times) {
        Object wrapped = value;
        for (int i = 0; i < times; i++) {
            wrapped = List.of(wrapped);
        }
        return wrapped;
    }

    private static String b64url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** One logging event: a message with SLF4J arguments, key-value pairs and MDC entries. */
    static final class Event {

        private final String message;
        private final Object[] arguments;
        private final List<KeyValuePair> pairs = new ArrayList<>();
        private final Map<String, String> mdc = new LinkedHashMap<>();

        Event(String message, Object[] arguments) {
            this.message = message;
            this.arguments = arguments;
        }

        Event pair(String key, Object value) {
            pairs.add(new KeyValuePair(key, value));
            return this;
        }

        Event mdc(String key, String value) {
            mdc.put(key, value);
            return this;
        }

        LoggingEvent build(LoggerContext context) {
            Logger logger = context.getLogger(LOGGER);
            LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.INFO, message, null,
                    arguments.length == 0 ? null : arguments);
            pairs.forEach(event::addKeyValuePair);
            event.setMDCPropertyMap(mdc);
            return event;
        }

        @Override
        public String toString() {
            return message;
        }
    }
}
