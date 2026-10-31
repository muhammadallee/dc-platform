package ae.gov.dubaicustoms.platform.messaging.rabbit;

import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

/**
 * {@link EventTransport} over RabbitMQ. Built from Boot's own {@code RabbitTemplate} and
 * {@code ConnectionFactory} beans (produced from {@code spring.rabbitmq.*} by Boot's own
 * {@code RabbitAutoConfiguration}) — broker connection details are never duplicated here, only
 * consumed.
 *
 * <p>Destination syntax: {@code "exchange"} (routes with an empty routing key) or
 * {@code "exchange:routingKey"}. Each subscription declares (idempotently, via {@link RabbitAdmin})
 * a durable topic exchange, a durable queue bound to it with the routing key, and a dead-letter
 * exchange + queue the broker routes to automatically once a message is rejected without requeue.
 * Quorum queues are opt-in via the constructor flag (recommended for production RabbitMQ clusters;
 * classic queues are the local/dev default).
 *
 * <p>Redelivery/DLQ is Rabbit-native: the container's retry interceptor gives each message up to
 * {@code maxAttempts} local redeliveries, then rejects it without requeue — the queue's
 * {@code x-dead-letter-exchange} argument makes the broker route it to the DLQ automatically. This
 * is in addition to, not instead of, {@code platform-messaging-autoconfigure}'s transport-agnostic
 * republish-to-{@code dlq.suffix} fallback (which every provider gets for free).
 *
 * <p>{@link #send} honours the SPI's "blocks until acknowledged": it waits for the broker's publisher
 * confirm, and a nack, a returned (unroutable) message or no confirm within the timeout is an
 * {@link EventPublishException}. The connection factory and template must have publisher confirms, returns
 * and {@code mandatory} on; the platform defaults them and the constructor refuses anything else.
 *
 * <p>Thread-safe.
 *
 * @since 0.2.0
 */
public final class RabbitEventTransport implements EventTransport {

    private static final String UNACKNOWLEDGED = "A send could be lost without anyone knowing. Set ";

    private final RabbitTemplate rabbitTemplate;
    private final ConnectionFactory connectionFactory;
    private final RabbitAdmin rabbitAdmin;
    private final int maxAttempts;
    private final Duration backoff;
    private final boolean quorumQueues;
    private final Duration confirmTimeout;
    private final CopyOnWriteArrayList<SimpleMessageListenerContainer> containers = new CopyOnWriteArrayList<>();

    /**
     * Creates the transport.
     *
     * @param rabbitTemplate the Boot-configured template used for publish; never {@code null}
     * @param connectionFactory the Boot-configured connection factory each subscription's container
     *     and the topology declarations are built from; never {@code null}
     * @param maxAttempts total delivery attempts before a message is rejected to the DLQ; at least 1
     * @param backoff pause between redelivery attempts; never {@code null}
     * @param quorumQueues declare quorum queues ({@code x-queue-type=quorum}) instead of classic
     *     queues; opt-in for production clusters
     * @param confirmTimeout how long a send waits for the broker's confirm; never {@code null}
     * @throws IllegalStateException if publisher confirms, returns or {@code mandatory} are off
     */
    public RabbitEventTransport(RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, int maxAttempts,
            Duration backoff, boolean quorumQueues, Duration confirmTimeout) {
        this(rabbitTemplate, connectionFactory, new RabbitAdmin(connectionFactory), maxAttempts, backoff, quorumQueues,
                confirmTimeout);
    }

    // Package-private: lets docker-free unit tests inject a mocked RabbitAdmin instead of one
    // backed by a real broker channel.
    RabbitEventTransport(RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, RabbitAdmin rabbitAdmin,
            int maxAttempts, Duration backoff, boolean quorumQueues, Duration confirmTimeout) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.rabbitAdmin = Objects.requireNonNull(rabbitAdmin, "rabbitAdmin must not be null");
        this.maxAttempts = maxAttempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
        this.quorumQueues = quorumQueues;
        this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout must not be null");
        requireAcknowledgedPublishing(rabbitTemplate, connectionFactory);
    }

    private static void requireAcknowledgedPublishing(RabbitTemplate template, ConnectionFactory factory) {
        if (!factory.isPublisherConfirms()) {
            throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.publisher-confirm-type=correlated");
        }
        if (!factory.isPublisherReturns()) {
            throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.publisher-returns=true");
        }
        if (!template.isMandatoryFor(new Message(new byte[0]))) {
            throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.template.mandatory=true");
        }
    }

    @Override
    public String name() {
        return "rabbit";
    }

    @Override
    public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        RabbitDestination dest = RabbitDestination.parse(destination);
        MessageProperties properties = new MessageProperties();
        headers.forEach(properties::setHeader);
        if (key != null) {
            properties.setHeader("key", key);
        }
        CorrelationData correlation = new CorrelationData();
        rabbitTemplate.send(dest.exchange(), dest.routingKey(), new Message(value, properties), correlation);
        awaitConfirm(destination, correlation);
    }

    private void awaitConfirm(String destination, CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EventPublishException("no broker confirm for " + destination + " within " + confirmTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("interrupted waiting for the broker's confirm for " + destination, e);
        } catch (ExecutionException e) {
            throw new EventPublishException("failed waiting for the broker's confirm for " + destination, e);
        }
        // The broker acks a mandatory message it could not route, after returning it.
        if (correlation.getReturned() != null) {
            throw new EventPublishException("unroutable: no queue is bound for " + destination, null, true);
        }
        if (!confirm.ack()) {
            throw new EventPublishException("not confirmed by the broker: " + destination, null, true);
        }
    }

    @Override
    public Subscription subscribe(String destination, String group, TransportListener listener) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        RabbitDestination dest = RabbitDestination.parse(destination);
        String queueName = queueName(dest, group);
        declareTopology(dest, queueName);

        var container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        // Rejected (retries exhausted) does NOT requeue to the same queue; the queue's
        // x-dead-letter-exchange argument (declared below) is what routes it to the DLQ instead.
        container.setDefaultRequeueRejected(false);
        container.setAdviceChain(retryInterceptor());
        container.setMessageListener((MessageListener) message -> deliver(message, listener));
        container.start();
        containers.add(container);
        return () -> {
            containers.remove(container);
            container.stop();
        };
    }

    private MethodInterceptor retryInterceptor() {
        // RejectAndDontRequeueRecoverer: once retries are exhausted, reject without requeue so the
        // broker's x-dead-letter-exchange argument (declared in declareTopology) routes the
        // message to the DLQ. maxRetries() is retries AFTER the first attempt, hence -1.
        return RetryInterceptorBuilder.stateless()
                .maxRetries(Math.max(0, maxAttempts - 1))
                .backOffOptions(backoff.toMillis(), 1.0, backoff.toMillis())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    // Package-private (not private): exercised directly by docker-free unit tests without needing
    // a live SimpleMessageListenerContainer poll loop.
    static void deliver(Message message, TransportListener listener) {
        byte[] key = (byte[]) message.getMessageProperties().getHeaders().get("key");
        listener.onMessage(key, message.getBody(), fromRabbitHeaders(message));
    }

    static Map<String, String> fromRabbitHeaders(Message message) {
        Map<String, String> headers = new LinkedHashMap<>();
        message.getMessageProperties().getHeaders().forEach((name, value) -> {
            if (!"key".equals(name)) {
                headers.put(name, String.valueOf(value));
            }
        });
        return headers;
    }

    private static String queueName(RabbitDestination dest, String group) {
        String routingKeyPart = dest.routingKey().isEmpty() ? "all" : dest.routingKey();
        return dest.exchange() + "." + routingKeyPart + "." + group;
    }

    private void declareTopology(RabbitDestination dest, String queueName) {
        TopicExchange exchange = new TopicExchange(dest.exchange(), true, false);
        rabbitAdmin.declareExchange(exchange);
        String dlxName = dest.exchange() + ".dlx";
        DirectExchange deadLetterExchange = new DirectExchange(dlxName, true, false);
        rabbitAdmin.declareExchange(deadLetterExchange);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", dlxName);
        arguments.put("x-dead-letter-routing-key", queueName);
        if (quorumQueues) {
            arguments.put("x-queue-type", "quorum");
        }
        Queue queue = new Queue(queueName, true, false, false, arguments);
        rabbitAdmin.declareQueue(queue);
        String routingKey = dest.routingKey().isEmpty() ? "#" : dest.routingKey();
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));

        Queue deadLetterQueue = new Queue(queueName + ".dlq", true, false, false);
        rabbitAdmin.declareQueue(deadLetterQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queueName));
    }

    @Override
    public void close() {
        containers.forEach(SimpleMessageListenerContainer::stop);
        containers.clear();
    }
}
