package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What systemdocgenerator's query API says became of one payload.
 *
 * <p>Read by the reconciler and by nothing else. The public event is how this service learns an
 * outcome; the query is the safety net for the batch whose event never arrived, which is a broker
 * problem rather than a rendering one and must not be allowed to strand a night's registers.
 *
 * <p><strong>Every component is nullable, and the combination is the answer.</strong> A generated
 * time with an id is a document; a failed time with a reason is a refusal; neither is a render that
 * is still running, and the reconciler fails the batch GENERATION_TIMED_OUT rather than guessing.
 * Modelling this as an enum here would move that judgement into the adapter, which sees one HTTP
 * body and not the grace period.
 *
 * @param documentFileServiceId the rendered document's file-service id, or {@code null}
 * @param generatedTime         when it was generated, or {@code null}
 * @param failedTime            when the generation failed, or {@code null}
 * @param reason                systemdocgenerator's own words about a failure, kept for support and
 *                              never logged at INFO; {@code null} where it said nothing
 */
public record DocumentStatus(
        UUID documentFileServiceId,
        Instant generatedTime,
        Instant failedTime,
        String reason) {
}
