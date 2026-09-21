package uk.gov.hmcts.cp.courtregister.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.courtregister.application.OperationsRunLauncher.RunAccepted;
import uk.gov.hmcts.cp.courtregister.application.RegisterRegenerationService.RegenerationTally;
import uk.gov.hmcts.cp.courtregister.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.courtregister.domain.OperationsReason;
import uk.gov.hmcts.cp.courtregister.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * The {@code 202} hand-off, and the one rule the command it replaces did not have.
 *
 * <p>The CLI left "do not regenerate during the nightly run" to a runbook. Asking whether the lock
 * is held and then acting races the scheduler, so the background run <strong>takes</strong> the
 * schedule's own lock by its own name and records the refusal as the run's outcome when it cannot
 * (research R12). Everything else here is about the order: the arguments, then the one lever, then
 * the run id, then the submit - and nothing of the run itself on the calling thread.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the launcher behind the 202")
class OperationsRunLauncherTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

    private static final UUID BATCH = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final Instant NOW = Instant.parse("2026-08-21T07:00:00Z");

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(70);

    /** The default: one non-blocking attempt, which is the only kind that holds no thread. */
    private static final Duration NO_WAIT = Duration.ZERO;

    private final RegisterRegenerationService regeneration =
            mock(RegisterRegenerationService.class);
    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);
    private final LockProvider locks = mock(LockProvider.class);
    private final SimpleLock lock = mock(SimpleLock.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    /** What was handed to the generation scheduler, held rather than run. */
    private final List<Runnable> submitted = new ArrayList<>();

    private final Executor executor = submitted::add;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * The launcher a deployment gets, over whatever wait it was configured with.
     *
     * @param lockWait how long the background run waits for the schedule's lock
     * @return the launcher
     */
    private OperationsRunLauncher launcher(final Duration lockWait) {
        return new OperationsRunLauncher(regeneration, gate, locks, LOCK_AT_MOST_FOR, lockWait,
                executor, clock);
    }

    /** The whole day, with nothing narrowed and no override. */
    private static Selection theWholeDay() {
        return new Selection(THURSDAY, null, null, null, false);
    }

    /** An empty tally, for the cases that are about the run happening rather than about counts. */
    private static RegenerationTally nothingToDo() {
        return new RegenerationTally(THURSDAY, 0, 0, 0, 0, 0, false, Map.of(), Map.of());
    }

    /** Runs whatever the launcher handed the generation scheduler. */
    private void theSchedulerRunsIt() {
        submitted.forEach(Runnable::run);
    }

    @Nested
    @DisplayName("before the caller is answered")
    class BeforeTheAnswer {

        @Test
        void an_override_without_a_batch_should_be_refused_and_read_nothing() {
            Assertions.assertThatThrownBy(() -> launcher(NO_WAIT)
                            .launch(new Selection(THURSDAY, null, null, null, true)))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.OVERRIDE_REQUIRES_BATCH);

            verifyNoInteractions(gate, locks, regeneration);
            softly.assertThat(submitted).isEmpty();
        }

        @Test
        void an_override_with_a_batch_should_be_admitted_and_said_out_loud() {
            when(gate.decide(true)).thenReturn(new Proceed(true));

            final RunAccepted accepted = launcher(NO_WAIT)
                    .launch(new Selection(THURSDAY, null, BATCH, null, true));

            softly.assertThat(accepted.overridden())
                    .as("the caller is told that the run went ahead over a flag that would have "
                            + "stopped it, because that is the run most worth finding again")
                    .isTrue();
            softly.assertThat(accepted.registerDate()).isEqualTo(THURSDAY);
        }

        @Test
        void a_flag_that_says_off_should_refuse_before_any_work_is_submitted() {
            when(gate.decide(anyBoolean())).thenReturn(new Skipped(Reason.FLAG_OFF));

            Assertions.assertThatThrownBy(() -> launcher(NO_WAIT).launch(theWholeDay()))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.FLAG_OFF);

            softly.assertThat(submitted)
                    .as("a refusal the caller can see means nothing was started")
                    .isEmpty();
            verifyNoInteractions(locks, regeneration);
        }

        @Test
        void a_flag_that_cannot_be_read_should_refuse_the_same_way() {
            when(gate.decide(anyBoolean())).thenReturn(new Skipped(Reason.FLAG_UNREADABLE));

            Assertions.assertThatThrownBy(() -> launcher(NO_WAIT).launch(theWholeDay()))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .as("fail-closed: every failure to read leaves the legacy in charge")
                    .isEqualTo(OperationsReason.FLAG_UNREADABLE);
        }

        @Test
        void the_run_id_should_be_minted_and_recorded_before_the_work_is_submitted() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));

            final RunAccepted accepted;
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                accepted = launcher(NO_WAIT).launch(theWholeDay());
                lines = log.renderings();
            }

            softly.assertThat(accepted.runId())
                    .as("an outcome that arrives for a run whose id was never written down is an "
                            + "outcome nothing can be applied to")
                    .isNotBlank();
            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("run_id=" + accepted.runId())
                            && line.contains("trigger=operator"));
            softly.assertThat(submitted).hasSize(1);
            verifyNoInteractions(regeneration);
        }

        @Test
        void the_work_should_not_run_on_the_calling_thread() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));

            launcher(NO_WAIT).launch(theWholeDay());

            verify(regeneration, never()).regenerate(any(), anyBoolean());
            softly.assertThat(submitted)
                    .as("a gateway will not hold a connection for a run measured in tens of "
                            + "minutes, which is the whole reason the endpoint is asynchronous")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("the lock the background run takes")
    class TheLock {

        @Test
        void it_should_be_the_schedules_own_lock_attempted_without_blocking() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenReturn(nothingToDo());

            launcher(NO_WAIT).launch(theWholeDay());
            theSchedulerRunsIt();

            final ArgumentCaptor<LockConfiguration> taken =
                    ArgumentCaptor.forClass(LockConfiguration.class);
            verify(locks).lock(taken.capture());
            softly.assertThat(taken.getValue().getName())
                    .as("reused rather than restated, so the operator run and the schedule cannot "
                            + "hold two different locks over one night")
                    .isEqualTo(RegisterGenerationJob.LOCK_NAME);
            softly.assertThat(taken.getValue().getLockAtMostFor()).isEqualTo(LOCK_AT_MOST_FOR);
            softly.assertThat(taken.getValue().getLockAtLeastFor()).isEqualTo(Duration.ZERO);
        }

        @Test
        void a_lock_it_cannot_take_should_record_schedule_running_and_do_nothing() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.empty());

            final RunAccepted accepted = launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            verifyNoInteractions(regeneration);
            softly.assertThat(lines)
                    .as("two launches for one date on two replicas end with one of them saying "
                            + "this, rather than with a failure")
                    .anyMatch(line -> line.contains("run_id=" + accepted.runId())
                            && line.contains("outcome=SCHEDULE_RUNNING"));
        }

        @Test
        void a_bounded_wait_that_runs_out_should_still_end_in_schedule_running() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.empty());

            launcher(Duration.ofMillis(40)).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("outcome=SCHEDULE_RUNNING"));
            verifyNoInteractions(regeneration);
        }

        @Test
        void a_lock_it_took_should_be_given_back_when_the_run_finishes() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenReturn(nothingToDo());

            launcher(NO_WAIT).launch(theWholeDay());
            theSchedulerRunsIt();

            verify(regeneration).regenerate(theWholeDay(), false);
            verify(lock).unlock();
        }

        @Test
        void a_lock_it_took_should_be_given_back_when_the_run_throws() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.GENERATION_FAILED));

            launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            verify(lock)
                    .unlock();
            softly.assertThat(lines)
                    .as("a run that failed must not also leave the night's lock held, and its "
                            + "ending is written down rather than dropped into a Future")
                    .anyMatch(line -> line.contains("outcome=generation-failed"));
        }

        @Test
        void a_defect_in_the_run_should_be_recorded_rather_than_lost_on_the_executors_thread() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean()))
                    .thenThrow(new IllegalStateException("ZQX7DEFECT"));

            launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            verify(lock).unlock();
            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("cause=java.lang.IllegalStateException"));
            softly.assertThat(lines)
                    .as("named by class; its message belongs to whatever raised it")
                    .noneMatch(line -> line.contains("ZQX7DEFECT"));
        }
    }
}
