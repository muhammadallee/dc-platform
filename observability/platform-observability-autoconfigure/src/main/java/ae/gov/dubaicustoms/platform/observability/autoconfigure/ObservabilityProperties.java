package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the observability capability. Bound from
 * {@code dc.platform.observability.*}.
 *
 * <p>Immutable; validated at startup. Registered by the observability auto-configurations via
 * {@code @EnableConfigurationProperties} (never scanned). The nested toggles are also read
 * pre-context by {@code PlatformObservabilityEnvironmentPostProcessor}, which contributes
 * health-group, actuator-exposure, baggage, and OTLP-export defaults before the application
 * context exists.
 *
 * @param enabled master kill switch for the whole capability
 * @param commonTags stamp {@code service}/{@code env}/{@code platform.version} on every meter
 * @param otlp OTLP export; OFF by default — laptops have no collector (export config is
 *        documented in docs/modules/observability.md)
 * @param health contribute liveness/readiness health-group defaults
 * @param platformEndpoint serve the {@code platform} actuator endpoint
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.observability")
public record ObservabilityProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Common meter tags (service, env, platform.version). */
        @DefaultValue CommonTags commonTags,
        /** OTLP export posture; off by default because laptops have no collector. */
        @DefaultValue Otlp otlp,
        /** Health-group defaults (liveness/readiness). */
        @DefaultValue Health health,
        /** The platform actuator endpoint. */
        @DefaultValue PlatformEndpointProperties platformEndpoint) {

    /**
     * Common meter tags stamped on every registry.
     *
     * @param enabled switch for the common-tags customizer
     * @since 0.1.0
     */
    public record CommonTags(
            /** Stamp service/env/platform.version tags on every meter. */
            @DefaultValue("true") boolean enabled) {
    }

    /**
     * OTLP export posture.
     *
     * @param enabled when {@code false} (the default) metric and span OTLP export is switched
     *        off so local runs never try to reach a collector
     * @since 0.1.0
     */
    public record Otlp(
            /** Enable OTLP export (metrics + traces); off by default: no collector on laptops. */
            @DefaultValue("false") boolean enabled) {
    }

    /**
     * Health-group defaults.
     *
     * @param groups liveness/readiness group contribution
     * @since 0.1.0
     */
    public record Health(
            /** Liveness/readiness group defaults. */
            @DefaultValue Groups groups) {

        /**
         * Liveness/readiness group contribution.
         *
         * @param enabled contribute default liveness/readiness health groups
         * @since 0.1.0
         */
        public record Groups(
                /** Contribute default liveness/readiness health groups. */
                @DefaultValue("true") boolean enabled) {
        }
    }

    /**
     * The {@code platform} actuator endpoint.
     *
     * @param enabled serve the endpoint listing active platform capabilities
     * @since 0.1.0
     */
    public record PlatformEndpointProperties(
            /** Serve the platform actuator endpoint (capability report). */
            @DefaultValue("true") boolean enabled) {
    }
}
