package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link AfterCommitDispatcher} that registers a {@link TransactionSynchronization} when a
 * transaction is active on the current thread, running the action in {@code afterCommit()}; runs
 * immediately when no transaction is active, same as {@link ImmediateDispatcher}.
 *
 * <p>Inside a transaction's own after-commit callback it runs the action at once, since a callback registered there
 * never fires. A {@code @Transactional} {@code REQUIRES_NEW} method in that callback waits for its own commit as usual.
 * It refuses, with an exception, wherever the action could run after a rollback or never: an after-completion callback, and a
 * transaction in an after-commit callback that cannot be proven new (one that joined the finished transaction, or any
 * opened with {@code TransactionTemplate}).
 */
public final class TransactionalDispatcher implements AfterCommitDispatcher {

    @Override
    public void dispatch(Runnable action) {
        // The phase comes first: Spring clears the synchronizations before after-completion callbacks, so that phase
        // looks like no transaction at all, and an action there would run even after a rollback.
        switch (currentPhase()) {
            case AFTER_COMMIT -> action.run();
            case UNSAFE -> throw new IllegalStateException("A dispatch here could run wrongly or never: this is under an "
                    + "after-completion callback, or a transaction in an after-commit callback that may have joined the "
                    + "finished one. Dispatch directly in the after-commit callback, or from a @Transactional REQUIRES_NEW "
                    + "method");
            case NORMAL -> {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
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
        }
    }

    // Spring offers no flag for this phase, so the stack decides. A transaction opened in the callback is proven new only
    // by the interceptor's status: a joined one has already finished, and whatever it registers never runs.
    private static Phase currentPhase() {
        return StackWalker.getInstance().walk(frames -> {
            Phase nested = null;
            for (var it = frames.iterator(); it.hasNext(); ) {
                var frame = it.next();
                String type = frame.getClassName();
                String method = frame.getMethodName();
                if ("org.springframework.transaction.support.TransactionSynchronizationUtils".equals(type)) {
                    if (method.equals("invokeAfterCommit") || method.equals("invokeAfterCompletion")) {
                        if (nested != null) {
                            return nested;
                        }
                        return method.equals("invokeAfterCommit") ? Phase.AFTER_COMMIT : Phase.UNSAFE;
                    }
                } else if (nested == null && "org.springframework.transaction.support.TransactionTemplate".equals(type)
                        && "execute".equals(method)) {
                    nested = Phase.UNSAFE;
                } else if (nested == null
                        && "org.springframework.transaction.interceptor.TransactionAspectSupport".equals(type)
                        && "invokeWithinTransaction".equals(method)) {
                    nested = TransactionAspectSupport.currentTransactionStatus().isNewTransaction()
                            ? Phase.NORMAL : Phase.UNSAFE;
                }
            }
            return Phase.NORMAL;
        });
    }

    private enum Phase { NORMAL, AFTER_COMMIT, UNSAFE }
}
