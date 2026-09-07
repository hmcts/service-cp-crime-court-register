package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.DocumentStatus;
import uk.gov.hmcts.cp.courtregister.domain.GenerationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;

/**
 * The safety net under the public-event topic.
 *
 * <p>A batch whose {@code document-available} or {@code generation-failed} never arrived would
 * otherwise sit in GENERATING for ever: the broker is the only thing that was going to say what
 * happened, and a subscription that missed the event says nothing a second time. So a batch still
 * GENERATING past {@code courtregister.generation.grace-period} is asked about exactly once, through
 * systemdocgenerator's query API, and the answer is applied through the same
 * {@link uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink} the listener uses, naming
 * RECONCILER rather than EVENT so the row records which mechanism learned it.
 *
 * <p><strong>And the batch that never reached the renderer at all.</strong>
 * {@code RegisterGenerationService} mints the payload id, writes it down, stores the payload, POSTs
 * and only then marks the batch requested. A pod that dies between the 202 and that mark - or a
 * store that blips on the mark itself - leaves the batch PENDING with a payload id and its
 * registers stamped, and nothing else in the flow ever revisits it: the overdue read is GENERATING
 * only, the stamped rows are outside {@code activeUnbatched}, and the live-key index defers every
 * later re-share of that key behind it. So a second read, over PENDING batches that minted a
 * payload before the same grace period, asks systemdocgenerator the same question by the same
 * payload id and applies the answer through the same sink. Where systemdocgenerator has no record of
 * that payload at all, the batch is failed RENDER_REQUEST_FAILED - this service's own verdict about
 * a request it cannot show was ever accepted, which is the reason the run itself would have used and
 * the one an operator reads as "ask for it again".
 *
 * <p>A batch the query has nothing to say about is failed GENERATION_TIMED_OUT rather than asked
 * again. Two systems have now been given the chance to report an outcome and neither has one, and a
 * batch that is retried indefinitely is a night's registers nobody is told are missing. That ending
 * goes through {@link RegisterStore#markFailed} rather than through the sink, because it is this
 * service's own verdict about a silence and not an answer anybody gave - and it still names
 * RECONCILER, which is what {@code BatchFailureReason.isGeneratorAttributed()} requires of it.
 *
 * <p><strong>Being answered about and being known are the same thing, and neither read changes
 * that.</strong> A query that answers about the payload and names neither a document nor a refusal
 * is systemdocgenerator saying it has the payload and is still rendering it, so that batch is
 * GENERATION_TIMED_OUT whichever of the two reads found it: the request reached the renderer, and
 * the state this service's own {@code markRequested} was lost from says nothing about whose silence
 * this is. RENDER_REQUEST_FAILED is for the other answer only - no record of the payload at all,
 * which is the one shape that shows the request never arrived.
 *
 * <p>The count is the broker's health seen from here: a run whose outcomes all arrive by
 * reconciliation is a subscription to investigate, and the {@code reconciled} metric and the run
 * report are where that shows.
 *
 * <p><strong>It has a schedule of its own, and not the flag gate's.</strong> Reconciliation is about
 * batches this service already owns; whether it may generate tonight is a different question with a
 * different answer, and hanging the net off the nightly run costs a night in both directions. Called
 * only from the run, a batch requested at 18:00 is first asked about at 18:01 - inside its own grace
 * period - and then not again until the next evening, so a lost public event costs about
 * twenty-four hours rather than the ten minutes the grace period configures; and on a night the flag
 * reads OFF or unreadable the run touches nothing, so a batch left GENERATING by an earlier ON night
 * is never asked about at all. Before cutover that is every night. So the pass runs every grace
 * period on the run's own single-threaded scheduler, under a lock of its own - the nightly run holds
 * its lock for as long as seventy minutes, and a reconciliation queued behind that is a net that
 * only ever ran when there was nothing to catch. The run still calls it, because the run report
 * names what the night had to fetch.
 *
 * <p>Every pass also publishes {@code courtregister_oldest_generating_age},
 * {@code courtregister_oldest_pending_age} and {@code courtregister_oldest_generated_age} from the
 * three reads it has just made: how long the batch that has been waiting longest for its document
 * has been waiting, how long the oldest batch that never reached the renderer has been stuck, and
 * how long the oldest batch holding a document nobody was told about has stood there. They are the
 * readings a nightly flow cannot be understood without between runs, and until this schedule
 * existed there was nowhere for them to be taken.
 *
 * <p><strong>The third read is a reading and not an ending.</strong> Notification follows the mark
 * that records the document in one step of one code path, so a store that went away in between - or
 * a listener session that rolled the JMS delivery back after that mark had already committed -
 * leaves the batch at GENERATED with rows nothing settled, and neither read above it moves for
 * that. But there is nothing to ask systemdocgenerator about a document it has already produced and
 * nothing to fail: the batch is owed its e-mails, and both
 * {@code RegisterNotifierService.resendFailed} and {@code notify-register --batch} are re-entrant
 * and will send exactly those. So this pass names the batch, publishes its age and settles nothing.
 *
 * <p><strong>Collaborators, and why these.</strong> The overdue read is
 * {@link RegisterBatchRepository#generatingSince}, which is a single-table read the store has no
 * part in; the question is the renderer's query method, which exists for this class and nothing
 * else; the answer goes to the sink, so that an outcome fetched here and an outcome received on the
 * topic take one code path; the silence goes to the store; and the grace period arrives as the one
 * {@link Duration} it is rather than as the whole of {@link
 * uk.gov.hmcts.cp.courtregister.config.GenerationProperties}, because a class that took the record
 * would state a dependency on ten settings it never reads. The clock is injected because the whole
 * of the rule is "how long ago was that", and a rule about elapsed time that reads the wall clock
 * cannot be asserted on either side of its own boundary.
 */
public class GenerationReconciler {

    /**
     * The lock's own name, which is what keeps it off the nightly run's.
     *
     * <p>Its own and not {@link RegisterGenerationJob#LOCK_NAME}: the run holds that one for as
     * long as seventy minutes, and a reconciliation queued behind it would be a safety net that
     * only ever ran when there was nothing to catch.
     */
    public static final String LOCK_NAME = "register-reconciliation";

    /**
     * How long the reconciliation lock is held for.
     *
     * <p>Shorter than the cadence it is taken at, because a lock outliving its own interval would
     * skip the next reconciliation rather than protect it. Five minutes is more than a pass over
     * tens of batches costs and less than the ten-minute grace period the schedule below runs at.
     */
    public static final String LOCK_AT_MOST_FOR = "PT5M";

    /**
     * The setting the schedule is written as, so the cadence and the grace period cannot drift.
     *
     * <p>An annotation attribute has to be a constant, and what this class is asked at is exactly
     * how long a batch is given before it counts as overdue: reading the same key the grace period
     * is configured under means a deployment that lengthens the grace lengthens the interval with
     * it, rather than leaving a batch overdue for nine minutes out of every ten.
     */
    private static final String GRACE_PERIOD = "${courtregister.generation.grace-period}";

    /**
     * What a batch systemdocgenerator answered about without a verdict is ended as.
     *
     * <p>The silence of a GENERATING batch, and also the silence of a stale PENDING one that
     * systemdocgenerator turns out to hold a payload for: in both cases the request reached the
     * renderer and the renderer is the one that has not finished, so the reason is the generator's
     * and the row names RECONCILER, which is what {@code BatchFailureReason.isGeneratorAttributed()}
     * requires of GENERATION_TIMED_OUT.
     */
    private static final Ending TIMED_OUT =
            new Ending(BatchFailureReason.GENERATION_TIMED_OUT, CompletedBy.RECONCILER);

    /** What a PENDING batch systemdocgenerator holds no payload for at all is ended as. */
    private static final Ending NEVER_REQUESTED =
            new Ending(BatchFailureReason.RENDER_REQUEST_FAILED, null);

    private static final Logger LOG = LoggerFactory.getLogger(GenerationReconciler.class);

    /** The {@code register_batch} table, read for the batches whose outcome is overdue. */
    private final RegisterBatchRepository batches;

    /** systemdocgenerator, asked what became of a payload it was given. */
    private final DocumentRenderer renderer;

    /** Where an answer is applied, the same port the public-event listener drives. */
    private final DocumentOutcomeSink sink;

    /** Where a silence is applied, as this service's own GENERATION_TIMED_OUT verdict. */
    private final RegisterStore store;

    /** Where a completion this run had to fetch rather than receive is counted. */
    private final GenerationMetrics metrics;

    /** How long a batch may stay GENERATING before it is asked about. */
    private final Duration gracePeriod;

    /** The clock the grace period is measured back from. */
    private final Clock clock;

    /**
     * Creates the reconciler over the batches it reads and the three collaborators it applies
     * through.
     *
     * @param batches     the {@code register_batch} table, read for the batches whose outcome is
     *                    overdue
     * @param renderer    systemdocgenerator, asked what became of a payload it was given
     * @param sink        where an answer is applied, the same one the public-event listener uses
     * @param store       where a silence is applied, as this service's own GENERATION_TIMED_OUT
     *                    verdict
     * @param metrics     where a completion this run had to fetch rather than receive is counted
     * @param gracePeriod how long a batch may stay GENERATING before it is asked about
     * @param clock       the clock the grace period is measured back from
     */
    public GenerationReconciler(final RegisterBatchRepository batches,
            final DocumentRenderer renderer, final DocumentOutcomeSink sink,
            final RegisterStore store, final GenerationMetrics metrics, final Duration gracePeriod,
            final Clock clock) {
        this.batches = batches;
        this.renderer = renderer;
        this.sink = sink;
        this.store = store;
        this.metrics = metrics;
        this.gracePeriod = gracePeriod;
        this.clock = clock;
    }

    /**
     * Asks about every batch whose grace period has passed and applies what comes back.
     *
     * <p>The cutoff is read from the clock at the moment the run asks, not carried over from the
     * run before it: the grace period is how long a batch may wait for its event, and a cutoff that
     * had aged with the previous run's duration would ask about a batch that was still inside it.
     *
     * <p>Every batch is its own attempt. A batch that could not be asked about leaves the ones after
     * it - which the read returns oldest first, so they are the ones a Youth Offending Team has been
     * waiting longest for - to be asked about anyway.
     *
     * @return how many batches this run completed rather than the topic: the answers it fetched and
     *     the silences it timed out, which is the same set of batches
     *     {@code register_batch.completed_by} names RECONCILER, for the run report and the
     *     {@code reconciled} counter
     */
    public int reconcile() {
        final Instant now = clock.instant();
        final Instant cutoff = now.minus(gracePeriod);

        final List<RegisterBatch> overdue = batches.generatingSince(cutoff);
        metrics.oldestGeneratingAge(oldestOf(overdue, now, RegisterBatch::requestedAt));

        final List<RegisterBatch> stalled = batches.pendingSince(cutoff);
        metrics.oldestPendingAge(oldestOf(stalled, now, RegisterBatch::assembledAt));

        final List<RegisterBatch> parked = batches.generatedSince(cutoff);
        metrics.oldestGeneratedAge(oldestOf(parked, now, RegisterBatch::generatedAt));
        report(parked);

        return settle(overdue, TIMED_OUT) + settle(stalled, NEVER_REQUESTED);
    }

    /**
     * Names the batches that hold a document nobody was told about, and settles none of them.
     *
     * <p>Reported rather than ended, which is the difference between this read and the two above
     * it. Those two are about a render that may never have happened; this one is about a document
     * that certainly did, so there is nothing to ask systemdocgenerator and nothing to fail - the
     * batch is owed its e-mails, and {@code RegisterNotifierService.resendFailed} and
     * {@code notify-register --batch} are what owe them. Both are re-entrant, so the recovery is
     * the operator's to start and not this pass's to guess at; failing the batch here would throw
     * away a document that exists.
     *
     * <p>The line carries the identities and the count, which is all a parked batch is: how many
     * teams it is owed by is on its own rows.
     *
     * @param parked the batches this pass read, oldest first
     */
    private static void report(final List<RegisterBatch> parked) {
        if (!parked.isEmpty()) {
            LOG.warn("{} batches hold a document nobody has been told about, the oldest since {}; "
                    + "each is owed its e-mails and reaches them through a resend rather than "
                    + "through this pass. oldest={}", parked.size(),
                    parked.getFirst().generatedAt(), parked.getFirst().batchId());
        }
    }

    /**
     * Asks about every batch of one read and counts the ones this pass settled.
     *
     * <p>Every batch is its own attempt, and the two reads are the same attempt made about two
     * states: what systemdocgenerator says is applied through the sink either way, and the reads
     * differ only in what an unknown payload means, which is why that ending is the argument.
     *
     * @param overdue the batches this pass read, oldest first
     * @param silence what a batch systemdocgenerator has no record of at all is ended as
     * @return how many of them this pass completed
     */
    private int settle(final List<RegisterBatch> overdue, final Ending silence) {
        int completed = 0;
        for (final RegisterBatch batch : overdue) {
            if (reconcileOne(batch, silence)) {
                metrics.reconciled();
                completed++;
            }
        }
        return completed;
    }

    /**
     * The schedule's own pass, every grace period, under a lock of its own.
     *
     * <p><strong>{@code void}, and that is ShedLock's rule rather than a preference.</strong> Its
     * interceptor refuses to lock a method returning a primitive - {@code
     * LockingNotSupportedException}, raised on every call through the proxy, the run's own
     * included - so the annotations cannot live on {@link #reconcile()}. A schedule has nobody to
     * return a count to in any case: the count is for the run report, and the run asks for it
     * directly.
     *
     * <p>The cadence is the grace period, written as the same property key so a deployment that
     * lengthens the one lengthens the other; the first pass waits one interval, because a pod that
     * has only just started has a database that may not be migrated yet and a batch that became
     * overdue during the restart is overdue for a while longer.
     */
    @Scheduled(initialDelayString = GRACE_PERIOD, fixedDelayString = GRACE_PERIOD)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    public void reconcileScheduled() {
        reconcile();
    }

    /**
     * How long the batch that has been waiting longest has been waiting.
     *
     * <p>Taken off the read this pass already made, rather than from a second query: the overdue
     * batches come back oldest first, so the first of them is the answer and a batch still inside
     * its grace has nothing to report yet - nothing has gone wrong with it.
     *
     * <p>Zero where none is overdue, which is what brings the gauge back down: a reading that only
     * ever moved up would need a batch to fail before it could fall.
     *
     * @param overdue the batches this pass read, oldest first
     * @param now     the instant the pass was made at
     * @param since   the stamp the age is measured from, which is the one the read was made against
     * @return the age of the oldest, or {@link Duration#ZERO} where there is none
     */
    private static Duration oldestOf(final List<RegisterBatch> overdue, final Instant now,
            final Function<RegisterBatch, Instant> since) {

        return overdue.stream()
                .map(since)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .map(oldest -> Duration.between(oldest, now))
                .orElse(Duration.ZERO);
    }

    /**
     * Asks about one batch, exactly once, and applies whatever that produced.
     *
     * <p>A query that could not be answered is not a generation that failed. systemdocgenerator
     * being unreachable says nothing about the render, so the batch keeps its grace and is asked
     * again by the next run rather than being failed on the strength of an outage here; nothing is
     * counted, because nothing was learned and nothing was decided. The exception is reported with
     * its cause rather than dropped - it is the reading that says the query API, and not the
     * broker, is what tonight's stuck batches are waiting on.
     *
     * @param batch   the overdue batch, as the read returned it
     * @param silence what this batch is ended as if systemdocgenerator has no record of its payload
     * @return whether this batch was completed by the reconciler
     */
    private boolean reconcileOne(final RegisterBatch batch, final Ending silence) {
        boolean completed;
        try {
            final Optional<DocumentStatus> answer =
                    renderer.query(batch.payloadFileId(), CallerIdentity.SYSTEM);
            completed = answer.map(status -> apply(batch, status))
                    .orElseGet(() -> end(batch, silence));
        } catch (GenerationFailedException e) {
            LOG.warn("Batch {} is past its grace period and systemdocgenerator could not answer "
                    + "what became of its payload, so it stays GENERATING and is asked again on "
                    + "the next run rather than failed on the strength of this.", batch.batchId(),
                    e);
            completed = false;
        }
        return completed;
    }

    /**
     * Reads the answer, whose components are what it says rather than a state it names.
     *
     * <p>A document is an id and the instant it was generated together: an id on its own is a
     * record systemdocgenerator opened and has not filled in, and taking it for a document would
     * send a Youth Offending Team an attachment nothing had rendered yet. A refusal is the instant
     * it failed, with or without words about why - the words are for support and the batch's own
     * reason is the bounded GENERATION_FAILED the sink applies. An answer that says neither is a
     * payload systemdocgenerator has and is still rendering.
     *
     * <p><strong>An answer is not the same silence as no answer.</strong> This ending is
     * {@link #TIMED_OUT} whichever read found the batch, and that is the whole difference between
     * being answered about and not being known: systemdocgenerator has replied about this payload,
     * so the request did reach the renderer and the renderer is what has not finished - which is
     * true of a stale PENDING batch whose {@code markRequested} was lost exactly as it is of a
     * GENERATING one. {@link #NEVER_REQUESTED} belongs to the other case only, the query that finds
     * no record of the payload at all, and it is applied where that case is: on the empty answer in
     * {@link #reconcileOne}.
     *
     * @param batch  the overdue batch the answer is about
     * @param status what systemdocgenerator said became of its payload
     * @return whether this batch was completed by the reconciler
     */
    private boolean apply(final RegisterBatch batch, final DocumentStatus status) {
        final boolean completed;
        if (status.documentFileServiceId() != null && status.generatedTime() != null) {
            LOG.info("Batch {} has a document systemdocgenerator generated and no event delivered, "
                    + "so it is completed here rather than by the topic.", batch.batchId());
            sink.documentAvailable(batch.batchId(), batch.payloadFileId(),
                    status.documentFileServiceId(), status.generatedTime(), CompletedBy.RECONCILER);
            completed = true;
        } else if (status.failedTime() != null) {
            LOG.info("Batch {} was refused by systemdocgenerator and no event delivered that "
                    + "either, so it is failed here; its words are carried to sdg_reason.",
                    batch.batchId());
            LOG.debug("systemdocgenerator refused batch {}: {}", batch.batchId(), status.reason());
            sink.generationFailed(batch.batchId(), batch.payloadFileId(), status.reason(),
                    status.failedTime(), CompletedBy.RECONCILER);
            completed = true;
        } else {
            completed = end(batch, TIMED_OUT);
        }
        return completed;
    }

    /**
     * Ends a batch nothing can be learned about.
     *
     * <p>Through the store rather than the sink, because it is this service's own verdict about a
     * render nobody answered for and not an answer anybody gave, with no words from
     * systemdocgenerator because it said none.
     *
     * @param batch  the overdue batch neither the topic nor the query API has an outcome for
     * @param ending the bounded reason and attribution its own read decided on
     * @return {@code true}, because a batch given up on is a completion this run made rather than
     *     one the topic delivered
     */
    private boolean end(final RegisterBatch batch, final Ending ending) {
        LOG.warn("Batch {} is past its grace period and systemdocgenerator has no outcome to give "
                + "for it, so it is failed {} rather than left waiting.", batch.batchId(),
                ending.reason());
        store.markFailed(batch.batchId(), ending.reason(), null, ending.completedBy());
        return true;
    }

    /**
     * How a batch the topic and the query API both said nothing about is ended, which depends on
     * what the query answered rather than on which read found the batch.
     *
     * <p>A batch systemdocgenerator answered about without naming a document or a refusal was asked
     * for and accepted, so the silence is systemdocgenerator's and the row names RECONCILER, which
     * is what {@code BatchFailureReason.isGeneratorAttributed()} requires of GENERATION_TIMED_OUT.
     * A stale PENDING batch systemdocgenerator has no record of at all is the other case: it was
     * never recorded as requested and no payload is held under its id, so the silence says the
     * request never arrived, which is this service's own RENDER_REQUEST_FAILED verdict and names no
     * mechanism, because nobody outside this service answered for it.
     *
     * @param reason      the bounded reason the batch is failed under
     * @param completedBy the mechanism that learned the outcome, or {@code null} where this is this
     *                    service's own verdict
     */
    private record Ending(BatchFailureReason reason, CompletedBy completedBy) {
    }
}
