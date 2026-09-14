package uk.gov.hmcts.cp.courtregister.domain;

/**
 * The five things the exception report says can be wrong.
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
    NOTIFICATION_FAILED
}
