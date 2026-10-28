package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the Oracle tests (paging, translated exceptions). */
interface OracleWidgetRepository extends JpaRepository<OracleWidget, Long> {
}
