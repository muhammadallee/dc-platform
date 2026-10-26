package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.ErrorsProperties;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemDetailFactoryTest {

    private static final ErrorCode CODE = new ErrorCode("DC-TEST-0001");

    private static ProblemDetailFactory factory(boolean includeRejectedValues) {
        return new ProblemDetailFactory(
                new ErrorsProperties(true, false, "https://errors.dc.com/", true, includeRejectedValues),
                List.of(), Clock.systemUTC());
    }

    @ParameterizedTest
    @CsvSource({"400,false", "404,false", "409,false", "422,false", "429,true", "500,false", "502,true",
            "503,true", "504,true"})
    void retryableFollowsTheStatus(int status, boolean retryable) {
        ProblemDetail problem = factory(false).create(status, CODE, "client-authored detail", "/x", null);
        assertThat(problem.getProperties()).containsEntry("retryable", retryable);
    }

    @ParameterizedTest
    @CsvSource({"500", "502", "503", "504"})
    void everyServerErrorGetsTheGenericDetail(int status) {
        ProblemDetail problem = factory(false).create(status, CODE, "db-7.internal refused", "/x", null);
        assertThat(problem.getDetail()).isEqualTo(ProblemDetailFactory.GENERIC_SERVER_ERROR_DETAIL);
    }

    @Test
    void clientErrorsKeepTheirAuthoredDetail() {
        assertThat(factory(false).create(404, CODE, "order 42 not found", "/x", null).getDetail())
                .isEqualTo("order 42 not found");
    }

    @Test
    void decorateReplacesAFrameworkServerErrorDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Async request timed out at node-3");
        factory(false).decorate(problem, CODE, null);
        assertThat(problem.getDetail()).isEqualTo(ProblemDetailFactory.GENERIC_SERVER_ERROR_DETAIL);
        assertThat(problem.getProperties()).containsEntry("code", "DC-TEST-0001").containsEntry("retryable", true);
    }

    @Test
    void rejectedValuesOnlyWhenEnabledAndCredentialsAlwaysRedacted() {
        Map<String, Object> off = new LinkedHashMap<>();
        factory(false).putRejectedValue(off, "email", "someone@example.ae");
        assertThat(off).isEmpty();

        Map<String, Object> on = new LinkedHashMap<>();
        factory(true).putRejectedValue(on, "email", "someone@example.ae");
        assertThat(on).containsEntry("rejectedValue", "someone@example.ae");

        Map<String, Object> secret = new LinkedHashMap<>();
        factory(true).putRejectedValue(secret, "apiToken", "s3cr3t");
        assertThat(secret).containsEntry("rejectedValue", "REDACTED");
    }
}
