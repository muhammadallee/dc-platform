package ae.gov.dubaicustoms.platform.idempotency.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the idempotency capability. Bound from {@code dc.platform.idempotency.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformIdempotencyAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). The store provider is chosen by the
 * classpath (Redis over JDBC); {@code @Idempotent} carries its own per-method TTL, so the properties
 * here only cover the capability switch and the optional HTTP filter.
 *
 * @param enabled master kill switch for the whole capability
 * @param http settings for the optional {@code Idempotency-Key} HTTP filter
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.idempotency")
public record IdempotencyProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Optional HTTP {@code Idempotency-Key} filter settings. */
        @DefaultValue Http http) {

    /**
     * Settings for the {@code Idempotency-Key} HTTP filter, which is disabled by default.
     *
     * @param enabled whether the filter rejects duplicate {@code Idempotency-Key} POSTs
     * @param headerName the request header carrying the client-supplied key
     * @param ttl how long a seen HTTP key is remembered
     */
    public record Http(
            /** Enable the {@code Idempotency-Key} POST filter (off by default). */
            @DefaultValue("false") boolean enabled,
            /** The request header carrying the idempotency key. */
            @DefaultValue("Idempotency-Key") String headerName,
            /** How long a seen HTTP key is remembered. */
            @DefaultValue("24h") Duration ttl) {
    }
}
