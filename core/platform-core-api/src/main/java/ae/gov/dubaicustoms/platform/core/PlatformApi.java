package ae.gov.dubaicustoms.platform.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * Marker: the annotated type is stable public API covered by the platform's SemVer guarantees.
 *
 * <p>Types carrying this annotation only change binary-incompatibly in a major release; the
 * japicmp gate enforces it once a release baseline exists. Thread-safe (annotations are inert).
 *
 * @since 0.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface PlatformApi {
}
