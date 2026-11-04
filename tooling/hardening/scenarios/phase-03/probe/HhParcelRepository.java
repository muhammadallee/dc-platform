package ae.gov.dubaicustoms.hardening.probe;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository over {@link HhParcel} (the repository path of the harness). */
public interface HhParcelRepository extends JpaRepository<HhParcel, Long> {

    long countByRefCode(String refCode);
}
