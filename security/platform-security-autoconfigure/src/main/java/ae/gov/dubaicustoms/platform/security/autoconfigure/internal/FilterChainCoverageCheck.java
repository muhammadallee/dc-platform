package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

/**
 * Refuses to start when a request could match no filter chain. Spring Security lets such a request through with no security at
 * all, which is what happens when a consumer's only chain has a {@code securityMatcher}, since any chain of theirs replaces the
 * platform's.
 */
public final class FilterChainCoverageCheck implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(FilterChainCoverageCheck.class);

    private final ObjectProvider<FilterChainProxy> proxy;
    private final boolean allowPartial;

    /**
     * Creates the check.
     *
     * @param proxy the filter chain proxy, if web security is active
     * @param allowPartial accept chains that leave requests unmatched, with a warning
     */
    public FilterChainCoverageCheck(ObjectProvider<FilterChainProxy> proxy, boolean allowPartial) {
        this.proxy = proxy;
        this.allowPartial = allowPartial;
    }

    @Override
    public void afterSingletonsInstantiated() {
        FilterChainProxy chains = proxy.getIfAvailable();
        if (chains == null) {
            return;
        }
        List<SecurityFilterChain> list = chains.getFilterChains();
        if (!list.isEmpty() && coversEverything(list.getLast())) {
            return;
        }
        if (allowPartial) {
            log.warn("dc.platform.security.allow-partial-chains is set: requests no filter chain matches are not secured");
            return;
        }
        throw new InvalidSecurityConfigurationException("The last SecurityFilterChain does not match every request, so any other "
                + "request would reach the application with no security at all.",
                "Add a final chain with no securityMatcher, or set dc.platform.security.allow-partial-chains=true if a chain "
                        + "with a custom matcher does in fact cover everything.");
    }

    private static boolean coversEverything(SecurityFilterChain chain) {
        return chain instanceof DefaultSecurityFilterChain known && known.getRequestMatcher() instanceof AnyRequestMatcher;
    }
}
