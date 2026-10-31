package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Identity-keyed Oracle test entity ({@code V2__oracle_note_and_part.sql}). Field access. */
@Entity
class OracleNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String text;
}
