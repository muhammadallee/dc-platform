package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.function.SingletonSupplier;

/**
 * {@link BeanPostProcessor} scanning every bean for {@code @EventHandler}-annotated methods and
 * subscribing each one to its declared destination through the configured {@link EventTransport}.
 *
 * <p>Lifecycle per message: deserialize into the handler's declared parameter type (the payload
 * type, or {@code EventEnvelope<T>} when the method wants headers/metadata too) &rarr; invoke
 * &rarr; on success, count {@code outcome=success}; on a thrown exception, retry up to
 * {@code dc.platform.messaging.handler.retry.max-attempts} with
 * {@code dc.platform.messaging.handler.retry.backoff} between attempts (counting each retry) &rarr;
 * once exhausted, republish the raw message to {@code destination + dc.platform.messaging.dlq.suffix}
 * via the same transport (counting {@code outcome=dlq}) and give up on this delivery.
 *
 * <p>Republishing to a suffixed destination is transport-agnostic — it works identically whether
 * the underlying provider is the in-memory transport, Kafka, or RabbitMQ — so it is this
 * platform-wide fallback that honors {@code dlq.suffix}, in addition to (not instead of) whatever
 * broker-native DLQ mechanism a specific provider also wires up (kafka's
 * {@code DeadLetterPublishingRecoverer}, rabbit's DLX).
 */
public final class EventHandlerRegistrar implements BeanPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EventHandlerRegistrar.class);
    private static final String METRIC_NAME = "platform.messaging.handled";

    private final Supplier<EventTransport> transport;
    private final Supplier<EventSerializer> serializer;
    private final Supplier<MessagingProperties> properties;
    private final String group;
    private final Supplier<MeterRegistry> meterRegistry;
    private final CopyOnWriteArrayList<EventTransport.Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public EventHandlerRegistrar(EventTransport transport, EventSerializer serializer, MessagingProperties properties,
            String group, MeterRegistry meterRegistry) {
        this(() -> Objects.requireNonNull(transport, "transport must not be null"),
                () -> Objects.requireNonNull(serializer, "serializer must not be null"),
                () -> Objects.requireNonNull(properties, "properties must not be null"), group, () -> meterRegistry);
    }

    /**
     * Resolves its collaborators on first use, never at construction. This is a {@code BeanPostProcessor}, created while
     * post-processors are being registered: a bean it obtains then skips post-processing. That is how a messaging service
     * lost its JVM, pool and repository meters and its common tags: the {@code MeterRegistry} was created too early for
     * Boot to configure it.
     *
     * @param transport supplies the transport; never {@code null}
     * @param serializer supplies the serializer; never {@code null}
     * @param properties supplies the properties; never {@code null}
     * @param group the consumer group; never {@code null}
     * @param meterRegistry supplies the registry, which may supply {@code null}; never {@code null}
     */
    public EventHandlerRegistrar(Supplier<EventTransport> transport, Supplier<EventSerializer> serializer,
            Supplier<MessagingProperties> properties, String group, Supplier<MeterRegistry> meterRegistry) {
        this.transport = SingletonSupplier.of(Objects.requireNonNull(transport, "transport must not be null"));
        this.serializer = SingletonSupplier.of(Objects.requireNonNull(serializer, "serializer must not be null"));
        this.properties = SingletonSupplier.of(Objects.requireNonNull(properties, "properties must not be null"));
        this.group = Objects.requireNonNull(group, "group must not be null");
        this.meterRegistry = SingletonSupplier.of(Objects.requireNonNull(meterRegistry, "meterRegistry must not be null"));
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = bean.getClass();
        ReflectionUtils.doWithMethods(targetClass, method -> registerHandler(bean, method),
                method -> method.isAnnotationPresent(EventHandler.class));
        return bean;
    }

    private void registerHandler(Object bean, Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "@EventHandler method " + method + " must declare exactly one parameter");
        }
        EventHandler annotation = method.getAnnotation(EventHandler.class);
        method.setAccessible(true);
        EventTransport.Subscription subscription = transport.get().subscribe(annotation.destination(), group,
                (key, value, headers) -> handle(bean, method, annotation, value, headers));
        subscriptions.add(subscription);
    }

    private void handle(Object bean, Method method, EventHandler annotation, byte[] value, Map<String, String> headers) {
        String eventType = headers.getOrDefault("eventType", "");
        if (!annotation.eventType().isEmpty() && !annotation.eventType().equals(eventType)) {
            return;
        }
        int maxAttempts = properties.get().handler().retry().maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                invoke(bean, method, value, headers, eventType);
                count("success");
                return;
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts) {
                    count("dlq");
                    republishToDlq(annotation.destination(), value, headers);
                    log.error("@EventHandler {} exhausted {} attempt(s) on destination={}: routed to DLQ",
                            method, attempt, annotation.destination(), e);
                    return;
                }
                count("retry");
                sleepBackoff();
            }
        }
    }

    private void invoke(Object bean, Method method, byte[] value, Map<String, String> headers, String eventType) {
        Object argument = resolveArgument(method, value, headers, eventType);
        try {
            method.invoke(bean, argument);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("@EventHandler method " + method + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot invoke @EventHandler method " + method, e);
        }
    }

    private Object resolveArgument(Method method, byte[] value, Map<String, String> headers, String eventType) {
        Class<?> paramType = method.getParameterTypes()[0];
        if (!EventEnvelope.class.isAssignableFrom(paramType)) {
            return serializer.get().deserialize(value, paramType);
        }
        // Generic erasure: EventEnvelope<T>'s T is only visible via the method's generic signature.
        Class<?> payloadType = envelopePayloadType(method);
        Object payload = serializer.get().deserialize(value, payloadType);
        int eventVersion = parseVersion(headers.get("eventVersion"));
        // A message from a non-platform producer has no id; it still gets one, though a redelivery will differ.
        String eventId = headers.getOrDefault(EventEnvelope.EVENT_ID_HEADER, UUID.randomUUID().toString());
        return new EventEnvelope<>(eventId, eventType, eventVersion, null, payload, headers, Instant.now());
    }

    private static Class<?> envelopePayloadType(Method method) {
        Type genericParamType = method.getGenericParameterTypes()[0];
        if (genericParamType instanceof ParameterizedType parameterized) {
            Type[] typeArguments = parameterized.getActualTypeArguments();
            if (typeArguments.length == 1 && typeArguments[0] instanceof Class<?> payloadClass) {
                return payloadClass;
            }
        }
        return Object.class;
    }

    private static int parseVersion(String header) {
        try {
            return header != null ? Integer.parseInt(header) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void republishToDlq(String destination, byte[] value, Map<String, String> headers) {
        String dlqDestination = destination + properties.get().dlq().suffix();
        transport.get().send(dlqDestination, null, value, headers);
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(properties.get().handler().retry().backoff());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void count(String outcome) {
        MeterRegistry registry = meterRegistry.get();
        if (registry != null) {
            registry.counter(METRIC_NAME, "outcome", outcome).increment();
        }
    }

    @Override
    public void destroy() {
        for (EventTransport.Subscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.warn("failed to close messaging subscription", e);
            }
        }
        subscriptions.clear();
    }
}
