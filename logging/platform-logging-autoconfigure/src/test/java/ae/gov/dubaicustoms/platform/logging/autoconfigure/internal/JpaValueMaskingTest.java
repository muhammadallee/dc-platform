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

    /** An entity that is also text: its proxy must not reach a CharSequence shortcut. */
    @Entity(name = "Code")
    public static class Code implements CharSequence {
        @Id
        Long id;
        String text;

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** A Spring Data interface projection. */
    public interface CustomerView {
        String getName();
    }

    /** A map key whose text is a credential name. */
    record Key(String name) {
        @Override
        public String toString() {
            return name;
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
                .addAnnotatedClass(Code.class)
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
            Code code = new Code();
            code.id = 1L;
            code.text = "code-value";
            session.persist(code);
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
    void aProxyThatIsTextIsNeverRenderedThroughATextShortcut() {
        neverLoaded(session -> session.getReference(Code.class, 1L), "[entity Code]", false);
    }

    @Test
    void aSpringDataProjectionOverAProxyIsNamedAndNothingOfItRuns() {
        try (EntityManager session = factory.createEntityManager()) {
            Customer proxy = session.getReference(Customer.class, 1L);
            CustomerView view = new org.springframework.data.projection.SpelAwareProxyProjectionFactory()
                    .createProjection(CustomerView.class, proxy);
            SQL.set(0);
            assertThat(PlatformValueMasker.text(masker.sanitizePair("view", view))).isEqualTo("[proxy CustomerView]");
            assertThat(PlatformValueMasker.text(masker.mask(null, Map.of("view", view))))
                    .isEqualTo("{view=[proxy CustomerView]}");
            assertThat(SQL).hasValue(0);
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
        }
    }

    @Test
    void aJdkObjectHoldingAProxyIsItsTypeName() {
        try (EntityManager session = factory.createEntityManager()) {
            Customer proxy = session.getReference(Customer.class, 1L);
            SQL.set(0);
            assertThat(PlatformValueMasker.text(masker.sanitizePair("held",
                    new java.util.concurrent.atomic.AtomicReference<>(proxy)))).isEqualTo("[AtomicReference]");
            assertThat(SQL).hasValue(0);
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
        }
    }

    /** An application number whose text renders what it holds. */
    public static final class Amount extends Number {
        private final transient Object entity;

        Amount(Object entity) {
            this.entity = entity;
        }

        public Object getEntity() {
            return entity;
        }

        @Override
        public int intValue() {
            return 1;
        }

        @Override
        public long longValue() {
            return 1;
        }

        @Override
        public float floatValue() {
            return 1;
        }

        @Override
        public double doubleValue() {
            return 1;
        }

        @Override
        public String toString() {
            return String.valueOf(entity);
        }
    }

    /** An application exception whose text renders what it holds. */
    public static final class Rejected extends RuntimeException {
        private final transient Object entity;

        Rejected(Object entity) {
            this.entity = entity;
        }

        @Override
        public String toString() {
            return "rejected " + entity;
        }
    }

    @Test
    void textLikeProxiesAndApplicationScalarsNeverRenderAProxy() {
        // Codex phase 4 round 1 reassessment: a JDK proxy that is text or a path, a number and an exception of the
        // service's own, each holding an unloaded reference.
        List<Function<Object, Object>> holders = List.of(
                proxy -> java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {CharSequence.class}, (self, method, args) -> String.valueOf(proxy)),
                proxy -> java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {java.nio.file.Path.class}, (self, method, args) -> String.valueOf(proxy)),
                Amount::new,
                Rejected::new);
        for (int i = 0; i < holders.size(); i++) {
            for (int way = 0; way < 3; way++) {
                try (EntityManager session = factory.createEntityManager()) {
                    Customer proxy = session.getReference(Customer.class, 1L);
                    Object holder = holders.get(i).apply(proxy);
                    SQL.set(0);
                    Object out = switch (way) {
                        case 0 -> masker.sanitizePair("value", holder);
                        case 1 -> masker.mask(null, holder);
                        default -> masker.sanitize("outer", List.of(holder));
                    };
                    assertThat(SQL).as("holder %d, way %d: %s", i, way, out).hasValue(0);
                    assertThat(Hibernate.isInitialized(proxy)).as("holder %d, way %d", i, way).isFalse();
                }
            }
        }
    }

    @Test
    void aThrowingEntryCostsOnlyItselfAndFloatingKeysArePlain() {
        Map.Entry<String, String> broken = new Map.Entry<>() {
            @Override
            public String getKey() {
                return "k";
            }

            @Override
            public String getValue() {
                throw new IllegalStateException("password=hunter12");
            }

            @Override
            public String setValue(String value) {
                throw new UnsupportedOperationException();
            }
        };
        String text = PlatformValueMasker.text(masker.sanitize("outer", List.of("keep-me", broken)));
        assertThat(text).contains("keep-me").contains("k=[unrenderable]").doesNotContain("hunter12");
        assertThat(PlatformValueMasker.text(masker.sanitize("outer", Map.of(1.5d, "healthy")))).isEqualTo("{1.5=healthy}");
    }

    @Test
    void aKeyThatIsNotAPlainValueMasksItsValue() {
        // Its text is a credential name, which the masker cannot read without calling the key's own code.
        String text = PlatformValueMasker.text(masker.sanitize("outer", Map.of(new Key("password"), "opaque-credential")));
        assertThat(text).doesNotContain("opaque-credential").contains("****");
        assertThat(PlatformValueMasker.text(masker.sanitize("outer", Map.of("password", "opaque-credential"))))
                .doesNotContain("opaque-credential");
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
        ways.add(value -> masker.sanitize("outer", java.util.Optional.of(value)));
        ways.add(value -> masker.sanitize("outer", Map.entry("inner", value)));
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
