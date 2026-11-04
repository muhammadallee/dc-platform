package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

/**
 * 20000 generated rows with no table behind them, so a JPQL cross join of this entity is pure SQL-engine
 * work (no PL/SQL in the call stack) for the timeout tests. Read-only; not part of schema validation.
 */
@Entity
@Immutable
@Subselect("select level l from dual connect by level <= 20000")
class OracleGeneratedRow {

    @Id
    Long l;
}
