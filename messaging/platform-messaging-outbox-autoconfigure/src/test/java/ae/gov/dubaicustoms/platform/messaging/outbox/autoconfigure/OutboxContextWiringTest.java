package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingAutoConfiguration;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxContext;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.AfterCommitOutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxConnections;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** What the outbox stores about the caller, the disabled mode's after-commit publish, and a non-Hikari data source. */
class OutboxContextWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class, DataSourceInitializationAutoConfiguration.class,
                    PlatformMessagingAutoConfiguration.class, OutboxAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "spring.sql.init.mode=always",
                    "spring.sql.init.schema-locations=classpath:ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql");

    @Test
    void theContextStoresTheSubjectAndTheW3cTraceHeaders() {
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(span.context()).thenReturn(traceContext);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        Propagator propagator = mock(Propagator.class);
        doAnswer(invocation -> {
            Propagator.Setter<Object> setter = invocation.getArgument(2);
            setter.set(invocation.getArgument(1), "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
            return null;
        }).when(propagator).inject(any(), any(), any());
        CurrentUserAccessor users = () -> Optional.of(new CurrentUser("user-1", null, Set.of(), Map.of()));

        runner.withBean(Tracer.class, () -> tracer).withBean(Propagator.class, () -> propagator)
                .withBean(CurrentUserAccessor.class, () -> users)
                .run(context -> {
                    OutboxContext outbox = context.getBean(OutboxContext.class);

                    assertThat(outbox.principal()).isEqualTo("user-1");
                    assertThat(outbox.traceHeaders()).containsOnlyKeys("traceparent");

                    when(tracer.currentSpan()).thenReturn(null);
                    assertThat(outbox.traceHeaders()).isEmpty();
                });
    }

    @Test
    void aServiceWithoutTheTracingJarStillStarts() {
        // A tracing type in a signature of an always-present bean breaks reflection over that bean.
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(Tracer.class, Propagator.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OutboxContext.class).traceHeaders()).isEmpty();
                });
    }

    @Test
    void anUnknownDataSourceIsRefusedBecauseItMayBeAPool() {
        var hidden = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override
            public java.sql.Connection getConnection() {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> OutboxConnections.of(hidden, 1, java.time.Duration.ofSeconds(60), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OutboxConnections bean");
    }

    @Test
    void aMeteredApplicationPoolCanBeTheSource() throws Exception {
        // Copying a pool's whole state carried its metrics factory across, and Hikari refused the copy.
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setMetricsTrackerFactory(new com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory(new SimpleMeterRegistry()));
        config.setAutoCommit(false);
        config.setConnectionTimeout(250_000);
        try (var application = new com.zaxxer.hikari.HikariDataSource(config);
                var connections = OutboxConnections.of(application, 2, java.time.Duration.ofSeconds(60), new SimpleMeterRegistry())) {
            var drainer = (com.zaxxer.hikari.HikariDataSource) connections.dataSource();

            assertThat(drainer.getJdbcUrl()).isEqualTo(application.getJdbcUrl());
            assertThat(drainer.isAutoCommit()).isTrue();
            assertThat(drainer.getConnectionTimeout()).isEqualTo(5000);
        }
    }

    @Test
    void theDrainersOraclePoolGetsTheDriverBoundsItselfAndKeepsTheApplicationsValue() throws Exception {
        // A JDBC-only service, or a pool the application started itself, has no Phase 3 bounds to inherit.
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:oracle:thin:@//127.0.0.1:9/APP");
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "3000");
        try (var application = new com.zaxxer.hikari.HikariDataSource(config);
                var connections = OutboxConnections.of(application, 2, java.time.Duration.ofSeconds(90), null)) {
            assertThat(((com.zaxxer.hikari.HikariDataSource) connections.dataSource()).getDataSourceProperties())
                    .containsEntry("oracle.jdbc.ReadTimeout", "90000")
                    .containsEntry("oracle.net.CONNECT_TIMEOUT", "3000");
        }
    }

    @Test
    void afterCommitPublishesNowInsideAnAfterCommitCallback() {
        List<EventEnvelope<?>> published = new ArrayList<>();
        var outbox = new AfterCommitOutboxPublisher((destination, event) -> published.add(event));
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status ->
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                // A callback registered here would never run.
                                outbox.publish("dc.orders", EventEnvelope.of("late").build());
                            }
                        }));

        assertThat(published).hasSize(1);
    }

    @Test
    void withoutTracingOrSecurityTheContextIsEmpty() {
        runner.run(context -> {
            OutboxContext outbox = context.getBean(OutboxContext.class);

            assertThat(outbox.principal()).isNull();
            assertThat(outbox.traceHeaders()).isEmpty();
        });
    }

    @Test
    void afterCommitPublishesOnlyOnCommitAndImmediatelyOutsideATransaction() {
        List<EventEnvelope<?>> published = new ArrayList<>();
        EventPublisher delegate = (destination, event) -> published.add(event);
        var outbox = new AfterCommitOutboxPublisher(delegate);
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(outbox.canPublish()).isTrue();
        transaction.executeWithoutResult(status -> {
            jdbc.execute("select 1");
            outbox.publish("dc.orders", EventEnvelope.of("rolled back").build());
            assertThat(published).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(published).isEmpty();

        transaction.executeWithoutResult(status -> outbox.publish("dc.orders", EventEnvelope.of("kept").build()));
        assertThat(published).hasSize(1);

        outbox.publish("dc.orders", EventEnvelope.of("no transaction").build());
        assertThat(published).hasSize(2);
    }

    @Test
    void aNonHikariDataSourceIsWrappedInABoundedPool() throws Exception {
        DataSource embedded = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

        try (OutboxConnections connections = OutboxConnections.of(embedded, 1, java.time.Duration.ofSeconds(60), new SimpleMeterRegistry());
                var connection = connections.dataSource().getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }
}
