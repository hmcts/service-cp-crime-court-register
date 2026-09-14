package uk.gov.hmcts.cp.courtregister.domain;

import static org.assertj.core.api.Assertions.entry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The two records of the report's model that carry behaviour, and the one shape a success has.
 *
 * <p>Most of this model is data: a kind, an entry, a bounded reason. Three things are not, and each
 * of them is a way a morning could look like something it is not. A window read backwards would
 * report nothing and read as a quiet night. A count map that omitted the kinds with nothing in them
 * would make an empty morning indistinguishable from a morning the report did not run. And a
 * delivered outcome whose accepted count was left at nought would say the log sink told nobody.
 *
 * <p>This and {@code LastScheduledRunTest} are the only two homes for the Monday-to-Friday
 * arithmetic; every later suite that needs a window mocks it.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the exception report's model")
class ExceptionReportModelTest {

    private static final ZoneId COURTS = ZoneId.of("Europe/London");

    private static final String COURTS_ZONE = "Europe/London";

    /** The morning report: 07:00 on a weekday, read in the courts' own zone. */
    private static final String REPORT_CRON = "0 0 7 * * MON-FRI";

    private static final String RUN_ID = "run-7f1c9204";

    private static final String SEAM =
            "the model's own behaviour lands next; this is its red run";

    /** How long after its own occurrence a scheduled run really asks for its window. */
    private static final long FIRING_DELAY_MILLIS = 50;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    void a_window_read_backwards_is_refused() {
        final Instant morning = london(2026, 9, 14, 7, 0);
        final Instant evening = london(2026, 9, 14, 19, 0);

        softly.assertThatThrownBy(() -> new ReportWindow(evening, morning))
                .as("a window read backwards reports nothing and looks exactly like a quiet "
                        + "morning, which is the one failure this report exists to make impossible")
                .isInstanceOf(IllegalArgumentException.class);
        softly.assertThatCode(() -> new ReportWindow(morning, evening))
                .as("and a window read forwards is the ordinary case")
                .doesNotThrowAnyException();
    }

    @Test
    void counts_answers_zero_for_every_kind_that_has_none() {
        final ExceptionReport report = report(
                exception(ExceptionKind.REQUEST_FAILED),
                exception(ExceptionKind.REQUEST_FAILED),
                exception(ExceptionKind.BATCH_LATE));

        softly.assertThat(counts(report))
                .as("five numbers always, so an empty kind is a nought a dashboard can read "
                        + "rather than an absence it has to interpret")
                .containsOnly(
                        entry(ExceptionKind.REQUEST_FAILED, 2),
                        entry(ExceptionKind.REQUEST_LATE, 0),
                        entry(ExceptionKind.BATCH_LATE, 1),
                        entry(ExceptionKind.BATCH_FAILED, 0),
                        entry(ExceptionKind.NOTIFICATION_FAILED, 0));
        softly.assertThat(counts(report()))
                .as("and a morning with nothing wrong is five zeroes, which is what makes it "
                        + "distinguishable from a morning the report did not run")
                .containsOnlyKeys(ExceptionKind.values())
                .containsValue(0);
    }

    @Test
    void since_last_scheduled_run_starts_at_the_previous_occurrence_of_the_cron() {
        final Instant firing = london(2026, 9, 16, 7, 0).plusMillis(FIRING_DELAY_MILLIS);

        final ReportWindow window = window(firing);

        softly.assertThat(window.from())
                .as("the run firing now is the most recent occurrence, so the window opens at the "
                        + "one before it - the run that last reported")
                .isEqualTo(london(2026, 9, 15, 7, 0));
        softly.assertThat(window.to())
                .as("and it ends at the moment the run started, not at its scheduled time")
                .isEqualTo(firing);
    }

    @Test
    void a_monday_window_starts_at_the_previous_friday_run() {
        final Instant firing = london(2026, 9, 14, 7, 0).plusMillis(FIRING_DELAY_MILLIS);

        softly.assertThat(window(firing).from())
                .as("a weekday schedule has no weekend occurrence, so a Monday morning reaches "
                        + "back to Friday and the weekend is inside a window rather than outside "
                        + "every one")
                .isEqualTo(london(2026, 9, 11, 7, 0));
    }

    @Test
    void a_delivered_log_outcome_is_one_accepted_and_none_refused() {
        final AtomicReference<DeliveryOutcome> answered = new AtomicReference<>(null);
        softly.assertThatCode(
                        () -> answered.set(DeliveryOutcome.delivered(ReportSinkName.LOG)))
                .as(SEAM)
                .doesNotThrowAnyException();

        softly.assertThat(answered.get())
                .as("the log sink has one audience, so a delivered outcome is one accepted and "
                        + "none refused, and its reason is NONE rather than absent")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.LOG, DeliveryStatus.DELIVERED,
                        ReportDeliveryReason.NONE, 1, 0));
    }

    // --- the model, asked so that a seam's refusal is recorded rather than thrown ---------------

    /**
     * The window a scheduled run would ask for, with a refusal recorded rather than thrown.
     *
     * <p>The backstop is a window at the epoch rather than a null, so a case that could not get an
     * answer fails on the instant it read and not on a null pointer - and it runs forwards, because
     * the record refuses one that does not.
     */
    private ReportWindow window(final Instant now) {
        return answered(() -> ReportWindow.sinceLastScheduledRun(REPORT_CRON, COURTS_ZONE, now),
                new ReportWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)));
    }

    private Map<ExceptionKind, Integer> counts(final ExceptionReport report) {
        return answered(report::counts, Map.of());
    }

    private <T> T answered(final Supplier<T> asked, final T whenRefused) {
        final AtomicReference<T> answer = new AtomicReference<>(whenRefused);
        softly.assertThatCode(() -> answer.set(asked.get())).as(SEAM).doesNotThrowAnyException();
        return answer.get();
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static ExceptionReport report(final ExceptionEntry... entries) {
        return new ExceptionReport(RUN_ID,
                new ReportWindow(london(2026, 9, 11, 7, 0), london(2026, 9, 14, 7, 0)),
                london(2026, 9, 14, 7, 0), List.of(entries));
    }

    /**
     * One entry of a kind, carrying only what that kind needs for a count to be about the kind.
     *
     * <p>The other twelve components are what the kind table says they are on the kinds that carry
     * them; a case about counting says nothing about them and leaves them empty rather than
     * inventing values a later assertion could come to depend on.
     */
    private static ExceptionEntry exception(final ExceptionKind kind) {
        return new ExceptionEntry(kind, null, null, null, null, null, null, null, null, null, null,
                null, 0);
    }

    private static Instant london(final int year, final int month, final int day, final int hour,
            final int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, COURTS).toInstant();
    }
}
