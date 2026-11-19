package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Platform {@code @RestControllerAdvice} mapping method-validation
 * {@link ConstraintViolationException}s to 400 problem responses with the same {@code errors[]}
 * shape as request-body validation failures.
 *
 * <p>A separate advice (not a method on {@code PlatformExceptionHandler}) for two reasons:
 * it must only load when {@code jakarta.validation} is on the classpath, and it must be ordered
 * AHEAD of the catch-all advice — Spring picks the first advice bean with any matching handler,
 * so the catch-all's {@code Exception} mapping would otherwise turn violations into 500s.
 *
 * <p>Registered by {@code PlatformErrorHandlingAutoConfiguration} when
 * {@code dc.platform.errors.map-validation} is on; define your own bean named
 * {@code platformValidationExceptionHandler} to replace it. Thread-safe.
 *
 * @since 0.1.0
 */
@Order(0)
@RestControllerAdvice
public class PlatformConstraintViolationHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PlatformConstraintViolationHandler.class);

    private final ProblemDetailFactory factory;

    /**
     * Creates the advice.
     *
     * @param factory the shared problem-body assembler; never {@code null}
     */
    public PlatformConstraintViolationHandler(ProblemDetailFactory factory) {
        this.factory = factory;
    }

    /**
     * Maps constraint violations from method validation ({@code @Validated} controllers,
     * validated service methods) to a 400 problem response.
     *
     * @param exception the violation set; never {@code null}
     * @param request the current request; never {@code null}
     * @param response the current response; never {@code null}
     * @return the 400 problem response with an {@code errors[]} extension; its cause is {@code exception}
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ErrorResponse handleConstraintViolation(ConstraintViolationException exception,
            HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            // Nothing may be written any more, but the failure must not vanish: log it on the request's trace.
            log.error("constraint violation after the response was committed; nothing more is written", exception);
            return null;
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String field = violation.getPropertyPath().toString();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", field);
            entry.put("message", violation.getMessage());
            factory.putRejectedValue(entry, field, violation.getInvalidValue());
            errors.add(entry);
        }
        ProblemDetail problem = factory.create(400, PlatformExceptionHandler.VALIDATION_FAILED,
                "Validation failed.", request.getRequestURI(), exception, request);
        problem.setProperty("errors", errors);
        return ProblemDetailFactory.wrap(problem, null, exception);
    }
}
