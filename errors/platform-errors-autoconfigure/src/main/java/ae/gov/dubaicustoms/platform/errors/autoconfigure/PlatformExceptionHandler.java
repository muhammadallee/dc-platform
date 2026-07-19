package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import ae.gov.dubaicustoms.platform.errors.BusinessException;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Platform {@code @RestControllerAdvice}: maps {@link PlatformException}s, request-body
 * validation failures, and unexpected exceptions to RFC-9457 {@link ProblemDetail} responses
 * with {@code code}, {@code correlationId}, and {@code timestamp} extensions.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the servlet stack's own exceptions
 * (404/405/406/415, …) keep their proper statuses instead of falling into the 500 catch-all.
 * Ordered at {@link Ordered#LOWEST_PRECEDENCE} so application advice beans win first.
 *
 * <p>Registered by {@code PlatformErrorHandlingAutoConfiguration}; define your own bean named
 * {@code platformExceptionHandler} to replace it. Thread-safe.
 *
 * @since 0.1.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {

    /** Code for exceptions nothing else mapped; part of the platform error-code registry. */
    static final ErrorCode UNEXPECTED_ERROR = new ErrorCode("DC-CORE-0500");

    /** Code for request-body validation failures mapped to 400 with {@code errors[]}. */
    static final ErrorCode VALIDATION_FAILED = new ErrorCode("DC-CORE-0400");

    private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    private final ErrorsProperties properties;
    private final ProblemDetailFactory factory;

    /**
     * Creates the advice.
     *
     * @param properties the errors capability configuration; never {@code null}
     * @param factory the shared problem-body assembler; never {@code null}
     */
    public PlatformExceptionHandler(ErrorsProperties properties, ProblemDetailFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    /**
     * Maps platform exceptions: business exceptions take their {@code statusHint}, all other
     * platform exceptions are infrastructure failures and map to 500. The exception message is
     * platform/application-authored and treated as safe for the response body.
     *
     * @param exception the platform exception; never {@code null}
     * @param request the current request; never {@code null}
     * @return the problem response
     */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ProblemDetail> handlePlatformException(PlatformException exception,
            HttpServletRequest request) {
        int status = exception instanceof BusinessException business ? business.statusHint().status() : 500;
        if (status >= 500) {
            log.error("platform exception [{}]", exception.code().value(), exception);
        } else {
            // Business outcomes are expected traffic, not incidents; keep them out of ERROR alerting.
            log.debug("business exception [{}]: {}", exception.code().value(), exception.getMessage());
        }
        ProblemDetail problem = factory.create(
                status, exception.code(), exception.getMessage(), request.getRequestURI(), exception);
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * Fallback: anything unmapped is a 500 with code {@code DC-CORE-0500}. The exception message
     * is NOT leaked — unexpected messages routinely carry internals (SQL, hosts, file paths); the
     * full exception goes to the log, correlated via MDC.
     *
     * @param exception the unexpected exception; never {@code null}
     * @param request the current request; never {@code null}
     * @return the generic 500 problem response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled exception [{}]", UNEXPECTED_ERROR.value(), exception);
        ProblemDetail problem = factory.create(
                500, UNEXPECTED_ERROR, "An unexpected error occurred.", request.getRequestURI(), exception);
        return ResponseEntity.status(500).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!properties.mapValidation()) {
            return super.handleMethodArgumentNotValid(exception, headers, status, request);
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().forEach(fieldError -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", fieldError.getField());
            entry.put("message", fieldError.getDefaultMessage());
            entry.put("rejectedValue", factory.redact(fieldError.getField(), fieldError.getRejectedValue()));
            errors.add(entry);
        });
        exception.getBindingResult().getGlobalErrors().forEach(globalError -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", globalError.getObjectName());
            entry.put("message", globalError.getDefaultMessage());
            entry.put("rejectedValue", null);
            errors.add(entry);
        });
        ProblemDetail problem = factory.create(
                400, VALIDATION_FAILED, "Validation failed.", instancePathOf(request), exception);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static String instancePathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
