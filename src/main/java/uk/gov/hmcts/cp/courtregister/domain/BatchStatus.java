package uk.gov.hmcts.cp.courtregister.domain;

/**
 * The states one register batch moves through, from assembly to a terminal outcome.
 *
 * <p>The constant names are the values written to {@code register_batch.status} and enumerated by
 * the V2 check constraint, so a rename here is a schema change rather than a refactor.
 *
 * <p>Four of the seven are terminal, and they are terminal for four different reasons: everybody who
 * subscribes was told, somebody was not, there was nobody to tell, or the document never came. An
 * undifferentiated "finished" would collapse the three good endings into the bad one, which is the
 * shape the progression leg ends a batch in and the shape defect P1 is about.
 *
 * <p><strong>Seam.</strong> {@link #canTransitionTo(BatchStatus)} carries the state machine of
 * data-model.md and is completed by T013; {@code BatchStateTest} (T011) guards it.
 */
public enum BatchStatus {

    /** Assembled and stamped onto its rows; nothing has been asked of the renderer yet. */
    PENDING,

    /** The payload is in the file service and the render request was accepted with a 202. */
    GENERATING,

    /** The document exists, learned from the public event or from the reconciler's query. */
    GENERATED,

    /** Every recipient of the batch was accepted by notificationnotify. */
    NOTIFIED,

    /** The document went to some recipients and not to others; the rest are resendable. */
    PARTIALLY_NOTIFIED,

    /** The document was generated and the batch had no recipients at all (defect fix P1). */
    NOTIFIED_NOBODY,

    /** The batch ended without a document, under one bounded {@link BatchFailureReason}. */
    FAILED;

    /**
     * Whether this batch may move to the given state.
     *
     * <p>Asked before every write rather than after, so a transition the state machine does not
     * permit is refused where it is attempted and never inferred from a row that already changed.
     *
     * @param next the state a transition would move the batch to
     * @return whether the data-model state machine permits the move
     */
    public boolean canTransitionTo(final BatchStatus next) {
        throw new UnsupportedOperationException(
                "T013 completes the batch state machine; BatchStateTest (T011) guards it");
    }
}
