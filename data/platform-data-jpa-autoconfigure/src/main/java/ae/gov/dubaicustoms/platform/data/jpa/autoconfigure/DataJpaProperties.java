package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the JPA persistence capability. Bound from {@code dc.platform.data.jpa.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformDataJpaAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). The Hibernate tuning defaults (batch size,
 * jdbc time zone, open-in-view) are contributed as standard {@code spring.jpa.*} environment
 * defaults by {@code PlatformDataJpaEnvironmentPostProcessor}, so they are overridden with Boot's
 * own keys rather than duplicated here.
 *
 * @param enabled master kill switch for the whole capability
 * @param requireMigrations fail startup when JPA is configured but Flyway is absent; set to
 *        {@code false} to run JPA without managed migrations
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.data.jpa")
public record DataJpaProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Fail startup when JPA is present but Flyway is not; set false to opt out of migrations. */
        @DefaultValue("true") boolean requireMigrations) {
}
