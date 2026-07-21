package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.resilience.ResilienceDefaults;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

/**
 * Behavior test: a circuit breaker built from the platform defaults (50% failure rate over a 10-call
 * count window) opens once the window fills with failures — the same config the environment
 * post-processor contributes to resilience4j-spring-boot4.
 */
class CircuitBreakerDefaultsTest {

    @Test
    void breakerOpensWhenTheDefaultThresholdIsExceeded() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(ResilienceDefaults.CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE)
                .failureRateThreshold(ResilienceDefaults.CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("test", config);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        for (int i = 0; i < ResilienceDefaults.CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE; i++) {
            try {
                breaker.executeCallable(() -> {
                    throw new IllegalStateException("failure");
                });
            } catch (Exception ignored) {
                // recorded as a failure in the breaker's window
            }
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
