package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * Assembles one hearing's register from its fragment, its matched subscriptions and its payload.
 *
 * <p>Ports {@code OutboundCourtRegister/index.js:16-40}: the youth filter, the three dates and the
 * ids carried across from the fragment, the file name, and the three mappers that produce the venue,
 * the recipients and the defendants.
 *
 * <p><strong>Not one of the twelve mappers</strong> — it is what calls them, and it lands with the
 * transformation chain in T056 rather than with the mapper bodies in T054. It is declared here so
 * that T051 can write against a seam nobody else owns, on the same terms as the twelve.
 *
 * <p>Three fixes meet in this one method. C33: the two early returns — no matched subscriptions, no
 * youth defendants — are the flow's two most common outcomes and both surface today as a bare
 * {@code null} that the orchestration reports as success; here each becomes its own named
 * completion. C26: {@code courtCentreId} is read under the spelling the fragment actually uses,
 * where the legacy reads "Center" off an object carrying "Centre" and sends {@code undefined}. C11:
 * the file name is the register day, the court centre code and the hearing id, with no colons in it
 * and no collision between two hearings at one centre in the same second.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T056, against the assertions T051 writes.
 */
public final class AggregationMapper {

    private AggregationMapper() {
    }

    /**
     * Assembles the document.
     *
     * @param fragment             the hearing's register fragment
     * @param matchedSubscriptions the subscriptions matching it
     * @param hearing              the hearing payload
     * @param anomalies            where every guarded skip beneath this is counted
     * @return the assembled document, or {@code null} where the register has no youth defendant or
     *         no recipient — outcomes the caller names rather than swallows
     */
    public static CourtRegisterDocument map(
            final RegisterFragment fragment,
            final List<JsonNode> matchedSubscriptions,
            final JsonNode hearing,
            final Consumer<TransformationAnomaly> anomalies) {
        throw new UnsupportedOperationException("AggregationMapper.map is implemented by T056");
    }
}
