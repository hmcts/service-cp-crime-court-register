package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterParentGuardian;
import uk.gov.hmcts.cp.courtregister.domain.RegisterDefendant;

/**
 * Maps a youth defendant's parent or guardian.
 *
 * <p>Ports {@code .../Mappers/ParentGuardian/ParentGuardianMapper.js}. Finds the defendant's
 * associated persons, takes the first whose role is {@code parent} and falls back to the first whose
 * role is {@code guardian} ({@code :24-31}), then composes the name from its parts and maps the
 * address. Where there is no such person there is no parent guardian — the field is simply absent.
 *
 * <p>The guardian fallback has never run: the one parent-guardian fixture has a parent. Neither has
 * the case that matters most — a parent or guardian with no address. The schema requires
 * {@code address} on this component, so that document is rejected with a 400, and the legacy
 * swallows the rejection and loses the whole hearing's register (defect C29).
 *
 * <p><strong>A seam.</strong> The behaviour lands in T054, against the assertions T046 writes.
 */
final class ParentGuardianMapper {

    private ParentGuardianMapper() {
    }

    /**
     * Maps the parent or guardian of one gathered defendant.
     *
     * @param registerDefendant the gathered defendant whose payload record carries the associated
     *                          persons
     * @param hearing           the hearing payload
     * @return the mapped parent or guardian, or {@code null} where the defendant has neither
     */
    /* default */ static CourtRegisterParentGuardian map(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {
        throw new UnsupportedOperationException("ParentGuardianMapper.map is implemented by T054");
    }
}
