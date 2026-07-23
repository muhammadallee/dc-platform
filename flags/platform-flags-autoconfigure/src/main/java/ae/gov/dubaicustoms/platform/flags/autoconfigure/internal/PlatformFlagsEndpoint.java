package ae.gov.dubaicustoms.platform.flags.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Actuator endpoint {@code platformflags} exposing the in-memory provider's flags and letting an
 * operator flip them at runtime — a write operation, so it is disabled for web exposure by default and
 * governed by the application's actuator security. Exists so local demos and incident response can
 * toggle a flag without a redeploy.
 */
@Endpoint(id = "platformflags")
public class PlatformFlagsEndpoint {

    private final InMemoryFlagProvider provider;

    public PlatformFlagsEndpoint(InMemoryFlagProvider provider) {
        this.provider = provider;
    }

    /** Current flags. */
    @ReadOperation
    public Map<String, Object> flags() {
        return provider.snapshot();
    }

    /** Sets (or replaces) a flag's value at runtime. */
    @WriteOperation
    public void set(@Selector String flag, String value) {
        provider.set(flag, value);
    }

    /** Removes a flag at runtime. */
    @DeleteOperation
    public boolean remove(@Selector String flag) {
        return provider.remove(flag);
    }
}
