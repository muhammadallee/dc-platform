package ae.gov.dubaicustoms.platform.test.assertj;

import java.util.Map;
import java.util.Objects;
import org.assertj.core.api.AbstractAssert;
import org.springframework.http.ProblemDetail;

/**
 * AssertJ assertion for RFC-9457 {@link ProblemDetail} bodies produced by the platform's error
 * handling, with first-class access to the platform extensions ({@code code}, {@code correlationId},
 * {@code timestamp}).
 *
 * <pre>{@code
 * assertThatProblem(response).hasStatus(404).hasCode("DC-ORD-0001");
 * }</pre>
 *
 * <p>Obtain one via {@link PlatformAssertions#assertThatProblem(ProblemDetail)}. Not thread-safe;
 * each instance is scoped to one assertion chain.
 *
 * @since 0.2.0
 */
public final class ProblemDetailAssert extends AbstractAssert<ProblemDetailAssert, ProblemDetail> {

    ProblemDetailAssert(ProblemDetail actual) {
        super(actual, ProblemDetailAssert.class);
    }

    /**
     * Asserts the problem's {@code code} extension equals {@code expected}.
     *
     * @param expected the platform error code, e.g. {@code "DC-ORD-0001"}; never {@code null}
     * @return this assertion
     */
    public ProblemDetailAssert hasCode(String expected) {
        isNotNull();
        Object code = property("code");
        if (!Objects.equals(code, expected)) {
            failWithMessage("expected problem code <%s> but was <%s> (properties: %s)",
                    expected, code, actual.getProperties());
        }
        return this;
    }

    /**
     * Asserts the problem's HTTP status equals {@code expected}.
     *
     * @param expected the expected status code
     * @return this assertion
     */
    public ProblemDetailAssert hasStatus(int expected) {
        isNotNull();
        if (actual.getStatus() != expected) {
            failWithMessage("expected problem status <%s> but was <%s>", expected, actual.getStatus());
        }
        return this;
    }

    /**
     * Asserts the problem's {@code detail} contains {@code substring}.
     *
     * @param substring the text the detail must contain; never {@code null}
     * @return this assertion
     */
    public ProblemDetailAssert hasDetailContaining(String substring) {
        isNotNull();
        String detail = actual.getDetail();
        if (detail == null || !detail.contains(substring)) {
            failWithMessage("expected problem detail to contain <%s> but was <%s>", substring, detail);
        }
        return this;
    }

    /**
     * Asserts an arbitrary extension property equals {@code expected}.
     *
     * @param name the property name; never {@code null}
     * @param expected the expected value
     * @return this assertion
     */
    public ProblemDetailAssert hasProperty(String name, Object expected) {
        isNotNull();
        Object value = property(name);
        if (!Objects.equals(value, expected)) {
            failWithMessage("expected problem property <%s> to be <%s> but was <%s>", name, expected, value);
        }
        return this;
    }

    /**
     * Asserts an extension property is present (non-null), for values that are non-deterministic
     * (e.g. {@code correlationId}, {@code timestamp}).
     *
     * @param name the property name; never {@code null}
     * @return this assertion
     */
    public ProblemDetailAssert hasPropertyPresent(String name) {
        isNotNull();
        if (property(name) == null) {
            failWithMessage("expected problem to carry property <%s> but it was absent (properties: %s)",
                    name, actual.getProperties());
        }
        return this;
    }

    private Object property(String name) {
        Map<String, Object> properties = actual.getProperties();
        return properties == null ? null : properties.get(name);
    }
}
