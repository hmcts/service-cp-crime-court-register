package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Thrown when the store refused a stale batch's release for a reason the operation cannot account
 * for.
 *
 * <p>The stale-batch pass settles exactly one refusal itself - the race for the day's
 * active-register key, which it makes its statement against again on a fresh snapshot and, where
 * every attempt meets it, reports as a contended batch rather than throwing. Any other unique key
 * the release meets is a rule nobody wrote this statement against: the same row meets the same rule
 * on every attempt and on every run, so no snapshot and no retry changes it, and it is raised
 * rather than reported. It is a fault in the schema or in this statement, not a race, and it is
 * allowed to end the run the way any programming error is - what FR-003a forbids is one batch's
 * <em>ordinary</em> ending taking the other court centres' releases with it.
 *
 * <p><strong>Why the store's own class does not travel.</strong> The pass that calls the port lives
 * in {@code batch/}, which may name no {@code org.springframework.dao} type (constitution Principle
 * V), so a refusal that escaped untranslated could only be caught there as {@code RuntimeException}
 * - the catch that swallows every programming error beside it. This is the same translation
 * {@link StoreUnavailableException} and {@link StoreRefusedRowException} make at the same boundary.
 *
 * <p><strong>It is not a store outage.</strong> {@link StoreUnavailableException} says the store
 * could not be reached; this says the store was reached and declined to hold this row.
 *
 * <p>The message names the statement and the batch's identity and nothing from a register: the
 * constraint travels on the cause, as it does for {@link RegisterNotRecordedException}, which is
 * the same refusal met by the write on the same table. Neither carries a defendant, a name or an
 * address (constitution Principle VII).
 */
public class RegisterNotReleasedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param detail a bounded description of the refusal; the statement, never a register
     * @param cause  the store's own refusal
     */
    public RegisterNotReleasedException(final String detail, final Throwable cause) {
        super(detail, cause);
    }
}
