package uk.gov.hmcts.cp.courtregister.adapter.progression;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;

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
 * <p><strong>Every one of those waits is bounded by the run's claim, not only by
 * {@code max-backoff}.</strong> The caller hands in the instant its run promised to have stopped by,
 * and it is read before every attempt and before every wait — the back-off's and progression's
 * {@code Retry-After} alike. A wait that would end after the deadline is refused rather than
 * shortened: the run is handed back TRANSIENT under
 * {@link uk.gov.hmcts.cp.courtregister.domain.ReasonCode#PROCESSING_DEADLINE_EXCEEDED} and the
 * redelivery gets a whole fresh budget. Without that check a policy sized in seconds can still spend
 * minutes — four attempts, each able to spend a connect and a read timeout, with a server-chosen
 * wait between them — and a POST made after the claim became reclaimable is a POST a second runner
 * may be making too, which for a command that <em>appends</em> is a second register for one hearing.
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

    private static final Logger LOG = LoggerFactory.getLogger(ProgressionCommandGateway.class);

    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /**
     * The only {@code Retry-After} form this client acts on: delta-seconds, at most ten digits.
     *
     * <p>RFC 9110 also permits an HTTP-date, and honouring one would mean measuring a remote clock
     * against this pod's — a server a few minutes ahead would park a run past the claim lease it
     * holds. The form is recognised before it is read, so an unusable value is classified rather
     * than parsed and caught, and every unusable form falls back to the back-off.
     */
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,10}");

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final SubmissionPause pause;
    private final Clock clock;

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
     * @param clock          elapsed-time source for the caller's deadline; local readings only, so
     *                       no decision here compares one pod's clock with another's
     */
    // Seven collaborators and settings, every one of them a separate operator decision. Grouping
    // them behind a holder would hide which setting a change touches.
    public ProgressionCommandGateway(final RestClient restClient, final String systemUserId,
            final Map<String, String> extraHeaders, final int maxAttempts,
            final Duration initialBackoff, final Duration maxBackoff, final SubmissionPause pause,
            final Clock clock) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.pause = pause;
        this.clock = clock;
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
     * @param body     the serialised document, sent byte for byte
     * @param caller   who the command is posted as
     * @param deadline the instant the run holding the claim promised to have stopped by; no attempt
     *                 is started and no wait is taken across it
     * @return the status progression answered with, which is {@code 202} or nothing
     * @throws uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException carrying
     *     {@code NON_TRANSIENT} when the command was refused or answered with a success the contract
     *     does not define, and {@code TRANSIENT} when the attempts ran out with the outcome still
     *     unresolved or the run's budget ran out first; the status progression answered travels with
     *     it where there was one
     */
    public int post(final byte[] body, final CallerIdentity caller, final Instant deadline) {
        // Resolved once per command, not once per attempt: a retry made as a different caller would
        // be a second, differently attributed command for the same hearing.
        final String identity = caller.orSystem(systemUserId);
        Duration backoff = initialBackoff;
        OptionalInt lastStatus = OptionalInt.empty();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (spent(deadline)) {
                throw overran(attempt, lastStatus);
            }
            final Outcome outcome = attempt(body, identity, attempt);
            if (outcome.status().isPresent()) {
                lastStatus = outcome.status();
            }
            if (outcome.accepted()) {
                return outcome.status().orElse(ACCEPTED);
            }
            if (outcome.refusal().isPresent()) {
                throw failed(
                        FailureClassification.NON_TRANSIENT, outcome.refusal().get(), lastStatus);
            }
            if (attempt < maxAttempts) {
                final Duration wait = capped(outcome.retryAfter().orElse(backoff));
                if (!fitsInside(wait, deadline)) {
                    throw overran(attempt, lastStatus);
                }
                waitFor(wait);
                backoff = capped(backoff.multipliedBy(2));
            }
        }

        // The transport's half of N42: an unresolved verdict handed back, never a register written
        // off. The FAILED row and the exhausted_message_id are the guard's, on the last permitted
        // delivery.
        LOG.error("The add-court-register attempts are exhausted with the outcome unresolved, so "
                + "the delivery is handed back. attempts={}", maxAttempts);
        throw failed(FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT, lastStatus);
    }

    /**
     * One attempt, classified.
     *
     * <p>The configured mesh headers are applied first and the two contract headers set over them, so
     * a header configured under a contract name replaces the contract value rather than joining it:
     * two values of {@code CJSCPPUID} is an ambiguous caller to a service authorising on identity,
     * and two content types is a 415.
     */
    private Outcome attempt(final byte[] body, final String identity, final int attempt) {
        Outcome outcome;
        try {
            outcome = restClient.post()
                    .uri(PATH)
                    .headers(headers -> {
                        extraHeaders.forEach(headers::set);
                        headers.setContentType(
                                MediaType.parseMediaType(ADD_COURT_REGISTER_MEDIA_TYPE));
                        headers.set(IDENTITY_HEADER, identity);
                    })
                    .body(body)
                    .exchange((request, response) -> classify(response, attempt));
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the command may or may not have
            // been applied. Unknown is not failed, and it is retried rather than written off.
            //
            // The exception travels with the line rather than only its type. What the pipeline acts
            // on is the classification; what a human acts on is what it *was* — a refused
            // connection, a read that timed out, a route the mesh dropped — and the bounded reason
            // code this is eventually reported under is deliberately incapable of carrying it. It is
            // safe to keep: a transport exception is raised instead of a response, so it carries the
            // endpoint and the socket error and never a register body.
            LOG.warn("The add-court-register attempt did not reach a verdict, so the outcome is "
                    + "unknown; retrying. attempt={}", attempt, unreachable);
            outcome = Outcome.retryable(Optional.empty(), OptionalInt.empty());
        }
        return outcome;
    }

    /**
     * What progression's answer means.
     *
     * <p>Nothing progression wrote is read: the body of a court-register refusal can name a child,
     * and this method's decisions all travel into a log line and a dead-letter description.
     */
    private Outcome classify(final ClientHttpResponse response, final int attempt)
            throws IOException {
        final HttpStatusCode status = response.getStatusCode();
        final int code = status.value();
        final Outcome outcome;

        if (code == ACCEPTED) {
            outcome = Outcome.accepted(code);
        } else if (status.is2xxSuccessful()) {
            // The contract declares one success. A 200 or a 204 means something other than the
            // command endpoint answered — a proxy, or a route that no longer reaches it — and
            // calling it success would complete the run `submitted` for a command nothing enqueued.
            LOG.error("Progression answered a success this contract does not define, so the command "
                    + "cannot be assumed enqueued. status={}", code);
            outcome = Outcome.refused(ReasonCode.SUBMISSION_NOT_ACCEPTED, code);
        } else if (code == HttpStatus.TOO_MANY_REQUESTS.value()) {
            LOG.warn("Progression asked this service to slow down. attempt={}", attempt);
            outcome = Outcome.retryable(retryAfter(response), OptionalInt.of(code));
        } else if (code == HttpStatus.REQUEST_TIMEOUT.value() || status.is5xxServerError()) {
            // The two the legacy never retries and every server error. AxiosRetryWrapper.js:34
            // abandons on any status at or below 429, which makes 408 and 429 — the two statuses
            // that most plainly mean "ask me again" — the least-retried failures it has (C3).
            LOG.warn("Progression could not process the command. attempt={} status={}",
                    attempt, code);
            outcome = Outcome.retryable(Optional.empty(), OptionalInt.of(code));
        } else {
            // Any other 4xx: the request was understood and declined, so the same bytes will be
            // declined again and the delivery budget is finite.
            LOG.error("Progression refused the command, and no redelivery can change that. "
                    + "status={}", code);
            outcome = Outcome.refused(ReasonCode.SUBMISSION_REJECTED, code);
        }
        return outcome;
    }

    /**
     * The wait progression asked for, where it asked for one this client can act on.
     *
     * @return the delta-seconds wait, or empty for every other form — which falls back to the
     *     back-off, the same outcome as no header at all
     */
    private static Optional<Duration> retryAfter(final ClientHttpResponse response) {
        final String header = response.getHeaders().getFirst(RETRY_AFTER_HEADER);
        final String asked = header == null ? "" : header.trim();
        final Optional<Duration> wait;
        if (DELTA_SECONDS.matcher(asked).matches()) {
            wait = Optional.of(Duration.ofSeconds(Long.parseLong(asked)));
        } else {
            if (!asked.isEmpty()) {
                LOG.warn("Retry-After was not a number of seconds, so the back-off is used instead.");
            }
            wait = Optional.empty();
        }
        return wait;
    }

    /** The ceiling on any wait, a server-supplied one included: a run holds a bounded claim. */
    private Duration capped(final Duration wait) {
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    /**
     * Whether the run holding this claim has used the time the claim guarantees it.
     *
     * <p>Read the same way the pipeline reads its own budget: strictly before, so a run standing
     * exactly on the deadline has already spent it and may not start another attempt.
     *
     * @param deadline the instant the run promised to have stopped by
     * @return whether the budget is gone
     */
    private boolean spent(final Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    /**
     * Whether a wait can be taken and still leave the run inside its claim.
     *
     * <p>The check that stops a retry policy outliving the claim it was made under. Waiting is the
     * longest thing this class does — a doubling back-off, or whatever number progression put in a
     * {@code Retry-After} — and a wait begun with less budget than it needs ends with the run past
     * its deadline and the claim behind it reclaimable, which for {@code add-court-register} is a
     * second register for the hearing. So a wait that would not finish inside the budget is not
     * shortened, it is refused: a truncated wait would hammer a service that has just asked for
     * room, and the delivery is worth more handed back with a fresh budget than spent here.
     *
     * @param wait     the wait that would be taken
     * @param deadline the instant the run promised to have stopped by
     * @return whether the wait ends before the deadline
     */
    private boolean fitsInside(final Duration wait, final Instant deadline) {
        return clock.instant().plus(wait).isBefore(deadline);
    }

    /**
     * The run's budget ran out before its attempts did.
     *
     * <p>TRANSIENT, and under its own reason: the command did not fail, the run ran out of the time
     * its claim guarantees it, and the redelivery gets a whole fresh budget with nothing sent twice.
     * It is deliberately not {@code SUBMISSION_TRANSIENT} — a rise in this code is a capacity signal
     * about this service, where a rise in that one is a signal about progression.
     *
     * @param attempt    the attempt the budget ran out on
     * @param lastStatus the last status progression answered, where anything answered
     * @return the failure to raise
     */
    private static SubmissionFailedException overran(
            final int attempt, final OptionalInt lastStatus) {
        LOG.error("The run's budget ran out before the add-court-register command was resolved, so "
                        + "the delivery is handed back rather than posted under a claim that may "
                        + "have been reclaimed. attempt={} reason={}",
                attempt, ReasonCode.PROCESSING_DEADLINE_EXCEEDED.code());
        return failed(FailureClassification.TRANSIENT,
                ReasonCode.PROCESSING_DEADLINE_EXCEEDED, lastStatus);
    }

    private void waitFor(final Duration wait) {
        try {
            pause.pause(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting to retry the add-court-register command, so the "
                    + "delivery is handed back unresolved.");
            throw new SubmissionFailedException(
                    FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT);
        }
    }

    /**
     * The failure, carrying the status progression answered where there was one.
     *
     * <p>An empty status is a real answer: a connect failure has none, and a row carrying an invented
     * one would say an attempt was answered when nothing answered. A status is also all that may
     * cross this boundary — it is bounded, and says nothing about a child.
     */
    private static SubmissionFailedException failed(final FailureClassification classification,
            final ReasonCode reason, final OptionalInt status) {
        return new SubmissionFailedException(classification, reason,
                status.isPresent() ? status.getAsInt() : null);
    }

    /**
     * What one attempt came to.
     *
     * @param accepted   whether progression accepted the command
     * @param refusal    the bounded reason where the answer is final, empty where another attempt
     *                   may change it
     * @param retryAfter the wait progression asked for, where it asked for a usable one
     * @param status     the status progression answered with, where anything answered
     */
    private record Outcome(boolean accepted, Optional<ReasonCode> refusal,
            Optional<Duration> retryAfter, OptionalInt status) {

        private static Outcome accepted(final int status) {
            return new Outcome(true, Optional.empty(), Optional.empty(), OptionalInt.of(status));
        }

        private static Outcome refused(final ReasonCode reason, final int status) {
            return new Outcome(false, Optional.of(reason), Optional.empty(), OptionalInt.of(status));
        }

        private static Outcome retryable(
                final Optional<Duration> retryAfter, final OptionalInt status) {
            return new Outcome(false, Optional.empty(), retryAfter, status);
        }
    }
}
