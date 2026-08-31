package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.courtregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.courtregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.courtregister.inbound.DistributionCommandParser;

/**
 * The application core and the adapters currently serving its ports.
 *
 * <p>Every bean here is declared as its port type rather than as its own class, so replacing an
 * adapter is a change to one method here and to nothing else (constitution Principle V). The ports
 * arrive as the pipeline learns to use them: the payload source is served from
 * {@link StubPayloadConfig} or, later, the live cache-with-fallback configuration; the
 * now-subscriptions source, the transformation and the submission client join it in their own
 * phases.
 *
 * <p>Excluded from the {@code test} profile for the same reason as the processed-log wiring: the
 * pipeline needs the guard, the guard needs a store, and that profile has none.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class PipelineConfig {

    /**
     * The clock the run's processing deadline is measured against, and the one the queue-health
     * component ages its readings with.
     *
     * <p>Local elapsed time only. No claim decision is made from it — those compare the database's
     * {@code now()} against stored timestamps, inside the database — so this clock cannot introduce
     * the multi-node skew the data model's single-time-authority rule rules out.
     *
     * @return the clock
     */
    @Bean
    public Clock courtRegisterClock() {
        return Clock.systemUTC();
    }

    /**
     * The parser over the shared mapper, so the running service reads a body exactly as the contract
     * corpus does.
     *
     * @param objectMapper the shared, contract-configured mapper
     * @return the parser
     */
    @Bean
    public DistributionCommandParser distributionCommandParser(final ObjectMapper objectMapper) {
        return new DistributionCommandParser(objectMapper);
    }

    /**
     * The use-case orchestrator, wired against ports only.
     *
     * @param guard         the processed-log guard
     * @param payloadSource where hearing payloads come from
     * @param metrics       the instrument surface every outcome is counted on
     * @param clock         the clock the run's deadline is measured against
     * @param properties    the typed settings, for the processing deadline
     * @return the pipeline
     */
    @Bean
    public DistributionPipeline distributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final ProcessingMetrics metrics,
            final Clock clock,
            final CourtRegisterProperties properties) {
        return new DistributionPipeline(
                guard, payloadSource, metrics, clock, properties.claim().processingDeadline());
    }
}
