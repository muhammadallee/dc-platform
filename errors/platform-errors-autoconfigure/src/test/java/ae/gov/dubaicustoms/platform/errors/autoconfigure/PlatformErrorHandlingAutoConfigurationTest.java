package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.errors.ProblemDetailCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The mandatory 5-case ContextRunner matrix for PlatformErrorHandlingAutoConfiguration. */
class PlatformErrorHandlingAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformErrorHandlingAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasBean("platformExceptionHandler");
            assertThat(context).hasBean("platformValidationExceptionHandler");
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.errors.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        runner.withBean("platformExceptionHandler", Object.class, Object::new)
                .run(context -> assertThat(context).doesNotHaveBean(PlatformExceptionHandler.class));
    }

    @Test
    void backsOffWhenUserValidationBeanPresent() {
        runner.withBean("platformValidationExceptionHandler", Object.class, Object::new)
                .run(context -> assertThat(context).doesNotHaveBean(PlatformConstraintViolationHandler.class));
    }

    @Test
    void inactiveWhenErrorsApiMissing() {
        runner.withClassLoader(new FilteredClassLoader(ProblemDetailCustomizer.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformErrorHandlingAutoConfiguration.class));
    }

    @Test
    void inactiveOutsideWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformErrorHandlingAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformExceptionHandler.class));
    }

    @Test
    void mapValidationSwitchDisablesConstraintAdvice() {
        runner.withPropertyValues("dc.platform.errors.map-validation=false")
                .run(context -> {
                    assertThat(context).hasBean("platformExceptionHandler");
                    assertThat(context).doesNotHaveBean(PlatformConstraintViolationHandler.class);
                });
    }

    @Test
    void consumerSubclassReplacesThePlatformAdvice() {
        // Plan §7.1: a real override, not annotation presence — the consumer's handler is the one
        // in the context and the platform's is not created alongside it.
        ErrorsProperties properties = new ErrorsProperties(true, false, "https://problems.example/", true, false);
        PlatformExceptionHandler mine = new PlatformExceptionHandler(properties,
                new ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory(properties,
                        java.util.List.of(), java.time.Clock.systemUTC())) {
        };
        runner.withBean("platformExceptionHandler", PlatformExceptionHandler.class, () -> mine)
                .run(context -> {
                    assertThat(context).getBeans(PlatformExceptionHandler.class).hasSize(1);
                    assertThat(context.getBean(PlatformExceptionHandler.class)).isSameAs(mine);
                });
    }

    @Test
    void configurationDefaultsSnapshot() {
        // Plan §7.2: a changed default fails here until the snapshot is consciously updated and the
        // change is recorded in the phase changelog. include-rejected-values=false is new in phase 1.
        runner.run(context -> assertThat(context.getBean(ErrorsProperties.class))
                .isEqualTo(new ErrorsProperties(true, false, "https://errors.dc.com/", true, false)));
    }

    @Test
    void propertiesBindFromKebabKeys() {
        runner.withPropertyValues(
                        "dc.platform.errors.include-stacktrace=true",
                        "dc.platform.errors.type-base-uri=https://problems.example/")
                .run(context -> {
                    ErrorsProperties properties = context.getBean(ErrorsProperties.class);
                    assertThat(properties.includeStacktrace()).isTrue();
                    assertThat(properties.typeBaseUri()).isEqualTo("https://problems.example/");
                    assertThat(properties.mapValidation()).isTrue();
                });
    }
}
