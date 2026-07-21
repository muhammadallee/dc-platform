package ae.gov.dubaicustoms.platform.cache.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DefaultCacheKeyConventionTest {

    private final DefaultCacheKeyConvention convention = new DefaultCacheKeyConvention("orders-service");

    @Test
    void prefixesApplicationNameAndCacheName() {
        assertThat(convention.key("orders")).isEqualTo("orders-service:orders");
    }

    @Test
    void appendsPartsWithSeparator() {
        assertThat(convention.key("orders", 42, "summary")).isEqualTo("orders-service:orders:42:summary");
    }

    @Test
    void rendersNullPartsSafely() {
        assertThat(convention.key("orders", (Object) null)).isEqualTo("orders-service:orders:null");
    }

    @Test
    void rejectsNullCacheName() {
        assertThatNullPointerException().isThrownBy(() -> convention.key(null));
    }
}
