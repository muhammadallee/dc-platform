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
                .contains("\"entity\":\"[unrenderable]\"");
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
        assertThat(line).contains("\"a\":\"[unrenderable]\"").contains("\"entity\":\"ok\"")
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

    // --- Codex phase 3 round 1 -------------------------------------------------------------------------------

    @Test
    void writableJsonIsMaskedText() {
        // Finding 2: Boot writes a WritableJson as raw JSON, past the value processor.
        String line = encode(event("stored").pair("raw",
                org.springframework.boot.json.JsonWriter.standard().write(Map.of("password", "hunter2"))));
        assertThat(line).doesNotContain("hunter2");
        assertThat(parse(line).get("raw")).isInstanceOf(String.class);
    }

    @Test
    void messageArgumentsAreMaskedByTheirStructure() {
        // Finding 3: a map argument's credential value is masked whole, spaces and all; a throwing argument costs only
        // its own place.
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("password=hunter6");
            }
        };
        String line = encode(event("login {} by {} as {}", Map.of("password", "correct horse battery staple"), hostile,
                List.of(Map.of("token", "tk-1"))));
        assertThat(line).doesNotContain("horse").doesNotContain("staple").doesNotContain("hunter6")
                .doesNotContain("tk-1");
        assertThat(parse(line).get("message"))
                .isEqualTo("login {password=****} by [unrenderable] as [{token=****}]");
    }

    /** A bean whose credential is a property and whose toString does not label it (finding 4). */
    public static final class Account {
        public String getUser() {
            return "ali";
        }

        public String getPassword() {
            return "unlabelled-secret-7";
        }

        public boolean isActive() {
            return true;
        }

        public String getBroken() {
            throw new IllegalStateException("password=hunter8");
        }

        @Override
        public String toString() {
            return "Account ali unlabelled-secret-7";
        }
    }

    @Test
    void beanPropertiesKeepTheirNames() {
        String line = encode(event("stored {}", new Account()).pair("account", new Account()));
        assertThat(line).doesNotContain("unlabelled-secret-7").doesNotContain("hunter8");
        assertThat(parse(line).get("account")).isEqualTo(Map.of("active", true, "broken", "[unrenderable]",
                "password", "****", "user", "ali"));
    }

    @Test
    void textLikeValuesAreRenderedOnceAndMasked() {
        // Finding 5: a Path, and a CharSequence whose text changes after the first read.
        CharSequence stateful = new CharSequence() {
            private int reads;

            @Override
            public String toString() {
                return ++reads == 1 ? "ok" : "password=hunter9";
            }

            @Override
            public int length() {
                return toString().length();
            }

            @Override
            public char charAt(int index) {
                return toString().charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return toString().subSequence(start, end);
            }
        };
        String line = encode(event("stored").pair("path", java.nio.file.Path.of("password=hunter2"))
                .pair("stateful", stateful));
        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter9");
        assertThat(parse(line)).containsEntry("path", "password=****").containsEntry("stateful", "ok");
    }

    @Test
    void everyKindOfNumberIsChecked() {
        // Finding 6: an AtomicLong card number, and non-finite doubles as valid JSON.
        String line = encode(event("stored").pair("card", new java.util.concurrent.atomic.AtomicLong(4111111111111111L))
                .pair("nan", Double.NaN).pair("infinite", Float.NEGATIVE_INFINITY).pair("count", new
                        java.util.concurrent.atomic.AtomicInteger(3)));
        assertThat(line).doesNotContain("4111111111111111");
        assertThat(parse(line)).containsEntry("card", "****").containsEntry("nan", "NaN")
                .containsEntry("infinite", "-Infinity").containsEntry("count", 3);
    }

    @Test
    void characterArraysAreText() {
        // Finding 7.
        String line = encode(event("stored").pair("contact", "alice@example.com".toCharArray())
                .pair("nested", List.of((Object) "Bearer abcdefgh12345678".toCharArray())));
        assertThat(line).doesNotContain("alice").doesNotContain("abcdefgh12345678");
        assertThat(parse(line)).containsEntry("contact", "****").containsEntry("nested", List.of("Bearer ****"));
    }

    @Test
    void extraPatternsReachNumbers() {
        // Finding 8: the replacement for a LogSanitizer bean on numbers, nested too.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "^12345678$"));
        String line = encode(event("stored {}", 12345678L).pair("accountNo", 12345678L)
                .pair("nested", Map.of("accounts", List.of(12345678, 42))));
        assertThat(line).doesNotContain("12345678");
        assertThat(parse(line)).containsEntry("accountNo", "****")
                .containsEntry("nested", Map.of("accounts", List.of("****", 42)));
    }

    @Test
    void aMemberRenamedToACredentialNameIsMasked() {
        // Finding 9: Boot's rename keeps the original name in the path.
        encoder.stop();
        start(new MockEnvironment().withProperty("logging.structured.json.rename.auth", "password"));
        String line = encode(event("stored").pair("auth", "correct horse battery staple"));
        assertThat(line).doesNotContain("horse");
        assertThat(parse(line)).containsEntry("password", "****");
    }

    @Test
    void strictModeFiltersOnlyContextKeysWhateverTheirNames() {
        // Finding 11: an unlisted MDC key named like a layout member is left out too.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.mdc.include", "tenant"));
        String line = encode(event("strict").mdc("stack_trace", "mdc-secret").mdc("tags", "mdc-tags")
                .mdc("tenant", "dubai").mdc("traceId", "4bf92f3577b34da6a3ce929d0e0e4736").pair("unlisted", "y"));
        assertThat(line).doesNotContain("mdc-secret").doesNotContain("mdc-tags").doesNotContain("unlisted");
        assertThat(parse(line)).containsEntry("tenant", "dubai").containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsKeys("@timestamp", "message", "logger", "level", "service");
    }

    @Test
    void bootsOtherFormatsStillWorkAndAreMasked() {
        // Findings 12 and 13: a service that picks ECS or GELF gets that format, masked, with its own fields.
        for (String format : List.of("ecs", "gelf", "logstash")) {
            encoder.stop();
            MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.logging.mdc.include", "tenant");
            environment.setProperty("logging.structured.format.console", format);
            environment.setProperty("spring.application.name", "orders");
            start(environment);
            String line = encode(event("as {} password=hunter2", format).mdc("tenant", "dubai").pair("raw",
                    org.springframework.boot.json.JsonWriter.standard().write(Map.of("password", "hunter2"))));
            // C: a WritableJson a service logs is masked whole in every format.
            assertThat(line).as(format).doesNotContain("hunter2").contains("password=****");
            assertThat(parse(line)).as(format).isNotEmpty();
        }
    }

    @Test
    void branchingCyclesAndLargeValuesStayBounded() {
        // Finding 14: a map holding itself under two keys, and a value with more nodes than the budget.
        Map<String, Object> loop = new HashMap<>();
        loop.put("left", loop);
        loop.put("right", loop);
        List<Object> wide = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            wide.add(i);
        }
        long started = System.nanoTime();
        String line = encode(event("stored").pair("loop", loop).pair("wide", wide));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(parse(line).get("loop")).isEqualTo(Map.of("left", "****", "right", "****"));
        assertThat((List<?>) parse(line).get("wide")).hasSizeLessThanOrEqualTo(10_001);
        assertThat(line).contains("\"****\"");
    }

    @Test
    void aSharedButAcyclicValueIsShownAtEachPlace() {
        List<String> shared = List.of("x");
        String line = encode(event("stored").pair("pair", List.of(shared, shared)));
        assertThat(parse(line).get("pair")).isEqualTo(List.of(List.of("x"), List.of("x")));
    }

    @Test
    void anInvalidPatternFailsOnlyWhenMaskingIsOn() {
        // Finding 17.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "[")
                .withProperty("dc.platform.logging.redaction.enabled", "false"));
        assertThat(message("password=hunter2")).isEqualTo("password=hunter2");
        encoder.stop();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformLogMasking.masker(
                new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "[")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dc.platform.logging.redaction.patterns");
        start(new MockEnvironment());
    }

    // --- Codex phase 3 round 1 reassessment (issues A-K) -------------------------------------------------------

    @Test
    void writableJsonIsMaskedWholeWhateverItsEscapes() {
        // B: an escaped credential name inside raw JSON.
        String line = encode(event("stored").pair("raw", (org.springframework.boot.json.WritableJson) out ->
                out.append("{\"\\u0070assword\":\"hunter2\"}")));
        assertThat(line).doesNotContain("hunter2");
        assertThat(parse(line)).containsEntry("raw", "****");
    }

    @Test
    void throwingCharacterDataAndMapKeysCostOnlyTheirPlace() {
        // D: a CharSequence argument and a map key whose rendering throws; the line is still written.
        CharSequence hostile = new CharSequence() {
            @Override
            public String toString() {
                throw new IllegalStateException("password=hunter10");
            }

            @Override
            public int length() {
                return 0;
            }

            @Override
            public char charAt(int index) {
                return 'x';
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return this;
            }
        };
        Object hostileKey = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("password=hunter11");
            }
        };
        String line = encode(event("probe {} {}", hostile, Map.of(hostileKey, "v")).pair("text", hostile));
        assertThat(line).doesNotContain("hunter10").doesNotContain("hunter11");
        assertThat(parse(line)).containsEntry("text", "[unrenderable]")
                .containsEntry("message", "probe [unrenderable] {[unrenderable]=v}");
    }

    @Test
    void credentialNamedEntriesAreChargedToTheBudgetToo() {
        // Verification E: a million credential-named keys, and an endless map, stop at the budget.
        Map<String, Object> keys = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 1_000_000; i++) {
            keys.put("k" + i + "Password", "v");
        }
        Map<String, Object> endless = new java.util.AbstractMap<>() {
            @Override
            public java.util.Set<Map.Entry<String, Object>> entrySet() {
                return new java.util.AbstractSet<>() {
                    @Override
                    public java.util.Iterator<Map.Entry<String, Object>> iterator() {
                        return java.util.stream.Stream.iterate(0, i -> i + 1)
                                .map(i -> (Map.Entry<String, Object>) Map.entry("p" + i + "Password", (Object) "v"))
                                .iterator();
                    }

                    @Override
                    public int size() {
                        return Integer.MAX_VALUE;
                    }
                };
            }
        };
        String line = encode(event("stored").pair("keys", keys).pair("endless", endless));
        assertThat((Map<?, ?>) parse(line).get("keys")).hasSizeLessThanOrEqualTo(10_001);
        assertThat((Map<?, ?>) parse(line).get("endless")).hasSizeLessThanOrEqualTo(10_001);
    }

    @Test
    void theBudgetStopsTheWalk() {
        // E: a million elements write at most the budget, and an endless Iterable as a message argument ends.
        List<Integer> huge = java.util.stream.IntStream.range(0, 1_000_000).boxed().toList();
        Iterable<Integer> endless = () -> java.util.stream.Stream.iterate(0, i -> i + 1).iterator();
        String line = encode(event("endless {}", endless).pair("huge", huge));
        @SuppressWarnings("unchecked")
        List<Object> written = (List<Object>) parse(line).get("huge");
        assertThat(written).hasSizeLessThanOrEqualTo(10_001).last().isEqualTo("****");
    }

    /** A bean whose getter returns itself (F). */
    public static final class Node {
        public Node getSelf() {
            return this;
        }

        public String getName() {
            return "n";
        }
    }

    @Test
    void aBeanHoldingItselfEndsTheDescentMasked() {
        assertThat(parse(encode(event("stored").pair("node", new Node()))).get("node"))
                .isEqualTo(Map.of("name", "n", "self", "****"));
    }

    @Test
    void numberSubclassesAreReadFromTheirText() {
        // G: a BigDecimal subclass whose value is zero and whose text is a card number.
        BigDecimal disguised = new BigDecimal(0) {
            @Override
            public String toString() {
                return "4111111111111111";
            }
        };
        String line = encode(event("stored").pair("amount", disguised));
        assertThat(line).doesNotContain("4111111111111111");
        assertThat(parse(line)).containsEntry("amount", "****");
    }

    @Test
    void extraPatternsReachBooleans() {
        // H.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "^true$"));
        String line = encode(event("stored").pair("sensitiveFlag", true).pair("nested", Map.of("flag", true, "off", false)));
        assertThat(parse(line)).containsEntry("sensitiveFlag", "****")
                .containsEntry("nested", Map.of("flag", "****", "off", false));
    }

    @Test
    void keyValueCorrelationIdsAreKept() {
        // I: a 16-digit span ID that is also a Luhn-valid number.
        String line = encode(event("stored").pair("spanId", "4111111111111111")
                .pair("traceId", "4bf92f3577b34da6a3ce929d0e0e4736").pair("other", "4111111111111111"));
        assertThat(parse(line)).containsEntry("spanId", "4111111111111111")
                .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736").containsEntry("other", "****");
    }

    /** A service customizer whose nested processor turns harmless text into an e-mail address (K). */
    public static class Deobfuscating implements org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer<Object> {
        @Override
        public void customize(org.springframework.boot.json.JsonWriter.Members<Object> members) {
            members.add("contact").usingMembers(contact -> {
                contact.add("email", "alice(at)example.com");
                contact.applyingValueProcessor(org.springframework.boot.json.JsonWriter.ValueProcessor.of(String.class,
                        text -> text.replace("(at)", "@")));
            });
        }
    }

    @Test
    void aValueANestedProcessorProducesIsMaskedToo() {
        encoder.stop();
        start(new MockEnvironment().withProperty("logging.structured.json.customizer", Deobfuscating.class.getName()));
        String line = encode(event("stored"));
        assertThat(line).doesNotContain("alice@example.com");
        // A service customizer's nested object reaches the processor as deferred JSON, as raw JSON a service logs
        // does; outside Boot's own format objects it is masked whole (fails closed; the module page says to add
        // flat members).
        assertThat(parse(line)).containsEntry("contact", "****");
    }

    @Test
    void ecsObjectNamesAreExemptOnlyUnderEcs() {
        // Verification 2, C: under Boot's logstash format a raw writer named "log" is masked like any other.
        encoder.stop();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.structured.format.console", "logstash");
        start(environment);
        String line = encode(event("stored").pair("log",
                org.springframework.boot.json.JsonWriter.standard().write(Map.of("password", "hunter2"))));
        assertThat(line).doesNotContain("hunter2");
    }

    /** A bean whose getters all throw (verification 2, E). */
    public static final class Broken {
        public String getA() {
            throw new IllegalStateException();
        }

        public String getB() {
            throw new IllegalStateException();
        }
    }

    @Test
    void failedGettersAndFilteringIteratorsAreChargedToTheBudget() {
        List<Object> broken = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            broken.add(new Broken());
        }
        // An iterator that never yields: hasNext() would search forever once called.
        int[] calls = {0};
        Iterable<Object> silent = () -> new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                return ++calls[0] < 50_000_000;
            }

            @Override
            public Object next() {
                return "x";
            }
        };
        String line = encode(event("stored").pair("broken", broken).pair("silent", silent));
        assertThat(line.length()).isLessThan(2_000_000);
        assertThat(calls[0]).isLessThan(20_000);
    }

    @Test
    void aLoggingLibraryObjectIsMaskedText() {
        // Verification 2, new 2: a Logback logger named with an e-mail address.
        Object logger = context.getLogger("alice@example.com");
        String line = encode(event("stored").pair("auditLogger", logger));
        assertThat(line).doesNotContain("alice@example.com");
    }

    @Test
    void anOrdinaryBootJsonObjectIsData() {
        // Verification 3, finding 1: only Boot's writers pass untouched, not every object from its JSON package.
        encoder.stop();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.structured.format.console", "logstash");
        start(environment);
        String line = encode(event("stored").pair("path",
                org.springframework.boot.json.JsonWriter.MemberPath.of("4111111111111111")));
        assertThat(line).doesNotContain("4111111111111111");
    }

    // --- Codex phase 3 round 2 ----------------------------------------------------------------------------

    @Test
    void maskingIsIdempotentSoNestedValuesDoNotGrow() {
        // Finding 1: a nested value walked more than once is masked once.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "te.t"));
        String line = encode(event("x").pair("nested", Map.of("a", Map.of("b", Map.of("c", "text")))));
        assertThat(parse(line).get("nested")).isEqualTo(Map.of("a", Map.of("b", Map.of("c", "****"))));
        assertThat(line.length()).isLessThan(400);
    }

    @Test
    void anOrdinaryBootLoggingObjectIsData() {
        // Finding 2: Boot's LogFile is data, not a writer.
        encoder.stop();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.structured.format.console", "logstash");
        start(environment);
        Object logFile = org.springframework.boot.logging.LogFile.get(new MockEnvironment()
                .withProperty("logging.file.name", "alice@example.com"));
        assertThat(encode(event("stored").pair("file", logFile))).doesNotContain("alice@example.com");
    }

    @Test
    void aContextPrefixKeepsCredentialNames() {
        // Finding 3: logging.structured.json.context.prefix, in the platform's format and in Boot's.
        for (String format : List.of("platform", "logstash")) {
            encoder.stop();
            MockEnvironment environment = new MockEnvironment().withProperty("logging.structured.json.context.prefix",
                    "ctx");
            if (!"platform".equals(format)) {
                environment.setProperty("logging.structured.format.console", format);
            }
            start(environment);
            String line = encode(event("stored").mdc("pwd", "correct horse battery staple"));
            assertThat(line).as(format).doesNotContain("horse");
        }
    }

    @Test
    void theDefaultPatternsReachNumbers() {
        // Finding 4: an Emirates ID logged as a number; NaN under an extra pattern, in Boot's format.
        String line = encode(event("stored").pair("nationalId", 784199012345671L));
        assertThat(parse(line)).containsEntry("nationalId", "****");
        encoder.stop();
        MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]",
                "^NaN$");
        environment.setProperty("logging.structured.format.console", "logstash");
        start(environment);
        assertThat(encode(event("stored").pair("value", Double.NaN))).doesNotContain("NaN");
    }

    @Test
    void contextNameCollisionsNeverLoseTheLine() {
        // Finding 6: the same name in the MDC and as a key-value pair, and an MDC entry named like a layout member.
        String line = encode(event("collide").mdc("tenant", "mdc").mdc("service", "spoofed").pair("tenant", "pair"));
        assertThat(parse(line)).containsEntry("tenant", "pair").containsEntry("service", "application")
                .containsEntry("message", "collide");
    }

    // --- Codex phase 3 round 2 reassessment --------------------------------------------------------------

    @Test
    void extraPatternsKeepWholeTextSemanticsAndAreAppliedOnce() {
        // New 1, 2: literal mask text cannot split a match; a pattern that matches empty text or the mask itself (which
        // could grow masks without end) is rejected at startup.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "CASE-\\S+"));
        String line = encode(event("x").pair("ref", "CASE-****123456").pair("nested", Map.of("a", List.of("b"))));
        assertThat(line).doesNotContain("123456");
        for (String growing : List.of("^", "\\S", "\\*+", "x*")) {
            org.assertj.core.api.Assertions.assertThatIllegalStateException().as(growing).isThrownBy(() ->
                    ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformLogMasking.masker(
                            new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", growing)))
                    .withMessageContaining("matches empty text or the mask");
        }
    }

    @Test
    void theMessageIsMaskedOnceMoreAtMostAndANullTemplateIsAllowed() {
        // Verification 1, new 1 and 5.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "^x$"));
        assertThat(parse(encode(event("{}", "x"))).get("message")).isEqualTo("****");
        LoggingEvent nullTemplate = new LoggingEvent(Logger.class.getName(), context.getLogger(LOGGER), Level.INFO,
                null, null, new Object[] {"x"});
        nullTemplate.setMDCPropertyMap(Map.of());
        assertThat(new String(encoder.encode(nullTemplate), StandardCharsets.UTF_8)).contains("\"level\":\"INFO\"");
    }

    @Test
    void aNumberIsCheckedAsWrittenAndAsDigits() {
        // Verification 1, new 4: a pattern on the written text of 123.0.
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "^123\\.0$"));
        assertThat(parse(encode(event("stored").pair("value", 123.0d)))).containsEntry("value", "****");
        encoder.stop();
        start(new MockEnvironment().withProperty("dc.platform.logging.redaction.patterns[0]", "E14$"));
        assertThat(parse(encode(event("stored").pair("nationalId", 784199012345671d)))).containsEntry("nationalId", "****");
    }

    @Test
    void mixedFormatsExemptNoFormatObjects() {
        // Verification 1, new 3: console logstash, file GELF: a raw writer named timestamp is masked.
        encoder.stop();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.structured.format.console", "logstash");
        environment.setProperty("logging.structured.format.file", "gelf");
        start(environment);
        String line = encode(event("stored").pair("timestamp",
                org.springframework.boot.json.JsonWriter.standard().write(Map.of("password", "hunter2"))));
        assertThat(line).doesNotContain("hunter2");
    }

    @Test
    void twoContextKeysWrittenUnderOneNameKeepTheLater() {
        // Verification 1, remaining part of round 2 finding 6: foo and _foo under prefix ctx are both ctx_foo.
        encoder.stop();
        start(new MockEnvironment().withProperty("logging.structured.json.context.prefix", "ctx"));
        assertThat(parse(encode(event("stored").mdc("foo", "one").pair("_foo", "two")))).containsEntry("ctx_foo", "two");
        assertThat(parse(encode(event("stored").pair("foo", "one").pair("_foo", "two").pair("foo", "three"))))
                .containsEntry("ctx_foo", "three");
    }

    @Test
    void gelfKeepsItsNumericTimestamp() {
        // New 3.
        encoder.stop();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.structured.format.console", "gelf");
        start(environment);
        Map<String, Object> line = parse(encode(event("gelf probe")));
        assertThat(line.get("timestamp")).isInstanceOf(Number.class);
    }

    @Test
    void contextPrefixesFollowBootsJoinRule() {
        // 3: a prefix ending in "_", and GELF's leading underscore; 6, new 4: collisions on the written name only.
        for (String format : List.of("platform", "logstash", "gelf")) {
            encoder.stop();
            MockEnvironment environment = new MockEnvironment().withProperty("logging.structured.json.context.prefix",
                    "ctx_");
            if (!"platform".equals(format)) {
                environment.setProperty("logging.structured.format.console", format);
            }
            start(environment);
            assertThat(encode(event("stored").mdc("pwd", "correct horse battery staple"))).as(format)
                    .doesNotContain("horse");
        }
        encoder.stop();
        start(new MockEnvironment().withProperty("logging.structured.json.context.prefix", "ctx"));
        assertThat(parse(encode(event("kept").mdc("service", "mine").pair("message", "also mine"))))
                .containsEntry("ctx_service", "mine").containsEntry("ctx_message", "also mine")
                .containsEntry("service", "application").containsEntry("message", "kept");
        encoder.stop();
        start(new MockEnvironment().withProperty("logging.structured.json.context.prefix", "stack"));
        String line = encode(event("failed").mdc("trace", "t").withThrowable());
        assertThat(parse(line)).containsKey("stack_trace").doesNotContainEntry("stack_trace", "t");
    }

    @Test
    void anIntegralDoubleIsCheckedAsItsDigits() {
        // 4: 784199012345671d prints as 7.84199012345671E14.
        assertThat(encode(event("stored").pair("nationalId", 784199012345671d))).doesNotContain("7.84199012345671E14");
    }

    // --- Phase 3 Gate H: resources and proxies are never walked ------------------------------------------

    /** A resource whose getters would do I/O; counts every call. */
    public static final class Pooled implements AutoCloseable {

        static final java.util.concurrent.atomic.AtomicInteger READS = new java.util.concurrent.atomic.AtomicInteger();

        public String getMetaData() {
            READS.incrementAndGet();
            return "queried";
        }

        public List<String> getCatalogs() {
            READS.incrementAndGet();
            return List.of("queried");
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "conn0: url=jdbc:h2:mem:x user=sa password=hunter2";
        }
    }

    /** A getter-bearing contract for a JDK dynamic proxy. */
    public interface Lazy {

        String getName();
    }

    /** Hibernate's proxy contract, reached through another interface (Codex Gate H); real proxies: JpaValueMaskingTest. */
    public interface ExtendedProxy extends org.hibernate.proxy.HibernateProxy {
    }

    /** A bean whose getter opens a resource, as Spring's UrlResource.getInputStream connects. */
    public static final class Opener {

        static final java.util.concurrent.atomic.AtomicInteger OPENED = new java.util.concurrent.atomic.AtomicInteger();

        public java.io.InputStream getInputStream() {
            OPENED.incrementAndGet();
            return java.io.InputStream.nullInputStream();
        }

        public String getDescription() {
            return "URL [http://example.invalid/x]";
        }
    }

    @Test
    void aGetterReturningAResourceIsNeverCalled() {
        Opener.OPENED.set(0);
        assertThat(parse(encode(event("stored").pair("resource", new Opener())))).containsEntry("resource",
                Map.of("description", "URL [http://example.invalid/x]", "inputStream", "****"));
        assertThat(Opener.OPENED).hasValue(0);
        // Codex Gate H verification 2: a bean whose only getter returns a resource stays a bean, not its toString().
        assertThat(parse(encode(event("stored").pair("holder", new SecretHolder())))).containsEntry("holder",
                Map.of("secret", "****"));
        assertThat(Opener.OPENED).hasValue(0);
    }

    /** Only a resource-valued getter, and an unlabelled credential in toString(). */
    public static final class SecretHolder {

        public java.io.InputStream getSecret() {
            Opener.OPENED.incrementAndGet();
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public String toString() {
            return "hunter2";
        }
    }

    @Test
    void aResourceOrAProxyIsItsOwnMaskedTextAndNoGetterRuns() {
        Pooled.READS.set(0);
        java.util.concurrent.atomic.AtomicInteger proxied = new java.util.concurrent.atomic.AtomicInteger();
        Lazy lazy = (Lazy) java.lang.reflect.Proxy.newProxyInstance(Lazy.class.getClassLoader(), new Class<?>[] {Lazy.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "Lazy(token=hunter2)";
                    }
                    proxied.incrementAndGet();
                    return "loaded";
                });
        java.util.concurrent.atomic.AtomicInteger extended = new java.util.concurrent.atomic.AtomicInteger();
        class Entity implements ExtendedProxy {
            @Override
            public Object writeReplace() {
                return this;
            }

            @Override
            public org.hibernate.proxy.LazyInitializer getHibernateLazyInitializer() {
                extended.incrementAndGet();
                return null;
            }

            public String getName() {
                extended.incrementAndGet();
                return "loaded";
            }
        }
        encode(event("stored").pair("entity", new Entity()));
        assertThat(extended).hasValue(0);
        String line = encode(event("Added connection {} {}", new Pooled(), lazy).pair("pooled", new Pooled())
                .pair("lazy", lazy).pair("nested", Map.of("conn", new Pooled())));
        assertThat(Pooled.READS).hasValue(0);
        assertThat(proxied).hasValue(0);
        assertThat(line).doesNotContain("queried").doesNotContain("loaded").doesNotContain("hunter2")
                .contains("conn0: url=jdbc:h2:mem:x");
        assertThat(parse(line).get("pooled")).isInstanceOf(String.class);
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

        private Throwable throwable;

        Event withThrowable() {
            throwable = new IllegalStateException("boom");
            return this;
        }

        LoggingEvent build(LoggerContext context) {
            Logger logger = context.getLogger(LOGGER);
            LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.INFO, message, throwable,
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
