package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.courtregister.application.BatchOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties.SourceMode;
import uk.gov.hmcts.cp.courtregister.domain.AssembledBatch;
import uk.gov.hmcts.cp.courtregister.domain.BatchAssembly;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * The night, asked for again by a person - and what that person is allowed to cause.
 *
 * <p>{@code generate-register} is the only way a register date is generated other than 18:00, so it
 * has to do the same work the run does and be stopped by the same lever. What makes it a different
 * class rather than the job with an argument is that a person's regeneration reaches rows the run
 * cannot see: a court centre whose whole night failed has its registers still stamped with the
 * batch that failed, so they are outside {@code activeUnbatched()} and no later run will ever pick
 * them up. Both halves are therefore this suite's subject at once - the day's FAILED batches, whose
 * rows are released and re-assembled, and the day's registers that never reached a batch at all.
 *
 * <p>Four claims, and each is a way the command can be wrong rather than merely incomplete:
 *
 * <ul>
 *   <li><strong>the flag first, and its answer refuses.</strong> A regeneration is a generation, so
 *       a stack whose {@code CourtRegisterService} flag says the legacy is live must not have this
 *       service e-mail a Youth Offending Team over it (constitution Cutover Rule). The refusal is
 *       {@link CliMain#REFUSED} and not {@link CliMain#FAILED}, because a runbook that retried it as
 *       a failure would be an operator overriding the cutover by accident; and where the override
 *       was passed, the run says out loud that it went ahead over a flag that would have stopped
 *       it - that is the one run in this flow most worth finding again;</li>
 *   <li><strong>the day, and only the day.</strong> Another day's failed batch, another court
 *       house's registers and rows recorded after the bound are all a wrong e-mail to a real Youth
 *       Offending Team, which is why the narrowing arguments are pinned one at a time;</li>
 *   <li><strong>no register twice.</strong> A row released from its failed batch and read again as
 *       active is the same row, and a command that handed the assembler both copies would render a
 *       hearing into a document twice. The store cannot tell a caller which of its two reads
 *       happened first, so the claim is about the registers the assembler is given rather than about
 *       the order they were read in;</li>
 *   <li><strong>{@code system_generated} is false.</strong> progression's own flag, and the only
 *       thing that tells a later reader that a person asked for this document rather than the
 *       schedule.</li>
 * </ul>
 *
 * <p>What the command prints is read by a person under pressure and is about children, so the last
 * group asserts the two halves of that: identifiers and counts reach the terminal, and nothing a
 * register carries reaches the terminal or the log at any level (constitution Principle VII,
 * FR-016).
 *
 * <p>The collaborators are doubled as small ledgers rather than with fixed answers, because what
 * the command does <em>is</em> the sequence of things it asked of them: the release, the grouping,
 * the write and the request are four moments in one batch's life, and a suite whose stubs did not
 * record them would have to assert on a return value the command has no reason to carry.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("generate-register")
class GenerateRegisterCliTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T065 wires the operations CLI; this is its red run";

    /** Returned when the command threw rather than answering, so a red run fails as an assertion. */
    private static final int NO_CODE = -1;

    /** The day a person is regenerating: the Thursday whose 18:00 run left a court centre out. */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

    /** The day before it, which nothing the command does may touch. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 19);

    /** The court centre whose whole night failed. */
    private static final UUID LEEDS = UUID.fromString("6f1d0a4c-1c2b-4f8e-9a3d-70b2c5e41a01");

    /** Another court centre of the same day, which the narrowing has to leave alone. */
    private static final UUID BRADFORD = UUID.fromString("6f1d0a4c-1c2b-4f8e-9a3d-70b2c5e41a02");

    private static final String LEEDS_HOUSE = "Leeds Youth Court";

    private static final String BRADFORD_HOUSE = "Bradford Youth Court";

    /** 09:00 Europe/London on the Thursday, which is when the first hearing was shared. */
    private static final Instant MORNING = Instant.parse("2026-08-20T08:00:00Z");

    /** 18:00 Europe/London on the Thursday: the bound a part-finished re-run is completed to. */
    private static final Instant SIX_PM = Instant.parse("2026-08-20T17:00:00Z");

    /** 08:00 the next morning, which is when the support call is made. */
    private static final Instant NEXT_MORNING = Instant.parse("2026-08-21T07:00:00Z");

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(60);

    /** What the ledger calls the moment a batch's row was written down, and its render asked for. */
    private static final String WRITTEN = "written:";

    private static final String REQUESTED = "requested:";

    /** This service's own package, whose every line the privacy case reads. */
    private static final String SERVICE_PACKAGE = "uk.gov.hmcts.cp.courtregister";

    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);
    private final RegisterStore store = mock(RegisterStore.class);
    private final BatchAssembler assembler = mock(BatchAssembler.class);
    private final RegisterGenerationService service = mock(RegisterGenerationService.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(NEXT_MORNING);

    /** What the operator's terminal shows, one entry per line the command wrote. */
    private final List<String> printed = new ArrayList<>();

    /** The day's batches the store holds, by the day they were recorded for. */
    private final Map<LocalDate, List<RegisterBatch>> recorded = new LinkedHashMap<>();

    /** The registers each batch still holds stamped, which a release gives back. */
    private final Map<UUID, List<RegisterRecord>> stamped = new LinkedHashMap<>();

    /** The registers the store calls active and unbatched, whatever day they are for. */
    private final List<RegisterRecord> unbatched = new ArrayList<>();

    /** Whether each reading of the flag was asked to override it. */
    private final List<Boolean> overrideAsked = new ArrayList<>();

    /** The days the store was asked about. */
    private final List<LocalDate> daysRead = new ArrayList<>();

    /** The batches whose registers were released, in the order they were released. */
    private final List<UUID> released = new ArrayList<>();

    /** Every grouping the command asked for. */
    private final List<Grouping> groupings = new ArrayList<>();

    /** Every render the command asked for, beside the bound it asked under. */
    private final List<Render> renders = new ArrayList<>();

    /** The writes and the requests in the one order they happened. */
    private final List<String> sequence = new ArrayList<>();

    private final GenerateRegisterCli cli = new GenerateRegisterCli(gate, store, assembler, service,
            settings(), clock, printed::add);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * One grouping the command asked the assembler for.
     *
     * @param registers       the registers it handed over
     * @param history         the batches it said the day already held
     * @param systemGenerated whether it claimed the schedule had asked
     */
    private record Grouping(List<RegisterRecord> registers, List<RegisterBatch> history,
            boolean systemGenerated) {
    }

    /**
     * One render the command asked for.
     *
     * @param batch    the batch it asked about
     * @param deadline the requesting bound it asked under
     */
    private record Render(RegisterBatch batch, Deadline deadline) {
    }

    /**
     * The settings a deployed command works to, which are the ones {@code application.yaml} ships.
     *
     * @return generation enabled, at the court's hour, in the court's zone
     */
    private static GenerationProperties settings() {
        return new GenerationProperties(true, "0 0 18 * * MON-FRI", "Europe/London", false,
                RUN_DEADLINE, Duration.ofMinutes(70), Duration.ofMinutes(10),
                GenerationProperties.COMPLETION_EVENT, SourceMode.LIVE, SourceMode.LIVE,
                SourceMode.LIVE, SourceMode.LIVE);
    }

    @BeforeEach
    void theStoreAnswersFromItsRowsAndRecordsWhatItWasAsked() {
        when(store.batchesOn(any())).thenAnswer(call -> {
            final LocalDate day = call.getArgument(0);
            daysRead.add(day);
            return recorded.getOrDefault(day, List.of());
        });
        when(store.activeUnbatched()).thenAnswer(call -> List.copyOf(unbatched));
        // A batch's registers read by identity while they are still stamped with it, which is what
        // the store answers whether or not the caller has released them yet.
        when(store.batched(any()))
                .thenAnswer(call -> stamped.getOrDefault(call.getArgument(0), List.of()));
        when(store.releaseFailed(any())).thenAnswer(call -> {
            final UUID batchId = call.getArgument(0);
            released.add(batchId);
            return stamped.getOrDefault(batchId, List.of());
        });
        when(store.assemble(any(), any())).thenAnswer(call -> {
            final RegisterBatch batch = call.getArgument(0);
            sequence.add(WRITTEN + batch.batchId());
            return batch;
        });
        when(assembler.assemble(any(), any(), anyBoolean())).thenAnswer(call -> {
            final List<RegisterRecord> registers = call.getArgument(0);
            groupings.add(new Grouping(List.copyOf(registers), call.getArgument(1),
                    call.getArgument(2)));
            return groupingOf(registers);
        });
        when(service.request(any(), any())).thenAnswer(call -> {
            final RegisterBatch batch = call.getArgument(0);
            renders.add(new Render(batch, call.getArgument(1)));
            sequence.add(REQUESTED + batch.batchId());
            return new BatchOutcome(batch.batchId(), BatchStatus.GENERATING, null, true);
        });
    }

    /**
     * Runs the command and hands back the code the process would exit on.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the code it did
     * not answer with is then asserted on as {@link #NO_CODE} - the red run is the exit code and the
     * green run is the same assertions unchanged.
     *
     * @param args the arguments an operator typed after the command's name
     * @return the exit code, or {@link #NO_CODE} where the seam refused
     */
    private int run(final String... args) {
        final AtomicInteger code = new AtomicInteger(NO_CODE);
        softly.assertThatCode(() -> code.set(cli.run(List.of(args))))
                .as(PENDING)
                .doesNotThrowAnyException();
        return code.get();
    }

    /**
     * What the flag says, and a note of whether the command asked to override it.
     *
     * @param decision what the gate answers, however it is asked
     */
    private void theFlagSays(final GateDecision decision) {
        when(gate.decide(anyBoolean())).thenAnswer(call -> {
            overrideAsked.add(call.getArgument(0));
            return decision;
        });
    }

    /** The ordinary case: the stack is cut over and nobody is overriding anything. */
    private void theFlagIsOn() {
        theFlagSays(new Proceed(false));
    }

    /**
     * A batch the day holds, recorded against the day it names.
     *
     * @param batch     the batch the store answers with
     * @param registers the registers it still holds stamped, which a release gives back
     */
    private void theDayHolds(final RegisterBatch batch, final RegisterRecord... registers) {
        recorded.computeIfAbsent(batch.registerDate(), day -> new ArrayList<>()).add(batch);
        stamped.put(batch.batchId(), List.of(registers));
    }

    /**
     * A register the store calls active and unbatched.
     *
     * @param register the register waiting for a batch
     */
    private void stillWaiting(final RegisterRecord register) {
        unbatched.add(register);
    }

    /**
     * The registers the command handed the assembler, read safely from a run that handed it none.
     *
     * @return the first grouping's registers, or empty where nothing was grouped
     */
    private List<RegisterRecord> registersGrouped() {
        return groupings.isEmpty() ? List.of() : groupings.getFirst().registers();
    }

    /**
     * The hearings behind the registers the command grouped, which is what a document holds.
     *
     * @return one identifier per register grouped, in the order they were grouped
     */
    private List<UUID> hearingsGrouped() {
        return registersGrouped().stream().map(RegisterRecord::hearingId).toList();
    }

    /**
     * Everything the operator's terminal showed, as one string.
     *
     * @return the printed lines joined by newlines
     */
    private String terminal() {
        return String.join(System.lineSeparator(), printed);
    }

    /**
     * The grouping a double makes of whatever it is given: one batch per key, no rules applied.
     *
     * <p>Which registers belong to which batch, and which key is deferred, are
     * {@code BatchAssemblerTest}'s questions and nothing here asserts them. What this has to do is
     * answer an assembly the command can carry on from, so that the write and the request are
     * reached at all.
     *
     * @param registers the registers the command handed over
     * @return one batch per key, each beside the registers it groups
     */
    private static BatchAssembly groupingOf(final List<RegisterRecord> registers) {
        final Map<CourtCentreDay, List<RegisterRecord>> byKey = registers.stream()
                .collect(Collectors.groupingBy(RegisterRecord::key, LinkedHashMap::new,
                        Collectors.toList()));
        return new BatchAssembly(byKey.entrySet().stream()
                .map(group -> new AssembledBatch(
                        batch(group.getKey(), courtHouseOf(group.getValue().getFirst()),
                                BatchStatus.PENDING),
                        group.getValue()))
                .toList(), List.of());
    }

    /**
     * Every value that must never be written down, whoever it describes.
     *
     * <p>The two lists the estate's privacy suites already sweep for, read as one: a child's own
     * details and the organisation's contact address are two different reasons for the same rule,
     * and a command's output is subject to both.
     *
     * @return the markers the fixtures carry
     */
    private static List<String> markers() {
        return Stream.concat(PersonalDataMarkers.PERSONAL.stream(),
                PersonalDataMarkers.RECIPIENT.stream()).toList();
    }

    /**
     * Which of the two moments a ledger entry records, without the identity that follows it.
     *
     * @param entry one entry of the sequence
     * @return {@link #WRITTEN} or {@link #REQUESTED}
     */
    private static String step(final String entry) {
        return entry.substring(0, entry.indexOf(':') + 1);
    }

    /**
     * The court house a register was produced at, as the batch row copies it.
     *
     * @param register the register that names the file
     * @return its court house
     */
    private static String courtHouseOf(final RegisterRecord register) {
        return register.document().hearingVenue().courtHouse();
    }

    /**
     * A batch as the store holds it.
     *
     * @param key        the court centre and register day it groups
     * @param courtHouse the court house it is described by
     * @param status     where it has got to
     * @return the batch row
     */
    private static RegisterBatch batch(final CourtCentreDay key, final String courtHouse,
            final BatchStatus status) {
        return new RegisterBatch(UUID.randomUUID(), key.courtCentreId(), "B01LY", courtHouse,
                key.registerDate(), "courtregister_" + key.registerDate() + ".json", null, null,
                status, null, null, true, null, SIX_PM, null, null, null, null, 0, null, 0);
    }

    /**
     * A register recorded at a court centre, a court house and a moment.
     *
     * <p>Every one of them carries the privacy suite's markers on the defendant and the recipient,
     * so that the last group's claim is over the same registers every other case runs on rather than
     * over a fixture written for it.
     *
     * @param courtCentre  the court centre it was recorded for
     * @param day          the register day it falls under
     * @param courtHouse   the court house it was produced at
     * @param registerTime the moment it was recorded
     * @return the register as the batch half reads it back
     */
    private static RegisterRecord register(final UUID courtCentre, final LocalDate day,
            final String courtHouse, final Instant registerTime) {
        return register(courtCentre, day, courtHouse, registerTime, registerTime);
    }

    /**
     * The same register with its shared instant and its hearing day pulled apart.
     *
     * <p>Every other case here gives one instant to both, which is what makes them the same
     * fixture; the case about what the bound is read against needs them on opposite sides of it.
     *
     * @param courtCentre  the court centre it was recorded for
     * @param day          the register day it falls under
     * @param courtHouse   the court house it was produced at
     * @param registerTime the instant the results were shared, which is what orders a batch
     * @param hearingDate  the day the hearing sat, which is a fact about the hearing and not about
     *                     when anybody shared or recorded anything
     * @return the register as the batch half reads it back
     */
    private static RegisterRecord register(final UUID courtCentre, final LocalDate day,
            final String courtHouse, final Instant registerTime, final Instant hearingDate) {
        final UUID hearingId = UUID.randomUUID();
        final String fileName = "courtregister_" + day + ".json";
        return new RegisterRecord(UUID.randomUUID(), hearingId, hearingDate,
                new CourtCentreDay(courtCentre, day), registerTime, fileName, "Applicant",
                RecordedFlagState.ON,
                new CourtRegisterDocument(registerTime.toString(), hearingDate.toString(),
                        hearingId.toString(), courtCentre.toString(), fileName, "Applicant",
                        new CourtRegisterHearingVenue("West Yorkshire", courtHouse, null),
                        List.of(new CourtRegisterRecipient(
                                PersonalDataMarkers.RECIPIENT_ORGANISATION,
                                PersonalDataMarkers.RECIPIENT_EMAIL, null, "cr_standard")),
                        List.of(new CourtRegisterDefendant(UUID.randomUUID().toString(),
                                PersonalDataMarkers.CHILD_NAME, PersonalDataMarkers.DATE_OF_BIRTH,
                                null, null, null, null, null, null, null, null, null, null,
                                null))));
    }

    /** A register of the Thursday at Leeds, recorded in the morning. */
    private static RegisterRecord leedsRegister() {
        return register(LEEDS, THURSDAY, LEEDS_HOUSE, MORNING);
    }

    /** A register of the Thursday at Bradford, recorded in the morning. */
    private static RegisterRecord bradfordRegister() {
        return register(BRADFORD, THURSDAY, BRADFORD_HOUSE, MORNING);
    }

    /**
     * The one lever, asked before anything is read, released or assembled.
     */
    @Nested
    @DisplayName("reading the flag first")
    class TheGateFirst {

        @Test
        void a_flag_that_says_off_should_refuse_with_its_own_code_and_change_nothing() {
            theFlagSays(new Skipped(Reason.FLAG_OFF));
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leedsRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(code)
                    .as("refused rather than failed: a runbook that retried a refusal would be an "
                            + "operator overriding the cutover flag by accident")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(released)
                    .as("and nothing was released, so no register left the batch it is recorded in")
                    .isEmpty();
            softly.assertThat(terminal())
                    .as("the operator is told which reading stopped it, in the bounded code the "
                            + "run's own line carries")
                    .contains(Reason.FLAG_OFF.code());
            softly.assertThat(groupings)
                    .as("nothing was grouped, so no batch row was ever a candidate")
                    .isEmpty();
            softly.assertThat(renders).as("and nobody was asked to render anything").isEmpty();
        }

        @Test
        void a_flag_nobody_could_read_should_refuse_the_same_way_and_say_which() {
            theFlagSays(new Skipped(Reason.FLAG_UNREADABLE));

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(code)
                    .as("fail-closed: a flag that could not be read is treated exactly as off")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(terminal())
                    .as("and the two are not the same answer to a person mid-cutover, so the code "
                            + "says which of them it was")
                    .contains(Reason.FLAG_UNREADABLE.code());
        }

        /**
         * What the log says a flag-off refusal was, which is what an incident view reads.
         *
         * <p>The terminal line carries the gate's own bounded code and always has. The WARN line
         * beside it is written by the refusal every command shares, and that one names a cause:
         * "its arguments were not usable". For the four argument refusals it is true; for this one
         * it is not, and it is the line indexed for the incident - so anybody searching the index
         * for why the 08:00 regeneration did nothing is told the operator mistyped rather than that
         * the cutover flag stopped them.
         */
        @Test
        void a_run_the_flag_stopped_should_not_be_logged_as_a_typing_mistake() {
            theFlagSays(new Skipped(Reason.FLAG_OFF));

            try (CapturedLog log = CapturedLog.capturing(CliMain.class)) {
                final int code = run("--" + Args.DATE, THURSDAY.toString());

                softly.assertThat(code).isEqualTo(CliMain.REFUSED);
                softly.assertThat(log.messages())
                        .as("the arguments were usable and were read; what declined the run is the "
                                + "one lever, and a log line saying otherwise sends an incident "
                                + "after the wrong thing")
                        .noneMatch(line -> line.contains("its arguments were not usable"));
                softly.assertThat(log.messages())
                        .as("and the line still says which reading it was, because that is what "
                                + "the search is for")
                        .anyMatch(line -> line.contains(Reason.FLAG_OFF.code()));
            }
        }

        @Test
        void a_run_without_the_override_should_not_ask_the_gate_for_one() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(overrideAsked)
                    .as("the gate is asked once, and asked not to override: the argument an "
                            + "operator did not type must not be the argument the gate is given")
                    .isEqualTo(List.of(false));
        }

        @Test
        void an_overridden_run_should_go_ahead_and_print_that_the_flag_was_overridden() {
            theFlagSays(new Proceed(true));
            stillWaiting(leedsRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.IGNORE_FLAG);

            softly.assertThat(overrideAsked)
                    .as("the override is the gate's decision to make, so it is passed to the gate "
                            + "rather than acted on before it")
                    .isEqualTo(List.of(true));
            softly.assertThat(code).as("and the run goes ahead").isEqualTo(CliMain.SUCCESS);
            softly.assertThat(terminal())
                    .as("printed as well as counted: a night this service generated while the flag "
                            + "said the legacy was is the one run most worth finding again")
                    .contains(Reason.OVERRIDDEN.code());
            softly.assertThat(renders).as("and the batch really was asked for").hasSize(1);
        }

        @Test
        void an_override_against_a_flag_that_says_on_should_not_claim_one() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.IGNORE_FLAG);

            softly.assertThat(code).isEqualTo(CliMain.SUCCESS);
            softly.assertThat(terminal())
                    .as("an override on a stack that is already cut over overrode nothing, and a "
                            + "terminal that said otherwise would make every regeneration look "
                            + "like one taken against the platform's wishes")
                    .doesNotContain(Reason.OVERRIDDEN.code());
        }
    }

    /**
     * What a day's regeneration is over: the batches that failed and the registers left behind.
     */
    @Nested
    @DisplayName("the day the operator named")
    class TheDay {

        @Test
        void the_days_failed_batches_should_be_released_and_re_assembled() {
            theFlagIsOn();
            final RegisterBatch failed = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            final RegisterRecord left = leedsRegister();
            theDayHolds(failed, left);

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(released)
                    .as("the four failure reasons that leave the stamp in place are exactly why "
                            + "this command exists: no run will ever pick those registers up, "
                            + "because a stamped row is not active and unbatched")
                    .isEqualTo(List.of(failed.batchId()));
            softly.assertThat(hearingsGrouped())
                    .as("and the registers it released are the ones it re-assembles")
                    .isEqualTo(List.of(left.hearingId()));
            softly.assertThat(code).isEqualTo(CliMain.SUCCESS);
        }

        @Test
        void the_days_unbatched_registers_should_be_included_beside_them() {
            theFlagIsOn();
            final RegisterRecord failed = leedsRegister();
            final RegisterRecord late = leedsRegister();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), failed);
            stillWaiting(late);

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(hearingsGrouped())
                    .as("one command answers both halves of the same support call - last night's "
                            + "court centre failed, and these hearings arrived after 18:00")
                    .containsExactlyInAnyOrder(failed.hearingId(), late.hearingId());
        }

        @Test
        void a_register_should_never_be_handed_to_the_assembler_twice() {
            theFlagIsOn();
            final RegisterRecord shared = leedsRegister();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), shared);
            // What the store looks like when the active read was taken after the release: the same
            // row, answered by both. Which read happened first is not something a caller can be
            // told, so the command has to be right either way.
            stillWaiting(shared);

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(hearingsGrouped())
                    .as("a register released from its failed batch and read back as active is one "
                            + "register; grouping both copies renders the hearing into the day's "
                            + "document twice")
                    .isEqualTo(List.of(shared.hearingId()));
        }

        @Test
        void a_batch_that_has_not_failed_should_be_left_exactly_where_it_is() {
            theFlagIsOn();
            final RegisterBatch inFlight = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.GENERATING);
            final RegisterBatch sent = batch(new CourtCentreDay(BRADFORD, THURSDAY),
                    BRADFORD_HOUSE, BatchStatus.NOTIFIED);
            theDayHolds(inFlight, leedsRegister());
            theDayHolds(sent, bradfordRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(released)
                    .as("a stamp cleared off a batch systemdocgenerator is still rendering is a "
                            + "second document for the day, and a sent one's registers have "
                            + "already reached the Youth Offending Team")
                    .isEmpty();
            softly.assertThat(renders).as("so there is nothing to ask for").isEmpty();
            softly.assertThat(code)
                    .as("and a day that needed nothing doing is a success, not a refusal")
                    .isEqualTo(CliMain.SUCCESS);
        }

        /**
         * A release is not undoable, so it is not made for a batch this run will not re-assemble.
         *
         * <p>The rows of a FAILED batch are outside {@code activeUnbatched()} only while the stamp
         * is on them. Clearing it off a key the assembler is going to defer - because another batch
         * for the key is still being rendered - hands those registers to the 18:00 run instead,
         * where they are batched as {@code system_generated=true}: not the operator's re-render, not
         * under the operator's bound, and reported as the schedule's work.
         */
        @Test
        void a_failed_batch_whose_key_is_still_being_rendered_should_be_left_where_it_is() {
            theFlagIsOn();
            final RegisterBatch inFlight = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.GENERATING);
            final RegisterBatch failed = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            theDayHolds(inFlight, leedsRegister());
            theDayHolds(failed, leedsRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(released)
                    .as("the assembler defers a key with a batch in flight, so a release made "
                            + "first would leave the rows active and unbatched for the schedule to "
                            + "pick up as its own")
                    .isEmpty();
            softly.assertThat(terminal())
                    .as("and the operator is told which batch was left alone and why, because a "
                            + "day that did nothing silently is a day somebody re-runs")
                    .contains("batch=" + failed.batchId() + " outcome=withheld reason=key-in-flight");
            softly.assertThat(code)
                    .as("a batch that has to wait for another to finish is not a failed command")
                    .isEqualTo(CliMain.SUCCESS);
        }

        @Test
        void the_days_batches_should_be_the_history_the_supplement_is_decided_from() {
            theFlagIsOn();
            final RegisterBatch sent = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.NOTIFIED);
            theDayHolds(sent, leedsRegister());
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(groupings.stream().map(Grouping::history).findFirst().orElse(null))
                    .as("a command that passed no history would answer 'no earlier batch' for "
                            + "every key, and the day's second document would be named as its "
                            + "first (design Q27)")
                    .isEqualTo(List.of(sent));
        }

        @Test
        void another_days_registers_should_be_left_where_they_are() {
            theFlagIsOn();
            final RegisterRecord thursday = leedsRegister();
            stillWaiting(thursday);
            stillWaiting(register(LEEDS, WEDNESDAY, LEEDS_HOUSE, MORNING));

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(hearingsGrouped())
                    .as("the store answers with everything waiting, whatever day it is for; a "
                            + "command that grouped all of it would generate a day nobody asked "
                            + "about and e-mail it")
                    .isEqualTo(List.of(thursday.hearingId()));
        }

        @Test
        void the_day_the_operator_named_should_be_the_day_the_store_is_asked_about() {
            theFlagIsOn();

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(daysRead)
                    .as("read once, for the day that was typed: a regeneration of the wrong date "
                            + "is an e-mail about the wrong children")
                    .isEqualTo(List.of(THURSDAY));
        }
    }

    /**
     * The three arguments that narrow a day, and the arguments that are refused instead of guessed.
     */
    @Nested
    @DisplayName("narrowing the day")
    class Narrowing {

        @Test
        void court_house_should_narrow_the_run_to_one_court_house_of_the_day() {
            theFlagIsOn();
            final RegisterBatch leeds = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            final RegisterBatch bradford = batch(new CourtCentreDay(BRADFORD, THURSDAY),
                    BRADFORD_HOUSE, BatchStatus.FAILED);
            final RegisterRecord leedsLeft = leedsRegister();
            theDayHolds(leeds, leedsLeft);
            theDayHolds(bradford, bradfordRegister());
            stillWaiting(bradfordRegister());

            run("--" + Args.DATE, THURSDAY.toString(), "--" + Args.COURT_HOUSE, LEEDS_HOUSE);

            softly.assertThat(released)
                    .as("one court house of the day, because that is what the operator was told "
                            + "on the phone")
                    .isEqualTo(List.of(leeds.batchId()));
            softly.assertThat(hearingsGrouped())
                    .as("and the other court house's registers, batched or waiting, are none of "
                            + "this run's business")
                    .isEqualTo(List.of(leedsLeft.hearingId()));
        }

        @Test
        void batch_should_narrow_the_run_to_one_batch_of_the_day() {
            theFlagIsOn();
            final RegisterBatch chosen = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            final RegisterBatch other = batch(new CourtCentreDay(BRADFORD, THURSDAY),
                    BRADFORD_HOUSE, BatchStatus.FAILED);
            final RegisterRecord chosenLeft = leedsRegister();
            theDayHolds(chosen, chosenLeft);
            theDayHolds(other, bradfordRegister());
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.BATCH, chosen.batchId().toString());

            softly.assertThat(released)
                    .as("one batch, named by the identity the run report and the public event "
                            + "both carry")
                    .isEqualTo(List.of(chosen.batchId()));
            softly.assertThat(hearingsGrouped())
                    .as("and nothing outside it: a register waiting for its first batch is not "
                            + "part of re-rendering one that failed")
                    .isEqualTo(List.of(chosenLeft.hearingId()));
        }

        /**
         * The narrowing says which batches this run may release. It does not say what the day held.
         *
         * <p>The supplementary link, the file name and the deferral are all decided from the key's
         * history (design Q27), so a run handed only the batch an operator named would reckon the
         * key had none: a failed supplement would be re-assembled at index 0 under the name of the
         * document the Youth Offending Team already has, and a key with another batch still in
         * flight would be released and then refused by {@code idx_register_batch_live_key} with its
         * rows already given back. {@code --court-house} narrows the same list, so it is the same
         * claim.
         */
        @Test
        void batch_should_narrow_what_is_released_and_not_the_history_it_is_reckoned_from() {
            theFlagIsOn();
            final RegisterBatch sent = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.NOTIFIED);
            final RegisterBatch failed = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            final RegisterRecord left = leedsRegister();
            theDayHolds(sent, leedsRegister());
            theDayHolds(failed, left);

            run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.BATCH, failed.batchId().toString());

            softly.assertThat(released)
                    .as("one batch is released, which is the whole of what the narrowing is for")
                    .isEqualTo(List.of(failed.batchId()));
            softly.assertThat(groupings.stream().map(Grouping::history).findFirst().orElse(null))
                    .as("and the assembler is still told what the key holds: a history narrowed to "
                            + "the named batch answers 'no earlier batch' for a key that has "
                            + "already been rendered and e-mailed, so the day's supplement is "
                            + "named as its first document (design Q27)")
                    .isEqualTo(List.of(sent, failed));
            softly.assertThat(hearingsGrouped())
                    .as("what is grouped is unchanged: the registers of the batch that was named")
                    .isEqualTo(List.of(left.hearingId()));
        }

        @Test
        void recorded_before_should_bound_the_run_to_what_had_been_recorded_by_then() {
            theFlagIsOn();
            final RegisterRecord morning = register(LEEDS, THURSDAY, LEEDS_HOUSE, MORNING);
            stillWaiting(morning);
            stillWaiting(register(LEEDS, THURSDAY, LEEDS_HOUSE, SIX_PM));

            run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.RECORDED_BEFORE, SIX_PM.toString());

            softly.assertThat(hearingsGrouped())
                    .as("how a day part-way through being re-run is finished off without picking "
                            + "up what has arrived since; the bound excludes the instant itself, "
                            + "so a run bounded at what it already did cannot repeat it")
                    .isEqualTo(List.of(morning.hearingId()));
        }

        /**
         * <strong>[A] characterisation.</strong> The bound already reads the register's own shared
         * instant - {@code register_time}, which is progression's own column and the moment the
         * estate agrees on - and this states it rather than driving it. Every other case here gives
         * one instant to the shared moment and to the hearing day at once, so which of the two the
         * bound is read against was held down by nothing at all. It is the same column
         * {@code supersede-before --shared-before} bounds on, and it is deliberately not this pod's
         * own recording time, which no register record carries. Green on introduction; non-vacuity
         * is the mutation quoted in this commit.
         */
        @Test
        void the_bound_should_be_read_against_the_registers_own_shared_instant() {
            theFlagIsOn();
            final RegisterRecord sharedInside =
                    register(LEEDS, THURSDAY, LEEDS_HOUSE, MORNING, SIX_PM);
            stillWaiting(sharedInside);
            stillWaiting(register(LEEDS, THURSDAY, LEEDS_HOUSE, SIX_PM, MORNING));

            run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.RECORDED_BEFORE, SIX_PM.toString());

            softly.assertThat(hearingsGrouped())
                    .as("the register shared before the bound is in and the one shared at it is "
                            + "out, whichever side of the bound the hearing itself sat on: the "
                            + "hearing day is a fact about the hearing and says nothing about "
                            + "which registers a part-finished re-run has already dealt with")
                    .isEqualTo(List.of(sharedInside.hearingId()));
        }

        /**
         * The bound is the whole point of the argument, so nothing may leave it by being released.
         *
         * <p>A row released from a FAILED batch and then dropped by the bound is a row nobody asked
         * about that is now active, unbatched and flag-ON: the 18:00 run assembles it as
         * {@code system_generated=true}, which is exactly the "picking up what has arrived since"
         * this argument exists to prevent. The batch is therefore left carrying its stamp, which is
         * where it was, and the operator is told.
         */
        @Test
        void a_failed_batch_holding_a_register_outside_the_bound_should_be_left_where_it_is() {
            theFlagIsOn();
            final RegisterBatch failed = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            theDayHolds(failed, register(LEEDS, THURSDAY, LEEDS_HOUSE, MORNING),
                    register(LEEDS, THURSDAY, LEEDS_HOUSE, SIX_PM));

            final int code = run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.RECORDED_BEFORE, SIX_PM.toString());

            softly.assertThat(released)
                    .as("a release the run then bounds out of itself is a register handed to the "
                            + "schedule, outside the bound the operator stated and written down as "
                            + "the schedule's own work")
                    .isEmpty();
            softly.assertThat(hearingsGrouped())
                    .as("so nothing of that batch is grouped either")
                    .isEmpty();
            softly.assertThat(terminal())
                    .as("and the batch that was left alone is named, with the bounded reason it "
                            + "was left under")
                    .contains("batch=" + failed.batchId()
                            + " outcome=withheld reason=outside-the-bound");
            softly.assertThat(code)
                    .as("a bound that excluded a batch is the argument working, not a failure")
                    .isEqualTo(CliMain.SUCCESS);
        }

        @Test
        void a_run_with_no_day_should_be_refused_rather_than_guessed() {
            theFlagIsOn();

            final int code = run();

            softly.assertThat(code)
                    .as("today is a guess, and a guess here is a register date somebody else's "
                            + "pipeline is generating")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(daysRead)
                    .as("and it is refused before the store is asked about any day at all")
                    .isEmpty();
            softly.assertThat(groupings).isEmpty();
            softly.assertThat(renders).isEmpty();
        }

        @Test
        void a_day_that_is_not_a_date_should_be_refused_rather_than_guessed() {
            theFlagIsOn();

            final int code = run("--" + Args.DATE, "last-tuesday");

            softly.assertThat(code)
                    .as("the refusal belongs where the command can say which argument it could "
                            + "not use, and it changes nothing on the way to saying so")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(released).isEmpty();
        }

        @Test
        void an_instant_that_is_not_an_instant_should_be_refused_rather_than_guessed() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.RECORDED_BEFORE, "yesterday");

            softly.assertThat(code)
                    .as("a bound nobody can read is not the same as no bound: the run it would "
                            + "have narrowed is the whole day")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(renders).isEmpty();
        }

        @Test
        void an_argument_this_command_does_not_take_should_be_refused() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            final int code = run("--" + Args.DATE, THURSDAY.toString(),
                    "--" + Args.SHARED_BEFORE, SIX_PM.toString());

            softly.assertThat(code)
                    .as("another command's argument is a mistyped invocation, and an operator "
                            + "regenerating a day at the wrong bound is an e-mail to the wrong "
                            + "Youth Offending Team")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(renders).isEmpty();
        }
    }

    /**
     * What is written down for the batches a person asked for, and in what order.
     */
    @Nested
    @DisplayName("what a person's batch is written down as")
    class WhatIsWrittenDown {

        @Test
        void every_batch_a_person_asked_for_should_be_stamped_as_not_system_generated() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(groupings.stream().map(Grouping::systemGenerated).toList())
                    .as("progression's own flag, and the only thing that tells a later reader "
                            + "that a person asked for this document rather than 18:00")
                    .isEqualTo(List.of(false));
        }

        @Test
        void every_batch_should_be_written_down_before_its_render_is_asked_for() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(sequence)
                    .as("everything downstream is keyed on the batch, so a render asked for "
                            + "before the row exists finds no registers, fails the batch and then "
                            + "asks the store to fail a batch that is not there")
                    .hasSize(2);
            softly.assertThat(sequence.stream().map(GenerateRegisterCliTest::step).toList())
                    .as("the write, and then the request")
                    .isEqualTo(List.of(WRITTEN, REQUESTED));
        }

        @Test
        void the_requesting_should_be_bounded_by_the_same_deadline_the_schedule_uses() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(renders.stream().map(Render::deadline).toList())
                    .as("a command and the schedule read the same settings, so a regeneration "
                            + "cannot outlive the bound the run deadline states")
                    .isEqualTo(List.of(Deadline.startingAt(NEXT_MORNING, RUN_DEADLINE)));
        }

        @Test
        void a_store_that_could_not_answer_should_fail_rather_than_refuse() {
            theFlagIsOn();
            when(store.batchesOn(any())).thenThrow(new StoreUnavailableException(
                    "the store could not be reached to read a day's batches",
                    new IllegalStateException("row " + PersonalDataMarkers.CHILD_NAME)));

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(code)
                    .as("the command tried and could not, which a runbook may retry - unlike a "
                            + "refusal, which it must not")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(terminal())
                    .as("and what the datasource said about a row it could not read is not the "
                            + "operator's terminal's business")
                    .doesNotContain(PersonalDataMarkers.CHILD_NAME);
        }
    }

    /**
     * The output an operator reads, which is a report about children.
     */
    @Nested
    @DisplayName("what the operator is shown")
    class WhatIsPrinted {

        /**
         * The one line the command answers a day with, asserted whole.
         *
         * <p><strong>[A] characterisation.</strong> The line has been printed in this shape since
         * the command landed; nothing here changes it. What was missing is a case that reads it:
         * quickstart.md quotes it verbatim as what a compose run prints and the Phase 7 checkpoint
         * records it as what was observed, while every case in this suite reads the counts out of
         * the ledgers the doubles keep instead - so a renamed field, a dropped one or two counts
         * swapped would have shipped green against the one line a runbook step tells an operator to
         * read.
         *
         * <p>Six numbers, all of them different: a FAILED batch released, a register that never
         * reached one, one batch assembled out of the two of them, one render asked for and one key
         * the assembler deferred. A day arranged so that any two of the counts could stand in for
         * each other would pin the arithmetic rather than the line.
         */
        @Test
        void the_day_should_be_answered_with_one_line_of_counts_in_the_documented_order() {
            theFlagIsOn();
            final RegisterBatch failed = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            theDayHolds(failed, leedsRegister());
            stillWaiting(leedsRegister());
            theAssemblerDefers(new CourtCentreDay(BRADFORD, THURSDAY));

            final int code = run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(printed)
                    .as("the summary line an operator and a runbook step both read: the day, what "
                            + "was released, what was grouped, what was written down, what was "
                            + "asked for and what waits for a later run")
                    .contains("date=" + THURSDAY + " released=1 registers=2 batches=1 requested=1"
                            + " deferred=1");
            softly.assertThat(code).isEqualTo(CliMain.SUCCESS);
        }

        /**
         * The assembler, answering one batch out of everything it was given and deferring a key.
         *
         * <p>The suite's own double answers one batch per key and defers nothing, which is what
         * every other case wants. The line above needs a deferral, and a deferral is the one number
         * on it that no arrangement of registers can produce: a key waits because a batch of its
         * own is still in flight, which is the assembler's rule and not this command's.
         *
         * @param deferred the keys the assembler passed over
         */
        private void theAssemblerDefers(final CourtCentreDay... deferred) {
            // doAnswer rather than when(...), which would call the mock and run the stub this one
            // replaces, over the nulls the matchers stand in for.
            doAnswer(call -> {
                final List<RegisterRecord> registers = call.getArgument(0);
                groupings.add(new Grouping(List.copyOf(registers), call.getArgument(1),
                        call.getArgument(2)));
                return new BatchAssembly(List.of(new AssembledBatch(
                        batch(registers.getFirst().key(), LEEDS_HOUSE, BatchStatus.PENDING),
                        registers)), List.of(deferred));
            }).when(assembler).assemble(any(), any(), anyBoolean());
        }

        @Test
        void the_batches_a_run_asked_for_should_be_named_by_their_own_identity() {
            theFlagIsOn();
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            final List<String> asked = renders.stream().map(Render::batch)
                    .map(RegisterBatch::batchId).map(UUID::toString).toList();
            softly.assertThat(asked).as("a batch was asked for at all").hasSize(1);
            asked.forEach(batchId -> softly.assertThat(terminal())
                    .as("the identity is what correlates the terminal, the run report, the batch "
                            + "row and systemdocgenerator's event, so an operator has to be able "
                            + "to read it off the command they just ran")
                    .contains(batchId));
        }

        @Test
        void nothing_a_register_carries_should_reach_the_operators_terminal() {
            theFlagIsOn();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leedsRegister());
            stillWaiting(leedsRegister());

            run("--" + Args.DATE, THURSDAY.toString());

            softly.assertThat(printed)
                    .as("a command that printed nothing would satisfy the markers below vacuously")
                    .isNotEmpty();
            for (final String marker : markers()) {
                softly.assertThat(terminal())
                        .as("every defendant on a register is a child and a terminal is copied "
                                + "into tickets, chats and screenshots; a recipient is a masked "
                                + "address at most (constitution Principle VII, FR-016): %s",
                                marker)
                        .doesNotContain(marker);
            }
        }

        @Test
        void nothing_a_register_carries_should_reach_the_log_either() {
            theFlagIsOn();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leedsRegister());
            stillWaiting(leedsRegister());

            try (CapturedLog log = CapturedLog.everythingAndAllOf(SERVICE_PACKAGE)) {
                run("--" + Args.DATE, THURSDAY.toString());

                softly.assertThat(printed)
                        .as("a command that said nothing at all would satisfy the markers below "
                                + "vacuously")
                        .isNotEmpty();
                for (final String marker : markers()) {
                    softly.assertThat(log.renderings())
                            .as("a register's own details reached the log index: %s", marker)
                            .noneMatch(line -> line.contains(marker));
                }
            }
        }
    }

    /**
     * The day was regenerated and the destination stopped taking the report part-way through it.
     *
     * <p><strong>The summary is written after the day has been written down and asked for.</strong>
     * A batch's own line goes out as the render is requested and the counts follow at the end, so
     * {@code startup.sh generate-register --date D | head -1} - or a terminal whose far end has
     * gone - refuses the summary with the registers already stamped into a batch row and
     * systemdocgenerator already asked. Caught as though the store had refused, that would tell an
     * operator the day stands as whatever the run had written down, which is exactly the sentence
     * that sends them to {@code list-batches} and then to a second regeneration of a day that has
     * already been generated - and the second run releases nothing, because the batch it would
     * re-assemble is in flight rather than FAILED, so the day they were told to fix looks untouched
     * by the command they ran to fix it.
     *
     * <p>So the refusal leaves this command untouched, for {@link CliMain} to answer on
     * {@link CliMain#FAILED} with one log line and no second write to the destination that has just
     * refused one. What the day did is on the batch line the destination took, and the exit code
     * says the operator did not get the whole of the answer.
     */
    @Nested
    @DisplayName("a report the destination refused")
    class AReportRefused {

        /** How many lines the destination takes before it refuses, as {@code head -1} takes one. */
        private static final int TAKES_ONE_LINE = 1;

        /** What the boundary says of a line it could not write, in this service's own words. */
        private static final String NOT_WRITTEN =
                "a command's report line could not be written to standard output";

        /** The summary of the day this case arranges, which is the line that is refused. */
        private static final String SUMMARY = "date=" + THURSDAY
                + " released=0 registers=1 batches=1 requested=1 deferred=0";

        /** Every line the report tried to write, the refused one included. */
        private final List<String> attempted = new ArrayList<>();

        /** The destination at the far end of {@code generate-register | head -1}: one line. */
        private final Consumer<String> refuses = line -> {
            attempted.add(line);
            if (attempted.size() > TAKES_ONE_LINE) {
                throw new ReportNotWritten(NOT_WRITTEN, new IOException("Broken pipe"));
            }
        };

        @Test
        void a_refused_report_should_not_be_reported_as_a_regeneration_that_failed() {
            theFlagIsOn();
            stillWaiting(leedsRegister());
            final GenerateRegisterCli refused = new GenerateRegisterCli(gate, store, assembler,
                    service, settings(), clock, refuses);

            try (CapturedLog log = CapturedLog.capturing(GenerateRegisterCli.class)) {
                softly.assertThatThrownBy(
                                () -> refused.run(List.of("--" + Args.DATE, THURSDAY.toString())))
                        .as("the refusal reaches the caller, which is the one place it can be "
                                + "answered without a terminal: the destination has already "
                                + "refused a line, so a verdict written there fails the same way")
                        .isInstanceOf(ReportNotWritten.class);
                softly.assertThat(log.events())
                        .as("and nothing says the regeneration did not finish, because it did: the "
                                + "registers are stamped into a batch and the render is asked for, "
                                + "and an operator told otherwise runs a second regeneration that "
                                + "can release nothing")
                        .noneMatch(event -> event.getLevel() == Level.ERROR);
            }
            softly.assertThat(attempted)
                    .as("the batch line the destination took and the summary it refused, and no "
                            + "verdict about the day after them")
                    .hasSize(2);
            softly.assertThat(attempted.getLast())
                    .as("the last thing written was the day's own counts, not a sentence about a "
                            + "regeneration that did not happen")
                    .isEqualTo(SUMMARY);
            softly.assertThat(sequence.stream().map(GenerateRegisterCliTest::step).toList())
                    .as("the day was written down and asked for, and a report the destination "
                            + "would not take changes neither")
                    .containsExactly(WRITTEN, REQUESTED);
        }
    }
}
