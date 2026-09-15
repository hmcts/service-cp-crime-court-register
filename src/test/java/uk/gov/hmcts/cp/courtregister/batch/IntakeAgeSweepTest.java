package uk.gov.hmcts.cp.courtregister.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.courtregister.domain.RequestStatus;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * The two readings an alert fires on, and what the sweep does when it cannot take one.
 *
 * <p>Design section 11 promised both gauges and 001 built neither, so until now the only thing that
 * noticed a request stuck RECEIVED for three days was somebody looking. They are refreshed on a
 * fixed delay of their own, in <strong>every</strong> JVM and under <strong>no</strong> lock: a
 * gauge describes the pod that publishes it, so a locked sweep would have one replica reading the
 * store while the others published whatever they last saw - one pod's view under two pod labels,
 * and an alert that is a coin toss. Unlocked, each replica refreshes its own pair and an alert
 * aggregates them with {@code max()}, which is the honest reading: the oldest unfinished request is
 * the oldest any pod can see.
 *
 * <p>A read it cannot take is <strong>the one refusal this service absorbs</strong>, and it is
 * absorbed because {@code .claude/rules/design_rules.md} says so: <em>"The one absorbed refusal is
 * telemetry: a round-trip reading that cannot be taken may not cost a Youth Offending Team its
 * e-mail, so it stops where it happens, is counted, and is said at WARN."</em> All three halves are
 * asserted here, plus the fourth thing that makes it an absorption rather than a swallow: the
 * schedule survives it. A fixed-delay schedule cancels the task that throws, so a sweep that let a
 * store blip out would stop refreshing the gauges for the life of the pod - the two readings would
 * sit frozen at their last value with nothing saying they had stopped moving, which is the silent
 * failure this service exists to cure, reached through the instrument that was supposed to reveal
 * it.
 */
@DisplayName("IntakeAgeSweep")
class IntakeAgeSweepTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** The resolved intake threshold, as `courtregister.report.request-terminal-within` binds it. */
    private static final Duration THRESHOLD = Duration.ofMinutes(30);

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final ProcessedRequestRepository requests = mock(ProcessedRequestRepository.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    private final IntakeAgeSweep sweep =
            new IntakeAgeSweep(requests, metrics, THRESHOLD, clock);

    @Test
    void both_gauges_move_from_the_repositorys_answers() {
        stillRunning(unfinishedFor(900), unfinishedFor(2400));

        sweep.sweepScheduled();

        assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                .as("the age the database computed, not one this JVM worked out from a stored "
                        + "timestamp")
                .isEqualTo(900);
        assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isEqualTo(2);
        verify(requests).nonTerminalOlderThan(NOW.minus(THRESHOLD));
    }

    @Test
    void both_gauges_return_to_zero_when_nothing_is_unfinished() {
        stillRunning(unfinishedFor(900), unfinishedFor(2400));
        sweep.sweepScheduled();

        stillRunning();
        sweep.sweepScheduled();

        assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                .as("a reading that only ever moved up would need an incident before it could fall")
                .isZero();
        assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isZero();
    }

    @Test
    void a_failed_read_leaves_the_gauges_at_their_last_reading_counted_and_does_not_cancel_the_schedule() {
        stillRunning(unfinishedFor(900), unfinishedFor(2400));
        sweep.sweepScheduled();

        when(requests.oldestNonTerminal()).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read the oldest unfinished request",
                new IllegalStateException("connection refused to cp-nle-01.postgres:5432")));

        try (CapturedLog log = CapturedLog.capturing(IntakeAgeSweep.class)) {
            assertThatCode(sweep::sweepScheduled)
                    .as("a fixed-delay schedule cancels the task that throws, and a sweep that "
                            + "stopped refreshing would leave the two readings frozen with nothing "
                            + "saying they had stopped moving")
                    .doesNotThrowAnyException();

            assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                    .as("the last reading, not a zero the store never said")
                    .isEqualTo(900);
            assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isEqualTo(2);
            assertThat(counter(ProcessingMetrics.INTAKE_SWEEP_FAILURES, "store-unavailable"))
                    .as("a path that drops something moves a counter - it is what makes the "
                            + "absorption visible at all")
                    .isEqualTo(1);

            final List<ILoggingEvent> warnings = log.events().stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst().getFormattedMessage())
                    .as("named by class; the message belongs to whatever raised it and is exactly "
                            + "where a connection string turns up")
                    .contains("StoreUnavailableException")
                    .doesNotContain("cp-nle-01")
                    .doesNotContain("connection refused");
            assertThat(warnings.getFirst().getThrowableProxy())
                    .as("and no throwable this service did not write is attached")
                    .isNull();
        }
    }

    @Test
    void the_sweep_opens_its_own_run_id_and_removes_it() {
        final AtomicReference<String> seen = new AtomicReference<>();
        when(requests.oldestNonTerminal()).thenAnswer(call -> {
            seen.set(RunCorrelation.current());
            return Optional.empty();
        });
        when(requests.nonTerminalOlderThan(any(Instant.class))).thenReturn(List.of());

        sweep.sweepScheduled();

        assertThat(seen.get())
                .as("the scheduler's threads are pooled, so a sweep needs a correlation of its own "
                        + "in place of the two a delivery has")
                .isNotNull();
        assertThat(RunCorrelation.current())
                .as("and an id left behind would be inherited by the next run on that thread, "
                        + "which reads as a true correlation and is worse than none")
                .isNull();
    }

    @Test
    void the_sweep_is_scheduled_and_deliberately_unlocked() throws NoSuchMethodException {
        final Method scheduled = IntakeAgeSweep.class.getDeclaredMethod("sweepScheduled");
        final Scheduled schedule = scheduled.getAnnotation(Scheduled.class);

        assertThat(scheduled.getReturnType())
                .as("a schedule has nobody to return a reading to")
                .isEqualTo(void.class);
        assertThat(schedule)
                .as("the gauges are refreshed on a cadence of their own, not on the nightly run's")
                .isNotNull();
        assertThat(schedule == null ? null : schedule.fixedDelayString())
                .as("the intake half's own setting, readable on a pod that binds neither the "
                        + "report's record nor the generation half's")
                .isEqualTo("${courtregister.intake.gauge-refresh}");
        assertThat(scheduled.getAnnotation(SchedulerLock.class))
                .as("no lock, on purpose: a gauge describes the JVM that publishes it, so a locked "
                        + "sweep would show one pod's view under every pod's labels")
                .isNull();
    }

    /** What the two reads answer for a store holding the given unfinished requests. */
    private void stillRunning(final ProcessedRequestSummary... unfinished) {
        when(requests.oldestNonTerminal()).thenReturn(
                unfinished.length == 0 ? Optional.empty() : Optional.of(unfinished[0]));
        when(requests.nonTerminalOlderThan(any(Instant.class))).thenReturn(List.of(unfinished));
    }

    /**
     * One request still in flight, aged by the database rather than by this JVM.
     *
     * @param ageSeconds how old the read said it was
     * @return the projection the read answers with
     */
    private ProcessedRequestSummary unfinishedFor(final long ageSeconds) {
        return new ProcessedRequestSummary(
                "RESULTS",
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.parse("2026-09-15"),
                RequestStatus.RETRYING,
                3,
                null,
                NOW.minusSeconds(ageSeconds),
                NOW.minusSeconds(ageSeconds),
                ageSeconds);
    }

    private double gauge(final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? ABSENT : found.value();
    }

    private double counter(final String name, final String reason) {
        final Counter found = registry.find(name)
                .tag(ProcessingMetrics.REASON_TAG, reason)
                .counter();
        return found == null ? ABSENT : found.count();
    }
}
