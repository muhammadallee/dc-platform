package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.errors.ConflictException;
import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.Idempotent;
import java.lang.reflect.Method;
import java.time.Duration;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;

/** SpEL key evaluation and duplicate rejection for the @Idempotent interceptor. */
class IdempotentInterceptorTest {

    static final class Sample {
        @Idempotent(keyExpression = "#a0", ttl = "1h")
        public void handle(String orderId) {
        }

        @Idempotent(keyExpression = "#a1")
        public void missingArg(String orderId) {
        }
    }

    private MethodInvocation invocationFor(Method method, Object... args) throws Throwable {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getThis()).thenReturn(new Sample());
        when(invocation.getArguments()).thenReturn(args);
        when(invocation.proceed()).thenReturn(null);
        return invocation;
    }

    @Test
    void derivesKeyFromArgumentsProceedsOnceAndRejectsDuplicate() throws Throwable {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.putIfAbsent(eq("order-1"), any(Duration.class))).thenReturn(true, false);
        IdempotentInterceptor interceptor = new IdempotentInterceptor(store);
        Method handle = Sample.class.getDeclaredMethod("handle", String.class);

        interceptor.invoke(invocationFor(handle, "order-1"));
        verify(store).putIfAbsent(eq("order-1"), eq(Duration.ofHours(1)));

        assertThatThrownBy(() -> interceptor.invoke(invocationFor(handle, "order-1")))
                .isInstanceOf(ConflictException.class);
        verify(store, times(2)).putIfAbsent(eq("order-1"), any(Duration.class));
    }

    @Test
    void rejectsAKeyExpressionThatEvaluatesToNull() throws Throwable {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotentInterceptor interceptor = new IdempotentInterceptor(store);
        Method missingArg = Sample.class.getDeclaredMethod("missingArg", String.class);

        assertThatThrownBy(() -> interceptor.invoke(invocationFor(missingArg, "order-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evaluated to null");
    }
}
