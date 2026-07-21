package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the resilience capability. Bound from {@code dc.platform.resilience.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformResilienceAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). The actual retry/circuit-breaker/time-limiter
 * tuning lives under Resilience4j's own {@code resilience4j.*} keys, contributed as lowest-precedence
 * environment defaults by {@code PlatformResilienceEnvironmentPostProcessor}, so it is overridden with
 * Resilience4j's native keys rather than duplicated here.
 *
 * @param enabled master kill switch for the whole capability
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.resilience")
public record ResilienceProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled) {
}
