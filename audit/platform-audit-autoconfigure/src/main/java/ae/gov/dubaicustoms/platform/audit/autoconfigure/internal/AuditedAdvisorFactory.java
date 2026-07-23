package ae.gov.dubaicustoms.platform.audit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.audit.Auditor;
import java.time.Clock;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/** Builds the {@code auditedAdvisor} bean; keeps the pointcut/interceptor pair module-private. */
public final class AuditedAdvisorFactory {

    private AuditedAdvisorFactory() {
    }

    /**
     * @param auditor the auditor the interceptor records events through
     * @param actorResolver resolves the actor for each audited invocation
     * @param clock stamps the event time
     * @return an advisor applying auditing around {@code @Audited} methods
     */
    public static Advisor create(Auditor auditor, ActorResolver actorResolver, Clock clock) {
        return new DefaultPointcutAdvisor(
                new AuditedPointcut(), new AuditedInterceptor(auditor, actorResolver, clock));
    }
}
