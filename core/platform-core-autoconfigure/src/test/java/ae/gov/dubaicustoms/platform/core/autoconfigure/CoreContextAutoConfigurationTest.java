package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/** The mandatory 5-case ContextRunner matrix for CoreContextAutoConfiguration. */
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
                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
    }

    @Test
    void backsOffWhenUserFilterPresent() {
        CorrelationIdFilter mine = new CorrelationIdFilter("X-Mine", false);
        runner.withBean("mine", CorrelationIdFilter.class, () -> mine)
                .run(context -> assertThat(context).getBean(CorrelationIdFilter.class).isSameAs(mine));
    }

    @Test
    void inactiveWhenCoreApiMissing() {
        runner.withClassLoader(new FilteredClassLoader(CorrelationId.class))
                .run(context -> assertThat(context).doesNotHaveBean(CoreContextAutoConfiguration.class));
    }

    @Test
    void inactiveOutsideWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoreContextAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
    }

    @Test
    void filterRegisteredAtHighestPrecedence() {
        // Ordering case of the matrix: the filter must wrap security and logging.
        runner.run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        });
    }

    @Test
    void headerNameIsConfigurable() {
        runner.withPropertyValues("dc.platform.core.correlation.header-name=X-Trace")
                .run(context -> assertThat(context.getBean(CoreProperties.class).correlation().headerName())
                        .isEqualTo("X-Trace"));
    }
}
