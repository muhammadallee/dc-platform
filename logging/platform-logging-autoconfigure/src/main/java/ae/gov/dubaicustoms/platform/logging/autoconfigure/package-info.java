/**
 * Auto-configuration for the logging capability: the {@code EnvironmentPostProcessor} that points
 * Boot's logging system at the platform JSON logback configuration (with a console fallback for
 * the {@code local} profile) and the capability banner registration.
 *
 * <p>Kill switch: {@code dc.platform.logging.enabled}. Back-off: set {@code logging.config}
 * yourself — user configuration always outranks the platform defaults property source.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.logging.autoconfigure;
