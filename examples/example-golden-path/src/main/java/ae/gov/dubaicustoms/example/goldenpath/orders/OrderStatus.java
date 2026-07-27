package ae.gov.dubaicustoms.example.goldenpath.orders;

/** Lifecycle of an {@link Order}; persisted as a string so the column stays readable and stable. */
public enum OrderStatus {
    PLACED,
    SHIPPED,
    CANCELLED
}
