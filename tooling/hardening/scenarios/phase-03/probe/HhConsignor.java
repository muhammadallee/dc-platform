package ae.gov.dubaicustoms.hardening.probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** The parcels' consignor, with a lazy collection: what AC18 logs without loading. */
@Entity
@Table(name = "hh_consignor")
public class HhConsignor {

    @Id
    private Long id;

    @Column(name = "hh_name")
    private String name;

    @OneToMany
    @JoinColumn(name = "hh_consignor_id", insertable = false, updatable = false)
    private List<HhParcel> parcels = new ArrayList<>();

    protected HhConsignor() {
        // JPA
    }

    public String getName() {
        return name;
    }

    public List<HhParcel> getParcels() {
        return parcels;
    }
}
