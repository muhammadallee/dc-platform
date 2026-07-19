package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
     * @return the 400 problem response with an {@code errors[]} extension
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception,
            HttpServletRequest request) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String field = violation.getPropertyPath().toString();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", field);
            entry.put("message", violation.getMessage());
            entry.put("rejectedValue", factory.redact(field, violation.getInvalidValue()));
            errors.add(entry);
        }
        ProblemDetail problem = factory.create(400, PlatformExceptionHandler.VALIDATION_FAILED,
                "Validation failed.", request.getRequestURI(), exception);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
