#set( $featureSet = ",${features}," )
#if($featureSet.contains(",data,"))
package ${package}.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.test.junit.PlatformTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The data sample against the real local provider (H2) with the schema created by the shipped Flyway
 * migration — not by Hibernate DDL and not by a mocked repository. Each step runs in its own
 * transaction, so every read goes back to the database instead of a persistence-context cache.
 */
@PlatformTest
class NoteRepositoryTest {

    @Autowired
    NoteRepository notes;

    @Autowired
    Flyway flyway;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void schemaComesFromTheFlywayMigration() {
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getScript)
                .contains("V1__create_note.sql");
    }

    @Test
    void createReadUpdateDeleteAcrossSeparateTransactions() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long id = tx.execute(status -> notes.save(new Note("first draft")).getId());
        assertThat(id).isNotNull();
        String created = tx.execute(status -> notes.findById(id).orElseThrow().getText());
        assertThat(created).isEqualTo("first draft");

        tx.executeWithoutResult(status -> notes.findById(id).orElseThrow().setText("revised"));
        String updated = tx.execute(status -> notes.findById(id).orElseThrow().getText());
        assertThat(updated).isEqualTo("revised");

        tx.executeWithoutResult(status -> notes.deleteById(id));
        Boolean stillThere = tx.execute(status -> notes.existsById(id));
        assertThat(stillThere).isFalse();
    }

    @Test
    void rolledBackTransactionPersistsNothing() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long before = notes.count();

        tx.executeWithoutResult(status -> {
            notes.save(new Note("discarded"));
            status.setRollbackOnly();
        });

        assertThat(notes.count()).isEqualTo(before);
    }

    @Test
    void notNullConstraintIsEnforced() {
        assertThatThrownBy(() -> notes.saveAndFlush(new Note(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
#end
