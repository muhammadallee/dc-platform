package ae.gov.dubaicustoms.example.minimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two endpoints that together demonstrate the floor:
 * <ul>
 *   <li>{@code GET /ping} — a healthy response; the request's W3C trace ID is on the log line
 *       ({@code traceId}) without any code here doing it;</li>
 *   <li>{@code GET /widgets/{id}} — always absent, so it throws Spring's {@link ErrorResponseException}
 *       with this service's error code as the {@code code} property. The platform (not this class) adds
 *       {@code traceId}, {@code timestamp} and {@code retryable} to the 404 {@code application/problem+json}
 *       body. There is deliberately no {@code @RestControllerAdvice} in this service.</li>
 * </ul>
 */
@RestController
public class PingController {

    /** This service's error code for a missing widget. */
    private static final String WIDGET_NOT_FOUND = "DC-XMPL-0404";

    @GetMapping("/ping")
    public Pong ping() {
        return new Pong("ok");
    }

    @GetMapping("/widgets/{id}")
    public Widget getWidget(@PathVariable String id) {
        // The minimal example has no persistence; every widget is "missing" on purpose so the
        // problem-response path is always exercised.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "widget %s not found".formatted(id));
        problem.setProperty("code", WIDGET_NOT_FOUND);
        throw new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }

    /** Response body for {@code GET /ping}. */
    public record Pong(String status) {
    }

    /** Would-be response body for {@code GET /widgets/{id}}; never actually returned here. */
    public record Widget(String id, String name) {
    }
}
