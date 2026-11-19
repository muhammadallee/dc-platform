package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The servlet error page as an RFC-9457 problem response: whatever reaches the container's error
 * dispatch — a filter's {@code response.sendError(…)}, an exception escaping the filter chain — is
 * rendered with the same contract as every other platform error ({@code code}, {@code traceId},
 * {@code timestamp}, {@code retryable}, {@code instance} = the original request URI), always as
 * {@code application/problem+json} regardless of the {@code Accept} header. Replaces Boot's
 * {@code BasicErrorController}; define your own {@link ErrorController} bean to replace this one.
 *
 * <p>4xx bodies carry the status's reason phrase and {@code DC-CORE-0499}; 5xx bodies the generic
 * detail and {@code DC-CORE-0500}, with any escaping exception logged in full.
 *
 * <p>The application's {@code ErrorResponse.Interceptor}s (those Spring MVC applies to controller errors) run here
 * too, in order, on an {@link ErrorResponseException} whose cause is the escaping exception ({@code null} for a bare
 * {@code sendError}). As in Spring MVC, an interceptor that throws stops the rest; here it is also logged at WARN, and
 * the platform body is still written. Thread-safe.
 *
 * @since 1.0.0
 */
@Controller
public class PlatformErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(PlatformErrorController.class);

    private final ProblemDetailFactory factory;
    private final Supplier<List<HttpMessageConverter<?>>> converters;
    private final Supplier<List<ErrorResponse.Interceptor>> interceptors;

    /**
     * Creates the controller.
     *
     * @param factory the shared problem-body assembler; never {@code null}
     * @param converters supplies the application's message converters (one must write problem JSON),
     *        resolved per error so the controller never depends on MVC's initialization order
     * @param interceptors supplies the application's error-response interceptors, resolved per error likewise
     */
    public PlatformErrorController(ProblemDetailFactory factory, Supplier<List<HttpMessageConverter<?>>> converters,
            Supplier<List<ErrorResponse.Interceptor>> interceptors) {
        this.factory = factory;
        this.converters = converters;
        this.interceptors = interceptors;
    }

    /**
     * Renders the current error dispatch.
     *
     * @param request the error-dispatch request carrying the {@code jakarta.servlet.error.*} attributes
     * @param response the response to write
     * @throws IOException if the body cannot be written
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int status = statusOf(request);
        Throwable failure = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        // A container ERROR dispatch carries the failing URI; a direct request to the error path does not.
        Object failingUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = failingUri instanceof String uri ? uri : request.getRequestURI();
        String code = status >= 500 ? PlatformExceptionHandler.UNEXPECTED_ERROR : PlatformExceptionHandler.REQUEST_REJECTED;
        if (status >= 500) {
            log.error("servlet error dispatch [{}] status {} for {}", code, status, path, failure);
        }
        ProblemDetail problem = factory.create(status, code, reasonPhrase(status), path, failure, request);
        intercept(problem, ProblemDetailFactory.wrap(problem, null, failure));
        response.setStatus(status);
        write(problem, response);
    }

    // Spring MVC's semantics (AbstractMessageConverterMethodProcessor): in order, stopping at the first that throws.
    private void intercept(ProblemDetail problem, ErrorResponse errorResponse) {
        for (ErrorResponse.Interceptor interceptor : interceptors.get()) {
            try {
                interceptor.handleError(problem, errorResponse);
            } catch (RuntimeException e) {
                log.warn("error-response interceptor {} failed; the remaining interceptors are skipped",
                        interceptor.getClass().getName(), e);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void write(ProblemDetail problem, HttpServletResponse response) throws IOException {
        for (HttpMessageConverter<?> converter : converters.get()) {
            if (converter.canWrite(ProblemDetail.class, MediaType.APPLICATION_PROBLEM_JSON)) {
                ((HttpMessageConverter<ProblemDetail>) converter)
                        .write(problem, MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(response));
                return;
            }
        }
        throw new IllegalStateException("no message converter can write application/problem+json");
    }

    private static int statusOf(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return status instanceof Integer value ? value : 500;
    }

    private static String reasonPhrase(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known == null ? "Request rejected." : known.getReasonPhrase();
    }
}
