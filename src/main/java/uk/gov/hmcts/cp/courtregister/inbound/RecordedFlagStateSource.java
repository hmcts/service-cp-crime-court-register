package uk.gov.hmcts.cp.courtregister.inbound;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.domain.FlagStateSnapshot;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;

/**
 * What the intake side labels an arriving command with, without ever waiting to find out.
 *
 * <p>A recorded row says which implementation was meant to be generating when it arrived, because a
 * command still on the queue after the producer stopped publishing may belong to a hearing the
 * resumed legacy also processed, and batching it automatically would send one child's register
 * twice (research §12). The label is the flag as it was last read, and the reading is only allowed
 * to speak for a command that arrived inside its window.
 *
 * <p><strong>The read is never on the delivery's thread.</strong> A command arriving on a stale or
 * absent reading hands a refresh to the executor and is labelled from what is already known, which
 * for an absent reading is {@link RecordedFlagState#UNKNOWN}. The alternative is a register whose
 * recording waits on App Configuration: an outage there would then stall the queue behind a label,
 * and a register that has been built is worth more than the flag state it carries. The refresh is
 * for the commands behind this one.
 *
 * <p>It is a collaborator of {@link CourtRegisterMessageListener} rather than a part of it. The
 * listener's rule is one delivery in, exactly one settlement out; holding a flag reader, a clock and
 * an executor inside it would put a second concern on the class whose single concern is the whole of
 * its correctness (constitution Principle V).
 *
 * <p><strong>At most one read per window, and at most one in flight.</strong> A refresh is asked for
 * only where the reading in hand no longer stands for anything, so a stack taking a command every
 * few seconds asks App Configuration once a minute rather than once a command; and a second arrival
 * during a read that has not come back yet joins the first one's refresh rather than starting
 * another. The pair is what keeps a busy queue from turning a labelling rule into a load test of
 * somebody else's store.
 */
public class RecordedFlagStateSource {

    private static final Logger LOG = LoggerFactory.getLogger(RecordedFlagStateSource.class);

    /** The same reader the nightly job uses, which is what makes the flag one lever. */
    private final FeatureFlagReader reader;

    /** Where a refresh is handed to, so that no delivery thread is ever inside a read. */
    private final ScheduledExecutorService refreshes;

    /** What a reading's age is measured against. */
    private final Clock clock;

    /**
     * The reading every delivery is labelled from, and {@code null} until the first read returns.
     *
     * <p>Written by whichever thread the executor ran a refresh on and read by every delivery
     * thread, so the reference is atomic and the snapshot it holds is immutable: a delivery sees the
     * reading whole or sees the one before it, and never half of each.
     */
    private final AtomicReference<FlagStateSnapshot> reading = new AtomicReference<>();

    /** Whether a refresh is already on its way, so that arrivals behind it do not start another. */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    /**
     * Creates the source; the reading it hands out is its own and is shared by every delivery.
     *
     * @param reader   the flag reader the nightly job also uses
     * @param refreshes where a read is run, which is never the caller's thread
     * @param clock    what the age of a reading is measured against
     */
    public RecordedFlagStateSource(
            final FeatureFlagReader reader, final ScheduledExecutorService refreshes,
            final Clock clock) {
        this.reader = reader;
        this.refreshes = refreshes;
        this.clock = clock;
    }

    /**
     * Starts keeping the reading inside its window, for as long as this pod is consuming.
     *
     * <p>The seam T030's review-gate fix implements; it schedules nothing yet.
     */
    public void start() {
        // Implemented by the commit this red run guards.
    }

    /**
     * What a command arriving now is labelled with, answered from what is already known.
     *
     * <p>Answered before anything is asked of App Configuration, and the refresh this arrival may
     * schedule is for the commands behind it. The two orderings are not interchangeable: a label
     * read on this thread would put another service's timeout inside every recording.
     *
     * @return the state the last reading stands for now, or {@link RecordedFlagState#UNKNOWN} where
     *         there is no reading or it has aged out of its window
     */
    public RecordedFlagState current() {
        final FlagStateSnapshot known = reading.get();
        final RecordedFlagState state = known == null
                ? RecordedFlagState.UNKNOWN
                : known.stateFor(clock.instant());
        if (state == RecordedFlagState.UNKNOWN) {
            scheduleRefresh();
        }
        return state;
    }

    /**
     * Hands a read to the executor, unless one is already on its way.
     *
     * <p>A rejected hand-over is reported and no more: the executor refusing work is a pod that is
     * shutting down or an outage in this service's own plumbing, and neither is a reason to fail a
     * delivery that has a register to build. The next arrival asks again. What it must not do is
     * leave the in-flight flag raised over a refresh that never ran, which would silence every
     * later one and label every row {@code UNKNOWN} for the life of the pod.
     */
    private void scheduleRefresh() {
        if (refreshing.compareAndSet(false, true)) {
            try {
                refreshes.execute(this::refresh);
            } catch (RejectedExecutionException notTaken) {
                refreshing.set(false);
                LOG.warn("A flag refresh could not be handed over, so the commands behind this one "
                        + "are labelled from what is already known. type={}",
                        notTaken.getClass().getName());
            }
        }
    }

    /**
     * The read itself, on the executor's thread and never on a delivery's.
     *
     * <p>The reading is timed by the same clock the window is measured with, and it is timed when
     * the flag was asked rather than when the answer came back: a store that took two seconds
     * answered about a flag as it stood when it was asked, and stamping the return would let a slow
     * read quietly lengthen the window its answer speaks for.
     */
    private void refresh() {
        try {
            final Instant asked = clock.instant();
            reading.set(new FlagStateSnapshot(reader.read(), asked));
        } finally {
            refreshing.set(false);
        }
    }
}
