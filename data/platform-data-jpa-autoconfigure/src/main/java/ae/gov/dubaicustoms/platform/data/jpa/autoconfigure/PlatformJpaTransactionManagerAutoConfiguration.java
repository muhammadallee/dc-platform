package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.CommitEvidenceJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionManager;

/*
 * Replaces Boot's JPA transaction manager with one that says when a failure came out of the commit itself (HI-0071).
 * Activation: Hibernate and JpaTransactionManager on the classpath, a single DataSource candidate (Boot's own JPA
 * conditions), and dc.platform.data.jpa.enabled not false.
 * Back-off: any TransactionManager bean. Boot's own bean backs off from this one, under the same name and with the same
 * customizers, so spring.transaction.* still applies. A service's own manager carries no evidence, and the errors
 * capability falls back to recognising the commit by its stack frame. It runs before HibernateJpaAutoConfiguration for
 * that back-off, so it assumes Boot's JPA configuration or a service's EntityManagerFactory will exist.
 */
@AutoConfiguration(
        before = HibernateJpaAutoConfiguration.class,
        after = {DataSourceAutoConfiguration.class, TransactionManagerCustomizationAutoConfiguration.class})
@ConditionalOnClass({EntityManagerFactory.class, JpaTransactionManager.class, SessionImplementor.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "dc.platform.data.jpa", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlatformJpaTransactionManagerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    JpaTransactionManager transactionManager(ObjectProvider<TransactionManagerCustomizers> customizers) {
        JpaTransactionManager manager = new CommitEvidenceJpaTransactionManager();
        customizers.ifAvailable(each -> each.customize(manager));
        return manager;
    }
}
