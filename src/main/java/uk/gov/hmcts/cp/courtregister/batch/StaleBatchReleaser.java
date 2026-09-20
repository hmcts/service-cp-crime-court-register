package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.application.ReleasedBatch;
import uk.gov.hmcts.cp.courtregister.application.StaleReleaseOutcome;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;

/**
 * The run's first act: giving up on the batches the night before did not finish.
 *
 * <p>A batch still awaiting its render when the next run begins, and awaiting it for longer than
 * the minimum age, is failed under {@code NOT_COMPLETED_BY_NEXT_RUN} and its registers are given
 * back, so this same run's assembly puts them in a batch tonight and the court centre gets its
 * document tonight. Nothing is asked of systemdocgenerator: an outcome that was lost is not an
 * outcome anybody can be asked for.
 *
 * <p>The pass holds a clock, the store port and the instruments, and nothing else - no driver, no
 * broker client, no HTTP client (design rules: the job is application code too). The decision it
 * makes is which two instants to ask about; the decision about any one batch is the store's, in one
 * fenced statement per batch.
 *
 * <p><strong>Nothing about one batch may end the night.</strong> The store isolates each batch and
 * reports the ones it could not release rather than raising them, so this pass counts those, says
 * each once at WARN by identity, and goes on: the run behind it has the rest of the country's
 * documents to make (FR-003a). A contended batch is stale still and untouched, so the next run
 * reaches it again, and the 07:00 report names its court centre day every morning meanwhile. What
 * does leave this pass is a store that went away - that is the run's own failure, reported on its
 * line and rethrown, exactly as every other read the run cannot make is.
 *
 * <p><strong>Two cutoffs, computed once per pass from this pass's own clock.</strong> PENDING and
 * GENERATING are <strong>one</strong> rule and not two: with nothing left to ask systemdocgenerator
 * there is no question that could tell them apart, and "it did not complete before the next run
 * began" is true of both - of the batch whose outcome was lost, and of the batch whose render
 * request was never recorded, including the one that never minted a payload at all, which the
 * retired reads excluded and left deferring its court centre day at every run for ever (FR-020).
 * GENERATED is on neither arm at any age: it holds a document somebody is owed e-mails about, and
 * failing it would throw that document away (FR-002).
 *
 * <p>A batch the schedule made is judged by the minimum age. A batch an operator asked for is
 * judged by the longer of that and the run's own lock duration, because a manual generation holds
 * no run lock and has the whole requesting deadline to work in (FR-017). Which cutoff a batch is
 * judged by is the store's to decide from {@code system_generated}, in the statement's own
 * predicate; both instants are computed here, once, so that every batch in one pass is judged
 * against the same moment.
 */
public class StaleBatchReleaser {

    private static final Logger LOG = LoggerFactory.getLogger(StaleBatchReleaser.class);

    /** The store, asked once per run for the fenced release of every stale batch. */
    private final RegisterStore store;

    /** Where the two released numbers and the contended one are counted. */
    private final GenerationMetrics metrics;

    /** How long a batch the schedule made may be in flight before a run gives up on it. */
    private final Duration staleAfter;

    /** How long the nightly run holds its lock, which is the grace an operator's batch gets. */
    private final Duration runLock;

    /** The clock both cutoffs are measured back from. */
    private final Clock clock;

    /**
     * Creates the pass over the store it releases through and the settings it measures by.
     *
     * @param store      the store, asked once per run for the fenced release of every stale batch
     * @param metrics    where the released and contended counts are recorded
     * @param staleAfter how long a batch the schedule made may be in flight before this pass gives
     *                   up on it
     * @param runLock    how long the nightly run holds its lock, which is the longer grace a batch
     *                   an operator asked for is given (FR-017)
     * @param clock      the clock both cutoffs are measured back from
     */
    public StaleBatchReleaser(final RegisterStore store, final GenerationMetrics metrics,
            final Duration staleAfter, final Duration runLock, final Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.staleAfter = staleAfter;
        this.runLock = runLock;
        this.clock = clock;
    }

    /**
     * Fails and releases every batch the run found still waiting, and says what that was.
     *
     * <p>One call, because the decision about any one batch is the store's: the staleness rule is
     * the write's own {@code WHERE} clause, so there is nothing for this pass to read first and
     * nothing for it to decide in between. A batch that stopped being stale between the question
     * and the write is simply not in the answer, which is a number here and not an error.
     *
     * <p>Under {@link RunCorrelation#under(java.util.function.Supplier)}, which adopts the run's
     * ambient id rather than minting a second one: a night that wrote itself down under two
     * correlations could not be read out of the estate's index as one thing. Where the pass is
     * driven on its own - a test, or any caller outside a run - it opens one of its own and removes
     * it again, because the scheduler's threads are pooled.
     *
     * @return what the pass released and what it could not release
     */
    public ReleaseTally releaseStale() {
        return RunCorrelation.under(this::release);
    }

    /**
     * The pass itself, under whatever correlation {@link #releaseStale()} settled on.
     *
     * @return what the pass released and what it could not release
     */
    private ReleaseTally release() {
        final Instant now = clock.instant();
        final StaleReleaseOutcome outcome = store.failAndReleaseStale(
                now.minus(staleAfter), now.minus(manualGrace()));

        int registers = 0;
        for (final ReleasedBatch released : outcome.released()) {
            registers += released.releasedRegisters();
            said(released);
        }
        for (final UUID contended : outcome.contended()) {
            saidContended(contended);
        }

        final ReleaseTally tally =
                new ReleaseTally(outcome.released().size(), registers, outcome.contended().size());
        metrics.staleBatchesReleased(tally.batches());
        metrics.staleRegistersReleased(tally.registers());
        metrics.staleBatchesContended(tally.contended());
        LOG.info("The stale-batch pass gave back what the night before had not finished, and the "
                + "run goes on to assemble it. released_batches={} released_registers={} "
                + "contended={}", tally.batches(), tally.registers(), tally.contended());
        return tally;
    }

    /**
     * The grace a batch an operator asked for is given, which is the longer of the two settings.
     *
     * <p><strong>The longer, and not the lock.</strong> A manual generation holds no run lock and
     * has the whole requesting deadline to ask for its renders, so a batch it assembled at 17:25 is
     * over the minimum age by the time the schedule fires at 18:00; failing it would orphan a
     * render that run is still making and refuse its own {@code markRequested} (FR-017). Taking the
     * lock alone would be the same rule stated wrong for a deployment that shortened it: an
     * operator's batch may never be given less grace than the schedule's own.
     *
     * @return the grace a batch this service's schedule did not make is given
     */
    private Duration manualGrace() {
        return staleAfter.compareTo(runLock) >= 0 ? staleAfter : runLock;
    }

    /**
     * One line about one batch the run gave up on.
     *
     * <p>Identities and counts, which is the whole of what a release is: the batch, the court
     * centre day it held and how many registers went back for tonight. Nothing here is a defendant,
     * a recipient or a word another system wrote, so the line is safe at INFO (constitution
     * Principle VII), and the run's own correlation is on it because
     * {@link #releaseStale()} opened or adopted one.
     *
     * @param released the batch, as the store answered with it
     */
    private static void said(final ReleasedBatch released) {
        LOG.info("Batch {} had not completed by the time this run began, so it is failed and its "
                + "{} registers go back to tonight's assembly. court_centre={} register_date={}",
                released.batchId(), released.releasedRegisters(), released.courtCentreId(),
                released.registerDate());
    }

    /**
     * One line about one batch nothing could be given back from.
     *
     * <p>At WARN and counted, because a path that leaves something undone moves a counter and "it
     * is in the log index" is not an alerting surface. The batch is untouched and stale still, so
     * the next run reaches it again; the pass says so and carries on rather than ending a night
     * over one hearing the estate re-shared at the wrong moment (FR-003a).
     *
     * @param batchId the batch every attempt at was refused over
     */
    private static void saidContended(final UUID batchId) {
        LOG.warn("Batch {} could not be given back: every attempt at it lost the race for its "
                + "day's active register, so it is left exactly as it was found and the next run "
                + "reaches it again. The rest of this pass is unaffected.", batchId);
    }

    /**
     * What one pass did, in the three numbers a night is read by.
     *
     * <p><strong>Two of them are a diagnostic and not a third sum.</strong> The registers counted
     * here are re-batched by the same run and are therefore already inside that run's own row
     * totals; they are here because a batch is one document and one e-mail while a register is one
     * hearing's youth defendants, and neither number answers the other's question (FR-009).
     *
     * <p>The third is the batches the store could not release because every attempt lost the day's
     * active-register key. They are stale still and untouched, so the next run reaches them again
     * and the 07:00 report names their court centre days meanwhile; a run that meets one goes on to
     * assemble, because no single batch's outcome may end the run (FR-003a).
     *
     * @param batches   how many batches this pass failed and released
     * @param registers how many registers came back with them and are still the day's to render
     * @param contended how many batches the pass left exactly as it found them
     */
    public record ReleaseTally(int batches, int registers, int contended) {
    }
}
