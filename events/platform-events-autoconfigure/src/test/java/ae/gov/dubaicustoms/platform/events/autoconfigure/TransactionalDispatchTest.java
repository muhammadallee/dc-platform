package ae.gov.dubaicustoms.platform.events.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventHandler;
import ae.gov.dubaicustoms.platform.events.DomainEventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the after-commit semantics with a real {@link PlatformTransactionManager} backed by an
 * embedded H2 database: a {@code @DomainEventHandler} method must not run until the enclosing
 * transaction commits, and must never run if it rolls back.
 */
@SpringBootTest(classes = TransactionalDispatchTest.App.class)
class TransactionalDispatchTest {

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingHandler handler;

    record OrderPlaced(String orderId) implements DomainEvent {}

    static class RecordingHandler {
        final List<OrderPlaced> received = new CopyOnWriteArrayList<>();

        @DomainEventHandler
        void onOrderPlaced(OrderPlaced event) {
            received.add(event);
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void clearReceivedEvents() {
        handler.received.clear();
    }

    @Test
    void handlerRunsOnlyAfterTheTransactionCommits() {
        var txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            publisher.publish(new OrderPlaced("o-1"));
            assertThat(handler.received).isEmpty();
        });

        assertThat(handler.received).containsExactly(new OrderPlaced("o-1"));
    }

    @Test
    void handlerNeverRunsWhenTheTransactionRollsBack() {
        var txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            publisher.publish(new OrderPlaced("o-1"));
            status.setRollbackOnly();
        });

        assertThat(handler.received).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }
}
