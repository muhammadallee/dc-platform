package ae.gov.dubaicustoms.platform.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * Marker: the annotated element is internal to the platform and carries no compatibility
 * guarantees; do not use it outside the module that declares it.
 *
 * <p>Used on the rare member that must be visible across packages for the platform's own wiring
 * (for example {@code RequestContext.open}) but is not part of the supported surface. Anything
 * under an {@code .internal} package is internal without needing this marker. Thread-safe
 * (annotations are inert).
 *
 * @since 0.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface PlatformInternal {
}
