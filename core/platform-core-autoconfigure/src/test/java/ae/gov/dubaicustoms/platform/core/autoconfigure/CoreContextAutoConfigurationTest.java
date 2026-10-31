package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.RegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.GenericFilterBean;

/**
 * The mandatory 5-case ContextRunner matrix for CoreContextAutoConfiguration, plus the ordering
 * contract, the real-override contract by type (plan §7.1, D98) and the config-default snapshot
 * (plan §7.2).
 */
class CoreContextAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoreContextAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasBean("platformCorrelationFilterRegistration");
            assertThat(registeredCorrelationFilters(context)).hasSize(1);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.core.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                    assertThat(registeredCorrelationFilters(context)).isEmpty();
                });
    }

    @Test
    void backsOffWhenUserFilterPresentUnderAnyName() {
        runner.withUserConfiguration(UserFilter.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
                    // Boot registers the consumer's filter itself, at the platform's position.
                    CorrelationIdFilter mine = context.getBean("myCorrelationFilter", CorrelationIdFilter.class);
                    List<RegistrationBean> registrations = correlationRegistrations(context);
                    assertThat(registrations).hasSize(1);
                    assertThat(((FilterRegistrationBean<?>) registrations.get(0)).getFilter()).isSameAs(mine);
                    assertThat(registrations.get(0).getOrder()).isEqualTo(CoreContextAutoConfiguration.FILTER_ORDER);
                });
    }

    @Test
    void backsOffWhenUserRegistrationPresentUnderAnyName() {
        runner.withUserConfiguration(UserRegistration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
                    // The consumer's (disabled) registration is the only one: no second platform filter.
                    assertThat(registeredCorrelationFilters(context))
                            .containsExactly(context.getBean("myRegistration", FilterRegistrationBean.class).getFilter());
                });
    }

    @Test
    void unrelatedFilterRegistrationsDoNotSuppressThePlatformOne() {
        runner.withUserConfiguration(UnrelatedRegistration.class)
                .run(context -> {
                    assertThat(context).hasBean("platformCorrelationFilterRegistration");
                    assertThat(context).hasBean("unrelatedRegistration");
                    assertThat(registeredCorrelationFilters(context)).hasSize(1);
                });
    }

    @Test
    void inactiveWhenCoreApiMissing() {
        runner.withClassLoader(new FilteredClassLoader(CorrelationId.class))
                .run(context -> assertThat(context).doesNotHaveBean(CoreContextAutoConfiguration.class));
    }

    @Test
    void noFilterWithoutTracingOnTheClasspath() {
        runner.withClassLoader(new FilteredClassLoader(Tracer.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
                });
    }

    @Test
    void inactiveOutsideWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoreContextAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration"));
    }

    @Test
    void filterRunsAfterTheObservationFilterAndBeforeSecurity() {
        runner.run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class);
            // Boot 4.1 registers ServerHttpObservationFilter at HIGHEST_PRECEDENCE + 1; Spring
            // Security's chain defaults to -100 (SecurityFilterProperties.DEFAULT_FILTER_ORDER).
            assertThat(registration.getOrder())
                    .isEqualTo(CoreContextAutoConfiguration.FILTER_ORDER)
                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1)
                    .isLessThan(-100);
            assertThat(new CorrelationIdFilter(Tracer.NOOP).getOrder()).isEqualTo(registration.getOrder());
        });
    }

    @Test
    void removedCorrelationPropertiesAreIgnored() {
        runner.withPropertyValues("dc.platform.core.correlation.header-name=X-Trace",
                        "dc.platform.core.correlation.generate-if-missing=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("platformCorrelationFilterRegistration");
                });
    }

    @Test
    void configurationDefaultsSnapshot() {
        // Plan §7.2: a changed default fails here until the snapshot is consciously updated and the
        // change is recorded in the phase changelog.
        runner.run(context -> {
            CoreProperties properties = context.getBean(CoreProperties.class);
            assertThat(Map.of("enabled", properties.enabled(), "banner-enabled", properties.bannerEnabled()))
                    .isEqualTo(Map.of("enabled", true, "banner-enabled", true));
        });
    }

    /** What Boot would register with the servlet container for CorrelationIdFilter. */
    private static List<RegistrationBean> correlationRegistrations(ApplicationContext context) {
        List<RegistrationBean> found = new ArrayList<>();
        new ServletContextInitializerBeans(((ConfigurableApplicationContext) context).getBeanFactory())
                .forEach(initializer -> {
                    if (initializer instanceof FilterRegistrationBean<?> registration
                            && registration.getFilter() instanceof CorrelationIdFilter) {
                        found.add(registration);
                    }
                });
        return found;
    }

    private static List<Object> registeredCorrelationFilters(ApplicationContext context) {
        return correlationRegistrations(context).stream()
                .map(registration -> (Object) ((FilterRegistrationBean<?>) registration).getFilter())
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class UserFilter {

        @Bean
        CorrelationIdFilter myCorrelationFilter() {
            return new CorrelationIdFilter(Tracer.NOOP);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRegistration {

        @Bean
        FilterRegistrationBean<CorrelationIdFilter> myRegistration() {
            FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
            mine.setEnabled(false); // a consumer may even switch the filter off
            return mine;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrelatedRegistration {

        @Bean
        FilterRegistrationBean<GenericFilterBean> unrelatedRegistration() {
            return new FilterRegistrationBean<>(new GenericFilterBean() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                        throws IOException, ServletException {
                    chain.doFilter(request, response);
                }
            });
        }
    }
}
