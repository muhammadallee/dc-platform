package ${package}.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample endpoint. {@code GET /hello?name=…} returns a greeting.
 *
 * <p>Authorization: endpoints are authenticated by default. To require a specific permission, add
 * {@code ae.gov.dubaicustoms.platform.authz.RequiresPermission} — do not write your own security
 * checks. Example (uncomment and add the {@code platform-starter-security-authz} dependency):
 * <pre>{@code
 * // @RequiresPermission("hello:read")
 * }</pre>
 */
@RestController
public class HelloController {

    private final HelloService service;

    public HelloController(HelloService service) {
        this.service = service;
    }

    // @RequiresPermission("hello:read")   // uncomment to enforce a permission (needs starter-security-authz)
    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "world") String name) {
        return service.greeting(name);
    }
}
