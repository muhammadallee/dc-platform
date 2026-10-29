package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Oracle dialect behaviour the platform documents (scope R6), with Hibernate in 19c mode (HD13):
 * identifiers, sequence and identity keys, {@code boolean} on {@code NUMBER(1)}, {@code Instant}
 * across JVM and session time zones, {@code OFFSET … FETCH} paging, and Oracle's {@code ''} = NULL.
 * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle.SqlCapture"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OracleDialectIT {

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private OracleWidgetRepository widgets;

    @Autowired
    private DataSource dataSource;

    @Test
    void unquotedSnakeCaseNamesAreStoredUpperCaseAnd128CharactersIsTheLimit() throws SQLException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "select count(*) from user_tables where table_name = 'ORACLE_WIDGET'", Integer.class)).isEqualTo(1);

        String longest = "t" + "x".repeat(127);
        jdbc.execute("create table " + longest + " (id number(19))");
        jdbc.execute("drop table " + longest);
        assertThatThrownBy(() -> jdbc.execute("create table " + longest + "y (id number(19))")).rootCause()
                .hasMessageContaining("ORA-00972");
    }

    @Test
    void sequenceAndIdentityKeysAreGenerated() {
        OracleWidget first = widget("SEQ-1");
        OracleWidget second = widget("SEQ-2");
        em.persist(first);
        em.persist(second);
        OracleNote note = new OracleNote();
        note.text = "identity";
        em.persist(note);
        em.flush();

        assertThat(first.id).isNotNull();
        assertThat(second.id).isNotNull().isNotEqualTo(first.id);
        assertThat(note.id).isNotNull();
        Number increment = (Number) em.createNativeQuery(
                "select increment_by from user_sequences where sequence_name = 'ORACLE_WIDGET_SEQ'").getSingleResult();
        assertThat(increment.intValue()).as("allocationSize must equal INCREMENT BY").isEqualTo(50);
    }

    @Test
    void booleanIsStoredAsNumberOne() {
        OracleWidget widget = widget("BOOL-1");
        widget.active = true;
        em.persist(widget);
        em.flush();

        Number stored = (Number) em.createNativeQuery(
                "select active from oracle_widget where reference_code = 'BOOL-1'").getSingleResult();
        assertThat(stored.intValue()).isEqualTo(1);
        String type = (String) em.createNativeQuery(
                "select data_type from user_tab_columns where table_name = 'ORACLE_WIDGET' and column_name = 'ACTIVE'")
                .getSingleResult();
        assertThat(type).isEqualTo("NUMBER");
    }

    @Test
    void instantRoundTripsUnchangedAcrossJvmAndSessionTimeZones() {
        Instant when = Instant.parse("2026-03-29T01:30:00.654321Z");
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
            em.createNativeQuery("alter session set time_zone = '-05:00'").executeUpdate();
            OracleWidget widget = widget("TZ-1");
            widget.happenedAt = when;
            em.persist(widget);
            em.flush();
            em.clear();

            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            em.createNativeQuery("alter session set time_zone = '+09:00'").executeUpdate();
            OracleWidget loaded = em.find(OracleWidget.class, widget.id);
            assertThat(loaded.happenedAt).isEqualTo(when);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void offsetDateTimeRoundTripsToTheSameInstantAcrossJvmAndSessionTimeZones() {
        OffsetDateTime when = OffsetDateTime.parse("2026-10-29T18:15:30.123456+04:00");
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
            em.createNativeQuery("alter session set time_zone = '-05:00'").executeUpdate();
            OracleWidget widget = widget("TZ-OFFSET");
            widget.offsetAt = when;
            em.persist(widget);
            em.flush();
            em.clear();

            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            em.createNativeQuery("alter session set time_zone = '+09:00'").executeUpdate();
            OracleWidget loaded = em.find(OracleWidget.class, widget.id);
            // The instant is the contract; the offset Hibernate returns depends on its time-zone storage.
            assertThat(loaded.offsetAt).isAtSameInstantAs(when);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void pagingUsesOffsetFetchAndIsStableWithAnOrderBy() {
        for (int i = 0; i < 7; i++) {
            em.persist(widget("PAGE-" + i));
        }
        em.flush();
        em.clear();
        SqlCapture.STATEMENTS.clear();

        Page<OracleWidget> page = widgets.findAll(PageRequest.of(1, 3, Sort.by("referenceCode")));

        assertThat(page.getContent()).extracting(w -> w.referenceCode).containsExactly("PAGE-3", "PAGE-4", "PAGE-5");
        assertThat(page.getTotalElements()).isEqualTo(7);
        List<String> selects = SqlCapture.STATEMENTS.stream().filter(sql -> sql.contains("order by")).toList();
        assertThat(selects).anySatisfy(sql -> assertThat(sql.toLowerCase())
                .contains("offset ? rows fetch first ? rows only").doesNotContain("rownum"));
    }

    @Test
    void anEmptyStringIsNullOnOracleSoANotNullColumnRejectsIt() {
        OracleWidget widget = widget("");
        assertThatThrownBy(() -> widgets.saveAndFlush(widget))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ORA-01400");
    }

    private static OracleWidget widget(String reference) {
        OracleWidget widget = new OracleWidget();
        widget.referenceCode = reference;
        widget.price = Money.of("1.00", "AED");
        return widget;
    }
}
