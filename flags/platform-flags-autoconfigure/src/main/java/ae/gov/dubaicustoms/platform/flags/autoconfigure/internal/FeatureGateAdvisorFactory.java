package ae.gov.dubaicustoms.platform.flags.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.flags.FeatureFlags;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/** Builds the {@code featureGateAdvisor} bean; keeps the pointcut/interceptor pair module-private. */
public final class FeatureGateAdvisorFactory {

    private FeatureGateAdvisorFactory() {
    }

    /**
     * @param featureFlags the flags the interceptor consults to decide whether to run a gated method
     * @return an advisor applying feature gating around {@code @FeatureGate} methods
     */
    public static Advisor create(FeatureFlags featureFlags) {
        return new DefaultPointcutAdvisor(new FeatureGatePointcut(), new FeatureGateInterceptor(featureFlags));
    }
}
