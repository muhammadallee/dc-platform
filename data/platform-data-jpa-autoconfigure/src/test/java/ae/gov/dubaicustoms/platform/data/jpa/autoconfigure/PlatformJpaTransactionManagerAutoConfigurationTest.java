package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.core.CommitOutcomeUnknown;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.CommitEvidenceJpaTransactionManager;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLRecoverableException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/** The platform's JPA transaction manager: wiring, back-off, and evidence from a commit that really fails. */
class PlatformJpaTransactionManagerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    TransactionManagerCustomizationAutoConfiguration.class,
                    PlatformJpaTransactionManagerAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "spring.jpa.hibernate.ddl-auto=none");

    @Test
    void activeByDefaultKeepingBootsBeanNameTypeAndCustomizers() {
        runner.withPropertyValues("spring.transaction.default-timeout=17s").run(context -> {
            assertThat(context).hasSingleBean(PlatformTransactionManager.class);
            assertThat(context.getBean("transactionManager")).isInstanceOf(CommitEvidenceJpaTransactionManager.class);
            assertThat(context.getBean(JpaTransactionManager.class).getDefaultTimeout()).isEqualTo(17);
        });
    }

    @Test
    void killSwitchLeavesBootsManager() {
        runner.withPropertyValues("dc.platform.data.jpa.enabled=false").run(context -> {
            assertThat(context.getBean(JpaTransactionManager.class))
                    .isNotInstanceOf(CommitEvidenceJpaTransactionManager.class);
        });
    }

    @Test
    void backsOffFromAServicesOwnManager() {
        runner.withBean("mine", DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(new org.springframework.jdbc.datasource.DriverManagerDataSource()))
                .withPropertyValues("spring.main.lazy-initialization=true")
                .run(context -> assertThat(context).doesNotHaveBean(CommitEvidenceJpaTransactionManager.class));
    }

    @Test
    void aCommitThatLosesItsConnectionCarriesTheEvidence() {
        AtomicBoolean failCommit = new AtomicBoolean();
        runner.withBean("failingCommits", BeanPostProcessor.class, () -> new FailingCommits(failCommit)).run(context -> {
            TransactionTemplate template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            failCommit.set(true);

            assertThatThrownBy(() -> template.executeWithoutResult(status -> { }))
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasAtLeastOneElementOfType(CommitOutcomeUnknown.class));
        });
    }

    @Test
    void aFailureBeforeTheCommitCarriesNoEvidence() {
        runner.run(context -> {
            TransactionTemplate template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> template.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    throw new IllegalStateException("before the commit");
                                }
                            });
                }
            })).satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
        });
    }

    /** Makes {@code Connection.commit} fail as a lost connection does, once switched on. */
    private record FailingCommits(AtomicBoolean failCommit) implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof DataSource dataSource)) {
                return bean;
            }
            return new DelegatingDataSource(dataSource) {
                @Override
                public Connection getConnection() throws java.sql.SQLException {
                    Connection connection = super.getConnection();
                    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                                if ("commit".equals(method.getName()) && failCommit.get()) {
                                    throw new SQLRecoverableException("ORA-17008: Closed connection", "08006");
                                }
                                try {
                                    return method.invoke(connection, args);
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }
    }
}
