package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p><strong>What a refusal says is part of the grammar.</strong> A refusal here is thrown, and a
 * throw is written down by whoever catches it: a command turns it into a bounded report line and a
 * log line, and any later handler may write the message whole. The token it refused on is an
 * operator's own text, so a message repeats a name where {@link #NAMES} owns it and says nothing at
 * all where it does not (constitution Principle VII).
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

    /**
     * The register date's records shared before this instant, and no later ones.
     *
     * <p>The register's own shared instant, which is the same thing {@link #SHARED_BEFORE} bounds
     * on: this service's record of a register does not carry the moment the row was written, and a
     * period of this pod's writes is not what either command narrows.
     */
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
     * Every name the five commands between them take, and the whole of what a refusal may repeat.
     *
     * <p>These eight are this service's own text, fixed by research §13, so a refusal that names
     * one is saying something the repository already says out loud. A token that is not one of them
     * is an operator's own typing - a contact detail where a court house belongs, a credential
     * pasted over an argument - and a refusal's message is written down by whoever catches it, so
     * repeating that token would publish it to an index the whole estate reads (constitution
     * Principle VII). What is not in this set is therefore not written into a message at all.
     */
    public static final Set<String> NAMES = Set.of(DATE, COURT_HOUSE, BATCH, RECORDED_BEFORE,
            SHARED_BEFORE, IGNORE_FLAG, RECORDED_WHILE_OFF, HELP);

    /** How a name is written, and the only way this parser recognises one. */
    private static final String NAME_PREFIX = "--";

    /**
     * Reads one command's arguments.
     *
     * <p>A token beginning {@code --} is a name; the token after it is its value unless that token
     * is a name too, in which case the first was a bare switch. That is the whole grammar, and it
     * is deliberately the whole of it: a value beginning {@code --} would be indistinguishable from
     * the next option, and none of the seven arguments the five commands take is ever written that
     * way.
     *
     * <p>Three shapes are refused rather than read charitably - a value where a name was expected,
     * a name given twice, and {@code --} with nothing after it. Each of them is an operator having
     * typed something other than what they meant, and the one thing this parser must never do is
     * decide which of two readings that was.
     *
     * @param args the arguments that followed the command's name, in the order they were given
     * @return the names that were given and the values that followed them
     * @throws IllegalArgumentException where the arguments cannot be read as options and switches
     */
    public static Args parse(final List<String> args) {
        final Map<String, String> options = new LinkedHashMap<>();
        final Set<String> flags = new LinkedHashSet<>();

        int position = 0;
        while (position < args.size()) {
            final String name = nameOf(args.get(position));
            given(name, !flags.contains(name) && !options.containsKey(name));
            final String next = position + 1 < args.size() ? args.get(position + 1) : null;
            if (next == null || next.startsWith(NAME_PREFIX)) {
                flags.add(name);
                position++;
            } else {
                options.put(name, next);
                position += 2;
            }
        }

        return new Args(Map.copyOf(options), Set.copyOf(flags));
    }

    /**
     * The name a token carries, refusing anything that is not one.
     *
     * <p>The refused token is not repeated. What is in a name position where no name was written is
     * whatever the operator typed - and a pasted secret or a mistyped address lands exactly there
     * when a runbook step is half-typed - so the rule is stated and the token is left where it came
     * from (constitution Principle VII). Which token it was is not knowable from this refusal by
     * design; that a name was expected is the whole of what the shape says.
     *
     * @param token the token where a name was expected
     * @return the name without its leading dashes
     * @throws IllegalArgumentException where the token is not a name at all, said without quoting
     *                                  it
     */
    private static String nameOf(final String token) {
        if (!token.startsWith(NAME_PREFIX) || NAME_PREFIX.equals(token)) {
            throw new IllegalArgumentException("an argument is written --name, and this one is not");
        }
        return token.substring(NAME_PREFIX.length());
    }

    /**
     * Refuses a name that was given twice, however it was given the second time.
     *
     * <p>Two values for one name is two answers to one question, and a parser that kept either of
     * them would be choosing which invocation the operator meant.
     *
     * <p>The name is repeated only where this service owns it ({@link #NAMES}), because which
     * argument was doubled is the whole of what a reader can act on and those eight words are the
     * repository's own. A name it does not own is a token an operator typed after two dashes, which
     * is no more this parser's to write down than a value would be.
     *
     * @param name  the name that was read
     * @param first whether this was the first time it was given
     * @throws IllegalArgumentException where it was not
     */
    private static void given(final String name, final boolean first) {
        if (!first) {
            throw new IllegalArgumentException(NAMES.contains(name)
                    ? NAME_PREFIX + name + " was given more than once"
                    : "an argument was given more than once");
        }
    }

    /**
     * Whether these are arguments one command takes, {@code --help} always being one of them.
     *
     * <p>Asked by every command before it reads a value, because another command's argument is a
     * mistyped invocation rather than a narrowing this one silently ignores: an operator who
     * regenerated a register date under a bound the command dropped would have e-mailed a Youth
     * Offending Team about hearings they did not mean to include.
     *
     * <p>The buckets are checked separately. A name this command takes a value for, given as a bare
     * switch, is as much a mistyped invocation as a name it does not take at all - and it is the
     * shape that would otherwise reach the command as a missing argument and be reported as one.
     *
     * @param permittedOptions the names this command takes a value for
     * @param permittedFlags   the bare switches it takes, {@code --help} excluded
     * @return true where every name given is one of them
     */
    public boolean permits(final Set<String> permittedOptions, final Set<String> permittedFlags) {
        return permittedOptions.containsAll(options.keySet())
                && permittedFlags.containsAll(withoutHelp());
    }

    /**
     * The switches given other than {@code --help}, which every command takes and none declares.
     *
     * @return the bare switches this invocation carried, help aside
     */
    private Set<String> withoutHelp() {
        return flags.stream().filter(flag -> !HELP.equals(flag))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether the operator asked what the command takes rather than for the command to run.
     *
     * @return true where {@code --help} was given
     */
    public boolean askedForHelp() {
        return flags.contains(HELP);
    }
}
