package ae.gov.dubaicustoms.example.goldenpath.orders;

import java.math.BigDecimal;

/**
 * API view of an {@link Order}. Kept separate from the entity so persistence details never leak into
 * the contract.
 *
 * @param id the generated identifier
 * @param customer the ordering party
 * @param amount the order total
 * @param status the current lifecycle state
 */
public record OrderResponse(Long id, String customer, BigDecimal amount, OrderStatus status) {

    static OrderResponse of(Order order) {
        return new OrderResponse(order.getId(), order.getCustomer(), order.getAmount(), order.getStatus());
    }
}
