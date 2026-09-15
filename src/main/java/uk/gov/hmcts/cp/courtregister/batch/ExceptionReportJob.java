package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.util.List;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;

/**
 * The 07:00 run: read the window, build the report, hand it to every sink, say what happened.
 *
 * <p>Seam for T047.
 */
public class ExceptionReportJob {

    private final ExceptionReportService reporting;

    private final List<ExceptionReportSink> sinks;

    private final String cron;

    private final String zone;

    private final ProcessingMetrics metrics;

    private final Clock clock;

    /**
     * Holds the report, the sinks it goes to, the schedule its window is read from and the clock.
     *
     * @param reporting the report, built and delivered through it
     * @param sinks     every sink on this context, asked in order
     * @param cron      this job's own schedule, which is what the window is measured back through
     * @param zone      the zone that schedule is read in
     * @param metrics   where the run's outcome is counted
     * @param clock     this pod's reading of now
     */
    public ExceptionReportJob(final ExceptionReportService reporting,
            final List<ExceptionReportSink> sinks, final String cron, final String zone,
            final ProcessingMetrics metrics, final Clock clock) {
        this.reporting = reporting;
        this.sinks = List.copyOf(sinks);
        this.cron = cron;
        this.zone = zone;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The scheduled entry point, which answers nothing because a schedule has nobody to tell. */
    public void run() {
        report();
    }

    /**
     * The run itself, which the seam cannot yet make.
     *
     * @return the report this morning's run built
     */
    public ExceptionReport report() {
        throw new UnsupportedOperationException(
                "the 07:00 run is T047's; this seam exists so its suite compiles");
    }
}
