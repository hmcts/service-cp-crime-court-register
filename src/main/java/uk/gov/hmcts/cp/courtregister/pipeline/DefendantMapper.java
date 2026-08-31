package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Finds the payload defendant records a register defendant was gathered from.
 *
 * <p>Ports {@code .../Mappers/Defendant/DefendantMapper.js}. The one mapper that produces no
 * document component: it answers with the hearing's own defendant records, which the youth-defendant
 * and parent-guardian mappers then read personal details off.
 *
 * <p><strong>The precedence is the behaviour.</strong> Prosecution cases are searched first, by
 * {@code masterDefendantId} across every case's defendants; court applications are consulted
 * <em>only</em> when that search finds nothing ({@code :11-20}). The four legacy cases cover both
 * halves and the neither-matched case, but none states the precedence itself — a defendant present
 * in both places is never constructed.
 *
 * <p>The empty answer is where defect C19 begins: the youth mapper takes {@code defendants[0]} with
 * no length check, so an unmatched master defendant id throws, the throw is swallowed, and the whole
 * hearing's register is lost.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T054, against the assertions T042 writes.
 */
final class DefendantMapper {

    private DefendantMapper() {
    }

    /**
     * Finds the hearing's defendant records for one master defendant.
     *
     * @param hearing           the hearing payload
     * @param masterDefendantId the defendant's identity across cases and applications
     * @return the matching defendant records, cases first and applications only if there were none;
     *         empty where nothing matched
     */
    /* default */ static List<JsonNode> defendantsOf(
            final JsonNode hearing, final String masterDefendantId) {
        throw new UnsupportedOperationException(
                "DefendantMapper.defendantsOf is implemented by T054");
    }
}
