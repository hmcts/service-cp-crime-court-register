package uk.gov.hmcts.cp.courtregister.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.courtregister.application.BatchOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties.SourceMode;
import uk.gov.hmcts.cp.courtregister.domain.AssembledBatch;
import uk.gov.hmcts.cp.courtregister.domain.BatchAssembly;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.RunReport;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * The night, in the order it happens.
 *
 * <p>The job decides almost nothing on its own - the gate answers about the flag, the assembler
 * groups, the service requests, the reconciler chases - and that is exactly what makes it worth
 * pinning: what a run <em>is</em> is the order it asks those four in, and every failure this class
 * can have is an order rather than a calculation.
 *
 * <p>Four of them matter enough to be stated as cases here:
 *
 * <ul>
 *   <li><strong>the flag first, and its answer ends the run.</strong> A run that read the store
 *       before it read the flag would have stamped {@code batch_id} onto rows the flag says this
 *       service may not generate, and getting them back is a person's decision about one batch at a
 *       time rather than something a later run can undo. The
 *       skipped run therefore touches nothing at all - not the store, not the assembler, not the
 *       service and not the reconciler - which is what the first nested class asserts as
 *       interactions rather than as outcomes;</li>
 *   <li><strong>one batch at a time.</strong> Sequential is not an efficiency choice: each request
 *       is a write to the shared file service followed by a POST, and a run that fanned them out
 *       would make the run deadline unenforceable and the file-service datasource's readiness
 *       reading meaningless;</li>
 *   <li><strong>the deadline bounds requesting and nothing else.</strong> It is computed once, so a
 *       bound re-derived per batch cannot grow by what the batch before it took, and a batch it
 *       leaves no time for is left PENDING for the next run rather than failed - nothing has gone
 *       wrong with it. The run still chases what it is waiting on afterwards, because completion
 *       arrives on the public-event topic long after this run has ended;</li>
 *   <li><strong>every run reports.</strong> Including - especially - the ones that did nothing, since
 *       before cutover that is every night, and a report that only appeared when work happened would
 *       make "the flag is off" and "the scheduler never fired" the same silence.</li>
 * </ul>
 *
 * <p>The last case is {@code job_is_scheduled_in_europe_london}, and it reads annotations rather
 * than behaviour because the annotations <em>are</em> the behaviour: 18:00 is a wall-clock
 * requirement that has to hold in BST and in GMT alike, and the legacy fires in the scheduling JVM's
 * default zone because its Quartz trigger was built without one (research §4). A cron with no zone
 * on a UTC pod is an hour late for five months of the year, and nothing else in this suite would
 * notice. The lock is read in the same breath: a second replica running the same night is two
 * documents and two e-mails for every court centre in the country.
 *
 * <p><strong>Two seams this task adds, and why.</strong> The job's constructor, because a run's
 * whole content is the order it asks its collaborators in and a test that could not stand between
 * them would have nothing to pin. And {@link RunReport}'s first component, which was a
 * {@code FlagDecision} and is now the {@link GateDecision} the run was actually given: the run never
 * sees the reading, so a report built from one could say neither that an operator overrode a flag
 * that had not said ON - the night most worth reading a report for - nor, for a flag that could not
 * be read, which of the six causes stopped it, since the gate does not pass the cause on. Those six
 * keep their own series on {@code courtregister_generation_skipped_total}, where the gate already
 * puts them, and the report says what the run knows.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the nightly generation run")
class RegisterGenerationJobTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T050 wires the nightly run; this is its red run";

    /** 18:00 Monday to Friday, six fields, Spring's dialect. */
    private static final String COURT_CRON = "0 0 18 * * MON-FRI";

    /** The setting the lock's duration has to come from rather than be written twice. */
    private static final String LOCK_PROPERTY = "${courtregister.generation.lock-at-most-for}";

    /** The one zone the requirement is written in. */
    private static final String COURTS_ZONE = "Europe/London";

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(60);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(70);
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(10);

    /** 18:00 in Europe/London on a Thursday in August, which is 17:00 UTC. */
    private static final Instant SIX_PM = Instant.parse("2026-08-20T17:00:00Z");

    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** Returned when the line does not carry a field, so an omission fails as an assertion. */
    private static final int ABSENT_FIELD = -1;

    /** How the one line a night leaves behind starts, which is what an operator filters on. */
    private static final String RUN_EVENT = "event=register_generation_run";

    /** How many outcomes the reconciler had to fetch on the mixed night. */
    private static final int RECONCILED = 4;

    /**
     * The line a night that did something leaves behind, in full.
     *
     * <p>Every field of {@link RunReport}: what the gate decided and why, the three states the
     * requesting leg can leave a batch in and their total, the court centre days the run passed
     * over, the outcomes it had to chase and how long it took. Written out rather than asserted
     * field by field because the claim is the whole line - a field dropped from it is a night an
     * operator can no longer read, and a field renamed is an alert that stops firing.
     */
    private static final String THE_MIXED_NIGHTS_LINE = RUN_EVENT
            + " gate=proceed reason=flag-on batches=3 generating=1 failed=1 pending=1 deferred=2"
            + " reconciled=" + RECONCILED + " duration_ms=180000";

    /** The same line for a night the flag stopped: the same fields, and nothing earned. */
    private static final String THE_SKIPPED_NIGHTS_LINE = RUN_EVENT
            + " gate=skipped reason=flag-off batches=0 generating=0 failed=0 pending=0 deferred=0"
            + " reconciled=0 duration_ms=0";

    /**
     * What every value on the line is allowed to be: a count, a duration, or a bounded code.
     *
     * <p>The privacy claim stated where the line is written rather than only across the service
     * ({@code TelemetryPrivacyTest}): a court centre's name, a recipient's address or a renderer's
     * own reason text added to this line would be free text in the one INFO line every night
     * produces, and this refuses the shape rather than the particular words.
     */
    private static final Pattern BOUNDED_FIELDS_ONLY = Pattern.compile(
            "event=register_generation_run gate=(?:proceed|skipped) reason=[a-z-]+ batches=\\d+ "
                    + "generating=\\d+ failed=\\d+ pending=\\d+ deferred=\\d+ reconciled=\\d+ "
                    + "duration_ms=\\d+");

    /** What the store answers with; the run's job is to pass it on unchanged. */
    private static final List<RegisterRecord> ACTIVE = List.of(record(), record());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);
    private final RegisterStore store = mock(RegisterStore.class);
    private final BatchAssembler assembler = mock(BatchAssembler.class);
    private final RegisterGenerationService service = mock(RegisterGenerationService.class);
    private final GenerationReconciler reconciler = mock(GenerationReconciler.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(SIX_PM);

    private final RegisterGenerationJob job = new RegisterGenerationJob(gate, store, assembler,
            service, reconciler, metrics, settings(), clock);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * The settings a deployed run works to, which are the ones {@code application.yaml} ships.
     *
     * @return generation enabled, at the court's hour, in the court's zone
     */
    private static GenerationProperties settings() {
        return new GenerationProperties(true, COURT_CRON, COURTS_ZONE, false, RUN_DEADLINE,
                LOCK_AT_MOST_FOR, GRACE_PERIOD, GenerationProperties.COMPLETION_EVENT,
                SourceMode.LIVE, SourceMode.LIVE, SourceMode.LIVE, SourceMode.LIVE);
    }

    /**
     * Runs the night and hands back what it reported.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the report it did
     * not produce is then asserted on as {@code null} - the red run is the report and the green run
     * is the same assertions unchanged.
     *
     * @return the run report, or {@code null} where the seam refused
     */
    private RunReport run() {
        final AtomicReference<RunReport> reported = new AtomicReference<>();
        softly.assertThatCode(() -> reported.set(job.run()))
                .as(PENDING)
                .doesNotThrowAnyException();
        return reported.get();
    }

    /**
     * One field of the report, read safely from a run that did not produce one.
     *
     * <p>A method reference applied to a null report throws where the case wants an assertion, and
     * the red run's whole point is that every failure it records is one.
     *
     * @param report what the run reported, or {@code null} where the seam refused
     * @param field  the field being asserted on
     * @param <T>    that field's type
     * @return the field's value, or {@code null} where there is no report
     */
    private static <T> T reported(final RunReport report, final Function<RunReport, T> field) {
        return report == null ? null : field.apply(report);
    }

    private void theGateAnswers(final GateDecision decision) {
        when(gate.decide(false)).thenReturn(decision);
    }

    /**
     * Sets the night up as one the flag allows, holding the given batches.
     *
     * @param assembled the batches the assembler makes of the night's active records
     */
    private void aNightHolding(final RegisterBatch... assembled) {
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(ACTIVE);
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly(assembled));
        // The store answers with the batch as the row now stands. It carries the same identity the
        // assembler decided - the OU code and the court house it gains are the rows' own and are
        // read by the notify leg rather than by anything here - so a stub that hands the argument
        // back is what a written batch looks like from this class.
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * Sets the night up as one the flag allows, holding one batch and deferring one key.
     *
     * <p>A key is deferred when a batch of its own is still in flight, which is the schema's
     * one-live-batch-per-key rule (design Q27) rather than anything going wrong. What makes it worth
     * reporting is that nothing else in the run says it happened: no batch was assembled for that
     * key, so it is in none of the outcome counts.
     *
     * @param deferred the court centre days the assembler passed over
     */
    private void aNightDeferring(final CourtCentreDay... deferred) {
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(ACTIVE);
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(batch(), ACTIVE)), List.of(deferred)));
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
        everyRequestIsAccepted();
    }

    private static CourtCentreDay key() {
        return new CourtCentreDay(UUID.randomUUID(), THURSDAY);
    }

    /**
     * A night with one of each outcome, two court centre days passed over and two chased outcomes.
     *
     * <p>Built so that the line it leaves behind exercises every field at once and none of them
     * with the same number: a report whose counts were all one would be pinned by a line that had
     * the fields in the wrong order, and one whose duration were zero would be pinned by a line
     * that did not measure it. The three batches end GENERATING, FAILED and PENDING - the three
     * states the requesting leg can produce - and the third gets there through a stamp that was
     * refused, so it costs the run no time and the duration stays the two advances the other two
     * make.
     *
     * @return the batches the night holds, in the order the assembler answered
     */
    private List<RegisterBatch> aMixedNight() {
        final RegisterBatch generating = batch();
        final RegisterBatch failing = batch();
        final RegisterBatch unstampable = batch();
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(ACTIVE);
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(generating, ACTIVE),
                        new AssembledBatch(failing, ACTIVE),
                        new AssembledBatch(unstampable, ACTIVE)),
                List.of(key(), key())));
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(store.assemble(eq(unstampable), any())).thenThrow(new IllegalStateException(
                "batch " + unstampable.batchId() + " was asked for 2 registers and stamped 1"));
        when(service.request(eq(generating), any())).thenAnswer(call -> {
            clock.advance(Duration.ofMinutes(1));
            return requested(generating, BatchStatus.GENERATING, null);
        });
        when(service.request(eq(failing), any())).thenAnswer(call -> {
            clock.advance(Duration.ofMinutes(2));
            return requested(failing, BatchStatus.FAILED,
                    BatchFailureReason.RENDER_REQUEST_REJECTED);
        });
        when(reconciler.reconcile()).thenReturn(RECONCILED);
        return List.of(generating, failing, unstampable);
    }

    /**
     * A night that assembled nothing and passed over the day one old register waits under.
     *
     * <p>The deferred key is the key that register is addressed by, which is what makes the age
     * gauge readable at all: {@code oldestStillWaiting} reads the registers whose key the assembler
     * passed over, so a deferred key nothing is waiting under gauges nothing.
     *
     * @param recorded when that register was recorded
     * @return how long it has been waiting as of the run's own instant
     */
    private Duration aNightDeferringARegisterRecordedAt(final Instant recorded) {
        final CourtCentreDay waiting = key();
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(List.of(new RegisterRecord(UUID.randomUUID(),
                UUID.randomUUID(), SIX_PM, waiting, recorded,
                "courtregister_" + THURSDAY + ".json", "Applicant", RecordedFlagState.ON, null)));
        when(assembler.assemble(any(), any(), anyBoolean()))
                .thenReturn(new BatchAssembly(List.of(), List.of(waiting)));
        return Duration.between(recorded, SIX_PM);
    }

    /**
     * Every line the run wrote that a night is indexed under, in the order it wrote them.
     *
     * @param log what the job logged
     * @return the run's own lines, which on a healthy night is exactly one
     */
    private static List<String> runLines(final CapturedLog log) {
        return log.messages().stream().filter(line -> line.startsWith(RUN_EVENT)).toList();
    }

    /**
     * The one line the night left behind, or {@code null} where it left none or more than one.
     *
     * <p>{@code null} rather than a refusal so that a run which wrote no line at all is recorded as
     * a failing assertion about the line rather than as an exception from the reading of it.
     *
     * @param log what the job logged
     * @return the single run line, or {@code null}
     */
    private static String theOneLine(final CapturedLog log) {
        final List<String> lines = runLines(log);
        return lines.size() == 1 ? lines.getFirst() : null;
    }

    /**
     * The line's {@code name=value} pairs, read the way a log index reads them.
     *
     * @param line the run's line, or {@code null} where it wrote none
     * @return each field against its value, or empty where there is no line
     */
    private static Map<String, String> fieldsOf(final String line) {
        return line == null
                ? Map.of()
                : Stream.of(line.split(" "))
                        .map(field -> field.split("=", 2))
                        .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
    }

    /**
     * One count the line carries.
     *
     * @param fields the line's fields
     * @param name   the field being read
     * @return its value, or {@link #ABSENT_FIELD} where the line does not carry it
     */
    private static int onTheLine(final Map<String, String> fields, final String name) {
        return Integer.parseInt(fields.getOrDefault(name, String.valueOf(ABSENT_FIELD)));
    }

    private double oldestRecordedUnbatchedAge() {
        final Gauge gauge =
                registry.find(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double deferredKeys() {
        final Gauge gauge = registry.find(GenerationMetrics.DEFERRED_KEYS).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    /**
     * The assembly the assembler answers with, one pairing per batch and nothing deferred.
     *
     * <p>Each batch is paired with the night's active records because
     * {@link AssembledBatch} refuses a batch assembled from none; which records belong to which
     * batch is the assembler's own concern (T032) and nothing here asserts it.
     *
     * @param assembled the batches the night holds
     * @return those batches as an assembly
     */
    private static BatchAssembly assembly(final RegisterBatch... assembled) {
        return new BatchAssembly(Stream.of(assembled)
                .map(batch -> new AssembledBatch(batch, ACTIVE))
                .toList(), List.of());
    }

    /** Every request is accepted, and each answers about the batch it was given. */
    private void everyRequestIsAccepted() {
        when(service.request(any(), any()))
                .thenAnswer(call -> requested(call.getArgument(0), BatchStatus.GENERATING, null));
    }

    private double pendingAfterDeadline() {
        final Gauge gauge = registry.find(GenerationMetrics.PENDING_AFTER_DEADLINE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private static RegisterBatch batch() {
        return new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(), "B01LY", "Youth Court",
                THURSDAY, "courtregister_" + THURSDAY + ".json", null, null, BatchStatus.PENDING,
                null, null, true, null, SIX_PM, null, null, null, null, 0, null, 0);
    }

    private static RegisterRecord record() {
        return new RegisterRecord(UUID.randomUUID(), UUID.randomUUID(), SIX_PM,
                new CourtCentreDay(UUID.randomUUID(), THURSDAY), SIX_PM,
                "courtregister_" + THURSDAY + ".json", "Applicant", RecordedFlagState.ON, null);
    }

    private static BatchOutcome requested(final RegisterBatch batch, final BatchStatus status,
            final BatchFailureReason reason) {
        return new BatchOutcome(batch.batchId(), status, reason);
    }

    /**
     * What the annotation names, whether it says it or names the setting that says it.
     *
     * <p>The schedule may reasonably be written either way - as the two literals research §4 gives,
     * or as the placeholders of the two settings {@code application.yaml} declares and
     * {@link GenerationProperties#validate()} holds to the court's zone. What must not vary is the
     * schedule itself, so a placeholder is resolved here to what those settings hold and the
     * assertion is about the hour and the zone rather than about the spelling.
     *
     * @param annotated the annotation's own value
     * @return the value the run is scheduled by
     */
    private static String configured(final String annotated) {
        return switch (annotated) {
            case "${courtregister.generation.cron}" -> settings().cron();
            case "${courtregister.generation.zone}" -> settings().zone();
            case LOCK_PROPERTY -> settings().lockAtMostFor().toString();
            default -> annotated;
        };
    }

    /**
     * How long the lock is held for, in either spelling ShedLock accepts.
     *
     * <p>{@code PT70M} and {@code 70m} are the same lock, and which one is written is not what this
     * suite is about; how long it lasts against the run deadline is.
     *
     * @param lock the annotation the job carries, or {@code null} where it carries none
     * @return the duration the lock is held for, or {@code null} where none is stated
     */
    private static Duration lockedFor(final SchedulerLock lock) {
        final String stated = lock == null ? "" : configured(lock.lockAtMostFor());
        return stated.isBlank()
                ? null
                : Duration.parse(stated.regionMatches(true, 0, "P", 0, 1) ? stated : "PT" + stated);
    }

    /**
     * The one lever, and the first thing the run asks about.
     */
    @Nested
    @DisplayName("reading the flag before anything else")
    class TheGateFirst {

        @Test
        void a_flag_that_did_not_say_on_should_end_the_run_before_anything_is_read() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            run();

            verifyNoInteractions(store, assembler, service, reconciler);
        }

        @Test
        void the_flag_should_be_read_before_the_store_is_asked_what_is_waiting() {
            aNightHolding(batch());
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(gate, store, assembler, service);
            order.verify(gate).decide(false);
            order.verify(store).activeUnbatched();
            order.verify(assembler).assemble(any(), any(), anyBoolean());
            order.verify(service).request(any(), any());
        }

        @Test
        void the_nightly_run_should_never_override_the_flag() {
            aNightHolding();

            run();

            verify(gate).decide(false);
            verify(gate, never()).decide(true);
        }

        @Test
        void a_skipped_run_should_still_report_what_the_gate_decided() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::gateDecision))
                    .as("a night nobody generated on leaves no batch, no request and no row; the "
                            + "report is the only thing that distinguishes it from a scheduler "
                            + "that never fired")
                    .isEqualTo(new Skipped(Reason.FLAG_OFF));
            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("and it did nothing, which the counts have to say rather than merely omit")
                    .isEqualTo(Map.of());
        }

        @Test
        void a_run_stopped_by_a_flag_nobody_could_read_should_say_so_and_not_that_it_was_off() {
            theGateAnswers(new Skipped(Reason.FLAG_UNREADABLE));

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::gateDecision))
                    .as("fail-closed and off are the same restraint and not the same night: one is "
                            + "the cutover working, the other is an outage this service rode out")
                    .isEqualTo(new Skipped(Reason.FLAG_UNREADABLE));
        }

        @Test
        void a_run_an_operator_overrode_should_go_ahead_and_the_report_should_say_it_was() {
            theGateAnswers(new Proceed(true));
            when(store.activeUnbatched()).thenReturn(ACTIVE);
            when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly());

            final RunReport report = run();

            verify(store).activeUnbatched();
            softly.assertThat(reported(report, RunReport::gateDecision))
                    .as("the one night on which this service generated while the flag said the "
                            + "legacy was; a report that could not say so would leave the warning "
                            + "line as the only trace of it")
                    .isEqualTo(new Proceed(true));
        }
    }

    /**
     * What the run assembles, and on whose behalf.
     */
    @Nested
    @DisplayName("assembling the night's work")
    class Assembling {

        @Test
        void the_run_should_assemble_exactly_the_records_the_store_calls_active() {
            aNightHolding();

            run();

            verify(assembler).assemble(eq(ACTIVE), any(), anyBoolean());
        }

        @Test
        void the_nightly_run_should_stamp_its_batches_as_system_generated() {
            aNightHolding();

            run();

            verify(assembler).assemble(any(), any(), eq(true));
        }

        /**
         * The run has to write the batch down before it asks anybody to render it.
         *
         * <p>Everything downstream is keyed on the batch: {@code store.batched(batchId)} is what the
         * payload is built from, {@code markPayloadMinted} and {@code markRequested} move a row that
         * has to exist, and the public event correlates back on the same identity. A run that handed
         * the assembler's batch straight to the service would ask for a render of a batch no row
         * knows about - the payload read would answer nothing, every batch would take the
         * ASSEMBLY_FAILED path, and {@code markFailed} would be asked about a batch that is not
         * there. Nothing about it is visible from a suite that mocks the store, which is exactly why
         * it is asserted as an order here.
         */
        @Test
        void every_assembled_batch_should_be_written_down_before_its_render_is_asked_for() {
            final RegisterBatch first = batch();
            final RegisterBatch second = batch();
            aNightHolding(first, second);
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(store, service);
            order.verify(store).assemble(eq(first), any());
            order.verify(service).request(eq(first), any());
            order.verify(store).assemble(eq(second), any());
            order.verify(service).request(eq(second), any());
        }

        /**
         * And the history the supplementary rule is decided from has to be read at all.
         *
         * <p>The assembler is given the batches already recorded for the keys in play so that a late
         * re-share becomes a supplementary batch for its day rather than a second first one (design
         * Q27). A run that passed an empty list would answer "no earlier batch" for every key, and
         * the rule would be dead from end to end however carefully the assembler implemented it.
         */
        @Test
        void the_batches_already_recorded_for_tonights_keys_should_be_read_and_passed_on() {
            final RegisterBatch earlier = batch();
            aNightHolding(batch());
            everyRequestIsAccepted();
            when(store.batchesFor(any())).thenReturn(List.of(earlier));

            run();

            verify(assembler).assemble(any(), eq(List.of(earlier)), anyBoolean());
        }

        /**
         * A court centre whose registers moved is one court centre, not the night.
         *
         * <p>The stamp is refused where a register was superseded or batched between the read and
         * the write, and the refusal takes the batch row with it - so there is no row to fail and
         * nothing to record about it. Its registers are still active and unbatched, which is exactly
         * the state the next run finds them in, so the batch is counted PENDING and the court
         * centres behind it are still asked for. That isolation is the other half of defect fix P5:
         * progression's leg caught the stream exception and walked on, and what it got wrong was
         * leaving no trace, not the walking on.
         */
        @Test
        void a_batch_that_could_not_be_written_down_should_not_stop_the_batches_behind_it() {
            final RegisterBatch unstampable = batch();
            final RegisterBatch following = batch();
            aNightHolding(unstampable, following);
            everyRequestIsAccepted();
            when(store.assemble(eq(unstampable), any())).thenThrow(new IllegalStateException(
                    "batch " + unstampable.batchId() + " was asked for 2 registers and stamped 1"));

            final RunReport report = run();

            verify(service, never()).request(eq(unstampable), any());
            verify(service).request(eq(following), any());
            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("PENDING rather than FAILED, because there is no batch row to have failed: "
                            + "the registers are where the next run will look for them")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, 1, BatchStatus.PENDING, 1));
        }

        @Test
        void a_night_with_nothing_waiting_should_ask_the_renderer_for_nothing() {
            aNightHolding();

            final RunReport report = run();

            verifyNoInteractions(service);
            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("a quiet night is a successful run with no outcomes, not a failed one")
                    .isEqualTo(Map.of());
        }
    }

    /**
     * One batch at a time, each against the same bound.
     */
    @Nested
    @DisplayName("requesting one batch at a time")
    class Requesting {

        @Test
        void every_batch_should_be_requested_in_the_order_it_was_assembled() {
            final RegisterBatch first = batch();
            final RegisterBatch second = batch();
            aNightHolding(first, second);
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(service);
            order.verify(service).request(eq(first), any());
            order.verify(service).request(eq(second), any());
        }

        @Test
        void a_batch_that_failed_should_not_stop_the_batches_behind_it() {
            final RegisterBatch failing = batch();
            final RegisterBatch following = batch();
            aNightHolding(failing, following);
            when(service.request(eq(failing), any())).thenReturn(requested(failing,
                    BatchStatus.FAILED, BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE));
            when(service.request(eq(following), any()))
                    .thenReturn(requested(following, BatchStatus.GENERATING, null));

            run();

            verify(service).request(eq(following), any());
        }

        @Test
        void every_batch_should_be_measured_against_the_deadline_the_run_started_with() {
            aNightHolding(batch(), batch());
            when(service.request(any(), any())).thenAnswer(call -> {
                clock.advance(Duration.ofMinutes(5));
                return requested(call.getArgument(0), BatchStatus.GENERATING, null);
            });

            run();

            final ArgumentCaptor<Deadline> deadlines = ArgumentCaptor.forClass(Deadline.class);
            verify(service, times(2)).request(any(), deadlines.capture());
            softly.assertThat(deadlines.getAllValues())
                    .as("computed once, at the moment requesting began: a bound re-derived per "
                            + "batch grows by whatever the batch before it took, and a run that "
                            + "kept extending its own deadline would collide with the next one")
                    .containsOnly(Deadline.startingAt(SIX_PM, RUN_DEADLINE));
        }
    }

    /**
     * The bound on requesting, and the two things it deliberately does not bound.
     */
    @Nested
    @DisplayName("the run deadline")
    class TheRunDeadline {

        /** Sets the night up as two batches, the first of which spends the whole budget. */
        private RegisterBatch aBatchTheRunRanOutOfTimeFor() {
            final RegisterBatch first = batch();
            final RegisterBatch stranded = batch();
            aNightHolding(first, stranded);
            when(service.request(eq(first), any())).thenAnswer(call -> {
                clock.advance(RUN_DEADLINE.plusMinutes(1));
                return requested(first, BatchStatus.GENERATING, null);
            });
            return stranded;
        }

        @Test
        void a_batch_the_run_had_no_time_left_for_should_not_be_requested() {
            final RegisterBatch stranded = aBatchTheRunRanOutOfTimeFor();

            run();

            verify(service, never()).request(eq(stranded), any());
        }

        @Test
        void a_batch_the_run_had_no_time_left_for_should_be_reported_as_still_pending() {
            aBatchTheRunRanOutOfTimeFor();

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("the counts add up to the batches the night held, or a run that ran out "
                            + "of time reads as a quieter night than it was; and PENDING rather "
                            + "than FAILED because nothing went wrong with that batch - it is "
                            + "waiting for tomorrow's run, which will find it active and unbatched")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, 1, BatchStatus.PENDING, 1));
        }

        @Test
        void the_batches_a_run_left_behind_should_be_gauged() {
            aBatchTheRunRanOutOfTimeFor();

            run();

            softly.assertThat(pendingAfterDeadline())
                    .as("the reading that says the night is no longer finishing inside its hour, "
                            + "which is the one failure a nightly flow can have repeatedly without "
                            + "anything ever failing")
                    .isEqualTo(1);
        }

        @Test
        void a_run_that_finished_inside_its_hour_should_leave_nothing_behind() {
            aNightHolding(batch(), batch());
            everyRequestIsAccepted();

            run();

            softly.assertThat(pendingAfterDeadline())
                    .as("a gauge that only ever moved up would need a run to fail before it could "
                            + "come down again")
                    .isZero();
        }

        @Test
        void a_run_that_ran_out_of_time_should_still_chase_the_batches_it_is_waiting_on() {
            aBatchTheRunRanOutOfTimeFor();

            run();

            verify(reconciler).reconcile();
        }
    }

    /**
     * The safety net, which is part of the run rather than a schedule of its own.
     */
    @Nested
    @DisplayName("chasing the batches already generating")
    class Reconciling {

        @Test
        void a_night_with_nothing_to_assemble_should_still_chase_what_is_outstanding() {
            aNightHolding();

            run();

            verify(reconciler).reconcile();
        }

        /**
         * The run calls the reconciler, but the run is not what makes reconciliation happen.
         *
         * <p>A skipped run touches nothing - which is right, and is the case above - so on a night
         * the flag reads OFF or could not be read, a batch left GENERATING by an earlier ON night
         * is not asked about by this class at all. Before cutover that is every night. The safety
         * net therefore has to have a schedule of its own, independent of the gate, and this is
         * where a reader of the run meets that fact; what its cadence and its lock are is pinned in
         * {@code GenerationReconcilerTest}.
         */
        @Test
        void a_skipped_run_should_leave_overdue_batches_to_the_reconcilers_own_schedule()
                throws NoSuchMethodException {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            run();

            verifyNoInteractions(reconciler);
            softly.assertThat(GenerationReconciler.class.getDeclaredMethod("reconcileScheduled")
                            .getAnnotation(Scheduled.class))
                    .as("a night this service may not generate on is still a night it owns the "
                            + "batches it asked for yesterday; without a schedule of its own the "
                            + "reconciler never runs on one")
                    .isNotNull();
        }

        @Test
        void what_the_reconciler_had_to_fetch_should_be_reported() {
            aNightHolding();
            when(reconciler.reconcile()).thenReturn(2);

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::reconciled))
                    .as("the broker's health seen from here: a run whose outcomes all arrive by "
                            + "reconciliation is a subscription to investigate rather than a "
                            + "renderer")
                    .isEqualTo(2);
        }
    }

    /**
     * The one line a night leaves behind.
     */
    @Nested
    @DisplayName("the run report")
    class TheReport {

        @Test
        void the_report_should_count_the_batches_by_how_the_run_left_them() {
            final RegisterBatch failing = batch();
            aNightHolding(batch(), failing, batch());
            when(service.request(any(), any())).thenAnswer(call -> {
                final RegisterBatch asked = call.getArgument(0);
                return failing.equals(asked)
                        ? requested(asked, BatchStatus.FAILED,
                                BatchFailureReason.RENDER_REQUEST_REJECTED)
                        : requested(asked, BatchStatus.GENERATING, null);
            });

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("what a run adds to the durable rows is the shape of the night, and the "
                            + "shape is how many ended each way")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, 2, BatchStatus.FAILED, 1));
        }

        @Test
        void the_report_should_say_how_long_the_run_took() {
            aNightHolding(batch());
            when(service.request(any(), any())).thenAnswer(call -> {
                clock.advance(Duration.ofMinutes(7));
                return requested(call.getArgument(0), BatchStatus.GENERATING, null);
            });

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::duration))
                    .as("measured on the run's own clock and against the deadline it works to, "
                            + "which is how a night that is drifting towards its hour is seen "
                            + "before the night it runs out of it")
                    .isEqualTo(Duration.ofMinutes(7));
        }

        /**
         * A deferred key is the one thing a run does that no other number it reports covers.
         *
         * <p>Its registers were read as active and no batch was assembled for them, so they appear
         * in none of the outcome counts, and the court centre gets no document tonight.
         * {@code BatchAssembly.deferred} exists so the run can say so, and until it reached the
         * report and the line it said it to nobody.
         */
        @Test
        void the_report_should_count_the_keys_the_run_deferred() {
            aNightDeferring(key());

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::deferredKeys))
                    .as("a key whose earlier batch is still in flight produced no batch tonight, "
                            + "so it is in none of the outcome counts and would otherwise leave "
                            + "the run with nothing at all to say about it")
                    .isEqualTo(1);
        }

        @Test
        void the_line_the_run_leaves_behind_should_carry_the_keys_it_deferred() {
            aNightDeferring(key());

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(log.messages())
                        .as("the report is a value a test can read and the line is what an "
                                + "operator reads; a count that reached only the first of them "
                                + "would be a night's deferral nobody sees")
                        .anyMatch(line -> line.contains("deferred=1"));
            }
        }

        @Test
        void the_keys_the_run_deferred_should_be_gauged() {
            aNightDeferring(key(), key());

            run();

            softly.assertThat(deferredKeys())
                    .as("the companion of the oldest-unbatched age: that says how long the worst "
                            + "of them has waited and this says how much of the estate is waiting")
                    .isEqualTo(2);
        }

        @Test
        void a_run_that_deferred_nothing_should_bring_that_gauge_back_down() {
            aNightDeferring(key());
            run();

            aNightHolding(batch());
            when(assembler.assemble(any(), any(), anyBoolean()))
                    .thenReturn(assembly(batch()));
            run();

            softly.assertThat(deferredKeys())
                    .as("a gauge that only ever went up would need a run to fail before it could "
                            + "come down again")
                    .isZero();
        }

        @Test
        void a_run_that_did_nothing_at_all_should_still_report() {
            aNightHolding();

            final RunReport report = run();

            softly.assertThat(report)
                    .as("before cutover this is every night, and a silence that meant both "
                            + "\"the flag is off\" and \"the job did not fire\" would hide the "
                            + "second inside the first for months")
                    .isNotNull();
        }
    }

    /**
     * <strong>[A]</strong> The line itself, field by field, as an operator and an alert read it.
     *
     * <p>An <strong>[A] characterisation of behaviour this service already had</strong>: the line
     * has been written for every run, skipped ones included, since {@code 6d7aca8} landed the
     * nightly run, and every field asserted here was on it from that commit but one - {@code
     * deferred}, which {@code eaf1413} added. What was missing was anything holding it down.
     * {@code TheReport} above reads the report as a value and one case of it looks for {@code
     * deferred=1} in the line; the other nine fields, their names, their order, the fact that a
     * skipped night writes the same line at all, and the arithmetic between the counts were pinned
     * nowhere, so any of them could have been renamed or dropped with the whole suite still green.
     * Every case here therefore passes on introduction, no implementation commit follows them, and
     * non-vacuity is shown by mutation instead - quoted in this commit's body and reverted before
     * it.
     *
     * <p>The gauges are the other half of the same report (data model: "recorded as the run report
     * (log + gauges), not as a table"). Their names, their labels and their readings are pinned in
     * {@code GenerationMetricsTest}; what belongs here is that the run publishes them, which for
     * two of the three {@code TheReport} and {@code TheRunDeadline} already assert and for the
     * third - the age of the oldest register the run left waiting - nothing did.
     */
    @Nested
    @DisplayName("the fields on the line")
    class TheLine {

        @Test
        void every_field_of_the_report_should_be_on_the_line_a_night_is_read_by() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(runLines(log))
                        .as("one line per run and every field of the report on it: the durable "
                                + "rows say what happened to each batch, and this is the only "
                                + "place that says what happened to the night")
                        .containsExactly(THE_MIXED_NIGHTS_LINE);
            }
        }

        @Test
        void a_night_the_flag_stopped_should_leave_the_same_line_with_nothing_on_it() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(runLines(log))
                        .as("before cutover this is every night, and the same fields with zeroes "
                                + "in them is what makes \"the flag is off\" different from \"the "
                                + "scheduler never fired\" in a log index")
                        .containsExactly(THE_SKIPPED_NIGHTS_LINE);
            }
        }

        @Test
        void a_run_an_operator_overrode_should_be_on_the_line_as_overridden_and_not_as_flag_on() {
            theGateAnswers(new Proceed(true));
            when(store.activeUnbatched()).thenReturn(ACTIVE);
            when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly());

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(fieldsOf(theOneLine(log)))
                        .as("the one night on which this service generated while the flag said the "
                                + "legacy was; a line that called it flag-on would leave nothing "
                                + "at all to find it by")
                        .containsEntry("gate", "proceed")
                        .containsEntry("reason", Reason.OVERRIDDEN.code());
            }
        }

        @Test
        void a_flag_nobody_could_read_should_be_on_the_line_as_its_own_reason_and_not_as_off() {
            theGateAnswers(new Skipped(Reason.FLAG_UNREADABLE));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(fieldsOf(theOneLine(log)))
                        .as("fail-closed and off are the same restraint and not the same night: "
                                + "one is the cutover working and the other is an outage this "
                                + "service rode out")
                        .containsEntry("gate", "skipped")
                        .containsEntry("reason", Reason.FLAG_UNREADABLE.code());
            }
        }

        /**
         * The total has to be the night, or the line describes a quieter one than there was.
         *
         * <p>{@code batches} is the sum over every state a batch ended in and the three named
         * counts are the three states the requesting leg can produce, so the two agreeing is what
         * says nothing has fallen out of the line. A fourth state reaching the report - a batch
         * counted GENERATED by the requesting leg, say - would show up here as a total that no
         * longer adds up rather than as a count nobody notices is missing.
         */
        @Test
        void the_counts_on_the_line_should_add_up_to_the_batches_the_run_saw() {
            final List<RegisterBatch> night = aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "batches"))
                        .as("every batch the assembler answered with is counted exactly once, "
                                + "and under one of the three states the requesting leg can "
                                + "leave a batch in")
                        .isEqualTo(night.size())
                        .isEqualTo(onTheLine(fields, "generating") + onTheLine(fields, "failed")
                                + onTheLine(fields, "pending"));
            }
        }

        @Test
        void nothing_on_the_line_should_be_free_text_or_anything_a_register_carries() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(theOneLine(log))
                        .as("counts, a duration and bounded codes only - no court centre, no "
                                + "batch, no recipient and no renderer's reason text, because "
                                + "every defendant on this register is a child (constitution "
                                + "Principle VII)")
                        .matches(BOUNDED_FIELDS_ONLY);
            }
        }

        @Test
        void the_age_of_the_oldest_register_the_run_left_waiting_should_be_gauged() {
            final Duration waited = aNightDeferringARegisterRecordedAt(SIX_PM.minusSeconds(93_600));

            run();

            softly.assertThat(oldestRecordedUnbatchedAge())
                    .as("the reading that says a night was missed: a register that is never "
                            + "batched moves no counter, because nothing happened to it")
                    .isEqualTo(waited.toSeconds());
        }
    }

    /**
     * When the run happens, and how many of it there are.
     */
    @Nested
    @DisplayName("when the run happens")
    class WhenItRuns {

        @Test
        void job_is_scheduled_in_europe_london() throws NoSuchMethodException {
            final Method run = RegisterGenerationJob.class.getDeclaredMethod("run");
            final Scheduled schedule = run.getAnnotation(Scheduled.class);
            final SchedulerLock lock = run.getAnnotation(SchedulerLock.class);

            softly.assertThat(schedule)
                    .as("the run is a schedule this service keeps, and a job nothing fires is a "
                            + "night of registers nobody is told are missing")
                    .isNotNull();
            softly.assertThat(schedule == null ? null : configured(schedule.cron()))
                    .as("18:00 Monday to Friday, which is the hour the courts' day is over and the "
                            + "one the legacy has always generated at")
                    .isEqualTo(COURT_CRON);
            softly.assertThat(schedule == null ? null : configured(schedule.zone()))
                    .as("the requirement is 18:00 WALL CLOCK in BST and GMT alike; the legacy "
                            + "fires in the scheduling JVM's default zone because its Quartz "
                            + "trigger was built without one, and on a UTC pod that is an hour "
                            + "late for five months of the year")
                    .isEqualTo(COURTS_ZONE);
            softly.assertThat(lock)
                    .as("one replica today is a deployment fact and not a code guarantee, and the "
                            + "cost of being wrong is two documents and two e-mails for every "
                            + "court centre in the country")
                    .isNotNull();
            softly.assertThat(lock == null ? null : lock.name())
                    .as("a lock every job in the estate shares a name with is not a lock")
                    .isNotBlank();
            softly.assertThat(lockedFor(lock))
                    .as("the lock has to outlast the requesting the deadline permits, or a run "
                            + "still inside its hour would be joined by the replica that took the "
                            + "lock it had already lost")
                    .isGreaterThan(RUN_DEADLINE);
        }

        /**
         * The lock and the deadline are one arrangement, so the lock is not allowed to be a second
         * statement of it.
         *
         * <p>{@code run-deadline} is configurable and a literal here is not, so a deployment that
         * lengthens the run past a hard-coded lock gets a window in which a run still inside its
         * hour has already lost the lock that keeps the second replica out. The annotation
         * therefore reads the setting, {@code application.yaml} declares it as the deadline plus a
         * fixed margin, and {@code ConfigurationValidationTest.SchedulerLockAgainstRunDeadline}
         * refuses startup on any pair that does not hold.
         */
        @Test
        void the_lock_duration_should_come_from_configuration_and_not_be_written_twice()
                throws NoSuchMethodException {

            final SchedulerLock lock = RegisterGenerationJob.class.getDeclaredMethod("run")
                    .getAnnotation(SchedulerLock.class);

            softly.assertThat(lock == null ? null : lock.lockAtMostFor())
                    .as("a literal here is a second copy of a configurable setting, and the day "
                            + "the two disagree is the day two pods generate the same night")
                    .isEqualTo(LOCK_PROPERTY);
        }
    }
}
