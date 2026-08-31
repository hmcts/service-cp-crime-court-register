package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;

/**
 * Keeps only what a court extract may carry — at result level and then at prompt level.
 *
 * <p>Ports {@code NowsHelper/service/RegisterFragmentService.js:3-23}, which
 * {@code SetCourtRegister/index.js:43} runs over every gathered defendant before their vocabulary is
 * attached. Two filters, in this order:
 *
 * <ol>
 *   <li><strong>Results</strong> — kept when {@code judicialResult.isAvailableForCourtExtract} is
 *       truthy <em>and</em> {@code judicialResult.publishedForNows} is falsy. A result already
 *       published through the NOWs route is not published again through the register.</li>
 *   <li><strong>Prompts</strong>, on the results that survived — kept on
 *       {@code prompt.isAvailableForCourtExtract} where the prompt carries that field at all, and
 *       otherwise on the older {@code courtExtract} spelling, upper-cased and compared to
 *       {@code 'Y'}. The fallback is reached only when the newer field is <em>absent</em>: the
 *       legacy tests {@code === undefined}, which a JSON {@code null} does not satisfy, so a null
 *       flag is returned as the filter's own answer and drops the prompt.</li>
 * </ol>
 *
 * <p><strong>The legacy filters in place; this does not.</strong>
 * {@code r.judicialResult.judicialResultPrompts = …filter(…)} edits the hearing payload the activity
 * was handed, and is saved from being seen downstream only by the Durable Functions serialisation
 * boundary between activities. A Java pipeline passes references, so the payload would really be
 * edited — and the inbound tree is owned by the producer, not by this service (constitution
 * Principle IV). The filter derives new results instead and leaves the tree it was given untouched.
 */
public final class CourtExtractFilter {

    /** The marker the red run records while the filter is unwritten. */
    private static final String UNIMPLEMENTED = "the court-extract filter is not ported yet";

    private CourtExtractFilter() {
    }

    /**
     * Replaces each defendant's results with the ones a court extract may carry.
     *
     * @param defendants the gathered defendants
     */
    /* default */ static void apply(final List<DefendantContext> defendants) {
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
