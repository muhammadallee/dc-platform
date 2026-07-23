package ae.gov.dubaicustoms.platform.audit.autoconfigure;

import ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.ActorResolver;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the security capability's CurrentUserAccessor is on the classpath, a bean of it
 *                 exists, and dc.platform.audit.enabled != false.
 * Backs off when: an ActorResolver is already defined (PlatformAuditAutoConfiguration is
 *                 @AutoConfigureAfter this, so its anonymous default only registers when this does not).
 * Beans: securityActorResolver — resolves the audit actor from the current user's subject, falling back
 *                 to anonymous when the request is unauthenticated.
 * Order: guarded reference to another capability's api (CLAUDE.md rule 5).
 */
@AutoConfiguration
@ConditionalOnClass(CurrentUserAccessor.class)
@ConditionalOnProperty(prefix = "dc.platform.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AuditSecurityAutoConfiguration {

    @Bean
    @ConditionalOnBean(CurrentUserAccessor.class)
    @ConditionalOnMissingBean(ActorResolver.class)
    ActorResolver securityActorResolver(CurrentUserAccessor currentUserAccessor) {
        return () -> currentUserAccessor.currentUser()
                .map(CurrentUser::subject)
                .orElse(ActorResolver.ANONYMOUS);
    }
}
