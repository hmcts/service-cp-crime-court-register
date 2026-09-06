package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One court centre's register for one day, as the {@code register_batch} row records it.
 *
 * <p>The batch is what the downstream half is about: the rows are grouped once, the group is given
 * an identity before anything is asked of another system, and every subsequent fact - the payload
 * that was stored, the document that came back, who was told and when it failed - is written
 * against that identity. The progression leg had no such row, which is why its failures were
 * invisible and its completions could flip registers that belonged to another day (defect P3).
 *
 * <p><strong>{@code batchId} is minted at assembly and travels as systemdocgenerator's
 * {@code sourceCorrelationId}.</strong> It is the only thing that correlates a public event back to
 * the rows the document was built from, so it is persisted before the render request rather than
 * after it: an event for a batch this service never recorded is unattributable, and an unattributed
 * document is a register nobody is told about.
 *
 * @param batchId           identity minted at assembly, sent as {@code sourceCorrelationId}
 * @param courtCentreId     the court centre the register is for
 * @param courtCentreOuCode that court centre's OU code, or {@code null} where the records carried
 *                          none
 * @param courtHouse        the hearing venue's court house, as progression's own column
 * @param registerDate      the London register day the batch groups
 * @param fileName          the first record's file name, as progression named the document
 * @param payloadFileId     minted before the file-service insert, sent as
 *                          {@code payloadFileServiceId}; {@code null} until then
 * @param documentFileId    the rendered PDF's file-service id, from the event or the query API;
 *                          {@code null} until the document exists
 * @param status            where the batch has got to
 * @param failureReason     the bounded reason the batch failed, or {@code null}
 * @param sdgReason         systemdocgenerator's own words about a failure, kept for support and
 *                          never logged at INFO, bounded by {@link #boundedReason(String)};
 *                          {@code null} where it said nothing
 * @param systemGenerated   true from the nightly schedule, false from the operations CLI
 * @param completedBy       which mechanism learned the outcome, or {@code null} while it is pending
 * @param assembledAt       when the batch was grouped and stamped
 * @param requestedAt       when systemdocgenerator accepted the render request, or {@code null}
 * @param generatedAt       when the document was generated, or {@code null}
 * @param notifiedAt        when the last recipient was settled, or {@code null}
 * @param failedAt          when the batch reached FAILED, or {@code null}
 * @param attempts          lifetime tally of render requests made for this batch, never a control
 *                          variable
 */
public record RegisterBatch(
        UUID batchId,
        UUID courtCentreId,
        String courtCentreOuCode,
        String courtHouse,
        LocalDate registerDate,
        String fileName,
        UUID payloadFileId,
        UUID documentFileId,
        BatchStatus status,
        BatchFailureReason failureReason,
        String sdgReason,
        boolean systemGenerated,
        CompletedBy completedBy,
        Instant assembledAt,
        Instant requestedAt,
        Instant generatedAt,
        Instant notifiedAt,
        Instant failedAt,
        int attempts) {

    /**
     * How much of systemdocgenerator's message this service keeps.
     *
     * <p>The same bound the column carries (data-model.md). It is a bound and not a validation:
     * how long the message is is systemdocgenerator's decision, not this service's, and a batch
     * whose failure could not be written because the renderer was verbose would stay GENERATING
     * until the reconciler gave up on it - the failure lost twice over.
     */
    public static final int REASON_LIMIT = 512;

    /** What a reader who cannot see the rest of the message is told about the rest. */
    private static final String TRUNCATION_MARKER = " [truncated]";

    /**
     * Bounds the renderer's words to what the row holds, and says so when it had to.
     */
    public RegisterBatch {
        sdgReason = boundedReason(sdgReason);
    }

    /**
     * The renderer's message as this service stores it.
     *
     * <p>Public because the store writes {@code sdg_reason} from a bare argument as well as from a
     * batch, and one bound applied in two places is two bounds waiting to disagree.
     *
     * <p>The marker replaces the tail rather than being appended past the bound, so the result is
     * exactly {@link #REASON_LIMIT} characters and the column never refuses it. A message that fits
     * is returned untouched, so nothing that was never truncated says it was.
     *
     * @param reason systemdocgenerator's own words, or {@code null} where it said nothing
     * @return the same words where they fit, and a marked prefix of them where they do not
     */
    public static String boundedReason(final String reason) {
        return reason == null || reason.length() <= REASON_LIMIT
                ? reason
                : reason.substring(0, REASON_LIMIT - TRUNCATION_MARKER.length())
                        + TRUNCATION_MARKER;
    }

    /**
     * Which of the two completion mechanisms learned a batch's outcome.
     *
     * <p>Recorded rather than inferred, and it is what the {@code reconciled} metric counts: a run
     * whose outcomes all arrive by RECONCILER is a broker or a subscription to look at, and nothing
     * else in the flow would say so.
     */
    public enum CompletedBy {

        /** The {@code public.event} listener, which is the platform pattern and the default. */
        EVENT,

        /** The grace-period reconciler asking systemdocgenerator's query API. */
        RECONCILER
    }

    /**
     * The key this batch was assembled under.
     *
     * @return the court centre and register day pair
     */
    public CourtCentreDay key() {
        return new CourtCentreDay(courtCentreId, registerDate);
    }
}
