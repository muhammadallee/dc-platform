package ${package}.hello;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Trivial business logic behind {@code GET /hello}. Replace with your domain services — keep
 * cross-cutting concerns (errors, security, messaging, persistence) on the platform, not here.
 *
 * <p>Logging follows the platform rules: SLF4J, a constant message, no user input or secrets in the
 * line, and no hand-added correlation id (the platform puts it on every event).
 */
@Service
public class HelloService {

    private static final Logger log = LoggerFactory.getLogger(HelloService.class);

    public String greeting(String name) {
        log.info("Composing greeting");
        return "Hello, " + name + "!";
    }
}
