package ae.gov.dubaicustoms.example.goldenpath.orders;

import ae.gov.dubaicustoms.platform.audit.Audited;
import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.errors.NotFoundException;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The orders business logic — and a tour of the platform APIs a service actually calls:
 * <ul>
 *   <li>{@code @Transactional} + {@link OrderRepository} for persistence (no EntityManager here);</li>
 *   <li>{@link OutboxPublisher} to emit {@link OrderPlaced} in the same transaction as the order — never a broker
 *       template, and never a direct publish, which could announce an order that then rolls back;</li>
 *   <li>{@link Audited @Audited} to record {@code order.create} in the audit trail;</li>
 *   <li>{@code @Cacheable} to serve reads from the platform cache.</li>
 * </ul>
 */
@Service
public class OrderService {

    private static final ErrorCode ORDER_NOT_FOUND = new ErrorCode("DC-XMPL-0404");

    private final OrderRepository repository;
    private final OutboxPublisher outbox;

    public OrderService(OrderRepository repository, OutboxPublisher outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    /**
     * Persists a new order and publishes {@link OrderPlaced}. The audit event's {@code resource} is the
     * new order id, taken from the returned {@link OrderResponse} ({@code #result.id}).
     *
     * @param request the validated create request
     * @return the created order
     */
    @Transactional
    @Audited(action = "order.create", resourceExpression = "#result.id")
    public OrderResponse create(CreateOrderRequest request) {
        Order saved = repository.save(new Order(request.customer(), request.amount()));
        OrderPlaced event = new OrderPlaced(saved.getId(), saved.getCustomer(), saved.getAmount());
        // Commits with the order; the outbox's drainer delivers it, at least once, even if the broker is down right now.
        outbox.publish(OrderPlaced.DESTINATION, EventEnvelope.of(event).build());
        return OrderResponse.of(saved);
    }

    /**
     * Reads an order, serving repeat reads from the {@code orders} cache.
     *
     * @param id the order id
     * @return the order
     * @throws NotFoundException if no order has that id
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "orders", key = "#id")
    public OrderResponse get(Long id) {
        return repository.findById(id)
                .map(OrderResponse::of)
                .orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND, "order %d not found".formatted(id)));
    }
}
