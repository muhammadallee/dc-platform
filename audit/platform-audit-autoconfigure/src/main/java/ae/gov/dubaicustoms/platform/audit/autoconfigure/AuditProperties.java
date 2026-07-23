package ae.gov.dubaicustoms.platform.audit.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the audit capability. Bound from {@code dc.platform.audit.*}.
 *
 * <p>Immutable; validated at startup. Registered via {@code @EnableConfigurationProperties} (never
 * scanned). The active sink is chosen by classpath/beans (messaging &rarr; jdbc &rarr; log), not by a
 * property; this record carries the kill switch and the async worker's bounded-queue capacity.
 *
 * @param enabled master kill switch for the whole capability
 * @param queueCapacity bound on the in-memory queue feeding the audit worker; events offered when the
 *     queue is full are dropped with a WARN so auditing never blocks or slows the request thread
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.audit")
public record AuditProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Capacity of the bounded queue feeding the async audit worker; full-queue offers are dropped. */
        @DefaultValue("1000") int queueCapacity) {
}
