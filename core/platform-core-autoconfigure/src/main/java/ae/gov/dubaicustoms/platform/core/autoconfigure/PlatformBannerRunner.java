package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.autoconfigure.internal.PlatformBanner;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

/**
 * Logs the platform startup banner: one INFO line listing every {@link CapabilityDescriptor} bean,
 * sorted by capability name, once the application context is ready.
 *
 * <p>Registered by {@code PlatformBannerAutoConfiguration}, which backs off when the application
 * defines its own {@code PlatformBannerRunner} bean (by type). Subclass it and override
 * {@link #describe(List)} to change the line, or set {@code dc.platform.core.banner-enabled=false}
 * and register an unrelated {@link ApplicationRunner} to replace the banner entirely:
 * <pre>{@code
 * @Bean
 * PlatformBannerRunner platformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors) {
 *     return new PlatformBannerRunner(descriptors) {
 *         @Override
 *         protected String describe(List<CapabilityDescriptor> active) {
 *             return "my-service " + super.describe(active);
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>Logs under the {@code ae.gov.dubaicustoms.platform.core.autoconfigure.PlatformBannerAutoConfiguration}
 * category.
 *
 * <p>Thread-safe; descriptors are read when {@link #run(ApplicationArguments)} is called. Constructor
 * arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
public class PlatformBannerRunner implements ApplicationRunner {

    // Keeps the category the banner has always logged under, so consumer log configuration still applies.
    private static final Logger log = LoggerFactory.getLogger(PlatformBannerAutoConfiguration.class);

    private final ObjectProvider<CapabilityDescriptor> descriptors;
    private final Environment environment;

    /**
     * Creates the runner.
     *
     * @param descriptors the capability descriptors to report; never {@code null}
     * @param environment the environment whose {@code dc.platform.core.capabilities.*} overrides apply; may be
     *        {@code null} for none
     */
    public PlatformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors, Environment environment) {
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors must not be null");
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("{}", describe(CapabilityOverrides.apply(descriptors.orderedStream().toList(), environment)));
    }

    /**
     * Formats the banner line.
     *
     * @param active the capability descriptors present in the context; never {@code null}
     * @return the line to log, for example {@code platform: core[ACTIVE], errors[ACTIVE]}
     */
    protected String describe(List<CapabilityDescriptor> active) {
        return PlatformBanner.format(active);
    }
}
