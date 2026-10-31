package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * With Hibernate's own nullability check on (its default when Bean Validation is absent), a null in a
 * {@code nullable = false} column is rejected before any SQL: the chain the errors capability
 * classifies as a 409 (D111) is {@code org.hibernate.PropertyValueException}, untranslated on the
 * {@code EntityManager} path. Excluded from the default build (docker JUnit tag); run under
 * {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.check_nullability=true"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OracleNullabilityCheckIT {

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aNullInANonNullableColumnIsAPropertyValueExceptionBeforeAnySql() {
        Throwable thrown = catchThrowable(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> em.persist(new OracleNote())));
        List<String> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null && chain.size() < 20; t = t.getCause()) {
            chain.add(t.getClass().getName());
        }
        System.out.println("observed chain: " + chain);
        assertThat(chain).contains("org.hibernate.PropertyValueException");
        assertThat(thrown).hasStackTraceContaining("text").satisfies(t ->
                assertThat(t.toString()).doesNotContain("ORA-01400"));
    }
}
