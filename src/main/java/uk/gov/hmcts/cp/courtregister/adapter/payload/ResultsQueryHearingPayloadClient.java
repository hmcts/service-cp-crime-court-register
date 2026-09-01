package uk.gov.hmcts.cp.courtregister.adapter.payload;

import java.time.Duration;
import java.util.Optional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;

/**
 * The results query API, read when the cache has nothing.
 *
 * <p>The endpoint, the vendor media type and the identity header are the query side's contract, not
 * this service's. {@code HearingResultedCacheQuery/index.js:63-76} declares three of them and this
 * service carries exactly one — the {@code INT_} entry, {@code GET
 * /results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}} with
 * {@code Accept: application/vnd.results.hearing-details-internal+json} and a {@code CJSCPPUID}
 * header. The internal variant returns the untransformed hearing, which is what the cache holds, and
 * it takes <strong>no</strong> {@code hearingDate} parameter: the {@code ?hearingDate=} form K8
 * asserts belongs to the {@code EXT_} endpoint, which this flow does not use.
 *
 * <p><strong>The retry taxonomy is the corrected one — defect fix C3.</strong>
 * {@code CommonUtility/AxiosRetryWrapper.js:19,34} abandons the moment a response arrives carrying a
 * status at or below 429, which makes 429 and 408 — the two statuses that most plainly mean "ask me
 * again" — the least-retried failures there are. This client retries connect and read failures, 5xx,
 * 429 and 408, and never retries any other 4xx; it is the same taxonomy the progression submission
 * client applies, which is what makes the two agree about what a redelivery can fix.
 *
 * <p><strong>Nothing it cannot answer is silent — defect fix C32.</strong>
 * {@code getPrefixHearing} ({@code index.js:34-57}) returns {@code undefined} when the body has no
 * content, because there is no {@code else}, and {@code null} when the read threw; both make the
 * orchestrator's {@code if (hearingResultedObj)} false and the run reports success having produced
 * nothing. Here the two are told apart and neither is silence: a query side that answered and held
 * the hearing under no key answers {@link Optional#empty()}, which
 * {@link CachedHearingPayloadAdapter} turns into a transient failure once the cache has missed too,
 * and a read that could not be made at all raises
 * {@link uk.gov.hmcts.cp.courtregister.domain.PayloadUnavailableException} here.
 */
// PMD.UnusedPrivateField: the five collaborators are the seam T058's suite constructs the client
// with, and they are read by the body T065 writes. The suppression comes off with that body.
@SuppressWarnings("PMD.UnusedPrivateField")
public class ResultsQueryHearingPayloadClient implements HearingPayloadQuery {

    /** The resource's path under the results context; {@code {hearingId}} is the only variable. */
    public static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}";

    /** The vendor media type the internal hearing-details resource is served as. */
    public static final String ACCEPT = "application/vnd.results.hearing-details-internal+json";

    /**
     * The header carrying the user identity the query side authorises against.
     *
     * <p>Its value is the run's caller: the user who shared the results where the message named one,
     * and the configured system identity otherwise. The legacy sends {@code input.cjscppuid} and
     * skips the read entirely when there is none ({@code index.js:172-179}), which is a silent stop
     * this service does not have.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    private final RestClient restClient;
    private final String systemUserId;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final Duration retryInterval;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient    the client, carrying the results base URL and its timeouts
     * @param systemUserId  the fallback identity for a message that names no user; a secret, never
     *                      logged
     * @param objectMapper  the shared mapper, so a response is read exactly as any other JSON is
     * @param maxAttempts   total attempts including the first
     * @param retryInterval the wait between retryable attempts
     */
    public ResultsQueryHearingPayloadClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper, final int maxAttempts, final Duration retryInterval) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
    }

    @Override
    public Optional<JsonNode> fetch(final DistributionCommand command) {
        throw new UnsupportedOperationException("T065 reads the results query API");
    }
}
