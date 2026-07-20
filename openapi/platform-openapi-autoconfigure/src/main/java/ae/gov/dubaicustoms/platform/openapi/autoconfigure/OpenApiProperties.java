package ae.gov.dubaicustoms.platform.openapi.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the OpenAPI capability. Bound from {@code dc.platform.openapi.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformOpenApiAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). {@code title} and {@code version} fall
 * back to {@code spring.application.name} / {@code info.app.version} respectively when left
 * blank — resolved when the {@code OpenAPI} bean is built, not as a property placeholder, so the
 * fallback also applies when the key is present but empty.
 *
 * @param enabled master kill switch for the whole capability
 * @param title document title; blank defaults to {@code spring.application.name}
 * @param version document version; blank defaults to {@code info.app.version}, then {@code dev}
 * @param securityScheme the authentication scheme documented on every operation
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.openapi")
public record OpenApiProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Document title; blank defaults to spring.application.name. */
        @DefaultValue("") String title,
        /** Document version; blank defaults to info.app.version, then "dev". */
        @DefaultValue("") String version,
        /** Authentication scheme documented on every operation. */
        @DefaultValue("BEARER_JWT") SecurityScheme securityScheme) {

    /**
     * The authentication scheme documented on every operation.
     *
     * @since 0.2.0
     */
    public enum SecurityScheme {
        /** Bearer JWT (the platform default): {@code Authorization: Bearer <token>}. */
        BEARER_JWT,
        /** No security scheme documented (public or internal APIs). */
        NONE
    }
}
