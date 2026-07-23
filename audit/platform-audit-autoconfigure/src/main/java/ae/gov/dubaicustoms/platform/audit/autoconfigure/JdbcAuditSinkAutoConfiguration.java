package ae.gov.dubaicustoms.platform.audit.autoconfigure;

import ae.gov.dubaicustoms.platform.audit.jdbc.JdbcAuditSink;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Activates when: the JDBC sink and JdbcTemplate are on the classpath, a DataSource bean exists, and
 *                 dc.platform.audit.enabled != false.
 * Backs off when: an AuditSink is already defined — which includes the messaging sink, since this
 *                 config is @AutoConfigureAfter it (by name; that sink lives in the separate
 *                 platform-audit-messaging-autoconfigure module). So JDBC is the second choice in the
 *                 degradation chain, ahead of the log sink.
 * Beans: jdbcAuditSink — the JDBC AuditSink over the DataSource (details serialised with the
 *                 application's ObjectMapper, or a fresh one);
 *        platformAuditFlywayLocations — appends the sink's migration location so platform_audit is
 *                 created (only when Flyway is present).
 */
@AutoConfiguration
@AutoConfigureAfter(name =
        "ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.MessagingAuditSinkAutoConfiguration")
@ConditionalOnClass({JdbcAuditSink.class, JdbcTemplate.class})
@ConditionalOnProperty(prefix = "dc.platform.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JdbcAuditSinkAutoConfiguration {

    /** Classpath location of the shipped platform_audit migration; appended to Flyway's own locations. */
    static final String MIGRATION_LOCATION = "classpath:db/migration-platform-audit";

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AuditSink.class)
    JdbcAuditSink jdbcAuditSink(DataSource dataSource, ObjectProvider<ObjectMapper> objectMapper) {
        return new JdbcAuditSink(new JdbcTemplate(dataSource), objectMapper.getIfAvailable(ObjectMapper::new));
    }

    /*
     * Adds the platform's migration location to Flyway's configuration rather than to
     * spring.flyway.locations, because that property REPLACES (not merges) — appending here preserves
     * the application's own locations. Guarded by @ConditionalOnClass so a JDBC-without-Flyway
     * consumer still starts.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Flyway.class, FlywayConfigurationCustomizer.class})
    static class FlywayLocationConfiguration {

        @Bean
        @ConditionalOnBean(JdbcAuditSink.class)
        FlywayConfigurationCustomizer platformAuditFlywayLocations() {
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
