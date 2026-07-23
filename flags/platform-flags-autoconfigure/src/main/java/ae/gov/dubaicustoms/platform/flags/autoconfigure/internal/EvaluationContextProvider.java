package ae.gov.dubaicustoms.platform.flags.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import java.util.function.Supplier;

/**
 * Supplies the {@link EvaluationContext} for the current evaluation. A distinct bean type (rather than
 * a raw {@code Supplier<EvaluationContext>}) so the platform can back off to it and swap the anonymous
 * default for a security-aware one when the security capability is present.
 */
@FunctionalInterface
public interface EvaluationContextProvider extends Supplier<EvaluationContext> {
}
