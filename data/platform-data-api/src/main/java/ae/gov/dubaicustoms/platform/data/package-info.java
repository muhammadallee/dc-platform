/**
 * The tech-neutral data capability contract: {@link ae.gov.dubaicustoms.platform.data.Money} is the
 * shared monetary value type, {@link ae.gov.dubaicustoms.platform.data.EntityId} wraps raw
 * identifiers for type safety, and {@link ae.gov.dubaicustoms.platform.data.PersistenceConventions}
 * pins the column lengths, audit column names, and naming rules every entity should follow.
 *
 * <p>Nothing here references JPA, Hibernate, or any datastore type, so domain code can use these
 * values without dragging in a persistence provider; {@code platform-data-jpa-autoconfigure}
 * supplies the JPA wiring (auditing, naming strategy, and the {@code Money} attribute converter
 * built on {@link ae.gov.dubaicustoms.platform.data.Money#toStorageString()} /
 * {@link ae.gov.dubaicustoms.platform.data.Money#parse(String)}).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.data;
