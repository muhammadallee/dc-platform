package ae.gov.dubaicustoms.platform.authz.autoconfigure;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import ae.gov.dubaicustoms.platform.authz.autoconfigure.internal.RequiresPermissionAdvisorFactory;
import ae.gov.dubaicustoms.platform.authz.autoconfigure.internal.RequiresPermissionAutoProxyRegistrar;
import ae.gov.dubaicustoms.platform.authz.autoconfigure.internal.RolesClaimPermissionProvider;
import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;

/*
 * Activates when: RequiresPermission + PermissionEvaluatorProvider (this cap's own api/spi) +
 *                 CurrentUserAccessor (security-api) on the classpath AND
 *                 dc.platform.authz.enabled != false
 * Backs off when: user defines a PermissionEvaluatorProvider bean (default provider) or the
 *                 requiresPermissionAdvisor is not created when no CurrentUserAccessor bean exists
 *                 (security capability not actually active in this application)
 * Beans: permissionEvaluatorProvider — RolesClaimPermissionProvider (configurable claim name);
 *        requiresPermissionAdvisor — Spring AOP Advisor bridging @RequiresPermission to the
 *                 ordered PermissionEvaluatorProvider beans; unauthenticated callers get
 *                 InsufficientAuthenticationException (401 via the security capability's chain),
 *                 unauthorized callers get AccessDeniedException (403);
 *        authzCapabilityDescriptor — one line in the startup capability banner
 * Order: @Import registers a plain (non-AspectJ) InfrastructureAdvisorAutoProxyCreator via
 *        AopConfigUtils's cooperative escalation protocol — the same mechanism
 *        @EnableMethodSecurity uses — so proxying works even if the security capability's method
 *        security has not separately enabled it, and the two never register conflicting creators.
 *        afterName (by string, not class literal: autoconfigure -> autoconfigure of another
 *        capability has no allowance in the dependency constitution, even same-capability) ensures
 *        PlatformSecurityAutoConfiguration's currentUserAccessor bean definition is already
 *        registered when requiresPermissionAdvisor's @ConditionalOnBean(CurrentUserAccessor.class)
 *        is evaluated — Boot only sees PREVIOUSLY processed auto-configurations' bean definitions.
 */
@AutoConfiguration(afterName = "ae.gov.dubaicustoms.platform.security.autoconfigure.PlatformSecurityAutoConfiguration")
@ConditionalOnClass({RequiresPermission.class, PermissionEvaluatorProvider.class, CurrentUserAccessor.class})
@ConditionalOnProperty(prefix = "dc.platform.authz", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuthzProperties.class)
@Import(RequiresPermissionAutoProxyRegistrar.class)
public class PlatformAuthzAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PermissionEvaluatorProvider permissionEvaluatorProvider(AuthzProperties properties) {
        return new RolesClaimPermissionProvider(properties.rolesClaim());
    }

    // ROLE_INFRASTRUCTURE: InfrastructureAdvisorAutoProxyCreator (registered by our own
    // RequiresPermissionAutoProxyRegistrar, or already present via @EnableMethodSecurity) only
    // considers Advisor beans with this role — a plain application-role bean is silently ignored.
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnBean(CurrentUserAccessor.class)
    @ConditionalOnMissingBean(name = "requiresPermissionAdvisor")
    Advisor requiresPermissionAdvisor(CurrentUserAccessor accessor, ObjectProvider<PermissionEvaluatorProvider> providers) {
        return RequiresPermissionAdvisorFactory.create(accessor, providers.orderedStream().toList());
    }

    @Bean
    CapabilityDescriptor authzCapabilityDescriptor() {
        return new CapabilityDescriptor("authz", "ACTIVE", "");
    }
}
