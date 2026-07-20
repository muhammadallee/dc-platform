package ae.gov.dubaicustoms.platform.restclient;

import org.springframework.web.client.RestClient;

/**
 * Extension point: further customize a named client's {@link RestClient.Builder} after the
 * platform has applied its own conventions. Contribute beans of this type; the platform applies
 * them in {@code @Order}.
 *
 * <pre>{@code
 * @Bean
 * @Order(10)
 * PlatformRestClientCustomizer apiKeyCustomizer() {
 *     return (name, builder) -> {
 *         if ("orders".equals(name)) {
 *             builder.defaultHeader("X-Api-Key", apiKey);
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe and must not throw: a throwing customizer fails builder
 * creation for every client. New {@code default} methods may be added in minor releases.
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface PlatformRestClientCustomizer {

    /**
     * Adjusts the builder for the named client before it is handed to application code.
     *
     * @param name the client name passed to {@link PlatformRestClientFactory#builder(String)}; never {@code null}
     * @param builder the builder to customize; never {@code null}
     */
    void customize(String name, RestClient.Builder builder);
}
