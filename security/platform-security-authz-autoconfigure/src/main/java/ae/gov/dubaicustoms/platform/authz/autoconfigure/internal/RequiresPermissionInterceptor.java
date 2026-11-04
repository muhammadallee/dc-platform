package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * Bridges {@link RequiresPermission} to the ordered {@link PermissionEvaluatorProvider} beans:
 * denies unauthenticated callers ({@link InsufficientAuthenticationException}, translated to 401
 * by the security capability's baseline chain) and callers no provider grants the permission to
 * ({@link AccessDeniedException}, translated to 403).
 */
final class RequiresPermissionInterceptor implements MethodInterceptor {

    private final CurrentUserAccessor currentUserAccessor;
    private final List<PermissionEvaluatorProvider> providers;

    RequiresPermissionInterceptor(CurrentUserAccessor currentUserAccessor, List<PermissionEvaluatorProvider> providers) {
        this.currentUserAccessor = Objects.requireNonNull(currentUserAccessor, "currentUserAccessor must not be null");
        this.providers = List.copyOf(providers);
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Class<?> target = invocation.getThis() == null ? null : AopUtils.getTargetClass(invocation.getThis());
        // Method before class, and the implementing method first: a JDK proxy passes the interface method, and a weaker class
        // permission must not stand in for the implementing method's own.
        Method specific = target == null ? invocation.getMethod() : AopUtils.getMostSpecificMethod(invocation.getMethod(), target);
        RequiresPermission annotation = AnnotatedElementUtils.findMergedAnnotation(specific, RequiresPermission.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), RequiresPermission.class);
        }
        if (annotation == null && target != null) {
            // The class the pointcut matched, which may be a subclass of the method's declaring class.
            annotation = AnnotatedElementUtils.findMergedAnnotation(target, RequiresPermission.class);
        }
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(
                    invocation.getMethod().getDeclaringClass(), RequiresPermission.class);
        }
        if (annotation == null) {
            // Matched by the pointcut, yet no permission found: refuse rather than guess.
            throw new AccessDeniedException("no permission could be determined");
        }
        String permission = annotation.value();
        CurrentUser user = currentUserAccessor.currentUser()
                .orElseThrow(() -> new InsufficientAuthenticationException(
                        "Authentication required for permission '" + permission + "'"));
        boolean granted = providers.stream().anyMatch(provider -> provider.hasPermission(user, permission));
        if (!granted) {
            throw new AccessDeniedException("missing permission '" + permission + "'");
        }
        return invocation.proceed();
    }
}
