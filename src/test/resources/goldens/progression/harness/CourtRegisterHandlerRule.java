import static java.util.Objects.isNull;
import static org.apache.commons.collections.CollectionUtils.isEmpty;

import uk.gov.justice.core.courts.CourtApplication;
import uk.gov.justice.core.courts.courtRegisterDocument.CourtRegisterDefendant;
import uk.gov.justice.core.courts.courtRegisterDocument.CourtRegisterDocumentRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The defendant-type rule, copied verbatim.
 *
 * <p>Source: cpp-context-progression at 79edf7cf3dab8a8f4fb667bc0946f36cc4547ee6,
 * progression-command/progression-command-handler/src/main/java/uk/gov/moj/cpp/progression/handler/
 * CourtRegisterHandler.java. getDefendantType is lines 131-153 and getCourtApplicationId is lines
 * 226-235 of that file; both are reproduced below character for character, including their private
 * modifiers. The only additions are the two package-private wrappers at the bottom, which exist so
 * the recorder can call private methods, and this comment.
 *
 * <p>CourtRegisterHandler itself cannot be compiled outside progression - it extends
 * AbstractCommandHandler and injects EventSource, AggregateService and Requester - so the rule is
 * carried here instead of the class.
 */
public class CourtRegisterHandlerRule {

    private String getDefendantType(final CourtRegisterDocumentRequest courtRegisterDocumentRequest,
                                    final CourtApplication courtApplication) {
        final AtomicReference<String> defendantType = new AtomicReference<>("Applicant");

        Optional.ofNullable(courtApplication).ifPresent(c ->
                Optional.ofNullable(c.getApplicant()).ifPresent(a -> {
                            if (Optional.ofNullable(a.getMasterDefendant()).isPresent()) {
                                if (c.getType().getAppealFlag() && c.getType().getApplicantAppellantFlag()) {
                                    defendantType.set("Appellant");
                                }
                            } else {
                                final List<UUID> defandantList = courtRegisterDocumentRequest.getDefendants().stream()
                                        .map(CourtRegisterDefendant::getMasterDefendantId).collect(Collectors.toList());
                                if (courtApplication.getRespondents().stream()
                                        .anyMatch(respondent -> defandantList.contains(respondent.getMasterDefendant().getMasterDefendantId()))) {
                                    defendantType.set("Respondent");
                                }
                            }
                        }
                )
        );
        return defendantType.get();
    }

    private UUID getCourtApplicationId(final CourtRegisterDocumentRequest courtRegisterDocumentRequest) {
        if (isNull(courtRegisterDocumentRequest.getDefendants()) ||
                isEmpty(courtRegisterDocumentRequest.getDefendants()) ||
                isEmpty(courtRegisterDocumentRequest.getDefendants().get(0).getProsecutionCasesOrApplications())
        ) {
            return null;
        }

        return courtRegisterDocumentRequest.getDefendants().get(0).getProsecutionCasesOrApplications().get(0).getCourtApplicationId();
    }

    String defendantTypeOf(final CourtRegisterDocumentRequest request, final CourtApplication application) {
        return getDefendantType(request, application);
    }

    UUID courtApplicationIdOf(final CourtRegisterDocumentRequest request) {
        return getCourtApplicationId(request);
    }
}
