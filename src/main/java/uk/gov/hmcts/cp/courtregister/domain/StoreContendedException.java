package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Thrown when this service's own store answered, but every attempt at a write lost the same race.
 *
 * <p>The store <em>answering</em>, like {@link StoreRefusedRowException} and unlike
 * {@link StoreUnavailableException}: the connection plainly worked, and what refused the write was
 * another writer holding the key rather than a rule the write can never meet. What separates it
 * from a refusal is that it was already retried - the statement was made again on a fresh snapshot,
 * as many times as the store is willing to try - and the same writer won each time. Nothing is
 * retried on its account above here; the caller decides what a contended write means for the unit
 * of work it belongs to.
 *
 * <p><strong>Why it is a domain signal and not the {@code org.springframework.dao} type that
 * discovered it.</strong> The caller that has to decide is the nightly run's stale-batch pass, which
 * lives in {@code batch/} and may name no {@code org.springframework.dao} type at all (constitution
 * Principle V). A contended write it could not name by type is one it could only catch as
 * {@code RuntimeException}, which is the catch that also swallows every programming error beside
 * it. The persistence layer therefore translates the exhaustion at the boundary of the package that
 * owns the datasource, and the layers above key off this alone.
 *
 * <p><strong>The message is this repository's own words and the driver's are deliberately
 * dropped.</strong> A unique violation arrives from Postgres quoting the colliding key's values,
 * and on this service's tables those values include a hearing identity and a court centre - detail
 * about a document whose every defendant is a child (constitution Principle VII). The cause is
 * attached only where it carries no row: for the stale-batch pass it is not attached, for the same
 * reason {@link StoreRefusedRowException} attaches none.
 */
public class StoreContendedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure around a write every attempt lost.
     *
     * @param reason a bounded phrase naming the statement and the race it kept losing - never the
     *               driver's words, and never a value from the row
     */
    public StoreContendedException(final String reason) {
        super(reason);
    }
}
