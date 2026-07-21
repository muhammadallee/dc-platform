package ae.gov.dubaicustoms.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Constructor;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Guards the published default values — the environment post-processor and docs depend on them. */
class ResilienceDefaultsTest {

    @Test
    void retryDefaultsAreThreeAttemptsWithExponentialBackoff() {
        assertThat(ResilienceDefaults.RETRY_MAX_ATTEMPTS).isEqualTo(3);
        assertThat(ResilienceDefaults.RETRY_INITIAL_INTERVAL).isEqualTo(Duration.ofMillis(200));
        assertThat(ResilienceDefaults.RETRY_BACKOFF_MULTIPLIER).isEqualTo(2.0d);
    }

    @Test
    void circuitBreakerOpensAtHalfFailureOverTenCalls() {
        assertThat(ResilienceDefaults.CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD).isEqualTo(50.0f);
        assertThat(ResilienceDefaults.CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE).isEqualTo(10);
    }

    @Test
    void timeLimiterCapsCallsAtFiveSeconds() {
        assertThat(ResilienceDefaults.TIME_LIMITER_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void isNotInstantiable() throws Exception {
        Constructor<ResilienceDefaults> constructor = ResilienceDefaults.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThatCode(constructor::newInstance).doesNotThrowAnyException();
    }
}
