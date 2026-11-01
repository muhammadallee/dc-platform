package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.function.SingletonSupplier;

/**
 * {@link BeanPostProcessor} scanning every bean for {@code @EventHandler}-annotated methods and
 * subscribing each one to its declared destination through the configured {@link EventTransport}.
 *
 * <p>Lifecycle per message: deserialize into the handler's declared parameter type (the payload
 * type, or {@code EventEnvelope<T>} when the method wants headers/metadata too) &rarr; invoke
 * &rarr; on success, count {@code outcome=success}; on a thrown exception, retry up to
 * {@code dc.platform.messaging.handler.retry.max-attempts} with an exponential, capped backoff (counting each retry)
 * &rarr; once exhausted, dead-letter it (counting {@code outcome=dlq}). A payload that cannot be deserialized is
 * dead-lettered on the first attempt: it can never succeed.
 *
 * <p>This is the only retry layer on a transport that dead-letters natively (Rabbit's DLX): the listener throws and the
 * broker routes the message. Any other transport gets a republish to {@code destination + dc.platform.messaging.dlq.suffix}.
 *
 * <p>A stop cuts a backoff short and puts the message back: nothing is wrong with it. Subscriptions close in the
 * lifecycle stop, before the connection pools a handler uses are destroyed.
 */
public final class EventHandlerRegistrar implements BeanPostProcessor, SmartInitializingSingleton, SmartLifecycle,
        DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EventHandlerRegistrar.class);
    private static final String METRIC_NAME = "platform.messaging.handled";

    private final Supplier<EventTransport> transport;
    private final Supplier<EventSerializer> serializer;
    private final Supplier<MessagingProperties> properties;
    private final String group;
    private final Supplier<MessagingInstrumentation> instrumentation;
    private final Supplier<MessageTrace> trace;
    private final CopyOnWriteArrayList<EventTransport.Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> pending = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private final CountDownLatch stopping = new CountDownLatch(1);
    private volatile boolean singletonsReady;

    public EventHandlerRegistrar(EventTransport transport, EventSerializer serializer, MessagingProperties properties,
            String group, MessagingInstrumentation instrumentation) {
        this(() -> Objects.requireNonNull(transport, "transport must not be null"),
                () -> Objects.requireNonNull(serializer, "serializer must not be null"),
                () -> Objects.requireNonNull(properties, "properties must not be null"), group,
                () -> instrumentation != null ? instrumentation : MessagingInstrumentation.NOOP, () -> MessageTrace.NONE);
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
     * @param instrumentation supplies what records the outcomes; never {@code null}
     * @param trace supplies what continues a message's trace; never {@code null}
     */
    public EventHandlerRegistrar(Supplier<EventTransport> transport, Supplier<EventSerializer> serializer,
            Supplier<MessagingProperties> properties, String group, Supplier<MessagingInstrumentation> instrumentation,
            Supplier<MessageTrace> trace) {
        this.transport = SingletonSupplier.of(Objects.requireNonNull(transport, "transport must not be null"));
        this.serializer = SingletonSupplier.of(Objects.requireNonNull(serializer, "serializer must not be null"));
        this.properties = SingletonSupplier.of(Objects.requireNonNull(properties, "properties must not be null"));
        this.group = Objects.requireNonNull(group, "group must not be null");
        this.instrumentation = SingletonSupplier.of(Objects.requireNonNull(instrumentation, "instrumentation must not be null"));
        this.trace = SingletonSupplier.of(Objects.requireNonNull(trace, "trace must not be null"));
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
        Runnable subscribe = () -> subscriptions.add(transport.get().subscribe(annotation.destination(), group,
                (key, value, headers) -> handle(bean, method, annotation, value, headers)));
        // Not during this bean's own post-processing: the transport may need this very bean (a template customizer that
        // also handles events), and creating it now would re-enter the unfinished bean.
        if (singletonsReady) {
            subscribe.run();
        } else {
            pending.add(subscribe);
        }
    }

    /** Subscribes once every singleton exists, with the collaborators resolved first, not on a delivery thread. */
    @Override
    public void afterSingletonsInstantiated() {
        singletonsReady = true;
        if (pending.isEmpty()) {
            return;
        }
        serializer.get();
        properties.get();
        instrumentation.get();
        trace.get();
        pending.forEach(Runnable::run);
        pending.clear();
    }

    private void handle(Object bean, Method method, EventHandler annotation, byte[] value, Map<String, String> headers) {
        String eventType = headers.getOrDefault("eventType", "");
        if (!annotation.eventType().isEmpty() && !annotation.eventType().equals(eventType)) {
            return;
        }
        try (MessageTrace.Scope scope = trace.get().continueFrom(headers)) {
            handleInTrace(bean, method, annotation, value, headers, eventType);
        }
    }

    private void handleInTrace(Object bean, Method method, EventHandler annotation, byte[] value,
            Map<String, String> headers, String eventType) {
        EventType declared = payloadType(method).getAnnotation(EventType.class);
        int version = parseVersion(headers.get("eventVersion"));
        if (declared != null && version > declared.version()) {
            // Not retried, not dropped: a replay delivers it once this service understands the version.
            log.warn("@EventHandler {} understands up to version {} of {} and received version {}: dead-lettered",
                    method, declared.version(), declared.value(), version);
            deadLetter(annotation.destination(), value, headers, new IllegalArgumentException("event version too new"));
            return;
        }
        Object argument;
        try {
            argument = resolveArgument(method, value, headers, eventType);
        } catch (RuntimeException e) {
            // The class only: a parser's message can quote the payload.
            log.error("@EventHandler {} cannot read a message on destination={}: {}", method, annotation.destination(),
                    e.getClass().getName());
            deadLetter(annotation.destination(), value, headers, new IllegalArgumentException("unreadable message"));
            return;
        }
        MessagingProperties.Retry retry = properties.get().handler().retry();
        Duration backoff = retry.backoff();
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                invoke(bean, method, argument);
                count("success");
                return;
            } catch (RuntimeException e) {
                if (attempt >= retry.maxAttempts()) {
                    log.error("@EventHandler {} exhausted {} attempt(s) on destination={}: dead-lettered",
                            method, attempt, annotation.destination(), e);
                    deadLetter(annotation.destination(), value, headers, e);
                    return;
                }
                count("retry");
                pause(backoff);
                backoff = backoff.multipliedBy(2).compareTo(retry.maxBackoff()) < 0 ? backoff.multipliedBy(2) : retry.maxBackoff();
            }
        }
    }

    private void deadLetter(String destination, byte[] value, Map<String, String> headers, RuntimeException cause) {
        count("dlq");
        if (transport.get().deadLettersNatively()) {
            throw cause;
        }
        republishToDlq(destination, value, headers);
    }

    private void invoke(Object bean, Method method, Object argument) {
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

    private static Class<?> payloadType(Method method) {
        Class<?> paramType = method.getParameterTypes()[0];
        return EventEnvelope.class.isAssignableFrom(paramType) ? envelopePayloadType(method) : paramType;
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

    private void pause(Duration backoff) {
        try {
            if (stopping.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new EventTransport.TransportListener.RequeueException("the consumer is stopping");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventTransport.TransportListener.RequeueException("interrupted during a retry backoff");
        }
    }

    private void count(String outcome) {
        instrumentation.get().count(METRIC_NAME, "outcome", outcome);
    }

    /** Subscriptions start in {@link #afterSingletonsInstantiated}; the lifecycle is here to stop them in order. */
    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        stopping.countDown();
        closeSubscriptions();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stopping.countDown();
        closeSubscriptions();
    }

    private void closeSubscriptions() {
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
