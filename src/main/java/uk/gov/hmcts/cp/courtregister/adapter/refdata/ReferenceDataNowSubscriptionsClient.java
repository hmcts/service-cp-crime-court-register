package uk.gov.hmcts.cp.courtregister.adapter.refdata;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;

/**
 * The now-subscriptions read, against the reference-data query API.
 *
 * <p>The endpoint, the {@code on} query parameter, the vendor media type and the identity header are
 * reference data's contract, not this service's: {@code GET
 * /referencedata-query-api/query/api/rest/referencedata/now-subscriptions?on={YYYY-MM-DD}} with
 * {@code Accept: application/vnd.referencedata.query.get-now-subscriptions+json} and a
 * {@code CJSCPPUID} header ({@code NowsHelper/service/ReferenceDataService.js:33-54}).
 *
 * <p><strong>The day is chosen upstream, and it is chosen fixed — defect fix C12.</strong>
 * {@code ReferenceDataService.js:38} derives {@code on} as
 * {@code new Date(registerDate).toISOString().slice(0, 10)}, and the register date it is handed
 * carries C10's relabelled local time with a literal {@code Z}: a hearing shared between 23:00 and
 * midnight BST therefore reads the <em>next</em> day's subscription set, and is addressed to
 * whoever is subscribed tomorrow. The corrected day is {@code Dates.subscriptionDay}, the UTC day of
 * the share instant, and it is computed once in the pipeline. This client renders the day it is
 * handed as the plain {@code YYYY-MM-DD} the parameter takes and derives nothing of its own — one
 * derivation, in one place, is what makes the fix hold.
 *
 * <p><strong>The retry taxonomy is the corrected one — defect fix C3</strong>, and it is the same
 * taxonomy the progression submission client applies: connect and read failures, 5xx, 429 and 408
 * are retried; any other 4xx is a refusal no redelivery can change.
 *
 * <p><strong>An empty answer and no answer are different things.</strong> The legacy catches
 * everything and returns {@code null} ({@code :50-53}), which
 * {@code CourtRegisterSubscriptions/index.js:31-44} cannot tell from "reference data answered,
 * nobody is subscribed" — so an outage files a register that reaches nobody and nothing records
 * why. Here an answer carrying no subscriptions is returned as the answer it is, and the
 * subscription matcher decides {@code no-subscriptions}; a read that could not be made raises
 * {@link uk.gov.hmcts.cp.courtregister.domain.ReferenceDataUnavailableException}, which is
 * transient, so the run comes back rather than completing addressed to nobody.
 */
// PMD.UnusedPrivateField: the six collaborators are the seam T060's suite constructs the client
// with, and they are read by the body T066 writes. The suppression comes off with that body.
@SuppressWarnings("PMD.UnusedPrivateField")
public class ReferenceDataNowSubscriptionsClient implements NowSubscriptionsSource {

    /** The resource's path under the reference-data context. */
    public static final String PATH =
            "/referencedata-query-api/query/api/rest/referencedata/now-subscriptions";

    /** The query parameter carrying the day the subscription set is read as at. */
    public static final String ON = "on";

    /** The vendor media type the now-subscriptions resource is served as. */
    public static final String ACCEPT =
            "application/vnd.referencedata.query.get-now-subscriptions+json";

    /** The header carrying the user identity reference data authorises against. */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final Duration retryInterval;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient    the client, carrying the reference-data base URL and its timeouts
     * @param systemUserId  the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                      logged
     * @param extraHeaders  any further headers the mesh requires, name to value
     * @param objectMapper  the shared mapper, so a response is read exactly as any other JSON is
     * @param maxAttempts   total attempts including the first
     * @param retryInterval the wait between retryable attempts
     */
    public ReferenceDataNowSubscriptionsClient(final RestClient restClient,
            final String systemUserId, final Map<String, String> extraHeaders,
            final ObjectMapper objectMapper, final int maxAttempts, final Duration retryInterval) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
    }

    @Override
    public JsonNode subscriptionsOn(final LocalDate registerDay, final CallerIdentity caller) {
        throw new UnsupportedOperationException("T066 reads the now-subscriptions resource");
    }
}
