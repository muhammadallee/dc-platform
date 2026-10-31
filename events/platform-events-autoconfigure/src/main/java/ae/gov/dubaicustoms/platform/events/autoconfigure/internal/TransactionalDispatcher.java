package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link AfterCommitDispatcher} that registers a {@link TransactionSynchronization} when a
 * transaction is active on the current thread, running the action in {@code afterCommit()}; runs
 * immediately when no transaction is active, same as {@link ImmediateDispatcher}.
 */
public final class TransactionalDispatcher implements AfterCommitDispatcher {

    @Override
    public void dispatch(Runnable action) {
        // A callback registered during the after-commit phase would never run, so that phase dispatches now.
        if (!TransactionSynchronizationManager.isSynchronizationActive() || afterCommitPhase()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // Spring offers no flag for this phase, so the nearest frame decides. A transaction opened inside the callback
    // has its own callbacks, and a handler must wait for it: it may yet roll back.
    private static boolean afterCommitPhase() {
        return StackWalker.getInstance().walk(frames -> frames.map(frame -> {
            String type = frame.getClassName();
            String method = frame.getMethodName();
            if ("org.springframework.transaction.support.TransactionSynchronizationUtils".equals(type)
                    && method.equals("invokeAfterCommit")) {
                return Boolean.TRUE;
            }
            boolean inner = ("org.springframework.transaction.support.TransactionTemplate".equals(type)
                    && "execute".equals(method))
                    || ("org.springframework.transaction.interceptor.TransactionAspectSupport".equals(type)
                    && "invokeWithinTransaction".equals(method));
            return inner ? Boolean.FALSE : null;
        }).filter(java.util.Objects::nonNull).findFirst().orElse(Boolean.FALSE));
    }
}
