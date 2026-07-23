package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/** Builds the {@code rateLimitedAdvisor} bean; keeps the pointcut/interceptor pair module-private. */
public final class RateLimitedAdvisorFactory {

    private RateLimitedAdvisorFactory() {
    }

    /**
     * @param rateLimiter the limiter the interceptor consumes permits from
     * @param metrics the decision metrics sink
     * @return an advisor applying rate limiting around {@code @RateLimited} methods
     */
    public static Advisor create(RateLimiter rateLimiter, RateLimitMetrics metrics) {
        return new DefaultPointcutAdvisor(
                new RateLimitedPointcut(), new RateLimitedInterceptor(rateLimiter, metrics));
    }
}
