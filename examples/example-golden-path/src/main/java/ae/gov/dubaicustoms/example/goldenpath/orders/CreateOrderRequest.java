package ae.gov.dubaicustoms.example.goldenpath.orders;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /orders}. The constraints are enforced by the platform's validation
 * layer: a violation becomes an RFC-9457 400 with a per-field {@code errors[]} extension, with no
 * validation code in the controller.
 *
 * @param customer the ordering party; must be present
 * @param amount the order total; must be present and strictly positive
 */
public record CreateOrderRequest(
        @NotBlank String customer,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
