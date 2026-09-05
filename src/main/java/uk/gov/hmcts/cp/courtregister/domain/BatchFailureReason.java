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
    ASSEMBLY_FAILED
}
