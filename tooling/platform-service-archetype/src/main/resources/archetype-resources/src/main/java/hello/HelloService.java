package ${package}.hello;

import org.springframework.stereotype.Service;

/**
 * Trivial business logic behind {@code GET /hello}. Replace with your domain services — keep
 * cross-cutting concerns (errors, security, messaging, persistence) on the platform, not here.
 */
@Service
public class HelloService {

    public String greeting(String name) {
        return "Hello, " + name + "!";
    }
}
