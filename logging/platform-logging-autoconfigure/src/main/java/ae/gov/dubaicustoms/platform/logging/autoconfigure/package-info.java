/**
 * Auto-configuration for the logging capability. Two registrations in {@code META-INF/spring.factories} run before
 * the application context: {@code PlatformLoggingEnvironmentPostProcessor} switches on Boot's structured JSON logging
 * with the platform's field names (console fallback for the {@code local} profile), and the internal
 * {@code PlatformLogMasking} customizer masks credentials and personal data in every JSON line. The auto-configuration
 * adds the capability banner line and a WARN when the service's own Logback file replaces Boot's structured logging.
 *
 * <p>Kill switch: {@code dc.platform.logging.enabled}. Back-off: any {@code logging.structured.*} value the service
 * sets outranks the platform defaults property source; a service adds its own customizer next to the platform's.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.logging.autoconfigure;
