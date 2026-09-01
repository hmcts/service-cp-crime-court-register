package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;

/**
 * Maps the hearing's court centre onto the register's venue.
 *
 * <p>Ports {@code .../Mappers/HearingVenue/HearingVenueMapper.js}: the court centre's name as the
 * court house, its local justice area's name where it has one, and its address — with the same
 * {@code postcode} to {@code postCode} case change {@link AddressMapper} makes, written out a second
 * time in the legacy rather than reused.
 *
 * <p><strong>Almost none of this has ever been executed.</strong> Every court-register fixture but
 * one carries a court centre of an id and an LJA only, so the address body has never run in the
 * legacy suite and {@code courtHouse} — the contract's required field — comes out absent. The legacy
 * constructor also reads {@code courtCentre.address} eagerly, so a hearing with no court centre at
 * all throws before {@code build()} is entered.
 */
final class HearingVenueMapper {

    private HearingVenueMapper() {
    }

    /**
     * Maps the venue.
     *
     * @param hearing the hearing payload
     * @return the mapped venue
     */
    /* default */ static CourtRegisterHearingVenue map(final JsonNode hearing) {
        throw new UnsupportedOperationException("HearingVenueMapper.map is implemented by T054");
    }
}
