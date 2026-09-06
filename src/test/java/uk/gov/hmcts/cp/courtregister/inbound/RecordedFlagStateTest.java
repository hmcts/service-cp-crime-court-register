package uk.gov.hmcts.cp.courtregister.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.domain.FlagStateSnapshot;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;

/**
 * The label a recorded register carries about the one lever, and the read it never waits for.
 *
 * <p>Every row this service records says which implementation was meant to be generating when the
 * command arrived. It matters only once, and it matters completely: commands still on the queue
 * after the producer has stopped publishing may belong to hearings the resumed legacy also
 * processed, and batching those automatically would send one child's register to a Youth Offending
 * Team twice (research §12). A row labelled anything other than {@code ON} is kept out of automatic
 * batching and surfaced to an operator instead.
 *
 * <p><strong>Two rules, and they pull in opposite directions.</strong> The label has to be
 * <em>true</em>, so a reading is only allowed to speak for a command that arrived inside its window;
 * a flag read a quarter of an hour ago says nothing about a cutover that was rolled back ten minutes
 * ago, and a reading that has aged out is worth exactly what no reading at all is worth. And the
 * label may never <em>cost</em> anything: a recording that waited on App Configuration would stall
 * the whole queue behind an outage in a store this service does not own, to decide a column. So the
 * arriving command schedules the refresh and is labelled from what is already known, and the refresh
 * is for the commands behind it.
 *
 * <p>{@link RecordedFlagState#UNKNOWN} is what both of those rules fall back to, and it is not a
 * synonym for {@code OFF}. OFF says the flag was read and the legacy generates; UNKNOWN says nobody
 * knows. Both are excluded from automatic batching, but only one of them is a working App
 * Configuration store, and support needs to be able to tell which it has.
 *
 * <p>Guards T030: {@link FlagStateSnapshot#stateFor} and {@link RecordedFlagStateSource#current()}.
 */
@DisplayName("Recorded flag state")
class RecordedFlagStateTest {

    /** When the reading in these cases was taken. Any instant does; this one is a nightly's eve. */
    private static final Instant READ_AT = Instant.parse("2026-09-06T17:59:30Z");

    /**
     * How long a reading may speak for a command, from research §12.
     *
     * <p>Stated here rather than read from the production constant on purpose: a test that imports
     * the window it is checking asserts that the number equals itself, and would follow a change to
     * sixty minutes without a word.
     */
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** Enough to be outside the window and too little to be a different rule. */
    private static final Duration A_MOMENT = Duration.ofMillis(1);

    /** How long the off-thread case will wait for a refresh to get where it is going. */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final FeatureFlagReader reader = mock(FeatureFlagReader.class);

    private final AdjustableClock clock = AdjustableClock.startingAt(READ_AT);

    /**
     * The refreshes handed over, held rather than run.
     *
     * <p>An executor that runs nothing is the whole of the "never waits" assertion: a source that
     * read the flag on the caller's thread would reach the reader with this list still empty.
     */
    private final List<Runnable> scheduled = new ArrayList<>();

    private final Executor refreshes = scheduled::add;

    private final RecordedFlagStateSource source =
            new RecordedFlagStateSource(reader, refreshes, clock);

    private ExecutorService offThread;

    @AfterEach
    void stopTheExecutor() {
        if (offThread != null) {
            offThread.shutdownNow();
        }
    }

    @Nested
    @DisplayName("the window a reading speaks for")
    class Window {

        @Test
        @DisplayName("a reading taken now stands for the flag it read")
        void a_reading_taken_now_should_stand_for_the_flag_it_read() {
            assertThat(new FlagStateSnapshot(FlagDecision.ON, READ_AT).stateFor(READ_AT))
                    .as("the flag was read, and it was on")
                    .isEqualTo(RecordedFlagState.ON);
            assertThat(new FlagStateSnapshot(FlagDecision.OFF, READ_AT).stateFor(READ_AT))
                    .as("the flag was read, and it was off")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        /**
         * The last instant the reading is allowed to speak for, asserted rather than approached: an
         * inclusive window and an exclusive one differ by a millisecond, and only a case that lands
         * on the boundary can say which of the two this service implements.
         */
        @Test
        @DisplayName("a reading exactly at the edge of the window still stands for its flag")
        void a_reading_at_the_edge_of_the_window_should_still_stand_for_its_flag() {
            final Instant edge = READ_AT.plus(WINDOW);

            assertThat(new FlagStateSnapshot(FlagDecision.ON, READ_AT).stateFor(edge))
                    .as("a reading %s old is inside a window of %s", WINDOW, WINDOW)
                    .isEqualTo(RecordedFlagState.ON);
            assertThat(new FlagStateSnapshot(FlagDecision.OFF, READ_AT).stateFor(edge))
                    .as("the edge is the same edge whichever way the flag read")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        @Test
        @DisplayName("a reading older than the window stands for nothing")
        void a_reading_older_than_the_window_should_stand_for_nothing() {
            final Instant past = READ_AT.plus(WINDOW).plus(A_MOMENT);

            assertThat(new FlagStateSnapshot(FlagDecision.ON, READ_AT).stateFor(past))
                    .as("a stale on is not a statement about the flag now")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
            assertThat(new FlagStateSnapshot(FlagDecision.OFF, READ_AT).stateFor(past))
                    .as("a stale off is not one either, and is not OFF")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        /**
         * Once per bounded cause, because a cause added later is a new way for a read to produce no
         * answer and must inherit the same label rather than be discovered stamping ON.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(FlagDecision.UnreadableReason.class)
        @DisplayName("a reading that could not be taken stands for nothing")
        void an_unreadable_reading_should_stand_for_nothing(
                final FlagDecision.UnreadableReason reason) {
            final FlagStateSnapshot unreadable =
                    new FlagStateSnapshot(new FlagDecision.Unreadable(reason), READ_AT);

            assertThat(unreadable.stateFor(READ_AT))
                    .as("a read that failed is not a flag that is off")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("what an arriving command is labelled with")
    class Attachment {

        @Test
        @DisplayName("a command arriving before any read is labelled unknown")
        void a_command_arriving_before_any_read_should_be_labelled_unknown() {
            assertThat(source.current())
                    .as("nobody has read the flag, and that is exactly what UNKNOWN says")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        @Test
        @DisplayName("a command arriving inside the window of a read saying on is labelled on")
        void a_command_arriving_inside_the_window_of_an_on_read_should_be_labelled_on() {
            aCompletedReadAnswering(FlagDecision.ON);

            assertThat(source.current())
                    .as("the flag has just been read and said this service generates")
                    .isEqualTo(RecordedFlagState.ON);
        }

        /**
         * The half that decides something: an OFF row is the one the nightly job leaves alone, and
         * it is left alone because the legacy was generating when the command arrived.
         */
        @Test
        @DisplayName("a command arriving inside the window of a read saying off is labelled off")
        void a_command_arriving_inside_the_window_of_an_off_read_should_be_labelled_off() {
            aCompletedReadAnswering(FlagDecision.OFF);

            assertThat(source.current())
                    .as("the legacy was generating, and the row has to say so")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        @Test
        @DisplayName("a command arriving after the window has passed is labelled unknown")
        void a_command_arriving_after_the_window_should_be_labelled_unknown() {
            aCompletedReadAnswering(FlagDecision.ON);
            clock.advance(WINDOW.plus(A_MOMENT));

            assertThat(source.current())
                    .as("a reading this old cannot say what the lever is set to now")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        @Test
        @DisplayName("a command arriving on an unreadable flag is labelled unknown")
        void a_command_arriving_on_an_unreadable_flag_should_be_labelled_unknown() {
            aCompletedReadAnswering(
                    new FlagDecision.Unreadable(FlagDecision.UnreadableReason.TIMED_OUT));

            assertThat(source.current())
                    .as("App Configuration did not answer, so nothing is known about the lever")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        /**
         * The refresh a stale arrival schedules is for the commands behind it, and this is the case
         * that says the refresh is real: the reading is replaced, and by the answer the reader gave
         * this time rather than the one it gave a minute ago.
         */
        @Test
        @DisplayName("the refresh a stale arrival schedules replaces the reading")
        void a_stale_reading_should_be_replaced_by_the_refresh_the_arrival_schedules() {
            aCompletedReadAnswering(FlagDecision.ON);
            clock.advance(WINDOW.plus(A_MOMENT));
            when(reader.read()).thenReturn(FlagDecision.OFF);

            source.current();
            runTheScheduledRefreshes();

            assertThat(source.current())
                    .as("the cutover was rolled back, and the next command says so")
                    .isEqualTo(RecordedFlagState.OFF);
        }
    }

    @Nested
    @DisplayName("what a recording never waits for")
    class NeverWaits {

        /**
         * The reader is not touched while a delivery is being labelled, and the executor is holding
         * the refresh that proves the labelling asked for one. A source that read the flag here
         * would put an App Configuration timeout inside every recording, and a store that had gone
         * away would stall the queue behind a column.
         */
        @Test
        @DisplayName("labelling a command never reaches the flag reader")
        void a_command_should_never_wait_on_a_flag_read() {
            when(reader.read()).thenReturn(FlagDecision.ON);

            final RecordedFlagState label = source.current();

            verifyNoInteractions(reader);
            assertThat(label)
                    .as("labelled from what is known now, not from a read that has not happened")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
            assertThat(scheduled)
                    .as("the arrival still asked for a refresh; it just did not wait for it")
                    .isNotEmpty();
        }

        /**
         * The other half of the same rule, and the half a held executor cannot show: the read does
         * happen, and it happens somewhere else. The listener's thread belongs to one delivery and
         * to its single settlement.
         */
        @Test
        @DisplayName("the read a command schedules runs off the delivery thread")
        void a_scheduled_flag_read_should_run_off_the_delivery_thread() {
            final AtomicReference<String> readOn = new AtomicReference<>();
            when(reader.read()).thenAnswer(invocation -> {
                readOn.set(Thread.currentThread().getName());
                return FlagDecision.ON;
            });
            offThread = Executors.newSingleThreadExecutor();
            final RecordedFlagStateSource offThreadSource =
                    new RecordedFlagStateSource(reader, offThread, clock);

            offThreadSource.current();

            await().atMost(PATIENCE).until(() -> readOn.get() != null);
            verify(reader).read();
            assertThat(readOn.get())
                    .as("the flag was read, and not on the thread that holds the delivery")
                    .isNotEqualTo(Thread.currentThread().getName());
        }
    }

    /**
     * A read that has already happened, which is the only way a reading ever gets in place: an
     * arrival with nothing known schedules one, and this stands in for the executor's thread.
     */
    private void aCompletedReadAnswering(final FlagDecision decision) {
        when(reader.read()).thenReturn(decision);
        source.current();
        runTheScheduledRefreshes();
    }

    /** Runs what the arrivals handed over, which is what the executor's thread would have done. */
    private void runTheScheduledRefreshes() {
        final List<Runnable> handedOver = List.copyOf(scheduled);
        scheduled.clear();
        handedOver.forEach(Runnable::run);
    }
}
