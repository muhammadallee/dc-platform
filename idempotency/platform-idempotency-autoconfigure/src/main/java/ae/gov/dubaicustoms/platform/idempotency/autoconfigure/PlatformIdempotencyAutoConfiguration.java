package ae.gov.dubaicustoms.platform.idempotency.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.errors.ConflictException;
import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.IdempotencyKeyFilter;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.IdempotentAdvisorFactory;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.IdempotentAutoProxyRegistrar;
import jakarta.servlet.http.HttpFilter;
import java.util.Locale;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;

/*
 * Activates when: an IdempotencyStore bean exists (Redis or JDBC store config, or user) AND
 *                 dc.platform.idempotency.enabled != false.
 * Backs off when: the user defines their own idempotentAdvisor / filter (via @ConditionalOnMissingBean).
 * Beans: idempotencyCapabilityDescriptor — one line in the startup capability banner naming the store;
 *        idempotentAdvisor — (only when the errors capability's ConflictException is on the classpath)
 *                 a plain AOP advisor enforcing @Idempotent and rejecting duplicates as 409;
 *        idempotencyKeyFilter — (only in a servlet app and when http.enabled=true) rejects duplicate
 *                 Idempotency-Key POSTs with 409.
 * Order: after both store auto-configurations so the IdempotencyStore is registered first.
 */
@AutoConfiguration(after = {
        RedisIdempotencyStoreAutoConfiguration.class, JdbcIdempotencyStoreAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dc.platform.idempotency", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class PlatformIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    CapabilityDescriptor idempotencyCapabilityDescriptor(IdempotencyStore store) {
        // Report the selected store by its simple class name (JdbcIdempotencyStore -> "jdbc").
        String detail = store.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("idempotencystore", "");
        return new CapabilityDescriptor("idempotency", "ACTIVE", detail);
    }

    /*
     * The @Idempotent advisor: contributed only when the errors capability's ConflictException is on
     * the classpath (guarded reference to another capability's api, CLAUDE.md rule 5), since a rejected
     * duplicate is surfaced as its 409. Uses the plain auto-proxy creator (decision D26).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ConflictException.class)
    @Import(IdempotentAutoProxyRegistrar.class)
    static class IdempotentAdvisorConfiguration {

        // ROLE_INFRASTRUCTURE: the InfrastructureAdvisorAutoProxyCreator only considers Advisor beans
        // with this role — a plain application-role bean would be silently ignored (decision D26).
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnBean(IdempotencyStore.class)
        @ConditionalOnMissingBean(name = "idempotentAdvisor")
        Advisor idempotentAdvisor(IdempotencyStore store) {
            return IdempotentAdvisorFactory.create(store);
        }
    }

    /*
     * The Idempotency-Key HTTP filter: only in a servlet web app, only when explicitly enabled
     * (dc.platform.idempotency.http.enabled=true). Reject-duplicate only; no response replay (v1 scope).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HttpFilter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "dc.platform.idempotency.http", name = "enabled", havingValue = "true")
    static class HttpFilterConfiguration {

        @Bean
        @ConditionalOnBean(IdempotencyStore.class)
        @ConditionalOnMissingBean
        IdempotencyKeyFilter idempotencyKeyFilter(IdempotencyStore store, IdempotencyProperties properties) {
            return new IdempotencyKeyFilter(store, properties.http().headerName(), properties.http().ttl());
        }
    }
}
