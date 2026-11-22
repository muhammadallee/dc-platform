package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.zeroconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * Contract 14, AC20: a service holding only the data starter's classpath, given a datasource URL and nothing else,
 * runs its migrations from Boot's default location, audits as {@code "system"} and has every platform default.
 */
class ZeroConfigAcceptanceTest {

    @Entity(name = "ZeroNote")
    @EntityListeners(AuditingEntityListener.class)
    static class ZeroNote {

        @Id
        Long id;

        @CreatedBy
        String createdBy;

        @CreatedDate
        Instant createdAt;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    @Test
    void aDatasourceIsTheOnlySettingAServiceNeeds(@TempDir Path resources) throws Exception {
        // On a class path of its own: db/migration in this module's test resources would reach every other slice.
        Path migrations = Files.createDirectories(resources.resolve("db/migration"));
        Files.writeString(migrations.resolve("V1__zero_note.sql"), """
                create table zero_note (id bigint primary key, created_by varchar(64), created_at timestamp with time zone);
                """);
        try (URLClassLoader classes = new URLClassLoader(new URL[] {resources.toUri().toURL()},
                getClass().getClassLoader());
                ConfigurableApplicationContext context = new SpringApplication(new DefaultResourceLoader(classes), App.class)
                        .run("--spring.datasource.url=jdbc:h2:mem:zero-config;DB_CLOSE_DELAY=-1")) {

            Flyway flyway = context.getBean(Flyway.class);
            assertThat(flyway.info().applied()).hasSize(1);
            assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
            assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
            assertThat(flyway.getConfiguration().isOutOfOrder()).isFalse();
            assertThat(flyway.getConfiguration().isValidateMigrationNaming()).isTrue();

            EntityManagerFactory factory = context.getBean(EntityManagerFactory.class);
            factory.runInTransaction(entities -> {
                ZeroNote note = new ZeroNote();
                note.id = 1L;
                entities.persist(note);
            });
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select created_by from zero_note where id = 1", String.class))
                    .isEqualTo("system");
            assertThat(jdbc.queryForObject("select count(*) from zero_note where created_at is not null", Integer.class))
                    .isEqualTo(1);

            assertThat(context.getBean(HikariDataSource.class).getConnectionTimeout()).isEqualTo(5_000);
            assertThat(context.getBean(JpaTransactionManager.class).getDefaultTimeout()).isEqualTo(30);
            assertThat(jdbc.getQueryTimeout()).isEqualTo(30);
            Map<String, Object> hibernate = factory.getProperties();
            assertThat(hibernate.get("jakarta.persistence.query.timeout")).hasToString("30000");
            assertThat(hibernate.get("hibernate.jdbc.batch_size")).hasToString("50");
            assertThat(hibernate.get("hibernate.jdbc.time_zone")).hasToString("UTC");
            assertThat(hibernate.get("hibernate.order_inserts")).hasToString("true");
            assertThat(hibernate.get("hibernate.order_updates")).hasToString("true");
            assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        }
    }
}
