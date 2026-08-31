package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Thrown when the hearing payload for a request cannot be obtained.
 *
 * <p>Transient by construction. There is no non-transient payload-fetch case: a payload that cannot
 * be read now is a cache, a network or a query-side problem, and every one of those is worth a
 * redelivery. The classification is therefore fixed rather than supplied, so no caller can raise a
 * payload failure that quietly parks a recoverable request. A cache miss <em>and</em> a fallback
 * miss is one of those failures rather than a silent stop — defect fix C32.
 *
 * <p>Like every other failure this service reports, it carries a bounded {@link ReasonCode} and
 * never a raw message from the layer beneath it — the code travels into {@code failure_reason}, a
 * dead-letter description and the log index.
 */
public class PayloadUnavailableException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;

    /**
     * Creates the failure carrying the reason the payload could not be fetched.
     *
     * @param reason the bounded reason recorded for this failure
     */
    public PayloadUnavailableException(final ReasonCode reason) {
        super(reason.code());
        this.reasonCode = reason;
    }

    /**
     * Always {@link FailureClassification#TRANSIENT} — see the class comment.
     *
     * @return the fixed transient classification
     */
    @Override
    public FailureClassification classification() {
        return FailureClassification.TRANSIENT;
    }

    /**
     * The bounded code recorded for this failure.
     *
     * @return the bounded reason code
     */
    @Override
    public ReasonCode reason() {
        return reasonCode;
    }
}
