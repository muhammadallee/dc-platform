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
 * with {@code code}, {@code traceId}, {@code timestamp} and {@code retryable} extensions.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the servlet stack's own exceptions
 * (404/405/406/415, …) keep their proper statuses instead of falling into the 500 catch-all; their
 * bodies get the same platform extensions ({@code DC-CORE-0410} for an unreadable request body,
 * {@code DC-CORE-0499} for any other rejected request). No 5xx body ever carries an exception
 * message: the full exception is logged with the trace ID instead.
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

    /** Code for a request body that could not be read or parsed (e.g. malformed JSON). */
    static final ErrorCode MALFORMED_REQUEST = new ErrorCode("DC-CORE-0410");

    /** Code for every other request the servlet stack rejected (404, 405, 406, 415, …). */
    static final ErrorCode REQUEST_REJECTED = new ErrorCode("DC-CORE-0499");

    /** Detail for an unreadable body: the parser's own message names classes and offsets. */
    private static final String MALFORMED_REQUEST_DETAIL = "The request body is malformed or unreadable.";

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
     * platform exceptions are infrastructure failures and map to 500. For 4xx the exception message
     * is application-authored and becomes the {@code detail}; for 5xx the body carries only the
     * generic detail (infrastructure messages name hosts, clients and statuses).
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
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request)
            throws Exception {
        // Spring Security's authentication/authorization failures (e.g. a method-security denial in a
        // controller) must reach its ExceptionTranslationFilter, which renders the platform 401/403;
        // swallowing them here would turn every denial into a 500.
        if (isSecurityException(exception)) {
            throw exception;
        }
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
            factory.putRejectedValue(entry, fieldError.getField(), fieldError.getRejectedValue());
            errors.add(entry);
        });
        exception.getBindingResult().getGlobalErrors().forEach(globalError -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", globalError.getObjectName());
            entry.put("message", globalError.getDefaultMessage());
            errors.add(entry);
        });
        ProblemDetail problem = factory.create(
                400, VALIDATION_FAILED, "Validation failed.", instancePathOf(request), exception);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Every body Spring MVC builds for its own exceptions passes here: it gets the platform extensions
     * (with the originating exception as the customizer {@code source}), and an unreadable body never
     * exposes the parser's message.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.value() >= 500) {
            // Framework 5xx (async timeout, conversion failure, …) reach only this hook: log them here,
            // once, while the request context (trace ID) is still open.
            log.error("framework exception [{}] status {}", UNEXPECTED_ERROR.value(), statusCode.value(), exception);
        }
        ResponseEntity<Object> entity = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (entity != null && entity.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            ErrorCode code = statusCode.value() >= 500 ? UNEXPECTED_ERROR : REQUEST_REJECTED;
            if (problem.getInstance() == null && instancePathOf(request) != null) {
                problem.setInstance(java.net.URI.create(instancePathOf(request)));
            }
            factory.decorate(problem, code, exception);
        }
        return entity;
    }

    /**
     * Spring MVC's native method validation (constraints on {@code @RequestParam}/{@code @PathVariable}
     * or on {@code @Valid} arguments of a controller without class-level {@code @Validated}) maps to the
     * same 400 {@code errors[]} shape as body validation. Return-value validation is a server bug and
     * keeps its 500 with the generic body.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            org.springframework.web.method.annotation.HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (!properties.mapValidation() || status.value() >= 500) {
            return super.handleHandlerMethodValidationException(exception, headers, status, request);
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (org.springframework.validation.method.ParameterValidationResult result
                : exception.getParameterValidationResults()) {
            String parameter = result.getMethodParameter().getParameterName();
            if (result instanceof org.springframework.validation.method.ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(fieldError -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", fieldError.getField());
                    entry.put("message", fieldError.getDefaultMessage());
                    factory.putRejectedValue(entry, fieldError.getField(), fieldError.getRejectedValue());
                    errors.add(entry);
                });
                parameterErrors.getGlobalErrors().forEach(globalError -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", parameter);
                    entry.put("message", globalError.getDefaultMessage());
                    errors.add(entry);
                });
            } else {
                result.getResolvableErrors().forEach(error -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", parameter);
                    entry.put("message", error.getDefaultMessage());
                    factory.putRejectedValue(entry, parameter, result.getArgument());
                    errors.add(entry);
                });
            }
        }
        ProblemDetail problem = factory.create(400, VALIDATION_FAILED, "Validation failed.", instancePathOf(request), exception);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        log.debug("unreadable request body [{}]", MALFORMED_REQUEST.value(), exception);
        ProblemDetail problem = factory.create(400, MALFORMED_REQUEST, MALFORMED_REQUEST_DETAIL,
                instancePathOf(request), exception);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    private static boolean isSecurityException(Throwable exception) {
        for (Class<?> type = exception.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals("org.springframework.security.access.AccessDeniedException")
                    || name.equals("org.springframework.security.core.AuthenticationException")) {
                return true;
            }
        }
        return false;
    }

    private static String instancePathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
