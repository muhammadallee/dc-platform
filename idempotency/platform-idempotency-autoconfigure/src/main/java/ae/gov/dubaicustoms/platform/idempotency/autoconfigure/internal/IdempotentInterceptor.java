package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.errors.ConflictException;
import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.Idempotent;
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
 * Enforces {@link Idempotent}: derives the key from the annotation's SpEL expression evaluated over
 * the method arguments, records it in the {@link IdempotencyStore}, and rejects a duplicate with the
 * errors capability's {@link ConflictException} (HTTP 409). Exists so a service gets exactly-once
 * effect on a method without writing the store plumbing.
 */
final class IdempotentInterceptor implements MethodInterceptor {

    /** Client-safe: never echoes the key (it may carry request data). */
    private static final ErrorCode DUPLICATE = new ErrorCode("DC-IDEM-0409");

    private final IdempotencyStore store;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    IdempotentInterceptor(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Idempotent annotation =
                AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), Idempotent.class);
        String key = evaluateKey(annotation, invocation);
        Duration ttl = DurationStyle.detectAndParse(annotation.ttl());
        if (store.putIfAbsent(key, ttl)) {
            return invocation.proceed();
        }
        throw new ConflictException(DUPLICATE, "duplicate request rejected by idempotency");
    }

    private String evaluateKey(Idempotent annotation, MethodInvocation invocation) {
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                invocation.getThis(), invocation.getMethod(), invocation.getArguments(), parameterNames);
        Expression expression = parser.parseExpression(annotation.keyExpression());
        String key = expression.getValue(context, String.class);
        if (key == null) {
            throw new IllegalStateException(
                    "@Idempotent keyExpression '" + annotation.keyExpression() + "' evaluated to null");
        }
        return key;
    }
}
