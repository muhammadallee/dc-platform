package ae.gov.dubaicustoms.platform.audit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.audit.Audited;
import java.lang.reflect.Method;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Matches methods annotated with {@link Audited}. */
final class AuditedPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(method, Audited.class);
    }
}
