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
