package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.flags.FeatureFlags;
import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.DefaultFeatureFlags;
import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.EvaluationContextProvider;
import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.FeatureGateAdvisorFactory;
import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.FeatureGateAutoProxyRegistrar;
import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.PlatformFlagsEndpoint;
import ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;

/*
 * Activates when: a FlagProvider bean exists (contributed by the OpenFeature or in-memory provider
 *                 config, or by the user) AND dc.platform.flags.enabled != false.
 * Backs off when: the user defines their own FeatureFlags / featureGateAdvisor / EvaluationContextProvider.
 * Beans: featureFlags — DefaultFeatureFlags over the ordered FlagProviders and the evaluation context;
 *        anonymousEvaluationContextProvider — the context fallback when security is absent;
 *        flagsCapabilityDescriptor — one line in the startup capability banner naming the provider(s);
 *        featureGateAdvisor — a plain AOP advisor enforcing @FeatureGate;
 *        platformFlagsEndpoint — (actuator + in-memory provider only) the platformflags endpoint.
 * Order: after the provider and security auto-configurations so their beans are registered first.
 */
@AutoConfiguration(after = {
        OpenFeatureFlagProviderAutoConfiguration.class,
        InMemoryFlagProviderAutoConfiguration.class,
        FlagsSecurityAutoConfiguration.class})
@ConditionalOnClass(FeatureFlags.class)
@ConditionalOnProperty(prefix = "dc.platform.flags", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlagsProperties.class)
public class PlatformFlagsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EvaluationContextProvider.class)
    EvaluationContextProvider anonymousEvaluationContextProvider() {
        return EvaluationContext::anonymous;
    }

    @Bean
    @ConditionalOnBean(FlagProvider.class)
    @ConditionalOnMissingBean
    FeatureFlags featureFlags(ObjectProvider<FlagProvider> providers, EvaluationContextProvider contextProvider) {
        return new DefaultFeatureFlags(providers.orderedStream().toList(), contextProvider);
    }

    @Bean
    @ConditionalOnBean(FlagProvider.class)
    CapabilityDescriptor flagsCapabilityDescriptor(ObjectProvider<FlagProvider> providers) {
        // Name the active provider(s), e.g. InMemoryFlagProvider -> "inmemory".
        String detail = providers.orderedStream()
                .map(provider -> provider.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("flagprovider", ""))
                .collect(Collectors.joining("+"));
        return new CapabilityDescriptor("flags", "ACTIVE", detail);
    }

    /*
     * The @FeatureGate advisor: a plain auto-proxy (decision D26) that skips a gated method and returns
     * a neutral value when its flag is off. Gated on a FlagProvider (the same condition as FeatureFlags,
     * which it depends on), contributed by a provider config that runs first.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(FeatureGateAutoProxyRegistrar.class)
    static class FeatureGateConfiguration {

        // ROLE_INFRASTRUCTURE: the InfrastructureAdvisorAutoProxyCreator only considers Advisor beans
        // with this role — a plain application-role bean would be silently ignored (decision D26).
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnBean(FlagProvider.class)
        @ConditionalOnMissingBean(name = "featureGateAdvisor")
        Advisor featureGateAdvisor(FeatureFlags featureFlags) {
            return FeatureGateAdvisorFactory.create(featureFlags);
        }
    }

    /*
     * The platformflags actuator endpoint: only when actuator is present and the in-memory provider is
     * active (it is the mutable one the write/delete operations drive).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class FlagsEndpointConfiguration {

        @Bean
        @ConditionalOnBean(InMemoryFlagProvider.class)
        @ConditionalOnMissingBean
        @ConditionalOnAvailableEndpoint(endpoint = PlatformFlagsEndpoint.class)
        PlatformFlagsEndpoint platformFlagsEndpoint(InMemoryFlagProvider provider) {
            return new PlatformFlagsEndpoint(provider);
        }
    }
}
