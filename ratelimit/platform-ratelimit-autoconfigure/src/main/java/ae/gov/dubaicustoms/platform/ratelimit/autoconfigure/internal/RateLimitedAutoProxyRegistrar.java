package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers the plain (non-AspectJ) {@code InfrastructureAdvisorAutoProxyCreator} via
 * {@link AopConfigUtils}'s cooperative registration protocol (decision D26), so {@code @RateLimited}
 * methods are proxied without pulling in {@code aspectjweaver} and escalation with any other
 * auto-proxy creator resolves correctly regardless of order.
 */
public final class RateLimitedAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
    }
}
