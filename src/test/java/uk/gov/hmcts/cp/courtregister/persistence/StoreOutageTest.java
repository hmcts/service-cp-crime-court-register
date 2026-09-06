package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * Which data-access failures mean "the store went away", asserted where the rule now lives.
 *
 * <p>The list itself is not new and neither is the behaviour it decides. It used to be written out
 * twice, in {@code DistributionPipeline} and again in {@code CourtRegisterMessageListener}, and
 * {@code DistributionPipelineTest} held the three classes to the core. The core may not name a JDBC
 * type at all (constitution Principle V), so the rule moved down to the package that owns the
 * datasource and the assertion moved with it; what the core and the transport are held to now is the
 * domain signal, in their own suites.
 *
 * <p><strong>Spring's transient/non-transient split is the wrong knife, which is why the list is
 * named.</strong> The exception a dead store actually produces -
 * {@link DataAccessResourceFailureException}, connection acquisition included - sits on the
 * non-transient side, while a constraint violation is the store <em>answering</em> over a connection
 * that plainly worked. Only the first kind may stop the queue: a suspension takes the whole pod's
 * intake down, so a rule that was one class too wide would turn a poison row into an outage.
 *
 * <p>The contention case is the one that has to be caught first and is asserted for it.
 * {@link ConcurrencyFailureException} extends {@code TransientDataAccessException}, so a list read
 * in the wrong order would call a deadlock an outage - and a deadlock is the opposite of one. It
 * clears itself on the next delivery.
 */
@DisplayName("StoreOutage")
class StoreOutageTest {

    /** Stands in for whatever the driver had to say, so a leak of it would be visible. */
    private static final String DRIVER_TEXT = "DRIVERTEXTMARKERZQX7";

    private static final String STATEMENT = "record a register";

    @Nested
    @DisplayName("a store that went away")
    class WentAway {

        @Test
        @DisplayName("every way it goes away becomes the domain's own signal")
        void every_outage_becomes_a_store_unavailable_exception() {
            for (final DataAccessException outage : everyWayTheStoreCanGoAway()) {
                assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                    throw outage;
                }))
                        .as("a %s reached the core as itself", outage.getClass().getSimpleName())
                        .isInstanceOf(StoreUnavailableException.class)
                        .hasCause(outage);
            }
        }

        @Test
        @DisplayName("the signal names the statement and none of the driver's words")
        void the_signal_names_the_statement_and_not_the_driver() {
            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw new DataAccessResourceFailureException(DRIVER_TEXT);
            }))
                    .hasMessageContaining(STATEMENT)
                    .as("the message reaches an ERROR line about a flow whose defendants are "
                            + "children, so it is this repository's words and not the driver's")
                    .hasMessageNotContaining(DRIVER_TEXT);
        }

        @Test
        @DisplayName("a statement whose answer nobody reads is translated the same way")
        void an_update_is_translated_the_same_way() {
            assertThatThrownBy(() -> StoreOutage.translatingUpdate(STATEMENT, () -> {
                throw new DataAccessResourceFailureException("connection refused");
            }))
                    .isInstanceOf(StoreUnavailableException.class);
        }

        /**
         * Every way the store can go away underneath a run.
         *
         * @return one outage of each kind
         */
        private List<DataAccessException> everyWayTheStoreCanGoAway() {
            return List.of(
                    new DataAccessResourceFailureException("the store cannot be reached"),
                    new RecoverableDataAccessException("the store dropped the connection"),
                    new TransientDataAccessResourceException("the store timed out"));
        }
    }

    @Nested
    @DisplayName("a store that answered")
    class Answered {

        /**
         * Caught above the outage classes and let out unchanged, which is the whole reason the order
         * of the branches is behaviour rather than style: contention is a subclass of one of them.
         */
        @Test
        @DisplayName("contention is the store answering, and is handed on as it came")
        void contention_is_handed_on_unchanged() {
            final ConcurrencyFailureException contention =
                    new CannotAcquireLockException("two writers met on one row");

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw contention;
            }))
                    .as("suspending intake for a fault that clears itself on the next delivery "
                            + "would stall every message behind it")
                    .isSameAs(contention);
        }

        @Test
        @DisplayName("a refused row is the store answering too")
        void a_constraint_violation_is_handed_on_unchanged() {
            final DataIntegrityViolationException refused =
                    new DuplicateKeyException("the key is already taken");

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw refused;
            }))
                    .isSameAs(refused);
        }

        @Test
        @DisplayName("a failure that is not the store's at all travels as it was thrown")
        void an_unrelated_failure_is_handed_on_unchanged() {
            final IllegalStateException unrelated = new IllegalStateException("a defect");

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw unrelated;
            }))
                    .isSameAs(unrelated);
        }

        @Test
        @DisplayName("a statement that worked answers with what it answered")
        void a_statement_that_worked_answers_normally() {
            assertThat(StoreOutage.translating(STATEMENT, () -> "recorded")).isEqualTo("recorded");
        }
    }
}
