package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Platform {@code @RestControllerAdvice}: turns every exception a controller lets through into an RFC 9457
 * {@link ProblemDetail} with {@code code}, {@code traceId}, {@code timestamp} and {@code retryable} extensions.
 *
 * <ul>
 *   <li><b>Spring's own error types</b> keep their status and body: a service's {@link ErrorResponseException}
 *       (with its {@code code} property and 4xx detail), {@code ResponseStatusException}, and Spring MVC's exceptions
 *       (404, 405, 406, 415, …), which get {@code DC-CORE-0499}. An unreadable body is {@code DC-CORE-0410} without
 *       the parser's message.</li>
 *   <li><b>Bean Validation</b> failures ({@code MethodArgumentNotValidException},
 *       {@code HandlerMethodValidationException}) are a 400 {@code DC-CORE-0400} with {@code errors[]} field paths;
 *       rejected values only when enabled, credential-shaped fields redacted.</li>
 *   <li><b>{@code @ResponseStatus}</b> on an exception class sets the status; its {@code reason} is a 4xx detail.</li>
 *   <li><b>Data-access failures</b> anywhere in the cause chain are 409 {@code DC-DATA-0409}, 409
 *       {@code DC-DATA-0410} or 503 {@code DC-DATA-0503}, with a fixed detail that names no SQL, table or
 *       constraint.</li>
 *   <li><b>Anything else</b> is a 500 {@code DC-CORE-0500}.</li>
 * </ul>
 * No 5xx body carries an exception message; the full exception is logged on the request's trace instead. Spring
 * Security's authentication and access-denied exceptions are rethrown so its filter chain answers them.
 *
 * <p>Every handler returns an {@link ErrorResponseException} whose cause is the originating exception, so a
 * service's {@code ErrorResponse.Interceptor} (added through {@code WebMvcConfigurer.addErrorResponseInterceptors})
 * sees both the finished body and the exception. Ordered at {@link Ordered#LOWEST_PRECEDENCE} so a service's own
 * advice wins for its exceptions.
 *
 * <p>Registered by {@code PlatformErrorHandlingAutoConfiguration}; define a bean named {@code platformExceptionHandler}
 * (or of this type) to replace it. Thread-safe.
 *
 * @since 0.1.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class PlatformExceptionHandler {

    /** Code for exceptions nothing else mapped. */
    static final String UNEXPECTED_ERROR = "DC-CORE-0500";

    /** Code for Bean Validation failures mapped to 400 with {@code errors[]}. */
    static final String VALIDATION_FAILED = "DC-CORE-0400";

    /** Code for a request body that could not be read or parsed (for example malformed JSON). */
    static final String MALFORMED_REQUEST = "DC-CORE-0410";

    /** Code for every other client error without a code of its own (404, 405, 406, 415, a 4xx {@code @ResponseStatus}, …). */
    static final String REQUEST_REJECTED = "DC-CORE-0499";

    /** Detail for an unreadable body: the parser's own message names classes and offsets. */
    private static final String MALFORMED_REQUEST_DETAIL = "The request body is malformed or unreadable.";

    private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    private final ProblemDetailFactory factory;
    private final FrameworkErrors framework;

    /**
     * Creates the advice.
     *
     * @param properties the errors capability configuration; never {@code null}
     * @param factory the shared problem-body assembler; never {@code null}
     */
    public PlatformExceptionHandler(ErrorsProperties properties, ProblemDetailFactory factory) {
        this.factory = factory;
        this.framework = new FrameworkErrors(properties, factory);
    }

    /**
     * Maps any exception a handler let through.
     *
     * @param exception the exception; never {@code null}
     * @param request the current request; never {@code null}
     * @return the error response, whose cause is {@code exception}
     * @throws Exception a Spring Security exception, rethrown unchanged
     */
    @ExceptionHandler(Exception.class)
    public ErrorResponse handle(Exception exception, HttpServletRequest request) throws Exception {
        // Spring Security's authentication/authorization failures (e.g. a method-security denial in a controller)
        // must reach its ExceptionTranslationFilter; swallowing them here would turn every denial into a 500.
        if (isSecurityException(exception)) {
            throw exception;
        }
        ResponseEntity<Object> entity = framework.map(exception, new ServletWebRequest(request));
        if (entity != null && entity.getBody() instanceof ProblemDetail problem) {
            String code = factory.decorate(problem, problem.getStatus() >= 500 ? UNEXPECTED_ERROR : REQUEST_REJECTED,
                    exception, request).getProperties().get(ProblemDetailFactory.CODE).toString();
            if (problem.getStatus() < 500) {
                log.debug("error response [{}] status {}: {}", code, problem.getStatus(), problem.getDetail());
            }
            return ProblemDetailFactory.wrap(problem, entity.getHeaders(), exception);
        }
        ResponseStatus annotated = AnnotatedElementUtils.findMergedAnnotation(exception.getClass(), ResponseStatus.class);
        if (annotated != null) {
            int status = annotated.code().value();
            String code = status >= 500 ? UNEXPECTED_ERROR : REQUEST_REJECTED;
            if (status >= 500) {
                log.error("error response [{}] status {}", code, status, exception);
            } else {
                log.debug("error response [{}] status {}", code, status);
            }
            String reason = StringUtils.hasText(annotated.reason()) ? annotated.reason() : null;
            return respond(status, code, reason, exception, request);
        }
        // Data-access failures (D111): 409 for a conflict with stored data, 503 for an unavailable database, with a
        // fixed detail (their messages carry SQL and constraint names). Logged once on the request's trace.
        Optional<DataAccessFailure> dataFailure = DataAccessFailure.classify(exception);
        if (dataFailure.isPresent()) {
            DataAccessFailure failure = dataFailure.get();
            log.warn("data access failure [{}]", failure.code(), exception);
            return respond(failure.status(), failure.code(), failure.detail(), exception, request);
        }
        log.error("unhandled exception [{}]", UNEXPECTED_ERROR, exception);
        return respond(500, UNEXPECTED_ERROR, null, exception, request);
    }

    private ErrorResponse respond(int status, String code, String detail, Exception exception, HttpServletRequest request) {
        ProblemDetail problem = factory.create(status, code, detail, request.getRequestURI(), exception, request);
        return ProblemDetailFactory.wrap(problem, null, exception);
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

    /**
     * Spring MVC's standard mapping of its own exceptions (and of {@link ErrorResponse} exceptions), used as a
     * delegate rather than as advice, with the platform's validation and unreadable-body bodies. Not a bean.
     */
    private static final class FrameworkErrors extends ResponseEntityExceptionHandler {

        private final ErrorsProperties properties;
        private final ProblemDetailFactory factory;

        private FrameworkErrors(ErrorsProperties properties, ProblemDetailFactory factory) {
            this.properties = properties;
            this.factory = factory;
        }

        // The standard response for a Spring MVC or ErrorResponse exception; null for any other exception.
        ResponseEntity<Object> map(Exception exception, WebRequest request) throws Exception {
            try {
                return handleException(exception, request);
            } catch (Exception rethrown) {
                if (rethrown == exception) {
                    return null; // not one of Spring MVC's exceptions
                }
                throw rethrown;
            }
        }

        @Override
        protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                HttpStatusCode statusCode, WebRequest request) {
            ResponseEntity<Object> entity = super.handleExceptionInternal(exception, body, headers, statusCode, request);
            if (statusCode.value() >= 500) {
                // Framework and service 5xx reach only this hook: log them here, once, on the request's trace.
                String code = entity != null && entity.getBody() instanceof ProblemDetail problem
                        && ProblemDetailFactory.serviceCode(problem) != null
                        ? ProblemDetailFactory.serviceCode(problem) : UNEXPECTED_ERROR;
                log.error("error response [{}] status {}", code, statusCode.value(), exception);
            }
            return entity;
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
            return validationFailed(errors, headers);
        }

        /**
         * Spring MVC's native method validation (constraints on {@code @RequestParam}/{@code @PathVariable} or on
         * {@code @Valid} arguments of a controller without class-level {@code @Validated}) maps to the same 400
         * {@code errors[]} shape as body validation. Return-value validation is a server bug and keeps its 500.
         */
        @Override
        protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                HttpHeaders headers, HttpStatusCode status, WebRequest request) {
            if (!properties.mapValidation() || status.value() >= 500) {
                return super.handleHandlerMethodValidationException(exception, headers, status, request);
            }
            List<Map<String, Object>> errors = new ArrayList<>();
            for (ParameterValidationResult result : exception.getParameterValidationResults()) {
                String parameter = result.getMethodParameter().getParameterName();
                if (result instanceof ParameterErrors parameterErrors) {
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
            return validationFailed(errors, headers);
        }

        @Override
        protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                HttpHeaders headers, HttpStatusCode status, WebRequest request) {
            log.debug("unreadable request body [{}]", MALFORMED_REQUEST, exception);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, MALFORMED_REQUEST_DETAIL);
            problem.setProperty(ProblemDetailFactory.CODE, MALFORMED_REQUEST);
            return ResponseEntity.status(status).headers(headers).body(problem);
        }

        private static ResponseEntity<Object> validationFailed(List<Map<String, Object>> errors, HttpHeaders headers) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), "Validation failed.");
            problem.setProperty(ProblemDetailFactory.CODE, VALIDATION_FAILED);
            problem.setProperty("errors", errors);
            return ResponseEntity.badRequest().headers(headers).body(problem);
        }
    }
}
