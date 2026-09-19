package uk.gov.hmcts.cp.courtregister.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The attribution mapping, pinned constant by constant.
 *
 * <p><strong>[A]</strong>, and it was two mappings until the run report stopped needing the second.
 * {@code wasRenderRequested()} classified every reason by whether the render had been asked for,
 * and it could not: RENDER_REQUEST_FAILED is what a request that was made and answered nothing ends
 * under and what a batch no attempt could be started for ends under, so the run report counted a
 * request this service never made. That fact is carried on {@code BatchOutcome} now, set where the
 * call is made, and the predicate and the three cases that classified it are gone rather than left
 * here as a second answer to a question one place owns.
 *
 * <p>{@link BatchFailureReason#isGeneratorAttributed()} is the one place that says which endings
 * were reported by a mechanism outside this service, and three enforcements read it:
 * {@code JdbcRegisterStore.markFailed} refuses a mark that disagrees with it,
 * {@code register_batch_completed_by_shape_chk} enumerates the same two reasons for the writers
 * that do not go through the store, and the {@code reconciled} metric counts the rows it lets
 * through. A constant classified the wrong way is therefore not one wrong answer but a batch that
 * cannot be written at all, or one written crediting a decision nobody made.
 *
 * <p><strong>The tables below are the specification and the enumeration is checked against
 * them</strong>, rather than the methods being read twice. Both are keyed by the constant's
 * <em>name</em>, which is what reaches {@code register_batch.failure_reason}, a metric label and the
 * run report - and which lets a reason be specified here before it exists, so that the red run of
 * the task that adds it is a failing assertion about the vocabulary rather than a compile error.
 * Every constant is offered by {@link EnumSource}, so a reason added to the enumeration and not
 * classified in both tables fails this suite - which is the only moment at which somebody is still
 * deciding what it means. Left to the implementation alone a new constant would simply answer false
 * to the one question that has a method, and nobody would have answered the other at all.
 */
@DisplayName("batch failure reason")
class BatchFailureReasonTest {

    /** The stale-batch pass's ending, named as a string because that is what the column holds. */
    private static final String STALE_RELEASE = "NOT_COMPLETED_BY_NEXT_RUN";

    /**
     * Which endings somebody outside this service reported, written out one by one.
     *
     * <p>GENERATION_FAILED is systemdocgenerator's own verdict about the render and
     * GENERATION_TIMED_OUT is the reconciler's verdict about its silence: each arrived because
     * something went and learned it, so each names the mechanism that did. The other five are this
     * service's own verdict about a render it could not ask for, could not hear about, or stopped
     * waiting for - the payload was never stored, the request was never delivered, it was refused,
     * the batch could not be assembled at all, or the next run began and the answer still had not
     * come - and nobody outside this service was ever in a position to answer.
     */
    private static final Map<String, Boolean> ATTRIBUTION = Map.of(
            "PAYLOAD_STORE_UNAVAILABLE", false,
            "RENDER_REQUEST_FAILED", false,
            "RENDER_REQUEST_REJECTED", false,
            "GENERATION_FAILED", true,
            "GENERATION_TIMED_OUT", true,
            "ASSEMBLY_FAILED", false,
            STALE_RELEASE, false);

    /**
     * Which endings give their registers back to the next run, written out one by one.
     *
     * <p>data-model.md's "releases rows" column. A batch that ends under one of these leaves its
     * registers unbatched and RECORDED, so the next run re-assembles them; a batch that ends under
     * one of the others leaves them where they are, because a document either exists or was refused
     * for a reason re-rendering would meet again. The distinction is the difference between a
     * register that is late and a register that is lost, which is the whole subject of this
     * increment.
     *
     * <p>{@code JdbcRegisterStore.RELEASING_REASONS} is the one place that acts on it, and the store
     * is held to this table where the release can be observed - {@code RegisterStoreIT}, over a real
     * batch and its rows. Recorded here because this is where a new constant is classified, and a
     * constant nobody classified would join the four that keep their rows by saying nothing.
     */
    private static final Map<String, Boolean> RELEASES_ROWS = Map.of(
            "PAYLOAD_STORE_UNAVAILABLE", true,
            "RENDER_REQUEST_FAILED", false,
            "RENDER_REQUEST_REJECTED", false,
            "GENERATION_FAILED", false,
            "GENERATION_TIMED_OUT", false,
            "ASSEMBLY_FAILED", true,
            STALE_RELEASE, true);

    /** The reason of that name, empty while the vocabulary does not have one. */
    private static Optional<BatchFailureReason> reasonNamed(final String name) {
        return Arrays.stream(BatchFailureReason.values())
                .filter(reason -> reason.name().equals(name))
                .findFirst();
    }

    @ParameterizedTest
    @EnumSource(BatchFailureReason.class)
    void every_reason_should_be_classified_the_way_the_attribution_table_says(
            final BatchFailureReason reason) {
        assertThat(ATTRIBUTION)
                .as("a reason nobody has classified is a reason the store, the check constraint "
                        + "and the reconciled metric would each answer for on their own")
                .containsKey(reason.name());
        assertThat(reason.isGeneratorAttributed())
                .as("%s: whether the ending was reported by a mechanism outside this service, "
                        + "which is what decides whether the row may name one", reason)
                .isEqualTo(ATTRIBUTION.get(reason.name()));
    }

    @Test
    void the_attribution_table_should_classify_every_reason_and_no_others() {
        assertThat(ATTRIBUTION.keySet())
                .as("the other direction: a reason removed from the enumeration leaves a "
                        + "classification here for an ending that can no longer happen")
                .containsExactlyInAnyOrderElementsOf(names());
    }

    @Test
    void the_release_table_should_classify_every_reason_and_no_others() {
        assertThat(RELEASES_ROWS.keySet())
                .as("both directions again, and for the costlier of the two columns: an ending "
                        + "nobody classified keeps its registers, and keeping them is how a "
                        + "register is lost rather than late")
                .containsExactlyInAnyOrderElementsOf(names());
    }

    @Test
    void exactly_the_two_endings_somebody_else_reported_should_be_attributed() {
        assertThat(ATTRIBUTION.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey))
                .as("two of the seven, and the reconciled metric is the count of which one "
                        + "delivered each; the other five are this service answering for itself")
                .containsExactlyInAnyOrder("GENERATION_FAILED", "GENERATION_TIMED_OUT");
    }

    /**
     * An ending somebody outside this service reported is an ending about a render that happened,
     * so there is nothing to give back: the document exists, or the renderer said why it does not.
     * The releasing endings are this service's own, every one of them, and asserting the implication
     * rather than the two lists separately is what makes a future constant classified true in both
     * tables fail here instead of silently re-rendering a day somebody has already been sent.
     */
    @Test
    @DisplayName("no ending somebody else reported gives its registers back")
    void an_attributed_reason_should_never_release_its_rows() {
        for (final BatchFailureReason reason : BatchFailureReason.values()) {
            if (reason.isGeneratorAttributed()) {
                assertThat(RELEASES_ROWS.get(reason.name()))
                        .as("%s was reported about a render that happened", reason)
                        .isFalse();
            }
        }
    }

    /**
     * The bounded set itself, named rather than counted, because the names are the values of
     * {@code register_batch_failure_reason_chk} and a rename here without a migration is a batch
     * nobody can write. {@code BatchStateTest} holds the two to each other in both directions;
     * this case is the enumeration's own half of that statement.
     */
    @Test
    void the_seven_reasons_are_the_bounded_set() {
        assertThat(names())
                .containsExactlyInAnyOrder(
                        "PAYLOAD_STORE_UNAVAILABLE",
                        "RENDER_REQUEST_FAILED",
                        "RENDER_REQUEST_REJECTED",
                        "GENERATION_FAILED",
                        "GENERATION_TIMED_OUT",
                        "ASSEMBLY_FAILED",
                        STALE_RELEASE);
    }

    /**
     * The stale-batch pass is this service deciding for itself that it has waited long enough. No
     * event arrived, no query was made and no mechanism outside this service reported anything, so
     * the row names none - and {@code register_batch_completed_by_shape_chk} refuses one that does.
     */
    @Test
    void not_completed_by_next_run_is_not_generator_attributed() {
        assertThat(reasonNamed(STALE_RELEASE))
                .as("the reason the stale-batch pass writes")
                .get()
                .satisfies(reason -> assertThat(reason.isGeneratorAttributed())
                        .as("nobody outside this service was asked and nobody answered")
                        .isFalse());
    }

    /**
     * And the point of the ending: the registers go back. A batch released without its rows being
     * released is a court centre's day stamped to a terminal batch that no later run can see, which
     * is the lost register this increment exists to end.
     */
    @Test
    void not_completed_by_next_run_releases_its_rows() {
        assertThat(releasingReasons())
                .as("the endings that give their registers back for the next run to re-assemble, "
                        + "and the stale-batch pass's is the third of them")
                .containsExactlyInAnyOrder(
                        "PAYLOAD_STORE_UNAVAILABLE", "ASSEMBLY_FAILED", STALE_RELEASE);
    }

    /** The names of the reasons that exist and that the release table classifies as releasing. */
    private static List<String> releasingReasons() {
        return names().stream()
                .filter(name -> Boolean.TRUE.equals(RELEASES_ROWS.get(name)))
                .toList();
    }

    private static List<String> names() {
        return Arrays.stream(BatchFailureReason.values()).map(Enum::name).toList();
    }
}
