package uk.gov.hmcts.cp.courtregister.config;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.courtregister.domain.SettlementOperation;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per instrument: the name, the type, the label set and the condition that moves it.
 *
 * <p>The names and labels are asserted literally because they are a published surface — dashboards
 * and alert rules are written against them, and a rename is a breaking change even though nothing in
 * this repository would notice.
 *
 * <p>Two of them are this flow's own. The completions counter separates the five ways a run can end
 * well, because on this flow "nothing was sent" is the ordinary answer and not an incident: two of
 * its four no-op reasons are the most common outcomes the service has, and an undifferentiated
 * success is the legacy defect C33 rather than a simplification. The anomaly counter is the
 * telemetry half of fixes C19, C20 and C27 — a register that survived with a part missing, which the
 * legacy pipeline either lost whole or dropped in silence.
 *
 * <p>Absences are asserted too: no instrument may carry an identifier as a label — every defendant
 * on this register is a youth, so a label that could name one is a privacy breach as well as a
 * cardinality explosion — and there is no dead-letter depth gauge, because depth is read from the
 * platform's own queue metric rather than polled by service code.
 */
class ProcessingMetricsTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double gauge(final String name) {
        final Gauge gauge = registry.find(name).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private List<String> tagKeysOf(final String name) {
        final Meter meter = registry.find(name).meter();
        return meter == null
                ? List.of("<meter absent>")
                : meter.getId().getTags().stream().map(Tag::getKey).toList();
    }

    @Nested
    @DisplayName("courtregister_processed_total")
    class Processed {

        @Test
        void a_completed_request_should_increment_the_completed_series() {
            metrics.requestSettled(RequestOutcome.COMPLETED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed")).isEqualTo(1);
        }

        @Test
        void a_parked_request_should_increment_the_failed_series() {
            metrics.requestSettled(RequestOutcome.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void the_two_outcomes_should_be_separate_series() {
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.requestSettled(RequestOutcome.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed")).isEqualTo(2);
            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_outcome_label_and_nothing_else() {
            metrics.requestSettled(RequestOutcome.COMPLETED);

            assertThat(tagKeysOf(ProcessingMetrics.PROCESSED)).containsExactly("outcome");
        }
    }

    /**
     * The distribution this flow's dashboards are actually read for.
     *
     * <p>Expect a high completed-but-not-submitted rate: a hearing with no youth defendants and a
     * court centre nobody subscribes to are the two commonest results the service has, and neither
     * is an incident. Telling them apart is what makes the difference between a quiet day and a
     * broken pipeline visible without opening the processed log.
     */
    @Nested
    @DisplayName("courtregister_completions_total")
    class Completions {

        @Test
        void a_submitted_register_should_increment_its_own_series() {
            metrics.completed(CompletionReason.SUBMITTED);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "submitted")).isEqualTo(1);
        }

        @Test
        void every_reason_should_have_its_own_series() {
            metrics.completed(CompletionReason.SUBMITTED);
            metrics.completed(CompletionReason.GROUP_PROCEEDINGS);
            metrics.completed(CompletionReason.NO_DEFENDANTS);
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.completed(CompletionReason.NO_YOUTH_DEFENDANTS);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "submitted")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "group-proceedings"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-defendants"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-subscriptions"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-youth-defendants"))
                    .isEqualTo(1);
        }

        @Test
        void the_no_op_reasons_should_not_be_folded_into_one_another() {
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.completed(CompletionReason.NO_YOUTH_DEFENDANTS);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-subscriptions"))
                    .isEqualTo(2);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-youth-defendants"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.completed(CompletionReason.SUBMITTED);

            assertThat(tagKeysOf(ProcessingMetrics.COMPLETIONS)).containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("courtregister_processing_failures_total")
    class ProcessingFailures {

        @Test
        void a_transient_failure_ending_in_retrying_should_increment_it() {
            // Every failed run counts, not only the one that exhausts the delivery budget: a
            // request retrying quietly forever is exactly what this service exists to make visible.
            metrics.pipelineFailed(FailureClassification.TRANSIENT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES, "classification", "transient"))
                    .isEqualTo(1);
        }

        @Test
        void a_non_transient_failure_should_increment_its_own_series() {
            metrics.pipelineFailed(FailureClassification.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    "classification", "non-transient")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_classification_label_and_nothing_else() {
            metrics.pipelineFailed(FailureClassification.TRANSIENT);

            assertThat(tagKeysOf(ProcessingMetrics.PROCESSING_FAILURES))
                    .containsExactly("classification");
        }
    }

    /**
     * The telemetry half of fixes C19, C20 and C27.
     *
     * <p>Each of these was a place the legacy pipeline threw and lost the whole hearing's register,
     * or dropped a recipient without a log line. The register now survives with the unresolvable
     * part skipped, which is only an improvement if somebody can see it happening — so a skip is
     * counted here and written to {@code processed_output.anomaly_summary}, and is deliberately not
     * a failure.
     */
    @Nested
    @DisplayName("courtregister_transformation_anomalies_total")
    class TransformationAnomalies {

        @Test
        void every_anomaly_should_have_its_own_series() {
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_APPLICATION);
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            metrics.transformationAnomaly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
            metrics.transformationAnomaly(TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION);

            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "unresolvable-youth-defendant")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "unresolvable-application")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "letter-delivery-dropped")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "recipient-missing-email")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "recipient-not-for-distribution")).isEqualTo(1);
        }

        @Test
        void repeated_drops_in_one_register_should_count_once_each() {
            // The bounded summary the processed log records is a count — `letter-delivery-dropped:2`
            // — so the counter has to move once per dropped recipient, not once per register.
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "letter-delivery-dropped")).isEqualTo(2);
        }

        @Test
        void an_anomaly_should_not_be_counted_as_a_processing_failure() {
            // A skipped part is not a failed run. TRANSFORMATION_FAILED is reserved for a
            // transformation that cannot produce a document at all.
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    "classification", "non-transient")).isEqualTo(ABSENT);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_APPLICATION);

            assertThat(tagKeysOf(ProcessingMetrics.TRANSFORMATION_ANOMALIES))
                    .containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("courtregister_intake_suspensions_total and courtregister_intake_suspended")
    class Intake {

        @Test
        void suspending_intake_should_increment_the_counter_and_raise_the_gauge() {
            metrics.intakeSuspended();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1);
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isEqualTo(1);
        }

        @Test
        void resuming_intake_should_lower_the_gauge_without_touching_the_counter() {
            metrics.intakeSuspended();
            metrics.intakeResumed();

            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isZero();
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1);
        }

        @Test
        void the_gauge_should_read_zero_before_anything_happens() {
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isZero();
        }

        @Test
        void a_second_suspension_should_count_again() {
            metrics.intakeSuspended();
            metrics.intakeResumed();
            metrics.intakeSuspended();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(2);
        }

        @Test
        void neither_should_carry_a_label() {
            metrics.intakeSuspended();

            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEmpty();
            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENDED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("courtregister_deadlettered_total")
    class DeadLettered {

        @Test
        void every_reason_should_have_its_own_series() {
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            metrics.deadLettered(DeadLetterReason.COLLISION);
            metrics.deadLettered(DeadLetterReason.EXHAUSTED);
            metrics.deadLettered(DeadLetterReason.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "validation"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "collision"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "exhausted"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "non-transient"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.deadLettered(DeadLetterReason.VALIDATION);

            assertThat(tagKeysOf(ProcessingMetrics.DEAD_LETTERED)).containsExactly("reason");
        }

        /**
         * The two reason-labelled counters are separate instruments, so a dead-letter can never be
         * read as a completion — which is exactly the confusion the legacy pipeline's single
         * {@code Success: true} produced.
         */
        @Test
        void a_dead_letter_should_not_touch_the_completions_counter() {
            metrics.deadLettered(DeadLetterReason.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "non-transient"))
                    .isEqualTo(ABSENT);
        }
    }

    @Nested
    @DisplayName("courtregister_settlement_failures_total")
    class SettlementFailures {

        @Test
        void every_settlement_call_should_have_its_own_series() {
            metrics.settlementFailed(SettlementOperation.COMPLETE);
            metrics.settlementFailed(SettlementOperation.ABANDON);
            metrics.settlementFailed(SettlementOperation.DEADLETTER);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "complete"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "abandon"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "deadletter"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_operation_label_and_nothing_else() {
            metrics.settlementFailed(SettlementOperation.ABANDON);

            assertThat(tagKeysOf(ProcessingMetrics.SETTLEMENT_FAILURES))
                    .containsExactly("operation");
        }
    }

    @Nested
    @DisplayName("the unlabelled counters")
    class UnlabelledCounters {

        @Test
        void a_lost_lock_should_increment_its_counter() {
            metrics.lockLost();

            assertThat(counter(ProcessingMetrics.LOCK_LOSS)).isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.LOCK_LOSS)).isEmpty();
        }

        @Test
        void a_rejected_stale_runner_should_increment_its_counter() {
            metrics.staleRunnerRejected();

            assertThat(counter(ProcessingMetrics.STALE_RUNNER_REJECTIONS)).isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.STALE_RUNNER_REJECTIONS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("courtregister_servicebus_up")
    class ServiceBusUp {

        @Test
        void it_should_start_up_because_no_outage_has_been_observed() {
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);
        }

        @Test
        void it_should_mirror_the_health_component_in_both_directions() {
            metrics.serviceBusUp(false);
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isZero();

            metrics.serviceBusUp(true);
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);
        }

        /**
         * A scrape and a health check must read the same live state rather than the same remembered
         * one, whichever of them happens first: a Prometheus scrape does not call the health
         * endpoint on its way past.
         */
        @Test
        void it_should_answer_from_the_component_it_is_bound_to() {
            final boolean[] reachable = {true};
            metrics.bindServiceBusUp(() -> reachable[0]);

            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);

            reachable[0] = false;
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isZero();
        }

        @Test
        void it_should_not_carry_a_label() {
            assertThat(tagKeysOf(ProcessingMetrics.SERVICEBUS_UP)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the surface as a whole")
    class Surface {

        @Test
        void the_two_gauges_should_be_registered_before_anything_happens() {
            // Gauges are state, not events: a dashboard must be able to read them from a pod that
            // has not yet seen a message.
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).toList())
                    .containsExactlyInAnyOrder(
                            ProcessingMetrics.INTAKE_SUSPENDED,
                            ProcessingMetrics.SERVICEBUS_UP);
        }

        @Test
        void exercising_everything_should_register_exactly_the_documented_instruments() {
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.completed(CompletionReason.SUBMITTED);
            metrics.pipelineFailed(FailureClassification.TRANSIENT);
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            metrics.intakeSuspended();
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            metrics.settlementFailed(SettlementOperation.ABANDON);
            metrics.lockLost();
            metrics.staleRunnerRejected();

            assertThat(registry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            ProcessingMetrics.PROCESSED,
                            ProcessingMetrics.COMPLETIONS,
                            ProcessingMetrics.PROCESSING_FAILURES,
                            ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                            ProcessingMetrics.INTAKE_SUSPENSIONS,
                            ProcessingMetrics.DEAD_LETTERED,
                            ProcessingMetrics.SETTLEMENT_FAILURES,
                            ProcessingMetrics.LOCK_LOSS,
                            ProcessingMetrics.STALE_RUNNER_REJECTIONS,
                            ProcessingMetrics.INTAKE_SUSPENDED,
                            ProcessingMetrics.SERVICEBUS_UP);
        }

        @Test
        void no_instrument_should_carry_an_identifying_label() {
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.completed(CompletionReason.NO_YOUTH_DEFENDANTS);
            metrics.pipelineFailed(FailureClassification.TRANSIENT);
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
            metrics.deadLettered(DeadLetterReason.COLLISION);
            metrics.settlementFailed(SettlementOperation.COMPLETE);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getKey)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("outcome", "classification", "reason", "operation");
        }

        @Test
        void every_label_value_should_be_a_bounded_code() {
            // Bounded codes, and never a hearing id, a court centre, a defendant's name or an
            // exception message: the label set is a published surface and, on this flow, a
            // youth-defendant privacy boundary.
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.transformationAnomaly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("no-subscriptions", "recipient-missing-email");
        }

        @Test
        void there_should_be_no_dead_letter_depth_gauge() {
            // Depth comes from the platform's own queue metric. Polling it here would cost a
            // receiver connection and race with support tooling draining the queue.
            assertThat(registry.find("courtregister_deadletter_depth").gauge()).isNull();
        }
    }
}
