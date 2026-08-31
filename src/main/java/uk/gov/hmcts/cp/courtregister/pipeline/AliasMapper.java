package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterAlias;

/**
 * Maps a defendant's aliases onto the register's aliases.
 *
 * <p>Ports {@code .../Mappers/Alias/AliasMapper.js}. Four name parts copied one for one; everything
 * else on the payload's alias — {@code legalEntityName} among it — is deliberately not carried.
 *
 * <p><strong>The asymmetry is load-bearing.</strong> This mapper guards on truthiness alone
 * ({@code :9}), so an <em>absent</em> alias list answers with nothing while an <em>empty</em> one
 * answers with an empty list. Its counsel counterpart guards on {@code counsels && counsels.length}
 * and answers with nothing for both. Absent, null and empty are three different answers across these
 * two mappers, and the comparator vectors exist to keep them that way.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T054, against the assertions T041 writes.
 */
final class AliasMapper {

    private AliasMapper() {
    }

    /**
     * Maps a defendant's aliases.
     *
     * @param defendantAliases the payload's alias array, or {@code null} where the defendant has
     *                         none
     * @return the mapped aliases — empty for an empty array, {@code null} for an absent one
     */
    /* default */ static List<CourtRegisterAlias> map(final JsonNode defendantAliases) {
        throw new UnsupportedOperationException("AliasMapper.map is implemented by T054");
    }
}
