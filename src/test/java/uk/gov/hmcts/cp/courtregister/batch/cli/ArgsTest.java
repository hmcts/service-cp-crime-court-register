package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * The one parser the five commands read an invocation through, and the shapes it will not guess at.
 *
 * <p><strong>[A] characterisation.</strong> {@link Args} already behaves as every case here says;
 * this suite states the behaviour rather than driving it, and it passed on introduction. It exists
 * because nothing held the parser down on its own: the five command suites each assert what their
 * command does with an invocation it accepts or refuses, and none of them says what the grammar is
 * - so the three shapes the parser deliberately refuses rather than reads charitably (a value where
 * a name was expected, a name given twice, {@code --} with nothing after it) were reachable only
 * through a command, and a later implementer could have made any of them mean something without a
 * single case going red.
 *
 * <p><strong>Why a guess would matter.</strong> An operator regenerating a register date at the
 * wrong court house is an e-mail to the wrong Youth Offending Team about somebody's child, so the
 * parser's contract is that an invocation it cannot read costs a refusal rather than a run
 * ({@link CliMain#REFUSED}, via the command's own {@code unreadable}). Every refusal below is
 * therefore asserted as a refusal - {@link IllegalArgumentException} out of {@link Args#parse} -
 * and not as a value the command would then have worked from.
 *
 * <p><strong>And what a refusal is allowed to say is part of the grammar.</strong> A refusal here
 * is thrown, and a throw is handled by whoever catches it: a command turns it into a bounded report
 * line and a log line, and a future handler may write the message down whole. The token it refused
 * on is an operator's own text - an address dictated over the phone, a credential pasted over an
 * argument - so the message names the argument where this service owns the name and says nothing at
 * all where it does not (constitution Principle VII).
 *
 * <p><strong>What the parser does not do is as much of the claim as what it does.</strong> It
 * interprets no value: {@code --date last-tuesday} parses, because the refusal for a date that is
 * not one belongs where the command can say which argument it could not use. And the two buckets
 * are separate, so a name a command takes a value for, typed as a bare switch, is a mistyped
 * invocation rather than a narrowing that command silently drops.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the one argument parser")
class ArgsTest {

    /** A register date as an operator types it, which this parser does not read as a date. */
    private static final String TYPED_DATE = "2026-08-20";

    /** One court house of it, as the narrowing argument's value. */
    private static final String COURT_HOUSE = "Leeds Youth Court";

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * The shapes the parser refuses rather than reading charitably.
     *
     * @return each shape, named as the mistyped invocation it is, beside the tokens that make it
     */
    static Stream<Arguments> shapesItRefusesRatherThanGuesses() {
        return Stream.of(
                arguments("a value where a name was expected", List.of(TYPED_DATE)),
                arguments("a value after a complete option",
                        List.of("--date", TYPED_DATE, TYPED_DATE)),
                arguments("one dash rather than two", List.of("-date", TYPED_DATE)),
                arguments("dashes with no name after them", List.of("--")),
                arguments("an option given twice",
                        List.of("--date", TYPED_DATE, "--date", "2026-08-21")),
                arguments("an option given twice with the same value",
                        List.of("--date", TYPED_DATE, "--date", TYPED_DATE)),
                arguments("a switch given twice", List.of("--ignore-flag", "--ignore-flag")),
                arguments("an option given again as a bare switch",
                        List.of("--date", TYPED_DATE, "--date")),
                arguments("help given twice", List.of("--help", "--help")));
    }

    /**
     * The same shapes again, with the token an operator got wrong put where the mistake goes.
     *
     * <p>Once in a name position, which is where a pasted token lands when a runbook step is
     * half-typed, and once as a name nobody owns given twice - the two refusals that have an
     * operator's own text in their hands.
     *
     * @return each shape, beside the tokens that make it, one of them the marker
     */
    static Stream<Arguments> shapesItRefusesOverATokenAnOperatorTyped() {
        return Stream.of(
                arguments("a value where a name was expected",
                        List.of(PersonalDataMarkers.OPERATOR_TOKEN)),
                arguments("a value after a complete option",
                        List.of("--date", TYPED_DATE, PersonalDataMarkers.OPERATOR_TOKEN)),
                arguments("one dash rather than two",
                        List.of("-" + PersonalDataMarkers.OPERATOR_TOKEN)),
                arguments("a name nobody owns, given twice",
                        List.of("--" + PersonalDataMarkers.OPERATOR_TOKEN,
                                "--" + PersonalDataMarkers.OPERATOR_TOKEN)));
    }

    /**
     * The whole grammar: {@code --name value} pairs and bare {@code --name} switches.
     */
    @Nested
    @DisplayName("the grammar it reads")
    class Grammar {

        @Test
        void a_name_with_a_token_after_it_should_be_read_as_an_option() {
            final Args parsed = Args.parse(List.of("--date", TYPED_DATE));

            softly.assertThat(parsed.options())
                    .as("the token after a name is that name's value, which is the whole of the "
                            + "grammar for the six arguments the commands take a value for")
                    .containsExactly(Map.entry(Args.DATE, TYPED_DATE));
            softly.assertThat(parsed.flags())
                    .as("and it is not also a switch")
                    .isEmpty();
        }

        @Test
        void a_name_at_the_end_should_be_read_as_a_bare_switch() {
            final Args parsed = Args.parse(List.of("--ignore-flag"));

            softly.assertThat(parsed.flags())
                    .as("nothing followed it, so it is the switch it looks like")
                    .containsExactly(Args.IGNORE_FLAG);
            softly.assertThat(parsed.options())
                    .as("a switch is never an option with a missing value: the command that would "
                            + "have reported it as one takes no value for this name at all")
                    .isEmpty();
        }

        @Test
        void a_name_followed_by_another_name_should_be_read_as_a_bare_switch() {
            final Args parsed = Args.parse(List.of("--ignore-flag", "--date", TYPED_DATE));

            softly.assertThat(parsed.flags())
                    .as("a value beginning -- would be indistinguishable from the next option, and "
                            + "none of the arguments the commands take is ever written that way")
                    .containsExactly(Args.IGNORE_FLAG);
            softly.assertThat(parsed.options())
                    .as("so the name that followed it is the next option, with its own value")
                    .containsExactly(Map.entry(Args.DATE, TYPED_DATE));
        }

        @Test
        void a_whole_invocation_should_be_read_as_the_names_it_gave_and_the_values_that_followed() {
            final Args parsed = Args.parse(List.of("--date", TYPED_DATE, "--court-house",
                    COURT_HOUSE, "--recorded-before", "2026-08-20T17:00:00Z", "--ignore-flag"));

            softly.assertThat(parsed.options())
                    .as("the four values a regeneration is narrowed by, each under its own name")
                    .containsOnly(
                            Map.entry(Args.DATE, TYPED_DATE),
                            Map.entry(Args.COURT_HOUSE, COURT_HOUSE),
                            Map.entry(Args.RECORDED_BEFORE, "2026-08-20T17:00:00Z"));
            softly.assertThat(parsed.flags())
                    .as("and the one thing an operator asks for rather than gives a value to")
                    .containsExactly(Args.IGNORE_FLAG);
        }

        @Test
        void nothing_typed_should_be_read_as_nothing_given() {
            final Args parsed = Args.parse(List.of());

            softly.assertThat(parsed.options())
                    .as("check-flag takes nothing, and an empty invocation is the one it expects")
                    .isEmpty();
            softly.assertThat(parsed.flags())
                    .as("and it is not a refusal: nothing typed is not the same as a token this "
                            + "parser could not read")
                    .isEmpty();
        }

        @Test
        void no_value_should_be_interpreted_here() {
            final Args parsed = Args.parse(List.of("--date", "last-tuesday"));

            softly.assertThat(parsed.options())
                    .as("a date is a date to the command that asked for one: the refusal for a "
                            + "value that is not one belongs where the command can say which "
                            + "argument it could not use")
                    .containsExactly(Map.entry(Args.DATE, "last-tuesday"));
        }

        @Test
        void what_a_command_reads_should_not_be_modifiable_by_it() {
            final Args parsed = Args.parse(List.of("--date", TYPED_DATE, "--ignore-flag"));

            softly.assertThatThrownBy(() -> parsed.options().put(Args.BATCH, TYPED_DATE))
                    .as("one invocation, read once and read the same way by every command: a "
                            + "command that could add to it would be answering a question the "
                            + "operator was never asked")
                    .isInstanceOf(UnsupportedOperationException.class);
            softly.assertThatThrownBy(() -> parsed.flags().add(Args.IGNORE_FLAG))
                    .as("and the override above all, which only an operator may ask for")
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * The invocations it refuses rather than deciding between two readings of.
     */
    @Nested
    @DisplayName("what it refuses rather than guesses")
    class Refusals {

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.courtregister.batch.cli.ArgsTest"
                + "#shapesItRefusesRatherThanGuesses")
        void a_shape_it_cannot_read_should_be_refused_rather_than_guessed(final String shape,
                final List<String> typed) {

            softly.assertThatThrownBy(() -> Args.parse(typed))
                    .as("an operator having typed something other than what they meant, and the "
                            + "one thing this parser must never do is decide which of two "
                            + "readings that was: " + shape)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void a_name_given_twice_should_be_refused_whichever_value_it_carried() {
            softly.assertThatThrownBy(() -> Args.parse(List.of("--date", TYPED_DATE, "--date",
                            "2026-08-21")))
                    .as("two values for one name is two answers to one question, and a parser that "
                            + "kept either of them would be choosing which invocation the operator "
                            + "meant - here, which register date is e-mailed")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--" + Args.DATE);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.courtregister.batch.cli.ArgsTest"
                + "#shapesItRefusesOverATokenAnOperatorTyped")
        void what_it_refused_on_should_not_be_in_the_message_it_refused_with(final String shape,
                final List<String> typed) {

            softly.assertThatThrownBy(() -> Args.parse(typed))
                    .as("a refusal's message is written down by whatever handles it, and one of "
                            + "the handlers is a log line in a command's JVM - so a message "
                            + "carrying the token is the token published to an index the whole "
                            + "estate reads (constitution Principle VII): " + shape)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(PersonalDataMarkers.OPERATOR_TOKEN);
        }

        @Test
        void a_name_this_parser_owns_should_still_be_named_where_it_was_given_twice() {
            softly.assertThatThrownBy(() -> Args.parse(List.of("--batch", "--batch")))
                    .as("the seven names are this service's own text, fixed by research §13, and "
                            + "which argument was doubled is the whole of what a reader can act "
                            + "on: the rule is about what followed a name, not about the name")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--" + Args.BATCH);
        }
    }

    /**
     * What a command asks of an invocation before it reads a value out of it.
     */
    @Nested
    @DisplayName("whether these are arguments one command takes")
    class Permits {

        @Test
        void the_names_a_command_takes_should_be_permitted() {
            final Args parsed = Args.parse(
                    List.of("--date", TYPED_DATE, "--court-house", COURT_HOUSE, "--ignore-flag"));

            softly.assertThat(parsed.permits(Set.of(Args.DATE, Args.COURT_HOUSE),
                            Set.of(Args.IGNORE_FLAG)))
                    .as("generate-register's own arguments, in the two buckets it declares them in")
                    .isTrue();
        }

        @Test
        void help_should_be_permitted_although_no_command_declares_it() {
            softly.assertThat(Args.parse(List.of("--help")).permits(Set.of(), Set.of()))
                    .as("check-flag declares no argument at all, and --help is still one it takes")
                    .isTrue();
            softly.assertThat(Args.parse(List.of("--date", TYPED_DATE, "--help"))
                            .permits(Set.of(Args.DATE), Set.of()))
                    .as("and it is permitted beside the arguments a command does declare")
                    .isTrue();
        }

        @Test
        void a_name_this_command_does_not_take_should_not_be_permitted() {
            final Args parsed = Args.parse(List.of("--date", TYPED_DATE, "--batch",
                    "9c1f4b2e-88a7-4d35-b0e6-1f7a3c5d9e20"));

            softly.assertThat(parsed.permits(Set.of(Args.DATE), Set.of()))
                    .as("another command's argument is a mistyped invocation rather than a "
                            + "narrowing this one silently ignores: an operator who regenerated a "
                            + "date under a bound the command dropped would have e-mailed a Youth "
                            + "Offending Team about hearings they did not mean to include")
                    .isFalse();
        }

        @Test
        void an_option_typed_as_a_bare_switch_should_not_be_permitted() {
            final Args parsed = Args.parse(List.of("--date"));

            softly.assertThat(parsed.permits(Set.of(Args.DATE), Set.of()))
                    .as("the buckets are checked separately, so --date with no date is refused as "
                            + "the mistyped invocation it is rather than reaching the command as a "
                            + "missing argument and being reported as one")
                    .isFalse();
        }

        @Test
        void a_switch_given_a_value_should_not_be_permitted_either() {
            final Args parsed = Args.parse(List.of("--ignore-flag", "please"));

            softly.assertThat(parsed.permits(Set.of(Args.DATE), Set.of(Args.IGNORE_FLAG)))
                    .as("the override is asked for, never given a value: a token after it is a "
                            + "token nobody meant to type next to the one argument that overrides "
                            + "the cutover flag")
                    .isFalse();
        }
    }

    /**
     * The one argument every command takes and none of them declares.
     */
    @Nested
    @DisplayName("asking what a command takes")
    class AskingForHelp {

        @Test
        void help_should_be_seen_where_it_was_given() {
            softly.assertThat(Args.parse(List.of("--help")).askedForHelp())
                    .as("the operator asked what the command takes rather than for it to run")
                    .isTrue();
            softly.assertThat(Args.parse(List.of("--date", TYPED_DATE, "--help")).askedForHelp())
                    .as("and asked it beside other arguments, which is what a half-remembered "
                            + "runbook step looks like")
                    .isTrue();
        }

        @Test
        void help_should_not_be_seen_where_it_was_not_given() {
            softly.assertThat(Args.parse(List.of("--date", TYPED_DATE)).askedForHelp())
                    .as("a command that read help into a plain invocation would print its usage "
                            + "and generate nothing, which an operator would read as a success")
                    .isFalse();
            softly.assertThat(Args.parse(List.of()).askedForHelp())
                    .as("and an empty invocation is not a request for help either")
                    .isFalse();
        }
    }
}
