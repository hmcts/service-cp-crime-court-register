package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p>A batch the query has nothing to say about is failed GENERATION_TIMED_OUT rather than asked
 * again. Two systems have now been given the chance to report an outcome and neither has one, and a
 * batch that is retried indefinitely is a night's registers nobody is told are missing. That ending
 * goes through {@link RegisterStore#markFailed} rather than through the sink, because it is this
 * service's own verdict about a silence and not an answer anybody gave - and it still names
 * RECONCILER, which is what {@code BatchFailureReason.isGeneratorAttributed()} requires of it.
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
 * <p>Every pass also publishes {@code courtregister_oldest_generating_age} from the read it has just
 * made: how long the batch that has been waiting longest for its document has been waiting. It is
 * the reading a nightly flow cannot be understood without between runs, and until this schedule
 * existed there was nowhere for it to be taken.
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
    @Scheduled(initialDelayString = GRACE_PERIOD, fixedDelayString = GRACE_PERIOD)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    public int reconcile() {
        final Instant now = clock.instant();
        final List<RegisterBatch> overdue = batches.generatingSince(now.minus(gracePeriod));
        metrics.oldestGeneratingAge(oldestOf(overdue, now));

        int completed = 0;
        for (final RegisterBatch batch : overdue) {
            if (reconcileOne(batch)) {
                metrics.reconciled();
                completed++;
            }
        }
        return completed;
    }

    /**
     * The schedule's own entry point.
     *
     * <p><strong>Seam.</strong> Not yet the schedule's, and not yet doing anything, which is what
     * the three cases in {@code GenerationReconcilerTest.ItsOwnSchedule} fail on.
     */
    public void reconcileScheduled() {
        throw new UnsupportedOperationException("the schedule's own pass is not wired yet");
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
     * @return the age of the oldest, or {@link Duration#ZERO} where there is none
     */
    private static Duration oldestOf(final List<RegisterBatch> overdue, final Instant now) {
        return overdue.stream()
                .map(RegisterBatch::requestedAt)
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
     * @param batch the overdue batch, as the read returned it
     * @return whether this batch was completed by the reconciler
     */
    private boolean reconcileOne(final RegisterBatch batch) {
        boolean completed;
        try {
            final Optional<DocumentStatus> answer =
                    renderer.query(batch.payloadFileId(), CallerIdentity.SYSTEM);
            completed = answer.map(status -> apply(batch, status))
                    .orElseGet(() -> timedOut(batch));
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
     * reason is the bounded GENERATION_FAILED the sink applies. An answer that says neither is the
     * renderer having nothing to say, which is the silence below.
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
            completed = timedOut(batch);
        }
        return completed;
    }

    /**
     * Ends a batch nothing can be learned about.
     *
     * <p>Through the store rather than the sink, because it is this service's own verdict about a
     * render nobody answered for and not an answer anybody gave; under GENERATION_TIMED_OUT, with
     * no words from systemdocgenerator because it said none, and naming RECONCILER, which is what
     * that reason requires of the row.
     *
     * @param batch the overdue batch neither the topic nor the query API has an outcome for
     * @return {@code true}, because a batch given up on is a completion this run made rather than
     *     one the topic delivered, and is one of the rows {@code completed_by} names RECONCILER
     */
    private boolean timedOut(final RegisterBatch batch) {
        LOG.warn("Batch {} is past its grace period, the topic never carried an outcome for it and "
                + "systemdocgenerator has none to give, so it is failed GENERATION_TIMED_OUT "
                + "rather than left waiting.", batch.batchId());
        store.markFailed(batch.batchId(), BatchFailureReason.GENERATION_TIMED_OUT, null,
                CompletedBy.RECONCILER);
        return true;
    }
}
