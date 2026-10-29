package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for the Oracle tests (paging, translated exceptions, timeout defaults). */
interface OracleWidgetRepository extends JpaRepository<OracleWidget, Long> {

    /** A declared query with no {@code @Transactional} of its own: SQL-engine work, about 10 s uncancelled. */
    @Query(value = "select count(*) from (select level l from dual connect by level <= 20000) a,"
            + " (select level l from dual connect by level <= 20000) b", nativeQuery = true)
    long heavyCount();
}
