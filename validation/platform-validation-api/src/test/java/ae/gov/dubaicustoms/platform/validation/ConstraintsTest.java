package ae.gov.dubaicustoms.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Per-constraint happy/sad matrix against the real validator engine with a fixed clock. */
class ConstraintsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.byDefaultProvider().configure()
                .clockProvider(() -> Clock.fixed(NOW, ZoneOffset.UTC))
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    record TrimmedHolder(@NotBlankTrimmed String value) {
    }

    record UlidHolder(@Ulid String value) {
    }

    record SafeTextHolder(@SafeText String value) {
    }

    record FutureHolder(@FutureInstant Instant value) {
    }

    private static <T> Set<ConstraintViolation<T>> validate(T holder) {
        return validator.validate(holder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "  x  ", "many words"})
    void notBlankTrimmedAcceptsRealContent(String value) {
        assertThat(validate(new TrimmedHolder(value))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t \t"})
    void notBlankTrimmedRejectsBlankAndNull(String value) {
        assertThat(validate(new TrimmedHolder(value))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "01arz3ndektsv4rrffq69g5fav", // Crockford base32 is case-insensitive
        "7ZZZZZZZZZZZZZZZZZZZZZZZZZ"
    })
    void ulidAcceptsValidUlids(String value) {
        assertThat(validate(new UlidHolder(value))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "01ARZ3NDEKTSV4RRFFQ69G5FA",    // 25 chars
        "01ARZ3NDEKTSV4RRFFQ69G5FAVX",  // 27 chars
        "81ARZ3NDEKTSV4RRFFQ69G5FAV",   // first char > 7 overflows 128 bits
        "01ARZ3NDEKTSV4RRFFQ69G5FAI",   // I is not Crockford base32
        "01ARZ3NDEKTSV4RRFFQ69G5FAL",   // L is not Crockford base32
        "01ARZ3NDEKTSV4RRFFQ69G5FAO",   // O is not Crockford base32
        "01ARZ3NDEKTSV4RRFFQ69G5FAU"    // U is not Crockford base32
    })
    void ulidRejectsMalformedValues(String value) {
        assertThat(validate(new UlidHolder(value))).hasSize(1);
    }

    @Test
    void ulidTreatsNullAsValid() {
        assertThat(validate(new UlidHolder(null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain text", "أهلاً دبي", "punctuation !?%", ""})
    void safeTextAcceptsPrintableText(String value) {
        assertThat(validate(new SafeTextHolder(value))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tab\there", "line\nbreak", "escape[31m", "bell"})
    void safeTextRejectsControlCharacters(String value) {
        assertThat(validate(new SafeTextHolder(value))).hasSize(1);
    }

    @Test
    void safeTextTreatsNullAsValid() {
        assertThat(validate(new SafeTextHolder(null))).isEmpty();
    }

    @Test
    void futureInstantAcceptsInstantsAfterTheClock() {
        assertThat(validate(new FutureHolder(NOW.plusSeconds(1)))).isEmpty();
    }

    @Test
    void futureInstantRejectsPastAndPresent() {
        assertThat(validate(new FutureHolder(NOW))).hasSize(1);
        assertThat(validate(new FutureHolder(NOW.minusSeconds(1)))).hasSize(1);
    }

    @Test
    void futureInstantTreatsNullAsValid() {
        assertThat(validate(new FutureHolder(null))).isEmpty();
    }

    @Test
    void messagesResolveThroughTheContributorBundle() {
        ConstraintViolation<TrimmedHolder> violation = validate(new TrimmedHolder(" ")).iterator().next();
        // Proves the key resolved via ContributorValidationMessages.properties, not echoed raw.
        assertThat(violation.getMessage()).isEqualTo("must contain non-whitespace characters");
    }
}
