package ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.locking.LockManager;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;

/** Builds the {@code lockedScheduleAdvisor} bean; keeps the pointcut/interceptor pair module-private. */
public final class LockedScheduleAdvisorFactory {

    private LockedScheduleAdvisorFactory() {
    }

    /**
     * @param lockManager provider for the (optional) platform lock manager
     * @return an advisor applying the lock around {@code @LockedSchedule} methods
     */
    public static Advisor create(ObjectProvider<LockManager> lockManager) {
        return new DefaultPointcutAdvisor(
                new LockedSchedulePointcut(), new LockedScheduleInterceptor(lockManager));
    }
}
