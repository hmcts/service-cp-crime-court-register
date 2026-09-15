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
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

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
 *
 * <p><strong>The batch and notification repositories are here rather than with the generation
 * half.</strong> They were declared in {@link GenerationConfig}, which is conditional on
 * {@code courtregister.generation.enabled}, and the morning exception report reads both - so
 * {@code courtregister.report.enabled=true} with the generation half switched off, which is FR-004's
 * deployment and the MVP's own shape, could not start. This configuration is generation-neutral and
 * already declares every other reader of the same database over the same {@link JdbcClient} and the
 * same {@link PlatformTransactionManager} those two constructors take, so it is where they belong in
 * any case. A second copy of each declared in the report's own configuration was rejected for the
 * reason a second lock provider is: two beans of one repository over one table is a race with a
 * different name, and the pair would drift.
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
     * The {@code register_batch} table.
     *
     * <p>Over the register store's own client, because a batch is the store's neighbour: the two
     * write the same database and the reconciler reads this one while the store writes the other.
     *
     * <p>The transaction manager is the register store's own, so the two statements the
     * notification claim is taken in - the advisory lock and the compare-and-set - run on the
     * connection this client already joins. It is the only thing here that needs a transaction at
     * all; every other statement is one statement.
     *
     * <p>The lease is {@code courtregister.notification.claim-lease} and not the reconciler's grace
     * period. The two answer different questions: how long a batch may hold a document before the
     * safety net looks is no bound at all on telling that batch's recipients, whose cost is the
     * number of Youth Offending Teams it is addressed to times whatever notificationnotify makes of
     * each of them. Startup refuses a lease that cannot cover one recipient's POST cycle twice over
     * ({@link PropertiesValidator#NOTIFICATION_LEASE_MARGIN}).
     *
     * @param jdbcClient         the processed log's client, which is the register store's
     * @param transactionManager the register store's transaction manager, for the claim's two
     *                           statements
     * @param properties         the bound settings, for the notification claim's lease
     * @return the repository
     */
    @Bean
    public RegisterBatchRepository registerBatchRepository(final JdbcClient jdbcClient,
            final PlatformTransactionManager transactionManager,
            final CourtRegisterProperties properties) {
        return new RegisterBatchRepository(jdbcClient,
                new TransactionTemplate(transactionManager),
                properties.notification().claimLease());
    }

    /**
     * The {@code register_notification} table.
     *
     * <p>Over the same client, and beside {@link #registerBatchRepository} rather than inside the
     * store, for the reason that read is: one recipient's row is a single-table read and write that
     * the register store has no business owning, and the notifier is the only thing that touches it.
     *
     * @param jdbcClient the processed log's client, which is the register store's
     * @return the repository
     */
    @Bean
    public RegisterNotificationRepository registerNotificationRepository(
            final JdbcClient jdbcClient) {
        return new RegisterNotificationRepository(jdbcClient);
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
