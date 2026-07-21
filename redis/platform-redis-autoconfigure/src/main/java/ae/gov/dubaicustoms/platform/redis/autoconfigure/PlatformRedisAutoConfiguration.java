package ae.gov.dubaicustoms.platform.redis.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.redis.autoconfigure.internal.StringRedisTemplateKeyPrefixPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * Activates when: StringRedisTemplate (spring-data-redis) on the classpath AND
 *                 dc.platform.redis.enabled != false.
 * Backs off when: nothing here replaces a user bean — the post-processor decorates whatever
 *                 StringRedisTemplate exists (Boot's or the user's); disable via the kill switch.
 * Beans: platformRedisKeyPrefixPostProcessor — a BeanPostProcessor that installs the prefixing key
 *                 serializer on every StringRedisTemplate before it initializes;
 *        redisCapabilityDescriptor — one line in the startup capability banner, naming the prefix.
 * Order: none required; the post-processor runs against the template bean whenever it is created.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "dc.platform.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PlatformRedisProperties.class)
public class PlatformRedisAutoConfiguration {

    @Bean
    static BeanPostProcessor platformRedisKeyPrefixPostProcessor(PlatformRedisProperties properties, Environment environment) {
        return new StringRedisTemplateKeyPrefixPostProcessor(resolvePrefix(properties, environment));
    }

    @Bean
    CapabilityDescriptor redisCapabilityDescriptor(PlatformRedisProperties properties, Environment environment) {
        return new CapabilityDescriptor("redis", "ACTIVE", "key-prefix=" + resolvePrefix(properties, environment));
    }

    /** The configured prefix, or {@code "<spring.application.name>:"} when none is set. */
    private static String resolvePrefix(PlatformRedisProperties properties, Environment environment) {
        if (properties.keyPrefix() != null && !properties.keyPrefix().isBlank()) {
            return properties.keyPrefix();
        }
        return environment.getProperty("spring.application.name", "application") + ":";
    }
}
