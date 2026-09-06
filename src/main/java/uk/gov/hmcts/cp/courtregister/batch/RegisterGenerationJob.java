package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.config.RunProgress;
import uk.gov.hmcts.cp.courtregister.domain.AssembledBatch;
import uk.gov.hmcts.cp.courtregister.domain.BatchAssembly;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.RunReport;

/**
 * The nightly run: flag, assemble, request, report.
 *
 * <p>The order is the whole of it. {@link FeatureFlagGate} is asked first and its answer ends the
 * run where it is not ON, because a run that assembled before it read the flag would have stamped
 * rows into batches the flag says this service may not generate (constitution Cutover Rule). Then
 * one batch at a time, sequentially, each through {@link RegisterGenerationService}; the run
 * deadline bounds the requesting and nothing else, because completion arrives on the public-event
 * topic long after the run has ended.
 *
 * <p><strong>Sequential is not an efficiency choice.</strong> Each request is a write to the shared
 * file service followed by a POST, and a run that fanned them out would make the run deadline
 * unenforceable and the file-service datasource's readiness reading meaningless. The deadline is
 * computed once, at the moment requesting begins, so a bound re-derived per batch cannot grow by
 * whatever the batch before it took; a batch it leaves no time for is left PENDING for the next run
 * rather than failed, because nothing has gone wrong with it.
 *
 * <p><strong>The reconciler runs whatever the night held.</strong> It is the safety net under the
 * public-event topic rather than a schedule of its own, so a run with nothing to assemble still
 * chases the batches an earlier run is waiting on - which is precisely the night on which the
 * subscription is most likely to be the thing that is broken.
 *
 * <p>Every run produces a {@link RunReport}, the skipped ones included: a report that only appeared
 * when work happened would make "the flag is off" and "the job did not fire" the same silence, and
 * before cutover the first of those is every night. The report is written as one structured line at
 * INFO and as the two gauges a nightly flow is read by between runs; every value in it is a count, a
 * duration or a bounded code, and no register, defendant or recipient reaches it (constitution
 * Principle VII).
 *
 * <p><strong>18:00 in the courts' own zone, and one of it.</strong> The schedule names the two
 * settings rather than repeating their values, so the hour this class runs at and the hour
 * {@code application.yaml} declares cannot drift apart, and {@link GenerationProperties#validate()}
 * is what holds the zone to {@code Europe/London}: the requirement is 18:00 wall clock in BST and
 * GMT alike, and the legacy fires in the scheduling JVM's default zone because its Quartz trigger
 * was built without one (research §4). The lock is the same argument about replicas rather than
 * hours: one replica today is a deployment fact and not a code guarantee, and the cost of being
 * wrong is two documents and two e-mails for every court centre in the country.
 */
public class RegisterGenerationJob {

    /**
     * How long the lock is held for, which has to outlast the requesting the deadline permits.
     *
     * <p>Ten minutes more than the sixty {@code courtregister.generation.run-deadline} ships with,
     * so a run still inside its hour cannot be joined by the replica that took the lock it had
     * already lost. It is a literal because an annotation's attribute has to be a constant, which
     * is also why a deployment that lengthens the run deadline has to lengthen this with it - the
     * two are one setting written twice, and the day they disagree is the day two pods generate the
     * same night.
     */
    public static final String LOCK_AT_MOST_FOR = "PT70M";

    /** The lock's own name, which is what makes it this job's lock and not the estate's. */
    public static final String LOCK_NAME = "register-generation";

    private static final Logger LOG = LoggerFactory.getLogger(RegisterGenerationJob.class);

    /** The one event name the run's line is indexed under. */
    private static final String RUN_EVENT = "register_generation_run";

    /** What the line calls a run that went ahead, and one that did not. */
    private static final String PROCEEDED = "proceed";

    private static final String SKIPPED = "skipped";

    /** What the line calls the reading a run goes ahead on when nobody overrode anything. */
    private static final String FLAG_ON = "flag-on";

    /**
     * What the assembler is told about the batches already recorded for tonight's keys.
     *
     * <p>Nothing, and that is a gap rather than a decision: {@link RegisterStore} answers no
     * question of the form "the batches recorded for these keys", so the supplementary link and the
     * in-flight deferral design Q27 describes cannot yet be decided by a run. Both belong to the
     * same store change - the one that stamps an assembled batch onto exactly its rows, which is
     * what {@code assembled_at} and the OU code are left to - and this constant is the single line
     * that changes when it lands. Until then the read would answer nothing anyway: no run yet
     * stamps the batch the assembler decided, so there is no earlier batch for a key to be found.
     */
    private static final List<RegisterBatch> NO_EARLIER_BATCHES = List.of();

    private final FeatureFlagGate gate;

    private final RegisterStore store;

    private final BatchAssembler assembler;

    private final RegisterGenerationService service;

    private final GenerationReconciler reconciler;

    private final GenerationMetrics metrics;

    private final GenerationProperties properties;

    private final Clock clock;

    private final RunProgress runProgress;

    /**
     * Creates the run over the collaborators it asks in order, with no readiness to move.
     *
     * <p>The run a unit test builds. A deployment always uses the constructor below, because the
     * file service gates readiness for exactly as long as a run is in progress and only the run
     * knows when that is.
     *
     * @param gate       the one lever, asked first and before anything is read or assembled
     * @param store      the register store, for the records this run may batch
     * @param assembler  the grouping into one batch per court centre and register date
     * @param service    the requesting leg, asked once per batch and sequentially
     * @param reconciler the grace-period safety net under the public-event topic
     * @param metrics    the instrument surface a nightly flow is read by between runs
     * @param properties the settings the run works to, the run deadline above all
     * @param clock      the run's own clock, which the deadline and the report's duration are
     *                   measured on
     */
    public RegisterGenerationJob(final FeatureFlagGate gate, final RegisterStore store,
            final BatchAssembler assembler, final RegisterGenerationService service,
            final GenerationReconciler reconciler, final GenerationMetrics metrics,
            final GenerationProperties properties, final Clock clock) {
        this(gate, store, assembler, service, reconciler, metrics, properties, clock,
                RunProgress.NONE);
    }

    /**
     * Creates the run a deployment schedules, which says while it is going on that it is.
     *
     * @param gate        the one lever, asked first and before anything is read or assembled
     * @param store       the register store, for the records this run may batch
     * @param assembler   the grouping into one batch per court centre and register date
     * @param service     the requesting leg, asked once per batch and sequentially
     * @param reconciler  the grace-period safety net under the public-event topic
     * @param metrics     the instrument surface a nightly flow is read by between runs
     * @param properties  the settings the run works to, the run deadline above all
     * @param clock       the run's own clock, which the deadline and the report's duration are
     *                    measured on
     * @param runProgress what the run tells the pod while it is in progress, so that the file
     *                    service gates readiness for that long and no longer
     */
    public RegisterGenerationJob(final FeatureFlagGate gate, final RegisterStore store,
            final BatchAssembler assembler, final RegisterGenerationService service,
            final GenerationReconciler reconciler, final GenerationMetrics metrics,
            final GenerationProperties properties, final Clock clock,
            final RunProgress runProgress) {
        this.gate = gate;
        this.store = store;
        this.assembler = assembler;
        this.service = service;
        this.reconciler = reconciler;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.runProgress = runProgress;
    }

    /**
     * Runs one generation, from the flag read to the report.
     *
     * <p>The flag is read before anything else and its answer ends the run: a skipped run touches
     * neither the store, the assembler, the service nor the reconciler, because a run that read the
     * store first would already have stamped {@code batch_id} onto rows the flag says this service
     * may not generate, and unstamping them is not something the schema offers.
     *
     * @return what the run did, including a run the flag stopped
     */
    @Scheduled(cron = "${courtregister.generation.cron}", zone = "${courtregister.generation.zone}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    public RunReport run() {
        final Instant startedAt = clock.instant();
        final GateDecision decision = gate.decide(false);

        final RunReport report;
        if (decision instanceof Skipped) {
            report = new RunReport(decision, Map.of(), 0, sinceStart(startedAt));
        } else {
            // Only from here on is the file service anything readiness should have an opinion
            // about, and it stops being one however the run ends.
            runProgress.recordRunStarted();
            try {
                report = generate(decision, startedAt);
            } finally {
                runProgress.recordRunEnded();
            }
        }
        record(report);
        return report;
    }

    /**
     * The night the flag allowed: read, assemble, request one batch at a time, then chase.
     *
     * @param decision  what the gate decided, which the report carries unchanged
     * @param startedAt when the run began, which its duration is measured from
     * @return what the run did
     */
    private RunReport generate(final GateDecision decision, final Instant startedAt) {
        final List<RegisterRecord> active = store.activeUnbatched();
        // True because this is the schedule asking. The operations CLI assembles the same way and
        // says false, which is progression's own flag and is written to the batch row.
        final BatchAssembly assembly = assembler.assemble(active, NO_EARLIER_BATCHES, true);

        final Map<BatchStatus, Integer> outcomes = request(assembly);
        metrics.oldestRecordedUnbatchedAge(oldestStillWaiting(active, assembly));

        return new RunReport(decision, outcomes, reconciler.reconcile(), sinceStart(startedAt));
    }

    /**
     * Asks for every batch the deadline holds, in the order the assembler answered.
     *
     * <p>One at a time, and each measured against the same instant. A batch the run had no time
     * left for is counted PENDING rather than failed and gauged as left behind: it is waiting for
     * tomorrow's run, which will find its registers active and unbatched, and a gauge that only
     * ever moved up would need a run to fail before it could come down again.
     *
     * @param assembly what the assembler made of the night
     * @return how many batches ended in each state
     */
    private Map<BatchStatus, Integer> request(final BatchAssembly assembly) {
        final Deadline deadline = Deadline.startingAt(clock.instant(), properties.runDeadline());
        final Map<BatchStatus, Integer> outcomes = new EnumMap<>(BatchStatus.class);
        int leftBehind = 0;

        for (final AssembledBatch assembled : assembly.batches()) {
            if (deadline.hasPassedAt(clock.instant())) {
                leftBehind++;
                count(outcomes, BatchStatus.PENDING);
            } else {
                count(outcomes, service.request(assembled.batch(), deadline).status());
            }
        }

        metrics.pendingAfterDeadline(leftBehind);
        return outcomes;
    }

    /**
     * How old the oldest register this run left unbatched is.
     *
     * <p>The reading that says a night was missed: a register that is never batched is invisible in
     * every counter here, because nothing happened to it. What the run left unbatched is what the
     * assembler deferred - a key whose earlier batch is still in flight - since everything else it
     * read is in a batch by now.
     *
     * @param active   the registers the store called active
     * @param assembly what the assembler made of them
     * @return the age of the oldest register still waiting, or {@link Duration#ZERO} where there is
     *         none
     */
    private Duration oldestStillWaiting(final List<RegisterRecord> active,
            final BatchAssembly assembly) {

        final List<CourtCentreDay> waiting = assembly.deferred();
        final Instant now = clock.instant();
        return active.stream()
                .filter(register -> waiting.contains(register.key()))
                .map(RegisterRecord::registerTime)
                .min(Instant::compareTo)
                .map(oldest -> Duration.between(oldest, now))
                .orElse(Duration.ZERO);
    }

    /**
     * The one line a night leaves behind.
     *
     * <p>Written for every run, including - especially - the ones that did nothing, since before
     * cutover that is every night. Counts, a duration and bounded codes only: the batches are
     * counted rather than named, and nothing a register carries is anywhere near it (constitution
     * Principle VII).
     *
     * @param report what the run did
     */
    private static void record(final RunReport report) {
        final Map<BatchStatus, Integer> outcomes = report.outcomes();
        LOG.info("event={} gate={} reason={} batches={} generating={} failed={} pending={} "
                        + "reconciled={} duration_ms={}",
                RUN_EVENT, gateOf(report.gateDecision()), reasonOf(report.gateDecision()),
                outcomes.values().stream().mapToInt(Integer::intValue).sum(),
                counted(outcomes, BatchStatus.GENERATING), counted(outcomes, BatchStatus.FAILED),
                counted(outcomes, BatchStatus.PENDING), report.reconciled(),
                report.duration().toMillis());
    }

    /**
     * Whether the run went ahead, in the one word the line is filtered on.
     *
     * @param decision what the gate decided
     * @return {@code proceed} or {@code skipped}
     */
    private static String gateOf(final GateDecision decision) {
        return decision instanceof Proceed ? PROCEEDED : SKIPPED;
    }

    /**
     * Why the run went ahead or did not, as one of the four bounded codes.
     *
     * <p>A run an operator overrode is {@code overridden} rather than {@code flag-on}, because the
     * night this service generated while the flag said the legacy was is the one night in this flow
     * most worth finding again.
     *
     * @param decision what the gate decided
     * @return the bounded code the line carries
     */
    private static String reasonOf(final GateDecision decision) {
        return switch (decision) {
            case Proceed proceed -> proceed.overridden() ? Reason.OVERRIDDEN.code() : FLAG_ON;
            case Skipped skipped -> skipped.reason().code();
        };
    }

    private static void count(final Map<BatchStatus, Integer> outcomes, final BatchStatus status) {
        outcomes.merge(status, 1, Integer::sum);
    }

    private static int counted(final Map<BatchStatus, Integer> outcomes, final BatchStatus status) {
        return outcomes.getOrDefault(status, 0);
    }

    private Duration sinceStart(final Instant startedAt) {
        return Duration.between(startedAt, clock.instant());
    }
}
