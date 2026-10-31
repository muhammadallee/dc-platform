package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A dispatch from inside an after-commit callback: a callback registered there never fires. */
class TransactionalDispatcherPhaseTest {

    private final DataSourceTransactionManager manager = new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
    private final TransactionalDispatcher dispatcher = new TransactionalDispatcher();
    private final List<String> ran = new ArrayList<>();

    private void inAfterCommit(Runnable action) {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }));
    }

    @Test
    void runsNowInAnAfterCommitCallback() {
        inAfterCommit(() -> dispatcher.dispatch(() -> ran.add("handler")));

        assertThat(ran).containsExactly("handler");
    }

    @Test
    void waitsForATransactionOpenedInTheCallbackAndSkipsWhenItRollsBack() {
        var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        inAfterCommit(() -> inner.executeWithoutResult(status -> {
            dispatcher.dispatch(() -> ran.add("rolled back"));
            status.setRollbackOnly();
        }));
        assertThat(ran).isEmpty();

        inAfterCommit(() -> inner.executeWithoutResult(status -> {
            dispatcher.dispatch(() -> ran.add("committed"));
            assertThat(ran).isEmpty();
        }));
        assertThat(ran).containsExactly("committed");
    }
}
