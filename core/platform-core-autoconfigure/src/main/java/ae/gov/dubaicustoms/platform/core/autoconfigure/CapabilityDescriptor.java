package ae.gov.dubaicustoms.platform.core.autoconfigure;

import java.util.Objects;

/**
 * One active platform capability as reported in the startup banner and {@code /actuator/platform}: contributed as
 * a bean by each capability's auto-configuration and collected by {@code PlatformBannerAutoConfiguration}. Platform
 * infrastructure: services do not declare one, except to replace a platform descriptor by its bean name.
 *
 * <pre>{@code
 * @Bean
 * CapabilityDescriptor messagingCapabilityDescriptor() {
 *     return new CapabilityDescriptor("messaging", "ACTIVE", "rabbit");
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
