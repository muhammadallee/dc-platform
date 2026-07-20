package ae.gov.dubaicustoms.platform.openapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ContextRunner matrix + bean-shape behavior for {@link PlatformOpenApiAutoConfiguration}. */
class PlatformOpenApiAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformOpenApiAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OpenAPI.class);
            assertThat(ctx).hasSingleBean(OpenApiCustomizer.class);
            assertThat(ctx).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.openapi.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OpenAPI.class));
    }

    @Test
    void backsOffWhenUserOpenApiBeanPresent() {
        OpenAPI mine = new OpenAPI();
        runner.withBean("myOpenApi", OpenAPI.class, () -> mine)
                .run(ctx -> assertThat(ctx).getBean(OpenAPI.class).isSameAs(mine));
    }

    @Test
    void backsOffWhenUserCustomizerBeanPresent() {
        OpenApiCustomizer mine = openApi -> { };
        runner.withBean("platformProblemDetailOpenApiCustomizer", OpenApiCustomizer.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean("platformProblemDetailOpenApiCustomizer"))
                        .isSameAs(mine));
    }

    @Test
    void inactiveWhenSpringdocMissing() {
        runner.withClassLoader(new FilteredClassLoader(OpenAPI.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PlatformOpenApiAutoConfiguration.class));
    }

    @Test
    void titleAndVersionDefaultToApplicationIdentity() {
        runner.withPropertyValues("spring.application.name=orders", "info.app.version=1.2.3")
                .run(ctx -> {
                    OpenAPI openApi = ctx.getBean(OpenAPI.class);
                    assertThat(openApi.getInfo().getTitle()).isEqualTo("orders");
                    assertThat(openApi.getInfo().getVersion()).isEqualTo("1.2.3");
                });
    }

    @Test
    void explicitTitleAndVersionWin() {
        runner.withPropertyValues("dc.platform.openapi.title=Orders API",
                        "dc.platform.openapi.version=9.9.9", "spring.application.name=ignored")
                .run(ctx -> {
                    OpenAPI openApi = ctx.getBean(OpenAPI.class);
                    assertThat(openApi.getInfo().getTitle()).isEqualTo("Orders API");
                    assertThat(openApi.getInfo().getVersion()).isEqualTo("9.9.9");
                });
    }

    @Test
    void bearerJwtSchemeIsTheDefault() {
        runner.run(ctx -> {
            OpenAPI openApi = ctx.getBean(OpenAPI.class);
            SecurityScheme scheme = openApi.getComponents().getSecuritySchemes()
                    .get(PlatformOpenApiAutoConfiguration.BEARER_JWT_SCHEME);
            assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
            assertThat(scheme.getScheme()).isEqualTo("bearer");
            assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
            assertThat(openApi.getSecurity()).hasSize(1);
        });
    }

    @Test
    void securitySchemeNoneOmitsTheScheme() {
        runner.withPropertyValues("dc.platform.openapi.security-scheme=NONE")
                .run(ctx -> {
                    OpenAPI openApi = ctx.getBean(OpenAPI.class);
                    assertThat(openApi.getComponents()).isNull();
                    assertThat(openApi.getSecurity()).isNull();
                });
    }
}
