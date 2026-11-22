package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * HI-0167: the masker never loads a JPA value. Real Hibernate and H2, because a stand-in for Hibernate's proxy cannot
 * show that a proxy's {@code toString()} loads it.
 */
class JpaValueMaskingTest {

    private static final AtomicInteger SQL = new AtomicInteger();
    private static final AtomicInteger ENTITY_CALLS = new AtomicInteger();
    private static SessionFactory factory;

    private final PlatformValueMasker masker = new PlatformValueMasker(List.of());

    @Entity(name = "Customer")
    public static class Customer {
        @Id
        Long id;
        String name;

        public String getName() {
            return name;
        }
    }

    @Entity(name = "Line")
    public static class Line {
        @Id
        Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        Purchase purchase;

        public Long getId() {
            return id;
        }
    }

    @Entity(name = "Purchase")
    public static class Purchase {
        @Id
        Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        Customer customer;
        @OneToMany(mappedBy = "purchase")
        Set<Line> lines = new HashSet<>();

        public int getLineCount() {
            ENTITY_CALLS.incrementAndGet();
            return lines.size();
        }

        @Override
        public String toString() {
            ENTITY_CALLS.incrementAndGet();
            return "Purchase(lines=" + lines + ")";
        }
    }

    record Holder(Object value) {
    }

    public static final class Bean {
        private final Object value;

        Bean(Object value) {
            this.value = value;
        }

        public Object getValue() {
            return value;
        }
    }

    @BeforeAll
    static void database() {
        factory = new Configuration()
                .addAnnotatedClass(Customer.class).addAnnotatedClass(Line.class).addAnnotatedClass(Purchase.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:jpa-masking;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create")
                .setStatementInspector(sql -> {
                    SQL.incrementAndGet();
                    return sql;
                })
                .buildSessionFactory();
        factory.inTransaction(session -> {
            Customer customer = new Customer();
            customer.id = 1L;
            customer.name = "customer-name";
            session.persist(customer);
            Purchase purchase = new Purchase();
            purchase.id = 1L;
            purchase.customer = customer;
            session.persist(purchase);
            Line line = new Line();
            line.id = 1L;
            line.purchase = purchase;
            session.persist(line);
        });
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void aLazyProxyIsNamedAndNeverLoaded() {
        neverLoaded(session -> session.getReference(Customer.class, 1L), "[entity Customer]", false);
    }

    @Test
    void anUninitialisedCollectionIsNeverLoaded() {
        neverLoaded(session -> session.find(Purchase.class, 1L).lines, "[not loaded]", false);
    }

    @Test
    void anEntityIsNamedAndNoneOfItsMethodsRun() {
        neverLoaded(session -> session.find(Purchase.class, 1L), "[entity Purchase]", true);
    }

    @Test
    void anInitialisedCollectionIsItsElementsEachUnderTheSameRules() {
        try (EntityManager session = factory.createEntityManager()) {
            Set<Line> lines = session.find(Purchase.class, 1L).lines;
            Hibernate.initialize(lines);
            SQL.set(0);
            assertThat(PlatformValueMasker.text(masker.sanitize("lines", lines))).isEqualTo("[[entity Line]]");
            assertThat(SQL).hasValue(0);
        }
    }

    @Test
    void aMessageArgumentIsFormattedWithoutLoadingIt() {
        // The formatter's part. Logback's appender renders arguments itself before it runs (HI-0157).
        try (EntityManager session = factory.createEntityManager()) {
            Customer proxy = session.getReference(Customer.class, 1L);
            ch.qos.logback.classic.LoggerContext context = new ch.qos.logback.classic.LoggerContext();
            ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent("test",
                    context.getLogger("jpa.probe"), ch.qos.logback.classic.Level.INFO, "saw {}", null,
                    new Object[] {proxy});
            SQL.set(0);
            assertThat(String.valueOf(PlatformLogstashFormatter.message(event, masker)))
                    .isEqualTo("saw [entity Customer]");
            assertThat(SQL).hasValue(0);
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
        }
    }

    // Every way the value can reach the masker: a pair, Boot's member processor, nested, and as a map key.
    private void neverLoaded(Function<EntityManager, Object> load, String placeholder, boolean loaded) {
        List<Function<Object, Object>> ways = new ArrayList<>();
        ways.add(value -> masker.sanitizePair("value", value));
        ways.add(value -> masker.mask(null, value));
        ways.add(value -> masker.sanitize("outer", Map.of("inner", value)));
        ways.add(value -> masker.sanitize("outer", List.of(value)));
        ways.add(value -> masker.sanitize("outer", new Holder(value)));
        ways.add(value -> masker.sanitize("outer", new Bean(value)));
        // Map.of would hash the key, which loads a collection by itself.
        ways.add(value -> {
            Map<Object, String> keyed = new java.util.IdentityHashMap<>();
            keyed.put(value, "x");
            return masker.sanitize("outer", keyed);
        });
        for (int i = 0; i < ways.size(); i++) {
            try (EntityManager session = factory.createEntityManager()) {
                Object value = load.apply(session);
                SQL.set(0);
                ENTITY_CALLS.set(0);
                String text = PlatformValueMasker.text(ways.get(i).apply(value));
                assertThat(SQL).as("statements, way %d", i).hasValue(0);
                assertThat(ENTITY_CALLS).as("entity methods, way %d", i).hasValue(0);
                assertThat(Hibernate.isInitialized(value)).as("initialised, way %d", i).isEqualTo(loaded);
                assertThat(text).as("text, way %d", i).contains(placeholder).doesNotContain("customer-name");
            }
        }
    }
}
