package ae.gov.dubaicustoms.platform.resilience;

import java.time.Duration;
import org.apiguardian.api.API;

/**
 * The platform's default resilience tuning, exposed as constants so both the auto-configuration
 * (which contributes them as Resilience4j environment defaults) and application code (which may read
 * them when building ad-hoc policies) share one source of truth.
 *
 * <p>The values back the {@code default} named instances of each Resilience4j registry: retry makes
 * three attempts with exponential backoff; the circuit breaker opens at a 50% failure rate measured
 * over a count-based window of ten calls; the time limiter caps a call at five seconds. User
 * configuration under {@code resilience4j.*} always overrides these — they are contributed at lowest
 * precedence.
 *
 * <p>Not instantiable; thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class ResilienceDefaults {

    /** Total attempts (the initial call plus retries) the {@code default} retry makes. */
    public static final int RETRY_MAX_ATTEMPTS = 3;

    /** Wait before the first retry; subsequent waits grow by {@link #RETRY_BACKOFF_MULTIPLIER}. */
    public static final Duration RETRY_INITIAL_INTERVAL = Duration.ofMillis(200);

    /** Exponential backoff multiplier applied between successive retries. */
    public static final double RETRY_BACKOFF_MULTIPLIER = 2.0d;

    /** Failure-rate percentage (0–100) at which the {@code default} circuit breaker opens. */
    public static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;

    /** Number of calls in the count-based sliding window the breaker measures over. */
    public static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;

    /** Maximum duration a call guarded by the {@code default} time limiter may run. */
    public static final Duration TIME_LIMITER_TIMEOUT = Duration.ofSeconds(5);

    private ResilienceDefaults() {
    }
}
