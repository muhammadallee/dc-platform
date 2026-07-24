package ae.gov.dubaicustoms.platform.test.assertj;

import static ae.gov.dubaicustoms.platform.test.assertj.PlatformAssertions.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class ProblemDetailAssertTest {

    private static ProblemDetail sampleProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(404);
        problem.setDetail("order 'unknown' was not found");
        problem.setProperty("code", "DC-ORD-0001");
        problem.setProperty("correlationId", "abc-123");
        return problem;
    }

    @Test
    void passesForMatchingCodeStatusDetailAndProperties() {
        assertThatProblem(sampleProblem())
                .hasStatus(404)
                .hasCode("DC-ORD-0001")
                .hasDetailContaining("not found")
                .hasProperty("correlationId", "abc-123")
                .hasPropertyPresent("correlationId");
    }

    @Test
    void failsWithADescriptiveMessageOnCodeMismatch() {
        assertThatThrownBy(() -> assertThatProblem(sampleProblem()).hasCode("DC-ORD-9999"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DC-ORD-9999")
                .hasMessageContaining("DC-ORD-0001");
    }

    @Test
    void failsOnStatusDetailAndAbsentProperty() {
        assertThatThrownBy(() -> assertThatProblem(sampleProblem()).hasStatus(500))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatProblem(sampleProblem()).hasDetailContaining("teapot"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatProblem(sampleProblem()).hasPropertyPresent("missing"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void unwrapsAResponseEntityBody() {
        ResponseEntity<ProblemDetail> response = ResponseEntity.status(HttpStatus.NOT_FOUND).body(sampleProblem());
        assertThatProblem(response).hasCode("DC-ORD-0001");
    }

    @Test
    void rejectsAResponseEntityWithoutABody() {
        ResponseEntity<ProblemDetail> response = ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        assertThatThrownBy(() -> assertThatProblem(response))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ProblemDetail body");
    }

    @Test
    void hasCodeReturnsTheSameAssertionForChaining() {
        ProblemDetailAssert assertion = assertThatProblem(sampleProblem());
        assertThat(assertion.hasCode("DC-ORD-0001")).isSameAs(assertion);
    }
}
