package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayPresenceCheck;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationContext;

/**
 * The starter's migration composition (flyway-core + spring-boot-flyway, require-migrations left ON)
 * against H2: Boot actually starts Flyway, the migration creates the schema, and the presence guard
 * passes. Guards against the Boot 4 split where flyway-core alone leaves Flyway un-started.
 */
@DataJpaTest(properties = "spring.flyway.locations=classpath:db/migration-platform-data-it")
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
class FlywayMigrationSliceTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager em;

    @Test
    void migrationsRunAndCreateTheSchema() {
        assertThat(context.getBeansOfType(FlywayPresenceCheck.class)).hasSize(1);
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getScript)
                .containsExactly("V1__platform_data_it.sql");

        em.createNativeQuery("insert into platform_data_it_note (id, text) values (1, 'migrated')").executeUpdate();

        Object text = em.createNativeQuery("select text from platform_data_it_note where id = 1").getSingleResult();
        assertThat(text).isEqualTo("migrated");
    }
}
