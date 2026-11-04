package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import com.fasterxml.jackson.core.JsonStreamContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Pattern, key and sanitizer masking of {@link PlatformValueMasker} (D102), including the phase-02
 * round-1 findings (quoted values, cookie headers, short credentials, numeric credentials, PAN
 * boundaries next to hex IDs, linear e-mail scanning) and false positives.
 */
class PlatformValueMaskerTest {

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";
    private static final String MASK = PlatformValueMasker.MASK;

    private final PlatformValueMasker masker = new PlatformValueMasker(List.of());

    @AfterEach
    void clearSanitizers() {
        publish();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "token " + JWT + " seen",
            "Authorization: Bearer abcdefgh12345678",
            "header Basic dXNlcjpwYXNzd29yZA==",
            "login password=hunter2 ok",
            "{\"client_secret\":\"s3cr3t-value\"}",
            "api-key: k-123456",
            "id 784-1990-1234567-1 checked",
            "id 784199012345671 checked",
            "card 4111111111111111 charged",
            "card 4111 1111 1111 1111 charged",
            "card 5500-0000-0000-0004 charged",
            "mail fatima.ali+orders@customs.gov.ae sent",
            "call +971 50 123 4567 now",
            "call +971501234567 now",
            "call 00971501234567 now",
            "call 050-123-4567 now",
            "call 0501234567 now",
            "intl +44 20 7946 0958 ok"})
    void masksCredentialsAndPii(String text) {
        String masked = masker.maskPatterns(text);
        assertThat(masked).contains(MASK)
                .doesNotContain(JWT).doesNotContain("abcdefgh12345678").doesNotContain("dXNlcjpwYXNzd29yZA==")
                .doesNotContain("hunter2").doesNotContain("s3cr3t-value").doesNotContain("k-123456")
                .doesNotContain("1234567").doesNotContain("4111").doesNotContain("0004")
                .doesNotContain("@customs").doesNotContain("4567").doesNotContain("0958");
    }

    // Round 1 F1: quoted values (with escapes) are masked whole; every pair of a cookie header goes.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "password=\"correct horse battery staple\" ok | password=\"****\" ok",
            "password='hunter2' ok                      | password='****' ok",
            "secret: \"a \\\"quoted\\\" b\" end           | secret: \"****\" end",
            "{\"password\":\"p w\",\"user\":\"ali\"}      | {\"password\":\"****\",\"user\":\"ali\"}",
            "Cookie: foo=abc; session=deadbeef          | Cookie: ****",
            "Set-Cookie: sid=abc123; Path=/; HttpOnly   | Set-Cookie: ****",
            "{\"cookie\":\"a=1; b=2\",\"n\":1}            | {\"cookie\":\"****\",\"n\":1}"})
    void masksQuotedValuesAndWholeCookieHeaders(String text, String expected) {
        assertThat(masker.maskPatterns(text)).isEqualTo(expected);
    }

    // Round 1 F2: no length floors; Basic is masked when it decodes to user:password.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Basic YTpi           | Basic ****",
            "Bearer abc           | Bearer ****",
            "Bearer token missing | Bearer **** missing",
            "jwt eyJhbGciOiJub25lIn0.e30. done | jwt **** done",
            "jwt ewogICJhbGciOiAibm9uZSIKfQ.e30.sig done | jwt **** done"})
    void masksShortCredentials(String text, String expected) {
        assertThat(masker.maskPatterns(text)).isEqualTo(expected);
    }

    @Test
    void basicWordInProseStaysWhenItIsNotACredential() {
        assertThat(masker.maskPatterns("Basic auth is disabled")).isEqualTo("Basic auth is disabled");
    }

    @Test
    void keepsTheKeyAndSchemeVisible() {
        assertThat(masker.maskPatterns("password=hunter2")).isEqualTo("password=****");
        assertThat(masker.maskPatterns("Bearer abcdefgh12345678")).isEqualTo("Bearer ****");
    }

    // Round 1 F4: hex identifiers with an embedded Luhn-valid run stay intact; a following number does
    // not hide a card; a trailing hyphen is a boundary.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "trace abcd4111111111111111abcdefabcdef | trace abcd4111111111111111abcdefabcdef",
            "card 4111111111111111 12               | card **** 12",
            "card 4111 1111 1111 1111 12            | card **** 12",
            "card 4111111111111111- end             | card ****- end",
            "amex 3782 822463 10005 ok               | amex **** ok"})
    void cardNumberBoundaries(String text, String expected) {
        assertThat(masker.maskPatterns(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "at 1793001600123 ms",                       // epoch millis
            "order 4000000001 created",                  // numeric order id (10 digits)
            "order 400000000000001 created",             // 15 digits, not Luhn-valid
            "trace 4bf92f3577b34da6a3ce929d0e0e4736",     // trace id
            "span 00f067aa0ba902b7",                      // span id
            "uuid 3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            "uuid 12345678-1234-5678-1234-567812345678",
            "at 2026-10-26T18:07:36.818Z took PT0.25S status 503",
            "object com.example.Order@6d06d69c",            // identity hash
            "trace 0012345678abcdef0123456789abcdef",       // trace id starting 00 + digits
            "uuid 3f2504e0-0012-3456-7890-0305e82c3301",
            "version 1.2.3 build 20261026",
            "ip 10.0.12.7 port 8080",
            "landline 04 123 4567 not matched",            // documented gap
            "eyes.of.the storm",                           // not a JWT
            "tokens: 5, passwords rotated"})
    void leavesOrdinaryValuesAlone(String text) {
        assertThat(masker.maskPatterns(text)).isEqualTo(text);
    }

    @Test
    void traceAndSpanIdFieldsKeepTheirHexIdsButNotOtherContent() {
        assertThat(masker.mask(field("traceId"), "abcd4111111111111111abcdefabcdef")).isNull();
        assertThat(masker.mask(field("spanId"), "4111111111111111")).isNull();
        assertThat(masker.mask(field("traceId"), "password=hunter2")).isEqualTo("password=****");
        // Round 2 R2-F6: no name exempts a consumer value — not the encoder's own field names, and not
        // traceId/spanId below the top level.
        assertThat(masker.mask(field("level"), "password=hunter2")).isEqualTo("password=****");
        assertThat(masker.mask(field("logger"), "card 4111111111111111")).isEqualTo("card ****");
        assertThat(masker.mask(nested("payload", "spanId"), "4111111111111111")).isEqualTo(MASK);
    }

    @Test
    void masksWholeValueOfCredentialNamedFields() {
        assertThat(masker.mask(field("Authorization"), "anything at all")).isEqualTo(MASK);
        assertThat(masker.mask(field("x-api-key"), "abc")).isEqualTo(MASK);
        assertThat(masker.mask(field("refresh_token"), "abc")).isEqualTo(MASK);
    }

    // Round 1 F3: credential names win whatever the value type, also for nested values.
    @Test
    void credentialNamesWinForEveryValueTypeAndNesting() {
        assertThat(masker.mask(field("password"), 123456)).isEqualTo(MASK);
        assertThat(masker.mask(field("token"), true)).isEqualTo(MASK);
        assertThat(masker.mask(nested("password", "inner"), "plain")).isEqualTo(MASK);
        assertThat(masker.mask(nested("details", "inner"), "plain")).isNull();
    }

    @Test
    void masksCardNumbersWrittenAsNumbers() {
        assertThat(masker.mask(field("card"), 4111111111111111L)).isEqualTo(MASK);
        assertThat(masker.mask(field("card"), new BigInteger("4111111111111111"))).isEqualTo(MASK);
        assertThat(masker.mask(field("card"), new BigDecimal("4111111111111111.00"))).isEqualTo(MASK);
        assertThat(masker.mask(field("card"), new BigDecimal("4111111111111111.5"))).isNull();
    }

    @Test
    void returnsNullWhenNothingChangesOrValueIsNotText() {
        assertThat(masker.mask(field("message"), "plain")).isNull();
        assertThat(masker.mask(field("count"), 42)).isNull();
        assertThat(masker.mask(field("at"), 1793001600123L)).isNull();
        assertThat(masker.mask(field("id"), 4111111111111112L)).isNull(); // not Luhn-valid
        assertThat(masker.mask(field(null), "plain")).isNull();
        assertThat(masker.mask(field("x"), null)).isNull();
    }

    // Round 1 F6: long inputs are scanned in linear time.
    @Test
    void longInputsWithoutMatchesAreScannedQuickly() {
        // Scaling, not a wall-clock budget (shared CI runners vary): 4x the input must cost far less than
        // the 16x a quadratic scan would. The absolute ceiling only catches a pathological case.
        // Fastest of five runs per size: a GC pause or a descheduled thread on a shared runner only ever
        // adds time. Linear ~4x, quadratic ~16x; the threshold sits between.
        scanTime(50_000); // warm-up (JIT)
        long small = Math.max(fastestScan(50_000), Duration.ofMillis(1).toNanos());
        long large = fastestScan(200_000);
        assertThat(large).as("4x input: %d ns vs %d ns", large, small).isLessThan(10 * small);
        assertThat(Duration.ofNanos(large)).isLessThan(Duration.ofSeconds(5));
    }

    private long fastestScan(int length) {
        long fastest = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            fastest = Math.min(fastest, scanTime(length));
        }
        return fastest;
    }

    private long scanTime(int length) {
        String noAt = "a".repeat(length);
        String trailingAt = "a.".repeat(length / 2) + "@";
        String digits = "1".repeat(length);
        long start = System.nanoTime();
        assertThat(masker.maskPatterns(noAt)).isEqualTo(noAt);
        assertThat(masker.maskPatterns(trailingAt)).isEqualTo(trailingAt);
        assertThat(masker.maskPatterns(digits)).isEqualTo(digits);
        return System.nanoTime() - start;
    }

    @Test
    void appliesSanitizersToNonPlatformFieldsOnly() {
        publish((key, value) -> "customerName".equals(key) ? "C***" : value);
        assertThat(masker.mask(field("customerName"), "Fatima")).isEqualTo("C***");
        assertThat(masker.mask(field("message"), "customerName")).isNull();
    }

    @Test
    void patternsStillRunAfterSanitizersAndAThrowingSanitizerIsIgnored() {
        publish((key, value) -> { throw new IllegalStateException("broken"); },
                (key, value) -> value + " Bearer abcdefgh12345678");
        assertThat(masker.mask(field("note"), "hello")).isEqualTo("hello Bearer ****");
    }

    @Test
    void extraPatternsAreMasked() {
        PlatformValueMasker custom = new PlatformValueMasker(PlatformValueMasker.compile("CASE-\\d{6}\n\n  "));
        assertThat(custom.maskPatterns("see CASE-123456")).isEqualTo("see ****");
        assertThat(PlatformValueMasker.compile(null)).isEmpty();
        assertThat(PlatformValueMasker.compile(" a+ \n")).extracting(Pattern::pattern).containsExactly("a+");
    }

    static void publish(LogSanitizer... sanitizers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < sanitizers.length; i++) {
            beans.registerSingleton("s" + i, sanitizers[i]);
        }
        new LogSanitizerRegistry(beans.getBeanProvider(LogSanitizer.class)).afterSingletonsInstantiated();
    }

    // A top-level field: its object's parent is the root context.
    private static JsonStreamContext field(String name) {
        return context(name, ROOT);
    }

    private static final JsonStreamContext ROOT = new JsonStreamContext(JsonStreamContext.TYPE_ROOT, 0) {
        @Override
        public JsonStreamContext getParent() {
            return null;
        }

        @Override
        public String getCurrentName() {
            return null;
        }
    };

    private static JsonStreamContext nested(String outer, String inner) {
        return context(inner, context(outer, ROOT));
    }

    private static JsonStreamContext context(String name, JsonStreamContext parent) {
        return new JsonStreamContext(JsonStreamContext.TYPE_OBJECT, 0) {
            @Override
            public JsonStreamContext getParent() {
                return parent;
            }

            @Override
            public String getCurrentName() {
                return name;
            }
        };
    }
}
