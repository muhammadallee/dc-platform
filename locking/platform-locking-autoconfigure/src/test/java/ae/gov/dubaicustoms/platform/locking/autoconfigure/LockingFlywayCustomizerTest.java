package ae.gov.dubaicustoms.platform.locking.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

/** Proves the platform migration location is appended to (not replacing) the existing locations. */
class LockingFlywayCustomizerTest {

    @Test
    void appendsThePlatformMigrationLocation() {
        FlywayConfigurationCustomizer customizer =
                new JdbcLockProviderAutoConfiguration.FlywayLocationConfiguration()
                        .platformLockingFlywayLocations();
        FluentConfiguration configuration = new FluentConfiguration().locations("classpath:db/migration");

        customizer.customize(configuration);

        assertThat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor))
                .containsExactly("classpath:db/migration",
                        JdbcLockProviderAutoConfiguration.MIGRATION_LOCATION);
    }
}
