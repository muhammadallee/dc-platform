package ae.gov.dubaicustoms.platform.openapi.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.openapi.autoconfigure.internal.ProblemDetailOpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: springdoc's OpenAPI/OpenApiCustomizer on the classpath AND
 *                 dc.platform.openapi.enabled != false
 * Backs off when: user defines an OpenAPI bean
 * Beans: platformOpenApi — info/servers/bearer-jwt security scheme (title/version default to
 *                          spring.application.name / info.app.version when left blank);
 *        platformProblemDetailOpenApiCustomizer — appends the platform ProblemDetail schema and
 *                                                 default 4xx/5xx responses to every operation
 *                                                 (errors slice, phase 4);
 *        openapiCapabilityDescriptor — reports openapi[ACTIVE] into the banner
 * Order: none — springdoc applies OpenApiCustomizer beans to the OpenAPI bean it discovers at
 *        request time; no ordering dependency on other platform auto-configurations.
 */
@AutoConfiguration
@ConditionalOnClass({OpenAPI.class, OpenApiCustomizer.class})
@ConditionalOnProperty(prefix = "dc.platform.openapi", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OpenApiProperties.class)
public class PlatformOpenApiAutoConfiguration {

    /** Security-scheme bean name referenced by {@link #platformOpenApi}'s security requirement. */
    static final String BEARER_JWT_SCHEME = "bearer-jwt";

    @Bean
    @ConditionalOnMissingBean
    OpenAPI platformOpenApi(OpenApiProperties properties, Environment environment) {
        String title = properties.title().isBlank()
                ? environment.getProperty("spring.application.name", "service") : properties.title();
        String version = properties.version().isBlank()
                ? environment.getProperty("info.app.version", "dev") : properties.version();

        OpenAPI openApi = new OpenAPI().info(new Info().title(title).version(version));
        if (properties.securityScheme() == OpenApiProperties.SecurityScheme.BEARER_JWT) {
            openApi.components(new io.swagger.v3.oas.models.Components().addSecuritySchemes(
                            BEARER_JWT_SCHEME, new SecurityScheme()
                                    .type(SecurityScheme.Type.HTTP)
                                    .scheme("bearer")
                                    .bearerFormat("JWT")))
                    .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT_SCHEME));
        }
        return openApi;
    }

    @Bean
    @ConditionalOnMissingBean(name = "platformProblemDetailOpenApiCustomizer")
    OpenApiCustomizer platformProblemDetailOpenApiCustomizer() {
        return new ProblemDetailOpenApiCustomizer();
    }

    @Bean
    CapabilityDescriptor openapiCapabilityDescriptor() {
        return new CapabilityDescriptor("openapi", "ACTIVE", "springdoc");
    }
}
