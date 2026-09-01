package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.courtregister.pipeline.Dates;

/**
 * One request, from the guard admitting it to the guard recording what happened.
 *
 * <p>The application core: it names no broker, no database and no HTTP client, and it is the only
 * place that knows the order the ports are called in. The transport adapter above it decides how a
 * delivery is settled; this decides what the delivery is worth settling as.
 *
 * <p><strong>The five stages, in order</strong>: admit, fetch the hearing payload, ask whether the
 * group-proceedings flag suppresses the register, transform, submit — then record. The shape the
 * transport suites drive it through is unchanged: a request and the delivery it arrived on go in,
 * and exactly one {@link GuardDecision} comes out, so the listener has something to settle on every
 * path (constitution Principle VI). What the legacy orchestrator has instead is four silent guards
 * and a catch-all that reports failure from a completed orchestration, and no test file at all.
 *
 * <p>Every way a run can end well is written down and counted. A suppressed hearing ends
 * {@code COMPLETED, group-proceedings} where the legacy records nothing (the recorded half of defect
 * fix C7); a transformation that declines to produce a register says which of the three remaining
 * reasons it was, and the run ends {@code COMPLETED} under that reason; a register that was built
 * ends {@code submitted} and only after progression has accepted it. Four of the five completion
 * reasons send nothing and two of those four are this flow's ordinary results, so the reason is
 * written and counted rather than folded away (defect fixes C6 and C33): a court centre nobody
 * subscribes to and a pipeline that has quietly stopped working look identical from the outside
 * otherwise.
 *
 * <p><strong>The transformation port is not implemented yet</strong>, and a pipeline constructed
 * without one — the walking skeleton the transport suites use — ends every run it admits as
 * {@code no-defendants}, which is the outcome a payload with no register in it earns anyway. The
 * chain behind that port arrives with the mapper phase; nothing above it changes when it does.
 *
 * <p><strong>The run bounds itself.</strong> Before the ports are touched the deadline is fixed at
 * {@code courtregister.claim.processing-deadline} from now, and the run checks it before writing an
 * outcome. The deadline is strictly shorter than the claim lease, so a slow run stops itself while
 * its claim is still unambiguously its own; the alternative is a runner that discovers it has been
 * superseded only when its outcome write affects no rows — which is safe, but leaves the request
 * waiting for a redelivery it could have asked for a minute earlier. The check is against elapsed
 * local time only: nothing here compares a JVM reading with a stored timestamp, which is the
 * multi-node skew the data model's single-time-authority rule exists to rule out.
 *
 * <p><strong>A failure is read twice: is it worth retrying, and is there a retry left?</strong> The
 * first question is the ports' to answer, and they answer it in the exception. A non-transient
 * failure is recorded FAILED and parked immediately, whatever the delivery count says — handing one
 * back would spend the whole delivery budget re-reading a payload that reads the same every time and
 * park it at the end under {@code DELIVERY_LIMIT_EXHAUSTED}, a reason that tells support the service
 * ran out of tries rather than that the hearing was unusable.
 *
 * <p>Only a transient failure asks the second question. With deliveries remaining it is recorded
 * RETRYING and the delivery is handed back; on the final permitted delivery the same failure is
 * recorded FAILED, with the identity of the delivery that exhausted the budget, and the message is
 * parked. The transport adapter reads that fact from the delivery and carries it in, because the
 * processed log cannot know it — the budget belongs to the message, not to the request.
 *
 * <p>Payload unavailability is transient by construction — a cache miss <em>and</em> a fallback miss
 * is a reason to come back rather than a silent stop, which is defect fix C32 — and a deadline is not
 * a fault at all, so neither is ever parked for being unretryable. A failure nothing anticipated is
 * treated as transient, because "unknown" is not the same as "hopeless".
 */
public class DistributionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(DistributionPipeline.class);

    /** The field of the claim-check payload the hearing itself sits under. */
    private static final String HEARING = "hearing";

    private final IdempotencyGuard guard;
    private final HearingPayloadSource payloadSource;
    private final GroupProceedingsPolicy groupProceedings;
    private final NowSubscriptionsSource subscriptionsSource;
    private final Dates dates;
    private final RegisterTransformer transformer;
    private final RegisterSubmissionClient submissionClient;
    private final ProcessingMetrics metrics;
    private final Clock clock;
    private final Duration processingDeadline;

    /**
     * Creates the walking-skeleton pipeline: admit, fetch, record.
     *
     * <p>The transport suites run against this one. It has no transformation and no submission, so
     * every run that reaches the end of the fetch completes as {@code no-defendants} — which is the
     * outcome a payload with no register in it earns anyway, and is why the skeleton could be
     * settled honestly before the stages existed.
     *
     * @param guard              the {@code (source, requestId)} processed-log guard
     * @param payloadSource      where the hearing payload comes from
     * @param metrics            the instrument surface every outcome is counted on
     * @param clock              elapsed-time source for the run's own deadline; no claim decision is
     *                           made from it, so it cannot introduce multi-node skew
     * @param processingDeadline the enforced bound on a run, strictly shorter than the claim lease
     */
    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this(guard, payloadSource, null, null, null, null, null, metrics, clock,
                processingDeadline);
    }

    /**
     * Creates the pipeline over all four ports, the group-proceedings policy and the register's own
     * date handling.
     *
     * @param guard               the {@code (source, requestId)} processed-log guard
     * @param payloadSource       where the hearing payload comes from
     * @param groupProceedings    whether the hearing's flag suppresses its register
     * @param subscriptionsSource where the now-subscriptions a register is addressed with come from
     * @param dates               the register's date handling, for the day the subscriptions are
     *                            read on; pure, so holding it here costs the core no I/O
     * @param transformer         how a hearing payload and its subscriptions become a register
     * @param submissionClient    where an assembled register is sent
     * @param metrics             the instrument surface every outcome is counted on
     * @param clock               elapsed-time source for the run's own deadline
     * @param processingDeadline  the enforced bound on a run, strictly shorter than the claim lease
     */
    // Five ports, one policy, one date helper and two settings, every one of them owned by the core
    // and injected. Grouping them behind a holder would hide which stage a change touches.
    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final GroupProceedingsPolicy groupProceedings,
            final NowSubscriptionsSource subscriptionsSource,
            final Dates dates,
            final RegisterTransformer transformer,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this.guard = guard;
        this.payloadSource = payloadSource;
        this.groupProceedings = groupProceedings;
        this.subscriptionsSource = subscriptionsSource;
        this.dates = dates;
        this.transformer = transformer;
        this.submissionClient = submissionClient;
        this.metrics = metrics;
        this.clock = clock;
        this.processingDeadline = processingDeadline;
    }

    /**
     * Runs one request and reports what should happen to the delivery that carried it.
     *
     * @param command  the validated request
     * @param delivery who is running it, and whether the queue will deliver it again
     * @return the settlement the outcome calls for — a settlement, never a run
     */
    public GuardDecision process(
            final DistributionCommand command, final DeliveryIdentity delivery) {
        final GuardDecision admission = guard.admit(command, delivery);
        final GuardDecision decision;
        if (admission instanceof GuardDecision.Run admitted) {
            decision = runUnder(command, admitted.claim(), delivery.finalPermittedDelivery());
        } else {
            // Already completed, contested, or a collision: the guard has decided, and a run would
            // either duplicate work or overwrite a record that belongs to a different request.
            decision = admission;
        }
        return decision;
    }

    /**
     * The run itself, with every failure it can meet turned into an outcome.
     *
     * <p>The first catch takes every failure whose throw site already classified it — the four ports
     * answer "is this worth retrying" in the exception, and the pipeline never second-guesses them
     * by reading the Java type. The second is total on purpose: this frame holds the claim, and it
     * is the only frame that does. A failure that escaped it would leave {@code claim_owner} live
     * for the rest of the lease, so every redelivery would bounce off {@code CLAIM_NOT_ACQUIRED}
     * until the broker parked the message under its own reason with no FAILED record behind it —
     * the silent parking the state machine exists to prevent. It is a catch-and-record, not a
     * catch-and-ignore: the failure is reported at ERROR, classified, and written to the processed
     * log before the delivery is settled. A store that dies inside the recording write throws out
     * of the catch block itself, which is correct — nothing is recordable during a store outage,
     * and the transport adapter's own handling takes over.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private GuardDecision runUnder(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance) {
        GuardDecision outcome;
        try {
            outcome = runToOutcome(command, claim, lastChance);
        } catch (PayloadUnavailableException | ReferenceDataUnavailableException
                | TransformationFailedException | SubmissionFailedException classified) {
            outcome = failed(claim, classified.classification(), classified.reason(), lastChance);
        } catch (RuntimeException unexpected) {
            LOG.error("Run failed unexpectedly; recording it so the claim is released. "
                            + "source={} requestId={} type={}",
                    claim.source(), claim.requestId(), unexpected.getClass().getName());
            outcome = failed(claim, FailureClassification.TRANSIENT,
                    ReasonCode.UNEXPECTED_FAILURE, lastChance);
        }
        return outcome;
    }

    private GuardDecision runToOutcome(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance) {
        final Instant deadline = clock.instant().plus(processingDeadline);

        final JsonNode payload = payloadSource.fetch(command);
        // The payload's size and nothing from inside it: every defendant on a court register is a
        // youth, and a count is the most a deployed log may be told about one (Principle VII).
        LOG.info("Hearing payload obtained. source={} requestId={} hearingId={} topLevelFields={}",
                command.source(), command.requestId(), command.hearingId(), payload.size());

        final GuardDecision outcome;
        // Strictly before, so the deadline is a bound that is *reached* rather than passed: a run
        // standing exactly on it has already used the time its claim guarantees and may not write a
        // completion. `isAfter` on the other side of this branch would let that one instant through.
        if (clock.instant().isBefore(deadline)) {
            outcome = transformer == null
                    ? completed(claim, CompletionReason.NO_DEFENDANTS)
                    : distribute(command, payload, claim);
        } else {
            outcome = failed(claim, FailureClassification.TRANSIENT,
                    ReasonCode.PROCESSING_DEADLINE_EXCEEDED, lastChance);
        }
        return outcome;
    }

    /**
     * The stages between the payload and the outcome: the group-proceedings decision, the
     * transformation, and the submission of whatever it produced.
     *
     * <p>The policy is asked about the <em>hearing</em> and the transformation is handed the whole
     * claim-check envelope, which is the split the legacy has: {@code index.js:21} reads the flag
     * from {@code hearingResultedObj.hearing} while {@code SetCourtRegister} is called with the
     * hearing and the shared time together. Handing the envelope to the policy would read an absent
     * field and never suppress anything.
     *
     * <p>Nothing here edits what it was handed. The legacy passes one mutable hearing object to
     * {@code SetCourtRegister} and then to {@code OutboundCourtRegister}, and is saved from the
     * consequences only by the Durable Functions serialisation boundary between activities; a Java
     * pipeline passes references, so the payload is handed on exactly as it was fetched and the
     * stages derive rather than edit (constitution Principle IV).
     *
     * @param command the validated request
     * @param payload the hearing payload the fetch returned
     * @param claim   the claim this run holds
     * @return the settlement the outcome calls for
     */
    private GuardDecision distribute(
            final DistributionCommand command, final JsonNode payload, final RunClaim claim) {

        final GuardDecision outcome;
        if (groupProceedings.suppresses(command, hearingOf(payload))) {
            outcome = completed(claim, CompletionReason.GROUP_PROCEEDINGS);
        } else {
            // Seam: the reference-data read the core is about to own is not wired yet, so the
            // transformation is handed an answer nobody made.
            outcome = switch (transformer.transform(command, payload, MissingNode.getInstance())) {
                case TransformationResult.NoRegister nothing -> completed(claim, nothing.reason());
                case TransformationResult.Register register -> submit(command, register, claim);
            };
        }
        return outcome;
    }

    /**
     * The hearing inside the claim-check envelope.
     *
     * <p>The legacy reads {@code hearingResultedObj.hearing.isGroupProceedings} without a guard, so
     * an envelope carrying no hearing kills the orchestration. It is refused here for the same
     * reason and classified non-transient: the payload the cache holds reads the same on every
     * redelivery, so spending the delivery budget on it would only delay the dead-letter support
     * acts on.
     *
     * @param payload the claim-check payload
     * @return the hearing it carries
     * @throws TransformationFailedException if the envelope carries no hearing
     */
    private static JsonNode hearingOf(final JsonNode payload) {
        final JsonNode hearing = payload.get(HEARING);
        if (hearing == null || hearing.isNull()) {
            throw new TransformationFailedException("claim-check payload carries no hearing");
        }
        return hearing;
    }

    /**
     * Sends one register to progression and records the run only once it has been accepted.
     *
     * <p>Exactly one POST per run: progression's {@code add-court-register} appends an event and a
     * row for every call, so a second submission inside one run is a second register for the
     * hearing. A submission that failed is never a completion — that is the exact shape of defect
     * C1, where the POST's errors are swallowed and a lost register and a delivered one become the
     * same row.
     *
     * @param command  the validated request
     * @param register the register the transformation produced
     * @param claim    the claim this run holds
     * @return the settlement the outcome calls for
     */
    private GuardDecision submit(
            final DistributionCommand command,
            final TransformationResult.Register register,
            final RunClaim claim) {

        final SubmissionReceipt receipt =
                submissionClient.submit(register.document(), CallerIdentity.of(command));
        LOG.info("Register submitted. source={} requestId={} hearingId={} status={}",
                command.source(), command.requestId(), command.hearingId(), receipt.responseCode());
        return completed(claim, CompletionReason.SUBMITTED);
    }

    /**
     * Records the run's success, and counts it only if the guard accepted the write.
     *
     * <p>A superseded runner's completion affects no rows and comes back as an abandon; counting it
     * as a completed request would report work that was never recorded.
     *
     * <p>Two counters, answering two questions. {@code requestSettled} says the request finished;
     * {@code completed} says <em>how</em> — which of the five ways a court-register run ends well.
     * Four of them send nothing, and a single undifferentiated success is the legacy defect C33.
     */
    private GuardDecision completed(final RunClaim claim, final CompletionReason reason) {
        final GuardDecision outcome = guard.recordCompletion(claim, reason);
        if (outcome instanceof GuardDecision.Complete) {
            LOG.info("Run finished. source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), reason.value());
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.completed(reason);
        }
        return outcome;
    }

    /**
     * Records a failed run — loudly, and with a bounded reason rather than whatever the layer
     * beneath had to say about it.
     *
     * <p><strong>A failure the throw site classified {@code NON_TRANSIENT} is parked here and
     * now</strong>, whatever the delivery budget says: no redelivery can change it — the same
     * payload reads the same way, the same bytes meet the same refusal — so the delivery count is
     * not consulted at all, the remaining deliveries would buy nothing and would delay by four
     * back-offs the dead-letter support acts on, and the row carries the reason the port named
     * rather than an exhaustion the service never reached.
     *
     * <p>A transient failure means two different things depending on whether the queue will deliver
     * the message again. With deliveries remaining it is recorded RETRYING and the delivery is handed
     * back. On the final permitted delivery it is recorded FAILED, in the statement that stamps the
     * identity of the delivery that exhausted the budget onto the row, and the message is parked
     * where support can see it. Retry exhaustion is judged by that delivery count alone and never by
     * the cumulative attempt count, which is a lifetime tally and would park a replayed request on
     * its first failure.
     *
     * <p>The terminal outcome is counted only once the guard has accepted the write: a superseded
     * runner's parking affects no rows and comes back as a hand-back, and counting it would report a
     * request parked that is still being worked on by somebody else.
     */
    private GuardDecision failed(
            final RunClaim claim,
            final FailureClassification classification,
            final ReasonCode reason,
            final boolean lastChance) {
        LOG.error("Pipeline run failed. source={} requestId={} classification={} reason={} "
                        + "finalPermittedDelivery={}",
                claim.source(), claim.requestId(), classification.label(), reason.code(), lastChance);
        metrics.pipelineFailed(classification);

        return switch (classification) {
            case NON_TRANSIENT -> parked(guard.recordNonTransientFailure(claim, reason));
            case TRANSIENT -> lastChance
                    ? parked(guard.recordExhaustion(claim, reason))
                    : guard.recordTransientFailure(claim, reason);
        };
    }

    /** Counts a parking the guard accepted, and only one it accepted. */
    private GuardDecision parked(final GuardDecision outcome) {
        if (outcome instanceof GuardDecision.DeadLetter) {
            metrics.requestSettled(RequestOutcome.FAILED);
        }
        return outcome;
    }
}
