package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;

/**
 * The three in-flight batch readings, refreshed on a fixed delay of their own in every JVM that
 * generates.
 *
 * <p>They are the state a nightly flow cannot be understood without between runs: how long the
 * batch that has been waiting longest for its document has been waiting, how long the oldest batch
 * that never reached the renderer has been stuck, and how long the oldest batch holding a document
 * nobody was told about has stood there. The retired grace-period pass took all three on its way
 * past, and 004 deletes it - so without this sweep they would have no publisher at all.
 *
 * <p><strong>A Micrometer gauge never decays.</strong> That is the whole reason this class exists
 * rather than the readings simply moving into the nightly run. A gauge whose publisher goes away
 * does not fall to zero; it holds the last value it was given, for ever - so deleting the timer
 * without replacing it would not merely make the three readings stale, it would freeze them at
 * whatever the last reconciliation saw and leave them looking live. Taken once a night instead,
 * they would be a daily sample of a thing that is asked about hourly.
 *
 * <p><strong>Nothing locks this</strong>, for the reason {@link IntakeAgeSweep} gives at length: a
 * gauge describes the JVM that publishes it, so a locked sweep would have one replica reading the
 * store while the others went on publishing whatever they last saw. Unlocked, every replica
 * refreshes its own three and an alert aggregates them across pods with {@code max()}.
 *
 * <p>It holds a repository, the instruments and a clock, and no store, no renderer, no broker
 * client and no lock. It settles nothing: the GENERATED-but-never-notified read is a reading and
 * not an ending, because that batch holds a document somebody is owed e-mails about and failing it
 * would throw the document away. It is named at WARN, and the resend surfaces are what owe it.
 *
 * <p>This is the seam T029's cases are written against. T030 makes them green and T031 puts it on
 * a schedule of its own.
 */
public class BatchAgeSweep {

    /**
     * Creates the sweep over the batches it reads and the instruments it publishes.
     *
     * @param batches the {@code register_batch} table, read for the oldest of each in-flight kind
     * @param metrics where the three gauges and the absorbed-failure counter live
     * @param clock   the clock the three ages are measured back from
     */
    public BatchAgeSweep(final RegisterBatchRepository batches, final GenerationMetrics metrics,
            final Clock clock) {
        // T030 takes these: the fields land with the code that reads them, so the seam leaves no
        // unread state behind if the pair is ever split.
    }

    /**
     * The schedule's own refresh, under a correlation of its own.
     *
     * <p>Opened rather than adopted, because a sweep on its own schedule is a unit of work in its
     * own right - unlike {@code StaleBatchReleaser}, which is reached from inside a run and carries
     * the run's id.
     */
    public void sweepScheduled() {
        RunCorrelation.under(this::sweep);
    }

    /** Reads the store once and republishes all three gauges from what it said. */
    public void sweep() {
        throw new UnsupportedOperationException(
                "T030 implements the batch-age sweep; this is its red run");
    }
}
