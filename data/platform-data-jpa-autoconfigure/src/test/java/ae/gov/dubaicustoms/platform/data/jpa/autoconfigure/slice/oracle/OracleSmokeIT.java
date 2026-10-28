package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Oracle smoke: the 19c pin really reaches Hibernate's dialect, Flyway migrates through
 * {@code flyway-database-oracle}, and Hibernate validates the entity against the 19c-safe schema.
 * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OracleSmokeIT {

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private EntityManager em;

    @Test
    void hibernateResolvesTheOracle19DialectAgainstTheContainer() {
        var dialect = em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class)
                .getJdbcServices().getDialect();
        assertThat(dialect.getClass().getSimpleName()).isEqualTo("OracleDialect");
        assertThat(dialect.getVersion().getDatabaseMajorVersion()).isEqualTo(19);
    }

    @Test
    void flywayMigratedAndAnEntityRoundTrips() {
        Object applied = em.createNativeQuery(
                "select count(*) from \"flyway_schema_history\" where \"success\" = 1").getSingleResult();
        assertThat(((Number) applied).intValue()).isEqualTo(2);

        CorrelationId correlationId = CorrelationId.random();
        OracleWidget widget = new OracleWidget();
        widget.referenceCode = "ORA-1";
        widget.active = true;
        widget.happenedAt = Instant.parse("2026-10-28T10:15:30.123456Z");
        widget.price = Money.of("99.95", "AED");
        widget.correlationId = correlationId;
        em.persist(widget);
        em.flush();
        em.clear();

        OracleWidget loaded = em.find(OracleWidget.class, widget.id);
        assertThat(loaded.active).isTrue();
        assertThat(loaded.happenedAt).isEqualTo(widget.happenedAt);
        assertThat(loaded.price).isEqualTo(Money.of("99.95", "AED"));
        assertThat(loaded.correlationId).isEqualTo(correlationId);
        assertThat(loaded.createdBy).isEqualTo("system");
    }
}
