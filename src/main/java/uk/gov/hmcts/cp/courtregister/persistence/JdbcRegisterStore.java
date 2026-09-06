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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RecordOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
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
 * <p><strong>Two of them take a transaction as well, and for two different reasons.</strong>
 * Assembly's statement is one statement too, but the decision about it is not in the statement:
 * whether the batch is the batch that was asked for is a count compared in Java, and under
 * autocommit that comparison happens after the batch row and the stamps are already committed. A
 * refusal would then leave a PENDING batch holding {@code idx_register_batch_live_key} for that
 * court centre and day, and the day would never be rendered by any later run. So assembly runs
 * inside a transaction and the refusal rolls it back: the batch is assembled or it never existed.
 * Recording runs inside one because it may be issued more than once - a re-share that loses the
 * race for its key is refused by {@code idx_output_active_register_key} and tried again on a fresh
 * snapshot - and the supersession the refused attempt had already made has to go with it.
 *
 * <p>The clauses are chained through their output, which is what orders them: Postgres does not
 * otherwise say which clause runs first. In the recording statement {@code recorded} counts
 * {@code replaced}'s rows, because the row being replaced holds the key the insert is about to take
 * and has to be out of the index before the insert asks for it; in {@code mark generated} and
 * {@code mark notified}, {@code flipped} reads {@code generated} through {@code FROM}, because the
 * rows that move are the rows of the batch the update just settled.
 *
 * <p><strong>Recording is idempotent on the command's own key.</strong> The recording and the
 * completion of the command are two statements and not one, so a pod that stops between them leaves
 * a register written against a request the broker will deliver again. The recording statement is
 * therefore preceded, inside its own transaction, by a read of {@code (source, request_id)}: a
 * command that has been recorded before is answered with the row it already wrote, exactly as 001's
 * POST path answers a re-claim through {@code ON CONFLICT (source, request_id)}. The two unique
 * keys a recording can meet are told apart for the same reason - only
 * {@code idx_output_active_register_key} is a lost race worth retrying, and
 * {@code processed_output_unique_request} is this command arriving twice.
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

    /**
     * How many times a recording that lost the race for its key re-reads and tries again.
     *
     * <p>Three, and bounded rather than open-ended for the reason every retry in this service is
     * bounded: a hearing being re-shared faster than the store can record it is a producer to look
     * at, and a loop that never gives up would hold a broker thread against it for as long as it
     * lasted. Each attempt loses only to a re-share that <em>committed</em> in between, so three of
     * them is already two more collisions than the race the invariant exists for.
     */
    private static final int RECORD_ATTEMPTS = 3;

    /** How every refusal in this class names the batch it is about, and the only thing it names. */
    private static final String BATCH = "batch ";

    /** V1's key on the request a row is evidence about: one output row per command, ever. */
    private static final String COMMAND_KEY = "processed_output_unique_request";

    /** The four statuses the recorder writes, and the only ones a recorded register can be in. */
    private static final Set<String> RECORDER_STATUSES =
            Set.of("RECORDED", "SUPERSEDED", "GENERATED", "NOTIFIED");

    private static final String BATCH_ID = "batchId";
    private static final String OUTPUT_ID = "outputId";
    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String SUPERSEDED_OUTPUT_ID = "superseded_output_id";
    private static final String OUTPUT_IDS = "outputIds";
    private static final String EXPECTED = "expected";
    private static final String COURT_CENTRE_ID = "courtCentreId";
    private static final String REGISTER_DATE = "registerDate";
    private static final String REGISTER_TIME = "registerTime";
    private static final String HEARING_ID = "hearingId";

    /**
     * Statement 0 - the register this command already has, where it has been delivered before.
     *
     * <p>Read on the recording transaction's own snapshot and before anything is written, because a
     * redelivery is not a second register: the command is the same command, and the row it wrote the
     * first time is the answer to it. Without this read the insert meets {@value #COMMAND_KEY} and
     * the store, which cannot tell that refusal from the V3 key race, would fail a command whose
     * register is recorded and active and would go on failing it until the broker parked it.
     *
     * <p>The row it superseded comes back with it, so a redelivery is answered exactly as the
     * delivery that recorded it was - and it is asked for as <strong>the row this recording
     * replaced</strong> rather than as the row naming it, because more than one row can name it and
     * this store writes them itself. Statement 1 records an <em>earlier</em> share that arrives
     * after the register it belongs behind as SUPERSEDED against that register, so both the row the
     * later share replaced and the late arrival that never displaced it carry the same
     * {@code superseded_by}. A read of "the row naming this one" would find two and fail, on a
     * command whose register is recorded and active, every time the broker delivered it.
     *
     * <p>The two are told apart by <em>when</em> the supersession happened. Statement 1 supersedes
     * and inserts in one statement, so the row this recording replaced carries a
     * {@code superseded_at} equal to this row's own {@code created_at}, to the microsecond; a row
     * that recorded itself SUPERSEDED against this one stamped both of its own timestamps in its
     * own transaction, later. The scalar subquery stays scalar for the reason statement 1's is: it
     * answers {@code NULL} where nothing was superseded and <em>fails</em> where two rows were
     * superseded by one recording, which no single statement can produce and is a key that had
     * genuinely lost the invariant.
     */
    private static final String RECORDED_REGISTER = """
            SELECT recorded.output_id,
                   recorded.status,
                   (SELECT replaced.output_id
                      FROM processed_output replaced
                     WHERE replaced.superseded_by = recorded.output_id
                       AND replaced.superseded_at = recorded.created_at) AS superseded_output_id
              FROM processed_output recorded
             WHERE recorded.source = :source
               AND recorded.request_id = :requestId
            """;

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
     * <p><strong>The supersession runs before the insert, and that order is the statement's to
     * keep.</strong> {@code idx_output_active_register_key} (V3) admits one active row per key, and
     * the row being replaced still holds that key until the update takes it out of the index: an
     * insert issued first would collide with the register it is replacing, on the ordinary
     * single-threaded re-share. So {@code replaced} is chained ahead of {@code recorded} - the
     * insert's source counts {@code replaced}'s rows, which cannot be counted until every one of
     * its updates has been made - and the supersession names the new row through {@code :outputId},
     * which the caller minted, rather than through the insert's {@code RETURNING}. The foreign key
     * to it is satisfied by the end of the statement, which is when Postgres checks it.
     *
     * <p>Reversing the chain costs nothing that research §8 asked for: {@code incumbent} is a read
     * of rows that already existed on this statement's snapshot, so the supersession still cannot
     * see the row being inserted beside it and a recording still cannot supersede itself.
     *
     * <p>The scalar subquery is deliberate: it answers {@code NULL} where there is no incumbent and
     * <em>fails</em> where there is more than one, so a key that had already lost the invariant is
     * reported rather than silently added to.
     *
     * <p><strong>{@code now()} is read once here, and statement 0 depends on that.</strong> The
     * supersession's {@code superseded_at} and the insert's {@code created_at} are the same
     * transaction timestamp, which is how a redelivery tells the row this recording replaced from
     * the rows that later came to name it. A rewrite that timed either of them differently would
     * have to give statement 0 another way to ask the question.
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
            ), replaced AS (
                UPDATE processed_output superseded
                   SET status = 'SUPERSEDED',
                       superseded_at = now(),
                       superseded_by = :outputId,
                       updated_at = now()
                 WHERE superseded.output_id IN (SELECT output_id
                                                  FROM incumbent
                                                 WHERE register_time <= :registerTime)
                RETURNING superseded.output_id
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
                                 WHERE register_time > :registerTime) AS output_id,
                               (SELECT count(*) FROM replaced) AS superseded_rows) later
                RETURNING output_id
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
     * <p>{@code completed_by} is set here, in this statement, because there is nowhere else left to
     * set it. The move is fenced on the status the caller read, so a write before it would be
     * claiming an outcome about a batch that is still GENERATING and a write after it would need
     * GENERATED to GENERATED, which {@link BatchStatus#canTransitionTo(BatchStatus)} refuses. Which
     * mechanism learned the outcome is still the sink's knowledge; it arrives as the mark's own
     * argument and is written by the mark's own statement.
     */
    private static final String MARK_GENERATED = """
            WITH generated AS (
                UPDATE register_batch
                   SET status = 'GENERATED',
                       document_file_id = :documentFileId,
                       generated_at = :generatedAt,
                       completed_by = :completedBy
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
     * <p>{@code sdg_reason} is bounded on the way in, by the rule the domain states once
     * ({@link RegisterBatch#boundedReason(String)}). How long systemdocgenerator's message is is
     * systemdocgenerator's decision, and an unbounded write against a bounded column would fail
     * this whole statement: the batch would stay GENERATING, unable to say why it failed, until the
     * reconciler gave up on it.
     *
     * <p>The rows stay RECORDED whatever the reason, because nothing was ever sent about them. Two
     * of the six reasons say the batch never left this service, and only for those is the stamp
     * released: the rows become unbatched again and the next run re-assembles them under a fresh
     * batch identity (data-model.md). The other four leave the stamp in place - systemdocgenerator
     * was asked, so a document may yet exist, and re-rendering it is a decision a person makes.
     *
     * <p>{@code completed_by} is written here for the same reason it is written by statement 6, and
     * it is null for most of these endings: only a {@code generation-failed} event and a reconciled
     * query are somebody else's answer about the render. The other four are this service's own
     * verdict about a render it could not ask for or could not hear about, and naming a completion
     * mechanism for those would credit a decision nobody outside this service made. Which is which
     * is {@link BatchFailureReason#isGeneratorAttributed()}, and a mark that disagrees with it is
     * refused before this statement is issued rather than persisted contradicting itself.
     */
    private static final String MARK_FAILED = """
            WITH failed AS (
                UPDATE register_batch
                   SET status = 'FAILED',
                       failure_reason = :reason,
                       sdg_reason = :sdgReason,
                       completed_by = :completedBy,
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
     * <p><strong>A recording that lost the race re-reads and records again.</strong> Which register
     * a re-share replaces is decided by a read, so two re-shares of one hearing that both read
     * before either has committed both find the same incumbent - and
     * {@code idx_output_active_register_key} refuses the second of them rather than letting the day
     * be rendered with one hearing on it twice. That refusal is not the caller's to carry: the
     * losing re-share is a message the broker delivered and the pipeline completed, and handing the
     * listener a duplicate-key failure would abandon a command whose register is safely recorded and
     * turn an invariant the database keeps into an outage. So the attempt is made again, on a fresh
     * transaction and therefore a fresh snapshot: it finds the register the winner left and either
     * supersedes it or is recorded SUPERSEDED against it, exactly as an unraced re-share would.
     *
     * <p>Each attempt is its own transaction because a violated one cannot be continued: Postgres
     * refuses every further statement on an aborted transaction, so a retry inside it would fail on
     * the read rather than on the write. The identifier the caller is answered with is minted once
     * and reused, which is safe precisely because a refused attempt committed nothing.
     *
     * <p><strong>A command delivered again is answered rather than recorded again.</strong> The
     * completion of a command is written after this call and not inside it, so a pod that stops in
     * between - or a completion that fails transiently - leaves the register written and the request
     * unfinished, and the broker delivers the message again. Each attempt therefore reads
     * {@code (source, request_id)} first and answers with the row it finds, which is the same answer
     * the first delivery was given: 001's POST path settles the same shape with
     * {@code ON CONFLICT (source, request_id)}, and a recording that failed here instead would park a
     * command whose register is recorded and active. The two unique keys are told apart rather than
     * both read as contention - {@value #COMMAND_KEY} is this command arriving beside itself and is
     * settled from the row that landed, and only {@code idx_output_active_register_key} is a race
     * for the day's key worth trying again.
     *
     * @throws ConcurrencyFailureException if {@value #RECORD_ATTEMPTS} attempts all lost the race
     *                                     for this key, which is the store answering rather than
     *                                     the store being unreachable: the delivery is handed back
     *                                     and intake keeps running
     * @throws IllegalStateException       if the key already carries more than one active row, if
     *                                     the command already holds an output row no recording
     *                                     wrote, or if the statement recorded nothing
     */
    @Override
    public RecordOutcome record(final DistributionCommand command,
            final CourtRegisterDocument document, final String courtCentreOuCode,
            final String defendantType, final RecordedFlagState flagState) {
        final Recording recording = new Recording(UUID.randomUUID(), command, document,
                objectMapper.writeValueAsString(document), courtCentreOuCode, defendantType,
                flagState);
        RecordOutcome outcome = null;
        DuplicateKeyException lost = null;
        for (int attempt = 0; outcome == null && attempt < RECORD_ATTEMPTS; attempt++) {
            try {
                outcome = insert(recording);
            } catch (DuplicateKeyException collision) {
                if (violates(collision, COMMAND_KEY)) {
                    outcome = recorded(command).orElseThrow(() -> unrecorded(command, collision));
                } else {
                    lost = collision;
                }
            }
        }
        if (outcome == null) {
            throw new ConcurrencyFailureException("the register for this hearing and day was "
                    + "re-recorded by another delivery on each of " + RECORD_ATTEMPTS
                    + " attempts; source=" + command.source() + " requestId=" + command.requestId(),
                    lost);
        }
        return outcome;
    }

    /**
     * One attempt at the recording statement, inside the transaction that undoes a lost race.
     *
     * <p>The transaction is what makes the retry safe rather than what makes the write atomic - one
     * statement is atomic on its own. A re-share that loses the race has already superseded the
     * incumbent by the time its insert is refused, and without a transaction to roll back that
     * supersession would stand: the register the winner replaced would carry the loser's identity in
     * {@code superseded_by}, pointing support at a row that was never recorded.
     *
     * <p>The read that opens it is inside the same transaction, so what it sees and what the write
     * is refused for are one snapshot's worth of the same table.
     */
    private RecordOutcome insert(final Recording recording) {
        return transactions.execute(recorded ->
                recorded(recording.command()).orElseGet(() -> write(recording)));
    }

    /**
     * The register a command already holds, where a delivery of it has been here before.
     *
     * <p>Answered from the row rather than inferred from a refusal, so the redelivery is settled the
     * same way whether it arrived after the first delivery committed or beside it.
     *
     * @param command the request being recorded
     * @return the recording this command already has, or empty where it has none
     * @throws IllegalStateException if the command holds an output row no recording wrote, which is
     *                               a {@code progression-post} row left by an earlier release: its
     *                               register was never recorded and this one cannot be, because the
     *                               command may hold only one output row
     */
    private Optional<RecordOutcome> recorded(final DistributionCommand command) {
        return jdbcClient.sql(RECORDED_REGISTER)
                .param(SOURCE, command.source())
                .param(REQUEST_ID, command.requestId())
                .query((rs, rowNumber) -> recordedBy(command, rs))
                .optional();
    }

    /** The recording a row stands for, and a refusal where the row is not a recording at all. */
    private static RecordOutcome recordedBy(final DistributionCommand command, final ResultSet rs)
            throws SQLException {
        final String status = rs.getString("status");
        if (!RECORDER_STATUSES.contains(status)) {
            throw new IllegalStateException("source=" + command.source() + " requestId="
                    + command.requestId() + " already holds a " + status + " output row, which a "
                    + "submission wrote and no recording may replace");
        }
        return new RecordOutcome(rs.getObject("output_id", UUID.class),
                rs.getObject(SUPERSEDED_OUTPUT_ID, UUID.class));
    }

    /**
     * Whether a refusal is the named unique key's, asked of the driver's own message.
     *
     * <p>The two keys a recording can meet mean opposite things - one delivery of one command twice,
     * and two commands racing for one hearing's day - so which was violated decides whether the
     * attempt is answered or made again. Spring reports both as {@link DuplicateKeyException}; the
     * constraint's name survives only on the cause the driver raised.
     */
    private static boolean violates(final DuplicateKeyException collision, final String key) {
        final Throwable cause = NestedExceptionUtils.getMostSpecificCause(collision);
        return cause.getMessage() != null && cause.getMessage().contains(key);
    }

    /** The state a redelivery cannot be in: refused by the command key, with no row behind it. */
    private static IllegalStateException unrecorded(final DistributionCommand command,
            final DuplicateKeyException collision) {
        return new IllegalStateException("source=" + command.source() + " requestId="
                + command.requestId() + " was refused by " + COMMAND_KEY
                + ", and the row that refused it is not there to be answered with", collision);
    }

    /**
     * The recording statement itself, on a snapshot that has just been read for this command.
     *
     * <p>Issued inside {@link #insert(Recording)}'s transaction and opening none of its own: the
     * read that decided this command has no register yet and the write that gives it one belong to
     * the same transaction, or a redelivery could be answered from a snapshot the write no longer
     * agrees with.
     */
    private RecordOutcome write(final Recording recording) {
        final CourtRegisterDocument document = recording.document();
        final Instant registerTime = instantOf(document.registerDate(), "registerDate");
        final Instant hearingDate = instantOf(document.hearingDate(), "hearingDate");
        return jdbcClient.sql(RECORD_REGISTER)
                .param(OUTPUT_ID, recording.outputId())
                .param(SOURCE, recording.command().source())
                .param(REQUEST_ID, recording.command().requestId())
                .param(COURT_CENTRE_ID, UUID.fromString(document.courtCentreId()))
                .param("courtCentreOuCode", recording.courtCentreOuCode(), Types.VARCHAR)
                .param(REGISTER_DATE, LocalDate.ofInstant(registerTime, LONDON))
                .param("fileName", document.fileName())
                .param("digest", digestOf(recording.json()))
                .param("document", recording.json())
                .param(HEARING_ID, UUID.fromString(document.hearingId()))
                .param("hearingDate", offsetOf(hearingDate))
                .param("courtHouse", courtHouseOf(document))
                .param(REGISTER_TIME, offsetOf(registerTime))
                .param("defendantType", recording.defendantType())
                .param("flagState", recording.flagState().name())
                .query((rs, rowNumber) -> new RecordOutcome(recording.outputId(),
                        rs.getObject(SUPERSEDED_OUTPUT_ID, UUID.class)))
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
            throw new IllegalStateException(BATCH + assembled.batch().batchId() + " was asked for "
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
     * @throws IllegalArgumentException if the mark names no completion mechanism
     * @throws IllegalStateException    if there is no such batch, if the state machine refuses the
     *                                  move, or if the batch changed under the statement
     */
    @Override
    public void markGenerated(final UUID batchId, final UUID documentFileId,
            final Instant generatedAt, final CompletedBy completedBy) {
        if (completedBy == null) {
            throw new IllegalArgumentException(BATCH + batchId + " cannot be marked GENERATED "
                    + "without naming the mechanism that learned it");
        }
        final BatchStatus expected = permitted(batchId, BatchStatus.GENERATED);
        settle(jdbcClient.sql(MARK_GENERATED)
                .param(BATCH_ID, batchId)
                .param(EXPECTED, expected.name())
                .param("documentFileId", documentFileId)
                .param("generatedAt", offsetOf(generatedAt))
                .param("completedBy", name(completedBy), Types.VARCHAR)
                .query(Long.class)
                .single(), batchId, expected, BatchStatus.GENERATED);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the attribution disagrees with the reason: a reason
     *                                  somebody outside this service reported that names no
     *                                  mechanism, or one of this service's own that names one
     * @throws IllegalStateException    if there is no such batch, if the state machine refuses the
     *                                  move, or if the batch changed under the statement
     */
    @Override
    public void markFailed(final UUID batchId, final BatchFailureReason reason,
            final String sdgReason, final CompletedBy completedBy) {
        attributionOf(batchId, reason, completedBy);
        final BatchStatus expected = permitted(batchId, BatchStatus.FAILED);
        settle(jdbcClient.sql(MARK_FAILED)
                .param(BATCH_ID, batchId)
                .param(EXPECTED, expected.name())
                .param("reason", reason.name())
                .param("sdgReason", RegisterBatch.boundedReason(sdgReason), Types.VARCHAR)
                .param("completedBy", name(completedBy), Types.VARCHAR)
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
                    BATCH + batchId + " may not move from " + current + " to " + next);
        }
        return current;
    }

    /**
     * The attribution rule, asked of the reason itself and refused before any statement is issued.
     *
     * <p>Which endings carry a completion mechanism is
     * {@link BatchFailureReason#isGeneratorAttributed()}'s answer and not this class's, so the store
     * and {@code register_batch_completed_by_shape_chk} enforce one rule rather than two that can
     * drift. A refusal here leaves the batch exactly where the caller found it: the reason is
     * examined before the status is even read, so nothing at all was written to be undone.
     *
     * <p>Both messages name the batch and the reason and nothing else. Neither is about a document
     * whose every defendant is a child, so neither can carry a word of one (constitution Principle
     * VII).
     */
    private static void attributionOf(final UUID batchId, final BatchFailureReason reason,
            final CompletedBy completedBy) {
        if (reason.isGeneratorAttributed() && completedBy == null) {
            throw new IllegalArgumentException(BATCH + batchId + " failed " + reason
                    + ", which somebody outside this service reported, and named no mechanism");
        }
        if (!reason.isGeneratorAttributed() && completedBy != null) {
            throw new IllegalArgumentException(BATCH + batchId + " failed " + reason
                    + ", which is this service's own verdict, and named " + completedBy);
        }
    }

    /** The affected-row count is the decision, and a count of nothing is reported rather than kept. */
    private static void settle(final long batches, final UUID batchId, final BatchStatus expected,
            final BatchStatus next) {
        if (batches != ONE_BATCH) {
            throw new IllegalStateException(BATCH + batchId + " was not moved from " + expected
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
     * One register as it will be written, held so that a retry writes the same row again.
     *
     * <p>The identifier above all: the row a retry records is the row the first attempt would have
     * recorded, under the identity the caller is answered with, so a re-share settled on the second
     * attempt is indistinguishable from one that met no race at all.
     */
    private record Recording(UUID outputId, DistributionCommand command,
            CourtRegisterDocument document, String json, String courtCentreOuCode,
            String defendantType, RecordedFlagState flagState) {
    }

    /**
     * The constant name a bounded column holds, and nothing where there is no constant.
     *
     * <p>Typed as VARCHAR at every call site: an untyped {@code null} leaves the driver to guess a
     * type from a parameter it can see nothing about, which Postgres refuses rather than guesses.
     */
    private static String name(final Enum<?> value) {
        return value == null ? null : value.name();
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
