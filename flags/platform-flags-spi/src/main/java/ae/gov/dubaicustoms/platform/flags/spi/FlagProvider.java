package ae.gov.dubaicustoms.platform.flags.spi;

import java.util.Optional;
import org.apiguardian.api.API;

/**
 * Pluggable flag backend behind {@code FeatureFlags}: resolve a flag against an
 * {@link EvaluationContext}. Implementations back the capability (an in-memory provider by default, an
 * OpenFeature adapter when present).
 *
 * <pre>{@code
 * public final class StaticFlagProvider implements FlagProvider {
 *     public Optional<FlagValue> evaluate(String flag, EvaluationContext ctx) {
 *         return Optional.ofNullable(values.get(flag)).map(FlagValue::new);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Implementation requirements:</b> implementations must be thread-safe and total — return
 * {@link Optional#empty()} for a flag they do not know (rather than throwing) so the platform can fall
 * back to the caller's default or the next provider. They must tolerate an {@link EvaluationContext}
 * with no user and no tenant. New {@code default} methods may be added in minor releases.
 *
 * @since 0.2.0
 */
@FunctionalInterface
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public interface FlagProvider {

    /**
     * Resolves {@code flag} against {@code context}.
     *
     * @param flag the flag key; never {@code null}
     * @param context the evaluation context; never {@code null}
     * @return the resolved value, or {@link Optional#empty()} if this provider does not know the flag
     */
    Optional<FlagValue> evaluate(String flag, EvaluationContext context);
}
