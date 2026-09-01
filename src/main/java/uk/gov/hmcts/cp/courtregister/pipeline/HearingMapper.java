package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearing;
import uk.gov.hmcts.cp.courtregister.domain.RegisterDefendant;

/**
 * Maps the hearing details printed against one defendant.
 *
 * <p>Ports {@code .../Mappers/Hearing/HearingMapper.js}. Jurisdiction, hearing type and the
 * attending solicitor's name are copied; the two attendance fields are computed, and both are
 * catalogued defects.
 *
 * <ul>
 *   <li><strong>C8</strong> — {@code find(d => d.defendantId = defendantId)} ({@code :13,22}) is an
 *       assignment, not a comparison. It always answers with element zero of
 *       {@code defendantAttendance} and mutates that element's id on the way past. The one Jest case
 *       covering this mapper has a one-element array, so element zero is coincidentally right.</li>
 *   <li><strong>C9</strong> — the day found is then compared against the fragment's
 *       {@code registerDate}, a datetime in production against a bare date on the payload. They
 *       never match, so {@code defendantPresent} is {@code false} and
 *       {@code defendantAppearanceDetails} absent on every register ever sent. The same Jest case
 *       supplies a bare-date {@code registerDate}, so it matches there and the defect is invisible.
 *       </li>
 * </ul>
 *
 * <p>The fix takes the attendance record by equality against the mapped defendant's own ids and
 * matches the day against the defendant's latest ordered date — which is why this takes the register
 * defendant rather than the fragment: the ordered day and the defendant ids are both on it, and the
 * fragment's {@code registerDate} is no longer part of the answer.
 */
final class HearingMapper {

    private HearingMapper() {
    }

    /**
     * Maps the hearing details for one defendant.
     *
     * @param hearing           the hearing payload
     * @param registerDefendant the gathered defendant — their ids, and the day their results were
     *                          ordered
     * @param defendant         the payload defendant record they were gathered from, which carries
     *                          the defence organisation
     * @return the mapped hearing details, as they appear on {@link CourtRegisterDefendant}
     */
    /* default */ static CourtRegisterHearing map(
            final JsonNode hearing,
            final RegisterDefendant registerDefendant,
            final JsonNode defendant) {
        throw new UnsupportedOperationException("HearingMapper.map is implemented by T054");
    }
}
