package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;

/**
 * The two intake gauges, refreshed on a schedule of their own in every JVM.
 *
 * <p>T027 implements it. This is the seam its tests are written against.
 */
public class IntakeAgeSweep {

    private final ProcessedRequestRepository requests;
    private final ProcessingMetrics metrics;
    private final Duration requestTerminalWithin;
    private final Clock clock;

    /**
     * Builds the sweep over the processed log it reads and the instruments it publishes.
     *
     * @param requests              the processed log, read for the oldest unfinished request and
     *                              for how many are over the threshold
     * @param metrics               where the two gauges and the absorbed-failure counter live
     * @param requestTerminalWithin how long a request may stay unfinished before it is over the
     *                              threshold
     * @param clock                 the clock the cut-off is measured back from
     */
    public IntakeAgeSweep(final ProcessedRequestRepository requests,
            final ProcessingMetrics metrics, final Duration requestTerminalWithin,
            final Clock clock) {
        this.requests = requests;
        this.metrics = metrics;
        this.requestTerminalWithin = requestTerminalWithin;
        this.clock = clock;
    }

    /**
     * The schedule's entry point.
     */
    public void sweepScheduled() {
        // T027 opens the correlation and delegates to the body below.
    }

    /**
     * The refresh itself, callable without the schedule.
     */
    public void sweep() {
        throw new UnsupportedOperationException("T027 implements the sweep");
    }
}
