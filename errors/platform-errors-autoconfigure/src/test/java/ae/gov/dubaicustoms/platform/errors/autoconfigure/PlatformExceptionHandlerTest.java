package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.errors.ConflictException;
import ae.gov.dubaicustoms.platform.errors.NotFoundException;
import ae.gov.dubaicustoms.platform.errors.ProblemDetailCustomizer;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Behavior of every mapping in the platform advice, including the one full-JSON-shape assert. */
class PlatformExceptionHandlerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION = "0123456789abcdef0123456789abcdef";

    private MockMvc mockMvc(ErrorsProperties properties, List<ProblemDetailCustomizer> customizers) {
        ProblemDetailFactory factory = new ProblemDetailFactory(properties, customizers, FIXED_CLOCK);
        return MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new PlatformExceptionHandler(properties, factory))
                .build();
    }

    private static ErrorsProperties defaults() {
        return new ErrorsProperties(true, false, "https://errors.dc.com/", true);
    }

    @Test
    void notFoundMapsToFullRfc9457Shape() throws Exception {
        MockMvc mvc = mockMvc(defaults(), List.of());
        try (AutoCloseable scope = RequestContext.open(new CorrelationId(CORRELATION), Map.of())) {
            MvcResult result = mvc.perform(get("/missing"))
                    .andExpect(status().isNotFound())
                    .andReturn();

            // The one strict full-shape assertion (spec: assert full JSON shape once with JSONAssert).
            JSONAssert.assertEquals("""
                    {
                      "type": "https://errors.dc.com/DC-TEST-0404",
                      "title": "DC-TEST-0404",
                      "status": 404,
                      "detail": "order 42 not found",
                      "instance": "/missing",
                      "code": "DC-TEST-0404",
                      "correlationId": "%s",
                      "timestamp": "2026-01-01T00:00:00Z"
                    }""".formatted(CORRELATION),
                    result.getResponse().getContentAsString(), JSONCompareMode.STRICT);
            assertThat(result.getResponse().getContentType())
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
    }

    @Test
    void conflictMapsTo409() throws Exception {
        mockMvc(defaults(), List.of()).perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DC-TEST-0409"));
    }

    @Test
    void infrastructurePlatformExceptionMapsTo500WithItsCode() throws Exception {
        mockMvc(defaults(), List.of()).perform(get("/infra"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DC-TEST-0500"))
                .andExpect(jsonPath("$.detail").value("transport down"));
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingTheMessage() throws Exception {
        MvcResult result = mockMvc(defaults(), List.of()).perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc://secret-host");
    }

    @Test
    void requestBodyValidationMapsTo400WithRedactedErrors() throws Exception {
        mockMvc(defaults(), List.of())
                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')].rejectedValue").value(""))
                // credential-shaped fields never echo their raw value back
                .andExpect(jsonPath("$.errors[?(@.field=='password')].rejectedValue").value("REDACTED"));
    }

    @Test
    void mapValidationOffFallsBackToDefaultSpringBody() throws Exception {
        ErrorsProperties off = new ErrorsProperties(true, false, "https://errors.dc.com/", false);
        mockMvc(off, List.of())
                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void includeStacktraceAddsTheExtension() throws Exception {
        ErrorsProperties withTrace = new ErrorsProperties(true, true, "https://errors.dc.com/", true);
        mockMvc(withTrace, List.of()).perform(get("/boom"))
                .andExpect(jsonPath("$.stacktrace").exists());
    }

    @Test
    void customizersApplyInOrder() throws Exception {
        ProblemDetailCustomizer first = (detail, source) -> detail.setProperty("marker", "first");
        ProblemDetailCustomizer second = (detail, source) -> detail.setProperty("marker", "second");
        // Later customizers overwrite earlier ones: last write wins proves application order.
        mockMvc(defaults(), List.of(first, second)).perform(get("/missing"))
                .andExpect(jsonPath("$.marker").value("second"));
    }

    @Test
    void correlationExtensionIsOmittedOutsideAnOpenContext() throws Exception {
        mockMvc(defaults(), List.of()).perform(get("/missing"))
                .andExpect(jsonPath("$.correlationId").doesNotExist());
    }

    @RestController
    static class FailingController {

        record NewOrder(@NotBlank String name, @Size(min = 12) String password) {
        }

        @GetMapping("/missing")
        String missing() {
            throw new NotFoundException(new ErrorCode("DC-TEST-0404"), "order 42 not found");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException(new ErrorCode("DC-TEST-0409"), "name already taken");
        }

        @GetMapping("/infra")
        String infra() {
            throw new TestInfraException();
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("cannot reach jdbc://secret-host:5432");
        }

        @PostMapping("/orders")
        String create(@jakarta.validation.Valid @RequestBody NewOrder order) {
            return "created";
        }
    }

    static final class TestInfraException extends PlatformException {
        TestInfraException() {
            super(new ErrorCode("DC-TEST-0500"), "transport down");
        }
    }
}
