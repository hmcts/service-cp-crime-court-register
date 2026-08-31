package uk.gov.hmcts.cp.courtregister.application;

import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;

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
     * @param document the assembled {@code add-court-register} command
     * @param caller   the identity the POST is made as
     * @return what progression answered, which is {@code 202} or nothing
     * @throws SubmissionFailedException if progression answered anything else, or did not answer;
     *     the exception carries whether another delivery could change that
     */
    SubmissionReceipt submit(CourtRegisterDocument document, CallerIdentity caller)
            throws SubmissionFailedException;
}
