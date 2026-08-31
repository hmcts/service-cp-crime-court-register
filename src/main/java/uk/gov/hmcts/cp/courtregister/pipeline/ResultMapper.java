package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterResult;

/**
 * Maps judicial results onto the register's results.
 *
 * <p>Ports {@code .../Mappers/Result/ResultMapper.js}: the result's text, and its {@code cjsCode}
 * under the name {@code cjsResultCode}. Both guards answer with nothing — {@code null} and an empty
 * list alike ({@code :6}) — which is what keeps an empty array out of a contract that puts
 * {@code minItems: 1} on every result list.
 *
 * <p>Called at three scopes with three differently-filtered lists: defendant-level results on the
 * defendant, case- and application-level results on the case or application, and offence-level
 * results on the offence they were ordered against. The filtering is the caller's; this maps
 * whatever it is handed.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T054, against the assertions T049 writes.
 */
final class ResultMapper {

    private ResultMapper() {
    }

    /**
     * Maps a list of judicial results.
     *
     * @param judicialResults the judicial results, already scoped by the caller
     * @return the mapped results, or {@code null} where there were none — empty included
     */
    /* default */ static List<CourtRegisterResult> map(final List<JsonNode> judicialResults) {
        throw new UnsupportedOperationException("ResultMapper.map is implemented by T054");
    }
}
