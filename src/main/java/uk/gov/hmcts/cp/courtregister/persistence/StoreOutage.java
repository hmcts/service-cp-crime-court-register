package uk.gov.hmcts.cp.courtregister.persistence;

import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * Where "the store went away" stops being a JDBC fact and becomes a domain one.
 *
 * <p>Every statement this package makes is made through here, so the three Spring classes a dead
 * store actually produces become a single {@link StoreUnavailableException} at the boundary of the
 * package that owns the datasource. Above it, the application core and the transport adapter read
 * that signal and nothing else, and the core imports no {@code org.springframework.dao} type at all
 * (constitution Principle V). It also makes the rule single: the same three classes used to be
 * listed in the pipeline and again in the listener, which is one rule about one store written twice
 * and a drift waiting to happen.
 *
 * <p><strong>Spring's own transient/non-transient split is the wrong knife here</strong>, which is
 * why the list is named rather than taken from a supertype. The exception a dead store produces -
 * {@link DataAccessResourceFailureException}, connection acquisition included - sits on the
 * non-transient side, while a constraint violation or a broken statement is the store
 * <em>answering</em>, over a connection that plainly worked. Only the store-went-away classes may
 * stop the queue.
 *
 * <p><strong>Contention is caught first and let out unchanged, and the order is the
 * behaviour.</strong> {@link ConcurrencyFailureException} extends
 * {@link TransientDataAccessException}, so without the branch above them a deadlock or a lost race
 * for a key would be read as an outage and would stop intake - and a deadlock is the opposite of an
 * outage: it is the store answering, and it clears itself on the next delivery. Suspending the whole
 * queue for one contended row would stall every message behind it.
 *
 * <p>The phrase each call site passes is written here in this repository and names the statement,
 * never the driver's message and never a parameter: it reaches an ERROR line about a flow whose
 * every defendant is a child (constitution Principle VII). The cause is attached, so the stack trace
 * still says which statement failed.
 */
final class StoreOutage {

    private StoreOutage() {
        // Static translation only.
    }

    /**
     * Makes a statement that answers, turning a store that went away into the domain's signal.
     *
     * @param statement a bounded phrase naming what was being done, for the failure's message
     * @param call      the statement
     * @param <T>       what the statement answers with
     * @return the statement's answer
     * @throws StoreUnavailableException if the store could not be reached at all
     */
    /* default */ static <T> T translating(final String statement, final Supplier<T> call) {
        try {
            return call.get();
        } catch (ConcurrencyFailureException contention) {
            // The store answering, not the store going away: two writers met on one row and this
            // one lost. Handed on unchanged, so it is settled as the ordinary transient failure it
            // is rather than stopping the queue.
            throw contention;
        } catch (TransientDataAccessException | RecoverableDataAccessException
                | DataAccessResourceFailureException gone) {
            throw new StoreUnavailableException("the store could not be reached to " + statement,
                    gone);
        }
    }

    /**
     * The same, for a statement whose answer nobody reads.
     *
     * <p>A second name rather than an overload: a lambda over a method that returns a value is
     * compatible with both a {@code Supplier} and a {@code Runnable}, so an overloaded pair would be
     * ambiguous at exactly the call sites this package is made of.
     *
     * @param statement a bounded phrase naming what was being done, for the failure's message
     * @param call      the statement
     * @throws StoreUnavailableException if the store could not be reached at all
     */
    /* default */ static void translatingUpdate(final String statement, final Runnable call) {
        translating(statement, () -> {
            call.run();
            return null;
        });
    }
}
