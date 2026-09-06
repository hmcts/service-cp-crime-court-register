package uk.gov.hmcts.cp.courtregister.domain;

/**
 * The bounded reasons a register batch ends FAILED.
 *
 * <p>One of these - never systemdocgenerator's own words and never an exception message - is what
 * reaches {@code register_batch.failure_reason}, the batches counter's outcome label and the run
 * report. The renderer's {@code reason} is kept separately in {@code sdg_reason}, where it is
 * available to support and is never logged at INFO, because it is another system's text about a
 * document whose every defendant is a child (constitution Principle VII).
 *
 * <p>The six are six different investigations. Two of them say the batch never left this service,
 * two say the renderer refused to start, and two say it started and did not finish - and only the
 * first pair leaves the batch's rows RECORDED for the next run to re-assemble.
 */
public enum BatchFailureReason {

    /** The file service could not be written to, so no payload exists to render. */
    PAYLOAD_STORE_UNAVAILABLE,

    /** The render request could not be delivered within the run deadline. */
    RENDER_REQUEST_FAILED,

    /** The render request was answered with something other than the contract's 202. */
    RENDER_REQUEST_REJECTED,

    /** systemdocgenerator said the generation failed, by event or by query. */
    GENERATION_FAILED,

    /** The grace period passed and systemdocgenerator still had no verdict. */
    GENERATION_TIMED_OUT,

    /** The batch could not be assembled into a payload at all (defect fix P5). */
    ASSEMBLY_FAILED;

    /**
     * Whether this ending was reported by a completion mechanism outside this service.
     *
     * <p>The two that were: {@link #GENERATION_FAILED} is systemdocgenerator's own verdict about
     * the render, and {@link #GENERATION_TIMED_OUT} is the reconciler's verdict about the renderer's
     * silence. Each of them arrived because something went and learned it, so each names the
     * mechanism that did - which is what {@code register_batch.completed_by} holds and what the
     * {@code reconciled} metric counts.
     *
     * <p>The other four are this service's own verdict about a render it could not ask for or could
     * not hear about, and naming a mechanism on one of them would credit a decision nobody outside
     * this service made.
     *
     * <p>Stated here once, and asked here by everything that enforces it: {@code JdbcRegisterStore}
     * refuses a mark whose attribution disagrees with its reason, and
     * {@code register_batch_completed_by_shape_chk} enumerates the same two reasons for the writers
     * that do not go through the store.
     *
     * @return true where the ending carries a {@link CompletedBy}, and false where it must not
     */
    public boolean isGeneratorAttributed() {
        return this == GENERATION_FAILED || this == GENERATION_TIMED_OUT;
    }
}
