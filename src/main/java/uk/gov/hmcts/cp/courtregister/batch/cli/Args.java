package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an operator typed after a command's name, read once and read the same way by every command.
 *
 * <p>Plain {@code --name value} pairs and bare {@code --name} switches, parsed here rather than by
 * a library: the five commands between them take seven arguments, and a dependency that brought
 * usage text, type conversion and a shell-completion generator with it would be a larger surface
 * than the thing it parses (research §13). One parser also means one answer to the questions that
 * actually cause trouble - an option given twice, an option given no value, a value that looks like
 * another option - so two commands cannot disagree about what a mistyped invocation meant.
 *
 * <p>Nothing here interprets a value. A date is a date and an instant an instant to the command
 * that asked for one, because the refusal for {@code --date last-tuesday} belongs where the command
 * can say which argument it could not use; this type only says which names were given and what
 * followed them.
 *
 * <p>An argument the parser could not make sense of is a refusal rather than a guess
 * ({@link CliMain#REFUSED}): an operator regenerating a register date at the wrong court house is
 * an e-mail to the wrong Youth Offending Team, and a tool that inferred what was probably meant
 * would be the thing that sent it.
 *
 * @param options the {@code --name value} pairs, by name without the leading dashes
 * @param flags   the bare {@code --name} switches that were present, without the leading dashes
 */
public record Args(Map<String, String> options, Set<String> flags) {

    /** The register date a command works on, as an ISO local date. */
    public static final String DATE = "date";

    /** One court house of that date, where a command is narrowed to one. */
    public static final String COURT_HOUSE = "court-house";

    /** One batch, by its identifier. */
    public static final String BATCH = "batch";

    /** The register date's records recorded before this instant, and no later ones. */
    public static final String RECORDED_BEFORE = "recorded-before";

    /** The records recorded before this instant, whichever date they are for. */
    public static final String SHARED_BEFORE = "shared-before";

    /** Proceed although the flag did not say on, which only an operator may ask for. */
    public static final String IGNORE_FLAG = "ignore-flag";

    /** List what was recorded while the flag was off rather than what a date holds. */
    public static final String RECORDED_WHILE_OFF = "recorded-while-off";

    /** Print what the command takes and do nothing else. */
    public static final String HELP = "help";

    /**
     * Reads one command's arguments.
     *
     * @param args the arguments that followed the command's name, in the order they were given
     * @return the names that were given and the values that followed them
     * @throws IllegalArgumentException where the arguments cannot be read as options and switches
     */
    public static Args parse(final List<String> args) {
        throw new UnsupportedOperationException("T065");
    }
}
