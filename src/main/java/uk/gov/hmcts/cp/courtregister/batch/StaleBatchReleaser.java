package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
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
 * <p>This is the seam T011's cases are written against. T012 makes them green and T014 computes the
 * two cutoffs.
 */
public class StaleBatchReleaser {

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
        // T012 and T014 take these: the fields land with the code that reads them, so the seam
        // leaves no unread state behind if the pair is ever split.
    }

    /**
     * Fails and releases every batch the run found still waiting, and says what that was.
     *
     * @return what the pass released and what it could not release
     */
    public ReleaseTally releaseStale() {
        throw new UnsupportedOperationException(
                "T012 implements the stale-batch pass; this is its red run");
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
