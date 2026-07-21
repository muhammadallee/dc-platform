package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import ae.gov.dubaicustoms.platform.resilience.ResilienceDefaults;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the platform's default Resilience4j tuning as {@code default}-instance configuration
 * under Resilience4j's own {@code resilience4j.*} keys: retry (3 attempts, exponential backoff),
 * circuit breaker (opens at 50% over a 10-call count window) and time limiter (5s), sourced from
 * {@link ResilienceDefaults} so there is a single source of truth.
 *
 * <p>Defaults land in a LOWEST-precedence property source named {@code platform-resilience-defaults},
 * exactly like the health-group and JPA defaults, so any {@code resilience4j.*} value a service sets
 * in {@code application.yml} wins, and the source name is visible in {@code /actuator/env} for
 * debuggability (property-conventions §7). Because these are Resilience4j's keys (not
 * {@code dc.platform.*}), they carry hand-written config metadata in
 * {@code additional-spring-configuration-metadata.json} — the processor cannot see them.
 * Registration is via {@code META-INF/spring.factories} — the only mechanism Boot offers for an
 * {@link EnvironmentPostProcessor}.
 *
 * <p>Skipped entirely when {@code dc.platform.resilience.enabled=false}. Stateless and thread-safe.
 *
 * @since 0.2.0
 */
public class PlatformResilienceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-resilience-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.resilience.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();

        // Retry: bounded attempts with exponential backoff so a transient failure is retried a few
        // times, quickly, without hammering a struggling dependency.
        defaults.put("resilience4j.retry.configs.default.max-attempts",
                String.valueOf(ResilienceDefaults.RETRY_MAX_ATTEMPTS));
        defaults.put("resilience4j.retry.configs.default.wait-duration",
                ResilienceDefaults.RETRY_INITIAL_INTERVAL.toMillis() + "ms");
        defaults.put("resilience4j.retry.configs.default.enable-exponential-backoff", "true");
        defaults.put("resilience4j.retry.configs.default.exponential-backoff-multiplier",
                String.valueOf(ResilienceDefaults.RETRY_BACKOFF_MULTIPLIER));

        // Circuit breaker: a count-based window so behavior is deterministic under low traffic.
        defaults.put("resilience4j.circuitbreaker.configs.default.sliding-window-type", "COUNT_BASED");
        defaults.put("resilience4j.circuitbreaker.configs.default.sliding-window-size",
                String.valueOf(ResilienceDefaults.CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE));
        defaults.put("resilience4j.circuitbreaker.configs.default.failure-rate-threshold",
                String.valueOf(ResilienceDefaults.CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD));

        // Time limiter: a ceiling on how long a guarded call may block.
        defaults.put("resilience4j.timelimiter.configs.default.timeout-duration",
                ResilienceDefaults.TIME_LIMITER_TIMEOUT.toSeconds() + "s");

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // Lowest precedence: application.yml/profile values must win over these defaults.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
