package ae.gov.dubaicustoms.platform.locking.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.locking.LockManager;
import ae.gov.dubaicustoms.platform.locking.autoconfigure.internal.DefaultLockManager;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: a LockProvider bean exists (contributed by the Redis or JDBC provider config, or by
 *                 the user) AND dc.platform.locking.enabled != false.
 * Backs off when: the user defines their own LockManager.
 * Beans: lockManager — DefaultLockManager over the selected LockProvider;
 *        lockingCapabilityDescriptor — one line in the startup capability banner naming the provider.
 * Order: after both provider auto-configurations so the LockProvider is registered before the
 *        @ConditionalOnBean here is evaluated.
 */
@AutoConfiguration(after = {RedisLockProviderAutoConfiguration.class, JdbcLockProviderAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dc.platform.locking", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LockingProperties.class)
public class PlatformLockingAutoConfiguration {

    @Bean
    @ConditionalOnBean(LockProvider.class)
    @ConditionalOnMissingBean
    LockManager lockManager(LockProvider provider) {
        return new DefaultLockManager(provider);
    }

    @Bean
    @ConditionalOnBean(LockProvider.class)
    CapabilityDescriptor lockingCapabilityDescriptor(LockProvider provider) {
        // Report the selected provider by its simple class name (JdbcLockProvider -> "jdbc").
        String detail = provider.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT)
                .replace("lockprovider", "");
        return new CapabilityDescriptor("locking", "ACTIVE", detail);
    }
}
