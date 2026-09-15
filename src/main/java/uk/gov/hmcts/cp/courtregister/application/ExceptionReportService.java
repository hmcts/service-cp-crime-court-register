package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.ReportWindow;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * What went wrong, asked of the store and answered as one report.
 *
 * <p>The seam the suites of T029 and T030 are written against. The reads, the folding and the
 * delivery land at T034.
 *
 * @param requests              the intake half's processed log
 * @param batches               the downstream half's batches
 * @param notifications         the e-mails the batches were told by
 * @param registers             the recorded registers, through the store's own port
 * @param requestTerminalWithin how long a request may stay unfinished before it is late
 * @param batchGeneratedWithin  how long a batch may stay unrendered before it is late
 * @param notifiedWithin        how long a rendered batch may go untold before it is late
 * @param generationCron        the generation schedule, which decides what "left behind" means
 * @param generationZone        the zone that schedule is read in
 * @param metrics               the instrument facade
 * @param clock                 the one clock the snapshot is taken from
 */
public class ExceptionReportService {

    private final ProcessedRequestRepository requests;

    private final RegisterBatchRepository batches;

    private final RegisterNotificationRepository notifications;

    private final RegisterStore registers;

    private final Duration requestTerminalWithin;

    private final Duration batchGeneratedWithin;

    private final Duration notifiedWithin;

    private final String generationCron;

    private final String generationZone;

    private final ProcessingMetrics metrics;

    private final Clock clock;

    /**
     * Holds the four readers, the three limits, the generation schedule and the clock.
     *
     * @param requests              the intake half's processed log
     * @param batches               the downstream half's batches
     * @param notifications         the e-mails the batches were told by
     * @param registers             the recorded registers, through the store's own port
     * @param requestTerminalWithin how long a request may stay unfinished before it is late
     * @param batchGeneratedWithin  how long a batch may stay unrendered before it is late
     * @param notifiedWithin        how long a rendered batch may go untold before it is late
     * @param generationCron        the generation schedule, which decides what "left behind" means
     * @param generationZone        the zone that schedule is read in
     * @param metrics               the instrument facade
     * @param clock                 the one clock the snapshot is taken from
     */
    public ExceptionReportService(
            final ProcessedRequestRepository requests,
            final RegisterBatchRepository batches,
            final RegisterNotificationRepository notifications,
            final RegisterStore registers,
            final Duration requestTerminalWithin,
            final Duration batchGeneratedWithin,
            final Duration notifiedWithin,
            final String generationCron,
            final String generationZone,
            final ProcessingMetrics metrics,
            final Clock clock) {
        this.requests = requests;
        this.batches = batches;
        this.notifications = notifications;
        this.registers = registers;
        this.requestTerminalWithin = requestTerminalWithin;
        this.batchGeneratedWithin = batchGeneratedWithin;
        this.notifiedWithin = notifiedWithin;
        this.generationCron = generationCron;
        this.generationZone = generationZone;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Everything wrong at one moment, over one window.
     *
     * @param window what was asked for
     * @param runId  the correlation the caller opened
     * @return the report
     */
    public ExceptionReport build(final ReportWindow window, final String runId) {
        return new ExceptionReport(runId, window, clock.instant(), List.of());
    }

    /**
     * Hands one report to the sinks the caller chose, and says how each of them went.
     *
     * @param report the report to deliver
     * @param sinks  the sinks this caller delivers to
     * @return one outcome per sink, in the order they were asked
     */
    public List<DeliveryOutcome> deliver(final ExceptionReport report,
            final Collection<ExceptionReportSink> sinks) {
        return List.of();
    }
}
