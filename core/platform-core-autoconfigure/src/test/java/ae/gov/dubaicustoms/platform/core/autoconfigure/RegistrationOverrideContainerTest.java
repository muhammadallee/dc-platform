package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * Phase-01 AC16 in a STARTED servlet container (D98): a consumer's own correlation filter or filter
 * registration — under any bean name — replaces the platform's. With a consumer registration (here:
 * disabled) the container holds no CorrelationIdFilter at all, not a second platform one; with a
 * consumer filter bean, Boot registers that filter and it still runs on REQUEST, ASYNC and ERROR
 * dispatches.
 */
class RegistrationOverrideContainerTest {

    private static final Set<DispatcherType> SEEN = ConcurrentHashMap.newKeySet();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class RegistrationApp {

        @Bean
        FilterRegistrationBean<CorrelationIdFilter> myCorrelationRegistration() {
            FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
            mine.setEnabled(false);
            return mine;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DispatchController.class)
    static class FilterApp {

        @Bean
        CorrelationIdFilter myCorrelationFilter() {
            return new CorrelationIdFilter(Tracer.NOOP) {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    SEEN.add(request.getDispatcherType());
                    super.doFilterInternal(request, response, chain);
                }
            };
        }
    }

    @RestController
    static class DispatchController {

        @GetMapping("/callable")
        Callable<String> callable() {
            return () -> "ok";
        }

        @GetMapping("/conflict")
        void conflict(HttpServletResponse response) throws IOException {
            response.sendError(409);
        }
    }

    @Test
    void aConsumerRegistrationLeavesNoPlatformFilterInTheContainer() {
        try (ConfigurableApplicationContext context = new SpringApplication(RegistrationApp.class)
                .run("--server.port=0", "--spring.main.banner-mode=off")) {
            ServletContext servletContext = ((WebApplicationContext) context).getServletContext();
            Map<String, ? extends FilterRegistration> registrations = servletContext.getFilterRegistrations();
            assertThat(registrations.values())
                    .noneMatch(registration -> CorrelationIdFilter.class.getName().equals(registration.getClassName()));
            assertThat(context.containsBean("platformCorrelationFilterRegistration")).isFalse();
        }
    }

    @Test
    void aConsumerFilterBeanRunsOnEveryDispatchInsteadOfThePlatformOne() throws Exception {
        SEEN.clear();
        try (ConfigurableApplicationContext context = new SpringApplication(FilterApp.class)
                .run("--server.port=0", "--spring.main.banner-mode=off")) {
            assertThat(context.containsBean("platformCorrelationFilterRegistration")).isFalse();
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient http = HttpClient.newHttpClient();
            for (String path : new String[] {"/callable", "/conflict"}) {
                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                        HttpResponse.BodyHandlers.discarding());
            }
            assertThat(SEEN).contains(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        }
    }
}
