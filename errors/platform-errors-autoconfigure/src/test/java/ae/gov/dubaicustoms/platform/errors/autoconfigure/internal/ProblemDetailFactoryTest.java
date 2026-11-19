package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.ErrorsProperties;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemDetailFactoryTest {

    private static final String CODE = "DC-TEST-0001";

    private static ProblemDetailFactory factory(boolean includeRejectedValues) {
        return new ProblemDetailFactory(
                new ErrorsProperties(true, false, "urn:dc:problem:", true, includeRejectedValues),
                TraceIdSource.NONE, Clock.systemUTC());
    }

    @ParameterizedTest
    @CsvSource({"400,false", "404,false", "409,false", "422,false", "429,true", "500,false", "502,true",
            "503,true", "504,true"})
    void retryableFollowsTheStatus(int status, boolean retryable) {
        ProblemDetail problem = factory(false).create(status, CODE, "client-authored detail", "/x", null, null);
        assertThat(problem.getProperties()).containsEntry("retryable", retryable);
    }

    @ParameterizedTest
    @CsvSource({"500", "502", "503", "504"})
    void everyServerErrorGetsTheGenericDetail(int status) {
        ProblemDetail problem = factory(false).create(status, CODE, "db-7.internal refused", "/x", null, null);
        assertThat(problem.getDetail()).isEqualTo(ProblemDetailFactory.GENERIC_SERVER_ERROR_DETAIL);
    }

    @Test
    void clientErrorsKeepTheirAuthoredDetail() {
        assertThat(factory(false).create(404, CODE, "order 42 not found", "/x", null, null).getDetail())
                .isEqualTo("order 42 not found");
    }

    @Test
    void decorateReplacesAFrameworkServerErrorDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Async request timed out at node-3");
        factory(false).decorate(problem, CODE, null, null);
        assertThat(problem.getDetail()).isEqualTo(ProblemDetailFactory.GENERIC_SERVER_ERROR_DETAIL);
        assertThat(problem.getProperties()).containsEntry("code", "DC-TEST-0001").containsEntry("retryable", true);
    }

    @Test
    void aStringCodeTheThrowerSetIsKeptWithItsTypeAndAnythingElseIsReplaced() {
        ProblemDetail service = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.CONFLICT, "taken");
        service.setProperty("code", "ORD-0001");
        factory(false).decorate(service, CODE, null, null);
        assertThat(service.getProperties()).containsEntry("code", "ORD-0001");
        assertThat(service.getType()).hasToString("urn:dc:problem:ORD-0001");
        assertThat(service.getDetail()).isEqualTo("taken");

        ProblemDetail ownType = ProblemDetail.forStatus(409);
        ownType.setType(java.net.URI.create("https://orders.example/problems/taken"));
        ownType.setTitle("Taken");
        ownType.setProperty("code", "ORD-0002");
        factory(false).decorate(ownType, CODE, null, null);
        assertThat(ownType.getType()).hasToString("https://orders.example/problems/taken");
        assertThat(ownType.getTitle()).isEqualTo("Taken");

        for (Object notACode : new Object[] {42, " ", java.util.List.of("x")}) {
            ProblemDetail other = ProblemDetail.forStatus(409);
            other.setProperty("code", notACode);
            factory(false).decorate(other, CODE, null, null);
            assertThat(other.getProperties()).as("code %s", notACode).containsEntry("code", "DC-TEST-0001");
        }
    }

    @Test
    void theWrappedResponseCarriesTheBodyStatusHeadersAndTheOriginatingException() {
        ProblemDetail problem = factory(false).create(405, CODE, "nope", "/x", null, null);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Allow", "GET");
        IllegalStateException origin = new IllegalStateException("origin");
        org.springframework.web.ErrorResponseException response = ProblemDetailFactory.wrap(problem, headers, origin);
        assertThat(response.getBody()).isSameAs(problem);
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getFirst("Allow")).isEqualTo("GET");
        assertThat(response.getCause()).isSameAs(origin);
        assertThat(response.getMessage()).doesNotContain("nope");
    }

    @Test
    void theTraceIdComesFromTheSourceAndIsOmittedWithoutOne() {
        ProblemDetailFactory traced = new ProblemDetailFactory(
                new ErrorsProperties(true, false, "urn:dc:problem:", true, false),
                request -> "0123456789abcdef0123456789abcdef", Clock.systemUTC());
        assertThat(traced.create(404, CODE, "x", "/x", null, null).getProperties())
                .containsEntry("traceId", "0123456789abcdef0123456789abcdef");
        assertThat(factory(false).create(404, CODE, "x", "/x", null, null).getProperties()).doesNotContainKey("traceId");
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
