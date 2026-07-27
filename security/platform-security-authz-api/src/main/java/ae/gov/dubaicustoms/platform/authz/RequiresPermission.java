package ae.gov.dubaicustoms.platform.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * Declares that a method (or every method of a type) requires the named permission. Enforced by
 * a method-security aspect bridging to a {@code PermissionEvaluatorProvider}
 * (platform-security-authz-spi); the default provider maps permissions from a roles claim.
 *
 * <pre>{@code
 * @RequiresPermission("orders:read")
 * Order get(String id) { ... }
 * }</pre>
 *
 * <p>Callers without the permission receive a 403; anonymous callers receive a 401 (handled by
 * the security capability's baseline chain, which always runs first). Thread-safe (annotations
 * are inert).
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface RequiresPermission {

    /**
     * The permission required to invoke the annotated element.
     *
     * @return the permission string, e.g. {@code "orders:read"}; never blank
     */
    String value();
}
