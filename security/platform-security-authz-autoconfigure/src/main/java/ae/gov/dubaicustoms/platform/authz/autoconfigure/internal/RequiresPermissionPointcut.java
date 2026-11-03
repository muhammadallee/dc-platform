package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import java.lang.reflect.Method;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Matches methods (or types) annotated with {@link RequiresPermission}. */
final class RequiresPermissionPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        // A JDK proxy passes the interface method; the implementing method may carry the annotation.
        Method specific = targetClass == null ? method : AopUtils.getMostSpecificMethod(method, targetClass);
        return AnnotatedElementUtils.hasAnnotation(specific, RequiresPermission.class)
                || AnnotatedElementUtils.hasAnnotation(method, RequiresPermission.class)
                || targetClass != null && AnnotatedElementUtils.hasAnnotation(targetClass, RequiresPermission.class);
    }
}
