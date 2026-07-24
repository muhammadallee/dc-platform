#if($features.contains("data"))
package ${package}.data;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Feature sample (features=data): a Spring Data repository over {@link Note}. Use platform JPA
 * conventions; do not hand-roll DAOs or manage transactions/EntityManagers directly.
 */
public interface NoteRepository extends JpaRepository<Note, Long> {
}
#end
