package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.config.OutputMode;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.courtregister.pipeline.Dates;

/**
 * One request, from the guard admitting it to the guard recording what happened.
 *
 * <p>The application core: it names no broker, no database and no HTTP client, and it is the only
 * place that knows the order the ports are called in. The transport adapter above it decides how a
 * delivery is settled; this decides what the delivery is worth settling as.
 *
 * <p><strong>The stages, in order</strong>: admit, fetch the hearing payload, ask whether the
 * group-proceedings flag suppresses the register, read the subscriptions in force on the register's
 * day, transform, record the register - or, under the fallback mode, submit it - and then record
 * the run. The two reads are the core's because the ports are; the
 * transformation between them is pure, and is handed everything it needs (constitution Principle V).
 * The shape the
 * transport suites drive it through is unchanged: a request and the delivery it arrived on go in,
 * and exactly one {@link GuardDecision} comes out, so the listener has something to settle on every
 * path (constitution Principle VI). What the legacy orchestrator has instead is four silent guards
 * and a catch-all that reports failure from a completed orchestration, and no test file at all.
 *
 * <p>Every way a run can end well is written down and counted. A suppressed hearing ends
 * {@code COMPLETED, group-proceedings} where the legacy records nothing (the recorded half of defect
 * fix C7); a transformation that declines to produce a register says which of the three remaining
 * reasons it was, and the run ends {@code COMPLETED} under that reason; a register that was built
 * ends {@code recorded}, once the store holds it, or {@code submitted} under the fallback mode and
 * only after progression has accepted it. Four of the five completion
 * reasons send nothing and two of those four are this flow's ordinary results, so the reason is
 * written and counted rather than folded away (defect fixes C6 and C33): a court centre nobody
 * subscribes to and a pipeline that has quietly stopped working look identical from the outside
 * otherwise.
 *
 * <p><strong>What happens to a register that was built is a deployment's choice, made once.</strong>
 * {@code courtregister.output} says whether the last stage records the register in this service's
 * own store or POSTs it to progression, and the pipeline is handed the answer rather than reading a
 * setting. It is not a second cutover lever: the value is fixed for the life of a release, the one
 * lever is the App Configuration flag, and {@link OutputMode} says so at length.
 *
 * <p><strong>The transformation port is not implemented yet</strong>, and a pipeline constructed
 * without one — the walking skeleton the transport suites use — ends every run it admits as
 * {@code no-defendants}, which is the outcome a payload with no register in it earns anyway. The
 * chain behind that port arrives with the mapper phase; nothing above it changes when it does.
 *
 * <p><strong>The run bounds itself, across every stage.</strong> Before the ports are touched the
 * deadline is fixed at {@code courtregister.claim.processing-deadline} from now, and what is left of
 * it is read again before the transformation, before the send and before any outcome is written.
 * <strong>One budget, not one check</strong>: three network steps that each fit inside the deadline
 * can spend more than twice it between them, and the stage that matters is the last one — a POST
 * started after the deadline is a POST started while the claim behind it may already have been
 * reclaimed, and progression's {@code add-court-register} <em>appends</em> a register rather than
 * replacing one, so the second runner's send is a second register for the hearing. An overrun is
 * TRANSIENT: the delivery is handed back, and the redelivery gets the whole deadline again with
 * nothing sent twice. The single exception is the completion of a register that <em>was</em> sent,
 * which is written whatever the clock says — the alternative is a redelivery that sends it again.
 *
 * <p>The deadline is strictly shorter than the claim lease, so a slow run stops itself while its
 * claim is still unambiguously its own; the alternative is a runner that discovers it has been
 * superseded only when its outcome write affects no rows — which is safe, but leaves the request
 * waiting for a redelivery it could have asked for a minute earlier. The check is against elapsed
 * local time only: nothing here compares a JVM reading with a stored timestamp, which is the
 * multi-node skew the data model's single-time-authority rule exists to rule out. That the whole
 * run's worst case actually fits inside the deadline is checked at startup, in
 * {@code PropertiesValidator}.
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
 * <p><strong>Every port answers the first question for itself, and two of them answer it both
 * ways.</strong> A cache miss <em>and</em> a fallback miss is a reason to come back rather than a
 * silent stop (defect fix C32), and a reference-data context that could not be reached may answer
 * next time — both transient. A read the query side or reference data <em>understood and
 * declined</em> is not: the same request is refused identically on every delivery, so it is parked
 * here and now under the code the adapter named rather than carried to exhaustion and parked under a
 * reason that describes the retry budget instead of the fault. A deadline is not a fault at all and
 * is never parked for being unretryable, and a failure nothing anticipated is treated as transient,
 * because "unknown" is not the same as "hopeless".
 *
 * <p><strong>A store that went away is the one failure this class does not classify at all.</strong>
 * Nothing is recordable while it is gone, and what the delivery needs is not a transient failure but
 * a suspension - the delivery back <em>and</em> intake stopped - which is the transport adapter's to
 * carry out and nobody else's (spec FR-015). So it leaves the run as it was thrown, by its own type
 * and never by where it was thrown, because this service's store is reached from more than one stage
 * and the answer is the same wherever it went away. That type is
 * {@link StoreUnavailableException}, raised by the persistence layer: the outage is a JDBC fact
 * there and a domain signal here, which is what lets this class name no data-access type at all.
 */
public class DistributionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(DistributionPipeline.class);

    /** The field of the claim-check payload the hearing itself sits under. */
    private static final String HEARING = "hearing";

    /** The field of the claim-check payload the results' share instant sits under. */
    private static final String SHARED_TIME = "sharedTime";

    private final IdempotencyGuard guard;
    private final HearingPayloadSource payloadSource;
    private final GroupProceedingsPolicy groupProceedings;
    private final NowSubscriptionsSource subscriptionsSource;
    private final Dates dates;
    private final RegisterTransformer transformer;
    private final OutputMode outputMode;
    // The store the recording stage writes through, held from here so that the composition is
    // settled in one place. Null under `progression-post`, where the last stage is the POST and a
    // deployment that never records has nothing to hand in.
    private final RegisterStore registerStore;
    // The contract the register is held to at the write. Null under `progression-post`, where
    // nothing is written, and under the skeleton the transport suites use.
    // PMD.UnusedPrivateField: this commit lands the seam the red run needs to compile against; the
    // implementation commit that reads it follows immediately and removes this suppression.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final RegisterDocumentValidator recordedDocument;
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
     * Creates the 001 pipeline, whose last stage is the POST to progression.
     *
     * <p>{@link OutputMode#PROGRESSION_POST} by construction, and it is the constructor the 001
     * suites keep using: their subject is the submission leg, and a submission suite that had to
     * name an output mode to reach the submission would be saying the same thing twice. A
     * deployment's mode is stated by {@code courtregister.output} and reaches the pipeline through
     * the constructor below.
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
        this(guard, payloadSource, groupProceedings, subscriptionsSource, dates, transformer,
                OutputMode.PROGRESSION_POST, null, submissionClient, metrics, clock,
                processingDeadline);
    }

    /**
     * Creates the pipeline over both last stages, and the mode that says which of them runs.
     *
     * <p>Both are handed in rather than one: the mode is a deployment's statement, and a pipeline
     * that held only the port its mode selected could not be asked what the other mode would have
     * done. Which one is exercised is decided once, here, and never per run.
     *
     * @param guard               the {@code (source, requestId)} processed-log guard
     * @param payloadSource       where the hearing payload comes from
     * @param groupProceedings    whether the hearing's flag suppresses its register
     * @param subscriptionsSource where the now-subscriptions a register is addressed with come from
     * @param dates               the register's date handling, for the day the subscriptions are
     *                            read on; pure, so holding it here costs the core no I/O
     * @param transformer         how a hearing payload and its subscriptions become a register
     * @param outputMode          what this deployment does with a register that was built
     * @param registerStore       this service's own register store, the {@code record} mode's last
     *                            stage
     * @param submissionClient    where an assembled register is sent under {@code progression-post}
     * @param metrics             the instrument surface every outcome is counted on
     * @param clock               elapsed-time source for the run's own deadline
     * @param processingDeadline  the enforced bound on a run, strictly shorter than the claim lease
     */
    // Six ports, one policy, one date helper and three settings. The count is the core's dependency
    // list, not a smell: see the note on the constructor above.
    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final GroupProceedingsPolicy groupProceedings,
            final NowSubscriptionsSource subscriptionsSource,
            final Dates dates,
            final RegisterTransformer transformer,
            final OutputMode outputMode,
            final RegisterStore registerStore,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this(guard, payloadSource, groupProceedings, subscriptionsSource, dates, transformer,
                outputMode, registerStore, null, submissionClient, metrics, clock,
                processingDeadline);
    }

    /**
     * Creates the pipeline over both last stages, with the contract the recorded register is held to.
     *
     * <p>The validator belongs to the {@code record} arm and to nothing else: the submission arm's
     * document was held to the {@code add-court-register} command by the transformation, and the
     * body that goes on the wire is the adapter's projection of it. What this one answers for is the
     * register as the store will hold it, {@code defendantType} included.
     *
     * @param guard               the {@code (source, requestId)} processed-log guard
     * @param payloadSource       where the hearing payload comes from
     * @param groupProceedings    whether the hearing's flag suppresses its register
     * @param subscriptionsSource where the now-subscriptions a register is addressed with come from
     * @param dates               the register's date handling, for the day the subscriptions are
     *                            read on
     * @param transformer         how a hearing payload and its subscriptions become a register
     * @param outputMode          what this deployment does with a register that was built
     * @param registerStore       this service's own register store, the {@code record} mode's last
     *                            stage
     * @param recordedDocument    the frozen register-document contract the write is held to
     * @param submissionClient    where an assembled register is sent under {@code progression-post}
     * @param metrics             the instrument surface every outcome is counted on
     * @param clock               elapsed-time source for the run's own deadline
     * @param processingDeadline  the enforced bound on a run, strictly shorter than the claim lease
     */
    // Seven ports, one policy, one date helper and three settings. The count is the core's
    // dependency list, not a smell: see the note on the first mode-bearing constructor above.
    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final GroupProceedingsPolicy groupProceedings,
            final NowSubscriptionsSource subscriptionsSource,
            final Dates dates,
            final RegisterTransformer transformer,
            final OutputMode outputMode,
            final RegisterStore registerStore,
            final RegisterDocumentValidator recordedDocument,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this.recordedDocument = recordedDocument;
        this.guard = guard;
        this.payloadSource = payloadSource;
        this.groupProceedings = groupProceedings;
        this.subscriptionsSource = subscriptionsSource;
        this.dates = dates;
        this.transformer = transformer;
        this.outputMode = outputMode;
        this.registerStore = registerStore;
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
        return process(command, delivery, RecordedFlagState.UNKNOWN);
    }

    /**
     * Runs one request under the flag state the delivery attached to it.
     *
     * <p>The state travels with the command because the transport is where it is learned: the intake
     * side reads the one lever with the same reader the nightly job uses and labels an arriving
     * command from the last reading, never waiting on one (research §12). The pipeline records what
     * it was handed and reads no flag of its own.
     *
     * @param command   the validated request
     * @param delivery  who is running it, and whether the queue will deliver it again
     * @param flagState the cutover flag as the intake side last read it, which decides whether the
     *                  recorded register is batched automatically at all
     * @return the settlement the outcome calls for - a settlement, never a run
     */
    public GuardDecision process(final DistributionCommand command, final DeliveryIdentity delivery,
            final RecordedFlagState flagState) {
        final GuardDecision admission = guard.admit(command, delivery);
        final GuardDecision decision;
        if (admission instanceof GuardDecision.Run admitted) {
            decision = runUnder(command, admitted.claim(), delivery.finalPermittedDelivery(),
                    flagState);
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
     * log before the delivery is settled. A store that dies while that outcome is being written
     * throws out of the catch block itself, which is correct - nothing is recordable during a store
     * outage, and the transport adapter's own handling takes over.
     *
     * <p><strong>Between them sits the one failure this frame refuses to touch.</strong>
     * {@link StoreUnavailableException} leaves it untouched: there is nothing to record while the
     * store is gone, and the transport adapter is the only place that can hand the delivery back
     * <em>and</em> stop intake. It is a <em>domain</em> signal rather than a JDBC one, because this
     * class may not import a JDBC type (constitution Principle V) - the persistence layer decides
     * which failures are a store that went away and raises this for them, and the listener reads the
     * same signal, so the rule is written once instead of twice.
     *
     * <p>Contention is not one of them and does not need a branch of its own here. A re-share losing
     * a race for its key is the store <em>answering</em>, over a connection that plainly worked; the
     * persistence layer lets it out as it came, and it falls to the catch-all below, which releases
     * the claim so the redelivery finds the row free. Stopping the queue for it would stall every
     * message behind a fault that clears itself on the next delivery.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private GuardDecision runUnder(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance,
            final RecordedFlagState flagState) {
        GuardDecision outcome;
        try {
            outcome = runToOutcome(command, claim, lastChance, flagState);
        } catch (PayloadUnavailableException | ReferenceDataUnavailableException
                | TransformationFailedException | SubmissionFailedException classified) {
            outcome = failed(claim, classified.classification(), classified.reason(), lastChance);
        } catch (StoreUnavailableException storeGone) {
            throw storeGone;
        } catch (RuntimeException unexpected) {
            outcome = unexpectedFailure(claim, unexpected, lastChance);
        }
        return outcome;
    }

    /**
     * The failure nothing anticipated: reported by its type, classified transient, and recorded so
     * that the claim this frame holds is released before the delivery is settled.
     *
     * @param claim      the claim this run holds
     * @param unexpected what was thrown
     * @param lastChance whether the queue will deliver this message again
     * @return the settlement the outcome calls for
     */
    private GuardDecision unexpectedFailure(
            final RunClaim claim, final RuntimeException unexpected, final boolean lastChance) {
        LOG.error("Run failed unexpectedly; recording it so the claim is released. "
                        + "source={} requestId={} type={}",
                claim.source(), claim.requestId(), unexpected.getClass().getName());
        return failed(claim, FailureClassification.TRANSIENT,
                ReasonCode.UNEXPECTED_FAILURE, lastChance);
    }

    private GuardDecision runToOutcome(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance,
            final RecordedFlagState flagState) {
        final RunBudget budget =
                new RunBudget(clock.instant().plus(processingDeadline), lastChance);

        final JsonNode payload = payloadSource.fetch(command);
        // The payload's size and nothing from inside it: every defendant on a court register is a
        // youth, and a count is the most a deployed log may be told about one (Principle VII).
        LOG.info("Hearing payload obtained. source={} requestId={} hearingId={} topLevelFields={}",
                command.source(), command.requestId(), command.hearingId(), payload.size());

        final GuardDecision outcome;
        if (spent(budget)) {
            outcome = overran(claim, budget);
        } else if (transformer == null) {
            outcome = completed(claim, CompletionReason.NO_DEFENDANTS);
        } else {
            outcome = distribute(command, payload, claim, budget, flagState);
        }
        return outcome;
    }

    /**
     * What is left of the run's time, and what a delivery that runs out of it is worth.
     *
     * @param deadline   the instant the run promised to have stopped by
     * @param lastChance whether the queue will deliver this message again
     */
    private record RunBudget(Instant deadline, boolean lastChance) {
    }

    /**
     * Whether the run has used the time its claim guarantees it.
     *
     * <p>Strictly before, so the deadline is a bound that is <em>reached</em> rather than passed: a
     * run standing exactly on it has already spent its budget and may not start another call or
     * write another outcome. Reading it the other way would let that one instant through.
     *
     * @param budget the run's budget
     * @return whether the budget is gone
     */
    private boolean spent(final RunBudget budget) {
        return !clock.instant().isBefore(budget.deadline());
    }

    /**
     * The outcome of a run that ran out of budget: transient, because the redelivery gets a whole
     * fresh one and nothing has been sent.
     *
     * @param claim  the claim this run holds
     * @param budget the run's budget
     * @return the settlement the overrun calls for
     */
    private GuardDecision overran(final RunClaim claim, final RunBudget budget) {
        return failed(claim, FailureClassification.TRANSIENT,
                ReasonCode.PROCESSING_DEADLINE_EXCEEDED, budget.lastChance());
    }

    /**
     * The stages between the payload and the outcome: the group-proceedings decision, the
     * reference-data read, the transformation, and the submission of whatever it produced.
     *
     * <p><strong>The read sits here rather than inside the transformation</strong> because the
     * transformation is pure by contract (constitution Principle V). The legacy's
     * {@code CourtRegisterSubscriptions} activity reads reference data and matches against it in one
     * step; the port behind it is the core's, so the core makes the call and the matching stage is
     * handed the answer. It is made after the suppression decision and before the transformation,
     * which is where the legacy makes it too — the orchestrator skips the activity entirely for a
     * group-proceedings hearing ({@code index.js:23}) and calls it for every other one, including the
     * hearing that gathered no defendants.
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
     * @param payload   the hearing payload the fetch returned
     * @param claim     the claim this run holds
     * @param budget    what is left of the run's time
     * @param flagState the cutover flag as the intake side last read it
     * @return the settlement the outcome calls for
     */
    private GuardDecision distribute(
            final DistributionCommand command,
            final JsonNode payload,
            final RunClaim claim,
            final RunBudget budget,
            final RecordedFlagState flagState) {

        final GuardDecision outcome;
        if (groupProceedings.suppresses(command, hearingOf(payload))) {
            outcome = completed(claim, CompletionReason.GROUP_PROCEEDINGS);
        } else {
            final LocalDate registerDay = dates.subscriptionDay(sharedTimeOf(payload));
            final JsonNode subscriptions =
                    subscriptionsSource.subscriptionsOn(registerDay, CallerIdentity.of(command));
            outcome = spent(budget)
                    ? overran(claim, budget)
                    : transformed(command, payload, subscriptions, registerDay, claim, budget,
                            flagState);
        }
        return outcome;
    }

    /**
     * The transformation, and what its answer is worth once the budget has been read again.
     *
     * <p>The transformation itself is pure and bounded, but it is not free, and the budget is what
     * says whether the outcome it produced may still be written. A completion recorded after the
     * deadline is a completion recorded while another delivery may already hold the claim.
     *
     * @param command       the validated request
     * @param payload       the hearing payload the fetch returned
     * @param subscriptions reference data's answer for the register's day
     * @param registerDay   the day the subscriptions were read for, and the day the output row
     *                      records (C12)
     * @param claim         the claim this run holds
     * @param budget        what is left of the run's time
     * @param flagState     the cutover flag as the intake side last read it
     * @return the settlement the outcome calls for
     */
    private GuardDecision transformed(
            final DistributionCommand command,
            final JsonNode payload,
            final JsonNode subscriptions,
            final LocalDate registerDay,
            final RunClaim claim,
            final RunBudget budget,
            final RecordedFlagState flagState) {

        final RunAnomalies anomalies = new RunAnomalies();
        final TransformationResult result =
                transformer.transform(command, payload, subscriptions, anomalies);
        counted(anomalies);

        return switch (result) {
            case TransformationResult.NoRegister nothing -> {
                reported(command, anomalies);
                yield spent(budget)
                        ? overran(claim, budget)
                        : completed(claim, nothing.reason().completion());
            }
            case TransformationResult.Register register ->
                output(command, register, anomalies.counts(), registerDay, claim, budget,
                        flagState);
        };
    }

    /**
     * The last stage, under the output mode this deployment was built for.
     *
     * <p>One decision, taken once when the pipeline was assembled, so a run never asks a setting
     * what to do with a register it has already built. The two arms are the same success reported
     * under two reasons - {@code recorded} and {@code submitted} - and they are never both live.
     *
     * <p>Only one of them is handed the run's anomaly counts and its register day. The submission
     * carries both to progression, which has no other way of learning them; the recording needs
     * neither, because the row the store writes is derived from the document itself - the register
     * day is the London date part of the document's own register instant, which is what orders two
     * re-shares of one hearing.
     *
     * @param command     the validated request
     * @param register    the register the transformation produced
     * @param anomalies   how many of each guarded skip it survived
     * @param registerDay the day the register covers, as its recipients were read for it (C12)
     * @param claim       the claim this run holds
     * @param budget      what is left of the run's time
     * @param flagState   the cutover flag as the intake side last read it
     * @return the settlement the outcome calls for
     */
    private GuardDecision output(
            final DistributionCommand command,
            final TransformationResult.Register register,
            final Map<TransformationAnomaly, Integer> anomalies,
            final LocalDate registerDay,
            final RunClaim claim,
            final RunBudget budget,
            final RecordedFlagState flagState) {

        return switch (outputMode) {
            case RECORD -> record(command, register, claim, budget, flagState);
            case PROGRESSION_POST ->
                submit(command, register, anomalies, registerDay, claim, budget);
        };
    }

    /**
     * Writes one register into this service's own store and records the run only once it is there.
     *
     * <p>The register stops being progression's to hold: nothing leaves the pod, and the row the
     * store writes is the register (spec US1). The write and the supersession of the hearing's
     * earlier active row for the day are the store's own single transaction, so no reader can ever
     * see two active registers for one hearing and one day (research §8), and a hearing whose
     * results are shared twice before the nightly run therefore ends with one register rather than
     * two.
     *
     * <p><strong>Four answers go into the write and this stage invents none of them.</strong> The
     * document is the one the transformation validated; the OU code travels beside it because the
     * frozen contract has no field for it and the batch's file name and render payload are built
     * from it; the defendant type is the one the chain resolved from the hearing's own court
     * application (FR-002); and the flag state is what the intake side last learned about the one
     * lever.
     *
     * <p><strong>The flag state is the delivery's and this stage records it unchanged.</strong> The
     * intake side labels an arriving command from the last reading of the one lever, taken with the
     * same reader the nightly job uses, and hands the answer down with the command (research §12);
     * the pipeline reads no flag of its own, because a recording that waited on App Configuration
     * would stall the queue behind a label and a register that has been built is worth more than the
     * label it carries. A run with no reading behind it therefore records {@code UNKNOWN}, which is a
     * statement rather than a placeholder - it is what a pod that reads no flag at all has to say,
     * and {@code UNKNOWN} and {@code OFF} are both kept out of automatic batching, so the label costs
     * a register nothing except an operator's attention.
     *
     * <p><strong>The budget is read once more before the write</strong>, for the reason the send
     * reads it: past the deadline the claim behind this run may already have been reclaimed, and a
     * row written under a claim somebody else holds is a register the redelivery will record again
     * and this one will only supersede. The completion that follows a write that <em>did</em> happen
     * is not withheld for the budget, exactly as it is not on the submission arm.
     *
     * @param command   the validated request
     * @param register  the register the transformation produced
     * @param claim     the claim this run holds
     * @param budget    what is left of the run's time
     * @param flagState the cutover flag as the intake side last read it, recorded as the row's own
     * @return the settlement the outcome calls for
     */
    private GuardDecision record(
            final DistributionCommand command,
            final TransformationResult.Register register,
            final RunClaim claim,
            final RunBudget budget,
            final RecordedFlagState flagState) {

        final GuardDecision outcome;
        if (spent(budget)) {
            outcome = overran(claim, budget);
        } else {
            final RecordOutcome recording = registerStore.record(command, register.document(),
                    register.courtCentreOuCode(), register.document().defendantType(), flagState);
            // The identities of the two rows and nothing from inside either of them: a register is a
            // document about children (Principle VII). `superseded` is the fact support needs when a
            // hearing is re-shared - the register that will not be sent is the one this line names.
            LOG.info("Register recorded. source={} requestId={} hearingId={} outputId={} "
                            + "supersededOutputId={}",
                    command.source(), command.requestId(), command.hearingId(),
                    recording.outputId(), recording.supersededOutputId());
            outcome = completed(claim, CompletionReason.RECORDED);
        }
        return outcome;
    }

    /**
     * Counts every guarded skip the transformation met, once per occurrence.
     *
     * <p>Whichever way the run ended: a register that was sent missing a part and a register that
     * was never built are both worth knowing about, and the metric is the only surface that carries
     * both. The counter series is what an alert on "this court centre's subscriptions have gone
     * wrong" would fire on, and a defect fix whose visibility never left the transformation is the
     * defect again (C19, C20, C27).
     *
     * @param anomalies what the run skipped
     */
    private void counted(final RunAnomalies anomalies) {
        anomalies.counts().forEach((anomaly, occurrences) -> {
            for (int counted = 0; counted < occurrences; counted++) {
                metrics.transformationAnomaly(anomaly);
            }
        });
    }

    /**
     * Says out loud what a run that produced no register skipped on the way to that answer.
     *
     * <p>Only for that outcome, and it is the outcome that needs it. A register that is sent carries
     * its counts into {@code processed_output.anomaly_summary}, written with the digest before the
     * POST; a run that produced no register has no output row to write anything against — the
     * cardinality is 0..1 and this is the 0 — so the codes would otherwise exist for the length of
     * the run and then stop existing. One line, the bounded codes and their counts, the permitted
     * correlation identifiers, and nothing from inside the payload (constitution Principle VII).
     *
     * @param command   the validated request, for correlation
     * @param anomalies what the run skipped
     */
    private static void reported(final DistributionCommand command, final RunAnomalies anomalies) {
        if (!anomalies.isEmpty()) {
            LOG.warn("The transformation skipped parts of a register it then did not produce, so "
                            + "there is no output row to record them on. source={} requestId={} "
                            + "hearingId={} anomalies={}",
                    command.source(), command.requestId(), command.hearingId(),
                    anomalies.summary());
        }
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
     * The instant the results were shared, as the claim-check envelope records it.
     *
     * <p>The same value the fragment's {@code registerDate} is built from, read from the same place
     * ({@code CourtRegisterOrchestrator/index.js:28}), so the day a register is addressed on and the
     * day it is dated can never come apart. The legacy derives the {@code on=} day from the fragment
     * — one step later, from the same field.
     *
     * @param payload the claim-check payload
     * @return the shared time it carries
     * @throws TransformationFailedException if the envelope carries no shared time
     */
    private static String sharedTimeOf(final JsonNode payload) {
        final JsonNode sharedTime = payload.get(SHARED_TIME);
        if (sharedTime == null || sharedTime.isNull()) {
            throw new TransformationFailedException("claim-check payload carries no shared time");
        }
        return sharedTime.stringValue();
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
     * <p><strong>The send is the stage the budget exists for.</strong> A POST started after the
     * deadline is a POST started while the claim behind it may already have been reclaimed, and a
     * second runner's POST does not overwrite the first — it appends a second register for the same
     * hearing. So the budget is read once more here and an overrun is handed back before anything
     * leaves the service; the redelivery has the whole deadline again.
     *
     * <p><strong>The budget travels with the register, not only around it.</strong> Reading it once
     * here would bound the moment the POST <em>starts</em> and nothing after it: the transport
     * retries, and the waits between its attempts are the longest thing a run does. So the deadline
     * itself is handed to the port, which refuses to start an attempt or take a wait across it — an
     * overrun inside the submission comes back TRANSIENT under the same
     * {@link ReasonCode#PROCESSING_DEADLINE_EXCEEDED} an overrun outside it does, and the redelivery
     * gets a whole fresh budget.
     *
     * <p>The completion that follows a successful send is <strong>not</strong> withheld for the
     * budget. The register has gone; a run that declined to record it would be redelivered and would
     * send it a second time, which is the one outcome the budget exists to prevent.
     *
     * @param command     the validated request
     * @param register    the register the transformation produced
     * @param anomalies   how many of each guarded skip it survived
     * @param registerDay the day the register covers, as its recipients were read for it (C12)
     * @param claim       the claim this run holds
     * @param budget      what is left of the run's time
     * @return the settlement the outcome calls for
     */
    private GuardDecision submit(
            final DistributionCommand command,
            final TransformationResult.Register register,
            final Map<TransformationAnomaly, Integer> anomalies,
            final LocalDate registerDay,
            final RunClaim claim,
            final RunBudget budget) {

        final GuardDecision outcome;
        if (spent(budget)) {
            outcome = overran(claim, budget);
        } else {
            final SubmissionReceipt receipt = submissionClient.submit(new RegisterSubmission(
                    claim, budget.deadline(), register.document(), register.courtCentreOuCode(),
                    registerDay, CallerIdentity.of(command), anomalies));
            // `sentNow` distinguishes a POST this delivery made from a register the processed log
            // already held POSTED, whose POST this delivery deliberately skipped. Both complete the
            // run submitted; only one of them is a call that just happened, and a line that said
            // otherwise would report traffic to progression that never left the pod.
            LOG.info("Register submitted. source={} requestId={} hearingId={} status={} sentNow={}",
                    command.source(), command.requestId(), command.hearingId(),
                    receipt.responseCode(), receipt.sentByThisDelivery());
            outcome = completed(claim, CompletionReason.SUBMITTED);
        }
        return outcome;
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
