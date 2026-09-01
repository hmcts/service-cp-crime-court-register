package uk.gov.hmcts.cp.courtregister.config;

import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The real payload source: the cache, and the query side behind it.
 *
 * <p>Selected by {@code courtregister.payload.mode}, and selected by default — a service that has to
 * be told to fetch payloads is a service that will one day be deployed not fetching them.
 * {@link StubPayloadConfig} is the other half of the pair, and exactly one of the two contributes a
 * bean.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the pipeline wiring, which that
 * profile has no store for.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.payload", name = "mode", havingValue = "LIVE",
        matchIfMissing = true)
public class LivePayloadConfig {

    /**
     * The address the cache is reached on, TLS included.
     *
     * <p>Separated from the client so the one security decision in it can be asserted.
     * <strong>Certificates are verified wherever TLS is used — defect fix C15.</strong> The function
     * app connects with {@code rejectUnauthorized: false}
     * ({@code HearingResultedCacheQuery/index.js:107}), which is a defect rather than a setting: the
     * payload it reads is a whole hearing about named children, and a connection that will accept
     * any certificate will accept one somebody else presents. A fix with nothing asserting it is a
     * fix that reverts the next time somebody meets a self-signed certificate in a test environment
     * and reaches for the setting that makes the error go away, so {@code LivePayloadConfigTest}
     * reads this back.
     *
     * @param redis the cache settings
     * @return the URI the Lettuce client connects with
     */
    /* default */ static RedisURI cacheUri(final CourtRegisterProperties.Redis redis) {
        throw new UnsupportedOperationException("T065 builds the cache connection");
    }
}
