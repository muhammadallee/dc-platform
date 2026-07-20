package ae.gov.dubaicustoms.platform.observability.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Actuator endpoint serving the active {@code CapabilityDescriptor} list as JSON — exists so
 * operators can ask a RUNNING service which platform capabilities are active without reading
 * startup logs.
 */
@Endpoint(id = "platform")
public class PlatformEndpoint {

    private final ObjectProvider<CapabilityDescriptor> capabilities;

    public PlatformEndpoint(ObjectProvider<CapabilityDescriptor> capabilities) {
        this.capabilities = capabilities;
    }

    @ReadOperation
    public List<CapabilityDescriptor> capabilities() {
        // Resolved per read (not cached at startup) and name-sorted for a stable JSON shape.
        return capabilities.orderedStream()
                .sorted(Comparator.comparing(CapabilityDescriptor::name))
                .toList();
    }
}
