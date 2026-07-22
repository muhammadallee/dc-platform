package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.idempotency.Idempotent;
import java.lang.reflect.Method;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Matches methods annotated with {@link Idempotent}. */
final class IdempotentPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(method, Idempotent.class);
    }
}
