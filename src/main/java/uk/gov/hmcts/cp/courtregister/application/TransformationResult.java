package uk.gov.hmcts.cp.courtregister.application;

import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;

/**
 * What a hearing transformed into: a register to send, or a reason there is nothing to send.
 *
 * <p>A closed pair, because the legacy's answer to the second question is {@code undefined} and
 * every one of the four ways it can arise looks the same to the orchestrator. Three of them are
 * separate silent guards ({@code CourtRegisterOrchestrator/index.js:20, 23, 33, 50}) and the fourth
 * is a bare {@code null} from {@code OutboundCourtRegister/index.js:17-26}; all four end in
 * {@code Success: true}. Two of them — no matched subscriptions, and no youth defendants — are this
 * flow's commonest legitimate outcomes, so an undifferentiated success is the legacy defect C33
 * rather than an acceptable simplification.
 *
 * <p>Making the reason part of the type means a transformation cannot decline to produce a register
 * without saying which of the four it was.
 */
public sealed interface TransformationResult {

    /**
     * There is nothing to send, and this is which of the four reasons.
     *
     * @param reason the completion reason recorded for the run
     */
    record NoRegister(CompletionReason reason) implements TransformationResult {
    }

    /**
     * A register to send.
     *
     * @param document the assembled {@code add-court-register} command
     */
    record Register(CourtRegisterDocument document) implements TransformationResult {
    }
}
