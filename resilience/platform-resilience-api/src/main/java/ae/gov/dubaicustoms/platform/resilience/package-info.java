/**
 * The resilience capability contract: {@link ae.gov.dubaicustoms.platform.resilience.ResilienceDefaults}
 * publishes the platform's default retry, circuit-breaker and time-limiter tuning, and
 * {@link ae.gov.dubaicustoms.platform.resilience.RetryableOperation} offers a programmatic retry
 * helper for call sites that cannot use Resilience4j's declarative annotations.
 *
 * <p>Declarative resilience uses Resilience4j's own annotations ({@code @Retry},
 * {@code @CircuitBreaker}, {@code @TimeLimiter}); this module intentionally ships no wrapper
 * annotations over them. {@code platform-resilience-autoconfigure} contributes the default instance
 * configurations and the {@code RetryableOperation} bean.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.resilience;
