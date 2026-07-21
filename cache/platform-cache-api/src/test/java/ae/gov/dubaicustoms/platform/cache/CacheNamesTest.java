package ae.gov.dubaicustoms.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CacheNamesTest {

    @Test
    void joinsSegmentsWithSeparator() {
        assertThat(CacheNames.of("orders", "by-id")).isEqualTo("orders.by-id");
    }

    @Test
    void singleSegmentIsReturnedAsIs() {
        assertThat(CacheNames.of("orders")).isEqualTo("orders");
    }

    @Test
    void rejectsNoSegments() {
        assertThatThrownBy(CacheNames::of).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSegment() {
        assertThatThrownBy(() -> CacheNames.of("orders", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSegmentContainingSeparator() {
        assertThatThrownBy(() -> CacheNames.of("orders.by-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain");
    }
}
