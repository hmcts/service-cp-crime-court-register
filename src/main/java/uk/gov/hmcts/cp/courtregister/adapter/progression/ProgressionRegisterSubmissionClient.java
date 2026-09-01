package uk.gov.hmcts.cp.courtregister.adapter.progression;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmission;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.courtregister.application.SubmissionReceipt;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
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
public class ProgressionRegisterSubmissionClient implements RegisterSubmissionClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(ProgressionRegisterSubmissionClient.class);

    private static final String DIGEST_ALGORITHM = "SHA-256";

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
        final byte[] body = objectMapper.writeValueAsBytes(submission.document());
        final RunClaim claim = submission.claim();

        // One statement, two questions: may this delivery send, and record that it is about to. The
        // row carries the digest of exactly these bytes and the counts of what the register was
        // assembled without, because both are worth more after a failure than after a success.
        final boolean maySend = outputs.claimPending(claim, rowFor(submission, digestOf(body)));

        final SubmissionReceipt receipt;
        if (maySend) {
            receipt = new SubmissionReceipt(sent(body, submission, claim), true);
        } else {
            // Either this hearing's register is already POSTED — a replay, and the run still
            // completes `submitted` — or this runner's claim was reclaimed while it worked, and it
            // has no request to speak for. Neither may send.
            //
            // The receipt carries no status, because nothing answered this delivery;
            // `sentByThisDelivery` is what says so, and a 202 here would have the run log a call it
            // never made.
            LOG.info("The register for this request has already gone, or the claim behind it was "
                            + "reclaimed; nothing is posted. source={} requestId={}",
                    claim.source(), claim.requestId());
            receipt = new SubmissionReceipt(0, false);
        }
        return receipt;
    }

    /**
     * The POST, and the outcome write that follows it whichever way it goes.
     *
     * @return the status progression accepted the command with
     */
    private int sent(final byte[] body, final RegisterSubmission submission, final RunClaim claim) {
        final int responseCode;
        try {
            responseCode = gateway.post(body, submission.caller());
        } catch (SubmissionFailedException failure) {
            // Caught to record, never to absorb — the whole of defect fix C1. The row is moved to
            // FAILED carrying whatever progression answered, and the same exception continues with
            // the classification the pipeline settles the delivery on.
            recorded(outputs.recordFailed(claim, answered(failure)), "FAILED", claim);
            throw failure;
        }

        recorded(outputs.recordPosted(claim, responseCode), "POSTED", claim);
        LOG.info("The court register was submitted to progression. source={} requestId={} "
                        + "responseCode={} anomalies={}",
                claim.source(), claim.requestId(), responseCode, submission.anomalies().size());
        return responseCode;
    }

    /**
     * The status to record against a failure, or {@code null} where nothing answered.
     *
     * <p>A PENDING-turned-FAILED row with no {@code response_code} is exactly the state that warns a
     * duplicate is possible; an invented status would say an attempt was answered when none was.
     */
    private static Integer answered(final SubmissionFailedException failure) {
        return failure.responseCode().isPresent() ? failure.responseCode().getAsInt() : null;
    }

    /**
     * The row written before anything is sent.
     *
     * <p>Three of its columns are not recoverable from the frozen {@code add-court-register} body,
     * which is why the submission carries them: the court centre's OU code appears there only inside
     * the file name, and the register day would have to be re-derived — a second derivation of the
     * day defect fix C12 exists to make single.
     */
    private static ProcessedOutputClaim rowFor(
            final RegisterSubmission submission, final String digest) {
        final CourtRegisterDocument document = submission.document();
        return new ProcessedOutputClaim(
                UUID.randomUUID(),
                UUID.fromString(document.courtCentreId()),
                submission.courtCentreOuCode(),
                submission.registerDay(),
                document.fileName(),
                digest,
                submission.anomalies());
    }

    /**
     * The outcome write is the durable half of a submission, so it is checked rather than assumed.
     *
     * <p>A claim was granted moments before, so a statement that affects no row means an overlapping
     * delivery reached it first and POSTED it, or that this runner's claim was reclaimed while it
     * worked. Either way two runners were working the same request, and what this one believes
     * happened is not what the log durably says.
     */
    private static void recorded(
            final boolean written, final String intended, final RunClaim claim) {
        if (!written) {
            LOG.error("Outcome write affected no row; an overlapping delivery reached it first, or "
                            + "the claim was reclaimed. source={} requestId={} intendedStatus={}",
                    claim.source(), claim.requestId(), intended);
        }
    }

    private static String digestOf(final byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(DIGEST_ALGORITHM).digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen on a running JVM; if
            // it ever did, no submission could be recorded safely and failing loudly is the only
            // honest response.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }
}
