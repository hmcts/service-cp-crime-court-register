package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.domain.AssembledBatch;
import uk.gov.hmcts.cp.courtregister.domain.BatchAssembly;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * {@code generate-register --date D [--court-house H] [--batch B] [--ignore-flag]
 * [--recorded-before T]}.
 *
 * <p>What the nightly run does, asked for by a person: the register date's FAILED batches are
 * re-assembled and re-requested and its records that never reached a batch are included, so the
 * one command answers both "last night's court centre failed" and "these hearings arrived after
 * 18:00". {@code --court-house} narrows it to one court house of the date and {@code --batch} to
 * one batch of it; {@code --recorded-before} bounds it to what had been recorded by an instant,
 * which is how a date part-way through being re-run is finished off without picking up what has
 * arrived since.
 *
 * <p><strong>The flag is asked first, through the same gate the schedule uses.</strong> A
 * regeneration is a generation, and a stack whose {@code CourtRegisterService} flag says the legacy
 * is live must not have this service e-mail a Youth Offending Team over it (constitution Cutover
 * Rule). So the command refuses - {@link CliMain#REFUSED}, nothing assembled, nothing requested -
 * unless {@code --ignore-flag} was passed, and where it was, the override is printed as well as
 * counted: the run report and the operator's own terminal both have to say that a run went ahead
 * the flag would have stopped, because that is the one run in this flow most worth finding again.
 *
 * <p>Every batch it assembles is written down as not system-generated, which is progression's own
 * flag and is what tells a later reader that a person asked for this document rather than 18:00.
 *
 * <p><strong>A register is handed to the assembler once.</strong> A row released from its failed
 * batch is answered by {@link RegisterStore#activeUnbatched()} as well, and the store cannot tell a
 * caller which of the two reads happened first - so the two sources are merged on the register's own
 * identity rather than on the order they arrived in. Grouping both copies would render a hearing
 * into the day's document twice.
 */
public class GenerateRegisterCli {

    /** What the command takes, printed under a refusal and on request. */
    private static final String USAGE = "usage: " + CliMain.GENERATE_REGISTER + " --" + Args.DATE
            + " D [--" + Args.COURT_HOUSE + " H] [--" + Args.BATCH + " B] [--" + Args.IGNORE_FLAG
            + "] [--" + Args.RECORDED_BEFORE + " T]";

    private static final Logger LOG = LoggerFactory.getLogger(GenerateRegisterCli.class);

    /** What this command could not finish, as the bounded reason the line carries. */
    private static final String NOT_GENERATED = "generation-failed";

    /**
     * False, because this is a person asking rather than the schedule.
     *
     * <p>progression's own flag, and the only thing that tells a later reader that a person asked
     * for this document rather than 18:00.
     */
    private static final boolean BY_HAND = false;

    /** The one lever, asked first and through the same gate the 18:00 run is stopped by. */
    private final FeatureFlagGate gate;

    /** Where the day's batches are read, its FAILED ones released and its new ones stamped. */
    private final RegisterStore store;

    /** The grouping, asked to say false where the schedule says true. */
    private final BatchAssembler assembler;

    /** The requesting leg, asked once per batch and sequentially, as the run asks it. */
    private final RegisterGenerationService service;

    /** The settings the command works to, the run deadline above all. */
    private final GenerationProperties properties;

    /** This pod's reading of now, which the requesting deadline is measured from. */
    private final Clock clock;

    /**
     * Where the command's lines go, one line per call.
     *
     * <p>Handed in rather than reached for, so that a test reads exactly what an operator would see
     * and a command cannot write anywhere else.
     */
    private final Consumer<String> output;

    /**
     * Creates the command over the four collaborators a generation needs and the stream it reports
     * on.
     *
     * @param featureFlagGate   the one lever, asked before anything is read or assembled
     * @param registerStore     where the day's batches are read, its FAILED ones released and its
     *                          new ones written down
     * @param batchAssembler    the grouping into one batch per court centre and register date
     * @param generationService the requesting leg, asked once per batch and sequentially
     * @param generationSettings the settings the command works to, the run deadline above all
     * @param runClock          this pod's reading of now, which the deadline is measured from
     * @param reportTo          where the command's lines are written, one line per call
     */
    public GenerateRegisterCli(final FeatureFlagGate featureFlagGate,
            final RegisterStore registerStore, final BatchAssembler batchAssembler,
            final RegisterGenerationService generationService,
            final GenerationProperties generationSettings, final Clock runClock,
            final Consumer<String> reportTo) {
        this.gate = featureFlagGate;
        this.store = registerStore;
        this.assembler = batchAssembler;
        this.service = generationService;
        this.properties = generationSettings;
        this.clock = runClock;
        this.output = reportTo;
    }

    /**
     * Re-assembles and re-requests the register date the arguments name.
     *
     * @param args the arguments that followed {@code generate-register}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} where the flag said no or the
     *         arguments were not usable, or {@link CliMain#FAILED}
     */
    // PMD.OnlyOneReturn: the six exits are the six things that can happen to an invocation, each
    // said where it is decided; one exit would carry a verdict past the flag read and the store
    // reads that must not be made once the arguments have been refused.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public int run(final List<String> args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.GENERATE_REGISTER, USAGE, notUsable, output);
        }
        if (parsed.askedForHelp()) {
            output.accept(USAGE);
            return CliMain.SUCCESS;
        }
        if (!parsed.permits(Set.of(Args.DATE, Args.COURT_HOUSE, Args.BATCH, Args.RECORDED_BEFORE),
                Set.of(Args.IGNORE_FLAG))) {
            return refuse(CliMain.UNEXPECTED_ARGUMENT);
        }
        if (parsed.options().get(Args.DATE) == null) {
            return refuse(CliMain.MISSING_ARGUMENT);
        }
        final Selection selection;
        try {
            selection = selectionOf(parsed);
        } catch (DateTimeParseException | IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.GENERATE_REGISTER, USAGE, notUsable, output);
        }
        return gated(selection);
    }

    /**
     * What an operator typed, read into the four things a regeneration is narrowed by.
     *
     * <p>Every value is interpreted here and before the flag is read, so a mistyped invocation
     * costs an operator a refusal rather than a run: a bound nobody can read is not the same as no
     * bound, and the run it would have narrowed is the whole day.
     *
     * @param parsed the names and values the parser read
     * @return the day, the narrowing and whether an override was asked for
     * @throws DateTimeParseException   where the date or the instant is not one
     * @throws IllegalArgumentException where the batch is not an identity
     */
    private static Selection selectionOf(final Args parsed) {
        final Map<String, String> options = parsed.options();
        final String batch = options.get(Args.BATCH);
        final String recordedBefore = options.get(Args.RECORDED_BEFORE);
        return new Selection(
                LocalDate.parse(options.get(Args.DATE)),
                options.get(Args.COURT_HOUSE),
                batch == null ? null : UUID.fromString(batch),
                recordedBefore == null ? null : Instant.parse(recordedBefore),
                parsed.flags().contains(Args.IGNORE_FLAG));
    }

    /**
     * Reads the one lever, and generates only where its answer or an override allows it.
     *
     * <p>The gate is asked before anything is read, released or assembled, and it is asked the same
     * question the schedule asks it: a run that read the store first would already have stamped
     * rows into batches the flag says this service may not generate (constitution Cutover Rule).
     * The override is passed to the gate rather than acted on before it, so that
     * {@code --ignore-flag} on a stack that is already cut over overrides nothing and is not
     * reported as though it had.
     *
     * @param selection the day, the narrowing and whether an override was asked for
     * @return {@link CliMain#REFUSED} where the flag stopped it, otherwise whatever the run did
     */
    // PMD.OnlyOneReturn: a refusal changes nothing and is said where the gate says so, which is
    // what keeps the reads below out of a run the flag stopped.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private int gated(final Selection selection) {
        final GateDecision decision = gate.decide(selection.ignoreFlag());
        if (decision instanceof Skipped skipped) {
            return CliMain.refusal(CliMain.GENERATE_REGISTER, USAGE, skipped.reason().code(),
                    output);
        }
        if (decision instanceof Proceed proceed && proceed.overridden()) {
            // Printed as well as counted: a night this service generated while the flag said the
            // legacy was is the one run in this flow most worth finding again.
            output.accept("command=" + CliMain.GENERATE_REGISTER + " reason="
                    + Reason.OVERRIDDEN.code());
        }
        return generate(selection);
    }

    /**
     * The day, re-assembled and re-requested: read, release, group, write down, ask.
     *
     * @param selection the day and the narrowing it is bounded by
     * @return {@link CliMain#SUCCESS} where the day was worked through, {@link CliMain#FAILED}
     *         where it could not be
     */
    // PMD.AvoidCatchingGenericException: the store translates an outage into its own unchecked type
    // and refuses a stamp with another; both mean the same thing here - this regeneration did not
    // finish - and a summary printed over either would say a day had been asked for that had not.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private int generate(final Selection selection) {
        try {
            final List<RegisterBatch> day = store.batchesOn(selection.registerDate());
            final List<RegisterRecord> released = released(narrowed(day, selection));
            final List<RegisterRecord> registers = registers(released, selection);
            final BatchAssembly assembly = assembler.assemble(registers, day, BY_HAND);
            final int requested = request(assembly);
            output.accept("date=" + selection.registerDate()
                    + " released=" + released.size()
                    + " registers=" + registers.size()
                    + " batches=" + assembly.batches().size()
                    + " requested=" + requested
                    + " deferred=" + assembly.deferred().size());
            return CliMain.SUCCESS;
        } catch (RuntimeException notGenerated) {
            LOG.error("The regeneration of register date {} did not finish, so the day stands as "
                    + "whatever this run had already written down. cause={}",
                    selection.registerDate(), notGenerated.getClass().getName(), notGenerated);
            return CliMain.failure(CliMain.GENERATE_REGISTER, "date=" + selection.registerDate(),
                    NOT_GENERATED, output);
        }
    }

    /**
     * The day's batches this run is about, which is all of them unless an argument narrowed it.
     *
     * <p>One court house of the day, or one batch of it, because that is what the operator was told
     * on the phone: another court house's registers are a wrong e-mail to a real Youth Offending
     * Team.
     *
     * <p><strong>This narrows what may be released and nothing else.</strong> The key's history is
     * the day as the store holds it: the supplementary link, the file name and the deferral are all
     * decided from it (design Q27), so an assembler told only about the batch an operator named
     * would name the day's supplement as its first document and would release a key whose other
     * batch is still being rendered.
     *
     * @param day       every batch the day holds, whatever state it reached
     * @param selection the narrowing an operator asked for
     * @return the batches this run may release
     */
    private static List<RegisterBatch> narrowed(final List<RegisterBatch> day,
            final Selection selection) {

        return day.stream()
                .filter(batch -> selection.batchId() == null
                        || selection.batchId().equals(batch.batchId()))
                .filter(batch -> selection.courtHouse() == null
                        || selection.courtHouse().equals(batch.courtHouse()))
                .toList();
    }

    /**
     * The registers given back by the day's FAILED batches, in the order those batches were read.
     *
     * <p>The four failure reasons that leave the stamp in place are exactly why this command
     * exists: no later run will ever pick those registers up, because a stamped row is not active
     * and unbatched. A batch that has not failed is left exactly where it is - a stamp cleared off
     * one systemdocgenerator is still rendering is a second document for the day, and a notified
     * one's registers have already reached the Youth Offending Team.
     *
     * @param narrowed the day's batches this run is about
     * @return the registers whose stamp was cleared
     */
    private List<RegisterRecord> released(final List<RegisterBatch> narrowed) {
        final List<RegisterRecord> released = new ArrayList<>();
        narrowed.stream()
                .filter(batch -> batch.status() == BatchStatus.FAILED)
                .forEach(batch -> released.addAll(store.releaseFailed(batch.batchId())));
        return released;
    }

    /**
     * The registers this run hands the assembler: what it released, and what never reached a batch.
     *
     * <p>Merged on the register's own identity, because the two reads overlap: a row released a
     * moment ago is active and unbatched by the time the second read is taken, and the store cannot
     * tell a caller which of them happened first. Handing the assembler both copies would render
     * the hearing into the day's document twice.
     *
     * <p><strong>The active read is not taken at all where {@code --batch} named one.</strong>
     * A register waiting for its first batch is not part of re-rendering one that failed, and
     * including it would make a narrowed re-run wider than the batch the operator named.
     *
     * @param released  the registers the day's FAILED batches gave back
     * @param selection the day and the narrowing every register is held to
     * @return the registers to be grouped, first seen first
     */
    private List<RegisterRecord> registers(final List<RegisterRecord> released,
            final Selection selection) {

        final Map<UUID, RegisterRecord> byIdentity = new LinkedHashMap<>();
        released.forEach(register -> byIdentity.putIfAbsent(register.outputId(), register));
        if (selection.batchId() == null) {
            store.activeUnbatched()
                    .forEach(register -> byIdentity.putIfAbsent(register.outputId(), register));
        }
        return byIdentity.values().stream().filter(selection::holds).toList();
    }

    /**
     * Writes each batch down and then asks for its render, in that order and one at a time.
     *
     * <p>Everything downstream is keyed on the batch, so a render asked for before the row exists
     * would find no registers, fail the batch and then ask the store to fail a batch that is not
     * there. The bound is computed once, at the moment requesting begins, and is the same run
     * deadline the schedule works to: a regeneration cannot outlive it by re-deriving it per batch.
     *
     * @param assembly what the assembler made of the day
     * @return how many batches were asked for
     */
    private int request(final BatchAssembly assembly) {
        final Deadline deadline = Deadline.startingAt(clock.instant(), properties.runDeadline());
        int requested = 0;
        for (final AssembledBatch assembled : assembly.batches()) {
            final RegisterBatch stored = store.assemble(assembled.batch(), assembled.records());
            final BatchStatus status = service.request(stored, deadline).status();
            output.accept("batch=" + stored.batchId() + " state=" + status
                    + " records=" + assembled.records().size());
            requested++;
        }
        return requested;
    }

    /**
     * Declines, under the bounded reason and this command's own usage.
     *
     * @param reason one of {@link CliMain}'s three argument reasons
     * @return {@link CliMain#REFUSED}
     */
    private int refuse(final String reason) {
        return CliMain.refusal(CliMain.GENERATE_REGISTER, USAGE, reason, output);
    }

    /**
     * What an operator asked for, read once and never re-interpreted.
     *
     * @param registerDate   the London register day being regenerated
     * @param courtHouse     one court house of it, or {@code null} for all of them
     * @param batchId        one batch of it, or {@code null} for all of them
     * @param recordedBefore the exclusive bound on what had been recorded, or {@code null} for the
     *                       whole day
     * @param ignoreFlag     whether the operator asked to proceed over a flag that says otherwise
     */
    private record Selection(LocalDate registerDate, String courtHouse, UUID batchId,
            Instant recordedBefore, boolean ignoreFlag) {

        /**
         * Whether one register is part of the run the operator asked for.
         *
         * <p>The store answers with everything waiting, whatever day it is for, so the day is
         * tested here as well as asked for: a command that grouped all of it would generate a day
         * nobody asked about and e-mail it.
         *
         * @param register a register released or waiting
         * @return true where the day, the court house and the bound all admit it
         */
        private boolean holds(final RegisterRecord register) {
            return registerDate.equals(register.key().registerDate())
                    && (courtHouse == null || courtHouse.equals(courtHouseOf(register)))
                    && (recordedBefore == null
                            || register.registerTime().isBefore(recordedBefore));
        }

        /**
         * The court house a register was produced at, as the batch row copies it.
         *
         * @param register the register that names it
         * @return its court house, or {@code null} where the document names no venue
         */
        private static String courtHouseOf(final RegisterRecord register) {
            return register.document() == null || register.document().hearingVenue() == null
                    ? null
                    : register.document().hearingVenue().courtHouse();
        }
    }
}
