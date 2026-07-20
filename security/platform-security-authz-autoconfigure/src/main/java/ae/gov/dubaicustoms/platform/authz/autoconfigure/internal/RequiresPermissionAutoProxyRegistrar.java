package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers the plain (non-AspectJ) {@code InfrastructureAdvisorAutoProxyCreator} via
 * {@link AopConfigUtils}'s cooperative registration protocol — the same mechanism
 * {@code @EnableMethodSecurity} and {@code @EnableTransactionManagement} use, so escalation
 * (whichever auto-proxy creator asks for the higher-priority/class-proxying variant wins) works
 * correctly regardless of registration order. A hand-written {@code @Bean} would not participate
 * in that protocol (the escalation check reads the bean class Spring associates with the
 * well-known name, which a static {@code @Bean} factory method does not populate the way this
 * registrar does).
 */
public final class RequiresPermissionAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
    }
}
