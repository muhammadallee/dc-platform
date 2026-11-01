package ae.gov.dubaicustoms.platform.messaging.rabbit;

import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.AbstractRoutingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

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
 * Topology is declared on every new connection, so a subscription does not need the broker at startup, and a broker that
 * lost its definitions gets them back.
 *
 * <p>This transport does not retry: {@code platform-messaging-autoconfigure}'s registrar does. A listener that throws
 * has its message rejected without requeue, and the queue's {@code x-dead-letter-exchange} routes it to the DLQ; a
 * {@link TransportListener.RequeueException} puts it back. A quorum queue's {@code x-delivery-limit} also dead-letters
 * a message that keeps killing its consumer.
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

    private static final Logger log = LoggerFactory.getLogger(RabbitEventTransport.class);
    private static final String UNACKNOWLEDGED = "A send could be lost without anyone knowing. Set ";
    private static final int MAX_SENDERS_IN_FLIGHT = 64;

    private final RabbitTemplate rabbitTemplate;
    private final ConnectionFactory connectionFactory;
    private final RabbitAdmin rabbitAdmin;
    private final boolean quorumQueues;
    private final Duration confirmTimeout;
    private final int prefetch;
    private final Duration shutdownTimeout;
    private final int deliveryLimit;
    private final CopyOnWriteArrayList<Runnable> topologies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SimpleMessageListenerContainer> containers = new CopyOnWriteArrayList<>();
    private final Semaphore sendersInFlight = new Semaphore(MAX_SENDERS_IN_FLIGHT);
    private final Set<Thread> senders = ConcurrentHashMap.newKeySet();

    /**
     * Creates the transport.
     *
     * @param rabbitTemplate the Boot-configured template used for publish; never {@code null}
     * @param connectionFactory the Boot-configured connection factory each subscription's container
     *     and the topology declarations are built from; never {@code null}
     * @param quorumQueues declare quorum queues ({@code x-queue-type=quorum}), the main and the dead-letter one,
     *     instead of classic queues
     * @param confirmTimeout how long a send waits for the broker's confirm; never {@code null}
     * @param prefetch how many messages a consumer holds at once; at least 1
     * @param shutdownTimeout how long a stopping consumer waits for the listener in flight; never {@code null}
     * @param deliveryLimit deliveries a quorum queue allows before dead-lettering; at least 1
     * @throws IllegalStateException if publisher confirms, returns or {@code mandatory} are off
     */
    public RabbitEventTransport(RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory,
            boolean quorumQueues, Duration confirmTimeout, int prefetch, Duration shutdownTimeout, int deliveryLimit) {
        this(rabbitTemplate, connectionFactory, new RabbitAdmin(connectionFactory), quorumQueues, confirmTimeout,
                prefetch, shutdownTimeout, deliveryLimit);
    }

    // Package-private: lets docker-free unit tests inject a mocked RabbitAdmin instead of one
    // backed by a real broker channel.
    RabbitEventTransport(RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, RabbitAdmin rabbitAdmin,
            boolean quorumQueues, Duration confirmTimeout, int prefetch, Duration shutdownTimeout, int deliveryLimit) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.rabbitAdmin = Objects.requireNonNull(rabbitAdmin, "rabbitAdmin must not be null");
        this.quorumQueues = quorumQueues;
        this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout must not be null");
        this.prefetch = prefetch;
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout must not be null");
        this.deliveryLimit = deliveryLimit;
        requireAcknowledgedPublishing(rabbitTemplate, connectionFactory);
        connectionFactory.addConnectionListener(connection -> redeclareTopologies());
    }

    private static void requireAcknowledgedPublishing(RabbitTemplate template, ConnectionFactory factory) {
        // A send runs on its own thread, so a routing key bound to the caller's thread would not be seen there, and the
        // message could reach the default broker.
        if (factory instanceof AbstractRoutingConnectionFactory) {
            throw new IllegalStateException("A routing ConnectionFactory is not supported by this transport");
        }
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
        Message message = new Message(value, properties);
        // Evaluated per message: a mandatory expression can be true for the constructor's probe and false here.
        if (!rabbitTemplate.isMandatoryFor(message)) {
            throw new EventPublishException("not mandatory, so an unroutable message would be dropped: " + destination, null);
        }
        CorrelationData correlation = new CorrelationData();
        // The whole operation is bounded. A broker under a resource alarm blocks the publish itself, before any confirm
        // can be awaited, so it runs apart from the caller. If it gets through after the timeout, that is a duplicate
        // of the retry, which at-least-once delivery allows. Senders the broker is holding are capped: past the cap a
        // send fails at once, as an outage, rather than piling up threads and channels during a long alarm.
        if (!sendersInFlight.tryAcquire()) {
            throw new EventPublishException("too many sends waiting on the broker: " + destination, null);
        }
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        CompletableFuture<Void> publish = new CompletableFuture<>();
        Thread sender = Thread.ofVirtual().name("platform-rabbit-send").unstarted(() -> {
            try {
                rabbitTemplate.send(dest.exchange(), dest.routingKey(), message, correlation);
                publish.complete(null);
            } catch (RuntimeException e) {
                publish.completeExceptionally(e);
            } finally {
                sendersInFlight.release();
                senders.remove(Thread.currentThread());
            }
        });
        senders.add(sender);
        sender.start();
        try {
            publish.get(confirmTimeout.toNanos(), TimeUnit.NANOSECONDS);
            awaitConfirm(destination, correlation, Math.max(0, deadline - System.nanoTime()));
        } catch (TimeoutException e) {
            sender.interrupt();
            throw new EventPublishException("no broker confirm for " + destination + " within " + confirmTimeout, e);
        } catch (InterruptedException e) {
            sender.interrupt();
            Thread.currentThread().interrupt();
            throw new EventPublishException("interrupted waiting for the broker's confirm for " + destination, e);
        } catch (ExecutionException e) {
            throw new EventPublishException("failed to send to " + destination, e.getCause());
        }
    }

    private static void awaitConfirm(String destination, CorrelationData correlation, long nanos)
            throws TimeoutException, InterruptedException, ExecutionException {
        CorrelationData.Confirm confirm = correlation.getFuture().get(nanos, TimeUnit.NANOSECONDS);
        // The broker acks a mandatory message it could not route, after returning it.
        if (correlation.getReturned() != null) {
            throw new EventPublishException("unroutable: no queue is bound for " + destination, null, true);
        }
        if (!confirm.ack()) {
            // Spring also synthesizes a nack when a connection or channel closes with confirms outstanding: that is an
            // outage, and retrying is right. Only a broker's "no such exchange" is an answer about this message.
            String reason = confirm.reason();
            boolean rejected = reason != null && (reason.contains("reply-code=404") || reason.contains("NOT_FOUND"));
            throw new EventPublishException("not confirmed by the broker: " + destination, null, rejected);
        }
    }

    @Override
    public boolean deadLettersNatively() {
        return true;
    }

    private void redeclareTopologies() {
        topologies.forEach(Runnable::run);
    }

    @Override
    public Subscription subscribe(String destination, String group, TransportListener listener) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        RabbitDestination dest = RabbitDestination.parse(destination);
        String queueName = queueName(dest, group);
        Runnable topology = () -> declareTopology(dest, queueName);
        topologies.add(topology);
        try {
            topology.run();
        } catch (AmqpConnectException e) {
            // Not fatal: the outbox absorbs a broker outage, and the connection listener declares this on connecting.
            log.warn("RabbitMQ is unreachable; subscribing to {} once it is", destination);
        }

        var container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        // Rejected does NOT requeue to the same queue; the queue's x-dead-letter-exchange argument (declared below) is
        // what routes it to the DLQ instead. The registrar has already retried.
        container.setDefaultRequeueRejected(false);
        container.setMissingQueuesFatal(false);
        container.setPrefetchCount(prefetch);
        container.setShutdownTimeout(shutdownTimeout.toMillis());
        container.setMessageListener((MessageListener) message -> {
            try {
                deliver(message, listener);
            } catch (TransportListener.RequeueException e) {
                throw new ImmediateRequeueAmqpException(e.getMessage(), e);
            }
        });
        container.start();
        containers.add(container);
        return () -> {
            containers.remove(container);
            topologies.remove(topology);
            container.stop();
        };
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
            arguments.put("x-delivery-limit", deliveryLimit);
        }
        Queue queue = new Queue(queueName, true, false, false, arguments);
        rabbitAdmin.declareQueue(queue);
        String routingKey = dest.routingKey().isEmpty() ? "#" : dest.routingKey();
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));

        Map<String, Object> deadLetterArguments = quorumQueues ? Map.of("x-queue-type", "quorum") : Map.of();
        Queue deadLetterQueue = new Queue(queueName + ".dlq", true, false, false, new HashMap<>(deadLetterArguments));
        rabbitAdmin.declareQueue(deadLetterQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queueName));
    }

    @Override
    public void close() {
        senders.forEach(Thread::interrupt);
        containers.forEach(SimpleMessageListenerContainer::stop);
        containers.clear();
    }
}
