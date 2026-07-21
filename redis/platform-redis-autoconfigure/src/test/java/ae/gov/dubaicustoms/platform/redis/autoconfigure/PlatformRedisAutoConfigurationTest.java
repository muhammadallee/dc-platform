package ae.gov.dubaicustoms.platform.redis.autoconfigure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/** The mandatory ContextRunner matrix for PlatformRedisAutoConfiguration, plus prefix resolution. */
class PlatformRedisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformRedisAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasBean("platformRedisKeyPrefixPostProcessor");
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.redis.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("platformRedisKeyPrefixPostProcessor");
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(StringRedisTemplate.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformRedisAutoConfiguration.class));
    }

    @Test
    void appliesApplicationNamePrefixByDefault() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        runner.withPropertyValues("spring.application.name=orders")
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(connectionFactory))
                .run(context -> assertThat(serializeKey(context.getBean(StringRedisTemplate.class), "42"))
                        .isEqualTo("orders:42"));
    }

    @Test
    void appliesExplicitPrefixFromKebabKey() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        runner.withPropertyValues("dc.platform.redis.key-prefix=tenant-a:")
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(connectionFactory))
                .run(context -> assertThat(serializeKey(context.getBean(StringRedisTemplate.class), "42"))
                        .isEqualTo("tenant-a:42"));
    }

    private static String serializeKey(StringRedisTemplate template, String key) {
        @SuppressWarnings("unchecked")
        RedisSerializer<String> keySerializer = (RedisSerializer<String>) template.getKeySerializer();
        return new String(keySerializer.serialize(key), UTF_8);
    }
}
