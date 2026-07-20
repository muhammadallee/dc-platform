package ae.gov.dubaicustoms.platform.validation.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.validation.NotBlankTrimmed;
import ae.gov.dubaicustoms.platform.validation.Ulid;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/** The mandatory ContextRunner matrix plus message-resolution and method-validation behavior. */
class PlatformValidationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformValidationAutoConfiguration.class));

    record Sample(@NotBlankTrimmed String name) {
    }

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasBean("platformValidator");
            assertThat(context).hasSingleBean(MethodValidationPostProcessor.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.validation.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("platformValidator");
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserValidatorPresent() {
        runner.withUserConfiguration(UserValidatorConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean("platformValidator"));
    }

    @Test
    void inactiveWhenValidationApiMissing() {
        runner.withClassLoader(new FilteredClassLoader(Ulid.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PlatformValidationAutoConfiguration.class));
    }

    @Test
    void messagesResolveThroughThePlatformBundle() {
        runner.run(context -> {
            Validator validator = context.getBean(Validator.class);
            Set<ConstraintViolation<Sample>> violations = validator.validate(new Sample("  "));
            assertThat(violations).singleElement()
                    .extracting(ConstraintViolation::getMessage)
                    .isEqualTo("must contain non-whitespace characters");
        });
    }

    @Test
    void methodValidationIsOn() {
        runner.withUserConfiguration(GuardedServiceConfiguration.class)
                .run(context -> {
                    GuardedService service = context.getBean(GuardedService.class);
                    assertThatExceptionOfType(ConstraintViolationException.class)
                            .isThrownBy(() -> service.lookup(" "));
                });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class UserValidatorConfiguration {
        @org.springframework.context.annotation.Bean
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean myValidator() {
            return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        }
    }

    @Validated
    static class GuardedService {
        String lookup(@NotBlankTrimmed String name) {
            return name;
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class GuardedServiceConfiguration {
        @org.springframework.context.annotation.Bean
        GuardedService guardedService() {
            return new GuardedService();
        }
    }
}
