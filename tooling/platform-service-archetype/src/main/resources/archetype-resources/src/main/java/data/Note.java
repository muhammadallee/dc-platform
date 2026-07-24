#if($features.contains("data"))
package ${package}.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Feature sample (features=data): a JPA entity persisted with the platform's conventions (snake_case
 * naming, auditing, {@code open-in-view=false}). H2 backs it in dev/test; point a real datasource at
 * it in prod via {@code spring.datasource.*}.
 */
@Entity
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
}
#end
