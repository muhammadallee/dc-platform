package ae.gov.dubaicustoms.platform.core.report;

import ae.gov.dubaicustoms.platform.core.PlatformApi;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * One active platform capability as reported in the startup banner: contributed as a bean by each
 * capability's auto-configuration and collected by {@code PlatformBannerAutoConfiguration}.
 *
 * <pre>{@code
 * @Bean
 * CapabilityDescriptor messagingCapabilityDescriptor() {
 *     return new CapabilityDescriptor("messaging", "ACTIVE", "kafka");
 * }
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe. Components are never {@code null}.
 *
 * @param name the capability name, e.g. {@code core}
 * @param status short status, conventionally {@code ACTIVE}
 * @param detail one-line detail such as the selected provider; may be empty
 * @since 0.1.0
 */
@PlatformApi
@API(status = API.Status.STABLE, since = "0.1.0")
public record CapabilityDescriptor(String name, String status, String detail) {

    /**
     * Validates that all components are present.
     *
     * @throws NullPointerException if any component is null
     */
    public CapabilityDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
