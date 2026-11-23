package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.zeroconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract 13 (HI-0168), as the data page and chapter 8 state it: with two auditors, the {@code @Primary} one audits;
 * with two and no {@code @Primary}, startup fails naming the ambiguity instead of auditing as anyone silently.
 */
class TwoAuditorsTest {

    @Entity(name = "TwoAuditorsNote")
    @EntityListeners(AuditingEntityListener.class)
    static class Note {

        @Id
        Long id;

        @CreatedBy
        String createdBy;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Ambiguous {

        @Bean
        AuditorAware<String> firstAuditor() {
            return () -> Optional.of("first");
        }

        @Bean
        AuditorAware<String> secondAuditor() {
            return () -> Optional.of("second");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class WithPrimary {

        @Bean
        AuditorAware<String> firstAuditor() {
            return () -> Optional.of("first");
        }

        @Bean
        @Primary
        AuditorAware<String> secondAuditor() {
            return () -> Optional.of("second");
        }
    }

    private static final String[] ARGS = {"--spring.datasource.url=jdbc:h2:mem:two-auditors;DB_CLOSE_DELAY=-1",
            "--spring.flyway.enabled=false", "--dc.platform.data.jpa.require-migrations=false",
            "--spring.jpa.hibernate.ddl-auto=create-drop"};

    @Test
    void twoAuditorsWithoutAPrimaryFailStartup() {
        assertThatThrownBy(() -> new SpringApplication(Ambiguous.class).run(ARGS).close())
                .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                .rootCause().hasMessageContaining("firstAuditor").hasMessageContaining("secondAuditor");
    }

    @Test
    void thePrimaryAuditorAudits() {
        try (ConfigurableApplicationContext context = new SpringApplication(WithPrimary.class).run(ARGS)) {
            context.getBean(EntityManagerFactory.class).runInTransaction(entities -> {
                Note note = new Note();
                note.id = 1L;
                entities.persist(note);
            });
            assertThat(context.getBean(JdbcTemplate.class)
                    .queryForObject("select created_by from two_auditors_note where id = 1", String.class))
                    .isEqualTo("second");
        }
    }
}
