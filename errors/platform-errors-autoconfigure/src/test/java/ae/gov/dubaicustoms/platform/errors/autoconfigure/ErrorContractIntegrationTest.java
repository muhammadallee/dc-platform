package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The error contract through a real embedded server with a real tracer: the servlet error dispatch ({@code sendError}
 * from a filter, an exception escaping the filter chain), framework rejections and service errors all render as
 * {@code application/problem+json} with the platform extensions, and a service's Spring
 * {@link ErrorResponse.Interceptor}s see every body together with the originating exception (D138, AC19–AC21).
 */
class ErrorContractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** One line per interceptor call: "interceptor instance cause-class-or-none code". */
    static final ConcurrentLinkedQueue<String> SEEN = new ConcurrentLinkedQueue<>();
    static final AtomicBoolean FIRST_THROWS = new AtomicBoolean();

    private static ConfigurableApplicationContext context;
    private static int port;

    record NewOrder(@NotBlank String name) {
    }

    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "order already shipped")
    static final class AlreadyShipped extends RuntimeException {
    }

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

        // Two configurers, each adding one interceptor: Spring composes them into one list, in order.
        @Bean
        @Order(1)
        WebMvcConfigurer firstInterceptor() {
            return new WebMvcConfigurer() {
                @Override
                public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
                    interceptors.add((detail, response) -> {
                        record("first", detail, response);
                        detail.setProperty("tenant", "dxb");
                        if (FIRST_THROWS.get()) {
                            throw new IllegalStateException("interceptor bug");
                        }
                    });
                }
            };
        }

        @Bean
        @Order(2)
        WebMvcConfigurer secondInterceptor() {
            return new WebMvcConfigurer() {
                @Override
                public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
                    interceptors.add((detail, response) -> record("second", detail, response));
                }
            };
        }

        private static void record(String name, ProblemDetail detail, ErrorResponse response) {
            Throwable cause = response instanceof ErrorResponseException exception ? exception.getCause() : null;
            SEEN.add(name + " " + detail.getInstance() + " " + (cause == null ? "none" : cause.getClass().getSimpleName())
                    + " " + detail.getProperties().get("code"));
        }

        @RestController
        static class Endpoints {

            @GetMapping("/ok")
            String ok() {
                return "ok";
            }

            @GetMapping("/service")
            String service() {
                ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "order 42 is already paid");
                body.setProperty("code", "ORD-0001");
                throw new ErrorResponseException(HttpStatus.CONFLICT, body, null);
            }

            @GetMapping("/service-5xx")
            String serviceServerError() {
                ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "ledger.internal:8443 refused");
                body.setProperty("code", "ORD-0503");
                throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, body, null);
            }

            @GetMapping("/status")
            String status() {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "quantity exceeds stock");
            }

            @GetMapping("/annotated")
            String annotated() {
                throw new AlreadyShipped();
            }

            @GetMapping("/boom")
            String boom() {
                throw new IllegalStateException("cannot reach jdbc://secret-host:5432");
            }

            @GetMapping("/data")
            String data() {
                throw new DataIntegrityViolationException("ORA-00001: unique constraint (APP.ORDERS_UK) violated");
            }

            @PostMapping("/orders")
            String create(@Valid @RequestBody NewOrder order) {
                return "created";
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

    @BeforeEach
    void reset() {
        SEEN.clear();
        FIRST_THROWS.set(false);
    }

    private static Map<String, Object> call(String path, String accept, int expectedStatus) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)), accept, expectedStatus);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> send(HttpRequest.Builder request, String accept, int expectedStatus)
            throws Exception {
        if (accept != null) {
            request.header("Accept", accept);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        return JSON.readValue(response.body(), Map.class);
    }

    private static Map<String, Object> post(String path, String json, int expectedStatus) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)),
                "application/json", expectedStatus);
    }

    @Test
    void sendErrorFromAFilterIsAProblemResponseForJsonAndHtmlClients() throws Exception {
        for (String accept : new String[] {null, "application/json", "text/html"}) {
            Map<String, Object> body = call("/duplicate", accept, 409);
            assertThat(body).containsEntry("status", 409).containsEntry("code", "DC-CORE-0499")
                    .containsEntry("instance", "/duplicate").containsEntry("detail", "Conflict")
                    .containsEntry("retryable", false).containsKey("timestamp")
                    .containsEntry("type", "urn:dc:problem:DC-CORE-0499");
            assertThat((String) body.get("traceId")).as("error dispatch keeps the trace").matches("[0-9a-f]{32}");
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
    void aServiceCodeAndClientDetailReachTheBodyAndAServerDetailDoesNot() throws Exception {
        Map<String, Object> body = call("/service", "application/json", 409);
        assertThat(body).containsEntry("code", "ORD-0001").containsEntry("detail", "order 42 is already paid")
                .containsEntry("type", "urn:dc:problem:ORD-0001").containsEntry("retryable", false);
        assertThat((String) body.get("traceId")).matches("[0-9a-f]{32}");

        Map<String, Object> server = call("/service-5xx", "application/json", 503);
        assertThat(server).containsEntry("code", "ORD-0503").containsEntry("detail", "An unexpected error occurred.")
                .containsEntry("retryable", true);
        assertThat(server.toString()).doesNotContain("ledger.internal");
    }

    @Test
    void aResponseStatusAnnotatedExceptionIsItsStatusNotA500() throws Exception {
        Map<String, Object> body = call("/annotated", "application/json", 409);
        assertThat(body).containsEntry("code", "DC-CORE-0499").containsEntry("detail", "order already shipped");
    }

    @Test
    void everyPathReachesTheInterceptorsInOrderWithTheOriginatingException() throws Exception {
        call("/service", "application/json", 409);
        call("/status", "application/json", 422);
        call("/annotated", "application/json", 409);
        call("/boom", "application/json", 500);
        call("/data", "application/json", 409);
        post("/orders", "{\"name\":\"\"}", 400);
        post("/orders", "{\"name\": [oops", 400);
        call("/no-such-endpoint", "application/json", 404);
        send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok"))
                .POST(HttpRequest.BodyPublishers.noBody()), "application/json", 405);
        call("/duplicate", "application/json", 409);
        call("/filter-crash", "application/json", 500);

        assertThat(SEEN).containsExactly(
                "first /service ErrorResponseException ORD-0001", "second /service ErrorResponseException ORD-0001",
                "first /status ResponseStatusException DC-CORE-0499",
                "second /status ResponseStatusException DC-CORE-0499",
                "first /annotated AlreadyShipped DC-CORE-0499", "second /annotated AlreadyShipped DC-CORE-0499",
                "first /boom IllegalStateException DC-CORE-0500", "second /boom IllegalStateException DC-CORE-0500",
                "first /data DataIntegrityViolationException DC-DATA-0409",
                "second /data DataIntegrityViolationException DC-DATA-0409",
                "first /orders MethodArgumentNotValidException DC-CORE-0400",
                "second /orders MethodArgumentNotValidException DC-CORE-0400",
                "first /orders HttpMessageNotReadableException DC-CORE-0410",
                "second /orders HttpMessageNotReadableException DC-CORE-0410",
                "first /no-such-endpoint NoResourceFoundException DC-CORE-0499",
                "second /no-such-endpoint NoResourceFoundException DC-CORE-0499",
                "first /ok HttpRequestMethodNotSupportedException DC-CORE-0499",
                "second /ok HttpRequestMethodNotSupportedException DC-CORE-0499",
                "first /duplicate none DC-CORE-0499", "second /duplicate none DC-CORE-0499",
                "first /filter-crash IllegalStateException DC-CORE-0500",
                "second /filter-crash IllegalStateException DC-CORE-0500");
    }

    @Test
    void anInterceptorsChangeReachesTheBody() throws Exception {
        assertThat(call("/boom", "application/json", 500)).containsEntry("tenant", "dxb");
        assertThat(call("/duplicate", "application/json", 409)).containsEntry("tenant", "dxb");
    }

    @Test
    void aThrowingInterceptorStopsTheRestButThePlatformBodyIsStillSent() throws Exception {
        FIRST_THROWS.set(true);
        assertThat(call("/boom", "application/json", 500)).containsEntry("code", "DC-CORE-0500");
        assertThat(call("/duplicate", "application/json", 409)).containsEntry("code", "DC-CORE-0499");
        assertThat(SEEN).containsExactly("first /boom IllegalStateException DC-CORE-0500",
                "first /duplicate none DC-CORE-0499");
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
