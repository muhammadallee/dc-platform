package ae.gov.dubaicustoms.platform.security.autoconfigure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the security capability. Bound from {@code dc.platform.security.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformSecurityAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). The issuer, JWKS location, audiences and
 * algorithms are Spring's own {@code spring.security.oauth2.resourceserver.jwt.*} keys.
 *
 * @param enabled master kill switch for the whole capability
 * @param mode {@link Mode#RESOURCE_SERVER} builds the baseline JWT resource-server chain;
 *        {@link Mode#DISABLED} leaves security entirely to the application (explicit only — never
 *        implied by a profile, so an accidental local-profile activation never disables security)
 * @param permitPaths request patterns open without authentication
 * @param allowPartialChains accept consumer filter chains that leave some requests unmatched, which
 *        Spring Security would then let through unauthenticated
 * @param jwt token validation settings
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
        @DefaultValue({"/actuator/health/liveness", "/actuator/health/readiness"}) List<String> permitPaths,
        /** Accept consumer filter chains that leave some requests unmatched and so unauthenticated. */
        @DefaultValue("false") boolean allowPartialChains,
        /** Token validation settings. */
        @DefaultValue Jwt jwt) {

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

    /**
     * Token validation settings beyond Spring's own resource-server keys.
     *
     * @param clockSkew tolerance on {@code exp} and {@code nbf}; at most 5 minutes
     * @param allowInsecureJwks accept a JWKS URL that is neither https nor loopback
     * @param resourceClient the {@code resource_access} entry roles are read from; empty means
     *        {@code spring.application.name}
     * @param jwks fetching and caching of the signing keys
     * @since 1.0.0
     */
    public record Jwt(
            /** Tolerance on exp and nbf; at most 5 minutes. */
            @DefaultValue("30s") Duration clockSkew,
            /** Accept a JWKS URL that is neither https nor loopback. */
            @DefaultValue("false") boolean allowInsecureJwks,
            /** The resource_access entry roles are read from; empty means spring.application.name. */
            @DefaultValue("") String resourceClient,
            /** Fetching and caching of the signing keys. */
            @DefaultValue Jwks jwks) {
    }

    /**
     * Fetching and caching of the signing keys.
     *
     * @param connectTimeout connection timeout of one fetch
     * @param readTimeout read timeout of one fetch
     * @param cacheTtl how long fetched keys are used before a refresh, so how long a removed key lasts
     * @param refreshCooldown least time between fetches, so an unknown key id cannot hammer the provider
     * @param outageTolerance how long past {@code cacheTtl} cached keys keep working while fetches fail
     * @since 1.0.0
     */
    public record Jwks(
            /** Connection timeout of one fetch. */
            @DefaultValue("2s") Duration connectTimeout,
            /** Read timeout of one fetch. */
            @DefaultValue("2s") Duration readTimeout,
            /** How long fetched keys are used before a refresh. */
            @DefaultValue("5m") Duration cacheTtl,
            /** Least time between fetches. */
            @DefaultValue("30s") Duration refreshCooldown,
            /** How long past cache-ttl cached keys keep working while fetches fail. */
            @DefaultValue("1h") Duration outageTolerance) {
    }
}
