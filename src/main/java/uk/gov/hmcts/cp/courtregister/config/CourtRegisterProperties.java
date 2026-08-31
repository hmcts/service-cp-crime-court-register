package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every setting this service owns, bound once and typed.
 *
 * <p>The shape only at this point: the documented defaults and the startup rules that make a
 * configuration safe to run land with the implementation this signature is the seam for.
 *
 * @param consumer      whether intake runs at all
 * @param servicebus    broker connection and consumer settings
 * @param claim         the single-runner claim's timings
 * @param store         processed-log store probing
 * @param stub          test-only control over the stub adapters
 * @param payload       where the hearing payload is read from
 * @param results       the results context's query API, the payload fallback only
 * @param referencedata the reference-data context the register's recipients are looked up in
 * @param progression   the progression context the register is POSTed to
 * @param submission    what is checked before a register is sent
 */
@ConfigurationProperties(prefix = "courtregister")
public record CourtRegisterProperties(
        Consumer consumer,
        Servicebus servicebus,
        Claim claim,
        Store store,
        Stub stub,
        Payload payload,
        Results results,
        Referencedata referencedata,
        Progression progression,
        Submission submission) {

    /**
     * Master switch for the Service Bus consumer.
     *
     * @param enabled whether the processor is started at all
     */
    public record Consumer(boolean enabled) {
    }

    /**
     * Connection, settlement and health settings for the inbound queue.
     *
     * @param connectionString         local and CI only, emulator connection string
     * @param namespace                deployed only, fully qualified namespace for workload identity
     * @param queueName                the inbound queue
     * @param maxConcurrentCalls       processor concurrency
     * @param maxDeliveryCount         mirrors the broker queue setting
     * @param maxAutoLockRenewDuration must outlive any legitimate run
     * @param healthStaleness          age past which an unresolved error with no traffic stops being
     *                                 reported as an outage
     */
    public record Servicebus(
            String connectionString,
            String namespace,
            String queueName,
            int maxConcurrentCalls,
            int maxDeliveryCount,
            Duration maxAutoLockRenewDuration,
            Duration healthStaleness) {
    }

    /**
     * Claim timing: how long a claim lives and how long a run may take inside it.
     *
     * @param lease              claim expiry, written as {@code now() + lease}
     * @param processingDeadline enforced run bound, strictly shorter than the lease
     */
    public record Claim(Duration lease, Duration processingDeadline) {
    }

    /**
     * Processed-log availability probing.
     *
     * @param probeInterval store-health probe interval, driving start and resume
     */
    public record Store(Duration probeInterval) {
    }

    /**
     * Stub adapter behaviour, for the test and local profiles only.
     *
     * @param payloadFailureMode the simulated payload failure
     */
    public record Stub(PayloadFailureMode payloadFailureMode) {
    }

    /**
     * Where the hearing payload comes from, and how each source is reached.
     *
     * @param mode     the adapter serving the payload port
     * @param redis    the payload cache
     * @param fallback the query-side read used when the cache has nothing
     */
    public record Payload(PayloadSourceMode mode, Redis redis, Fallback fallback) {
    }

    /**
     * The hearing payload cache.
     *
     * @param host           cache host
     * @param port           cache port
     * @param password       cache access key; a secret, and therefore without a default
     * @param ssl            whether to connect over TLS, with certificates verified
     * @param keyPrefix      the prefix the producer writes the payload under
     * @param connectTimeout how long to wait for a connection
     * @param commandTimeout how long to wait for a command to answer
     */
    public record Redis(
            String host,
            int port,
            String password,
            boolean ssl,
            String keyPrefix,
            Duration connectTimeout,
            Duration commandTimeout) {
    }

    /**
     * The query-side payload read.
     *
     * @param maxAttempts    total attempts including the first
     * @param retryInterval  the wait between attempts
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response
     */
    public record Fallback(
            int maxAttempts,
            Duration retryInterval,
            Duration connectTimeout,
            Duration readTimeout) {
    }

    /**
     * The results context's query API: the payload fallback, and nothing else.
     *
     * @param baseUrl      scheme, host and port of the results context, no path
     * @param systemUserId the {@code CJSCPPUID} identity; a secret, never logged
     */
    public record Results(String baseUrl, String systemUserId) {
    }

    /**
     * The reference-data context: the query API the register's recipients are looked up in.
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
            SubscriptionsSourceMode mode,
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            int maxAttempts,
            Duration retryInterval,
            Duration connectTimeout,
            Duration readTimeout) {
    }

    /**
     * The progression context: the command API the court register is POSTed to.
     *
     * @param baseUrl        scheme, host and port of the progression context, no path
     * @param systemUserId   the {@code CJSCPPUID} identity for the POST; a secret, never logged
     * @param headers        any further headers the mesh requires, name to value
     * @param maxAttempts    total POST attempts, the first included
     * @param initialBackoff the first wait between retryable attempts; doubled each time
     * @param maxBackoff     the ceiling on any wait, a {@code Retry-After} included
     * @param connectTimeout how long to wait for the connection
     * @param readTimeout    how long to wait for the response once connected
     */
    public record Progression(
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration connectTimeout,
            Duration readTimeout) {
    }

    /**
     * What is checked before a register leaves this service.
     *
     * @param validateOutbound whether every document is validated against the vendored progression
     *                         schemas before the POST
     */
    public record Submission(boolean validateOutbound) {
    }
}
