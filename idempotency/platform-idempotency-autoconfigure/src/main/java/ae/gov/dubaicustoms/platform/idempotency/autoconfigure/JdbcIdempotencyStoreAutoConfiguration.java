package ae.gov.dubaicustoms.platform.idempotency.autoconfigure;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.JdbcIdempotencyStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * Activates when: spring-jdbc (JdbcTemplate) is on the classpath, a DataSource bean exists, and
 *                 dc.platform.idempotency.enabled != false.
 * Backs off when: an IdempotencyStore is already defined — which includes the Redis store, since this
 *                 config is @AutoConfigureAfter RedisIdempotencyStoreAutoConfiguration. So JDBC is the
 *                 fallback when Redis is absent.
 * Beans: jdbcIdempotencyStore — the JDBC store over the DataSource (system-UTC clock unless a Clock
 *                 bean is supplied);
 *        platformIdempotencyFlywayLocations — appends the store's migration location (Flyway only).
 */
@AutoConfiguration
@AutoConfigureAfter(RedisIdempotencyStoreAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "dc.platform.idempotency", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JdbcIdempotencyStoreAutoConfiguration {

    /** Classpath location of the shipped platform_idempotency migration; appended to Flyway's locations. */
    static final String MIGRATION_LOCATION = "classpath:db/migration-platform-idempotency";

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    IdempotencyStore jdbcIdempotencyStore(DataSource dataSource, ObjectProvider<Clock> clock) {
        return new JdbcIdempotencyStore(new JdbcTemplate(dataSource), clock.getIfAvailable(Clock::systemUTC));
    }

    /*
     * Adds the platform's migration location to Flyway's configuration (append, not replace, so the
     * application's own locations are preserved). Guarded by @ConditionalOnClass so a JDBC-without-Flyway
     * consumer still starts. Mirrors the locking capability's mechanism (decision D41).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Flyway.class, FlywayConfigurationCustomizer.class})
    static class FlywayLocationConfiguration {

        @Bean
        FlywayConfigurationCustomizer platformIdempotencyFlywayLocations() {
            return configuration -> {
                List<String> locations = new ArrayList<>();
                for (Location existing : configuration.getLocations()) {
                    locations.add(existing.getDescriptor());
                }
                locations.add(MIGRATION_LOCATION);
                configuration.locations(locations.toArray(String[]::new));
            };
        }
    }
}
