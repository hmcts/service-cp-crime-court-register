package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifierService;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * {@code notify-register --batch B}: the resend, and the teams it must not tell twice.
 *
 * <p>{@code RegisterNotifierServiceTest} says what a resend does to the rows - only the FAILED
 * ones, under the identities they were first attempted with, and the batch settled on the tally
 * afterwards. This says what the command does about it, and the whole of that is: read one batch
 * identity out of the arguments, ask the service, and report what came back. The rule is not
 * restated here on purpose. A command that decided for itself which rows were owed would be a
 * second answer to "has this Youth Offending Team been told" that could disagree with the
 * schedule's, and the disagreement would be a second e-mail about the same children.
 *
 * <p><strong>So the assertions are about the seam and the report, not about notification.</strong>
 * That {@code resendFailed} is what is asked - and never {@code notify}, which mints fresh rows for
 * every recipient of the batch, the teams who read this morning's register included - is the
 * behaviour this class exists to hold down.
 *
 * <p><strong>A batch that was already fine is a success.</strong> An operator working down a list
 * of batches from a support ticket should not have to tell a refusal from a batch with nothing
 * owed, so the answer is exit 0 and a line whose {@code failed=0} says the batch is settled; the
 * exit codes are kept for the two things a runbook must act on differently, arguments that were not
 * usable ({@link CliMain#REFUSED}, nothing attempted) and an attempt that could not be finished
 * ({@link CliMain#FAILED}).
 *
 * <p><strong>Nothing about a recipient is printed.</strong> The tally is counts and a bounded
 * state; the addresses live in the notification rows, which are the only place that may hold them
 * (constitution Principle VII). {@code list-batches} masks the ones it has cause to show, and this
 * command has no such cause at all.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("notify-register")
class NotifyRegisterCliTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T065 implements notify-register; this is its red run";

    /** Returned where the seam refused, so an exit code that was never produced fails as one. */
    private static final int NOT_RUN = -1;

    /** The batch an operator carries in from a support ticket. */
    private static final UUID BATCH = UUID.fromString("6f1d0c62-4a3b-4f7e-9c8d-2b5e7a1f0c34");

    /** The four codes the line's last field carries, one per thing a call can have done. */
    private static final String SETTLED = "settled";

    private static final String ALREADY_NOTIFYING = "already-notifying";

    private static final String CLAIM_LOST = "claim-lost";

    private static final String INCOMPLETE = "incomplete";

    private final RegisterNotifierService notifier = mock(RegisterNotifierService.class);
    private final List<String> lines = new ArrayList<>();
    private final Consumer<String> output = lines::add;
    private final NotifyRegisterCli cli = new NotifyRegisterCli(notifier, output);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Runs the command over what an operator typed.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so the
     * seam's refusal is recorded as an assertion rather than ending the case, and the exit code it
     * did not produce is then asserted on as {@link #NOT_RUN}.
     *
     * @param args what an operator typed after the command's name
     * @return the exit code, or {@link #NOT_RUN} where the seam refused
     */
    private int run(final String... args) {
        final AtomicInteger code = new AtomicInteger(NOT_RUN);
        softly.assertThatCode(() -> code.set(cli.run(List.of(args))))
                .as(PENDING)
                .doesNotThrowAnyException();
        return code.get();
    }

    /** The one line the command has to print for a call that settled the batch. */
    private static String reportOf(final NotificationSummary summary) {
        return reportOf(summary, SETTLED);
    }

    /**
     * The same line for a call that did not settle it, so the format is stated once.
     *
     * <p>The disposition is on the line because the tally cannot carry it: nought accepted and
     * three failed is a resend that was refused the claim and a resend that really did fail three
     * teams, and an operator reading the line against a support ticket has to be able to tell them
     * apart. Written as the literal an operator would see rather than read off the enum, so a
     * renamed constant cannot silently rename what a runbook greps for.
     *
     * @param summary     what the notifier answered
     * @param disposition the bounded code for what the call did
     * @return the line the command prints
     */
    private static String reportOf(final NotificationSummary summary, final String disposition) {
        return "batch=" + BATCH + " accepted=" + summary.accepted() + " failed=" + summary.failed()
                + " state=" + summary.outcome() + " disposition=" + disposition;
    }

    /** How the command says it could not finish, which is {@link CliMain#FAILED} and a reason. */
    private static String failureOf(final String reason) {
        return "command=" + CliMain.NOTIFY_REGISTER + " batch=" + BATCH
                + " outcome=failed reason=" + reason;
    }

    /**
     * A batch that had recipients it could not tell, which is what the command is for.
     */
    @Nested
    @DisplayName("a batch with failed recipients")
    class OwedRecipients {

        @Test
        void a_resend_should_ask_for_the_failed_recipients_and_never_the_whole_batch() {
            final NotificationSummary settled =
                    new NotificationSummary(3, 0, BatchStatus.NOTIFIED);
            when(notifier.resendFailed(BATCH)).thenReturn(settled);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("the recipients that were owed have been re-requested and the batch is "
                            + "settled on what its rows now say")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("counts and a bounded state, exact, because an operator reads this line "
                            + "against the ticket that sent them here")
                    .containsExactly(reportOf(settled));
            verify(notifier).resendFailed(BATCH);
            verify(notifier, never()).notify(any(UUID.class));
        }

        @Test
        void a_resend_that_still_could_not_tell_everybody_should_report_it_and_still_exit_zero() {
            final NotificationSummary settled =
                    new NotificationSummary(2, 1, BatchStatus.PARTIALLY_NOTIFIED);
            when(notifier.resendFailed(BATCH)).thenReturn(settled);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("the command did what it was asked - it re-requested what was owed - and "
                            + "a team notificationnotify refused again is the batch's news, not "
                            + "the command's failure; the line is where an operator reads it")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("and the line says plainly that somebody is still not told, rather than "
                            + "reporting the state the batch would have had if everybody were")
                    .containsExactly(reportOf(settled));
        }

        @Test
        void no_recipient_address_should_appear_in_the_output() {
            when(notifier.resendFailed(BATCH))
                    .thenReturn(new NotificationSummary(1, 1, BatchStatus.PARTIALLY_NOTIFIED));

            run("--batch", BATCH.toString());

            softly.assertThat(lines)
                    .as("this command has no cause to name a Youth Offending Team at all: the "
                            + "addresses live in the notification rows, which are the only place "
                            + "that may hold them")
                    .noneMatch(line -> line.contains("@"));
        }
    }

    /**
     * A batch with nothing owed, which is a question worth asking and not a mistake.
     */
    @Nested
    @DisplayName("a batch with nothing owed")
    class NothingOwed {

        @Test
        void a_batch_that_was_already_fine_should_be_a_success_that_says_so_in_the_line() {
            final NotificationSummary settled =
                    new NotificationSummary(4, 0, BatchStatus.NOTIFIED);
            when(notifier.resendFailed(BATCH)).thenReturn(settled);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("there is nothing wrong with asking, and an operator working down a list "
                            + "of batches should not have to tell a refusal from a batch that "
                            + "needed nothing")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("failed=0 is the output saying the batch is settled, which is where that "
                            + "belongs rather than in an exit code a runbook branches on")
                    .containsExactly(reportOf(settled));
        }

        @Test
        void a_batch_that_reached_nobody_at_all_should_still_be_reported_rather_than_refused() {
            final NotificationSummary settled =
                    new NotificationSummary(0, 0, BatchStatus.NOTIFIED_NOBODY);
            when(notifier.resendFailed(BATCH)).thenReturn(settled);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("defect fix P1's terminal state seen from the terminal: a document nobody "
                            + "subscribes to is finished, and a command that refused on it would "
                            + "send an operator looking for a fault there is not")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("named by the state the batch actually stands in")
                    .containsExactly(reportOf(settled));
        }
    }

    /**
     * The three answers where the call did not settle the batch, which the tally cannot say.
     *
     * <p>{@link NotificationSummary} carries a disposition beside the counts because the counts are
     * not this call's work on the other three answers: they are the batch as it stood when the
     * claim was refused or lost, or as it stands with a recipient unaccounted for. So
     * {@code accepted=0 failed=3} means "this run failed three teams" on one of them and "another
     * notifier is part-way through" on another, and a command that printed only the tally would have
     * an operator escalate a resend that posted nothing as though it had been refused three times.
     *
     * <p><strong>And two of the three are {@link CliMain#FAILED}.</strong> Exit 0 is "the command
     * did what it was asked": a lost claim and an unaccounted-for recipient are both a cycle that
     * stopped with the batch unsettled, which is the one thing a runbook step is meant to retry.
     * {@code ALREADY_NOTIFYING} is not - the batch is being told by somebody else and there is
     * nothing left to do - so it is a success whose line says why nothing moved.
     */
    @Nested
    @DisplayName("a resend that did not settle the batch")
    class NotSettled {

        @Test
        void a_batch_another_notifier_holds_should_say_so_and_still_exit_zero() {
            final NotificationSummary standing =
                    NotificationSummary.alreadyNotifying(0, 3, BatchStatus.GENERATED);
            when(notifier.resendFailed(BATCH)).thenReturn(standing);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("the batch is being told by the outcome sink and this call posted nothing: "
                            + "not a failure and not a refusal, because there is nothing left for "
                            + "the operator to do")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("and the line says which of the two it was, because the tally beside it is "
                            + "the other notifier's work part-done rather than this call's")
                    .containsExactly(reportOf(standing, ALREADY_NOTIFYING));
        }

        @Test
        void a_resend_that_lost_the_claim_should_fail_under_its_own_reason() {
            final NotificationSummary standing =
                    NotificationSummary.claimLost(1, 2, BatchStatus.GENERATED);
            when(notifier.resendFailed(BATCH)).thenReturn(standing);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("the cycle stopped part way and the batch is not settled, which is the "
                            + "one thing exit 2 is for: the step that ran this may retry it")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("the tally as the rows stood, then the bounded reason the command could "
                            + "not finish - and how far it had got is not fixed, which is why the "
                            + "reason is the disposition rather than a count")
                    .containsExactly(reportOf(standing, CLAIM_LOST), failureOf(CLAIM_LOST));
        }

        @Test
        void a_resend_that_could_not_account_for_a_recipient_should_fail_under_its_own_reason() {
            final NotificationSummary standing =
                    NotificationSummary.incomplete(2, 0, BatchStatus.GENERATED);
            when(notifier.resendFailed(BATCH)).thenReturn(standing);

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("a settlement made for a row the store no longer holds leaves the batch "
                            + "GENERATED and nothing recovers it unasked, so the command an "
                            + "operator would have to type again is the one that says it failed")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("and state=GENERATED beside the reason is the batch left where it stands, "
                            + "not a verdict this call wrote")
                    .containsExactly(reportOf(standing, INCOMPLETE), failureOf(INCOMPLETE));
        }
    }

    /**
     * What is typed after the name, of which this command takes one thing.
     */
    @Nested
    @DisplayName("the arguments it takes")
    class Arguments {

        @Test
        void a_missing_batch_should_be_refused_without_asking_for_anything() {
            final int code = run();

            softly.assertThat(code)
                    .as("there is no default batch to resend and there must not be one: the "
                            + "command that guessed would e-mail a court centre nobody named")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(notifier);
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-uuid", "6f1d0c62", ""})
        void a_batch_that_is_not_an_identity_should_be_refused_rather_than_guessed_at(
                final String typed) {

            final int code = run("--batch", typed);

            softly.assertThat(code)
                    .as("a mistyped identity is a refusal where the command can say which "
                            + "argument it could not use, not a lookup that answers about "
                            + "whichever batch it happened to resolve to")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(notifier);
        }

        @Test
        void an_argument_this_command_does_not_take_should_be_refused() {
            final int code = run("--batch", BATCH.toString(), "--ignore-flag");

            softly.assertThat(code)
                    .as("a resend renders nothing and asks for nothing to be rendered, so there "
                            + "is no flag for it to override; accepting the switch would tell an "
                            + "operator this command had been gated all along")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(notifier);
        }

        @Test
        void asking_for_help_should_exit_zero_and_send_nothing() {
            final int code = run("--help");

            softly.assertThat(code)
                    .as("the one argument every command takes, and the one that does nothing")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("usage says which command it is about, so an operator with five of them "
                            + "in a runbook can tell the answers apart")
                    .anyMatch(line -> line.contains(CliMain.NOTIFY_REGISTER));
            verifyNoInteractions(notifier);
        }

        /**
         * What the usage promises, which has to be the rule the resend actually applies.
         *
         * <p>The line is printed on {@code --help} and under every refusal, and it is the only
         * place an operator mid-incident is told which recipients a resend reaches - so it is the
         * line they reason from about whether a Youth Offending Team may already hold this
         * register. {@code resendFailed} posts for every row of the batch that is not ACCEPTED: a
         * PENDING row a pod abandoned between the 202 and the settlement, and a recipient the batch
         * holds no row for at all, as well as a FAILED one. FAILED is a {@code NotificationStatus}
         * this codebase counts strictly elsewhere, so a usage promising "the FAILED recipients, and
         * only those" cannot be read as loose shorthand: it names a narrower set than the command
         * sends to.
         *
         * <p>The half that is true is the half asserted last: an ACCEPTED recipient is never told
         * twice, which is the reassurance the sentence exists to give.
         */
        @Test
        void the_usage_should_promise_the_rule_the_resend_applies() {
            final int code = run("--help");

            softly.assertThat(code).isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("a resend reaches every recipient this service has not had an e-mail "
                            + "accepted for, which is what the operator has to be told")
                    .anyMatch(line -> line.contains("no e-mail has been accepted for"));
            softly.assertThat(lines)
                    .as("and not the FAILED rows alone: a PENDING row and a recipient with no row "
                            + "at all are owed one too, so naming the status names the wrong set")
                    .noneMatch(line -> line.contains("FAILED"));
            softly.assertThat(lines)
                    .as("what is true stays said: the teams that were told are not told again")
                    .anyMatch(line -> line.contains("and only those"));
        }
    }

    /**
     * The resend was asked for and could not be finished, which is the third exit code.
     */
    @Nested
    @DisplayName("a resend that could not be finished")
    class CouldNotFinish {

        @Test
        void a_store_that_went_away_should_fail_rather_than_be_swallowed() {
            when(notifier.resendFailed(BATCH))
                    .thenThrow(new StoreUnavailableException("resend the batch",
                            new SQLException("connection closed")));

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("the command tried and could not, which a runbook does retry - unlike a "
                            + "refusal, which changed nothing and means the flag or the arguments "
                            + "were the problem")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("the batch is named, so the operator knows which one of a ticket's list "
                            + "to come back to")
                    .anyMatch(line -> line.contains(BATCH.toString()));
            softly.assertThat(lines)
                    .as("and no tally is printed, because none was taken: a success shape over a "
                            + "failed attempt is the worst line this command could write")
                    .noneMatch(line -> line.contains("accepted="));
        }

        @Test
        void a_batch_this_service_never_assembled_should_fail_rather_than_report_a_tally() {
            when(notifier.resendFailed(BATCH)).thenThrow(new IllegalStateException(
                    "no register batch " + BATCH + " to tell the recipients of"));

            final int code = run("--batch", BATCH.toString());

            softly.assertThat(code)
                    .as("a well-formed identity nothing was assembled under is not an argument the "
                            + "command can refuse on sight, and it is not a batch with nothing to "
                            + "send either; it is an attempt that reached no tally")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("named, and without the store's own sentence about it")
                    .anyMatch(line -> line.contains(BATCH.toString()));
        }
    }
}
