package uk.gov.hmcts.cp.courtregister.adapter.progression;

import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmission;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.courtregister.application.SubmissionReceipt;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedOutputRepository;

/**
 * The submission port, wired to progression's command API and to the processed log.
 *
 * <p>The order matters more than anything else in this class, because {@code add-court-register} is
 * not idempotent — every POST appends a {@code CourtRegisterRecorded} event and a
 * {@code court_register_request} row:
 *
 * <ol>
 *   <li><strong>Claim the row, before anything is sent.</strong> The same statement asks two
 *       questions at once: may this delivery send, and record that it is about to. A claim the
 *       database refuses means either that this hearing's register is already POSTED — so a replay
 *       skips it — or that this runner's claim was reclaimed while it worked, in which case it has
 *       no request to speak for.</li>
 *   <li><strong>POST.</strong> The transport's retry policy applies; what comes back out is either
 *       the accepted status or a classified failure.</li>
 *   <li><strong>Record the outcome.</strong> A failure is written down <em>before</em> it is
 *       rethrown, so the log never shows a submission in flight that nothing is going to finish, and
 *       the status progression answered is recorded with it.</li>
 * </ol>
 *
 * <p>Writing the row first is what makes an unknown outcome survivable. A POST that times out leaves
 * a PENDING row carrying the digest of exactly the bytes that were attempted and the bounded counts
 * of what the register was assembled without, so the next delivery re-sends and reconciliation can
 * tell whether the body changed. Writing it afterwards would lose precisely the case the evidence
 * exists for.
 *
 * <p><strong>This is where defect fix C1 becomes durable.</strong>
 * {@code ProcessOutboundCourtRegister/index.js:17-25} swallows the POST's errors and records
 * nothing at all, so a lost register and a delivered one are the same run. Here the status reaches
 * {@code processed_output.response_code} on both paths, and the failure continues upward carrying
 * the classification the pipeline settles the delivery on.
 *
 * <p><strong>The guarantee, stated honestly.</strong> At-most-once submission in normal operation,
 * redeliveries and replays included; across a crash in the instant between an accepted POST and the
 * row being marked POSTED, at-least-once. The duplicate is absorbed downstream by progression's
 * {@code max(register_time) per hearing_id} sweep, exactly like a re-share. Strict at-most-once
 * needs progression-side idempotency, which the frozen contract does not offer, and nothing here
 * claims otherwise.
 *
 * <p>The document is never logged, at any level. It is a register naming children and their
 * addresses; identifiers and counts are all a log line carries.
 */
// PMD.UnusedPrivateField: the three collaborators are the seam T062's suite constructs the client
// with, and they are read by the body T067 writes. The suppression comes off with that body.
@SuppressWarnings("PMD.UnusedPrivateField")
public class ProgressionRegisterSubmissionClient implements RegisterSubmissionClient {

    private final ProcessedOutputRepository outputs;
    private final ProgressionCommandGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * Creates the adapter over the processed log and the progression transport.
     *
     * @param outputs      the {@code processed_output} statements, each fenced on the run's claim
     * @param gateway      the {@code add-court-register} transport, with its retry policy
     * @param objectMapper the shared mapper, so what is sent is serialised exactly as everything else
     */
    public ProgressionRegisterSubmissionClient(
            final ProcessedOutputRepository outputs,
            final ProgressionCommandGateway gateway,
            final ObjectMapper objectMapper) {
        this.outputs = outputs;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public SubmissionReceipt submit(final RegisterSubmission submission) {
        throw new UnsupportedOperationException("T067 submits the register to progression");
    }
}
