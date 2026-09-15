package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchException;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryStatus;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionEntry;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionKind;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.FailedNotification;
import uk.gov.hmcts.cp.courtregister.domain.LastScheduledRun;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.courtregister.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.courtregister.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.courtregister.domain.ReportSinkName;
import uk.gov.hmcts.cp.courtregister.domain.ReportWindow;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * What went wrong, asked of the store and answered as one report.
 *
 * <p>Eight reads and no writes (FR-014). Three of them are bounded by the window, so a report is a
 * statement about a period rather than a growing ledger; the two late kinds deliberately are not,
 * because a request stuck since Friday is late on Monday morning whether or not it arrived over the
 * weekend, and a window filter would make the longest-running problem the first one to disappear.
 *
 * <p><strong>Every age is the one its own statement computed.</strong> Each of the four projections
 * carries an {@code age_seconds} measured by the database from the column that stage is timed off,
 * and nothing here subtracts a stored timestamp from a reading of this JVM's clock - that is the
 * cross-clock comparison V1's single-time-authority rule forbids. The clock is read once, for the
 * snapshot and for the three cut-offs the reads are asked about, which are boundaries this caller
 * chose rather than comparisons between two clocks.
 *
 * <p>The <strong>run id is an argument</strong> rather than something this class reads for itself.
 * Whoever opened the correlation - the 07:00 job, or the operations command - knows it, so
 * {@code build(window, runId)} takes it and the application layer imports no MDC. That is what
 * makes FR-011 true on the command's path, where nothing else would have opened a run at all.
 *
 * <p>Delivery is the other half, and it is deliberately not the same method. The run delivers to
 * every sink on the context; the command delivers to the log sink always and the e-mail sink only
 * under {@code --email}. One method that fanned out to whatever was wired could not express the
 * second without this class learning what a command-line flag is.
 */
public class ExceptionReportService {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionReportService.class);

    /**
     * The four stages a BATCH_LATE entry can be stuck at, as bounded codes.
     *
     * <p>A closed set of four, written here because the kind is one and the stage is what an
     * operator has to act on: a batch waiting to be asked about is a different morning's work from
     * one systemdocgenerator was asked about and never answered. They are codes rather than
     * sentences for the reason every reason on this report is - {@code reason} is a parsed slot,
     * and what goes in one comes from a vocabulary.
     */
    private static final String AWAITING_BATCH = "awaiting-batch";

    private static final String AWAITING_RENDER_REQUEST = "awaiting-render-request";

    private static final String AWAITING_RENDER = "awaiting-render";

    private static final String AWAITING_NOTIFICATION = "awaiting-notification";

    /** What a register that no batch was ever assembled for is, as its own state's name. */
    private static final String RECORDED = "RECORDED";

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
     * Everything wrong at one moment, over one window, oldest first.
     *
     * @param window what was asked for
     * @param runId  the correlation the caller opened
     * @return the report, stamped with the run id it was given
     */
    public ExceptionReport build(final ReportWindow window, final String runId) {
        final Instant snapshotAt = clock.instant();
        final List<ExceptionEntry> entries = new ArrayList<>();

        for (final ProcessedRequestSummary failed : requests.failedSince(window.from())) {
            entries.add(intake(ExceptionKind.REQUEST_FAILED, failed, failed.failureReason()));
        }
        for (final ProcessedRequestSummary late
                : requests.nonTerminalOlderThan(snapshotAt.minus(requestTerminalWithin))) {
            entries.add(intake(ExceptionKind.REQUEST_LATE, late, null));
        }
        for (final BatchException late : batches.latePending(snapshotAt.minus(batchGeneratedWithin))) {
            entries.add(batch(ExceptionKind.BATCH_LATE, late, AWAITING_RENDER_REQUEST));
        }
        for (final BatchException late
                : batches.lateGenerating(snapshotAt.minus(batchGeneratedWithin))) {
            entries.add(batch(ExceptionKind.BATCH_LATE, late, AWAITING_RENDER));
        }
        for (final BatchException late : batches.lateGenerated(snapshotAt.minus(notifiedWithin))) {
            entries.add(batch(ExceptionKind.BATCH_LATE, late, AWAITING_NOTIFICATION));
        }
        for (final BatchException dead : batches.failedSince(window.from())) {
            entries.add(batch(ExceptionKind.BATCH_FAILED, dead, nameOf(dead)));
        }
        for (final RecordedRegisterSummary stranded : registers.recordedUnbatchedBefore(
                LastScheduledRun.before(generationCron, generationZone, snapshotAt))) {
            entries.add(unbatched(stranded));
        }
        for (final FailedNotification refused : notifications.failedSince(window.from())) {
            entries.add(notification(refused));
        }

        entries.sort(Comparator.comparingLong(ExceptionEntry::ageSeconds).reversed());
        final ExceptionReport report =
                new ExceptionReport(runId, window, snapshotAt, entries);
        report.counts().forEach(metrics::exceptionsReported);
        return report;
    }

    /**
     * Hands one report to the sinks the caller chose, and says how each of them went.
     *
     * <p>Every sink is asked, whatever the one before it answered: the two outputs answer the same
     * question to two audiences and must not be able to take each other down (FR-007). Nothing is
     * retried - a report is regenerated in full by the next run or on demand, so a retry would
     * re-send a list support is about to receive again anyway - and nothing leaves, because a throw
     * here would lose the outcomes of every sink already asked.
     *
     * @param report the report to deliver
     * @param sinks  the sinks this caller delivers to
     * @return one outcome per sink, in the order they were asked
     */
    public List<DeliveryOutcome> deliver(final ExceptionReport report,
            final Collection<ExceptionReportSink> sinks) {
        final List<DeliveryOutcome> outcomes = new ArrayList<>(sinks.size());
        for (final ExceptionReportSink sink : sinks) {
            final DeliveryOutcome outcome = askedOf(sink, report);
            metrics.exceptionReportDelivery(outcome.sink(), outcome.status());
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    /**
     * One sink's answer, or the classification of a sink that could not give one.
     *
     * <p>The port's contract is that a sink answers rather than throws, so a throw reaching here is
     * a broken sink and not a refused delivery. It is caught only to classify: the outcome is
     * recorded against the sink's own name with the bounded code for its write having failed, and
     * the line names the caught failure by <strong>class</strong>, because its message belongs to
     * whatever library raised it and is exactly where an address or a connection string turns up.
     *
     * @param sink   the sink to ask
     * @param report the report to hand it
     * @return how it went
     */
    // PMD.AvoidCatchingGenericException and PMD.OnlyOneReturn: as stated in this method's javadoc
    // - the port's contract is that a sink answers rather than throws, so a throw reaching here is
    // any broken sink and the catch cannot be narrower than that; and both exits answer the same
    // question, one in the sink's own words and one in this service's.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private DeliveryOutcome askedOf(final ExceptionReportSink sink, final ExceptionReport report) {
        try {
            return sink.deliver(report);
        } catch (RuntimeException broken) {
            LOG.warn("The exception report could not be handed to the {} sink, which broke with a "
                            + "{}. The report stands and every other sink is still asked.",
                    sink.name(), broken.getClass().getName());
            return new DeliveryOutcome(sink.name(), DeliveryStatus.NOT_DELIVERED,
                    brokenSinkReason(sink.name()), 0, 0);
        }
    }

    /**
     * The bounded code for a sink that broke rather than answered, one per sink.
     *
     * <p>A switch expression so that a third sink cannot be added without deciding what its own
     * breaking is called.
     *
     * @param sink which sink broke
     * @return the bounded reason
     */
    private static ReportDeliveryReason brokenSinkReason(final ReportSinkName sink) {
        return switch (sink) {
            case LOG -> ReportDeliveryReason.LOG_WRITE_FAILED;
            case EMAIL -> ReportDeliveryReason.SEND_FAILED;
        };
    }

    /**
     * One of the two intake kinds, which carry the request's own identifiers and nothing else.
     *
     * @param kind    which of the two
     * @param summary the projection the statement answered
     * @param reason  the bounded reason, where the kind has one
     * @return the entry
     */
    private static ExceptionEntry intake(final ExceptionKind kind,
            final ProcessedRequestSummary summary, final String reason) {
        return new ExceptionEntry(kind, summary.source(), summary.requestId(), summary.hearingId(),
                summary.hearingDay(), null, null, null, null, summary.status().name(),
                summary.attempts(), reason, summary.ageSeconds());
    }

    /**
     * A batch, late at one of its three stages or dead at the end of them.
     *
     * @param kind   which of the two
     * @param batch  the projection the statement answered
     * @param reason the overdue stage, or the batch's own bounded failure reason
     * @return the entry
     */
    private static ExceptionEntry batch(final ExceptionKind kind, final BatchException batch,
            final String reason) {
        return new ExceptionEntry(kind, null, null, null, null, batch.batchId(), null,
                batch.courtCentreId(), batch.registerDate(), batch.status().name(), null, reason,
                batch.ageSeconds());
    }

    /**
     * A recorded register the most recent scheduled generation run left where it was.
     *
     * <p>It carries no batch id, because the absence of one is what is wrong with it.
     *
     * @param register the projection the statement answered
     * @return the entry
     */
    private static ExceptionEntry unbatched(final RecordedRegisterSummary register) {
        return new ExceptionEntry(ExceptionKind.BATCH_LATE, null, null, register.hearingId(), null,
                null, null, register.courtCentreId(), register.registerDate(), RECORDED, null,
                AWAITING_BATCH, register.ageSeconds());
    }

    /**
     * One Youth Offending Team's e-mail that was refused or never answered.
     *
     * <p>Named by its notification id and its batch, and by no address: no read this feature makes
     * selects one, so there is nothing here to mask and nothing to forget to mask.
     *
     * @param refused the projection the statement answered
     * @return the entry
     */
    private static ExceptionEntry notification(final FailedNotification refused) {
        return new ExceptionEntry(ExceptionKind.NOTIFICATION_FAILED, null, null, null, null,
                refused.batchId(), refused.notificationId(), refused.courtCentreId(),
                refused.registerDate(), refused.status().name(), refused.attempts(),
                responseCodeOf(refused), refused.ageSeconds());
    }

    /** The response code as its bounded three digits, or nothing where nobody answered. */
    private static String responseCodeOf(final FailedNotification refused) {
        return refused.responseCode() == null ? null : String.valueOf(refused.responseCode());
    }

    /** A dead batch's own bounded failure reason, and never systemdocgenerator's words. */
    private static String nameOf(final BatchException dead) {
        return dead.failureReason() == null ? null : dead.failureReason().name();
    }
}
