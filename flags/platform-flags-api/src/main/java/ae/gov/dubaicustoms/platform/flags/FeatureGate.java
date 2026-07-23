package ae.gov.dubaicustoms.platform.flags;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a method behind a feature flag: the method body runs only when {@link #value()} is enabled;
 * otherwise it is skipped and a neutral value is returned.
 *
 * <pre>{@code
 * @FeatureGate("new-clearance-flow")
 * public Receipt clearViaNewFlow(Declaration d) { ... }
 * }</pre>
 *
 * <p><b>Skip behaviour</b> (when the flag is off) depends on the method's return type:
 * <ul>
 *   <li>{@code boolean}/{@code Boolean} → {@code false}</li>
 *   <li>{@link java.util.Optional} → {@link java.util.Optional#empty()}</li>
 *   <li>any other reference type → {@code null}</li>
 *   <li>{@code void} → nothing happens (a no-op)</li>
 *   <li>a primitive number → its zero value; {@code char} → {@code '\0'}</li>
 * </ul>
 * A gated method whose skip value cannot be expressed (e.g. a non-void primitive you rely on) should
 * return a reference or {@code Optional} type instead. Apply to public methods of Spring-managed beans;
 * self-invocation within the same bean is not gated (standard proxy limitation).
 *
 * @since 0.2.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureGate {

    /**
     * The flag key that must be enabled for the annotated method to run.
     *
     * @return the flag key
     */
    String value();
}
