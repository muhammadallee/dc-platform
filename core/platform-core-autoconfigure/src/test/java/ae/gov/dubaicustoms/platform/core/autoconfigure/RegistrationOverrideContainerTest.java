package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.WebApplicationContext;

/**
 * Phase-01 AC16 in a STARTED servlet container: a consumer's own (here: disabled) registration named
 * {@code platformCorrelationFilterRegistration} is the only thing registered — the container holds no
 * CorrelationIdFilter at all, not a second auto-registered platform one.
 */
class RegistrationOverrideContainerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration() {
            FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
            mine.setEnabled(false);
            return mine;
        }
    }

    @Test
    void theContainerHoldsNoPlatformCorrelationFilter() {
        try (ConfigurableApplicationContext context = new SpringApplication(App.class)
                .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            ServletContext servletContext = ((WebApplicationContext) context).getServletContext();
            Map<String, ? extends FilterRegistration> registrations = servletContext.getFilterRegistrations();
            assertThat(registrations.values())
                    .noneMatch(registration -> CorrelationIdFilter.class.getName().equals(registration.getClassName()));
            assertThat(context.getBeansOfType(CorrelationIdFilter.class)).isEmpty();
        }
    }
}
