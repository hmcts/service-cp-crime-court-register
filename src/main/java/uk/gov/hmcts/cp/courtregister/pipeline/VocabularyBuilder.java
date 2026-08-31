package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.RegisterVocabulary;

/**
 * Computes one defendant's vocabulary — the eighteen facts a NOW subscription is matched against.
 *
 * <p>Ports the shared kernel's {@code VocabularyService} in the court register's own construction,
 * {@code new VocabularyService(hearingObj, defendantContextBase)}
 * ({@code SetCourtRegister/index.js:65}). Two arguments, not four: the major-creditor map and the
 * compliance-enforcement list belong to the NOWs and enforcement flows, and their absence is what
 * makes both creditor lists unconditionally empty here.
 *
 * <p>Custody is read from every prosecution case <em>and</em> every court application whose subject
 * is this defendant ({@code VocabularyService.js:194-244}) — the application scan is not gated on
 * the eligibility C22 fixes, because it is a fact about where the defendant is held rather than
 * about whose application it is. Attendance counts only days on which one of this defendant's own
 * results was ordered, and only attendance records naming one of their own defendant ids
 * ({@code :245-274}).
 *
 * <p><strong>Defect C30's vocabulary half is here.</strong> The two creditor lists are always
 * empty, and are carried as present-and-empty so the matcher can tell empty from absent — the
 * matcher half of the fix, where {@code anyMajorCreditor} is vacuously true on an empty list while
 * its two siblings can never match at all, belongs to {@code SubscriptionRules}.
 */
public final class VocabularyBuilder {

    /** The marker the red run records while the vocabulary is unwritten. */
    private static final String UNIMPLEMENTED = "the register vocabulary is not ported yet";

    private final JsonNode hearing;

    /**
     * Creates the builder for one hearing.
     *
     * @param hearing the hearing payload, exactly as the producer sent it
     */
    public VocabularyBuilder(final JsonNode hearing) {
        this.hearing = hearing;
    }

    /**
     * Computes the vocabulary of one gathered defendant.
     *
     * @param defendant the gathered defendant
     * @return their vocabulary
     * @throws uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException if the payload
     *     cannot be read
     */
    /* default */ RegisterVocabulary build(final DefendantContext defendant) {
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
