package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * Maps the youth defendants the register is about.
 *
 * <p>Ports {@code .../Mappers/YouthDefendant/YouthDefendantMapper.js}, the mapper that calls most of
 * the others: name, date of birth, address, gender, nationality, ethnicity and post-hearing custody
 * status off the payload's person details, then parent or guardian, hearing details, aliases, cases
 * and applications, defendant-level results and defence counsel.
 *
 * <p><strong>Two catalogued defects.</strong> C19: the mapper takes {@code defendants[0]} with no
 * length check and then {@code personDefendant.personDetails} with no legal-entity fallback
 * ({@code :32,34}), so an unmatched or non-person defendant throws — and the throw is swallowed one
 * level up, losing the whole hearing's register for every other child on it. The fix skips that
 * defendant, counts it through {@code anomalies} and keeps the register. C25: ethnicity is emitted
 * only when the payload holds both an observed and a self-defined description ({@code :70-74}), so a
 * self-defined-only child has theirs dropped and the {@code ||} at {@code :72} is unreachable.
 *
 * <p>Two more things this mapper does that nothing has ever asserted: it composes the name from
 * first, middle and last while the one legacy case asserts first-plus-last against a fixture with no
 * middle name, and it reads a real {@code postHearingCustodyStatus} only when the defendant carries
 * case judicial results — which the fixture's empty list means it never has.
 */
final class YouthDefendantMapper {

    /** Where an unresolvable defendant is counted; called once per skip. */
    private final Consumer<TransformationAnomaly> anomalies;

    /**
     * Creates the mapper.
     *
     * @param anomalyRecorder where each skipped youth defendant is counted
     */
    /* default */ YouthDefendantMapper(final Consumer<TransformationAnomaly> anomalyRecorder) {
        this.anomalies = anomalyRecorder;
    }

    /**
     * Maps the register's youth defendants.
     *
     * @param youthDefendants the gathered defendants the youth filter left, in fragment order
     * @param hearing         the hearing payload
     * @return the mapped defendants, one per resolvable youth defendant
     */
    /* default */ List<CourtRegisterDefendant> map(
            final List<RegisterDefendant> youthDefendants, final JsonNode hearing) {
        throw new UnsupportedOperationException("YouthDefendantMapper.map is implemented by T054");
    }
}
