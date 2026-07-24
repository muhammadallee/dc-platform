package ae.gov.dubaicustoms.example.minimal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The smallest possible platform service: it depends on {@code platform-starter-core},
 * {@code -errors}, and {@code -logging} and nothing else. Booting it proves the platform floor —
 * every request gets a correlation ID, logs are structured JSON, and any thrown
 * {@link ae.gov.dubaicustoms.platform.errors.BusinessException} renders as an RFC-9457
 * {@code application/problem+json} response — without the service writing a single line of
 * cross-cutting code.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
