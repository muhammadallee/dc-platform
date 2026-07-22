package ae.gov.dubaicustoms.platform.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.errors.ConflictException;
import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.Idempotent;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.IdempotencyKeyFilter;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.JdbcIdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.RedisIdempotencyStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** ContextRunner matrix, store selection, an end-to-end @Idempotent slice over H2, and the filter wiring. */
class PlatformIdempotencyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisIdempotencyStoreAutoConfiguration.class,
                    JdbcIdempotencyStoreAutoConfiguration.class,
                    PlatformIdempotencyAutoConfiguration.class));

    private static DataSource migratedH2() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:db/migration-platform-idempotency/V1__create_platform_idempotency.sql")
                .build();
    }

    @Test
    void activeByDefaultWithADataSource() {
        runner.withBean(DataSource.class, PlatformIdempotencyAutoConfigurationTest::migratedH2)
                .run(context -> {
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(JdbcIdempotencyStore.class);
                    assertThat(context).hasSingleBean(CapabilityDescriptor.class);
                    assertThat(context).hasBean("idempotentAdvisor");
                });
    }

    @Test
    void killSwitchDisables() {
        runner.withBean(DataSource.class, PlatformIdempotencyAutoConfigurationTest::migratedH2)
                .withPropertyValues("dc.platform.idempotency.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotencyStore.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserStorePresent() {
        IdempotencyStore mine = (key, ttl) -> true;
        runner.withBean("mine", IdempotencyStore.class, () -> mine)
                .run(context -> assertThat(context.getBean(IdempotencyStore.class)).isSameAs(mine));
    }

    @Test
    void inactiveWithoutAnyStoreInfrastructure() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
            assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void redisWinsWhenBothStoresAreAvailable() {
        runner.withBean(DataSource.class, PlatformIdempotencyAutoConfigurationTest::migratedH2)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> assertThat(context)
                        .getBean(IdempotencyStore.class).isInstanceOf(RedisIdempotencyStore.class));
    }

    @Test
    void idempotentMethodRejectsDuplicatesOverH2() {
        runner.withBean(DataSource.class, PlatformIdempotencyAutoConfigurationTest::migratedH2)
                .withUserConfiguration(OrderConfig.class)
                .run(context -> {
                    OrderService service = context.getBean(OrderService.class);

                    service.place("order-1");
                    assertThat(service.placed()).isEqualTo(1);

                    assertThatThrownBy(() -> service.place("order-1")).isInstanceOf(ConflictException.class);
                    assertThat(service.placed()).isEqualTo(1);

                    service.place("order-2");
                    assertThat(service.placed()).isEqualTo(2);
                });
    }

    @Test
    void httpFilterIsOffByDefaultAndWiredWhenEnabledInAWebApp() {
        // Not a web app: never wired.
        runner.withBean(DataSource.class, PlatformIdempotencyAutoConfigurationTest::migratedH2)
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyKeyFilter.class));

        // Servlet web app + explicitly enabled: wired.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisIdempotencyStoreAutoConfiguration.class,
                        JdbcIdempotencyStoreAutoConfiguration.class,
                        PlatformIdempotencyAutoConfiguration.class))
                .withBean(DataSource.class, PlatformIdempotencyAutoConfigurationTest::migratedH2)
                .withPropertyValues("dc.platform.idempotency.http.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IdempotencyKeyFilter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderConfig {
        @Bean
        OrderService orderService() {
            return new OrderService();
        }
    }

    /** A service whose command is applied at most once per order id. */
    static class OrderService {
        private final AtomicInteger placed = new AtomicInteger();

        @Idempotent(keyExpression = "#a0", ttl = "1h")
        public void place(String orderId) {
            placed.incrementAndGet();
        }

        int placed() {
            return placed.get();
        }
    }
}
