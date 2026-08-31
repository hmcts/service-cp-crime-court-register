package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Thrown when the {@code add-court-register} POST did not succeed.
 *
 * <p>The one failure type whose classification is <em>supplied</em> rather than fixed, because only
 * the throw site knows: a connect failure, a 5xx, a 429 and a 408 are worth another delivery, and a
 * 4xx refusal of the same bytes is not. The pipeline branches on the classification and never on the
 * type, which is what lets one exception carry both answers.
 *
 * <p>This type is defect fix C1 in one line. {@code ProcessOutboundCourtRegister/index.js:17-25}
 * catches everything the POST can raise, logs it, and never inspects the response status at all — so
 * a failed submission and a delivered register are indistinguishable, which is the silent-loss mode
 * this service was commissioned to end. Here a submission that did not end in {@code 202} throws,
 * and the run is recorded as RETRYING or FAILED accordingly.
 *
 * <p>A bounded {@link ReasonCode} and never progression's own words: the code reaches
 * {@code failure_reason}, a dead-letter description and the log index.
 */
public class SubmissionFailedException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final ReasonCode reasonCode;

    /**
     * Creates the failure.
     *
     * @param classification whether another delivery could change the answer
     * @param reason         the bounded reason recorded for this failure
     */
    public SubmissionFailedException(
            final FailureClassification classification, final ReasonCode reason) {
        super(reason.code());
        this.failureClassification = classification;
        this.reasonCode = reason;
    }

    /**
     * Whether another delivery could change the answer.
     *
     * @return the classification the throw site gave
     */
    @Override
    public FailureClassification classification() {
        return failureClassification;
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
