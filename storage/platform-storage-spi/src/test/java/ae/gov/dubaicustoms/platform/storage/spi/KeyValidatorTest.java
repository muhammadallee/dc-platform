package ae.gov.dubaicustoms.platform.storage.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Hardening tests: the validator must reject every traversal and injection vector. */
class KeyValidatorTest {

    private final KeyValidator validator = new KeyValidator();

    @ParameterizedTest
    @ValueSource(strings = {
        "2026/07/inv-42.pdf",
        "a",
        "deep/nested/path/to/object.bin",
        "name.with.dots.txt",
        "..prefix/ok",
        "ok/suffix.."
    })
    void acceptsSafeKeys(String key) {
        assertThat(validator.requireValidKey(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../etc/passwd",
        "a/../../b",
        "..",
        ".",
        "a/./b",
        "a/../b",
        "/absolute/key",
        "a\\b",
        "trailing/..",
        "../"
    })
    void rejectsTraversalKeys(String key) {
        assertThatThrownBy(() -> validator.requireValidKey(key))
                .isInstanceOf(ObjectStoreException.class)
                .satisfies(ex -> assertThat(((ObjectStoreException) ex).code())
                        .isEqualTo(ObjectStoreException.INVALID_KEY));
    }

    @Test
    void rejectsBlankAndNulKeys() {
        assertThatThrownBy(() -> validator.requireValidKey("  ")).isInstanceOf(ObjectStoreException.class);
        assertThatThrownBy(() -> validator.requireValidKey("a\0b")).isInstanceOf(ObjectStoreException.class);
    }

    @Test
    void bucketMustNotBeBlankOrContainSeparators() {
        assertThat(validator.requireValidBucket("invoices")).isEqualTo("invoices");
        assertThatThrownBy(() -> validator.requireValidBucket(" ")).isInstanceOf(ObjectStoreException.class);
        assertThatThrownBy(() -> validator.requireValidBucket("a/b")).isInstanceOf(ObjectStoreException.class);
        assertThatThrownBy(() -> validator.requireValidBucket("a\\b")).isInstanceOf(ObjectStoreException.class);
    }
}
