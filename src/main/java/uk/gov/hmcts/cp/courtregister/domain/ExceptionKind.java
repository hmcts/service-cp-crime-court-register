package uk.gov.hmcts.cp.courtregister.domain;

/**
 * The five things the exception report says can be wrong, and the one it says was put right.
 *
 * <p>Closed, and the {@code kind} field of every event, the {@code kind} column of the CSV and the
 * {@code kind} label of {@code courtregister_exceptions_reported_total}. A sixth kind is a spec
 * change and not an addition: the report's whole claim is that these five are what can be wrong.
 *
 * <p>The two intake kinds are disjoint by construction - FAILED is terminal and RECEIVED and
 * RETRYING are not - which is what makes a request that was late and has since failed appear once,
 * as {@link #REQUEST_FAILED}.
 */
public enum ExceptionKind {

    /** A request that reached FAILED inside the window; the intake half's parked work. */
    REQUEST_FAILED,

    /** A request still RECEIVED or RETRYING past the intake threshold, whenever it arrived. */
    REQUEST_LATE,

    /** A batch past a stage limit, or a recorded register the last scheduled run left unbatched. */
    BATCH_LATE,

    /** A batch that reached its own terminal failure inside the window. */
    BATCH_FAILED,

    /** A recipient's e-mail that was refused or never answered, inside the window. */
    NOTIFICATION_FAILED,

    /**
     * A batch a run gave up on and gave the registers back from, inside the window.
     *
     * <p>T036 makes it informational and derives it from the reason. Landed at T035 as the seam
     * its cases are written against.
     */
    BATCH_RELEASED;

    /**
     * Whether a run that did not report this kind would be asked about it again.
     *
     * <p>The two late kinds are read against a cut-off rather than a window, so whatever is still
     * late at the next run is read by it: an entry left out of one morning's report is in the next
     * morning's, older. The three failure kinds are read over a half-open window aligned to the
     * schedule, so each row falls in exactly one run's window and no run reads that window again -
     * a failure one report leaves out is a failure no report ever states.
     *
     * <p>That difference is what the entry cap is allowed to bound, and it is a switch expression
     * so that a sixth kind cannot be added without deciding which of the two it is.
     *
     * @return whether the next run would find it again
     */
    public boolean recursEveryRun() {
        return switch (this) {
            case REQUEST_LATE, BATCH_LATE -> true;
            case REQUEST_FAILED, BATCH_FAILED, NOTIFICATION_FAILED, BATCH_RELEASED -> false;
        };
    }
}
