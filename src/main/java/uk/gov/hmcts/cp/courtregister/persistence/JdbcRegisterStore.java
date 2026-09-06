package uk.gov.hmcts.cp.courtregister.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RecordOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * The register store over this service's own Postgres.
 *
 * <p>The adapter that owns the write-time supersession and the batch-scoped {@code mark} statements,
 * written in the same idiom as the rest of {@code persistence}: hand-written SQL, one method per
 * statement, and the affected-row count is the decision.
 *
 * <p><strong>Every write that spans two tables is one statement.</strong> The recording and the
 * supersession it causes, and each {@code mark} and the row flip it implies, are written as a single
 * statement with data-modifying {@code WITH} clauses rather than as two statements inside a
 * transaction. One statement is atomic on its own, and the clauses see one snapshot: the
 * supersession therefore cannot see the row being inserted beside it, so a recording can never
 * supersede itself (research §8).
 *
 * <p><strong>Assembly is the exception, and it takes a transaction.</strong> Its statement is one
 * statement too, but the decision about it is not in the statement: whether the batch is the batch
 * that was asked for is a count compared in Java, and under autocommit that comparison happens
 * after the batch row and the stamps are already committed. A refusal would then leave a PENDING
 * batch holding {@code idx_register_batch_live_key} for that court centre and day, and the day
 * would never be rendered by any later run. So assembly runs inside a transaction and the refusal
 * rolls it back: the batch is assembled or it never existed.
 *
 * <p>The clauses are chained through their {@code RETURNING} output - {@code replaced} reads
 * {@code recorded}, {@code flipped} reads {@code generated} - which is what orders them. Postgres
 * does not otherwise say which clause runs first, and the superseded row points at the row being
 * inserted, so the insert has to have happened.
 *
 * <p><strong>Every {@code mark} is fenced on the status it read.</strong> The permitted moves belong
 * to {@link BatchStatus#canTransitionTo(BatchStatus)} and are asked there rather than re-encoded as
 * SQL predicates, so there is one state machine; the status that answered is then the predicate the
 * update carries, and a batch some other run moved in between changes no rows and is reported rather
 * than overwritten.
 */
public class JdbcRegisterStore implements RegisterStore {

    /** The zone the register day is the date part of, and the only one this service batches in. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** The one statement a fenced batch write is expected to change. */
    private static final long ONE_BATCH = 1;

    private static final String BATCH_ID = "batchId";
    private static final String OUTPUT_ID = "outputId";
    private static final String OUTPUT_IDS = "outputIds";
    private static final String EXPECTED = "expected";
    private static final String COURT_CENTRE_ID = "courtCentreId";
    private static final String REGISTER_DATE = "registerDate";
    private static final String REGISTER_TIME = "registerTime";
    private static final String HEARING_ID = "hearingId";

    /**
     * Statement 1 - record this hearing's register, superseding the one it replaces.
     *
     * <p>{@code incumbent} is the hearing's active row for the day, read on the statement's own
     * snapshot: RECORDED, unsuperseded and <strong>unbatched</strong>. The last of those three is
     * what leaves a row that is already on its way to a PDF alone - systemdocgenerator has been
     * handed a payload built from it and an event will arrive naming that batch, so rewriting it
     * would take a register out of a document that already contains it.
     *
     * <p>The two orderings are written out rather than assumed. A re-share that is <em>later</em>
     * than the incumbent supersedes it and is recorded RECORDED; one that is <em>earlier</em> - a
     * redelivery that overtook the register it belongs behind - does not displace the later register
     * and is recorded SUPERSEDED against it. Either way the key keeps exactly one active row, which
     * is the invariant the whole batch half is written against; a predicate that only handled the
     * ordinary direction would leave two.
     *
     * <p>The scalar subquery is deliberate: it answers {@code NULL} where there is no incumbent and
     * <em>fails</em> where there is more than one, so a key that had already lost the invariant is
     * reported rather than silently added to.
     */
    private static final String RECORD_REGISTER = """
            WITH incumbent AS (
                SELECT output_id, register_time
                  FROM processed_output
                 WHERE hearing_id = :hearingId
                   AND court_centre_id = :courtCentreId
                   AND register_date = :registerDate
                   AND status = 'RECORDED'
                   AND superseded_at IS NULL
                   AND batch_id IS NULL
            ), recorded AS (
                INSERT INTO processed_output (
                    output_id, source, request_id, court_centre_id, court_centre_ou_code,
                    register_date, file_name, status, request_digest, document, hearing_id,
                    hearing_date, court_house, register_time, defendant_type, recorded_flag_state,
                    superseded_at, superseded_by, created_at, updated_at)
                SELECT
                    :outputId, :source, :requestId, :courtCentreId, :courtCentreOuCode,
                    :registerDate, :fileName,
                    CASE WHEN later.output_id IS NULL THEN 'RECORDED' ELSE 'SUPERSEDED' END,
                    :digest, CAST(:document AS jsonb), :hearingId, :hearingDate, :courtHouse,
                    :registerTime, :defendantType, :flagState,
                    CASE WHEN later.output_id IS NULL THEN NULL ELSE now() END,
                    later.output_id, now(), now()
                  FROM (SELECT (SELECT output_id
                                  FROM incumbent
                                 WHERE register_time > :registerTime) AS output_id) later
                RETURNING output_id
            ), replaced AS (
                UPDATE processed_output superseded
                   SET status = 'SUPERSEDED',
                       superseded_at = now(),
                       superseded_by = recorded.output_id,
                       updated_at = now()
                  FROM recorded
                 WHERE superseded.output_id IN (SELECT output_id
                                                  FROM incumbent
                                                 WHERE register_time <= :registerTime)
                RETURNING superseded.output_id
            )
            SELECT (SELECT output_id FROM replaced) AS superseded_output_id
            """;

    /**
     * Statement 2 - the registers the nightly job may pick up.
     *
     * <p>Four predicates, and the fourth is the one that is easy to forget and expensive to get
     * wrong: a register recorded while the cutover flag was not ON may already have been sent by the
     * legacy, and batching it would send a second copy of the same day's register to the same Youth
     * Offending Team (research §12).
     *
     * <p>Oldest first, by the register instant. The batch takes its file name from the first record,
     * so the order the rows come back in is part of what the document is called.
     */
    private static final String ACTIVE_UNBATCHED = """
            SELECT output_id, hearing_id, hearing_date, court_centre_id, register_date,
                   register_time, file_name, defendant_type, recorded_flag_state, document
              FROM processed_output
             WHERE status = 'RECORDED'
               AND superseded_at IS NULL
               AND batch_id IS NULL
               AND recorded_flag_state = 'ON'
             ORDER BY register_time, output_id
            """;

    /**
     * Statement 3 - group a court centre's day into a batch and stamp it onto the rows.
     *
     * <p>The batch is inserted before the stamp because {@code processed_output.batch_id} carries a
     * foreign key to it, and both are one statement because a batch with no rows would hold the
     * partial unique key for that court centre and day against every later run.
     *
     * <p>The descriptive columns are selected from the first row rather than passed in, so the batch
     * says what the rows say. {@code system_generated} is true: assembly through the port is the
     * nightly job's, and the operations CLI assembles by writing its own row through
     * {@link RegisterBatchRepository#insert(RegisterBatch)}, where it can say false.
     *
     * <p>The stamp repeats the active-unbatched predicates. Between the read and the write a row can
     * have been superseded by a re-share or stamped by another run, and a batch that quietly
     * contained fewer rows than it was asked for would render a register missing a hearing nobody
     * could name - so the count comes back, the caller refuses it, and the transaction the whole
     * thing runs in takes the batch row and the partial stamps back out with the refusal.
     */
    private static final String ASSEMBLE_BATCH = """
            WITH assembled AS (
                INSERT INTO register_batch (
                    batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                    file_name, status, system_generated, assembled_at, attempts)
                SELECT :batchId, first_row.court_centre_id, first_row.court_centre_ou_code,
                       first_row.court_house, first_row.register_date, first_row.file_name,
                       'PENDING', true, now(), 0
                  FROM processed_output first_row
                 WHERE first_row.output_id = :firstOutputId
                RETURNING batch_id, court_centre_id, court_centre_ou_code, court_house,
                          register_date, file_name, system_generated, assembled_at
            ), stamped AS (
                UPDATE processed_output waiting
                   SET batch_id = assembled.batch_id, updated_at = now()
                  FROM assembled
                 WHERE waiting.output_id IN (:outputIds)
                   AND waiting.status = 'RECORDED'
                   AND waiting.superseded_at IS NULL
                   AND waiting.batch_id IS NULL
                RETURNING waiting.output_id
            )
            SELECT assembled.batch_id, assembled.court_centre_id, assembled.court_centre_ou_code,
                   assembled.court_house, assembled.register_date, assembled.file_name,
                   assembled.system_generated, assembled.assembled_at,
                   (SELECT count(*) FROM stamped) AS stamped_rows
              FROM assembled
            """;

    /** Statement 4 - the status a transition is asked about and then fenced on. */
    private static final String READ_BATCH_STATUS = """
            SELECT status FROM register_batch WHERE batch_id = :batchId
            """;

    /**
     * Statement 5 - systemdocgenerator accepted the render request for this batch.
     *
     * <p>{@code attempts} is a lifetime tally and never a control variable: the run deadline decides
     * when to stop trying, and the column records how often this batch has been asked for.
     */
    private static final String MARK_REQUESTED = """
            UPDATE register_batch
               SET status = 'GENERATING',
                   payload_file_id = :payloadFileId,
                   requested_at = now(),
                   attempts = attempts + 1
             WHERE batch_id = :batchId AND status = :expected
            """;

    /**
     * Statement 6 - the document exists, and this batch's rows move with it.
     *
     * <p><strong>Defect fix P3, stated as a predicate.</strong> The rows are found through the batch
     * the update just settled ({@code flipped} joins {@code generated}), so the only rows that can
     * move are the ones stamped with that batch identity. Progression sweeps by court centre
     * instead, which marks another day's rows generated and leaves that day's register never
     * assembled, rendered or sent.
     *
     * <p>{@code completed_by} is not written here: which mechanism learned the outcome is the sink's
     * knowledge, not the store's, and it reaches the row through
     * {@link RegisterBatchRepository#update(RegisterBatch)}.
     */
    private static final String MARK_GENERATED = """
            WITH generated AS (
                UPDATE register_batch
                   SET status = 'GENERATED',
                       document_file_id = :documentFileId,
                       generated_at = :generatedAt
                 WHERE batch_id = :batchId AND status = :expected
                RETURNING batch_id
            ), flipped AS (
                UPDATE processed_output recorded
                   SET status = 'GENERATED', updated_at = now()
                  FROM generated
                 WHERE recorded.batch_id = generated.batch_id
                   AND recorded.status = 'RECORDED'
                RETURNING recorded.output_id
            )
            SELECT count(*) FROM generated
            """;

    /**
     * Statement 7 - the batch ended without a document, under one bounded reason.
     *
     * <p>The rows stay RECORDED whatever the reason, because nothing was ever sent about them. Two
     * of the six reasons say the batch never left this service, and only for those is the stamp
     * released: the rows become unbatched again and the next run re-assembles them under a fresh
     * batch identity (data-model.md). The other four leave the stamp in place - systemdocgenerator
     * was asked, so a document may yet exist, and re-rendering it is a decision a person makes.
     */
    private static final String MARK_FAILED = """
            WITH failed AS (
                UPDATE register_batch
                   SET status = 'FAILED',
                       failure_reason = :reason,
                       sdg_reason = :sdgReason,
                       failed_at = now()
                 WHERE batch_id = :batchId AND status = :expected
                RETURNING batch_id
            ), released AS (
                UPDATE processed_output recorded
                   SET batch_id = NULL, updated_at = now()
                  FROM failed
                 WHERE recorded.batch_id = failed.batch_id
                   AND recorded.status = 'RECORDED'
                   AND CAST(:releaseRows AS boolean)
                RETURNING recorded.output_id
            )
            SELECT count(*) FROM failed
            """;

    /**
     * Statement 8 - every recipient of the batch has been attempted.
     *
     * <p>The batch's own terminal state is the summary's verdict - NOTIFIED, PARTIALLY_NOTIFIED or
     * NOTIFIED_NOBODY - and its rows reach NOTIFIED under all three. A batch nobody subscribes to is
     * finished rather than left generated for ever waiting for an event nobody publishes, which is
     * defect fix P1.
     */
    private static final String MARK_NOTIFIED = """
            WITH notified AS (
                UPDATE register_batch
                   SET status = :outcome, notified_at = now()
                 WHERE batch_id = :batchId AND status = :expected
                RETURNING batch_id
            ), flipped AS (
                UPDATE processed_output generated
                   SET status = 'NOTIFIED', updated_at = now()
                  FROM notified
                 WHERE generated.batch_id = notified.batch_id
                   AND generated.status = 'GENERATED'
                RETURNING generated.output_id
            )
            SELECT count(*) FROM notified
            """;

    /** The three states a notification tally is allowed to settle a batch in. */
    private static final Set<BatchStatus> NOTIFICATION_OUTCOMES = Set.of(
            BatchStatus.NOTIFIED, BatchStatus.PARTIALLY_NOTIFIED, BatchStatus.NOTIFIED_NOBODY);

    /** The two failures that never left this service, and so give their rows back to the next run. */
    private static final Set<BatchFailureReason> RELEASING_REASONS = Set.of(
            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, BatchFailureReason.ASSEMBLY_FAILED);

    /**
     * The connection every statement in this class is issued through.
     *
     * <p>Held from construction rather than looked up per method, so that the store is one client's
     * worth of statements and a caller can see what it talks to.
     */
    private final JdbcClient jdbcClient;

    /**
     * The mapper the recorded document is written and read back through.
     *
     * <p>The service's own contract mapper rather than one of this class's invention: the document
     * stored here is the one the payload mapper renders, and a mapper that read floating-point
     * amounts as binary would change a financial order on the way through.
     */
    private final ObjectMapper objectMapper;

    /**
     * The one write in this class whose decision is made outside its statement.
     *
     * <p>Held rather than reached for, and over the same {@code DataSource} the client issues
     * against, because a transaction manager bound to a second one would open a transaction nothing
     * in this class ever joins.
     */
    private final TransactionOperations transactions;

    /**
     * Binds the store to this service's own Postgres.
     *
     * @param jdbcClient   the client every statement in this class is issued through
     * @param transactions the transaction {@link #assemble(CourtCentreDay, List)} runs in, over the
     *                     same data source as the client
     */
    public JdbcRegisterStore(final JdbcClient jdbcClient, final TransactionOperations transactions) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
        this.objectMapper = JacksonConfig.contractObjectMapper();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The row is written with the document as recorded and {@code request_digest} as its SHA-256,
     * which is what the differential audit compares. The register day is the London date part of the
     * document's own register instant rather than anything taken from the command, because that
     * instant is what orders two re-shares of one hearing and the day it falls on is what the batch
     * groups by.
     *
     * <p>The OU code is written here because {@link #assemble(CourtCentreDay, List)} reads it off
     * the batch's first row, and nothing between the transformation and the render payload knows it
     * otherwise: the document does not carry it.
     *
     * @throws IllegalStateException if the key already carries more than one active row, or if the
     *                               statement recorded nothing
     */
    @Override
    public RecordOutcome record(final DistributionCommand command,
            final CourtRegisterDocument document, final String courtCentreOuCode,
            final String defendantType, final RecordedFlagState flagState) {
        final UUID outputId = UUID.randomUUID();
        final String json = objectMapper.writeValueAsString(document);
        final Instant registerTime = instantOf(document.registerDate(), "registerDate");
        final Instant hearingDate = instantOf(document.hearingDate(), "hearingDate");
        return jdbcClient.sql(RECORD_REGISTER)
                .param(OUTPUT_ID, outputId)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param(COURT_CENTRE_ID, UUID.fromString(document.courtCentreId()))
                .param("courtCentreOuCode", courtCentreOuCode, Types.VARCHAR)
                .param(REGISTER_DATE, LocalDate.ofInstant(registerTime, LONDON))
                .param("fileName", document.fileName())
                .param("digest", digestOf(json))
                .param("document", json)
                .param(HEARING_ID, UUID.fromString(document.hearingId()))
                .param("hearingDate", offsetOf(hearingDate))
                .param("courtHouse", courtHouseOf(document))
                .param(REGISTER_TIME, offsetOf(registerTime))
                .param("defendantType", defendantType)
                .param("flagState", flagState.name())
                .query((rs, rowNumber) ->
                        new RecordOutcome(outputId, rs.getObject("superseded_output_id", UUID.class)))
                .single();
    }

    @Override
    public List<RegisterRecord> activeUnbatched() {
        return jdbcClient.sql(ACTIVE_UNBATCHED)
                .query((rs, rowNumber) -> registerRecord(rs))
                .list();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the batch is empty or holds a record from another key
     * @throws IllegalStateException    if a record stopped being available between the read and the
     *                                  stamp, so the assembled batch would not be the one asked for
     */
    @Override
    public RegisterBatch assemble(final CourtCentreDay key, final List<RegisterRecord> records) {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("a batch is assembled from at least one register: "
                    + key.courtCentreId() + " on " + key.registerDate());
        }
        final List<UUID> outputIds = records.stream().map(RegisterRecord::outputId).toList();
        records.stream()
                .filter(record -> !key.equals(record.key()))
                .findFirst()
                .ifPresent(foreign -> {
                    throw new IllegalArgumentException("register " + foreign.outputId()
                            + " belongs to " + foreign.key() + ", not to " + key);
                });
        return transactions.execute(transaction -> stamp(outputIds)).batch();
    }

    /**
     * The assembly statement and the count that judges it, inside the transaction that undoes both.
     *
     * <p>The refusal is thrown from here rather than from the caller precisely so that it is thrown
     * <em>inside</em> the transaction: a check made after the transaction returned would be a check
     * on a batch that is already committed, which is the state this method exists to make
     * impossible.
     */
    private Assembled stamp(final List<UUID> outputIds) {
        final Assembled assembled = jdbcClient.sql(ASSEMBLE_BATCH)
                .param(BATCH_ID, UUID.randomUUID())
                .param("firstOutputId", outputIds.getFirst())
                .param(OUTPUT_IDS, outputIds)
                .query((rs, rowNumber) -> new Assembled(assembledBatch(rs), rs.getLong("stamped_rows")))
                .single();
        if (assembled.stampedRows() != outputIds.size()) {
            throw new IllegalStateException("batch " + assembled.batch().batchId() + " was asked for "
                    + outputIds.size() + " registers and stamped " + assembled.stampedRows()
                    + "; a register was superseded or batched elsewhere in between");
        }
        return assembled;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if there is no such batch, if the state machine refuses the
     *                               move, or if the batch changed under the statement
     */
    @Override
    public void markRequested(final UUID batchId, final UUID payloadFileId) {
        final BatchStatus expected = permitted(batchId, BatchStatus.GENERATING);
        settle(jdbcClient.sql(MARK_REQUESTED)
                .param(BATCH_ID, batchId)
                .param(EXPECTED, expected.name())
                .param("payloadFileId", payloadFileId)
                .update(), batchId, expected, BatchStatus.GENERATING);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if there is no such batch, if the state machine refuses the
     *                               move, or if the batch changed under the statement
     */
    @Override
    public void markGenerated(final UUID batchId, final UUID documentFileId,
            final Instant generatedAt) {
        final BatchStatus expected = permitted(batchId, BatchStatus.GENERATED);
        settle(jdbcClient.sql(MARK_GENERATED)
                .param(BATCH_ID, batchId)
                .param(EXPECTED, expected.name())
                .param("documentFileId", documentFileId)
                .param("generatedAt", offsetOf(generatedAt))
                .query(Long.class)
                .single(), batchId, expected, BatchStatus.GENERATED);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if there is no such batch, if the state machine refuses the
     *                               move, or if the batch changed under the statement
     */
    @Override
    public void markFailed(final UUID batchId, final BatchFailureReason reason,
            final String sdgReason) {
        final BatchStatus expected = permitted(batchId, BatchStatus.FAILED);
        settle(jdbcClient.sql(MARK_FAILED)
                .param(BATCH_ID, batchId)
                .param(EXPECTED, expected.name())
                .param("reason", reason.name())
                .param("sdgReason", sdgReason)
                .param("releaseRows", RELEASING_REASONS.contains(reason))
                .query(Long.class)
                .single(), batchId, expected, BatchStatus.FAILED);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the summary's verdict is not one a notification tally can
     *                                  produce
     * @throws IllegalStateException    if there is no such batch, if the state machine refuses the
     *                                  move, or if the batch changed under the statement
     */
    @Override
    public void markNotified(final UUID batchId, final NotificationSummary summary) {
        if (!NOTIFICATION_OUTCOMES.contains(summary.outcome())) {
            throw new IllegalArgumentException("a notification tally settles a batch NOTIFIED, "
                    + "PARTIALLY_NOTIFIED or NOTIFIED_NOBODY, not " + summary.outcome());
        }
        final BatchStatus expected = permitted(batchId, summary.outcome());
        settle(jdbcClient.sql(MARK_NOTIFIED)
                .param(BATCH_ID, batchId)
                .param(EXPECTED, expected.name())
                .param("outcome", summary.outcome().name())
                .query(Long.class)
                .single(), batchId, expected, summary.outcome());
    }

    /**
     * The state machine asked once, where the move is attempted.
     *
     * <p>The status that answered is handed back so the update can carry it as a predicate: a batch
     * another run moved between the read and the write then changes no rows, and
     * {@link #settle(long, UUID, BatchStatus, BatchStatus)} says so rather than the store believing
     * a transition that did not happen.
     */
    private BatchStatus permitted(final UUID batchId, final BatchStatus next) {
        final BatchStatus current = jdbcClient.sql(READ_BATCH_STATUS)
                .param(BATCH_ID, batchId)
                .query(String.class)
                .optional()
                .map(BatchStatus::valueOf)
                .orElseThrow(() -> new IllegalStateException(
                        "no register batch " + batchId + " to move to " + next));
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "batch " + batchId + " may not move from " + current + " to " + next);
        }
        return current;
    }

    /** The affected-row count is the decision, and a count of nothing is reported rather than kept. */
    private static void settle(final long batches, final UUID batchId, final BatchStatus expected,
            final BatchStatus next) {
        if (batches != ONE_BATCH) {
            throw new IllegalStateException("batch " + batchId + " was not moved from " + expected
                    + " to " + next + "; it changed under the statement");
        }
    }

    /** The row view the batch half reads, with the document as it was stored. */
    private RegisterRecord registerRecord(final ResultSet rs) throws SQLException {
        return new RegisterRecord(
                rs.getObject("output_id", UUID.class),
                rs.getObject("hearing_id", UUID.class),
                instant(rs.getObject("hearing_date", OffsetDateTime.class)),
                new CourtCentreDay(rs.getObject("court_centre_id", UUID.class),
                        rs.getObject("register_date", LocalDate.class)),
                instant(rs.getObject("register_time", OffsetDateTime.class)),
                rs.getString("file_name"),
                rs.getString("defendant_type"),
                RecordedFlagState.valueOf(rs.getString("recorded_flag_state")),
                objectMapper.readValue(rs.getString("document"), CourtRegisterDocument.class));
    }

    /** The batch as the insert left it, so every stamp on it is the database's own. */
    private static RegisterBatch assembledBatch(final ResultSet rs) throws SQLException {
        return new RegisterBatch(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("court_centre_id", UUID.class),
                rs.getString("court_centre_ou_code"),
                rs.getString("court_house"),
                rs.getObject("register_date", LocalDate.class),
                rs.getString("file_name"),
                null,
                null,
                BatchStatus.PENDING,
                null,
                null,
                rs.getBoolean("system_generated"),
                null,
                instant(rs.getObject("assembled_at", OffsetDateTime.class)),
                null,
                null,
                null,
                null,
                0);
    }

    /** The batch the statement wrote, beside the count of rows it managed to stamp. */
    private record Assembled(RegisterBatch batch, long stampedRows) {
    }

    /**
     * The court house the register was produced at, where the document names one.
     *
     * <p>Progression kept this on its own request row and the batch's payload carries it, so it is
     * lifted out of the document at the write rather than re-derived at assembly from a document
     * that may by then have been superseded.
     */
    private static String courtHouseOf(final CourtRegisterDocument document) {
        return document.hearingVenue() == null ? null : document.hearingVenue().courtHouse();
    }

    /**
     * The document's own date-time strings, read as instants.
     *
     * <p>They are strings on the wire because the contract says {@code date-time} and defect fix C10
     * carries what was shared through unaltered; the store orders rows by them, so this is where
     * they become an instant and where a value that is not one is refused.
     */
    private static Instant instantOf(final String value, final String field) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException unparseable) {
            throw new IllegalArgumentException(
                    "the register document's " + field + " is not a date-time: " + value,
                    unparseable);
        }
    }

    /** The one hex form this service writes a SHA-256 in: sixty-four characters, lower case. */
    private static String digestOf(final String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM)
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen on a running JVM; if
            // it ever did, no register could be recorded with the evidence of what it holds, and
            // failing loudly is the only honest response.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
