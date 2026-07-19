package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/** Maps real (not mocked) constraint violations to the 400 errors[] shape. */
class PlatformConstraintViolationHandlerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    record Credentials(@NotBlank String user, @Size(min = 12) String apiToken) {
    }

    private ProblemDetail handle(Object invalidBean) {
        ErrorsProperties properties = new ErrorsProperties(true, false, "https://errors.dc.com/", true);
        PlatformConstraintViolationHandler handler = new PlatformConstraintViolationHandler(
                new ProblemDetailFactory(properties, List.of(), FIXED_CLOCK));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ConstraintViolationException exception =
                    new ConstraintViolationException(validator.validate(invalidBean));
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/credentials");
            return handler.handleConstraintViolation(exception, request).getBody();
        }
    }

    @Test
    void violationsMapTo400WithErrorsArray() {
        ProblemDetail problem = handle(new Credentials("", "0123456789ab"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "DC-CORE-0400");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) problem.getProperties().get("errors");
        assertThat(errors).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("field", "user");
            assertThat(entry).containsEntry("rejectedValue", "");
            assertThat(entry.get("message")).isNotNull();
        });
    }

    @Test
    void credentialShapedPathsAreRedacted() {
        ProblemDetail problem = handle(new Credentials("ali", "short"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) problem.getProperties().get("errors");
        assertThat(errors).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("field", "apiToken");
            assertThat(entry).containsEntry("rejectedValue", "REDACTED");
        });
    }
}
