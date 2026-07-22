package ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.scheduling.LockedSchedule;
import java.lang.reflect.Method;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Matches methods annotated with {@link LockedSchedule}. */
final class LockedSchedulePointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(method, LockedSchedule.class);
    }
}
