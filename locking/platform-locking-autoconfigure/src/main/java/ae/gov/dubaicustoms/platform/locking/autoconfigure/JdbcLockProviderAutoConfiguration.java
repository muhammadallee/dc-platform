package ae.gov.dubaicustoms.platform.locking.autoconfigure;

import ae.gov.dubaicustoms.platform.locking.jdbc.JdbcLockProvider;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
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
 *                 dc.platform.locking.enabled != false.
 * Backs off when: a LockProvider is already defined — which includes the Redis provider, since this
 *                 config is @AutoConfigureAfter RedisLockProviderAutoConfiguration. So JDBC is the
 *                 fallback when Redis is absent.
 * Beans: jdbcLockProvider — the JDBC LockProvider over the DataSource (system-UTC clock unless the
 *                 application supplies a Clock bean);
 *        platformLockingFlywayLocations — appends the provider's migration location so the
 *                 platform_lock table is created (only when Flyway is present).
 */
@AutoConfiguration
@AutoConfigureAfter(RedisLockProviderAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "dc.platform.locking", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JdbcLockProviderAutoConfiguration {

    /** Classpath location of the shipped platform_lock migration; appended to Flyway's own locations. */
    static final String MIGRATION_LOCATION = "classpath:db/migration-platform-locking";

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(LockProvider.class)
    LockProvider jdbcLockProvider(DataSource dataSource, ObjectProvider<Clock> clock) {
        return new JdbcLockProvider(new JdbcTemplate(dataSource), clock.getIfAvailable(Clock::systemUTC));
    }

    /*
     * Adds the platform's migration location to Flyway's configuration rather than to
     * spring.flyway.locations, because that property REPLACES (not merges) — appending here preserves
     * the application's own locations. Guarded by @ConditionalOnClass so a JDBC-without-Flyway
     * consumer (e.g. one managing the schema another way) still starts.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Flyway.class, FlywayConfigurationCustomizer.class})
    static class FlywayLocationConfiguration {

        @Bean
        FlywayConfigurationCustomizer platformLockingFlywayLocations() {
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
