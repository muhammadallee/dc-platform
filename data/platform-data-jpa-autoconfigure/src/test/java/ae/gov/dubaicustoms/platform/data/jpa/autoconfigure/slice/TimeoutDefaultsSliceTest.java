package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The D110 statement bounds reach the components Boot builds from them — not only the
 * {@code platform-data-jpa-defaults} property source: a misspelt key would be ignored silently.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "dc.platform.data.jpa.require-migrations=false"
})
@ImportAutoConfiguration({PlatformDataJpaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
class TimeoutDefaultsSliceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theDefaultsReachJdbcTemplateHibernateAndTheTransactionManager() {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(30);
        assertThat(entityManagerFactory.getProperties()).containsEntry("jakarta.persistence.query.timeout", "30000");
        assertThat(((JpaTransactionManager) transactionManager).getDefaultTimeout()).isEqualTo(30);
    }
}
