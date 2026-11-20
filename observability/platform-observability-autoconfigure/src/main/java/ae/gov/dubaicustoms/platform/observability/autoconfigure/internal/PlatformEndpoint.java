package ae.gov.dubaicustoms.platform.observability.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityOverrides;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;

/**
 * Actuator endpoint serving the active {@code CapabilityDescriptor} list as JSON — exists so
 * operators can ask a RUNNING service which platform capabilities are active without reading
 * startup logs.
 */
@Endpoint(id = "platform")
public class PlatformEndpoint {

    private final ObjectProvider<CapabilityDescriptor> capabilities;
    private final Environment environment;

    public PlatformEndpoint(ObjectProvider<CapabilityDescriptor> capabilities, Environment environment) {
        this.capabilities = capabilities;
        this.environment = environment;
    }

    @ReadOperation
    public List<CapabilityDescriptor> capabilities() {
        // Resolved per read (not cached at startup), with the service's dc.platform.core.capabilities.* overrides,
        // name-sorted for a stable JSON shape.
        return CapabilityOverrides.apply(capabilities.orderedStream().toList(), environment);
    }
}
