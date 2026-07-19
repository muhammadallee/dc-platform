package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/*
 * Activates when: servlet web application AND platform-core-api on the classpath
 *                 AND dc.platform.core.enabled != false
 * Backs off when: user defines a CorrelationIdFilter bean (the registration then wraps theirs),
 *                 or a FilterRegistrationBean named platformCorrelationFilterRegistration
 * Beans: correlationIdFilter — reads/generates the correlation header, opens RequestContext,
 *                              echoes the header on the response, always closes;
 *        platformCorrelationFilterRegistration — registers the filter for all URLs at
 *                              Ordered.HIGHEST_PRECEDENCE
 * Order: filter precedence is HIGHEST because it must wrap security and logging — their MDC
 *        reads and log lines must already carry the correlation id.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(CorrelationId.class)
@ConditionalOnProperty(prefix = "dc.platform.core", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CoreProperties.class)
public class CoreContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter correlationIdFilter(CoreProperties properties) {
        return new CorrelationIdFilter(
                properties.correlation().headerName(),
                properties.correlation().generateIfMissing());
    }

    @Bean
    @ConditionalOnMissingBean(name = "platformCorrelationFilterRegistration")
    FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
