package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingAutoConfiguration;
import ae.gov.dubaicustoms.platform.messaging.outbox.JdbcOutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxContext;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxDrainer;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxTableMissingException;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.AfterCommitOutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxConnections;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxTableMissingFailureAnalyzer;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.PlatformOutboxContext;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                    DataSourceInitializationAutoConfiguration.class, TransactionAutoConfiguration.class,
                    PlatformMessagingAutoConfiguration.class, OutboxAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "spring.sql.init.mode=always",
                    "spring.sql.init.schema-locations=classpath:ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql",
                    "spring.jpa.hibernate.ddl-auto=none");

    @Test
    void theServiceKeepsBootsPoolAndJpaTransactionManager() {
        // A DataSource or transaction manager bean from the outbox would make Boot back off: @Transactional would
        // stop covering JPA writes.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
            assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
            assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(JpaTransactionManager.class);
            assertThat(context).hasSingleBean(OutboxPublisher.class).hasSingleBean(OutboxDrainer.class);
            assertThat(context.getBean("outboxCapabilityDescriptor", CapabilityDescriptor.class).status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void theDrainersPoolIsSeparateBoundedAndCopiesTheApplicationPool() {
        runner.withPropertyValues("dc.platform.messaging.outbox.pool-size=3",
                "spring.datasource.hikari.connection-init-sql=select 1").run(context -> {
                    HikariDataSource application = context.getBean(HikariDataSource.class);
                    HikariDataSource drainer = (HikariDataSource) context.getBean(OutboxConnections.class).dataSource();

                    assertThat(drainer).isNotSameAs(application);
                    assertThat(drainer.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(drainer.getPoolName()).isEqualTo("platform-outbox");
                    assertThat(drainer.getJdbcUrl()).isEqualTo(application.getJdbcUrl());
                    assertThat(drainer.getConnectionInitSql()).isEqualTo("select 1");
                });
    }

    @Test
    void underJpaTheRowCommitsWithTheTransactionAndRollsBackWithIt() {
        // JpaTransactionManager exposes its connection for the DataSource: the row must join that transaction.
        runner.withPropertyValues("dc.platform.messaging.outbox.poll-interval=1h").run(context -> {
            assertThat(context.getBean(EntityManagerFactory.class)).isNotNull();
            OutboxPublisher outbox = context.getBean(OutboxPublisher.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                outbox.publish("dc.orders", EventEnvelope.of("rolled back").build());
                throw new IllegalArgumentException("business failure");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("select count(*) from platform_outbox", Integer.class)).isZero();

            transaction.executeWithoutResult(status -> outbox.publish("dc.orders", EventEnvelope.of("kept").build()));
            assertThat(jdbc.queryForObject("select count(*) from platform_outbox", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void propertiesDefaultSnapshot() {
        // §7.2: a changed default fails here until the snapshot is consciously updated (CHANGELOG).
        runner.run(context -> {
            var outbox = context.getBean(OutboxProperties.class);
            assertThat(outbox.enabled()).isTrue();
            assertThat(outbox.poolSize()).isEqualTo(2);
            assertThat(outbox.batchSize()).isEqualTo(50);
            assertThat(outbox.pollInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(outbox.maxRejections()).isEqualTo(10);
            assertThat(outbox.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(outbox.maxBackoff()).isEqualTo(Duration.ofMinutes(5));
            assertThat(outbox.sentRetention()).isEqualTo(Duration.ofHours(24));
            assertThat(outbox.statementTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(outbox.gaugeInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(outbox.retentionInterval()).isEqualTo(Duration.ofMinutes(5));
            assertThat(outbox.stopTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void disabledWarnsAndPublishesAfterCommitWithoutADrainer(CapturedOutput output) {
        runner.withPropertyValues("dc.platform.messaging.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxDrainer.class).doesNotHaveBean(OutboxConnections.class);
            assertThat(context.getBean(OutboxPublisher.class)).isInstanceOf(AfterCommitOutboxPublisher.class);
            assertThat(context.getBean("outboxCapabilityDescriptor", CapabilityDescriptor.class).status()).isEqualTo("INACTIVE");
            assertThat(output).contains("NO LONGER GUARANTEED");
        });
    }

    @Test
    void theMessagingKillSwitchTurnsItOff() {
        runner.withPropertyValues("dc.platform.messaging.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(OutboxPublisher.class).doesNotHaveBean(OutboxDrainer.class));
    }

    @Test
    void withoutTheModuleNothingIsCreated() {
        runner.withClassLoader(new FilteredClassLoader(JdbcOutboxPublisher.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxAutoConfiguration.class));
    }

    @Test
    void withoutADataSourceNothingIsCreated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class, OutboxAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxPublisher.class));
    }

    @Test
    void aMissingTableFailsStartupWithTheActionThatFixesIt() {
        runner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID(), "spring.sql.init.mode=never")
                .run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(failure).hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .cause().isInstanceOf(OutboxTableMissingException.class);
            var analysis = new OutboxTableMissingFailureAnalyzer() {
                org.springframework.boot.diagnostics.FailureAnalysis of(Throwable t) {
                    return analyze(t);
                }
            }.of(failure);
            assertThat(analysis.getAction()).contains("platform_outbox.sql").contains("outbox.enabled=false");
        });
    }

    @Test
    void anApplicationPublisherAndDrainerWin() {
        OutboxPublisher mine = new OutboxPublisher() {
            @Override
            public void publish(String destination, EventEnvelope<?> event) {
            }

            @Override
            public boolean canPublish() {
                return false;
            }
        };
        runner.withBean("mine", OutboxPublisher.class, () -> mine).run(context ->
                assertThat(context.getBean(OutboxPublisher.class)).isSameAs(mine));
    }

    @Test
    void theContextRestoresTheStoredTraceAndIgnoresABadOne() {
        var context = new PlatformOutboxContext(Map::of, () -> null);

        try (OutboxContext.Scope ignored = context.restore(
                Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"))) {
            assertThat(MDC.get("traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        }
        assertThat(MDC.get("traceId")).isNull();

        try (OutboxContext.Scope ignored = context.restore(Map.of("traceparent", "garbage"))) {
            assertThat(MDC.get("traceId")).isNull();
        }
    }
}
