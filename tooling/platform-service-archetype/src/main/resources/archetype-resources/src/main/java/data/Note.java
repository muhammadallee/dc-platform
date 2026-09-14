#set( $featureSet = ",${features}," )
#if($featureSet.contains(",data,"))
package ${package}.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Feature sample (features=data): a JPA entity persisted with the platform's conventions (snake_case
 * naming, auditing, {@code open-in-view=false}). Its table comes from the Flyway migration
 * {@code db/migration/V1__create_note.sql} — change the schema with a new migration, never with
 * Hibernate DDL. H2 backs it in local runs and tests; production supplies {@code spring.datasource.*}.
 */
@Entity
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String text;

    protected Note() {
        // JPA
    }

    public Note(String text) {
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
#end
