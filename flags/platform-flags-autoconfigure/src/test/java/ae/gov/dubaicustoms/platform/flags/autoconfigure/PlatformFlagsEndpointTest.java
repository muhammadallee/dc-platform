package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.PlatformFlagsEndpoint;
import ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The platformflags endpoint reads, writes and deletes flags on the in-memory provider. */
class PlatformFlagsEndpointTest {

    private final InMemoryFlagProvider provider = new InMemoryFlagProvider(Map.of("beta", "true"));
    private final PlatformFlagsEndpoint endpoint = new PlatformFlagsEndpoint(provider);

    @Test
    void readExposesCurrentFlags() {
        assertThat(endpoint.flags()).containsEntry("beta", "true");
    }

    @Test
    void writeSetsAFlagAtRuntime() {
        endpoint.set("gamma", "false");
        assertThat(endpoint.flags()).containsEntry("gamma", "false");
    }

    @Test
    void deleteRemovesAFlag() {
        assertThat(endpoint.remove("beta")).isTrue();
        assertThat(endpoint.remove("beta")).isFalse();
        assertThat(endpoint.flags()).doesNotContainKey("beta");
    }
}
