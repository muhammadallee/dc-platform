package ae.gov.dubaicustoms.platform.redis.autoconfigure.internal;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Installs the {@link PrefixingStringRedisSerializer} as the key serializer of every
 * {@link StringRedisTemplate} bean (Boot's or the application's), before the template's
 * {@code afterPropertiesSet} runs. Only the top-level key serializer is replaced — hash field names
 * are left untouched.
 */
public final class StringRedisTemplateKeyPrefixPostProcessor implements BeanPostProcessor {

    private final RedisSerializer<String> keySerializer;

    /**
     * @param prefix the key prefix to apply; never {@code null}
     */
    public StringRedisTemplateKeyPrefixPostProcessor(String prefix) {
        this.keySerializer = new PrefixingStringRedisSerializer(prefix);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof StringRedisTemplate template) {
            template.setKeySerializer(keySerializer);
        }
        return bean;
    }
}
