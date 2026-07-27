package ae.gov.dubaicustoms.platform.flags;

import org.apiguardian.api.API;

/**
 * Feature-flag facade: ask whether a flag is on, or read a typed flag value with a caller-supplied
 * default.
 *
 * <pre>{@code
 * if (featureFlags.enabled("new-clearance-flow")) {
 *     ...
 * }
 * int batchSize = featureFlags.value("import.batch-size", 100);
 * }</pre>
 *
 * <p>Evaluation is against the flag providers on the classpath (an in-memory provider by default, an
 * OpenFeature adapter when present); the platform resolves the current user/tenant into the evaluation
 * context when the security capability is available.
 *
 * <p><b>Thread-safe:</b> a single {@code FeatureFlags} bean is shared across request threads.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public interface FeatureFlags {

    /**
     * Whether the boolean flag {@code flag} is enabled. A flag that no provider knows is treated as
     * disabled ({@code false}).
     *
     * @param flag the flag key; never {@code null}
     * @return {@code true} if the flag resolves to on, otherwise {@code false}
     */
    boolean enabled(String flag);

    /**
     * The value of {@code flag} as the same type as {@code defaultValue}, or {@code defaultValue} when
     * no provider knows the flag or its value cannot be coerced to that type.
     *
     * @param flag the flag key; never {@code null}
     * @param defaultValue the value to return when the flag is unknown or untypable; never {@code null}
     * @param <T> the value type, inferred from {@code defaultValue}
     * @return the resolved value, or {@code defaultValue}
     */
    <T> T value(String flag, T defaultValue);
}
