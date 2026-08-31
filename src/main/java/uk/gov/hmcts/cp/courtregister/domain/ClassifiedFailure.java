package uk.gov.hmcts.cp.courtregister.domain;

/**
 * A failure that already knows how the delivery carrying it should be settled.
 *
 * <p>The pipeline asks two questions of every failure — is it worth retrying, and is there a retry
 * left — and only the second is its own. The first belongs to the layer that met the failure: a
 * cache and fallback that both missed may have a payload next time; a document progression refused
 * as malformed will be refused identically for ever. This interface is that answer, carried on the
 * exception, so the core branches on a classification the throw site chose rather than on the
 * exception's Java type.
 *
 * <p>Every implementation fixes its classification in its own constructor except
 * {@link SubmissionFailedException}, which is the one place where the same call can fail both ways
 * and so takes it from the caller.
 *
 * <p><strong>The reason is a bounded code</strong>, never free text and never a fragment of a
 * payload: it travels into {@code processed_request.failure_reason}, a dead-letter description and
 * the estate's log index (constitution Principle VII).
 */
public interface ClassifiedFailure {

    /**
     * Whether another delivery could change the answer.
     *
     * @return the classification the throw site chose
     */
    FailureClassification classification();

    /**
     * The bounded reason recorded for this failure.
     *
     * @return the reason code
     */
    ReasonCode reason();
}
