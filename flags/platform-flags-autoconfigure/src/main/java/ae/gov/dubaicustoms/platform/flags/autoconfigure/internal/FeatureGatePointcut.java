package ae.gov.dubaicustoms.platform.flags.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.flags.FeatureGate;
import java.lang.reflect.Method;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Matches methods annotated with {@link FeatureGate}. */
final class FeatureGatePointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(method, FeatureGate.class);
    }
}
