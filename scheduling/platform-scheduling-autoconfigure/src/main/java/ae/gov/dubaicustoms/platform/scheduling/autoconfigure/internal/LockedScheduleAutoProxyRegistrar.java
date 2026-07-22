package ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers the plain (non-AspectJ) {@code InfrastructureAdvisorAutoProxyCreator} via
 * {@link AopConfigUtils}'s cooperative registration protocol — the same mechanism
 * {@code @EnableMethodSecurity}/{@code @EnableTransactionManagement} use — so proxying of
 * {@code @LockedSchedule} methods works without pulling in {@code aspectjweaver} (decision D26) and
 * escalation with any other auto-proxy creator resolves correctly regardless of order.
 */
public final class LockedScheduleAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
    }
}
