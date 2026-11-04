package ae.gov.dubaicustoms.hardening.probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Consumer entity for the hardening phase-03 harness. Its table ({@code V2__hh_probe_tables.sql}) carries
 * a unique key, a foreign key, a check and a NOT NULL column with distinctive names, so the scenario can
 * assert that none of them reaches a response body. No Bean Validation annotations: the database is
 * the one that refuses.
 */
@Entity
@Table(name = "hh_parcel")
public class HhParcel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hh_ref_code")
    private String refCode;

    @Column(name = "hh_weight")
    private Integer weight;

    @Column(name = "hh_label")
    private String label;

    @Column(name = "hh_consignor_id")
    private Long consignorId;

    protected HhParcel() {
        // JPA
    }

    public HhParcel(String refCode, Integer weight, String label, Long consignorId) {
        this.refCode = refCode;
        this.weight = weight;
        this.label = label;
        this.consignorId = consignorId;
    }

    public Long getId() {
        return id;
    }

    public String getRefCode() {
        return refCode;
    }
}
