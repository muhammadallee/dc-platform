package ae.gov.dubaicustoms.platform.security.autoconfigure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the security capability. Bound from {@code dc.platform.security.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformSecurityAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param mode {@link Mode#RESOURCE_SERVER} builds the baseline JWT resource-server chain;
 *        {@link Mode#DISABLED} leaves security entirely to the application (explicit only — never
 *        implied by a profile, so an accidental local-profile activation never disables security)
 * @param permitPaths request patterns open without authentication
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.security")
public record SecurityProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** resource-server builds the baseline chain; disabled leaves security to the application. */
        @DefaultValue("resource-server") Mode mode,
        /** Request patterns open without authentication. */
        @DefaultValue({"/actuator/health", "/actuator/health/**", "/actuator/info",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"}) List<String> permitPaths) {

    /**
     * How the platform configures the baseline security chain.
     *
     * @since 0.2.0
     */
    public enum Mode {

        /** Stateless JWT resource server: the baseline {@code SecurityFilterChain} is built. */
        RESOURCE_SERVER,

        /** Security is left entirely to the application; the platform contributes no chain. */
        DISABLED
    }
}
