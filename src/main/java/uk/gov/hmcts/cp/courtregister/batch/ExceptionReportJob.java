package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.config.ReportSchedulingConfig;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryStatus;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ReportSinkName;
import uk.gov.hmcts.cp.courtregister.domain.ReportWindow;

/**
 * The 07:00 run: one window, one report, every sink, one line.
 *
 * <p>Thin on purpose. The eight reads belong to {@link ExceptionReportService} and the writing
 * belongs to the sinks, so what is left here is the three things only a scheduled run can know:
 * which window it covers, what correlation the whole of it happened under, and how it went.
 *
 * <p><strong>The window needs no setting.</strong> It opens at the previous occurrence of this
 * job's own cron and closes at the moment the run fired, so a Monday run reads back to Friday and
 * every failure lands in exactly one report. A duration beside the schedule would be one fact
 * written twice, and the morning the two disagreed would be the morning something fell into the gap
 * between two windows - or was reported in both. The cron and the zone are handed in as well as
 * annotated on for that reason: they have to be the same two strings, and a window measured through
 * a different schedule than the one that fired would open on a morning that never ran.
 *
 * <p><strong>The line is written after every sink has returned.</strong> It carries
 * {@code delivered_log} and {@code delivered_email}, which are claims about deliveries that have
 * happened; written any earlier it would be reporting a delivery it had not yet observed, and the
 * morning that mattered would be the morning a sink was refusing. It is one flat {@code key=value}
 * line rather than a structured event, exactly as {@code RegisterGenerationJob.recorded} writes
 * {@code register_generation_run} after a night: one line per run, read by eye and by a
 * single-field filter, not a row a saved query aggregates.
 *
 * <p><strong>A read that cannot be taken leaves.</strong> This is the opposite branch from
 * {@link IntakeAgeSweep}'s, and the two are opposite deliberately. The sweep absorbs a failed read
 * because a gauge is telemetry <em>about</em> the service and because a fixed-delay schedule
 * cancels the task that throws; here the read <strong>is</strong> the report, there is a lock to
 * release, and {@code .claude/rules/design_rules.md} is explicit that every other refusal still
 * leaves. So the run counts itself failed, writes its line - a morning that produced no report has
 * to say so rather than say nothing - and rethrows.
 *
 * <p>It holds a clock, a lock, a report and the sinks, and no driver, broker or HTTP client. It
 * never reads the {@code CourtRegisterService} flag: the report reads and writes nothing the
 * cutover decides, so it is not on the lever's circuit and adds no second reader of it
 * (constitution Cutover Rule).
 */
public class ExceptionReportJob {

    /**
     * How long the lock is held for, named as the setting that says it rather than as a value.
     *
     * <p>The same shape {@code RegisterGenerationJob.LOCK_AT_MOST_FOR} has, and for the same
     * reason: ShedLock resolves a property placeholder here through the context's own value
     * resolver, so the duration is written in one place. {@code PropertiesValidator} refuses a
     * value that cannot cover the fixed run budget plus the scheduler-lock margin, so the two
     * cannot drift apart in a deployment either.
     */
    public static final String LOCK_AT_MOST_FOR = "${courtregister.report.lock-at-most-for}";

    /** The lock's own name, which is what makes it this report's lock and not the estate's. */
    public static final String LOCK_NAME = "exception-report";

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionReportJob.class);

    /** The one event name the run's line is indexed under. */
    private static final String RUN_EVENT = "exception_report_run";

    /** What the line calls a sink that took the report, and one that did not. */
    private static final String OK = "ok";

    private static final String NOT_OK = "failed";

    /**
     * And what it calls an output this deployment does not have.
     *
     * <p>Not {@code skipped}: that is the command's word for a sink it chose not to ask, and this
     * job asks every sink there is. A run that said {@code skipped} would be describing a decision
     * nobody made.
     */
    private static final String DISABLED = "disabled";

    /** What a run that could not build a report has to report about, which is nothing. */
    private static final int NOTHING_BUILT = 0;

    private final ExceptionReportService reporting;

    private final List<ExceptionReportSink> sinks;

    /**
     * Whether this deployment has an e-mail sink at all, asked once while the sinks are whole.
     *
     * <p>A sink names itself off the thing it delivers through, so the sink that has just broken is
     * exactly the one that may no longer be able to say who it is - which is why the service reads
     * a sink's name before asking it to deliver, and why this is read here rather than at the
     * moment the line is written. By then a sink may be in no state to answer, and the one question
     * that would leave this method is the one about a run that has already happened.
     */
    private final boolean emailSinkOnThisContext;

    private final String cron;

    private final String zone;

    private final ProcessingMetrics metrics;

    private final Clock clock;

    /**
     * Holds the report, the sinks it goes to, the schedule its window is measured through, and the
     * clock that measures it.
     *
     * @param reporting the report, built and delivered through it
     * @param sinks     every sink on this context, asked in the order they were contributed
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
        this.emailSinkOnThisContext = this.sinks.stream()
                .anyMatch(sink -> sink.name() == ReportSinkName.EMAIL);
        this.cron = cron;
        this.zone = zone;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The scheduled entry point, which answers nothing because a schedule has nobody to tell.
     *
     * <p>{@code void} over a body that returns the report, which is {@code reconcileScheduled}'s
     * shape and taken for its reasons: ShedLock's interceptor refuses to lock a method returning a
     * primitive, so an entry point that stays {@code void} cannot be broken later by a body whose
     * return type changes - and the body stays directly callable by a unit test without going
     * through the proxy at all.
     *
     * <p>{@link RunCorrelation} is what gives the run its {@code runId} and, as much to the point,
     * takes it away again: the scheduler's threads are pooled, and an id left behind would be
     * inherited by whatever ran next on that thread. The work is handed in rather than the scope
     * handed out precisely so there is no way to call this and forget the removal.
     */
    @Scheduled(cron = "${courtregister.report.cron}",
            zone = "${courtregister.report.zone}",
            scheduler = ReportSchedulingConfig.REPORT_SCHEDULER)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    public void run() {
        RunCorrelation.under(() -> {
            report(RunCorrelation.current());
        });
    }

    /**
     * The run itself, under the correlation {@link #run()} opened for it.
     *
     * <p>The correlation is read rather than minted here, and handed to the service as an argument:
     * whoever opened the run knows it, so the application layer never touches an MDC and the
     * command's path in Phase 6 works exactly the same way.
     *
     * <p><strong>The line and the counter are written in a {@code finally}.</strong> A morning
     * that produced no report has to say so, and saying so must not be something the failure has
     * to wait behind: written from inside the catch, the rethrow is queued after the write, and a
     * registry or an appender that refused would replace the store outage with its own complaint -
     * the one throwable nobody could act on standing in for the one they could. Here the failure is
     * already in flight when the line is written, and the recording is one statement in one place
     * rather than the same call spelled on two paths.
     *
     * @param runId the correlation whoever opened the run already has
     * @return the report this morning's run built
     */
    // PMD.AvoidCatchingGenericException: what has to be reported is a morning that produced no
    // report, whatever stopped it - a store outage arrives as the store's own unchecked type and a
    // projection that has drifted from its table as IllegalArgumentException - and a narrower catch
    // would leave the classes it does not name as the mornings that say nothing at all. Nothing is
    // swallowed: the same throwable leaves the method.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ExceptionReport report(final String runId) {
        final Instant startedAt = clock.instant();
        final ReportWindow window = ReportWindow.forScheduledRun(cron, zone, startedAt);
        int entries = NOTHING_BUILT;
        List<DeliveryOutcome> delivered = List.of();
        try {
            final ExceptionReport report = reporting.build(window, runId);
            entries = report.entries().size();
            delivered = reporting.deliver(report, sinks);
            return report;
        } catch (RuntimeException stopped) {
            // Reported and rethrown, not absorbed: the line the finally writes says the morning
            // produced nothing, and the throw is what releases the lock and makes the failure
            // visible to anything watching the schedule. The cause is named by class - its message
            // belongs to whatever raised it, and is exactly where a connection string turns up.
            LOG.error("The morning report could not be built, so the line beside this one "
                    + "describes a run that told nobody anything. cause={}",
                    stopped.getClass().getName());
            throw stopped;
        } finally {
            recorded(runId, window, entries, delivered, startedAt);
        }
    }

    /**
     * The one line a morning leaves behind, written last and counted with the same word.
     *
     * <p>Counts, a duration, two instants and bounded codes only: the exceptions are counted rather
     * than named here, each of them having had an event of its own from the log sink, and nothing a
     * register carries is anywhere near it (constitution Principle VII).
     *
     * <p>The outcome is counted on {@code courtregister_exception_report_runs_total} with the same
     * word the line carries, so a dashboard filtered on the counter and a query over these lines
     * partition a morning the same way. Every run counts, including - especially - the ones that
     * found nothing: without that, a report that found nothing and a report that never fired are
     * the same absence of a series, and the second is the failure this feature exists to reveal.
     *
     * @param runId     the correlation this run happened under, as the caller handed it in
     * @param window    the window that was read
     * @param entries   how many exceptions the report held, across all five kinds
     * @param delivered one outcome per sink asked, in the order they were asked
     * @param startedAt when the run opened its correlation
     */
    private void recorded(final String runId, final ReportWindow window, final int entries,
            final List<DeliveryOutcome> delivered, final Instant startedAt) {

        final ReportRunOutcome outcome = outcomeOf(delivered);
        metrics.exceptionReportRun(outcome);
        LOG.info("event={} run_id={} window_from={} window_to={} entries={} delivered_log={} "
                        + "delivered_email={} outcome={} duration_ms={}",
                RUN_EVENT, runId, window.from(), window.to(), entries,
                tookIt(ReportSinkName.LOG, delivered), emailTookIt(delivered),
                outcome.name().toLowerCase(Locale.ROOT),
                Duration.between(startedAt, clock.instant()).toMillis());
    }

    /**
     * How the run as a whole went, from what each sink answered.
     *
     * <p>Three states rather than two, because a report that reached one of its two audiences is
     * neither a success nor a silence: it is a resend (FR-007). A run that asked nobody - which is
     * a run that could not build a report at all - is {@code failed}, because nothing was told.
     *
     * @param delivered one outcome per sink asked
     * @return the bounded outcome
     */
    private static ReportRunOutcome outcomeOf(final List<DeliveryOutcome> delivered) {
        final long accepted = delivered.stream()
                .filter(outcome -> outcome.status() == DeliveryStatus.DELIVERED)
                .count();
        final ReportRunOutcome ended;
        if (accepted == 0) {
            ended = ReportRunOutcome.FAILED;
        } else if (accepted == delivered.size()) {
            ended = ReportRunOutcome.DELIVERED;
        } else {
            ended = ReportRunOutcome.PARTIAL;
        }
        return ended;
    }

    /**
     * Whether one named sink took the report.
     *
     * <p>A sink that partially delivered has not taken it: some recipients were told and the rest
     * are a resend, which is a thing to act on rather than a delivery to tick off. The run's own
     * outcome is where that nuance is expressed, as {@code partial}.
     *
     * @param sink      which sink the field is about
     * @param delivered one outcome per sink asked
     * @return {@code ok} or {@code failed}
     */
    private static String tookIt(final ReportSinkName sink,
            final List<DeliveryOutcome> delivered) {
        return delivered.stream()
                .filter(outcome -> outcome.sink() == sink)
                .anyMatch(outcome -> outcome.status() == DeliveryStatus.DELIVERED)
                ? OK : NOT_OK;
    }

    /**
     * The e-mail field, which has a third answer the log field does not.
     *
     * @param delivered one outcome per sink asked
     * @return {@code disabled} where this deployment has no e-mail sink, else how it went
     */
    private String emailTookIt(final List<DeliveryOutcome> delivered) {
        return emailSinkOnThisContext ? tookIt(ReportSinkName.EMAIL, delivered) : DISABLED;
    }
}
