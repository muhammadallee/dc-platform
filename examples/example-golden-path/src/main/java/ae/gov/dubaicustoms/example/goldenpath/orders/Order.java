package ae.gov.dubaicustoms.example.goldenpath.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * An order, persisted with the platform's JPA conventions (snake_case table/column naming, JPA
 * auditing, and {@code open-in-view=false}) — none of which this class configures; the
 * {@code platform-starter-data-jpa} on the classpath does. H2 backs it in dev and test; a real
 * datasource backs it under the {@code pg} profile.
 */
@Entity
@Table(name = "orders") // "order" is a reserved SQL word; name the table explicitly.
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customer;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    protected Order() {
        // JPA
    }

    public Order(String customer, BigDecimal amount) {
        this.customer = customer;
        this.amount = amount;
        this.status = OrderStatus.PLACED;
    }

    public Long getId() {
        return id;
    }

    public String getCustomer() {
        return customer;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
