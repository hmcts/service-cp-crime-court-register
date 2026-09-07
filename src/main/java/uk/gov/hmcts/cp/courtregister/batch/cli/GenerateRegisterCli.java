package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;

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
 * <p><strong>Seam only.</strong> The command lands with T065; until then {@link #run(List)} throws,
 * so that {@code GenerateRegisterCliTest} records a failing assertion rather than a compile error.
 * The constructor is the real one from the start, for the reason
 * {@code application/RegisterNotifierService}'s is: it is the surface the unit suite builds the
 * command on and the surface {@code CliMain} will wire the context's beans through, so landing it
 * with the test is what makes T062 writable before T065 exists.
 */
// PMD.UnusedPrivateField: the seven collaborators are held from the seam onwards so that the test
// and the wiring are written against the constructor the command will keep, and run() is what reads
// them - which is T065. The suppression goes with the throw.
@SuppressWarnings("PMD.UnusedPrivateField")
public class GenerateRegisterCli {

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
    public int run(final List<String> args) {
        throw new UnsupportedOperationException("T065");
    }
}
