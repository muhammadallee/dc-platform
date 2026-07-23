package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimitExceededException;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimited;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import java.time.Duration;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Enforces {@link RateLimited}: derives the bucket key from the annotation's {@code name} plus the
 * SpEL {@code keyExpression} over the arguments, consumes a permit through the {@link RateLimiter},
 * records the decision, and rejects an over-limit call with {@link RateLimitExceededException} (mapped
 * to HTTP 429 by the platform's MVC advice). Exists so a method is rate-limited without any plumbing.
 */
final class RateLimitedInterceptor implements MethodInterceptor {

    private final RateLimiter rateLimiter;
    private final RateLimitMetrics metrics;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    RateLimitedInterceptor(RateLimiter rateLimiter, RateLimitMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        RateLimited annotation =
                AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), RateLimited.class);
        String key = buildKey(annotation, invocation);
        Duration window = DurationStyle.detectAndParse(annotation.window());
        Decision decision = rateLimiter.tryAcquire(key, annotation.permits(), window);
        metrics.record(annotation.name(), decision.allowed());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(annotation.name(), decision.retryAfter());
        }
        return invocation.proceed();
    }

    private String buildKey(RateLimited annotation, MethodInvocation invocation) {
        if (annotation.keyExpression().isEmpty()) {
            return annotation.name();
        }
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                invocation.getThis(), invocation.getMethod(), invocation.getArguments(), parameterNames);
        Expression expression = parser.parseExpression(annotation.keyExpression());
        Object value = expression.getValue(context);
        return annotation.name() + ":" + (value == null ? "null" : value);
    }
}
