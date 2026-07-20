package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionalDispatcherTest {

    private final TransactionalDispatcher dispatcher = new TransactionalDispatcher();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsImmediatelyWhenNoTransactionIsActive() {
        AtomicBoolean ran = new AtomicBoolean();

        dispatcher.dispatch(() -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void deferersUntilAfterCommitWhenATransactionIsActive() {
        AtomicBoolean ran = new AtomicBoolean();
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(() -> ran.set(true));
        assertThat(ran).isFalse();

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertThat(ran).isTrue();
    }

    @Test
    void neverRunsIfTransactionRollsBack() {
        AtomicBoolean ran = new AtomicBoolean();
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(() -> ran.set(true));
        // Rollback: afterCommit() is simply never invoked (Spring calls afterCompletion instead).

        assertThat(ran).isFalse();
    }
}
