package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.AuditorAwareProvider;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayOutOfOrderGuard;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayPresenceCheck;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleConnectionDefaults;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleFlywayConnectionDefaults;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/*
 * Activates when: JPA (jakarta.persistence.EntityManagerFactory + Spring Data AuditorAware) on the
 *                 classpath AND dc.platform.data.jpa.enabled != false.
 * Backs off when: the user defines the same-named bean (platformAuditorAware / the Flyway checks /
 *                 the Oracle defaults / dataJpaCapabilityDescriptor) or
 *                 already enables JPA auditing themselves (jpaAuditingHandler present).
 * Beans: platformAuditorAware — AuditorAware<String>; resolves the current user's subject when the
 *                 security capability is present and a request is authenticated, otherwise "system".
 *                 Two mutually exclusive definitions keyed on whether CurrentUserAccessor is on the
 *                 classpath (@ConditionalOnClass / @ConditionalOnMissingClass), so a data-only
 *                 consumer never loads a security type;
 *        platformFlywayPresenceCheck — fails startup when require-migrations is on but Flyway is
 *                 absent, with an actionable message;
 *        dataJpaCapabilityDescriptor — one line in the startup capability banner;
 *        platformOracleConnectionDefaults — adds the Oracle driver read/connect timeouts to a Hikari
 *                 pool whose URL is jdbc:oracle: (only when HikariCP is present; D110);
 *        platformFlywayOutOfOrderGuard — fails startup when the finished Flyway configuration has
 *                 out-of-order migrations on outside the local profile (only when Flyway is present; D112);
 *        platformOracleFlywayConnectionDefaults — adds the same Oracle timeouts to a separate Flyway
 *                 data source (spring.flyway.user/url) with a jdbc:oracle: URL (only when Flyway is present).
 * Nested JpaAuditingConfiguration enables @EnableJpaAuditing only once a real EntityManagerFactory
 *        exists (@ConditionalOnBean) — this is why the class is ordered after HibernateJpa
 *        AutoConfiguration — so a context without JPA infrastructure still starts cleanly.
 * Order: after HibernateJpaAutoConfiguration (EntityManagerFactory must be registered first).
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, AuditorAware.class})
@ConditionalOnProperty(prefix = "dc.platform.data.jpa", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DataJpaProperties.class)
public class PlatformDataJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FlywayPresenceCheck platformFlywayPresenceCheck(DataJpaProperties properties) {
        return new FlywayPresenceCheck(properties.requireMigrations(), getClass().getClassLoader());
    }

    // CapabilityDescriptor is a shared type (one per capability), so the override is by name (D97).
    @Bean
    @ConditionalOnMissingBean(name = "dataJpaCapabilityDescriptor")
    CapabilityDescriptor dataJpaCapabilityDescriptor() {
        return new CapabilityDescriptor("data-jpa", "ACTIVE", "");
    }

    /**
     * The auditor when the security capability is NOT on the classpath: everything is audited as
     * {@code "system"}. Kept free of any security type so a data-only consumer never loads one.
     */
    @Bean
    @ConditionalOnMissingBean(name = "platformAuditorAware")
    @ConditionalOnMissingClass("ae.gov.dubaicustoms.platform.security.CurrentUserAccessor")
    AuditorAware<String> platformAuditorAware() {
        // The "system" literal is inlined (not AuditorAwareProvider.SYSTEM) so this path never loads
        // AuditorAwareProvider, whose signature references the possibly-absent CurrentUserAccessor.
        return () -> Optional.of("system");
    }

    /*
     * Enables Spring Data JPA auditing, but only once a real EntityManagerFactory is present (the
     * auditing metamodel needs it); backs off when the user already enabled auditing.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnMissingBean(name = "jpaAuditingHandler")
    @EnableJpaAuditing(auditorAwareRef = "platformAuditorAware")
    static class JpaAuditingConfiguration {
    }

    /*
     * Oracle driver read/connect timeouts on a Hikari pool with a jdbc:oracle: URL (D110). Static: a
     * BeanPostProcessor must not pull the enclosing configuration in early.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
    static class OracleConnectionDefaultsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "platformOracleConnectionDefaults")
        static OracleConnectionDefaults platformOracleConnectionDefaults() {
            return new OracleConnectionDefaults();
        }
    }

    /*
     * Checks and bounds on the finished Flyway bean: out-of-order migrations only under the local profile
     * (D112) and the Oracle network bounds on a separate migration data source (D110). Nested so Flyway
     * types are only touched when Flyway is on the classpath; static bean methods, as a BeanPostProcessor
     * must not pull the enclosing configuration in early.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
    static class FlywayChecksConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "platformFlywayOutOfOrderGuard")
        static FlywayOutOfOrderGuard platformFlywayOutOfOrderGuard(Environment environment) {
            return new FlywayOutOfOrderGuard(environment);
        }

        @Bean
        @ConditionalOnMissingBean(name = "platformOracleFlywayConnectionDefaults")
        static OracleFlywayConnectionDefaults platformOracleFlywayConnectionDefaults() {
            return new OracleFlywayConnectionDefaults();
        }
    }

    /*
     * Security-aware auditor: contributed only when CurrentUserAccessor is on the classpath. Uses an
     * ObjectProvider so an absent accessor bean falls back to "system".
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CurrentUserAccessor.class)
    static class SecurityAuditorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "platformAuditorAware")
        AuditorAware<String> platformAuditorAware(ObjectProvider<CurrentUserAccessor> accessors) {
            return new AuditorAwareProvider(accessors.getIfAvailable());
        }
    }
}
