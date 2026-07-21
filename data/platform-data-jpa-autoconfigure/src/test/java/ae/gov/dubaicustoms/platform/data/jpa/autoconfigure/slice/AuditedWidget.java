package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.CorrelationIdConverter;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MoneyConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Slice-test entity exercising auditing, the Money/CorrelationId converters, and snake_case naming
 * ({@code referenceCode} -> {@code reference_code}, {@code correlationId} -> {@code correlation_id}).
 * Field access; package-private fields read directly by the test in the same package.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
class AuditedWidget {

    @Id
    @GeneratedValue
    Long id;

    String referenceCode;

    @Convert(converter = MoneyConverter.class)
    Money price;

    @Convert(converter = CorrelationIdConverter.class)
    CorrelationId correlationId;

    @CreatedDate
    Instant createdAt;

    @CreatedBy
    String createdBy;

    @LastModifiedDate
    Instant lastModifiedAt;

    @LastModifiedBy
    String lastModifiedBy;
}
