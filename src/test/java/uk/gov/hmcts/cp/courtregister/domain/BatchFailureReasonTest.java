package uk.gov.hmcts.cp.courtregister.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The two mappings, pinned constant by constant.
 *
 * <p><strong>[A]</strong> for the second of them. {@link BatchFailureReason#wasRenderRequested()}
 * arrived with the run report's {@code requested} count, driven red over the real job by
 * {@code RegisterGenerationJobTest}; the table below it is a characterisation of the answer that
 * landed, green on introduction and here for the reason this suite's own comment gives - a seventh
 * reason nobody classifies would otherwise join one side of each mapping silently.
 *
 * <p>{@link BatchFailureReason#isGeneratorAttributed()} is the one place that says which endings
 * were reported by a mechanism outside this service, and three enforcements read it:
 * {@code JdbcRegisterStore.markFailed} refuses a mark that disagrees with it,
 * {@code register_batch_completed_by_shape_chk} enumerates the same two reasons for the writers
 * that do not go through the store, and the {@code reconciled} metric counts the rows it lets
 * through. A constant classified the wrong way is therefore not one wrong answer but a batch that
 * cannot be written at all, or one written crediting a decision nobody made.
 *
 * <p><strong>The table below is the specification and the method is checked against it</strong>,
 * rather than the method being read twice. Every constant is offered by {@link EnumSource}, so a
 * seventh reason added to the enumeration and not classified here fails this suite - which is the
 * only moment at which somebody is still deciding what it means. Left to the implementation alone
 * a new constant would simply answer false, silently joining the four this service answers for.
 */
@DisplayName("batch failure reason")
class BatchFailureReasonTest {

    /**
     * Which endings somebody outside this service reported, written out one by one.
     *
     * <p>GENERATION_FAILED is systemdocgenerator's own verdict about the render and
     * GENERATION_TIMED_OUT is the reconciler's verdict about its silence: each arrived because
     * something went and learned it, so each names the mechanism that did. The other four are this
     * service's own verdict about a render it could not ask for or could not hear about - the
     * payload was never stored, the request was never delivered, it was refused, or the batch could
     * not be assembled at all - and nobody outside this service was ever in a position to answer.
     */
    private static final Map<BatchFailureReason, Boolean> ATTRIBUTION = Map.of(
            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, false,
            BatchFailureReason.RENDER_REQUEST_FAILED, false,
            BatchFailureReason.RENDER_REQUEST_REJECTED, false,
            BatchFailureReason.GENERATION_FAILED, true,
            BatchFailureReason.GENERATION_TIMED_OUT, true,
            BatchFailureReason.ASSEMBLY_FAILED, false);

    /**
     * Which endings follow a render this service had already asked for, written out one by one.
     *
     * <p>PAYLOAD_STORE_UNAVAILABLE is a payload that was never written and ASSEMBLY_FAILED is one
     * that was never built, so in neither case was systemdocgenerator ever sent anything. The other
     * four all follow a request that was made: refused with something other than the contract's
     * 202, undeliverable inside the run deadline, failed, or never answered at all.
     *
     * <p>What reads it is the run report's {@code requested} count, and what the classification
     * decides is whether an operator can tell "the renderer is rejecting our documents" from "our
     * file service is down" on the one line a night leaves behind. Both end the batch FAILED, so
     * the outcome count cannot carry the difference.
     */
    private static final Map<BatchFailureReason, Boolean> REQUESTED = Map.of(
            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, false,
            BatchFailureReason.RENDER_REQUEST_FAILED, true,
            BatchFailureReason.RENDER_REQUEST_REJECTED, true,
            BatchFailureReason.GENERATION_FAILED, true,
            BatchFailureReason.GENERATION_TIMED_OUT, true,
            BatchFailureReason.ASSEMBLY_FAILED, false);

    @ParameterizedTest
    @EnumSource(BatchFailureReason.class)
    void every_reason_should_be_classified_the_way_the_attribution_table_says(
            final BatchFailureReason reason) {
        assertThat(ATTRIBUTION)
                .as("a reason nobody has classified is a reason the store, the check constraint "
                        + "and the reconciled metric would each answer for on their own")
                .containsKey(reason);
        assertThat(reason.isGeneratorAttributed())
                .as("%s: whether the ending was reported by a mechanism outside this service, "
                        + "which is what decides whether the row may name one", reason)
                .isEqualTo(ATTRIBUTION.get(reason));
    }

    @Test
    void the_attribution_table_should_classify_every_reason_and_no_others() {
        assertThat(ATTRIBUTION.keySet())
                .as("the other direction: a reason removed from the enumeration leaves a "
                        + "classification here for an ending that can no longer happen")
                .containsExactlyInAnyOrder(BatchFailureReason.values());
    }

    @ParameterizedTest
    @EnumSource(BatchFailureReason.class)
    void every_reason_should_say_whether_the_render_had_been_asked_for(
            final BatchFailureReason reason) {
        assertThat(REQUESTED)
                .as("a reason nobody has classified would be counted as a render this service "
                        + "asked for, on a night it may never have got that far")
                .containsKey(reason);
        assertThat(reason.wasRenderRequested())
                .as("%s: whether the request had left this service by the time the batch ended "
                        + "this way, which is what the run report's requested count is", reason)
                .isEqualTo(REQUESTED.get(reason));
    }

    @Test
    void the_request_table_should_classify_every_reason_and_no_others() {
        assertThat(REQUESTED.keySet())
                .as("the other direction: a reason removed from the enumeration leaves a "
                        + "classification here for an ending that can no longer happen")
                .containsExactlyInAnyOrder(BatchFailureReason.values());
    }

    @Test
    void exactly_the_two_endings_that_never_left_this_service_should_be_unrequested() {
        assertThat(REQUESTED.entrySet().stream().filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey))
                .as("two of the six, and they are the pair whose registers the next run finds "
                        + "still RECORDED; the run asked the renderer about neither")
                .containsExactlyInAnyOrder(BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE,
                        BatchFailureReason.ASSEMBLY_FAILED);
    }

    @Test
    void exactly_the_two_endings_somebody_else_reported_should_be_attributed() {
        assertThat(ATTRIBUTION.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey))
                .as("two of the six, and the reconciled metric is the count of which one delivered "
                        + "each; the other four are this service answering for itself")
                .containsExactlyInAnyOrder(BatchFailureReason.GENERATION_FAILED,
                        BatchFailureReason.GENERATION_TIMED_OUT);
    }
}
