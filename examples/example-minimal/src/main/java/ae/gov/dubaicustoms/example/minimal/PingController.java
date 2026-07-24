package ae.gov.dubaicustoms.example.minimal;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.errors.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two endpoints that together demonstrate the floor:
 * <ul>
 *   <li>{@code GET /ping} — a healthy response; the correlation ID is on the log line and the
 *       {@code X-Correlation-Id} response header without any code here doing it;</li>
 *   <li>{@code GET /widgets/{id}} — always absent, so it throws {@link NotFoundException}. The
 *       platform (not this class) maps it to a 404 {@code application/problem+json} body carrying the
 *       {@link ErrorCode}. There is deliberately no {@code @RestControllerAdvice} in this service.</li>
 * </ul>
 */
@RestController
public class PingController {

    /** This service's error namespace; see the platform error-code registry. */
    private static final ErrorCode WIDGET_NOT_FOUND = new ErrorCode("DC-XMPL-0404");

    @GetMapping("/ping")
    public Pong ping() {
        return new Pong("ok");
    }

    @GetMapping("/widgets/{id}")
    public Widget getWidget(@PathVariable String id) {
        // The minimal example has no persistence; every widget is "missing" on purpose so the
        // problem-response path is always exercised.
        throw new NotFoundException(WIDGET_NOT_FOUND, "widget %s not found".formatted(id));
    }

    /** Response body for {@code GET /ping}. */
    public record Pong(String status) {
    }

    /** Would-be response body for {@code GET /widgets/{id}}; never actually returned here. */
    public record Widget(String id, String name) {
    }
}
