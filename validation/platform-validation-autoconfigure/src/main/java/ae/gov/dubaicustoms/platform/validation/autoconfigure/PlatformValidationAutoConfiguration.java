package ae.gov.dubaicustoms.platform.validation.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.validation.Ulid;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableValidator;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.beanvalidation.FilteredMethodValidationPostProcessor;
import org.springframework.boot.validation.beanvalidation.MethodValidationExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/*
 * Activates when: platform-validation-api + jakarta.validation on the classpath AND a Bean
 *                 Validation provider present (ValidationProvider service file, as Boot checks)
 *                 AND dc.platform.validation.enabled != false
 * Backs off when: user defines a jakarta.validation.Validator bean (validator + method
 *                 validation both stand down), or their own MethodValidationPostProcessor
 * Beans: platformValidator — LocalValidatorFactoryBean interpolating messages through
 *                 platform-validation-messages.properties (then contributor/default bundles);
 *        platformMethodValidationPostProcessor — method validation ON using that validator;
 *        validationCapabilityDescriptor — one line in the startup capability banner
 * Order: beforeName Boot's ValidationAutoConfiguration (classic and Boot-4 module locations) so
 *        the platform validator wins the @ConditionalOnMissingBean race and Boot's backs off.
 */
@AutoConfiguration(beforeName = {
    "org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration",
    "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration"})
@ConditionalOnClass({Ulid.class, ExecutableValidator.class})
@ConditionalOnResource(resources = "classpath:META-INF/services/jakarta.validation.spi.ValidationProvider")
@ConditionalOnProperty(prefix = "dc.platform.validation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ValidationProperties.class)
public class PlatformValidationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Validator.class)
    public static LocalValidatorFactoryBean platformValidator() {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        // Platform bundle first; missing keys fall through to ContributorValidationMessages /
        // ValidationMessages / hibernate defaults, so built-in constraint texts are untouched.
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("classpath:platform-validation-messages");
        messages.setDefaultEncoding("UTF-8");
        factory.setValidationMessageSource(messages);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean(MethodValidationPostProcessor.class)
    public static MethodValidationPostProcessor platformMethodValidationPostProcessor(
            ObjectProvider<Validator> validator, ObjectProvider<MethodValidationExcludeFilter> excludeFilters) {
        // Static bean method: BeanPostProcessors must instantiate before regular configuration
        // beans or the ones created earlier escape validation. Filtered like Boot's own:
        // @ConfigurationProperties records are final and must not be CGLIB-proxied.
        FilteredMethodValidationPostProcessor processor = new FilteredMethodValidationPostProcessor(
                Stream.concat(excludeFilters.orderedStream(),
                        Stream.of(MethodValidationExcludeFilter.byAnnotation(ConfigurationProperties.class))));
        processor.setValidatorProvider(validator);
        return processor;
    }

    @Bean
    CapabilityDescriptor validationCapabilityDescriptor() {
        return new CapabilityDescriptor("validation", "ACTIVE", "jakarta");
    }
}
