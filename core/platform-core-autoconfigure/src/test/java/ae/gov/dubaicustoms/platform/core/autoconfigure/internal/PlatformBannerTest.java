package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformBannerTest {

    @Test
    void formatsSortedByCapabilityName() {
        String line = PlatformBanner.format(List.of(
                new CapabilityDescriptor("messaging", "ACTIVE", "kafka"),
                new CapabilityDescriptor("core", "ACTIVE", "")));

        assertThat(line).isEqualTo("platform: core[ACTIVE], messaging[ACTIVE] (kafka)");
    }

    @Test
    void formatsEmptyDescriptorList() {
        assertThat(PlatformBanner.format(List.of())).isEqualTo("platform: ");
    }
}
