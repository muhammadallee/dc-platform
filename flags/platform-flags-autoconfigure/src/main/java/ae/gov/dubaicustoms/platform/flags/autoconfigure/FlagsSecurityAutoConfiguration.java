package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import ae.gov.dubaicustoms.platform.flags.autoconfigure.internal.EvaluationContextProvider;
import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the security capability's CurrentUserAccessor is on the classpath, a bean of it
 *                 exists, and dc.platform.flags.enabled != false.
 * Backs off when: an EvaluationContextProvider is already defined (PlatformFlagsAutoConfiguration is
 *                 @AutoConfigureAfter this, so its anonymous default only registers when this does not).
 * Beans: securityEvaluationContextProvider — resolves the current user (subject) and tenant into the
 *                 flag EvaluationContext, falling back to anonymous when the request is unauthenticated.
 * Order: guarded reference to another capability's api (CLAUDE.md rule 5).
 */
@AutoConfiguration
@ConditionalOnClass(CurrentUserAccessor.class)
@ConditionalOnProperty(prefix = "dc.platform.flags", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FlagsSecurityAutoConfiguration {

    @Bean
    @ConditionalOnBean(CurrentUserAccessor.class)
    @ConditionalOnMissingBean(EvaluationContextProvider.class)
    EvaluationContextProvider securityEvaluationContextProvider(CurrentUserAccessor currentUserAccessor) {
        return () -> currentUserAccessor.currentUser()
                .map(user -> new EvaluationContext(
                        Optional.of(user.subject()), Optional.ofNullable(user.tenant()), Map.of()))
                .orElseGet(EvaluationContext::anonymous);
    }
}
