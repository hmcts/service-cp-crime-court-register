package uk.gov.hmcts.cp.courtregister.inbound;

import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;

/**
 * Turns a raw message body into a validated {@link DistributionCommand}, or refuses it with a
 * bounded reason.
 *
 * <p>Compile-safe seam for T007: the surface the inbound adapter and the contract corpus call is
 * declared here; the validation itself arrives with T012.
 */
public class DistributionCommandParser {

    private final ObjectMapper objectMapper;

    /**
     * Creates the parser over the service-wide {@code ObjectMapper}.
     *
     * @param objectMapper the shared contract mapper
     */
    public DistributionCommandParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The correlation identifiers a body carries, whether or not the body is valid.
     *
     * @param body the raw message body, valid or not
     * @return the canonical identifiers it yielded, with nulls where it yielded none
     */
    public Correlation canonicalCorrelation(final String body) {
        throw new UnsupportedOperationException(
                "DistributionCommandParser.canonicalCorrelation is implemented by T012");
    }

    /**
     * Validates and converts a message body.
     *
     * @param body the raw message body
     * @return the validated command
     */
    public DistributionCommand parse(final String body) {
        throw new UnsupportedOperationException(
                "DistributionCommandParser.parse is implemented by T012");
    }

    /**
     * The correlation set, as far as a body could supply it.
     *
     * @param source     the permitted source, or null
     * @param requestId  the canonical request id, or null
     * @param hearingId  the canonical hearing id, or null
     * @param hearingDay the canonical hearing day, or null
     */
    public record Correlation(
            String source, String requestId, String hearingId, String hearingDay) {

        /** What a body that yielded nothing gives back. */
        public static final Correlation NONE = new Correlation(null, null, null, null);
    }
}
