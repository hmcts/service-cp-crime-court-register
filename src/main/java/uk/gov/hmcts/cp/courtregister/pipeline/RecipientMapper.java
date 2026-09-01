package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * Maps the matched subscriptions onto the organisations the register is emailed to.
 *
 * <p>Ports {@code .../Mappers/Recipient/RecipientMapper.js}. A subscription becomes a recipient when
 * it is marked for email delivery and for distribution and carries a recipient ({@code :12});
 * addresses are trimmed; the template name falls back to {@code cr_standard} ({@code :20-22}); and a
 * recipient with no first address is dropped ({@code :24-26}), because the contract requires one.
 * Where nothing survives, the answer is nothing rather than an empty list — and the register is then
 * never posted at all.
 *
 * <p><strong>Defect C27 is the silence, not the drops.</strong> The drops are product behaviour and
 * they stay: this is an email-only channel, so a first- or second-class-letter subscription cannot
 * be served. What the legacy does not do is say so — not a log line, not a count, where even its
 * informant sibling logs. Every drop is counted here through {@code anomalies}, which is what
 * reaches {@code processed_output.anomaly_summary} and the anomaly metric.
 */
final class RecipientMapper {

    /** Where a dropped recipient is counted; called once per drop. */
    private final Consumer<TransformationAnomaly> anomalies;

    /**
     * Creates the mapper.
     *
     * @param anomalyRecorder where each dropped recipient is counted
     */
    /* default */ RecipientMapper(final Consumer<TransformationAnomaly> anomalyRecorder) {
        this.anomalies = anomalyRecorder;
    }

    /**
     * Maps the matched subscriptions.
     *
     * @param matchedSubscriptions the subscriptions matching this register
     * @return the recipients, or {@code null} where none survived the drops
     */
    /* default */ List<CourtRegisterRecipient> map(final List<JsonNode> matchedSubscriptions) {
        throw new UnsupportedOperationException("RecipientMapper.map is implemented by T054");
    }
}
