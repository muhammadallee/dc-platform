package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the H2 slice tests (repository meters). */
interface AuditedWidgetRepository extends JpaRepository<AuditedWidget, Long> {
}
