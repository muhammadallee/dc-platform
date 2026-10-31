package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Regression (found by the phase-01 consumer run): an anonymous request on a permitted path whose
 * filter answers with sendError(409) must keep its 409 — the servlet ERROR dispatch to /error must not
 * be re-authorized into a 401. Real embedded server: MockMvc never performs an ERROR dispatch.
 */
class ErrorDispatchSecurityTest {

    private static ConfigurableApplicationContext context;
    private static int port;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        SecurityCustomizer permitDuplicatePath() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/dup").permitAll());
        }

        @Bean
        FilterRegistrationBean<Filter> rejectingFilter() {
            Filter filter = (request, response, chain) -> {
                if ("/dup".equals(((HttpServletRequest) request).getRequestURI())) {
                    ((HttpServletResponse) response).sendError(409);
                    return;
                }
                chain.doFilter(request, response);
            };
            return new FilterRegistrationBean<>(filter);
        }
    }

    @BeforeAll
    static void start() {
        context = new SpringApplication(App.class).run("--server.port=0", "--spring.main.banner-mode=off",
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.test/jwks.json");
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @Test
    void anonymousErrorDispatchKeepsTheOriginalStatus() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dup")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
    }

    @Test
    void protectedPathsStillRequireAuthentication() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/anything")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
