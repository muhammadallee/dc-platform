package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.List;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/** Builds the {@code requiresPermissionAdvisor} bean; keeps the pointcut/interceptor pair module-private. */
public final class RequiresPermissionAdvisorFactory {

    private RequiresPermissionAdvisorFactory() {
    }

    public static Advisor create(CurrentUserAccessor accessor, List<PermissionEvaluatorProvider> providers) {
        return new DefaultPointcutAdvisor(new RequiresPermissionPointcut(), new RequiresPermissionInterceptor(accessor, providers));
    }
}
