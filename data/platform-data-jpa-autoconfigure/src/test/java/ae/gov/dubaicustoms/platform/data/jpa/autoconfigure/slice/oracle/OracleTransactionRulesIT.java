package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring's declarative rollback rules against Oracle (scope R3.3, AC2), through a real
 * {@code @Transactional} service proxy: a write followed by a runtime exception leaves no row; a write
 * followed by a checked exception is committed (Spring's default — declare {@code rollbackFor} when that
 * is not wanted). Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OracleTransactionRulesIT.WidgetService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OracleTransactionRulesIT {

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private WidgetService service;

    @Autowired
    private OracleWidgetRepository widgets;

    @AfterEach
    void clean() {
        widgets.deleteAllInBatch();
    }

    @Test
    void aRuntimeExceptionAfterAWriteRollsItBack() {
        assertThatThrownBy(() -> service.saveThenFail("RB-RUNTIME", new IllegalStateException("business rule")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(widgets.findAll()).noneMatch(w -> "RB-RUNTIME".equals(w.referenceCode));
    }

    @Test
    void aCheckedExceptionAfterAWriteCommitsItByDefault() {
        assertThatThrownBy(() -> service.saveThenFailChecked("RB-CHECKED")).isInstanceOf(Exception.class);
        assertThat(widgets.findAll()).anyMatch(w -> "RB-CHECKED".equals(w.referenceCode));
    }

    @Test
    void aWriteWithoutAnExceptionIsVisibleAfterCommit() {
        service.saveThenFail("RB-OK", null);
        assertThat(widgets.findAll()).anyMatch(w -> "RB-OK".equals(w.referenceCode));
    }

    /** A service as an application writes it: the transaction boundary is the public method. */
    static class WidgetService {

        private final OracleWidgetRepository widgets;

        WidgetService(OracleWidgetRepository widgets) {
            this.widgets = widgets;
        }

        @Transactional
        public void saveThenFail(String reference, RuntimeException failure) {
            widgets.saveAndFlush(widget(reference));
            if (failure != null) {
                throw failure;
            }
        }

        @Transactional
        public void saveThenFailChecked(String reference) throws Exception {
            widgets.saveAndFlush(widget(reference));
            throw new Exception("checked");
        }

        private static OracleWidget widget(String reference) {
            OracleWidget widget = new OracleWidget();
            widget.referenceCode = reference;
            return widget;
        }
    }
}
