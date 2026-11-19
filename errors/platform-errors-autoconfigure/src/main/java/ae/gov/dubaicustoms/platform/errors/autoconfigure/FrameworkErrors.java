package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring MVC's standard mapping of its own exceptions (and of {@link ErrorResponse} exceptions), with the platform's
 * validation and unreadable-body bodies. {@link PlatformExceptionHandler} calls it as a delegate: it is a bean but not
 * a {@code @ControllerAdvice}, so it handles nothing on its own. Being a {@link ResponseEntityExceptionHandler} bean,
 * it makes Boot's {@code ProblemDetailsExceptionHandler} ({@code spring.mvc.problemdetails.enabled=true}) back off, as
 * the platform advice did when it extended that class itself: otherwise Boot's {@code @Order(0)} advice would answer
 * every framework and {@code ErrorResponse} exception first, without the platform contract.
 */
final class FrameworkErrors extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    private final ErrorsProperties properties;
    private final ProblemDetailFactory factory;

    FrameworkErrors(ErrorsProperties properties, ProblemDetailFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    // The standard response for a Spring MVC or ErrorResponse exception; null for any other exception.
    /**
     * What Spring MVC's standard mapping made of an exception.
     *
     * @param recognised whether it is one of Spring MVC's exceptions (or an {@link ErrorResponse})
     * @param entity the response to send; {@code null} for a recognised exception means "write nothing": the client
     *        is gone ({@code AsyncRequestNotUsableException}) or the response is already committed
     */
    record Mapped(boolean recognised, ResponseEntity<Object> entity) {
    }

    Mapped map(Exception exception, WebRequest request) throws Exception {
        try {
            return new Mapped(true, handleException(exception, request));
        } catch (Exception rethrown) {
            if (rethrown == exception) {
                return new Mapped(false, null); // not one of Spring MVC's exceptions
            }
            throw rethrown;
        }
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // An ErrorResponse exception arrives without a body: Spring would localise the exception's own ProblemDetail
        // in place (updateAndGetBody), and a service may throw one instance from many requests. Localise a copy.
        Object response = body == null && exception instanceof ErrorResponse errorResponse
                ? localisedCopy(errorResponse, getMessageSource(), LocaleContextHolder.getLocale()) : body;
        ResponseEntity<Object> entity = super.handleExceptionInternal(exception, response, headers, statusCode, request);
        if (entity == null) {
            return null; // the response is committed: Spring logged it and writes nothing
        }
        if (statusCode.value() >= 500) {
            // Framework and service 5xx reach only this hook: log them here, once, on the request's trace.
            String code = entity != null && entity.getBody() instanceof ProblemDetail problem
                    && ProblemDetailFactory.serviceCode(problem) != null
                    ? ProblemDetailFactory.serviceCode(problem) : PlatformExceptionHandler.UNEXPECTED_ERROR;
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
        return validationFailed(exception, errors, headers, request);
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
        return validationFailed(exception, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.debug("unreadable request body [{}]", PlatformExceptionHandler.MALFORMED_REQUEST, exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, PlatformExceptionHandler.MALFORMED_REQUEST_DETAIL);
        problem.setProperty(ProblemDetailFactory.CODE, PlatformExceptionHandler.MALFORMED_REQUEST);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    // Through handleExceptionInternal, so a committed response is left alone here too.
    private ResponseEntity<Object> validationFailed(Exception exception, List<Map<String, Object>> errors,
            HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), "Validation failed.");
        problem.setProperty(ProblemDetailFactory.CODE, PlatformExceptionHandler.VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, HttpStatusCode.valueOf(400), request);
    }

    /**
     * A copy of an {@link ErrorResponse}'s body, localised the way Spring localises the original: the type, detail and
     * title message codes ({@code ErrorResponse.updateAndGetBody}), and for a {@link ResponseStatusException} whose
     * detail is its reason, the reason resolved as a message code with the reason itself as the fallback. A subclass
     * that overrides {@code updateAndGetBody} with other logic gets only this standard part.
     */
    static ProblemDetail localisedCopy(ErrorResponse error, MessageSource messages, Locale locale) {
        ProblemDetail original = error.getBody();
        ProblemDetail copy = ProblemDetailFactory.copyOf(original);
        String reason = error instanceof ResponseStatusException status ? status.getReason() : null;
        boolean reasonIsDetail = reason != null && reason.equals(original.getDetail());
        if (messages != null) {
            String type = messages.getMessage(error.getTypeMessageCode(), null, null, locale);
            if (type != null) {
                copy.setType(URI.create(type));
            }
            String detail = messages.getMessage(error.getDetailMessageCode(),
                    error.getDetailMessageArguments(messages, locale), null, locale);
            if (detail != null) {
                copy.setDetail(detail);
            }
            String title = messages.getMessage(error.getTitleMessageCode(), null, null, locale);
            if (title != null) {
                copy.setTitle(title);
            }
        }
        if (reasonIsDetail) {
            String resolved = messages == null ? null
                    : messages.getMessage(reason, error.getDetailMessageArguments(messages, locale), null, locale);
            copy.setDetail(Objects.requireNonNullElse(resolved, reason));
        }
        return copy;
    }
}
