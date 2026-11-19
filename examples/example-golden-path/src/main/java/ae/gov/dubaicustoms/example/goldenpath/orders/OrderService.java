package ae.gov.dubaicustoms.example.goldenpath.orders;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * The orders business logic — and a tour of the platform APIs a service actually calls:
 * <ul>
 *   <li>{@code @Transactional} + {@link OrderRepository} for persistence (no EntityManager here);</li>
 *   <li>{@link OutboxPublisher} to emit {@link OrderPlaced} in the same transaction as the order — never a broker
 *       template, and never a direct publish, which could announce an order that then rolls back.</li>
 * </ul>
 */
@Service
public class OrderService {

    private static final String ORDER_NOT_FOUND = "DC-XMPL-0404";

    private final OrderRepository repository;
    private final OutboxPublisher outbox;

    public OrderService(OrderRepository repository, OutboxPublisher outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    /**
     * Persists a new order and publishes {@link OrderPlaced}.
     *
     * @param request the validated create request
     * @return the created order
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Order saved = repository.save(new Order(request.customer(), request.amount()));
        OrderPlaced event = new OrderPlaced(saved.getId(), saved.getCustomer(), saved.getAmount());
        // Commits with the order; the outbox's drainer delivers it, at least once, even if the broker is down right now.
        outbox.publish(OrderPlaced.DESTINATION, EventEnvelope.of(event).build());
        return OrderResponse.of(saved);
    }

    /**
     * Reads an order.
     *
     * @param id the order id
     * @return the order
     * @throws ErrorResponseException a 404 with code {@code DC-XMPL-0404} if no order has that id
     */
    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return repository.findById(id)
                .map(OrderResponse::of)
                .orElseThrow(() -> notFound(id));
    }

    // A service's own error: Spring's ErrorResponseException with its code; the platform adds the rest.
    private static ErrorResponseException notFound(Long id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "order %d not found".formatted(id));
        problem.setProperty("code", ORDER_NOT_FOUND);
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
