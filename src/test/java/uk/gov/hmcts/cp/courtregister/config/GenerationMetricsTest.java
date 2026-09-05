package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision.Unreadable;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision.UnreadableReason;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;

/**
 * One case per instrument of the downstream half: the name, the type, the label set and the
 * condition that moves it.
 *
 * <p>The sibling of {@code ProcessingMetricsTest}, written to the same rule: the names and labels
 * are asserted literally because they are a published surface - dashboards and alert rules are
 * written against them, and a rename is a breaking change even though nothing in this repository
 * would notice.
 *
 * <p>Three of them exist because a nightly, event-completed flow cannot be read from counters of
 * work that happened. The skipped counter separates "the flag is off" from "the flag could not be
 * read", which look identical from outside and are not the same night. The reconciled counter
 * separates an outcome that arrived from one that had to be fetched, because a run whose outcomes
 * all come from the reconciler is a broker to look at rather than a renderer. And the two age
 * gauges are the only reading that moves when nothing happens at all - a record that is never
 * batched, or a batch whose document never comes, touches no counter here, which is exactly the
 * silence the batches counter's {@code notified-nobody} outcome (defect fix P1) also ends.
 *
 * <p>Absences are asserted too: no instrument may carry an identifier as a label - every defendant
 * on this register is a youth, so a label that could name one is a privacy breach as well as a
 * cardinality explosion - and systemdocgenerator's own {@code reason} text is nowhere on this
 * surface, because it belongs to the batch row and never to a series.
 */
class GenerationMetricsTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final Tag... tags) {
        final Counter counter = registry.find(name).tags(List.of(tags)).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double gauge(final String name) {
        final Gauge gauge = registry.find(name).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double timerCount(final String name) {
        final Timer timer = registry.find(name).timer();
        return timer == null ? ABSENT : timer.count();
    }

    private double timerTotalSeconds(final String name) {
        final Timer timer = registry.find(name).timer();
        return timer == null ? ABSENT : timer.totalTime(TimeUnit.SECONDS);
    }

    private List<String> tagKeysOf(final String name) {
        final Meter meter = registry.find(name).meter();
        return meter == null
                ? List.of("<meter absent>")
                : meter.getId().getTags().stream().map(Tag::getKey).toList();
    }

    /**
     * The counter a night is read by.
     *
     * <p>Four of the seven batch states are terminal, and they are terminal for four different
     * reasons: everybody who subscribes was told, somebody was not, there was nobody to tell, or
     * the document never came. Folding them into one "finished" is the shape the progression leg
     * ends a batch in, and telling the third apart from the others is defect fix P1.
     */
    @Nested
    @DisplayName("courtregister_batches_total")
    class Batches {

        @Test
        void every_terminal_outcome_should_have_its_own_series() {
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.batchCompleted(BatchStatus.PARTIALLY_NOTIFIED);
            metrics.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            metrics.batchCompleted(BatchStatus.FAILED);

            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "partially-notified"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified-nobody"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void a_register_that_reached_nobody_should_count_as_neither_notified_nor_failed() {
            // Defect fix P1: the progression leg leaves a recipient-less batch at GENERATED and
            // publishes an event nothing subscribes to, so the failure is visible to no one. It is
            // a good ending, not a failure, and it is still worth alerting on.
            metrics.batchCompleted(BatchStatus.NOTIFIED_NOBODY);

            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified-nobody"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified"))
                    .isEqualTo(ABSENT);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "failed")).isEqualTo(ABSENT);
        }

        @Test
        void repeated_outcomes_should_accumulate_on_their_own_series() {
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.batchCompleted(BatchStatus.FAILED);

            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified")).isEqualTo(2);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_outcome_label_and_nothing_else() {
            // Never the batch id, never the court centre: one register is one court centre's
            // youth defendants for one day, so either label would name them.
            metrics.batchCompleted(BatchStatus.NOTIFIED);

            assertThat(tagKeysOf(GenerationMetrics.BATCHES)).containsExactly("outcome");
        }

        @Test
        void the_failure_reason_should_not_be_a_label_on_this_counter() {
            // The bounded reason is a column on register_batch and a field of the run report. It is
            // not a second dimension here: the batches counter answers "how did the night end", and
            // six reasons multiplied by seven outcomes is a series count nobody reads.
            metrics.batchCompleted(BatchStatus.FAILED);

            assertThat(tagKeysOf(GenerationMetrics.BATCHES)).doesNotContain("reason");
        }
    }

    @Nested
    @DisplayName("courtregister_generation_request_total")
    class GenerationRequest {

        @Test
        void an_accepted_request_should_count_under_the_contracts_202() {
            metrics.generationRequested(202);

            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "202"))
                    .isEqualTo(1);
        }

        @Test
        void a_status_line_the_contract_does_not_allow_should_be_its_own_series() {
            // 200 is not 202 and is counted as what it was, not as a failure: the contract permits
            // one status line, and the series is what says which one arrived.
            metrics.generationRequested(202);
            metrics.generationRequested(200);
            metrics.generationRequested(500);

            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "202"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "200"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "500"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_response_code_label_and_nothing_else() {
            metrics.generationRequested(202);

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_REQUEST))
                    .containsExactly("response_code");
        }
    }

    @Nested
    @DisplayName("courtregister_generation_latency")
    class GenerationLatency {

        @Test
        void a_batchs_time_from_request_to_outcome_should_be_timed() {
            metrics.generationLatency(Duration.ofSeconds(90));

            assertThat(timerCount(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(1);
            assertThat(timerTotalSeconds(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(90);
        }

        @Test
        void every_batch_should_be_timed_however_its_outcome_arrived() {
            // One distribution, not one per completion route: whether the event or the reconciler
            // brought the answer is the reconciled counter's question, not this one's.
            metrics.generationLatency(Duration.ofSeconds(30));
            metrics.generationLatency(Duration.ofSeconds(60));

            assertThat(timerCount(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(2);
            assertThat(timerTotalSeconds(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(90);
        }

        @Test
        void it_should_carry_no_label() {
            metrics.generationLatency(Duration.ofSeconds(1));

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_LATENCY)).isEmpty();
        }
    }

    /**
     * The counter that says the completion path is not working.
     *
     * <p>A reconciled outcome is a correct outcome, so nothing else in the flow marks it as
     * unusual. A night where every batch had to be fetched by the grace-period query is a durable
     * subscription that is not delivering, and this series is the only place it shows.
     */
    @Nested
    @DisplayName("courtregister_generation_reconciled_total")
    class Reconciled {

        @Test
        void an_outcome_the_reconciler_fetched_should_count_on_its_own_series() {
            metrics.reconciled();
            metrics.reconciled();

            assertThat(counter(GenerationMetrics.GENERATION_RECONCILED)).isEqualTo(2);
        }

        @Test
        void it_should_carry_no_label() {
            metrics.reconciled();

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_RECONCILED)).isEmpty();
        }
    }

    /**
     * The counter that says why a night generated nothing.
     *
     * <p>An off flag is the legacy generating, which is the cutover working. An unreadable flag is
     * this service fail-closed on an App Configuration outage, which is not. They produce the same
     * empty night, so only the label tells them apart.
     */
    @Nested
    @DisplayName("courtregister_generation_skipped_total")
    class Skipped {

        @Test
        void a_run_skipped_because_the_flag_is_off_should_count_under_off() {
            metrics.runSkipped(FlagDecision.OFF);

            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED, "reason", "off"))
                    .isEqualTo(1);
        }

        @Test
        void every_unreadable_cause_should_be_its_own_series() {
            metrics.runSkipped(new Unreadable(UnreadableReason.NOT_CONFIGURED));
            metrics.runSkipped(new Unreadable(UnreadableReason.NOT_FOUND));
            metrics.runSkipped(new Unreadable(UnreadableReason.ACCESS_DENIED));
            metrics.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));
            metrics.runSkipped(new Unreadable(UnreadableReason.MALFORMED));
            metrics.runSkipped(new Unreadable(UnreadableReason.CALL_FAILED));

            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-not-configured")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-not-found")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-access-denied")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-timed-out")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-malformed")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-call-failed")).isEqualTo(1);
        }

        @Test
        void an_unreadable_flag_should_not_be_folded_into_an_off_one() {
            metrics.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));

            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-timed-out")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED, "reason", "off"))
                    .as("an outage the service rode out fail-closed is not the cutover working")
                    .isEqualTo(ABSENT);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.runSkipped(FlagDecision.OFF);

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_SKIPPED)).containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("courtregister_notifications_total")
    class Notifications {

        @Test
        void an_accepted_recipient_should_count_with_the_status_line_that_accepted_it() {
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);

            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "accepted"), Tag.of("response_code", "202"))).isEqualTo(1);
        }

        @Test
        void a_refused_recipient_should_be_its_own_series() {
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);
            metrics.notificationSettled(NotificationStatus.FAILED, 500);

            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "accepted"), Tag.of("response_code", "202"))).isEqualTo(1);
            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "failed"), Tag.of("response_code", "500"))).isEqualTo(1);
        }

        @Test
        void a_recipient_nothing_answered_for_should_still_be_counted() {
            // A connection that never produced a status line is the failure most worth seeing, and
            // an absent label would make it the one shape no query matches.
            metrics.notificationSettled(NotificationStatus.FAILED, null);

            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "failed"),
                    Tag.of("response_code", GenerationMetrics.NO_RESPONSE))).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_status_and_response_code_labels_and_nothing_else() {
            // Never the address, never the recipient's name, never the batch: the recipients of a
            // youth court register are a protected list, and a metric label outlives a log line.
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);

            assertThat(tagKeysOf(GenerationMetrics.NOTIFICATIONS))
                    .containsExactlyInAnyOrder("status", "response_code");
        }
    }

    /**
     * The four readings that move when nothing happens.
     *
     * <p>Every counter above records work. These record its absence: a record nobody batched, a
     * batch nobody rendered, a deadline that cut a run short, and a flag nobody could read. A
     * nightly flow that stopped running moves none of the counters at all.
     */
    @Nested
    @DisplayName("the four gauges")
    class Gauges {

        @Test
        void all_four_should_be_registered_before_a_run_has_happened() {
            assertThat(gauge(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isZero();
            assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE)).isZero();
            assertThat(gauge(GenerationMetrics.PENDING_AFTER_DEADLINE)).isZero();
            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isEqualTo(1);
        }

        @Test
        void the_oldest_unbatched_record_should_be_reported_in_seconds() {
            metrics.oldestRecordedUnbatchedAge(Duration.ofHours(26));

            assertThat(gauge(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isEqualTo(93_600);
        }

        @Test
        void an_empty_store_should_report_no_age_rather_than_the_last_one() {
            metrics.oldestRecordedUnbatchedAge(Duration.ofHours(26));
            metrics.oldestRecordedUnbatchedAge(Duration.ZERO);

            assertThat(gauge(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isZero();
        }

        @Test
        void the_oldest_batch_awaiting_a_document_should_be_reported_in_seconds() {
            metrics.oldestGeneratingAge(Duration.ofMinutes(45));

            assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE)).isEqualTo(2_700);
        }

        @Test
        void batches_the_deadline_left_unrequested_should_be_reported_as_a_count() {
            metrics.pendingAfterDeadline(3);

            assertThat(gauge(GenerationMetrics.PENDING_AFTER_DEADLINE)).isEqualTo(3);
        }

        @Test
        void a_run_that_requested_everything_should_lower_the_pending_gauge() {
            metrics.pendingAfterDeadline(3);
            metrics.pendingAfterDeadline(0);

            assertThat(gauge(GenerationMetrics.PENDING_AFTER_DEADLINE)).isZero();
        }

        @Test
        void an_unreadable_flag_should_lower_the_flag_gauge_and_a_later_read_should_raise_it() {
            metrics.flagRead(new Unreadable(UnreadableReason.ACCESS_DENIED));
            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isZero();

            metrics.flagRead(FlagDecision.ON);
            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isEqualTo(1);
        }

        @Test
        void an_off_flag_should_leave_the_flag_gauge_up_because_it_is_an_answer() {
            // The gauge reports whether App Configuration answers, not what it answered. Off is the
            // cutover working; the skipped counter is where the two are told apart.
            metrics.flagRead(FlagDecision.OFF);

            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isEqualTo(1);
        }

        @Test
        void none_of_them_should_carry_a_label() {
            assertThat(tagKeysOf(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.OLDEST_GENERATING_AGE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.PENDING_AFTER_DEADLINE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.FLAG_READ_OK)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the surface as a whole")
    class Surface {

        @Test
        void only_the_gauges_should_be_registered_before_anything_happens() {
            // Gauges are state, not events: a dashboard must be able to read them from a pod that
            // has not yet run a night.
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).toList())
                    .containsExactlyInAnyOrder(
                            GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE,
                            GenerationMetrics.OLDEST_GENERATING_AGE,
                            GenerationMetrics.PENDING_AFTER_DEADLINE,
                            GenerationMetrics.FLAG_READ_OK);
        }

        @Test
        void exercising_everything_should_register_exactly_the_documented_instruments() {
            exerciseEveryInstrument();

            assertThat(registry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            GenerationMetrics.BATCHES,
                            GenerationMetrics.GENERATION_REQUEST,
                            GenerationMetrics.GENERATION_LATENCY,
                            GenerationMetrics.GENERATION_RECONCILED,
                            GenerationMetrics.GENERATION_SKIPPED,
                            GenerationMetrics.NOTIFICATIONS,
                            GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE,
                            GenerationMetrics.OLDEST_GENERATING_AGE,
                            GenerationMetrics.PENDING_AFTER_DEADLINE,
                            GenerationMetrics.FLAG_READ_OK);
        }

        @Test
        void no_instrument_should_carry_an_identifying_label() {
            exerciseEveryInstrument();

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getKey)
                    .distinct()
                    .sorted()
                    .toList())
                    .containsExactly("outcome", "reason", "response_code", "status");
        }

        @Test
        void every_label_value_should_be_a_bounded_code_or_a_status_line() {
            // Bounded codes and status lines, and never a batch id, a court centre, a recipient's
            // address or systemdocgenerator's own words about the document. The renderer's reason
            // is kept on the batch row, where support can read it and no series carries it.
            metrics.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            metrics.generationRequested(202);
            metrics.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            "notified-nobody", "202", "unreadable-timed-out", "accepted");
        }

        @Test
        void there_should_be_no_instrument_for_the_renderers_own_reason_text() {
            // sdg_reason is another system's prose about a document whose every defendant is a
            // child. It has a column and a support query; it has no series and no label.
            exerciseEveryInstrument();

            assertThat(registry.find("courtregister_generation_failure_reason_total").counter())
                    .isNull();
        }

        private void exerciseEveryInstrument() {
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.generationRequested(202);
            metrics.generationLatency(Duration.ofSeconds(30));
            metrics.reconciled();
            metrics.runSkipped(FlagDecision.OFF);
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);
            metrics.oldestRecordedUnbatchedAge(Duration.ofHours(1));
            metrics.oldestGeneratingAge(Duration.ofMinutes(20));
            metrics.pendingAfterDeadline(1);
            metrics.flagRead(FlagDecision.OFF);
        }
    }

    /**
     * The surface as Prometheus actually reads it, rather than as Micrometer holds it.
     *
     * <p>Everything above asks the registry what it was given. A dashboard and an alert rule ask
     * the scrape endpoint, and between the two sits a naming convention: a counter's name is
     * rewritten, a timer gains its unit and its suffixes, a tag becomes a label, and a series is a
     * name and a label set together. Names that agree in the registry can still arrive at Prometheus
     * renamed, and the first anyone would know is an alert that has quietly stopped firing.
     */
    @Nested
    @DisplayName("the surface as Prometheus scrapes it")
    class PrometheusSurface {

        private final PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        private final GenerationMetrics scraped = new GenerationMetrics(prometheus);

        /** Every sample line for one series name, in scrape order. */
        private List<String> samplesOf(final String series) {
            return prometheus.scrape().lines()
                    .filter(line -> line.startsWith(series + "{") || line.startsWith(series + " "))
                    .map(line -> line.substring(0, line.lastIndexOf(' ')))
                    .toList();
        }

        /** Every label key that appears anywhere in the scrape. */
        private List<String> scrapedLabelKeys() {
            return prometheus.scrape().lines()
                    .filter(line -> !line.startsWith("#"))
                    .filter(line -> line.contains("{"))
                    .map(line -> line.substring(line.indexOf('{') + 1, line.indexOf('}')))
                    .flatMap(labels -> Stream.of(labels.split(",")))
                    .filter(label -> label.contains("="))
                    .map(label -> label.substring(0, label.indexOf('=')).trim())
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Test
        @DisplayName("every terminal batch outcome is its own series, named as documented")
        void the_batches_counter_should_scrape_one_series_per_terminal_outcome() {
            scraped.batchCompleted(BatchStatus.NOTIFIED);
            scraped.batchCompleted(BatchStatus.PARTIALLY_NOTIFIED);
            scraped.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            scraped.batchCompleted(BatchStatus.FAILED);

            assertThat(samplesOf(GenerationMetrics.BATCHES))
                    .as("three of the four are good endings, and one of those is defect fix P1")
                    .containsExactlyInAnyOrder(
                            "courtregister_batches_total{outcome=\"notified\"}",
                            "courtregister_batches_total{outcome=\"partially-notified\"}",
                            "courtregister_batches_total{outcome=\"notified-nobody\"}",
                            "courtregister_batches_total{outcome=\"failed\"}");
        }

        @Test
        @DisplayName("every skip reason is its own series, named as documented")
        void the_skipped_counter_should_scrape_one_series_per_reason() {
            scraped.runSkipped(FlagDecision.OFF);
            scraped.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));

            assertThat(samplesOf(GenerationMetrics.GENERATION_SKIPPED))
                    .containsExactlyInAnyOrder(
                            "courtregister_generation_skipped_total{reason=\"off\"}",
                            "courtregister_generation_skipped_total"
                                    + "{reason=\"unreadable-timed-out\"}");
        }

        @Test
        @DisplayName("the latency timer scrapes in seconds")
        void the_latency_timer_should_scrape_under_a_unit_suffixed_name() {
            scraped.generationLatency(Duration.ofSeconds(30));

            assertThat(prometheus.scrape())
                    .contains("courtregister_generation_latency_seconds_count");
        }

        @Test
        @DisplayName("all four gauges scrape from a pod that has not run a night")
        void the_gauges_should_scrape_before_any_run_has_happened() {
            assertThat(samplesOf(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE))
                    .containsExactly(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE);
            assertThat(samplesOf(GenerationMetrics.OLDEST_GENERATING_AGE))
                    .containsExactly(GenerationMetrics.OLDEST_GENERATING_AGE);
            assertThat(samplesOf(GenerationMetrics.PENDING_AFTER_DEADLINE))
                    .containsExactly(GenerationMetrics.PENDING_AFTER_DEADLINE);
            assertThat(samplesOf(GenerationMetrics.FLAG_READ_OK))
                    .containsExactly(GenerationMetrics.FLAG_READ_OK);
        }

        @Test
        @DisplayName("the unlabelled counter scrapes as a single, unlabelled series")
        void the_reconciled_counter_should_scrape_without_a_label_set() {
            scraped.reconciled();

            assertThat(samplesOf(GenerationMetrics.GENERATION_RECONCILED))
                    .containsExactly(GenerationMetrics.GENERATION_RECONCILED);
        }

        @Test
        @DisplayName("no scraped series carries a label beyond the four bounded dimensions")
        void the_scrape_should_carry_no_identifying_label() {
            // The registry-level assertion above proves the tags this code sets. This one proves
            // what leaves the pod: a label added by a convention, a common tag or a registry filter
            // would appear here and nowhere else, and a metric label is a log line kept for a year.
            scraped.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            scraped.generationRequested(202);
            scraped.generationLatency(Duration.ofSeconds(30));
            scraped.reconciled();
            scraped.runSkipped(new Unreadable(UnreadableReason.MALFORMED));
            scraped.notificationSettled(NotificationStatus.FAILED, null);

            assertThat(scrapedLabelKeys())
                    .containsExactly("outcome", "reason", "response_code", "status");
        }
    }
}
