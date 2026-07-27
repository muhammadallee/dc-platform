package ae.gov.dubaicustoms.example.goldenpath.orders;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over {@link Order}. Per the platform conventions the service never manages
 * {@code EntityManager}s or transactions by hand — {@code @Transactional} on the service layer plus
 * this interface is the whole persistence surface.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
