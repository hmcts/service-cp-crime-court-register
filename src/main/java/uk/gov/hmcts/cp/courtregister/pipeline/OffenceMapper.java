package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterOffence;
import uk.gov.hmcts.cp.courtregister.domain.RegisterDefendant;

/**
 * Maps a case's or application's offences onto the register's offences.
 *
 * <p>Ports {@code .../Mappers/Offence/OffenceMapper.js}. Most fields are copied off the payload's
 * offence; three are not.
 *
 * <ul>
 *   <li><strong>{@code wording}</strong> — joined to {@code offenceLegislation} with a {@code ####}
 *       sentinel progression substitutes for a newline when it renders, and with a literal
 *       {@code undefined} where there is no legislation (defect C24).</li>
 *   <li><strong>{@code verdictCode}</strong> — the legacy writes the verdict type's prose
 *       description into it (defect C23).</li>
 *   <li><strong>{@code results}</strong> — the gathered results scoped to this offence by level and
 *       offence id ({@code :24-26}), which is why the register defendant is a parameter. The only
 *       legacy offence fixture's context has an empty result list, so this scoping — the court
 *       register's one correctness advantage over its informant sibling — has never executed.</li>
 * </ul>
 */
final class OffenceMapper {

    private OffenceMapper() {
    }

    /**
     * Maps a list of offences.
     *
     * @param offences          the payload offences, gathered by the caller from a prosecution case
     *                          or from an application's cases and court order
     * @param registerDefendant the gathered defendant, whose results are scoped onto each offence
     * @return the mapped offences
     */
    /* default */ static List<CourtRegisterOffence> map(
            final List<JsonNode> offences, final RegisterDefendant registerDefendant) {
        throw new UnsupportedOperationException("OffenceMapper.map is implemented by T054");
    }
}
