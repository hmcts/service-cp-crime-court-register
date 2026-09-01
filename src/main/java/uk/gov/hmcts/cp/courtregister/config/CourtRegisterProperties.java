package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every setting this service owns, bound once and typed.
 *
 * <p>Defaults live here rather than only in {@code application.yaml}, so the values are visible to
 * the code that depends on them and a missing configuration file cannot silently change behaviour.
 * The credential and endpoint settings are the deliberate exception: none of them has a default,
 * because a service that invents a broker address, a command API or an identity is a service that
 * can talk to the wrong one.
 *
 * @param consumer      whether intake runs at all
 * @param servicebus    broker connection and consumer settings
 * @param claim         the single-runner claim's timings
 * @param store         processed-log store probing
 * @param stub          test-only control over the stub adapters
 * @param payload       where the hearing payload is read from
 * @param results       the results context's query API, the payload fallback only
 * @param referencedata the reference-data context the register's recipients are looked up in
 * @param progression   the progression context the court register is POSTed to
 * @param submission    what is checked before a register is sent
 */
@ConfigurationProperties(prefix = "courtregister")
// PMD.AvoidDuplicateLiterals: the repeated literal is a `@DefaultValue`, which must be a compile-
// time constant on the annotation itself. Four unrelated ten-second defaults happen to agree today;
// naming one constant for them would tie four independent settings together, which is the opposite
// of what a per-setting default is for.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public record CourtRegisterProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue Servicebus servicebus,
        @DefaultValue Claim claim,
        @DefaultValue Store store,
        @DefaultValue Stub stub,
        @DefaultValue Payload payload,
        @DefaultValue Results results,
        @DefaultValue Referencedata referencedata,
        @DefaultValue Progression progression,
        @DefaultValue Submission submission) {

    /**
     * Master switch for the Service Bus consumer.
     *
     * @param enabled master switch for starting the processor at all; false in the test profile
     */
    public record Consumer(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Connection, settlement and health settings for the inbound queue.
     *
     * @param connectionString         local and CI only, emulator connection string
     * @param namespace                deployed only, fully qualified namespace for workload identity
     * @param queueName                the inbound queue
     * @param maxConcurrentCalls       processor concurrency
     * @param maxDeliveryCount         mirrors the broker queue setting; recognises the final delivery
     * @param maxAutoLockRenewDuration must outlive any legitimate run
     * @param healthStaleness          age past which an unresolved error with no traffic stops being
     *                                 reported as an outage
     */
    public record Servicebus(
            String connectionString,
            String namespace,
            @DefaultValue("courtregister.requests") String queueName,
            @DefaultValue("2") int maxConcurrentCalls,
            @DefaultValue("5") int maxDeliveryCount,
            @DefaultValue("5m") Duration maxAutoLockRenewDuration,
            @DefaultValue("60s") Duration healthStaleness) {
    }

    /**
     * Claim timing: how long a claim lives and how long a run may take inside it.
     *
     * @param lease              claim expiry, written as {@code now() + lease}
     * @param processingDeadline enforced run bound, strictly shorter than the lease
     */
    public record Claim(
            @DefaultValue("5m") Duration lease,
            @DefaultValue("4m") Duration processingDeadline) {
    }

    /**
     * Processed-log availability probing.
     *
     * @param probeInterval store-health probe interval, driving start and resume
     */
    public record Store(@DefaultValue("10s") Duration probeInterval) {
    }

    /**
     * Stub adapter behaviour, for the test and local profiles only.
     *
     * @param payloadFailureMode the simulated payload failure; test and local profiles only
     */
    public record Stub(@DefaultValue("NONE") PayloadFailureMode payloadFailureMode) {
    }

    /**
     * Where the hearing payload comes from, and how each source is reached.
     *
     * @param mode     the adapter serving the payload port
     * @param redis    the payload cache
     * @param fallback the query-side read used when the cache has nothing
     */
    public record Payload(
            @DefaultValue("LIVE") PayloadSourceMode mode,
            @DefaultValue Redis redis,
            @DefaultValue Fallback fallback) {
    }

    /**
     * The hearing payload cache.
     *
     * <p>The address is a LOCAL development default; deployed environments override it, and the key
     * is mounted from Key Vault. TLS is off by default because that is what a developer's local
     * server speaks, and on in every deployed environment — with certificates verified, which the
     * function app disables and fix C15 restores.
     *
     * @param host           cache host
     * @param port           cache port
     * @param password       cache access key; a secret, and therefore without a default
     * @param ssl            whether to connect over TLS, with certificates verified
     * @param keyPrefix      the payload prefix the producer writes under; {@code INT_} for this flow
     * @param connectTimeout how long to wait for a connection
     * @param commandTimeout how long to wait for a command to answer
     */
    public record Redis(
            @DefaultValue("localhost") String host,
            @DefaultValue("6379") int port,
            String password,
            @DefaultValue("false") boolean ssl,
            @DefaultValue("INT_") String keyPrefix,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("5s") Duration commandTimeout) {
    }

    /**
     * The query-side payload read.
     *
     * <p>The attempt count and interval are the function app's {@code DEFAULT_PUBLISH_RETRY_COUNT}
     * and {@code DEFAULT_PUBLISH_RETRY_INTERVAL} defaults; the retry <em>taxonomy</em> is the
     * corrected one (fix C3), which is a property of the client rather than of these numbers.
     *
     * @param maxAttempts    total attempts including the first
     * @param retryInterval  the wait between attempts
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response
     */
    public record Fallback(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration retryInterval,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {
    }

    /**
     * The results context's query API: the payload fallback, and nothing else.
     *
     * <p>Unlike the informant service, this context is <em>not</em> where the register is sent — the
     * court register is POSTed to progression ({@link Progression}). What remains here is the
     * {@code hearingDetails/internal} query the cache misses to, so the block carries an endpoint
     * and an identity and no retry policy: the fetch's retry settings belong to the fetch, in
     * {@link Fallback}.
     *
     * @param baseUrl      scheme, host and port of the results context, no path
     * @param systemUserId the {@code CJSCPPUID} identity; a secret, never logged
     */
    public record Results(String baseUrl, String systemUserId) {
    }

    /**
     * The reference-data context: the query API the register's recipients are looked up in.
     *
     * <p>Its own block rather than a member of {@link Results}, because it is a different deployment
     * behind a different internal mesh host. {@code baseUrl} and {@code systemUserId} carry no
     * default for the reason given on the record above; the local development value in
     * {@code application.yaml} is the reference-data query API's own declared {@code baseUri}, and
     * the identity there falls back to this service's own — the function app threads a single
     * {@code cjscppuid} through both calls ({@code ReferenceDataService.js:44}), so an environment
     * that mounts one identity is not asked for a second.
     *
     * <p>{@code headers} exists because {@code CJSCPPUID} is documented and the authorisation scheme
     * is not: reference data's access-control rules require the caller to be in a named user group,
     * so whatever the mesh turns out to need can be supplied without a code change and nothing is
     * invented in the meantime.
     *
     * @param mode           the adapter serving the subscriptions port
     * @param baseUrl        scheme, host and port of the reference-data context, no path
     * @param systemUserId   the {@code CJSCPPUID} identity; a secret, never logged
     * @param headers        any further headers the mesh requires, name to value
     * @param maxAttempts    total attempts including the first
     * @param retryInterval  the wait between attempts
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response once connected
     */
    public record Referencedata(
            @DefaultValue("LIVE") SubscriptionsSourceMode mode,
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration retryInterval,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        /** Freezes the header map, and treats an unconfigured one as none rather than as absent. */
        public Referencedata {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * The progression context: the command API the court register is POSTed to.
     *
     * <p>One POST per hearing, of {@code progression.add-court-register}, and success is {@code 202}
     * and nothing else. The retry numbers are this service's own: the contract documents that
     * back-off happens, not how much of it, so they are configuration rather than constants. What
     * they are a policy <em>for</em> is fix C1 and fix C3 — connect and read failures, 5xx, 429 and
     * 408 are retried, any other 4xx is a refusal that no redelivery will change, and a
     * {@code Retry-After} is honoured in delta-seconds only and bounded by {@code maxBackoff} so a
     * hostile or mistaken header cannot park a run past its claim.
     *
     * @param baseUrl        scheme, host and port of the progression context, no path
     * @param systemUserId   the {@code CJSCPPUID} identity for the POST; a secret, never logged
     * @param headers        any further headers the mesh requires, name to value
     * @param maxAttempts    total POST attempts, the first included
     * @param initialBackoff the first wait between retryable attempts; doubled each time
     * @param maxBackoff     the ceiling on any wait, a server-supplied {@code Retry-After} included
     * @param connectTimeout how long to wait for the connection
     * @param readTimeout    how long to wait for the response once connected
     */
    public record Progression(
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("500ms") Duration initialBackoff,
            @DefaultValue("20s") Duration maxBackoff,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        /** Freezes the header map, and treats an unconfigured one as none rather than as absent. */
        public Progression {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * What is checked before a register leaves this service.
     *
     * @param validateOutbound whether every document is validated against the vendored progression
     *                         schemas before the POST (fix C29); never false where the service is
     *                         deployed
     */
    public record Submission(@DefaultValue("true") boolean validateOutbound) {
    }
}
