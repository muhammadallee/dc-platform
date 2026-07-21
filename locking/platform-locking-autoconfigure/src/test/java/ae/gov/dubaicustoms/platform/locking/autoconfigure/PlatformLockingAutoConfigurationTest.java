package ae.gov.dubaicustoms.platform.locking.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.locking.LockManager;
import ae.gov.dubaicustoms.platform.locking.jdbc.JdbcLockProvider;
import ae.gov.dubaicustoms.platform.locking.redis.RedisLockProvider;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The mandatory ContextRunner matrix plus provider-selection and an end-to-end LockManager slice over
 * H2 (no Docker). The JDBC provider is exercised by default because spring-jdbc is on the classpath;
 * the Redis provider is selected by supplying a StringRedisTemplate bean.
 */
class PlatformLockingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisLockProviderAutoConfiguration.class,
                    JdbcLockProviderAutoConfiguration.class,
                    PlatformLockingAutoConfiguration.class));

    private static DataSource migratedH2() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:db/migration-platform-locking/V1__create_platform_lock.sql")
                .build();
    }

    @Test
    void activeByDefaultWithADataSource() {
        runner.withBean(DataSource.class, PlatformLockingAutoConfigurationTest::migratedH2)
                .run(context -> {
                    assertThat(context).hasSingleBean(LockManager.class);
                    assertThat(context).getBean(LockProvider.class).isInstanceOf(JdbcLockProvider.class);
                    assertThat(context).hasSingleBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void killSwitchDisables() {
        runner.withBean(DataSource.class, PlatformLockingAutoConfigurationTest::migratedH2)
                .withPropertyValues("dc.platform.locking.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LockManager.class);
                    assertThat(context).doesNotHaveBean(LockProvider.class);
                });
    }

    @Test
    void backsOffWhenUserLockManagerPresent() {
        LockManager mine = new LockManager() {
            @Override
            public <T> Optional<T> withLock(String name, Duration atMost, java.util.concurrent.Callable<T> action) {
                return Optional.empty();
            }
        };
        runner.withBean(DataSource.class, PlatformLockingAutoConfigurationTest::migratedH2)
                .withBean("mine", LockManager.class, () -> mine)
                .run(context -> assertThat(context.getBean(LockManager.class)).isSameAs(mine));
    }

    @Test
    void inactiveWithoutAnyProviderInfrastructure() {
        // No DataSource and no StringRedisTemplate bean -> no LockProvider -> no LockManager.
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(LockProvider.class);
            assertThat(context).doesNotHaveBean(LockManager.class);
        });
    }

    @Test
    void redisWinsWhenBothProvidersAreAvailable() {
        runner.withBean(DataSource.class, PlatformLockingAutoConfigurationTest::migratedH2)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> assertThat(context)
                        .getBean(LockProvider.class).isInstanceOf(RedisLockProvider.class));
    }

    @Test
    void lockManagerRunsAndIsNonReentrantOverH2() {
        runner.withBean(DataSource.class, PlatformLockingAutoConfigurationTest::migratedH2)
                .run(context -> {
                    LockManager manager = context.getBean(LockManager.class);

                    Optional<String> outer = manager.withLock("job", Duration.ofMinutes(1), () -> {
                        // Non-reentrant: the same name cannot be re-acquired while held.
                        Optional<String> inner =
                                manager.withLock("job", Duration.ofMinutes(1), () -> "inner");
                        assertThat(inner).isEmpty();
                        return "outer";
                    });

                    assertThat(outer).contains("outer");
                    // Released afterward: it can be acquired again.
                    assertThat(manager.withLock("job", Duration.ofMinutes(1), () -> "again")).contains("again");
                });
    }
}
