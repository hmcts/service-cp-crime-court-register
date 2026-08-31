package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterAddress;

/**
 * Maps a payload address onto the register's address.
 *
 * <p>Ports {@code OutboundCourtRegister/CourtRegisterRequest/Mappers/Address/AddressMapper.js}. Five
 * lines pass straight through and the post code changes case on the way out — the payload spells it
 * {@code postcode}, the wire spells it {@code postCode}.
 *
 * <p>An absent address maps to nothing, not to an empty address. It is the distinction defect C29
 * turns on, and it is also the one the legacy's own test file gets wrong — that file has never run,
 * because its name misses Jest's pattern (defect C28), so nobody has noticed that it asserts
 * {@code []} where the mapper answers {@code undefined}.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T054, against the assertions T040 writes.
 */
final class AddressMapper {

    private AddressMapper() {
    }

    /**
     * Maps one address.
     *
     * @param addressInfo the payload's address object, or {@code null} where there is none
     * @return the mapped address, or {@code null} where there was no address to map
     */
    /* default */ static CourtRegisterAddress map(final JsonNode addressInfo) {
        throw new UnsupportedOperationException("AddressMapper.map is implemented by T054");
    }
}
