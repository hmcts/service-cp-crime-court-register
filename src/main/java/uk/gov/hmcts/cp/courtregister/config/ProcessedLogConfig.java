package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.courtregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.persistence.JdbcRegisterStore;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedLogProbe;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;

/**
 * The processed log and the guard over it.
 *
 * <p>Registered here rather than annotated as components, so the guard and its repositories stay
 * plain constructor-injected objects that a unit test builds in one line. The persistence suites
 * already do exactly that, against a Testcontainers store and no Spring at all.
 *
 * <p>Excluded from the {@code test} profile because everything in it needs a {@code DataSource}, and
 * that profile deliberately has none: the plain context-load tests must keep running with no broker,
 * no database and therefore without Docker.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ProcessedLogConfig {

    /**
     * The request half of the log.
     *
     * <p>It binds the claim lease once, because that is the only setting its statements need — the
     * expiry it produces is computed by the database, not here.
     *
     * @param jdbcClient the store
     * @param properties the typed settings, for the claim lease
     * @return the repository
     */
    @Bean
    public ProcessedRequestRepository processedRequestRepository(
            final JdbcClient jdbcClient, final CourtRegisterProperties properties) {
        return new ProcessedRequestRepository(jdbcClient, properties.claim().lease());
    }

    /**
     * The availability question the consumer lifecycle controller and every delivery both ask.
     *
     * <p>A bean of its own rather than a method on the repository: the repository's statements are
     * the state machine, and "can this database be reached at all" is a different question asked at
     * a different moment — before a delivery is examined, and on a schedule while intake is stopped.
     *
     * @param jdbcClient the store
     * @return the probe
     */
    @Bean
    public ProcessedLogProbe processedLogProbe(final JdbcClient jdbcClient) {
        return new ProcessedLogProbe(jdbcClient);
    }

    /**
     * The output half of the log — one row per submitted command, the court register having no
     * fan-out dimension.
     *
     * <p>No lease and no other setting: its statements are keyed and conditional on state alone, and
     * every timestamp in them comes from the database.
     *
     * @param jdbcClient the store
     * @return the repository
     */
    @Bean
    public ProcessedOutputRepository processedOutputRepository(final JdbcClient jdbcClient) {
        return new ProcessedOutputRepository(jdbcClient);
    }

    /**
     * The register store, over the same log and the same client as the repositories above it.
     *
     * <p>It belongs beside them because it writes the same table: a recorded register <em>is</em> the
     * output half of the processed log, widened by V2 with the document, the hearing, the register
     * instant and the batch it is on. Declared without a condition, so a pod running the default
     * {@code courtregister.output=record} always has the store its last stage writes through, and the
     * {@code progression-post} fallback simply never asks it for anything.
     *
     * <p>The transaction template is built here rather than injected because the store needs the
     * commit boundary to be over <em>this</em> data source: the file-service datasource is a second
     * one, write-only and never transacted from here, and a manager bound to it would open a
     * transaction none of the store's statements ever joins.
     *
     * @param jdbcClient         the store
     * @param transactionManager the manager over the same data source the client issues against
     * @return the register store
     */
    @Bean
    public RegisterStore registerStore(
            final JdbcClient jdbcClient, final PlatformTransactionManager transactionManager) {
        return new JdbcRegisterStore(jdbcClient, new TransactionTemplate(transactionManager));
    }

    /**
     * The {@code (source, requestId)} idempotency guard.
     *
     * @param repository the request half of the log
     * @param metrics    the instrument surface stale-runner rejections are counted on
     * @return the guard
     */
    @Bean
    public IdempotencyGuard idempotencyGuard(
            final ProcessedRequestRepository repository, final ProcessingMetrics metrics) {
        return new IdempotencyGuard(repository, metrics);
    }
}
