package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;

/**
 * Which side of a court application the register's defendants are on.
 *
 * <p>The port of progression's {@code CourtRegisterHandler.getDefendantType}
 * ({@code progression-command/progression-command-handler/src/main/java/uk/gov/moj/cpp/progression/
 * handler/CourtRegisterHandler.java:131-153}) together with the application lookup that feeds it,
 * {@code getCourtApplicationId} ({@code :226-235}) - the first defendant's first case-or-application
 * entry, and the application it names. Both are carried verbatim in
 * {@code src/test/resources/goldens/progression/harness/CourtRegisterHandlerRule.java}, which is
 * what recorded the goldens this resolver is held to.
 *
 * <p>The rule, in progression's own order: {@code Applicant} unless something else is proved;
 * {@code Appellant} where the application has an applicant with a master defendant and the
 * application type carries both the appeal and the applicant-appellant flags; {@code Respondent}
 * where the application has an applicant <em>without</em> a master defendant and one of the
 * application's respondents is a master defendant this register covers. A hearing with no court
 * application has no defendant type at all, which is why the answer is an {@link Optional} rather
 * than the string {@code Applicant} progression's own default would produce for it.
 *
 * <p><strong>The application is read from the hearing, not from the aggregate</strong> (research
 * §5). Progression reads the court application's current respondents through
 * {@code AggregateService}; this service reads the as-at-hearing copy the payload carries. The two
 * agree on the type flags and can differ only where a respondent list was edited after the hearing,
 * which is a deviation recorded in the design (Q25) and pinned by
 * {@code DefendantTypeResolverTest.respondents_are_read_from_the_hearing_not_the_aggregate}.
 *
 * <p>Pure, and a singleton for it: reference data is not consulted, no clock is read, and nothing it
 * is handed is edited (constitution Principle V).
 *
 * <p><strong>Seam.</strong> T022 replaces the refusal below with the ported rule; its green run is
 * {@code DefendantTypeResolverTest} (T019), whose cases come from the T004 goldens.
 */
public class DefendantTypeResolver {

    /** The task that replaces the refusal in this class with the ported rule. */
    private static final String PENDING_TASK =
            "T022 implements DefendantTypeResolver; DefendantTypeResolverTest (T019) guards it";

    /**
     * The defendant type this register's hearing puts its defendants in.
     *
     * @param hearing  the hearing as the claim-check payload carries it, whose
     *                 {@code courtApplications[]} the application is looked up in
     * @param document the assembled register, which names the application through its first
     *                 defendant and carries the master defendant ids a respondent is matched against
     * @return {@code Applicant}, {@code Appellant} or {@code Respondent}, or empty where the hearing
     *         carried no court application for this register
     */
    public Optional<String> resolve(final JsonNode hearing, final CourtRegisterDocument document) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
