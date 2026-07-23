package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.ratelimit.RateLimited;
import java.lang.reflect.Method;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Matches methods annotated with {@link RateLimited}. */
final class RateLimitedPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(method, RateLimited.class);
    }
}
