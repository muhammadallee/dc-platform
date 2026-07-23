package ae.gov.dubaicustoms.platform.audit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Audited;
import ae.gov.dubaicustoms.platform.audit.Auditor;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import java.time.Clock;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Enforces {@link Audited}: runs the intercepted method, records an {@link AuditEvent} whether it
 * returns or throws (actor from the {@link ActorResolver}, correlation id from the request context,
 * resource from the annotation's SpEL over the invocation, {@link Outcome} from the return/throw),
 * then propagates the original result or exception unchanged. Exists so a method is audited without
 * the service writing any audit plumbing.
 */
final class AuditedInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditedInterceptor.class);

    private final Auditor auditor;
    private final ActorResolver actorResolver;
    private final Clock clock;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    AuditedInterceptor(Auditor auditor, ActorResolver actorResolver, Clock clock) {
        this.auditor = auditor;
        this.actorResolver = actorResolver;
        this.clock = clock;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Audited annotation =
                AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), Audited.class);
        Object result = null;
        Throwable thrown = null;
        try {
            result = invocation.proceed();
            return result;
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            Outcome outcome = thrown == null ? Outcome.SUCCESS : Outcome.FAILURE;
            recordQuietly(annotation, invocation, result, outcome);
        }
    }

    private void recordQuietly(Audited annotation, MethodInvocation invocation, Object result, Outcome outcome) {
        try {
            String correlationId = RequestContext.correlationId().map(id -> id.value()).orElse(null);
            AuditEvent event = new AuditEvent(
                    annotation.action(),
                    actorResolver.currentActor(),
                    resolveResource(annotation, invocation, result, outcome),
                    outcome,
                    clock.instant(),
                    correlationId,
                    Map.of());
            auditor.record(event);
        } catch (RuntimeException e) {
            // Auditing must never change the behavior of the method it wraps: swallow and log.
            log.warn("[DC-AUDIT-0502] failed to record audit event for action={}", annotation.action(), e);
        }
    }

    private String resolveResource(Audited annotation, MethodInvocation invocation, Object result, Outcome outcome) {
        if (annotation.resourceExpression().isEmpty()) {
            return null;
        }
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                invocation.getThis(), invocation.getMethod(), invocation.getArguments(), parameterNames);
        // #result is only meaningful on success; on failure it is left unbound (null).
        if (outcome == Outcome.SUCCESS) {
            context.setVariable("result", result);
        }
        Expression expression = parser.parseExpression(annotation.resourceExpression());
        Object value = expression.getValue(context);
        return value == null ? null : value.toString();
    }
}
