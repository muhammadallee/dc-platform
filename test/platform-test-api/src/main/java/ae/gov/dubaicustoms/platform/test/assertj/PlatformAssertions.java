package ae.gov.dubaicustoms.platform.test.assertj;

import java.util.Objects;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Entry points for the platform's AssertJ assertion extensions. Static-import
 * {@link #assertThatProblem} alongside AssertJ's own {@code assertThat}.
 *
 * <pre>{@code
 * import static ae.gov.dubaicustoms.platform.test.assertj.PlatformAssertions.assertThatProblem;
 *
 * assertThatProblem(response).hasStatus(404).hasCode("DC-ORD-0001");
 * }</pre>
 *
 * @since 0.2.0
 */
public final class PlatformAssertions {

    private PlatformAssertions() {
    }

    /**
     * Begins an assertion chain over a {@link ProblemDetail}.
     *
     * @param actual the problem detail to assert against; never {@code null}
     * @return a {@link ProblemDetailAssert}
     */
    public static ProblemDetailAssert assertThatProblem(ProblemDetail actual) {
        return new ProblemDetailAssert(Objects.requireNonNull(actual, "actual must not be null"));
    }

    /**
     * Begins an assertion chain over the {@link ProblemDetail} body of a response entity.
     *
     * @param response a response whose body is a problem detail; never {@code null}, and its body
     *     must be present
     * @return a {@link ProblemDetailAssert}
     */
    public static ProblemDetailAssert assertThatProblem(ResponseEntity<ProblemDetail> response) {
        Objects.requireNonNull(response, "response must not be null");
        ProblemDetail body = response.getBody();
        if (body == null) {
            throw new AssertionError("expected the response to carry a ProblemDetail body, but it was null");
        }
        return assertThatProblem(body);
    }
}
