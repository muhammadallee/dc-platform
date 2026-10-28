package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.CorrelationIdConverter;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MoneyConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Version;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Oracle test entity mapped onto {@code V1__oracle_widget.sql}: sequence key (allocation size equal
 * to the sequence's {@code INCREMENT BY}), a {@code boolean} on {@code NUMBER(1)}, an {@code Instant},
 * the platform converters, auditing and an optimistic-lock version. Field access.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
class OracleWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "oracle_widget_seq")
    @SequenceGenerator(name = "oracle_widget_seq", sequenceName = "oracle_widget_seq", allocationSize = 50)
    Long id;

    String referenceCode;

    boolean active;

    Instant happenedAt;

    @Convert(converter = MoneyConverter.class)
    Money price;

    @Convert(converter = CorrelationIdConverter.class)
    CorrelationId correlationId;

    @Version
    long version;

    @CreatedDate
    Instant createdAt;

    @CreatedBy
    String createdBy;

    @LastModifiedDate
    Instant lastModifiedAt;

    @LastModifiedBy
    String lastModifiedBy;
}
