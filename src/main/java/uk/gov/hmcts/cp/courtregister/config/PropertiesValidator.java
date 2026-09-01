package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>Everything checked here fails quietly in production and loudly at startup, so startup is where
 * it is made to fail: a run that can outlive its claim, a broker lock that can expire mid-run, an
 * ambiguous credential source, a payload source that cannot fetch anything, a now-subscriptions
 * source that cannot reach reference data, a submission that has nowhere to post the register or
 * nobody to post it as, any of those three network steps whose own worst case outlasts the run it
 * happens inside, and the pre-send contract validation switched off where the service is deployed.
 *
 * <p>These are the rules a healthy-looking pod hides. A live source with no identity, a fallback
 * with no attempts, a cache with no address and a submission with no endpoint all produce a service
 * that consumes normally, settles nothing usefully, and dead-letters every hearing it is given —
 * while readiness, liveness and the queue's own metrics say the deployment succeeded. Silence of
 * exactly that kind is what this service was commissioned to end, so it is not permitted to start.
 */
@Component
// The properties record is registered here, explicitly, rather than left to a scan: without it the
// packaged application starts no context at all ("No qualifying bean of type
// CourtRegisterProperties"), which the container smoke finds and no JUnit suite does.
@EnableConfigurationProperties(CourtRegisterProperties.class)
public class PropertiesValidator implements InitializingBean {

    /**
     * The fixed margin between the longest legitimate run and the broker's lock renewal, so the lock
     * is never the thing that ends a run.
     */
    public static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

    /**
     * What the run needs on top of its three network steps: the guard's admission and outcome
     * writes, and the transformation between the two reads.
     *
     * <p>Fixed rather than configured, because none of it is an environment's choice. A budget that
     * leaves the rest of the run nothing is the shape that overruns in production and looks correct
     * in review.
     */
    public static final Duration RUN_OVERHEAD_MARGIN = Duration.ofSeconds(30);

    /**
     * How many cache reads one payload fetch makes, and therefore how many of them the run's time
     * budget has to cover.
     *
     * <p>Two: the dated key and the legacy undated twin, which the cached payload adapter reads in
     * turn before the query side is asked at all. A budget that counts only the query side licences
     * a fetch that overruns the deadline by everything the cache cost.
     */
    private static final int CACHE_READS_PER_FETCH = 2;

    private static final String LEASE = "courtregister.claim.lease";
    private static final String PROCESSING_DEADLINE = "courtregister.claim.processing-deadline";
    private static final String RENEW_DURATION =
            "courtregister.servicebus.max-auto-lock-renew-duration";
    private static final String CONNECTION_STRING = "courtregister.servicebus.connection-string";
    private static final String NAMESPACE = "courtregister.servicebus.namespace";
    private static final String PAYLOAD = "courtregister.payload";
    private static final String PAYLOAD_MODE = PAYLOAD + ".mode";
    private static final String SYSTEM_USER_ID = "courtregister.results.system-user-id";
    private static final String FALLBACK = PAYLOAD + ".fallback";
    private static final String FALLBACK_MAX_ATTEMPTS = FALLBACK + ".max-attempts";
    private static final String REDIS = PAYLOAD + ".redis";
    private static final String CONNECT_TIMEOUT_SUFFIX = ".connect-timeout";
    private static final String REFDATA = "courtregister.referencedata";
    private static final String SUBSCRIPTIONS_MODE = REFDATA + ".mode";
    private static final String REFDATA_BASE_URL = REFDATA + ".base-url";
    private static final String REFDATA_SYSTEM_USER_ID = REFDATA + ".system-user-id";
    private static final String REFDATA_MAX_ATTEMPTS = REFDATA + ".max-attempts";
    private static final String PROGRESSION = "courtregister.progression";
    private static final String PROGRESSION_BASE_URL = PROGRESSION + ".base-url";
    private static final String PROGRESSION_SYSTEM_USER_ID = PROGRESSION + ".system-user-id";
    private static final String PROGRESSION_MAX_ATTEMPTS = PROGRESSION + ".max-attempts";

    /** The three clients read the same two keys, so the two suffixes are named once. */
    private static final String INITIAL_BACKOFF_SUFFIX = ".initial-backoff";
    private static final String MAX_BACKOFF_SUFFIX = ".max-backoff";
    private static final String VALIDATE_OUTBOUND = "courtregister.submission.validate-outbound";

    /** Shared so the wording of a lower-bound refusal is one string and not five. */
    private static final String MUST_BE_AT_LEAST = ") must be at least ";

    /** Shared so the wording of the run-budget refusals is one string and not three. */
    private static final String INSIDE_ITS_OWN_CLAIM =
            "); a run must be able to stop itself while its claim is still its own";

    /** The first attempt is the POST itself, so a policy that permits fewer never sends one. */
    private static final int MINIMUM_ATTEMPTS = 1;

    private final CourtRegisterProperties properties;

    /**
     * Creates the validator over the bound properties.
     *
     * @param properties the bound settings
     */
    public PropertiesValidator(final CourtRegisterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * Checks the settings that must hold for the service to be safe to run.
     *
     * @param properties the bound settings
     * @throws IllegalStateException if any rule is broken
     */
    public static void validate(final CourtRegisterProperties properties) {
        validateRunFinishesBeforeTheClaimExpires(properties);
        validateLockOutlivesTheRun(properties);
        validateExactlyOneCredentialSource(properties);
        validateThePayloadSourceCanFetch(properties);
        validateTheSubscriptionsSourceCanFetch(properties);
        validateTheSubmissionCanPost(properties);
        validateEveryStepTogetherFinishesInsideTheRun(properties);
        validateTheOutboundValidatorIsOnWhereItIsDeployed(properties);
    }

    private static void validateRunFinishesBeforeTheClaimExpires(
            final CourtRegisterProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration lease = properties.claim().lease();
        if (deadline.compareTo(lease) >= 0) {
            throw new IllegalStateException(
                    PROCESSING_DEADLINE + " (" + deadline + ") must be strictly shorter than " + LEASE
                            + " (" + lease + "), so a slow run stops before its claim can be"
                            + " reclaimed");
        }
    }

    private static void validateLockOutlivesTheRun(final CourtRegisterProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration renewal = properties.servicebus().maxAutoLockRenewDuration();
        final Duration required = deadline.plus(RENEWAL_MARGIN);
        if (renewal.compareTo(required) < 0) {
            throw new IllegalStateException(
                    RENEW_DURATION + " (" + renewal + MUST_BE_AT_LEAST + PROCESSING_DEADLINE
                            + " plus the " + RENEWAL_MARGIN + " renewal margin (" + required
                            + "), so the broker lock outlives any legitimate run");
        }
    }

    private static void validateExactlyOneCredentialSource(
            final CourtRegisterProperties properties) {
        final boolean hasConnectionString = hasText(properties.servicebus().connectionString());
        final boolean hasNamespace = hasText(properties.servicebus().namespace());
        if (hasConnectionString == hasNamespace) {
            throw new IllegalStateException(
                    "Set exactly one of " + CONNECTION_STRING + " (local and CI) or " + NAMESPACE
                            + " (deployed) — currently "
                            + (hasConnectionString ? "both are set" : "neither is set"));
        }
    }

    /**
     * The payload source must be one that can actually produce a payload.
     *
     * <p>Three separate ways a deployment can look healthy and fetch nothing: the stub selected
     * where the service is deployed, a live source with no identity to authorise its fallback with,
     * and a cache or fallback configured out of existence.
     *
     * <p>Each rule is asked of the source actually selected. The cache and the query side belong to
     * the live adapter and {@code STUB} builds neither of them, so holding a stub run to settings
     * nothing will read would fail a local run configured exactly as it means to be.
     */
    private static void validateThePayloadSourceCanFetch(
            final CourtRegisterProperties properties) {
        final CourtRegisterProperties.Payload payload = properties.payload();
        if (payload.mode() == PayloadSourceMode.STUB) {
            validateTheStubIsNotDeployed(properties);
        } else {
            validateTheLiveSourceHasAnIdentity(properties);
            validateTheCacheIsAddressable(payload.redis());
            validateTheFallbackIsAttempted(payload.fallback());
            validateTheFetchFinishesInsideTheRun(properties);
        }
    }

    /**
     * Constitution Principle V: the stub must not be reachable in a production profile. A namespace
     * means workload identity, which means a deployed pod — the same discriminator the credential
     * rule above already draws deployment on.
     */
    private static void validateTheStubIsNotDeployed(final CourtRegisterProperties properties) {
        if (hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    PAYLOAD_MODE + " is STUB while " + NAMESPACE + " is set, which is a deployed"
                            + " environment — the stub fetches nothing, so every request would be"
                            + " settled having produced no register at all");
        }
    }

    /**
     * The query-side fallback authorises with the system user identity, and without one it cannot be
     * used: every cold-cache request would be abandoned, redelivered and dead-lettered by a pod
     * reporting itself healthy throughout.
     */
    private static void validateTheLiveSourceHasAnIdentity(
            final CourtRegisterProperties properties) {
        if (!hasText(properties.results().systemUserId())) {
            throw new IllegalStateException(
                    SYSTEM_USER_ID + " must be set when " + PAYLOAD_MODE + " is LIVE, because the"
                            + " payload fallback cannot be used without an identity to authorise"
                            + " with");
        }
    }

    private static void validateTheCacheIsAddressable(final CourtRegisterProperties.Redis redis) {
        if (!hasText(redis.host())) {
            throw new IllegalStateException(REDIS + ".host must name the payload cache");
        }
        if (!hasText(redis.keyPrefix())) {
            throw new IllegalStateException(
                    REDIS + ".key-prefix must be the prefix the producer writes the payload under,"
                            + " INT_ for this flow — an empty prefix reads a key nobody writes");
        }
        requirePositive(redis.connectTimeout(), REDIS + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(redis.commandTimeout(), REDIS + ".command-timeout");
    }

    private static void validateTheFallbackIsAttempted(
            final CourtRegisterProperties.Fallback fallback) {
        if (fallback.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    FALLBACK_MAX_ATTEMPTS + " (" + fallback.maxAttempts() + MUST_BE_AT_LEAST
                            + MINIMUM_ATTEMPTS + " — at zero every cache miss skips a query side that"
                            + " could have answered, and the request is retried to the dead-letter"
                            + " queue instead");
        }
        validateTheBackOffIsUsable(FALLBACK, fallback.initialBackoff(), fallback.maxBackoff());
        requirePositive(fallback.connectTimeout(), FALLBACK + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(fallback.readTimeout(), FALLBACK + ".read-timeout");
    }

    /**
     * The fetch happens inside the run, and the run must finish before its claim can be reclaimed.
     *
     * <p>So the whole fetch's worst case has to fit inside the processing deadline: the two cache
     * reads that come first, each able to spend its connect and command timeouts, and then every
     * fallback attempt spending its connect and read timeouts with a bounded wait between them. A
     * configuration where it does not guarantees exactly what the deadline exists to prevent: a
     * runner still waiting on a socket while another runner takes its request.
     *
     * <p>Strictly shorter, not merely no longer. The run measures the deadline before the fetch and
     * tests it after, so a fetch that fills the deadline exactly leaves the rest of the run nothing
     * and can only end at {@code PROCESSING_DEADLINE_EXCEEDED}.
     */
    private static void validateTheFetchFinishesInsideTheRun(
            final CourtRegisterProperties properties) {
        final CourtRegisterProperties.Redis redis = properties.payload().redis();
        final CourtRegisterProperties.Fallback fallback = properties.payload().fallback();
        final Duration deadline = properties.claim().processingDeadline();
        final Duration cacheReads = cacheReadsWorstCase(redis);
        final Duration queryReads = queryReadsWorstCase(fallback);
        final Duration worstCase = cacheReads.plus(queryReads);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The payload settings allow a fetch of up to " + worstCase + " — " + REDIS
                            + " reads of " + cacheReads + " ahead of " + FALLBACK + " attempts of "
                            + queryReads + " — which is not strictly shorter than "
                            + PROCESSING_DEADLINE + " (" + deadline + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /** The two cache reads a fetch makes, each spending both of its timeouts. */
    private static Duration cacheReadsWorstCase(final CourtRegisterProperties.Redis redis) {
        return redis.connectTimeout()
                .plus(redis.commandTimeout())
                .multipliedBy(CACHE_READS_PER_FETCH);
    }

    /** Every query-side attempt spending both timeouts, with the waits between them. */
    private static Duration queryReadsWorstCase(
            final CourtRegisterProperties.Fallback fallback) {
        return attemptsWorstCase(fallback.connectTimeout(), fallback.readTimeout(),
                fallback.maxAttempts())
                .plus(backOffWorstCase(fallback.maxBackoff(), fallback.maxAttempts()));
    }

    /** The whole payload fetch, or nothing at all where the stub is the selected source. */
    private static Duration payloadFetchWorstCase(final CourtRegisterProperties properties) {
        return properties.payload().mode() == PayloadSourceMode.STUB
                ? Duration.ZERO
                : cacheReadsWorstCase(properties.payload().redis())
                        .plus(queryReadsWorstCase(properties.payload().fallback()));
    }

    /**
     * The now-subscriptions source must be one that can actually reach reference data.
     *
     * <p>The same class of hole the payload rules close, on the port that decides who a register is
     * addressed to. A live source with no endpoint or no identity is a pod that abandons, redelivers
     * and finally parks every hearing that produced a register, while readiness, liveness and the
     * queue's own metrics all say the deployment succeeded — and unlike the payload case, nothing
     * downstream ever gets far enough to notice.
     */
    private static void validateTheSubscriptionsSourceCanFetch(
            final CourtRegisterProperties properties) {
        final CourtRegisterProperties.Referencedata referencedata = properties.referencedata();
        if (referencedata.mode() == SubscriptionsSourceMode.STUB) {
            validateTheEmptyAnswerIsNotDeployed(properties);
            validateTheEmptyAnswerIsNotGivenAboutARealHearing(properties);
        } else {
            validateTheLiveSourceCanAskReferenceData(referencedata);
            validateTheSubscriptionsReadIsAttempted(referencedata);
            validateTheSubscriptionsReadFinishesInsideTheRun(properties);
        }
    }

    /**
     * Constitution Principle V, the same rule {@link #validateTheStubIsNotDeployed} applies to the
     * payload stub: a deployed pod running this one asks reference data nothing, so every hearing
     * it reads completes {@code no-subscriptions} and no register is ever addressed.
     */
    private static void validateTheEmptyAnswerIsNotDeployed(
            final CourtRegisterProperties properties) {
        if (hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    SUBSCRIPTIONS_MODE + " is STUB while " + NAMESPACE + " is set, which is a"
                            + " deployed environment — the stub asks reference data nothing, so every"
                            + " hearing that produced a register would complete addressed to nobody");
        }
    }

    /**
     * The stub answers "nobody is subscribed", which is a legitimate business outcome, so it must
     * never be given about a hearing anybody could mistake for a real one.
     *
     * <p>This is the pairing that would be indistinguishable from working: a live payload source
     * fetching real hearings, and a subscriptions source that says nobody wants them. Every run
     * would complete {@code no-subscriptions} — the flow's commonest legitimate outcome — and the
     * metrics, the processed log and the queue would all agree that the service was doing its job.
     * The stub cannot make itself safe here by refusing instead: the read happens before the
     * transformation, so a refusal would only trade a silent completion for a queue that never
     * drains. It is the configuration that has to be refused, and it is refused at startup.
     */
    private static void validateTheEmptyAnswerIsNotGivenAboutARealHearing(
            final CourtRegisterProperties properties) {
        if (properties.payload().mode() == PayloadSourceMode.LIVE) {
            throw new IllegalStateException(
                    SUBSCRIPTIONS_MODE + " is STUB while " + PAYLOAD_MODE + " is LIVE — real"
                            + " hearings would be fetched and every one of them completed"
                            + " no-subscriptions, because reference data was never asked");
        }
    }

    /**
     * A query needs somewhere to go and somebody to be from.
     *
     * <p>{@code CJSCPPUID} is part of the reference-data query's own contract and its access-control
     * rules authorise on it, so an anonymous query is a refused query — every time, for ever.
     */
    private static void validateTheLiveSourceCanAskReferenceData(
            final CourtRegisterProperties.Referencedata referencedata) {
        if (!hasText(referencedata.baseUrl())) {
            throw new IllegalStateException(
                    REFDATA_BASE_URL + " must name the reference-data context when "
                            + SUBSCRIPTIONS_MODE + " is LIVE, because the now-subscriptions query has"
                            + " nowhere to go without it");
        }
        if (!hasText(referencedata.systemUserId())) {
            throw new IllegalStateException(
                    REFDATA_SYSTEM_USER_ID + " must be set when " + SUBSCRIPTIONS_MODE + " is LIVE,"
                            + " because reference data authorises the now-subscriptions query on"
                            + " CJSCPPUID and refuses an anonymous one");
        }
    }

    private static void validateTheSubscriptionsReadIsAttempted(
            final CourtRegisterProperties.Referencedata referencedata) {
        if (referencedata.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    REFDATA_MAX_ATTEMPTS + " (" + referencedata.maxAttempts() + MUST_BE_AT_LEAST
                            + MINIMUM_ATTEMPTS + " — at zero the query is never made and every"
                            + " hearing that produced a register is parked having asked nobody");
        }
        validateTheBackOffIsUsable(
                REFDATA, referencedata.initialBackoff(), referencedata.maxBackoff());
        requirePositive(referencedata.connectTimeout(), REFDATA + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(referencedata.readTimeout(), REFDATA + ".read-timeout");
    }

    /**
     * The now-subscriptions read happens inside the run, so its worst case has to fit inside it.
     *
     * <p>Every attempt can spend its connect and its read timeout, with a wait bounded by
     * {@code max-backoff} between them, and nothing else bounds the total. Ten attempts against a
     * minute-long read is a startup that succeeds and a run that is still waiting on a socket ten
     * minutes later — long after its claim became reclaimable and another delivery began processing
     * the same request.
     *
     * <p>The bound here is per-step: it refuses a reference-data read that cannot finish inside a
     * run at all. Whether this step and the two around it fit inside one run <em>together</em> is a
     * different question, and it is asked by
     * {@link #validateEveryStepTogetherFinishesInsideTheRun}.
     */
    private static void validateTheSubscriptionsReadFinishesInsideTheRun(
            final CourtRegisterProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration worstCase = subscriptionsReadWorstCase(properties);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The " + REFDATA + " settings allow a now-subscriptions read of up to "
                            + worstCase + ", which is not strictly shorter than "
                            + PROCESSING_DEADLINE + " (" + deadline + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /** The whole now-subscriptions read, or nothing where the stub is selected. */
    private static Duration subscriptionsReadWorstCase(
            final CourtRegisterProperties properties) {
        final CourtRegisterProperties.Referencedata referencedata = properties.referencedata();
        return referencedata.mode() == SubscriptionsSourceMode.STUB
                ? Duration.ZERO
                : attemptsWorstCase(referencedata.connectTimeout(), referencedata.readTimeout(),
                        referencedata.maxAttempts())
                        .plus(backOffWorstCase(referencedata.maxBackoff(),
                                referencedata.maxAttempts()));
    }

    /**
     * The submission must be able to make the call this whole service exists to make.
     *
     * <p>The endpoint and the identity are asked of a live pipeline, which is every deployed one:
     * {@link #validateTheStubIsNotDeployed} already refuses a stub payload source wherever a
     * namespace is set, so no deployment reaches the POST through the exemption a local stub run
     * relies on — and a local stub run never fetches a hearing, so it never reaches the POST at all.
     *
     * <p>{@code max-attempts} below one is the setting that matters most: the loop that POSTs the
     * register never runs, every hearing is handed back as an unresolved transient failure, and the
     * queue fills with deliveries that were never attempted — silent non-delivery wearing a retry
     * policy's clothes, and unobservable except as a queue that will not drain.
     */
    private static void validateTheSubmissionCanPost(final CourtRegisterProperties properties) {
        if (properties.payload().mode() == PayloadSourceMode.LIVE) {
            validateTheSubmissionHasSomewhereToPost(properties.progression());
        }
        validateTheRetryPolicyCanPost(properties.progression());
        validateTheSubmissionFinishesInsideTheRun(properties);
    }

    private static void validateTheSubmissionHasSomewhereToPost(
            final CourtRegisterProperties.Progression progression) {
        if (!hasText(progression.baseUrl())) {
            throw new IllegalStateException(
                    PROGRESSION_BASE_URL + " must name the progression context when " + PAYLOAD_MODE
                            + " is LIVE, because the add-court-register command has nowhere to go"
                            + " without it");
        }
        if (!hasText(progression.systemUserId())) {
            throw new IllegalStateException(
                    PROGRESSION_SYSTEM_USER_ID + " must be set when " + PAYLOAD_MODE + " is LIVE,"
                            + " because progression authorises the add-court-register command on"
                            + " CJSCPPUID and refuses an anonymous one — every register, one 403 at"
                            + " a time");
        }
    }

    private static void validateTheRetryPolicyCanPost(
            final CourtRegisterProperties.Progression progression) {
        if (progression.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    PROGRESSION_MAX_ATTEMPTS + " (" + progression.maxAttempts() + MUST_BE_AT_LEAST
                            + MINIMUM_ATTEMPTS + ": a policy with no attempts posts no register at"
                            + " all and hands every hearing back unsent");
        }
        validateTheBackOffIsUsable(
                PROGRESSION, progression.initialBackoff(), progression.maxBackoff());
        requirePositive(progression.connectTimeout(), PROGRESSION + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(progression.readTimeout(), PROGRESSION + ".read-timeout");
    }

    /**
     * The POST happens inside the run the payload fetch and the reference-data read happen in, so
     * its worst case is held to the same bound theirs are.
     *
     * <p>Every attempt can spend its connect and read timeouts, and between them sits a wait the
     * policy bounds at {@code max-backoff} — which is where a retry policy that looks modest stops
     * being one. A run still waiting on the last of them when its claim becomes reclaimable is a
     * second runner processing the same hearing, and this flow POSTs a document that progression
     * appends rather than replaces.
     */
    private static void validateTheSubmissionFinishesInsideTheRun(
            final CourtRegisterProperties properties) {
        final CourtRegisterProperties.Progression progression = properties.progression();
        final Duration deadline = properties.claim().processingDeadline();
        final Duration attempts = attemptsWorstCase(progression.connectTimeout(),
                progression.readTimeout(), progression.maxAttempts());
        final Duration waits =
                backOffWorstCase(progression.maxBackoff(), progression.maxAttempts());
        final Duration worstCase = submissionWorstCase(properties);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The " + PROGRESSION + " settings allow a submission of up to " + worstCase
                            + " — attempts of " + attempts + " and back-off waits of " + waits
                            + " — which is not strictly shorter than " + PROCESSING_DEADLINE + " ("
                            + deadline + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /** Every POST attempt spending both timeouts, with the back-off waits between them. */
    private static Duration submissionWorstCase(final CourtRegisterProperties properties) {
        final CourtRegisterProperties.Progression progression = properties.progression();
        return attemptsWorstCase(progression.connectTimeout(), progression.readTimeout(),
                progression.maxAttempts())
                .plus(backOffWorstCase(progression.maxBackoff(), progression.maxAttempts()));
    }

    /**
     * One run, one budget: the three network steps and the rest of the run, against the deadline.
     *
     * <p>Every rule above asks whether <em>one</em> step can outlast the deadline, and three
     * separate "no"s do not answer the question that matters. The steps are spent inside one run
     * holding one claim, so what has to fit inside the processing deadline is their sum plus the
     * margin the guard's writes and the transformation need. A configuration where it does not is
     * a runner still waiting on a socket while its claim is reclaimed and a second delivery starts
     * the same request — and for this flow that is not a wasted retry: progression's
     * {@code add-court-register} appends a register per POST, so the second runner's send is a
     * second register for the hearing.
     *
     * <p>A step no adapter makes costs the run nothing, which is why the two stubbed sources
     * contribute zero — the same reading the per-step rules take.
     */
    private static void validateEveryStepTogetherFinishesInsideTheRun(
            final CourtRegisterProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration payload = payloadFetchWorstCase(properties);
        final Duration subscriptions = subscriptionsReadWorstCase(properties);
        final Duration submission = submissionWorstCase(properties);
        final Duration worstCase =
                payload.plus(subscriptions).plus(submission).plus(RUN_OVERHEAD_MARGIN);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "One run spends every step in turn, and together they allow up to " + worstCase
                            + " — a " + PAYLOAD + " fetch of " + payload + ", a " + REFDATA
                            + " read of " + subscriptions + ", a " + PROGRESSION + " submission of "
                            + submission + " and the fixed " + RUN_OVERHEAD_MARGIN
                            + " the guard's writes and the transformation need — which is not"
                            + " strictly shorter than " + PROCESSING_DEADLINE + " (" + deadline
                            + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /**
     * What every attempt costs when each one spends both of its timeouts.
     */
    private static Duration attemptsWorstCase(final Duration connectTimeout,
                                              final Duration readTimeout,
                                              final int maxAttempts) {
        return connectTimeout.plus(readTimeout).multipliedBy(maxAttempts);
    }

    /**
     * The shared retry policy's two waits, held to what a wait has to be to be takeable.
     *
     * <p>One rule for all three clients, because there is one policy: {@code initial-backoff} and
     * {@code max-backoff} mean the same thing wherever they are read, and so does a configuration
     * that makes them unusable. A negative first wait throws from inside the retry rather than being
     * taken, and a ceiling below the first wait shortens the very wait it exists to bound.
     *
     * @param prefix         the settings prefix, so the message names the client that is misconfigured
     * @param initialBackoff the first wait between retryable attempts
     * @param maxBackoff     the ceiling on any wait
     */
    private static void validateTheBackOffIsUsable(final String prefix,
            final Duration initialBackoff, final Duration maxBackoff) {
        if (initialBackoff.isNegative()) {
            throw new IllegalStateException(
                    prefix + INITIAL_BACKOFF_SUFFIX + " (" + initialBackoff + ") must not be"
                            + " negative: a negative wait throws from inside the retry rather than"
                            + " being taken");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException(
                    prefix + MAX_BACKOFF_SUFFIX + " (" + maxBackoff + MUST_BE_AT_LEAST + prefix
                            + INITIAL_BACKOFF_SUFFIX + " (" + initialBackoff + "), or the ceiling"
                            + " shortens the very wait it exists to bound");
        }
    }

    /**
     * Every wait a retry policy can take, at its worst.
     *
     * <p><strong>{@code max-backoff} per wait, not the doubling schedule.</strong> The schedule is
     * what the client waits when nothing tells it otherwise, and it is not the bound: a
     * {@code Retry-After} is honoured on <em>every</em> retryable answer in all three clients, and
     * the only thing limiting what a remote service can ask for is {@code max-backoff}. So a service
     * answering {@code Retry-After: 3600} on every attempt costs a full ceiling per wait, and a
     * budget computed from the doubling would licence a run that cannot finish inside its claim —
     * which is exactly the shape this validation exists to refuse. The doubling only ever makes the
     * real cost smaller.
     *
     * @param maxBackoff  the ceiling on any wait
     * @param maxAttempts total attempts including the first
     * @return the worst case the waits between those attempts can cost
     */
    private static Duration backOffWorstCase(final Duration maxBackoff, final int maxAttempts) {
        return maxAttempts <= MINIMUM_ATTEMPTS
                ? Duration.ZERO
                : maxBackoff.multipliedBy(maxAttempts - 1L);
    }

    /**
     * The C29 pre-send validation is what turns a schema-invalid document into an explicit, recorded
     * failure instead of the 400 the legacy pipeline swallowed, losing the whole hearing's register.
     * A deployed pod with it switched off is back in that failure mode with none of the legacy's
     * excuses, so it does not start. Local and CI runs may switch it off: a fixture that must reach
     * the wire with a shape the schemas reject is how the behaviour on the other side gets proven.
     */
    private static void validateTheOutboundValidatorIsOnWhereItIsDeployed(
            final CourtRegisterProperties properties) {
        if (!properties.submission().validateOutbound()
                && hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    VALIDATE_OUTBOUND + " is false while " + NAMESPACE + " is set, which is a"
                            + " deployed environment — without the pre-send check an invalid"
                            + " document is a 400 nobody sees and a register nobody can find");
        }
    }

    private static void requirePositive(final Duration value, final String setting) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    setting + " (" + value + ") must be positive — a timeout that never expires is"
                            + " a run that never ends");
        }
    }

    /**
     * A blank value counts as unset: a deployed environment overrides the local connection string
     * with an empty value rather than deleting the key, and treating that as "set" would fail every
     * deployment as ambiguous.
     */
    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
