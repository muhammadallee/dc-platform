package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import java.time.Duration;
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
 * @param oracle the Oracle driver's socket read timeouts (D110)
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.data.jpa")
public record DataJpaProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Fail startup when JPA is present but Flyway is not; set false to opt out of migrations. */
        @DefaultValue("true") boolean requireMigrations,
        /** Oracle driver read timeouts for the pool and for Flyway's own connection. */
        @DefaultValue Oracle oracle) {

    /**
     * The Oracle driver's socket read timeout ({@code oracle.jdbc.ReadTimeout}), the backstop for what a
     * statement cancel cannot stop (a dead network, a PL/SQL or lock wait). It bounds each wait for the
     * database's answer, so a statement the database takes longer than this to answer fails, whatever its
     * transaction timeout. A {@code data-source-properties} entry or a JVM system property of that name still
     * wins.
     *
     * @param readTimeout read timeout on every connection of an Oracle pool: the longest the driver waits for
     *        an answer; above the 30 s transaction default, so a slow statement is cancelled by that
     *        first. Raise it for a service whose transactions are longer
     * @param flywayReadTimeout read timeout on Flyway's own connection ({@code spring.flyway.user} or
     *        {@code url}), so a long migration statement (an index build) can finish while a silent database
     *        still fails startup
     * @since 1.0.0
     */
    public record Oracle(
            /** Read timeout on an Oracle pool's connections: the longest the driver waits for an answer. */
            @DefaultValue("60s") Duration readTimeout,
            /** Read timeout on Flyway's own Oracle connection (a separate migration user or URL). */
            @DefaultValue("10m") Duration flywayReadTimeout) {

        /** Rejects a negative timeout; zero switches the bound off (the driver waits indefinitely). */
        public Oracle {
            if (readTimeout.isNegative() || flywayReadTimeout.isNegative()) {
                throw new IllegalArgumentException("dc.platform.data.jpa.oracle read timeouts must not be negative");
            }
        }
    }
}
