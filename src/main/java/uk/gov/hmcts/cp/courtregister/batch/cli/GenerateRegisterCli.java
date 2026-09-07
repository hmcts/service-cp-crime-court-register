package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;

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
 * <p>Collaborators - the gate, the store, the assembler and the generation service - arrive with
 * T065; this is the seam T062 is written against.
 */
public class GenerateRegisterCli {

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
