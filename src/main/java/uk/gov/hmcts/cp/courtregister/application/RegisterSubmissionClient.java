package uk.gov.hmcts.cp.courtregister.application;

import java.util.Map;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * Where an assembled register is sent.
 *
 * <p>The last of the four ports the core owns: one POST of the progression context's
 * {@code add-court-register} command per hearing. Nothing here names HTTP, a retry policy or a
 * status code — the adapter behind it owns all three, and it is the adapter that decides whether a
 * failure is worth another delivery.
 *
 * <p><strong>Success is {@code 202 Accepted} and nothing else.</strong> Any other answer, 2xx
 * included, is a {@link SubmissionFailedException}: a 2xx that is not 202 means something other than
 * the command endpoint replied, which is a different investigation from a refusal and is never
 * treated as a delivered register.
 */
public interface RegisterSubmissionClient {

    /**
     * Submits one register to progression.
     *
     * <p>The anomaly counts travel with the document because they are written in the same breath as
     * it: the adapter behind this port claims the {@code processed_output} row before the POST, and
     * that row carries both the digest of exactly the bytes it is about to send and the bounded
     * {@code anomaly_summary} of what the register was assembled without (fixes C19, C20 and C27).
     * Both are worth more after a failure than after a success — what was attempted, and what was
     * skipped to attempt it — so neither is left until the answer comes back.
     *
     * @param document  the assembled {@code add-court-register} command
     * @param caller    the identity the POST is made as
     * @param anomalies how many of each guarded skip this register survived; empty where none
     * @return what progression answered, which is {@code 202} or nothing
     * @throws SubmissionFailedException if progression answered anything else, or did not answer;
     *     the exception carries whether another delivery could change that
     */
    SubmissionReceipt submit(
            CourtRegisterDocument document,
            CallerIdentity caller,
            Map<TransformationAnomaly, Integer> anomalies)
            throws SubmissionFailedException;
}
