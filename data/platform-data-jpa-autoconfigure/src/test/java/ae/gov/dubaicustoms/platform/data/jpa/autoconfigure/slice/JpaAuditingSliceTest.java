package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * H2 slice proving the JPA auto-configuration end to end against a real EntityManagerFactory:
 * auditing populates the created/modified fields with the {@code "system"} auditor, the Money and
 * CorrelationId converters round-trip, and snake_case physical naming is in effect.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "dc.platform.data.jpa.require-migrations=false"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
class JpaAuditingSliceTest {

    @Autowired
    private EntityManager em;

    @Test
    void auditingPopulatesCreatedAndModifiedFields() {
        AuditedWidget widget = new AuditedWidget();
        widget.referenceCode = "ABC-1";
        widget.price = Money.of("19.99", "AED");
        widget.correlationId = CorrelationId.random();

        em.persist(widget);
        em.flush();
        em.clear();

        AuditedWidget loaded = em.find(AuditedWidget.class, widget.id);
        assertThat(loaded.createdAt).isNotNull();
        assertThat(loaded.createdBy).isEqualTo("system");
        assertThat(loaded.lastModifiedAt).isNotNull();
        assertThat(loaded.lastModifiedBy).isEqualTo("system");
    }

    @Test
    void convertersRoundTripThroughTheDatabase() {
        CorrelationId correlationId = CorrelationId.random();
        AuditedWidget widget = new AuditedWidget();
        widget.referenceCode = "ABC-2";
        widget.price = Money.of("42.50", "USD");
        widget.correlationId = correlationId;

        em.persist(widget);
        em.flush();
        em.clear();

        AuditedWidget loaded = em.find(AuditedWidget.class, widget.id);
        assertThat(loaded.price).isEqualTo(Money.of("42.50", "USD"));
        assertThat(loaded.correlationId).isEqualTo(correlationId);

        // Money is stored as its single-column canonical string.
        Object stored = em.createNativeQuery(
                        "select price from audited_widget where reference_code = 'ABC-2'")
                .getSingleResult();
        assertThat(stored).isEqualTo("42.50 USD");
    }

    @Test
    void physicalNamingIsSnakeCase() {
        AuditedWidget widget = new AuditedWidget();
        widget.referenceCode = "ABC-3";
        em.persist(widget);
        em.flush();

        // These native queries only resolve if the columns are snake_cased.
        Object count = em.createNativeQuery(
                        "select count(*) from audited_widget where reference_code = 'ABC-3' and correlation_id is null")
                .getSingleResult();
        assertThat(((Number) count).intValue()).isEqualTo(1);
    }
}
