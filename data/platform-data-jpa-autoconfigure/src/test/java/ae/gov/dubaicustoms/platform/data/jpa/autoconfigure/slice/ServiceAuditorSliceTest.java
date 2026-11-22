package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;

/** Contract 13 (HI-0168): a service's own auditor, whatever its bean name, is the one that audits. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "dc.platform.data.jpa.require-migrations=false"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
class ServiceAuditorSliceTest {

    @TestConfiguration
    static class ServiceAuditor {

        // An auditor that needs JPA itself: taking it by type must not close a startup cycle.
        @Bean
        AuditorAware<String> callerAuditor(EntityManager entityManager) {
            return () -> Optional.ofNullable(entityManager == null ? null : "caller");
        }
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private ApplicationContext context;

    @Test
    void theServicesAuditorIsUsedUnchanged() {
        AuditedWidget widget = new AuditedWidget();
        widget.referenceCode = "AUD-1";
        em.persist(widget);
        em.flush();
        em.clear();

        AuditedWidget loaded = em.find(AuditedWidget.class, widget.id);
        assertThat(loaded.createdBy).isEqualTo("caller");
        assertThat(loaded.lastModifiedBy).isEqualTo("caller");
    }

    @Test
    void thePlatformsAuditorStepsBack() {
        assertThat(context.getBeanNamesForType(AuditorAware.class)).containsExactly("callerAuditor");
    }
}
