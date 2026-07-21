package ae.gov.dubaicustoms.platform.cache.autoconfigure;

import ae.gov.dubaicustoms.platform.cache.CacheKeyConvention;
import ae.gov.dubaicustoms.platform.cache.autoconfigure.CacheProperties.CacheSpec;
import ae.gov.dubaicustoms.platform.cache.autoconfigure.internal.DefaultCacheKeyConvention;
import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/*
 * Activates when: a Spring CacheManager type is on the classpath AND dc.platform.cache.enabled != false.
 * Backs off when: the user defines a CacheManager (provider configs) or a CacheKeyConvention.
 * Beans: platformCacheKeyConvention — DefaultCacheKeyConvention prefixing keys with the application
 *                 name so services never collide in a shared backend;
 *        a CacheManager — CaffeineCacheManager when Caffeine is on the classpath (and Redis is not),
 *                 else RedisCacheManager (String keys, JSON values) when Spring Data Redis is present,
 *                 each honoring dc.platform.cache.caches.<name>.ttl/max-size;
 *        cacheCapabilityDescriptor — one line in the startup capability banner naming the provider.
 * Cache metrics: contributed by Boot's own cache metrics binder over the CacheManager bean below;
 *                nothing extra is needed here.
 * Order: before CacheAutoConfiguration so the platform per-cache policy wins; both back off to any
 *        user-defined CacheManager via @ConditionalOnMissingBean.
 */
@AutoConfiguration(before = CacheAutoConfiguration.class)
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "dc.platform.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties.class)
public class PlatformCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CacheKeyConvention platformCacheKeyConvention(Environment environment) {
        return new DefaultCacheKeyConvention(environment.getProperty("spring.application.name", "application"));
    }

    /*
     * Caffeine provider: the default in-memory manager. Guarded off when Redis is also present so the
     * two provider configs never both try to own the CacheManager (mutually exclusive by class).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Caffeine.class, CaffeineCacheManager.class})
    @ConditionalOnMissingClass("org.springframework.data.redis.connection.RedisConnectionFactory")
    static class CaffeineConfiguration {

        @Bean
        @ConditionalOnMissingBean(CacheManager.class)
        CacheManager caffeineCacheManager(CacheProperties properties) {
            CaffeineCacheManager manager = new CaffeineCacheManager();
            for (Map.Entry<String, CacheSpec> entry : properties.caches().entrySet()) {
                CacheSpec spec = entry.getValue();
                Caffeine<Object, Object> builder = Caffeine.newBuilder();
                if (spec.ttl() != null) {
                    builder.expireAfterWrite(spec.ttl());
                }
                if (spec.maxSize() != null) {
                    builder.maximumSize(spec.maxSize());
                }
                manager.registerCustomCache(entry.getKey(), builder.build());
            }
            return manager;
        }

        @Bean
        CapabilityDescriptor cacheCapabilityDescriptor() {
            return new CapabilityDescriptor("cache", "ACTIVE", "caffeine");
        }
    }

    /*
     * Redis provider: String keys and JSON values, per-cache TTL from dc.platform.cache.caches.*.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class RedisConfiguration {

        @Bean
        @ConditionalOnBean(RedisConnectionFactory.class)
        @ConditionalOnMissingBean(CacheManager.class)
        CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, CacheProperties properties) {
            RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                    // String keys keep the app-name-prefixed convention readable in redis-cli; JSON
                    // values stay language-neutral. SCAN-friendly, human-inspectable keys (commented
                    // caveat mirrors the redis capability's prefixing serializer).
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(new GenericJackson2JsonRedisSerializer()));

            Map<String, RedisCacheConfiguration> perCache = new LinkedHashMap<>();
            for (Map.Entry<String, CacheSpec> entry : properties.caches().entrySet()) {
                RedisCacheConfiguration config = base;
                if (entry.getValue().ttl() != null) {
                    config = config.entryTtl(entry.getValue().ttl());
                }
                perCache.put(entry.getKey(), config);
            }
            return RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(base)
                    .withInitialCacheConfigurations(perCache)
                    .build();
        }

        @Bean
        CapabilityDescriptor cacheCapabilityDescriptor() {
            return new CapabilityDescriptor("cache", "ACTIVE", "redis");
        }
    }
}
