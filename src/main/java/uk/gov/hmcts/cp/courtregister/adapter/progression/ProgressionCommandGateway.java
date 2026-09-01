package uk.gov.hmcts.cp.courtregister.adapter.progression;

import java.time.Duration;
import java.util.Map;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;

/**
 * The one HTTP call this service makes outwards: {@code add-court-register}, once per hearing.
 *
 * <p>The contract is progression-owned and frozen. The path, the vendor media type and the identity
 * header are constants here because they are constants there
 * ({@code ProcessOutboundCourtRegister/index.js:17-25}), and the body is passed through as the bytes
 * the caller produced — nothing in this class inspects, reshapes or adds to it. A gateway that
 * touched the body would be a second place the outbound contract was defined, and the first one
 * ({@code adapter/progression/OutboundContractValidator}) has already refused anything progression
 * would.
 *
 * <p><strong>Success is {@code 202 Accepted} and nothing else.</strong> The contract declares one
 * success status, so any other 2xx is a failure rather than a lenient success: a 200 from a proxy or
 * a re-pointed route would otherwise mark the hearing POSTED for a command that was never enqueued,
 * and the register would be gone with the log saying it had been sent. It is not retried either —
 * the body may already have been applied, and {@code add-court-register} <em>appends</em> — so it is
 * reported non-transient under {@link uk.gov.hmcts.cp.courtregister.domain.ReasonCode#SUBMISSION_NOT_ACCEPTED}
 * and parked where somebody can look at the endpoint.
 *
 * <p><strong>Defect fix C1.</strong> {@code index.js:17-25} makes this call with a bare
 * {@code axios.post}, catches whatever comes back, logs it and swallows it; the response status is
 * never inspected at all, so a refused register and a delivered one are the same run. Here every
 * outcome is classified, the status progression answered travels with the failure so it can be
 * written to {@code processed_output.response_code}, and nothing is absorbed.
 *
 * <p><strong>Defect fix C3.</strong> {@code CommonUtility/AxiosRetryWrapper.js:19,34} abandons the
 * moment a response arrives carrying a status at or below 429, which makes 429 and 408 — the two
 * statuses that most plainly mean "ask me again" — the least-retried failures there are, and its
 * one-second interval never grows. Here:
 *
 * <ul>
 *   <li>a connect or read failure, and a connection dropped mid-flight, are retried — the outcome is
 *       <em>unknown</em>, not failed;</li>
 *   <li>5xx, 429 and 408 are retried, with the wait doubling each time and bounded by
 *       {@code max-backoff};</li>
 *   <li>a {@code Retry-After} is honoured in delta-seconds only and capped by the same ceiling; an
 *       HTTP-date is classified before it is read, never parsed, because acting on one would measure
 *       a remote clock against this pod's and could park a run past the claim it holds;</li>
 *   <li>any other 4xx is a refusal, is never retried, and comes back non-transient: the same bytes
 *       will be refused again and the delivery budget is finite.</li>
 * </ul>
 *
 * <p>It is the same taxonomy {@code ResultsQueryHearingPayloadClient} and
 * {@code ReferenceDataNowSubscriptionsClient} apply, which is what makes the three agree about what
 * a redelivery can fix.
 *
 * <p><strong>An unknown outcome is retried, and that is not at-most-once.</strong> A POST that timed
 * out may have been applied. Retrying it can create a duplicate {@code court_register_request} row —
 * which progression's {@code max(register_time) per hearing_id} sweep absorbs for generation, like a
 * re-share — while not retrying it can lose the hearing, which nothing absorbs and nobody sees. The
 * trade is made deliberately in that direction and no code or comment here promises more than it.
 *
 * <p>Nothing progression says is ever carried out of this class. The failure reason is one of a
 * bounded set of codes, because it reaches a dead-letter description and the log index, and a
 * response body from a court-register command can name a child.
 *
 * <p>The endpoint and the identity are not validated here as the informant's gateway validates them:
 * {@code config/PropertiesValidator} already refuses a LIVE deployment with no
 * {@code courtregister.progression.base-url} or {@code system-user-id}, so the fault is a startup
 * fault in one place rather than in two.
 */
// PMD.UnusedPrivateField: the seven collaborators are the seam T061's suite constructs the gateway
// with, and they are read by the body T067 writes. The suppression comes off with that body.
@SuppressWarnings("PMD.UnusedPrivateField")
public class ProgressionCommandGateway {

    /** The command's path under the progression context, exactly as the command API declares it. */
    public static final String PATH =
            "/progression-command-api/command/api/rest/progression/court-register";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String ADD_COURT_REGISTER_MEDIA_TYPE =
            "application/vnd.progression.add-court-register+json";

    /**
     * The CPP identity header. Its value is never logged — it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    public static final int ACCEPTED = 202;

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final SubmissionPause pause;

    /**
     * Builds the gateway over an already-configured HTTP client.
     *
     * @param restClient     the client, carrying the progression base URL and its timeouts
     * @param systemUserId   the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                       logged
     * @param extraHeaders   any further headers the mesh requires, name to value
     * @param maxAttempts    total attempts including the first
     * @param initialBackoff the first wait between retryable attempts; doubled each time
     * @param maxBackoff     the ceiling on any wait, a server-supplied {@code Retry-After} included
     * @param pause          how a wait between attempts is taken
     */
    public ProgressionCommandGateway(final RestClient restClient, final String systemUserId,
            final Map<String, String> extraHeaders, final int maxAttempts,
            final Duration initialBackoff, final Duration maxBackoff, final SubmissionPause pause) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.pause = pause;
    }

    /**
     * Posts one {@code add-court-register} body, retrying only what a retry could fix.
     *
     * <p>The command is attributed to the run's caller — the user who shared the results where the
     * message named one, and the configured system identity otherwise. That is what the legacy does
     * ({@code ProcessOutboundCourtRegister/index.js:21} sends {@code this.input.cjscppuid}, which is
     * literally {@code undefined} in the one test that asserts it), and the identity is resolved once
     * so that every attempt of a run posts as the same caller.
     *
     * @param body   the serialised document, sent byte for byte
     * @param caller who the command is posted as
     * @return the status progression answered with, which is {@code 202} or nothing
     * @throws uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException carrying
     *     {@code NON_TRANSIENT} when the command was refused or answered with a success the contract
     *     does not define, and {@code TRANSIENT} when the attempts ran out with the outcome still
     *     unresolved; the status progression answered travels with it where there was one
     */
    public int post(final byte[] body, final CallerIdentity caller) {
        throw new UnsupportedOperationException("T067 posts the add-court-register command");
    }
}
