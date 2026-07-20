package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.util.ReflectionUtils;

/**
 * {@link BeanPostProcessor} scanning every bean for {@code @DomainEventHandler}-annotated methods,
 * and an {@link ApplicationListener} dispatching each published {@link DomainEvent} to every
 * handler whose declared parameter type is assignable from the event's runtime type.
 *
 * <p>Dispatch timing is delegated to an {@link AfterCommitDispatcher}: after the enclosing
 * transaction commits when one is active, immediately otherwise.
 */
public final class DomainEventHandlerRegistrar
        implements BeanPostProcessor, ApplicationListener<PayloadApplicationEvent<?>> {

    private static final Logger log = LoggerFactory.getLogger(DomainEventHandlerRegistrar.class);

    private record HandlerInvocation(Object bean, Method method) {
    }

    private final Map<Class<?>, List<HandlerInvocation>> handlersByEventType = new ConcurrentHashMap<>();
    private final AfterCommitDispatcher dispatcher;

    public DomainEventHandlerRegistrar(AfterCommitDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        ReflectionUtils.doWithMethods(bean.getClass(), method -> registerHandler(bean, method),
                method -> method.isAnnotationPresent(DomainEventHandler.class));
        return bean;
    }

    private void registerHandler(Object bean, Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "@DomainEventHandler method " + method + " must declare exactly one parameter");
        }
        method.setAccessible(true);
        Class<?> eventType = method.getParameterTypes()[0];
        handlersByEventType.computeIfAbsent(eventType, t -> new CopyOnWriteArrayList<>())
                .add(new HandlerInvocation(bean, method));
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<?> event) {
        Object payload = event.getPayload();
        if (!(payload instanceof DomainEvent)) {
            return;
        }
        handlersByEventType.forEach((eventType, invocations) -> {
            if (eventType.isInstance(payload)) {
                invocations.forEach(invocation -> dispatcher.dispatch(() -> invoke(invocation, payload)));
            }
        });
    }

    private void invoke(HandlerInvocation invocation, Object payload) {
        try {
            invocation.method().invoke(invocation.bean(), payload);
        } catch (InvocationTargetException e) {
            log.error("@DomainEventHandler {} threw handling {}", invocation.method(), payload.getClass(), e.getCause());
        } catch (IllegalAccessException e) {
            log.error("cannot invoke @DomainEventHandler method {}", invocation.method(), e);
        }
    }
}
