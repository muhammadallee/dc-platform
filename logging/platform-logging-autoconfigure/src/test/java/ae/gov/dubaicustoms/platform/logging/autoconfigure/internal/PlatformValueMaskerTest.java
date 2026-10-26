package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import com.fasterxml.jackson.core.JsonStreamContext;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Pattern, key and sanitizer masking of {@link PlatformValueMasker} (D102), including false positives. */
class PlatformValueMaskerTest {

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";

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
            "card 5500-0000-0000-0004 charged"})
    void masksCredentialsAndPii(String text) {
        String masked = masker.maskPatterns(text);
        assertThat(masked).contains(PlatformValueMasker.MASK)
                .doesNotContain(JWT).doesNotContain("abcdefgh12345678").doesNotContain("dXNlcjpwYXNzd29yZA==")
                .doesNotContain("hunter2").doesNotContain("s3cr3t-value").doesNotContain("k-123456")
                .doesNotContain("1234567").doesNotContain("4111").doesNotContain("0004");
    }

    @Test
    void keepsTheKeyAndSchemeVisible() {
        assertThat(masker.maskPatterns("password=hunter2")).isEqualTo("password=****");
        assertThat(masker.maskPatterns("Bearer abcdefgh12345678")).isEqualTo("Bearer ****");
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
            "phone +971501234567",
            "tokens: 5, passwords rotated"})
    void leavesOrdinaryValuesAlone(String text) {
        assertThat(masker.maskPatterns(text)).isEqualTo(text);
    }

    @Test
    void masksWholeValueOfCredentialNamedFields() {
        assertThat(masker.mask(field("Authorization"), "anything at all")).isEqualTo(PlatformValueMasker.MASK);
        assertThat(masker.mask(field("x-api-key"), "abc")).isEqualTo(PlatformValueMasker.MASK);
        assertThat(masker.mask(field("refresh_token"), "abc")).isEqualTo(PlatformValueMasker.MASK);
    }

    @Test
    void returnsNullWhenNothingChangesOrValueIsNotText() {
        assertThat(masker.mask(field("message"), "plain")).isNull();
        assertThat(masker.mask(field("count"), 42)).isNull();
        assertThat(masker.mask(field("at"), 1793001600123L)).isNull();
        assertThat(masker.mask(field("id"), 4111111111111112L)).isNull(); // not Luhn-valid
    }

    @Test
    void masksCardNumbersWrittenAsNumbers() {
        assertThat(masker.mask(field("card"), 4111111111111111L)).isEqualTo(PlatformValueMasker.MASK);
        assertThat(masker.mask(field("card"), new java.math.BigInteger("4111111111111111")))
                .isEqualTo(PlatformValueMasker.MASK);
        assertThat(masker.mask(field(null), "plain")).isNull();
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
    }

    private static void publish(LogSanitizer... sanitizers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < sanitizers.length; i++) {
            beans.registerSingleton("s" + i, sanitizers[i]);
        }
        new LogSanitizerRegistry(beans.getBeanProvider(LogSanitizer.class)).afterSingletonsInstantiated();
    }

    private static JsonStreamContext field(String name) {
        return new JsonStreamContext(JsonStreamContext.TYPE_OBJECT, 0) {
            @Override
            public JsonStreamContext getParent() {
                return null;
            }

            @Override
            public String getCurrentName() {
                return name;
            }
        };
    }

    @Test
    void compiledPatternsAreTrimmed() {
        assertThat(PlatformValueMasker.compile(" a+ \n")).extracting(Pattern::pattern).containsExactly("a+");
    }
}
