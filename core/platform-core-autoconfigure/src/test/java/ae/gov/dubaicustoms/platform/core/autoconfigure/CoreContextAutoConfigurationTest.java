package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * The mandatory 5-case ContextRunner matrix for CoreContextAutoConfiguration, plus the ordering
 * contract, the real-override contract (plan §7.1) and the config-default snapshot (plan §7.2).
 */
class CoreContextAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoreContextAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CorrelationIdFilter.class);
            assertThat(context).hasBean("platformCorrelationFilterRegistration");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.core.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
                });
    }

    @Test
    void backsOffWhenUserFilterPresent() {
        CorrelationIdFilter mine = new CorrelationIdFilter(Tracer.NOOP);
        runner.withBean("mine", CorrelationIdFilter.class, () -> mine)
                .run(context -> {
                    assertThat(context).getBean(CorrelationIdFilter.class).isSameAs(mine);
                    // The platform registration wraps the consumer's filter instead of a second one.
                    assertThat(context.getBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class)
                            .getFilter()).isSameAs(mine);
                });
    }

    @Test
    void backsOffWhenUserRegistrationPresent() {
        FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
        runner.withBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class, () -> mine)
                .run(context -> assertThat(context.getBean("platformCorrelationFilterRegistration")).isSameAs(mine));
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
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                });
    }

    @Test
    void inactiveOutsideWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoreContextAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
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
        });
    }

    @Test
    void removedCorrelationPropertiesAreIgnored() {
        runner.withPropertyValues("dc.platform.core.correlation.header-name=X-Trace",
                        "dc.platform.core.correlation.generate-if-missing=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CorrelationIdFilter.class);
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
}
