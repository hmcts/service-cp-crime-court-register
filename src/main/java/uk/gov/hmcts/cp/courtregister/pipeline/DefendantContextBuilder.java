package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Gathers a hearing's judicial results into one context per defendant.
 *
 * <p>Ports the shared kernel's {@code DefendantContextService} in the court register's own
 * configuration — {@code new DefendantContextService(hearingObj, true)}
 * ({@code SetCourtRegister/index.js:33}), which is {@code isRegister = true} and
 * {@code isInformantRegister = false}. That is the only configuration this service has; the
 * one-argument and three-argument calls the legacy Jest suite also makes belong to the NOWs and
 * informant-register flows, which are not ported here.
 *
 * <p>Four passes, in the legacy's order: defendant-case and offence results from the prosecution
 * cases, then the court applications, then the hearing's defendant-level results. A context with no
 * master defendant id is dropped at the end ({@code :48-53}), and the ordered date is the latest of
 * the results the context gathered.
 *
 * <p><strong>Defect C22 is fixed here.</strong> The legacy's eligibility gate
 * ({@code DefendantContextBaseService.js:179-187}) reads
 * {@code courtApplication.subject.masterDefendant !== undefined} and nothing else when
 * {@code isInformantRegister} is false — so a court application brought by anyone at all, a
 * defence-initiated application included, contributes its results to the register. The mapper's own
 * comment says the check is "applicant is prosecutingAuthority and subject is masterDefendant"; only
 * the subject half was ever written. This builder requires <strong>both</strong>, which is what the
 * comment claims, what the informant register enforces, and what the fix register records as C22.
 */
public final class DefendantContextBuilder {

    /** The marker the red run records while the gather is unwritten. */
    private static final String UNIMPLEMENTED = "the defendant context gather is not ported yet";

    private final JsonNode hearing;
    private final Dates dates;

    /**
     * Creates the builder for one hearing.
     *
     * @param hearing the hearing payload, exactly as the producer sent it
     * @param dates   the register's date handling, for the latest-ordered-date sort
     */
    public DefendantContextBuilder(final JsonNode hearing, final Dates dates) {
        this.hearing = hearing;
        this.dates = dates;
    }

    /**
     * Gathers the hearing's defendants.
     *
     * @return one context per defendant carrying a master defendant id, in the order the legacy
     *     gathers them
     * @throws uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException if the payload
     *     cannot be read
     */
    /* default */ List<DefendantContext> build() {
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
