package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Parity of the auditing/converter/naming behavior against a real Postgres. Excluded from the
 * default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@Testcontainers
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "dc.platform.data.jpa.require-migrations=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostgresParityIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private EntityManager em;

    @Test
    void auditingConvertersAndNamingWorkOnPostgres() {
        CorrelationId correlationId = CorrelationId.random();
        AuditedWidget widget = new AuditedWidget();
        widget.referenceCode = "PG-1";
        widget.price = Money.of("99.95", "AED");
        widget.correlationId = correlationId;

        em.persist(widget);
        em.flush();
        em.clear();

        AuditedWidget loaded = em.find(AuditedWidget.class, widget.id);
        assertThat(loaded.createdBy).isEqualTo("system");
        assertThat(loaded.createdAt).isNotNull();
        assertThat(loaded.price).isEqualTo(Money.of("99.95", "AED"));
        assertThat(loaded.correlationId).isEqualTo(correlationId);

        Object stored = em.createNativeQuery(
                        "select price from audited_widget where reference_code = 'PG-1'")
                .getSingleResult();
        assertThat(stored).isEqualTo("99.95 AED");
    }
}
