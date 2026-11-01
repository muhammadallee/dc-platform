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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
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
 * <p>Handlers on one destination share a subscription, which routes each message by {@code eventType} to the handler
 * declaring that type, else to one declaring none. Two handlers that could receive the same message fail startup. A message
 * no handler wants is acknowledged and counted as {@code outcome=ignored}.
 *
 * <p>Consumers start and stop with the context's lifecycle, and close before the connection pools a handler uses are
 * destroyed. A stop cuts a backoff short and puts the message back: nothing is wrong with it. Handlers belong on singletons
 * created at startup: a bean with a handler created once consumers have started fails, loudly, because a running consumer
 * could already have acknowledged what it should receive.
 */
public final class EventHandlerRegistrar implements BeanPostProcessor, SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EventHandlerRegistrar.class);
    private static final String METRIC_NAME = "platform.messaging.handled";
    private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 8192;

    private final Supplier<EventTransport> transport;
    private final Supplier<EventSerializer> serializer;
    private final Supplier<MessagingProperties> properties;
    private final String group;
    private final Supplier<MessagingInstrumentation> instrumentation;
    private final Supplier<MessageTrace> trace;
    private final CopyOnWriteArrayList<EventTransport.Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<String, List<Registered>> routes = new ConcurrentHashMap<>();
    private boolean frozen;
    private final Object lifecycle = new Object();
    private volatile CountDownLatch stopping = new CountDownLatch(1);
    private volatile boolean running;

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
        Registered handler = new Registered(bean, method, annotation.destination(), annotation.eventType());
        // Only a registration table: subscribing waits for start(), because the transport may need this very bean (a template
        // customizer that also handles events). This lock is never held across a call out of this class.
        synchronized (routes) {
            if (frozen) {
                throw new IllegalStateException("@EventHandler " + method + " is on a bean created after consumers started. "
                        + "A consumer could already have acknowledged a message this handler should receive: put handlers on "
                        + "singletons created at startup");
            }
            List<Registered> route = routes.computeIfAbsent(annotation.destination(), each -> new ArrayList<>());
            route.stream().filter(each -> each.eventType().equals(handler.eventType())).findFirst().ifPresent(other -> {
                throw new IllegalStateException("@EventHandler methods " + other.method() + " and " + method + " both handle "
                        + describe(handler) + ": exactly one handler receives each message");
            });
            route.add(handler);
        }
    }

    private static String describe(Registered handler) {
        return (handler.eventType().isEmpty() ? "every event type" : "event type " + handler.eventType())
                + " on destination " + handler.destination();
    }

    private void subscribe(String destination) {
        // This subscription's own stop signal: a handler outliving its consumer must not read a later restart's.
        CountDownLatch cancelled = stopping;
        subscriptions.add(transport.get().subscribe(destination, group,
                (key, value, headers) -> route(destination, value, headers, cancelled)));
    }

    /** Handlers on one destination share one queue, so one subscription receives for all of them and routes by type. */
    private void route(String destination, byte[] value, Map<String, String> headers, CountDownLatch cancelled) {
        String eventType = headers.getOrDefault("eventType", "");
        List<Registered> candidates = routes.get(destination);
        Registered handler = candidates.stream().filter(each -> each.eventType().equals(eventType)).findFirst()
                .or(() -> candidates.stream().filter(each -> each.eventType().isEmpty()).findFirst()).orElse(null);
        if (handler == null) {
            count("ignored");
            log.debug("no @EventHandler for event type {} on destination={}", eventType, destination);
            return;
        }
        try (MessageTrace.Scope scope = trace.get().continueFrom(headers)) {
            handle(handler, value, headers, eventType, cancelled);
        }
    }

    private void handle(Registered handler, byte[] value, Map<String, String> headers, String eventType,
            CountDownLatch cancelled) {
        Method method = handler.method();
        String destination = handler.destination();
        EventType declared = payloadType(method).getAnnotation(EventType.class);
        OptionalInt version = parseVersion(headers.get("eventVersion"));
        if (version.isEmpty()) {
            log.warn("@EventHandler {} received an eventVersion that is not a number on destination={}: dead-lettered",
                    method, destination);
            deadLetter(destination, value, headers, "unreadable event version");
            return;
        }
        if (declared != null && version.getAsInt() > declared.version()) {
            // Not retried, not dropped: a replay delivers it once this service understands the version.
            log.warn("@EventHandler {} understands up to version {} of {} and received version {}: dead-lettered",
                    method, declared.version(), declared.value(), version.getAsInt());
            deadLetter(destination, value, headers, "event version too new");
            return;
        }
        MessagingProperties.Retry retry = properties.get().handler().retry();
        Duration backoff = shorter(retry.backoff(), retry.maxBackoff());
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            Object argument;
            try {
                // Per attempt: a handler may change its argument before it fails.
                argument = resolveArgument(method, value, headers, eventType, version.getAsInt());
            } catch (RuntimeException e) {
                // The class only: a parser's message can quote the payload.
                log.error("@EventHandler {} cannot read a message on destination={}: {}", method, destination,
                        e.getClass().getName());
                deadLetter(destination, value, headers, "unreadable message");
                return;
            }
            try {
                invoke(handler.bean(), method, argument);
                count("success");
                return;
            } catch (RuntimeException e) {
                if (attempt >= retry.maxAttempts()) {
                    // Classes and where it was thrown, at any level: a message can quote the payload.
                    StackTraceElement[] frames = e.getStackTrace();
                    log.error("@EventHandler {} exhausted {} attempt(s) on destination={}: dead-lettered. {} at {}",
                            method, attempt, destination, causes(e), frames.length > 0 ? frames[0] : "unknown");
                    deadLetter(destination, value, headers, "retries exhausted");
                    return;
                }
                count("retry");
                pause(backoff, cancelled);
                backoff = shorter(backoff.multipliedBy(2), retry.maxBackoff());
            }
        }
    }

    private static String causes(Throwable failure) {
        StringBuilder names = new StringBuilder(failure.getClass().getName());
        int depth = 0;
        for (Throwable t = failure.getCause(); t != null && depth < 8; t = t.getCause(), depth++) {
            names.append(" <- ").append(t.getClass().getName());
        }
        return names.toString();
    }

    private static Duration shorter(Duration one, Duration other) {
        return one.compareTo(other) < 0 ? one : other;
    }

    private void deadLetter(String destination, byte[] value, Map<String, String> headers, String reason) {
        count("dlq");
        if (transport.get().deadLettersNatively()) {
            throw new EventTransport.TransportListener.DeadLetterException(reason);
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

    private Object resolveArgument(Method method, byte[] value, Map<String, String> headers, String eventType, int eventVersion) {
        Class<?> paramType = method.getParameterTypes()[0];
        if (!EventEnvelope.class.isAssignableFrom(paramType)) {
            return serializer.get().deserialize(value, paramType);
        }
        // Generic erasure: EventEnvelope<T>'s T is only visible via the method's generic signature.
        Class<?> payloadType = envelopePayloadType(method);
        Object payload = serializer.get().deserialize(value, payloadType);
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
            if (typeArguments.length == 1 && typeArguments[0] instanceof ParameterizedType generic
                    && generic.getRawType() instanceof Class<?> rawClass) {
                return rawClass;
            }
        }
        return Object.class;
    }

    // A message from a non-platform producer may have none, which means version 1. One that is not a number cannot be judged.
    private static OptionalInt parseVersion(String header) {
        try {
            return OptionalInt.of(header != null ? Integer.parseInt(header) : 1);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private void republishToDlq(String destination, byte[] value, Map<String, String> headers) {
        String dlqDestination = destination + properties.get().dlq().suffix();
        transport.get().send(dlqDestination, null, value, headers);
    }

    private void pause(Duration backoff, CountDownLatch cancelled) {
        try {
            if (cancelled.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
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

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            running = true;
            stopping = new CountDownLatch(1);
            synchronized (routes) {
                frozen = true;
            }
            if (routes.isEmpty()) {
                return;
            }
            serializer.get();
            properties.get();
            instrumentation.get();
            trace.get();
            routes.keySet().forEach(this::subscribe);
        }
    }

    @Override
    public void stop() {
        List<EventTransport.Subscription> closing;
        synchronized (lifecycle) {
            running = false;
            stopping.countDown();
            closing = List.copyOf(subscriptions);
            subscriptions.clear();
        }
        close(closing);
    }

    /**
     * Before the outbox drainer ({@code DEFAULT_PHASE - 4096}), so a drained event finds its consumers; on the in-memory
     * transport it would otherwise vanish. And so stops after the drainer and the web server, with handlers still able to
     * use the pools, which are destroyed later. A lifecycle bean a handler depends on needs a lower phase than this.
     */
    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
    }

    // Together: one after another would let the last run for each one's timeout, redelivering what the others put back.
    private static void close(List<EventTransport.Subscription> closing) {
        List<Thread> threads = closing.stream().map(subscription -> Thread.ofVirtual().start(() -> {
            try {
                subscription.close();
            } catch (Exception e) {
                log.warn("failed to close messaging subscription", e);
            }
        })).toList();
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private record Registered(Object bean, Method method, String destination, String eventType) {
    }
}
