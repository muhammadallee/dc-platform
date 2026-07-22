package ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.locking.LockManager;
import ae.gov.dubaicustoms.platform.scheduling.LockedSchedule;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The no-LockManager path: the method runs unlocked (the warning is emitted once). */
class LockedScheduleInterceptorTest {

    static final class Sample {
        @LockedSchedule(name = "sample", atMost = "1s")
        public String run() {
            return "ran";
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsUnlockedWhenNoLockManagerBean() throws Throwable {
        ObjectProvider<LockManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        LockedScheduleInterceptor interceptor = new LockedScheduleInterceptor(provider);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(Sample.class.getMethod("run"));
        when(invocation.proceed()).thenReturn("ran");

        assertThat(interceptor.invoke(invocation)).isEqualTo("ran");
        // A second firing still runs; the missing-lock warning is emitted only once.
        assertThat(interceptor.invoke(invocation)).isEqualTo("ran");
        verify(invocation, times(2)).proceed();
    }
}
