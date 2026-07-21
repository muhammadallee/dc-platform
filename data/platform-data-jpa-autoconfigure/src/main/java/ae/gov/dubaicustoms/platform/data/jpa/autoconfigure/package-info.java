/**
 * Auto-configuration for the JPA persistence capability: enables Spring Data JPA auditing, defaults
 * Hibernate to platform conventions, registers the {@code Money}/{@code CorrelationId} attribute
 * converters, and guards startup against a missing Flyway.
 *
 * <p>Entry point {@link ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration};
 * bound properties in {@link ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.DataJpaProperties}
 * ({@code dc.platform.data.jpa.*}). Implementation details live under {@code .internal}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;
