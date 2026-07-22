package ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.locking.LockManager;
import ae.gov.dubaicustoms.platform.scheduling.LockedSchedule;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Runs a {@link LockedSchedule}-annotated method under the platform {@link LockManager} when one is
 * available, so a scheduled job fires on a single instance cluster-wide; when no {@code LockManager}
 * bean is present it runs the method unlocked and warns once. Exists so scheduling depends on locking
 * only softly: the {@code LockManager} is resolved through an {@link ObjectProvider}, never required.
 */
final class LockedScheduleInterceptor implements MethodInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(LockedScheduleInterceptor.class);

    private final ObjectProvider<LockManager> lockManager;
    private final AtomicBoolean warnedAboutMissingLockManager = new AtomicBoolean(false);

    LockedScheduleInterceptor(ObjectProvider<LockManager> lockManager) {
        this.lockManager = lockManager;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        LockManager manager = lockManager.getIfAvailable();
        LockedSchedule annotation =
                AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), LockedSchedule.class);
        if (manager == null) {
            warnOnce(annotation.name());
            return invocation.proceed();
        }
        Duration atMost = DurationStyle.detectAndParse(annotation.atMost());
        // withLock returns empty when the lock is held elsewhere: the firing is skipped on this node.
        return manager.withLock(annotation.name(), atMost, () -> proceedRethrowing(invocation)).orElse(null);
    }

    private static Object proceedRethrowing(MethodInvocation invocation) throws Exception {
        try {
            return invocation.proceed();
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception checked) {
            throw checked;
        } catch (Throwable other) {
            // Non-Exception, non-Error Throwable (rare): honor Callable's checked-only contract.
            throw new IllegalStateException(other);
        }
    }

    private void warnOnce(String name) {
        if (warnedAboutMissingLockManager.compareAndSet(false, true)) {
            LOG.warn("@LockedSchedule(name=\"{}\") is active but no LockManager bean is present; "
                    + "the method runs WITHOUT a cluster lock. Add a locking starter "
                    + "(platform-starter-locking-jdbc or -redis) to enforce single execution.", name);
        }
    }
}
