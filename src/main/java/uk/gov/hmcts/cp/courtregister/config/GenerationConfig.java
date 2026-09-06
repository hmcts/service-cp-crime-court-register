package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPause;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSinkImpl;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.pipeline.PdfPayloadMapper;

/**
 * The downstream half's own classes, put on the context that is going to run them.
 *
 * <p>Everything here is this service's, with no transport of its own: the grouping, the ported
 * payload generator, the one code path an outcome takes, the safety net and the requesting leg. The
 * adapters they speak through are chosen separately - {@link LiveGenerationConfig} where a
 * deployment means it, {@link StubGenerationConfig} where a local run does not - which is why the
 * two are two files: what a batch <em>is</em> does not change with the mode, and a configuration
 * that declared both would make the core conditional on a transport setting.
 *
 * <p>Declared as beans rather than annotated as components, exactly as {@link ProcessedLogConfig}'s
 * are and for the same reason: every one of these is a plain constructor-injected object that its
 * own suite builds in one line, and it stays that way.
 *
 * <p><strong>Only where the downstream half is deployed, and only outside the {@code test}
 * profile.</strong> The whole of it is conditional on {@code courtregister.generation.enabled}, so
 * an intake-only pod builds none of it; and it needs the register store, which needs a
 * {@code DataSource}, which the {@code test} profile deliberately has none of.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
public class GenerationConfig {

    /**
     * The {@code register_batch} table.
     *
     * <p>Over the register store's own client, because a batch is the store's neighbour: the two
     * write the same database and the reconciler reads this one while the store writes the other.
     *
     * @param jdbcClient the processed log's client, which is the register store's
     * @return the repository
     */
    @Bean
    public RegisterBatchRepository registerBatchRepository(final JdbcClient jdbcClient) {
        return new RegisterBatchRepository(jdbcClient);
    }

    /**
     * The one lever's gate: the flag read, and what a run may do about the answer.
     *
     * <p>Beside the classes it gates rather than beside the reader it asks, because which adapter
     * answers the flag is a mode decision and what a run does with the answer is not: the gate reads
     * OFF and UNREADABLE the same way whichever of the two readers replied (constitution Cutover
     * Rule).
     *
     * @param reader  the flag port, LIVE or STUB as the mode chose
     * @param metrics where a skipped run is counted, by the reason it was skipped
     * @return the gate every run asks first
     */
    @Bean
    public FeatureFlagGate featureFlagGate(
            final FeatureFlagReader reader, final GenerationMetrics metrics) {
        return new FeatureFlagGate(reader, metrics);
    }

    /**
     * The grouping of a night's registers into one batch per court centre and register date.
     *
     * @return the assembler, which decides and reads nothing
     */
    @Bean
    public BatchAssembler batchAssembler() {
        return new BatchAssembler();
    }

    /**
     * progression's payload generator, ported.
     *
     * <p>The clock is the service's own, the same bean the pipeline and the run measure by: the
     * payload carries a generated-at stamp, and a generator reading a different now would date a
     * document differently from the batch row that names it.
     *
     * @param clock this pod's reading of now
     * @return the mapper
     */
    @Bean
    public PdfPayloadMapper pdfPayloadMapper(final Clock clock) {
        return new PdfPayloadMapper(clock);
    }

    /**
     * The one code path a rendering outcome takes, whether the topic delivered it or the reconciler
     * fetched it.
     *
     * @param store   where the batch and its rows are moved
     * @param batches the {@code register_batch} table, read to correlate an outcome to a batch
     * @param metrics where an outcome no batch takes is counted, by the reason it was not taken
     * @return the port
     */
    @Bean
    public DocumentOutcomeSink documentOutcomeSink(final RegisterStore store,
            final RegisterBatchRepository batches, final GenerationMetrics metrics) {
        return new DocumentOutcomeSinkImpl(store, batches, metrics);
    }

    /**
     * The grace-period safety net, which carries a schedule and a lock of its own.
     *
     * <p>It has to be a bean for that schedule to exist at all: {@code @Scheduled} is read off a
     * bean, and a reconciler nothing constructed would leave every batch whose public event went
     * missing GENERATING until somebody noticed by hand.
     *
     * @param batches    the {@code register_batch} table, read for the batches whose outcome is
     *                   overdue
     * @param renderer   systemdocgenerator, asked what became of a payload it was given
     * @param sink       where an answer is applied, the same port the listener drives
     * @param store      where a silence is applied, as this service's own verdict
     * @param metrics    the downstream half's instruments
     * @param properties the settings it works to; it takes the one duration it reads
     * @param clock      the clock the grace period is measured back from
     * @return the reconciler
     */
    @Bean
    public GenerationReconciler generationReconciler(final RegisterBatchRepository batches,
            final DocumentRenderer renderer, final DocumentOutcomeSink sink,
            final RegisterStore store, final GenerationMetrics metrics,
            final GenerationProperties properties, final Clock clock) {
        return new GenerationReconciler(batches, renderer, sink, store, metrics,
                properties.gracePeriod(), clock);
    }

    /**
     * One batch, from the payload to the render request.
     *
     * <p>The retry policy is the shared one, built from the settings the two downstream clients
     * share: the taxonomy is stated once (defect fix C3) and only the loop lives here, because the
     * budget an attempt is measured against is the run's knowledge and not systemdocgenerator's.
     *
     * @param store        where the batch's registers are read and its progress written
     * @param mapper       progression's payload generator, ported
     * @param fileStore    the framework file service the payload is written to
     * @param renderer     systemdocgenerator, behind the port that names no status code
     * @param objectMapper the shared mapper, which decides what the payload's bytes are
     * @param properties   the endpoints' shared transport settings, for the retry policy
     * @param metrics      the downstream half's instruments
     * @param clock        the run's own reading of now
     * @return the requesting leg
     */
    @Bean
    public RegisterGenerationService registerGenerationService(final RegisterStore store,
            final PdfPayloadMapper mapper, final PayloadFileStore fileStore,
            final DocumentRenderer renderer, final ObjectMapper objectMapper,
            final CourtRegisterProperties properties, final GenerationMetrics metrics,
            final Clock clock) {

        final CourtRegisterProperties.Endpoints endpoints = properties.endpoints();
        return new RegisterGenerationService(store, mapper, fileStore, renderer, objectMapper,
                new RetryPolicy(endpoints.maxAttempts(), endpoints.initialBackoff(),
                        endpoints.maxBackoff(),
                        endpoints.connectTimeout().plus(endpoints.readTimeout())),
                (RetryPause) Thread::sleep, metrics, clock);
    }
}
