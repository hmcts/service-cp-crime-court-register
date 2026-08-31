package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.courtregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * Maps the cases and applications a defendant appeared on.
 *
 * <p>Ports {@code .../Mappers/ProsecutionCaseOrApplication/ProsecutionCaseOrApplicationMapper.js}.
 * Prosecution cases first, court applications second, concatenated ({@code :16-20}) — an order the
 * register prints and the comparator holds to. Each carries its reference, its case- or
 * application-level results, its offences, its counsel and the defendant's ASN.
 *
 * <p><strong>Three catalogued defects and an asymmetry.</strong> The case path was guarded against a
 * missing case by SNI-9005 and warns and skips; the application path was left unguarded, so an
 * absent {@code courtApplications} array or an unmatched application id throws and kills the whole
 * register (C20). {@code getASN} filters on {@code d.personDefendant.arrestSummonsNumber} with no
 * guard, so a legal-entity record carrying this defendant's own master id throws the same way
 * (C21). And the comment at {@code :64} says the applicant must be a prosecuting authority while the
 * code checks only the subject (C22) — the highest-value content question on the register.
 *
 * <p>The guarded skips are counted through {@code anomalies} rather than failing the transformation:
 * one unresolvable application must not cost a child their entry on the register, and it must not be
 * invisible either.
 *
 * <p>Two legacy methods are dead — {@code getApplicationReference} and
 * {@code getRespondentCounsels}, called from nowhere (C26). They are not reproduced.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T054, against the assertions T047 writes.
 */
final class ProsecutionCaseOrApplicationMapper {

    /** Where a skipped case or application is counted; called once per skip. */
    private final Consumer<TransformationAnomaly> anomalies;

    /**
     * Creates the mapper.
     *
     * @param anomalyRecorder where each guarded skip is counted
     */
    /* default */ ProsecutionCaseOrApplicationMapper(
            final Consumer<TransformationAnomaly> anomalyRecorder) {
        this.anomalies = anomalyRecorder;
    }

    /**
     * Maps one defendant's cases and applications.
     *
     * @param registerDefendant the gathered defendant, carrying the case and application ids and the
     *                          level-tagged results scoped onto them
     * @param hearing           the hearing payload
     * @return the mapped cases and applications, cases first
     */
    /* default */ List<CourtRegisterCaseOrApplication> map(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {
        throw new UnsupportedOperationException(
                "ProsecutionCaseOrApplicationMapper.map is implemented by T054");
    }
}
