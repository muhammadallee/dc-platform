package ae.gov.dubaicustoms.platform.tck.flags;

import ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import java.util.Map;

/** Certifies the in-memory reference flag provider against {@link FlagProviderTck}, docker-free. */
class InMemoryFlagProviderTckTest extends FlagProviderTck {

    @Override
    protected FlagProvider providerWith(Map<String, Object> seed) {
        return new InMemoryFlagProvider(seed);
    }
}
