package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Thrown when the now-subscriptions a register is addressed with cannot be obtained.
 *
 * <p>Transient by construction, for the same reason a payload failure is: reference data that
 * cannot be read now is a network, a gateway or a context-side problem, and every one of those is
 * worth a redelivery.
 *
 * <p><strong>It exists because the legacy cannot tell "nobody subscribes" from "nobody
 * answered".</strong> {@code CourtRegisterSubscriptions/index.js:20-24} tests
 * {@code if (!subscriptionsMetaData || !subscriptionsMetaData.nowSubscriptions)} and returns the
 * fragment unchanged either way, so a reference-data outage completes the run with no recipients and
 * reports it as a success — and {@code no-subscriptions}, which is one of this flow's two commonest
 * legitimate outcomes, is exactly what it looks like. An empty subscription set is an answer and
 * completes the run; no answer at all is this exception.
 *
 * <p>A bounded {@link ReasonCode} and nothing from the layer beneath: the code travels into
 * {@code failure_reason}, a dead-letter description and the log index.
 */
public class ReferenceDataUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param detail a bounded description of what could not be read; never response content
     */
    public ReferenceDataUnavailableException(final String detail) {
        super(detail);
    }

    /**
     * Always {@link FailureClassification#TRANSIENT} — see the class comment.
     *
     * @return the fixed transient classification
     */
    public FailureClassification classification() {
        return FailureClassification.TRANSIENT;
    }

    /**
     * The bounded code recorded for this failure.
     *
     * @return the bounded reason code
     */
    public ReasonCode reason() {
        return ReasonCode.REFERENCE_DATA_UNAVAILABLE;
    }
}
