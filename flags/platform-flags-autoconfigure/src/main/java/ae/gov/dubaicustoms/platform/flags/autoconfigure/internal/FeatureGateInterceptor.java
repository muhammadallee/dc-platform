package ae.gov.dubaicustoms.platform.flags.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.flags.FeatureFlags;
import ae.gov.dubaicustoms.platform.flags.FeatureGate;
import java.lang.reflect.Array;
import java.util.Optional;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Enforces {@link FeatureGate}: runs the method only when its flag is enabled; otherwise skips it and
 * returns a neutral value chosen by the method's return type ({@code false} for boolean, empty for
 * {@link Optional}, {@code null}/no-op for references and {@code void}, the zero value for other
 * primitives). Exists so a service can gate a code path with an annotation instead of an if-branch.
 */
final class FeatureGateInterceptor implements MethodInterceptor {

    private final FeatureFlags featureFlags;

    FeatureGateInterceptor(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        FeatureGate gate = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), FeatureGate.class);
        if (featureFlags.enabled(gate.value())) {
            return invocation.proceed();
        }
        return neutralValue(invocation.getMethod().getReturnType());
    }

    private static Object neutralValue(Class<?> returnType) {
        if (returnType == Boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType.isPrimitive() && returnType != void.class) {
            // Boxed default of the primitive: false for boolean, 0 for numerics, '\0' for char.
            return Array.get(Array.newInstance(returnType, 1), 0);
        }
        // void and every other reference type: nothing / null.
        return null;
    }
}
