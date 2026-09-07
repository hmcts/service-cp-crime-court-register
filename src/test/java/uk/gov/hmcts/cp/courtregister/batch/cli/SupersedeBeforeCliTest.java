package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * {@code supersede-before --shared-before T}: the rollback lever, and the bound it is never allowed
 * to widen.
 *
 * <p>This is the command run when the legacy has taken a period back over. The registers this
 * service recorded for those hearings are about to be sent by something else, so they are marked
 * superseded and no run - the schedule's or an operator's - will batch them again; a document sent
 * twice to a Youth Offending Team is worse than one sent once by the older system.
 *
 * <p><strong>The division of labour is the point of this class.</strong> Which rows a period holds
 * is {@code RegisterStore.supersedeSharedBefore}'s predicate - RECORDED, unsuperseded, unbatched,
 * shared before the instant - and it is held down over real rows by {@code RegisterStoreIT}, where
 * a status and a stamp are things a test can actually put in a table. What is held down here is the
 * only part the command decides: <em>the bound</em>. Every case asserts the exact instant the store
 * was asked for and that nothing else about the store was touched, because every way this command
 * can be dangerous is a way of asking for a wider period than the operator typed.
 *
 * <p><strong>Which is why an absent argument is a refusal and never a default.</strong> An operator
 * who meant "everything recorded before the cutover was rolled back" and got "everything recorded
 * up to this second" has superseded the hearings that arrived while they were typing - and
 * supersession is not undone by running the command again. A bound this command cannot read is a
 * bound it does not act on.
 *
 * <p>Supersession rather than deletion, and the count is what is reported: the rows stay, carrying
 * what was recorded and when, because the register store is the audit of what this service decided
 * and a rollback is exactly when that audit is read. The line carries a number and the bound, and
 * nothing about a hearing or a child (constitution Principle VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("supersede-before")
class SupersedeBeforeCliTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T065 implements supersede-before; this is its red run";

    /** Returned where the seam refused, so an exit code that was never produced fails as one. */
    private static final int NOT_RUN = -1;

    /** The cutover moment an operator types, to the second and in UTC. */
    private static final String TYPED = "2026-09-04T17:00:00Z";

    /** The same moment, as the store must be asked for it. */
    private static final Instant BOUND = Instant.parse(TYPED);

    private final RegisterStore store = mock(RegisterStore.class);
    private final List<String> lines = new ArrayList<>();
    private final Consumer<String> output = lines::add;
    private final SupersedeBeforeCli cli = new SupersedeBeforeCli(store, output);

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

    /** The one line the command has to print, so the format is stated once. */
    private static String reportOf(final int superseded) {
        return "superseded=" + superseded + " shared-before=" + TYPED;
    }

    /**
     * A bound the command could read, which is the only case that writes anything.
     */
    @Nested
    @DisplayName("a period the operator named")
    class Named {

        @Test
        void the_period_asked_for_should_be_exactly_the_one_that_was_typed() {
            when(store.supersedeSharedBefore(BOUND)).thenReturn(7);

            final int code = run("--shared-before", TYPED);

            softly.assertThat(code)
                    .as("the period the legacy took back is no longer this service's to claim")
                    .isEqualTo(CliMain.SUCCESS);
            verify(store).supersedeSharedBefore(BOUND);
            verifyNoMoreInteractions(store);
        }

        @Test
        void the_count_should_be_reported_beside_the_bound_it_was_taken_over() {
            when(store.supersedeSharedBefore(BOUND)).thenReturn(7);

            final int code = run("--shared-before", TYPED);

            softly.assertThat(code)
                    .as(PENDING)
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("a number and the bound, exact: the count is what a rollback runbook "
                            + "reconciles against the legacy's own, and the bound is echoed so "
                            + "the operator can see which period they actually superseded")
                    .containsExactly(reportOf(7));
        }

        @Test
        void a_period_that_held_nothing_should_be_a_success_rather_than_a_refusal() {
            when(store.supersedeSharedBefore(BOUND)).thenReturn(0);

            final int code = run("--shared-before", TYPED);

            softly.assertThat(code)
                    .as("a period this service recorded nothing in is a rollback with nothing to "
                            + "do, and a runbook step that failed on it would stop a rollback that "
                            + "is already complete")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("said as a count, so the operator can see that it was asked and answered "
                            + "rather than skipped")
                    .containsExactly(reportOf(0));
        }

        @Test
        void nothing_but_the_supersession_should_be_asked_of_the_store() {
            when(store.supersedeSharedBefore(BOUND)).thenReturn(2);

            run("--shared-before", TYPED);

            verify(store).supersedeSharedBefore(BOUND);
            verifyNoMoreInteractions(store);
        }
    }

    /**
     * A bound the command could not read, of which there is only one safe answer.
     */
    @Nested
    @DisplayName("a period it could not read")
    class Unreadable {

        @Test
        void an_absent_instant_should_be_refused_and_never_defaulted_to_now() {
            final int code = run();

            softly.assertThat(code)
                    .as("the instant is required. An operator who meant the cutover and got this "
                            + "second has superseded the hearings that arrived while they typed, "
                            + "and supersession is not undone by running the command again")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(store);
        }

        @ParameterizedTest
        @ValueSource(strings = {"last-tuesday", "2026-09-04", "2026-09-04T17:00:00", "", "0"})
        void an_instant_it_cannot_read_should_be_refused_rather_than_interpreted(
                final String typed) {

            final int code = run("--shared-before", typed);

            softly.assertThat(code)
                    .as("a local date and a local date-time name no instant without a zone, and a "
                            + "command that supplied one would supersede an hour either side of "
                            + "what was meant twice a year")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(store);
        }

        @Test
        void an_argument_this_command_does_not_take_should_be_refused() {
            final int code = run("--shared-before", TYPED, "--court-house", "B01LY00");

            softly.assertThat(code)
                    .as("a rollback is a period and not a court house: narrowing it silently would "
                            + "leave every other court centre's registers claimed by a service the "
                            + "operator has just told to stand down")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(store);
        }

        @Test
        void asking_for_help_should_exit_zero_and_supersede_nothing() {
            final int code = run("--help");

            softly.assertThat(code)
                    .as("the one argument every command takes, and the one that does nothing")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("usage says which command it is about, so an operator with five of them "
                            + "in a runbook can tell the answers apart")
                    .anyMatch(line -> line.contains(CliMain.SUPERSEDE_BEFORE));
            verifyNoInteractions(store);
        }
    }

    /**
     * The supersession was asked for and could not be finished.
     */
    @Nested
    @DisplayName("a supersession that could not be finished")
    class CouldNotFinish {

        @Test
        void a_store_that_went_away_should_fail_rather_than_report_a_count() {
            when(store.supersedeSharedBefore(BOUND)).thenThrow(
                    new StoreUnavailableException("supersede the period",
                            new SQLException("connection closed")));

            final int code = run("--shared-before", TYPED);

            softly.assertThat(code)
                    .as("the command tried and could not, which a rollback runbook has to retry: "
                            + "a period reported superseded that was not is a night on which both "
                            + "implementations generate")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("and no count is printed, because none was taken")
                    .noneMatch(line -> line.contains("superseded="));
        }
    }
}
