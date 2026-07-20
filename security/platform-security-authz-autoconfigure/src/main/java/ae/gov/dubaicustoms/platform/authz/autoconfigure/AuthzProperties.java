package ae.gov.dubaicustoms.platform.authz.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the authz capability. Bound from {@code dc.platform.authz.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformAuthzAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param rolesClaim the {@link ae.gov.dubaicustoms.platform.security.CurrentUser#claims()} key the
 *        default provider reads permissions from
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.authz")
public record AuthzProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Claim key the default provider reads permissions/roles from. */
        @DefaultValue("roles") String rolesClaim) {
}
