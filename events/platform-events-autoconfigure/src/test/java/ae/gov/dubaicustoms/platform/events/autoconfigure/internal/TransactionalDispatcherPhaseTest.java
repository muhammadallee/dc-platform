package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A dispatch from inside a transaction's callbacks: a callback registered there never fires. */
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

    /** What a {@code @Transactional} method with this propagation does around {@code action}. */
    private Runnable transactional(int propagation, Runnable action) {
        var source = new MatchAlwaysTransactionAttributeSource();
        source.setTransactionAttribute(new DefaultTransactionAttribute(propagation));
        var proxy = new ProxyFactory(action);
        proxy.addAdvice(new TransactionInterceptor(manager, source));
        return (Runnable) proxy.getProxy();
    }

    @Test
    void runsNowInAnAfterCommitCallback() {
        inAfterCommit(() -> dispatcher.dispatch(() -> ran.add("handler")));

        assertThat(ran).containsExactly("handler");
    }

    @Test
    void waitsForARequiresNewMethodInTheCallbackAndSkipsWhenItRollsBack() {
        Runnable rolledBack = transactional(TransactionDefinition.PROPAGATION_REQUIRES_NEW, () -> {
            dispatcher.dispatch(() -> ran.add("rolled back"));
            throw new IllegalArgumentException("business failure");
        });
        inAfterCommit(() -> {
            assertThatThrownBy(rolledBack::run).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(ran).isEmpty();

        Runnable committed = transactional(TransactionDefinition.PROPAGATION_REQUIRES_NEW, () -> {
            dispatcher.dispatch(() -> ran.add("committed"));
            assertThat(ran).isEmpty();
        });
        inAfterCommit(committed);
        assertThat(ran).containsExactly("committed");
    }

    @Test
    void refusesInARequiredMethodThatJoinedTheFinishedTransaction() {
        Runnable joined = transactional(TransactionDefinition.PROPAGATION_REQUIRED, () -> dispatcher.dispatch(() -> ran.add("lost")));

        inAfterCommit(() -> assertThatThrownBy(joined::run).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could run wrongly or never"));

        assertThat(ran).isEmpty();
    }

    @Test
    void refusesInATemplateInTheCallbackWhichCannotBeProvenNew() {
        var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        inAfterCommit(() -> assertThatThrownBy(() -> inner.executeWithoutResult(status ->
                dispatcher.dispatch(() -> ran.add("lost")))).isInstanceOf(IllegalStateException.class));

        assertThat(ran).isEmpty();
    }

    @Test
    void refusesInAnAfterCompletionCallback() {
        List<Throwable> refused = new ArrayList<>();
        new TransactionTemplate(manager).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int outcome) {
                        try {
                            dispatcher.dispatch(() -> ran.add("lost"));
                        } catch (IllegalStateException e) {
                            refused.add(e);
                        }
                    }
                }));

        assertThat(refused).hasSize(1);
        assertThat(ran).isEmpty();
    }
}
