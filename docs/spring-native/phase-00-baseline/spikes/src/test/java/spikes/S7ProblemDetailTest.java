package spikes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * S7: a service throws Spring's own {@code ErrorResponseException} (setting its error code as a ProblemDetail
 * property) or {@code ResponseStatusException}; a platform advice adds {@code traceId}, {@code timestamp} and
 * {@code retryable} to every ProblemDetail without the service using a platform type.
 */
@SpringBootTest(classes = S7ProblemDetailTest.App.class)
@AutoConfigureMockMvc
class S7ProblemDetailTest {

    static class OrderNotFound extends ErrorResponseException {
        OrderNotFound(String id) {
            super(HttpStatus.NOT_FOUND);
            getBody().setDetail("order " + id + " not found");
            getBody().setProperty("code", "DC-ORDER-0404");
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/orders/42")
        String missing() {
            throw new OrderNotFound("42");
        }

        @GetMapping("/busy")
        String busy() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "try later");
        }
    }

    /** Stands in for the platform's error advice. */
    @RestControllerAdvice
    static class PlatformAdvice extends ResponseEntityExceptionHandler {
        @Override
        protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                WebRequest request) {
            if (body instanceof ProblemDetail pd) {
                pd.setProperty("traceId", "0123456789abcdef0123456789abcdef");
                pd.setProperty("timestamp", "2026-01-01T00:00:00Z");
                pd.setProperty("retryable", status.value() == 503);
            }
            return super.createResponseEntity(body, headers, status, request);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({Endpoints.class, PlatformAdvice.class})
    static class App {
        /** Security is not what S7 checks: open its two paths, by the route S2 verified. */
        @Bean
        Customizer<HttpSecurity> openForS7() {
            return http -> http.authorizeHttpRequests(a -> a.requestMatchers("/orders/**", "/busy").permitAll());
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void serviceErrorCodeAndPlatformFieldsTogether() throws Exception {
        mvc.perform(get("/orders/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DC-ORDER-0404"))
                .andExpect(jsonPath("$.detail").value("order 42 not found"))
                .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void responseStatusExceptionGetsTheSameFields() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
