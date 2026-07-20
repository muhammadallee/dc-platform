package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the REST client capability. Bound from {@code dc.platform.restclient.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformRestClientAutoConfiguration}
 * via {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param propagateCorrelation propagate the current {@code RequestContext} correlation id as an
 *        outbound header
 * @param defaults connect/read timeouts applied when a client has no override
 * @param clients per-client timeout overrides, keyed by the name passed to
 *        {@link ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory#builder(String)}
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.restclient")
public record RestClientProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Propagate the current RequestContext correlation id as an outbound header. */
        @DefaultValue("true") boolean propagateCorrelation,
        /** Connect/read timeouts applied when a client has no override. */
        Defaults defaults,
        /** Per-client timeout overrides, keyed by client name. */
        Map<String, ClientOverride> clients) {

    /**
     * Normalizes nested defaults: Spring's constructor binding leaves unset object/map components
     * {@code null} rather than empty, so both are filled in here.
     */
    public RestClientProperties {
        defaults = defaults == null ? new Defaults(null, null) : defaults;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /**
     * Resolves the effective connect/read timeouts for a named client: its own override, falling
     * back to {@link #defaults()}.
     *
     * @param clientName the client name; never {@code null}
     * @return the effective timeouts; never {@code null}
     */
    public Defaults timeoutsFor(String clientName) {
        ClientOverride override = clients.get(clientName);
        if (override == null) {
            return defaults;
        }
        return new Defaults(
                override.connectTimeout() != null ? override.connectTimeout() : defaults.connectTimeout(),
                override.readTimeout() != null ? override.readTimeout() : defaults.readTimeout());
    }

    /**
     * Connect/read timeout pair.
     *
     * @param connectTimeout connection timeout
     * @param readTimeout read timeout
     * @since 0.2.0
     */
    public record Defaults(
            /** Connection timeout. */
            @DefaultValue("2s") Duration connectTimeout,
            /** Read timeout. */
            @DefaultValue("10s") Duration readTimeout) {

        /** Fills in defaults for any {@code null} component (used when constructed programmatically). */
        public Defaults {
            connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
            readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(10);
        }
    }

    /**
     * Per-client timeout override; either component may be {@code null} to fall back to
     * {@link #defaults()}.
     *
     * @param connectTimeout connection timeout override, or {@code null} to use the default
     * @param readTimeout read timeout override, or {@code null} to use the default
     * @since 0.2.0
     */
    public record ClientOverride(Duration connectTimeout, Duration readTimeout) {
    }
}
