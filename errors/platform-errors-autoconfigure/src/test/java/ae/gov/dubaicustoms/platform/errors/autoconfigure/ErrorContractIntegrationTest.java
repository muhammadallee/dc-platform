package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.errors.ProblemDetailCustomizer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The error contract through a real embedded server (phase-01 R3.1): the servlet error dispatch
 * ({@code sendError} from a filter, an exception escaping the filter chain) and framework rejections all
 * render as {@code application/problem+json} with the platform extensions, and every customizer gets a
 * non-null {@code source}.
 */
class ErrorContractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ConcurrentLinkedQueue<String> CUSTOMIZER_SOURCES = new ConcurrentLinkedQueue<>();

    private static ConfigurableApplicationContext context;
    private static int port;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        FilterRegistrationBean<Filter> rejectingFilter() {
            Filter filter = (request, response, chain) -> {
                String path = ((HttpServletRequest) request).getRequestURI();
                if (path.equals("/duplicate")) {
                    ((HttpServletResponse) response).sendError(409);
                    return;
                }
                if (path.equals("/filter-crash")) {
                    throw new IllegalStateException("filter reached db-secret-host:5432");
                }
                chain.doFilter(request, response);
            };
            return new FilterRegistrationBean<>(filter);
        }

        // Dereferences the source on every call: a null source would fail the response.
        @Bean
        ProblemDetailCustomizer sourceRecordingCustomizer() {
            return (detail, source) -> CUSTOMIZER_SOURCES.add(source.getClass().getSimpleName());
        }

        @RestController
        static class Endpoints {

            @GetMapping("/ok")
            String ok() {
                return "ok";
            }
        }
    }

    @BeforeAll
    static void start() {
        context = new SpringApplication(App.class).run("--server.port=0", "--spring.main.banner-mode=off");
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(String path, String accept, int expectedStatus) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (accept != null) {
            request.header("Accept", accept);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s: %s", path, response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        return JSON.readValue(response.body(), Map.class);
    }

    @Test
    void sendErrorFromAFilterIsAProblemResponseForJsonAndHtmlClients() throws Exception {
        for (String accept : new String[] {null, "application/json", "text/html"}) {
            Map<String, Object> body = call("/duplicate", accept, 409);
            assertThat(body).containsEntry("status", 409).containsEntry("code", "DC-CORE-0499")
                    .containsEntry("instance", "/duplicate").containsEntry("detail", "Conflict")
                    .containsEntry("retryable", false).containsKey("timestamp")
                    .containsEntry("type", "urn:dc:problem:DC-CORE-0499");
        }
    }

    @Test
    void anExceptionEscapingTheFilterChainIsAGeneric500() throws Exception {
        Map<String, Object> body = call("/filter-crash", "application/json", 500);
        assertThat(body).containsEntry("code", "DC-CORE-0500").containsEntry("instance", "/filter-crash")
                .containsEntry("detail", "An unexpected error occurred.");
        assertThat(body.toString()).doesNotContain("db-secret-host");
    }

    @Test
    void frameworkRejectionsReachCustomizersWithTheirException() throws Exception {
        CUSTOMIZER_SOURCES.clear();
        Map<String, Object> body = call("/no-such-endpoint", "application/json", 404);
        assertThat(body).containsEntry("code", "DC-CORE-0499");
        assertThat(CUSTOMIZER_SOURCES).isNotEmpty().doesNotContainNull();
    }

    @Test
    void theConsumerCanStillReplaceTheErrorPage() {
        assertThat(context.getBeansOfType(org.springframework.boot.webmvc.error.ErrorController.class))
                .as("the platform controller replaces Boot's BasicErrorController")
                .hasSize(1).allSatisfy((name, bean) -> assertThat(bean).isInstanceOf(PlatformErrorController.class));
        List<String> names = List.of(context.getBeanNamesForType(PlatformErrorController.class));
        assertThat(names).containsExactly("platformErrorController");
    }

    @Test
    void aDirectRequestToTheErrorPathStillHasAnInstance() throws Exception {
        Map<String, Object> body = call("/error", "application/json", 500);
        assertThat(body).containsEntry("instance", "/error").containsEntry("code", "DC-CORE-0500");
    }
}
