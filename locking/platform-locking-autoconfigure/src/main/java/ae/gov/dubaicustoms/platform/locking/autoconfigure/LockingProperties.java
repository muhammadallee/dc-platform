package ae.gov.dubaicustoms.platform.locking.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the locking capability. Bound from {@code dc.platform.locking.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformLockingAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). The provider is chosen by what is on the
 * classpath (Redis over JDBC), not by a property, so this record carries only the kill switch.
 *
 * @param enabled master kill switch for the whole capability
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.locking")
public record LockingProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled) {
}
