package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/** Builds the {@code idempotentAdvisor} bean; keeps the pointcut/interceptor pair module-private. */
public final class IdempotentAdvisorFactory {

    private IdempotentAdvisorFactory() {
    }

    /**
     * @param store the idempotency store the interceptor records keys in
     * @return an advisor applying idempotency around {@code @Idempotent} methods
     */
    public static Advisor create(IdempotencyStore store) {
        return new DefaultPointcutAdvisor(new IdempotentPointcut(), new IdempotentInterceptor(store));
    }
}
