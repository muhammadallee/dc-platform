package ae.gov.dubaicustoms.platform.resilience.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.resilience.RetryableOperation;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.function.Supplier;

/**
 * Bridges the platform's {@link RetryableOperation} contract onto a Resilience4j {@link RetryRegistry}:
 * exists so application code has a name-driven programmatic retry without depending on Resilience4j
 * types. A {@code Retry} is resolved from the registry by name (creating it from the registry's
 * default config on first use), so the same names configured for {@code @Retry} annotations apply.
 */
public final class DefaultRetryableOperation implements RetryableOperation {

    private final RetryRegistry registry;

    /**
     * @param registry the retry registry to resolve named policies from
     */
    public DefaultRetryableOperation(RetryRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> T call(String name, Supplier<T> action) {
        Retry retry = registry.retry(name);
        return Retry.decorateSupplier(retry, action).get();
    }
}
