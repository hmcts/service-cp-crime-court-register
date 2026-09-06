-- V2 - the register store.
--
-- V1 recorded what was POSTed to progression. V2 makes `processed_output` the register itself: the
-- validated CourtRegisterDocument as recorded, the hearing facts progression kept in its own
-- `court_register_request` table, the batch the row was assembled into, the supersession pair that
-- replaces progression's read-side `max(register_time)` sweep, and the cutover flag as it stood when
-- the row was written. Three tables arrive beside it - `register_batch`, `register_notification`
-- and `shedlock` - and the per-batch facts progression repeated on every request row now live once,
-- on the batch.
--
-- `processed_output` is widened rather than replaced by a fresh `register_record` table: the
-- cardinality is already 0..1 per command, so a second table would be a second truth about the same
-- command, and the `(source, request_id)` foreign-key chain and `request_digest` - now the SHA-256
-- of the stored document rather than of the bytes posted - are what the 001 differential audit
-- reads.
--
-- Add, backfill, then constrain what can safely be constrained - and no more. Two different writers
-- have to survive this migration. A deployed V1 database may already hold rows written in
-- `courtregister.output=progression-post` mode, and the backfill below is what gives those rows the
-- values they never had. But the previous release also goes on *writing* that shape after V2 has
-- run: Flyway here is deferred to the new pod's startup, so for the length of a rolling deployment
-- the old pod is still serving the queue against a schema that has already moved. Four of the ten
-- added columns are therefore left NULLABLE, and the shape the recorder writes is required instead
-- by `processed_output_recorded_shape_chk`, which binds only the statuses this service's recorder
-- produces. PENDING, POSTED and FAILED are outside it, which is exactly the previous release's
-- insert.
--
-- `recorded_flag_state` is the one exception and keeps NOT NULL, because the only value a default
-- could choose is the value the backfill already chooses: UNKNOWN, which is true of any row written
-- without reading the flag and is therefore honest for an old pod's insert as well as for a V1 row.
-- The recorder always states its own answer, so the default is never what a register is recorded
-- under. No other default is left on any of the ten.
--
-- This is the expand half of an expand/contract pair. A later migration may tighten the four to NOT
-- NULL and drop the check, once no release that writes the old shape can still be running.

-- One batch: the registers for one court centre on one register day, rendered as one PDF and
-- e-mailed to that court centre's Youth Offending Teams. The batch_id is minted at assembly and
-- sent to systemdocgenerator as `sourceCorrelationId`, so it is also the correlation key every
-- outcome - public event or reconciler query - comes back on.
CREATE TABLE register_batch (
    -- Application-minted at assembly, like `processed_output.output_id` and for the same reason:
    -- the code that writes the row already holds the identifier it will correlate on.
    batch_id              uuid        NOT NULL,

    -- The key. `court_house` and the OU code are descriptive copies taken from the batch's records
    -- for the file name and the render payload, and are nullable because a hearing venue may carry
    -- neither.
    court_centre_id       uuid        NOT NULL,
    court_centre_ou_code  text,
    court_house           text,
    register_date         date        NOT NULL,

    -- The first record's `fileName`, as progression built it.
    file_name             text        NOT NULL,

    -- Minted before the file-service insert and sent as `payloadFileServiceId`; the document id
    -- arrives later, with `document-available` or with the query API, and is null until a document
    -- exists.
    payload_file_id       uuid,
    document_file_id      uuid,

    status                text        NOT NULL,

    -- This service's bounded code for why the batch ended without a document. Kept apart from
    -- `sdg_reason`, which is systemdocgenerator's own words about a document whose every defendant
    -- is a child: the bounded code is what the batches counter labels its outcome with and what the
    -- run report prints, and the free text is never logged at INFO. Bounded at 512 characters
    -- because it is the one column here holding words this service did not author.
    failure_reason        text,
    sdg_reason            varchar(512),

    -- progression's own flag: true from the nightly schedule, false from the operations CLI.
    system_generated      boolean     NOT NULL,

    -- Which mechanism learned the outcome. Recorded rather than inferred: a run whose outcomes all
    -- arrive by RECONCILER is a broker or a subscription to look at, and nothing else would say so.
    completed_by          text,

    -- Five stamps rather than a generic `updated_at`: each names the event it records, and the
    -- batch's timeline is exactly the questions support asks of it.
    assembled_at          timestamptz,
    requested_at          timestamptz,
    generated_at          timestamptz,
    notified_at           timestamptz,
    failed_at             timestamptz,

    -- Lifetime tally of generation attempts. Never a control variable: the run deadline decides
    -- when to stop trying, exactly as the broker delivery count does on the intake half.
    attempts              integer     NOT NULL DEFAULT 0,

    CONSTRAINT register_batch_pkey
        PRIMARY KEY (batch_id),
    CONSTRAINT register_batch_status_chk
        CHECK (status IN ('PENDING', 'GENERATING', 'GENERATED', 'NOTIFIED', 'PARTIALLY_NOTIFIED',
                          'NOTIFIED_NOBODY', 'FAILED')),
    CONSTRAINT register_batch_failure_reason_chk
        CHECK (failure_reason IS NULL
            OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                                  'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED',
                                  'GENERATION_TIMED_OUT', 'ASSEMBLY_FAILED')),
    CONSTRAINT register_batch_completed_by_chk
        CHECK (completed_by IS NULL OR completed_by IN ('EVENT', 'RECONCILER')),
    -- Which endings carry an attribution, as a shape the row itself keeps. A batch that reached
    -- GENERATED - or one of the three notified states, which it can only reach through GENERATED -
    -- was completed by a mechanism somebody can name, and a FAILED batch names one exactly when the
    -- reason is the one somebody outside this service reported: GENERATION_FAILED, the renderer's
    -- own verdict, and GENERATION_TIMED_OUT, the reconciler's verdict about its silence. The other
    -- four reasons are this service's own verdict about a render it could not ask for or could not
    -- hear about, and naming a mechanism on one of them credits a decision nobody made. The store
    -- refuses such a mark before it issues a statement (BatchFailureReason.isGeneratorAttributed),
    -- and this is the same rule where the writers that do not go through the store - the operations
    -- CLI's whole-row write, and whatever is written next - cannot get past it either.
    --
    -- The two states that are still in flight say so themselves rather than being left to a
    -- catch-all: PENDING is a batch nothing has been asked of the renderer for and GENERATING is one
    -- whose answer has not come back, so on neither of them is there an outcome for any mechanism to
    -- have learned. A row that named one would have the reconciler chasing a batch its own row says
    -- was already reported, and the reconciled metric counting an outcome nobody delivered.
    --
    -- Three implications rather than a disjunction with an "everything else" arm. Each states what
    -- the column must be for one family of states and says nothing about the others, so all seven
    -- states of register_batch_status_chk are covered and none is covered by omission. A status that
    -- is not a status at all - a typo, a state from a release that never shipped - is
    -- register_batch_status_chk's refusal to report and not this one's, and leaving it there is what
    -- makes the error a writer sees name the vocabulary rather than the attribution. Widening the
    -- vocabulary is therefore a migration that widens this constraint in the same breath, which
    -- SchemaMigrationV2IT pins by reading BatchStatus against both.
    --
    -- COALESCE rather than a bare IN: a FAILED row with no reason at all would otherwise leave the
    -- comparison NULL, and a CHECK that evaluates to NULL is a CHECK that passes.
    CONSTRAINT register_batch_completed_by_shape_chk
        CHECK ((status NOT IN ('PENDING', 'GENERATING')
                    OR completed_by IS NULL)
           AND (status NOT IN ('GENERATED', 'NOTIFIED', 'PARTIALLY_NOTIFIED', 'NOTIFIED_NOBODY')
                    OR completed_by IS NOT NULL)
           AND (status <> 'FAILED'
                    OR ((COALESCE(failure_reason, '')
                              IN ('GENERATION_FAILED', 'GENERATION_TIMED_OUT'))
                         = (completed_by IS NOT NULL)))),
    CONSTRAINT register_batch_attempts_chk
        CHECK (attempts >= 0)
);

-- One live batch per (court centre, register day). Partial rather than a plain unique constraint
-- because a FAILED batch is re-assemblable under a new batch_id, and a total constraint would make
-- the first failure permanent for that day.
CREATE UNIQUE INDEX idx_register_batch_live_key
    ON register_batch (court_centre_id, register_date)
    WHERE status <> 'FAILED';

-- One row per recipient of one batch: the de-duplicated union of the batch's subscribers, so a
-- Youth Offending Team on ten of the day's hearings is told once (defect fix P4).
CREATE TABLE register_notification (
    -- Minted and persisted before the POST and reused on every retry: notificationnotify keys its
    -- aggregate on this identifier, so a fresh one would send a second e-mail.
    notification_id  uuid        NOT NULL,

    batch_id         uuid        NOT NULL,

    -- Personal data, and this row is the only place it may live. The name is optional because a
    -- subscription may carry an address and nothing else.
    email_address    text        NOT NULL,
    recipient_name   text,

    -- Resolved at startup and refused there when blank or malformed (defect fix P9), so a row can
    -- never record an e-mail sent under a template nobody can name.
    template_name    text        NOT NULL,
    template_id      uuid        NOT NULL,

    -- PENDING is written before the POST, so an attempt that was never answered is a row saying so
    -- rather than an absence indistinguishable from one that was never made.
    status           text        NOT NULL,

    response_code    integer,
    sent_at          timestamptz,
    attempts         integer     NOT NULL DEFAULT 0,

    CONSTRAINT register_notification_pkey
        PRIMARY KEY (notification_id),
    CONSTRAINT register_notification_status_chk
        CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED')),
    CONSTRAINT register_notification_attempts_chk
        CHECK (attempts >= 0),
    -- The persistence half of P4: the union is computed once at assembly, and the database refuses
    -- to hold a second attempt at the same address for the same batch.
    CONSTRAINT register_notification_unique_recipient
        UNIQUE (batch_id, email_address),
    CONSTRAINT register_notification_batch_fk
        FOREIGN KEY (batch_id) REFERENCES register_batch (batch_id)
);

-- ShedLock's standard JDBC schema, written verbatim: JdbcTemplateLockProvider issues its own SQL
-- against these four columns, and any deviation is a lock the library cannot take - which is two
-- pods running the nightly job.
CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL,

    CONSTRAINT shedlock_pkey
        PRIMARY KEY (name)
);

-- `processed_output` becomes the register store.
ALTER TABLE processed_output
    -- The validated CourtRegisterDocument itself, not a reference to one: after V2 this row is the
    -- only place the register exists, and `request_digest` is its SHA-256.
    ADD COLUMN document            jsonb,

    -- The hearing facts progression carried on its own table, read from the document.
    ADD COLUMN hearing_id          uuid,
    ADD COLUMN hearing_date        timestamptz,
    ADD COLUMN court_house         text,

    -- The document's `registerDate` instant - progression's `register_time` - beside the London
    -- date part, which stays the batch key. The instant is what supersession orders rows by.
    ADD COLUMN register_time       timestamptz,

    -- Applicant / Appellant / Respondent, or null where the hearing has no court application.
    ADD COLUMN defendant_type      text,

    -- Null until the row is assembled into a batch.
    ADD COLUMN batch_id            uuid,

    -- Written together inside the recording transaction that superseded the row, so both are absent
    -- on a row that is still the active one for its key.
    ADD COLUMN superseded_at       timestamptz,
    ADD COLUMN superseded_by       uuid,

    -- The cutover flag as last read when the row was recorded. Recording never waits on a read it
    -- does not need, so UNKNOWN is a statement the recorder makes rather than an absence - and the
    -- same statement is what the default below says on behalf of a writer that never read the flag
    -- at all.
    ADD COLUMN recorded_flag_state text;

-- The backfill for rows a deployed V1 database may already hold. Every `processed_output` row has a
-- parent request - the foreign key says so - so the join reaches all of them.
--
-- Those rows recorded a POST to progression, not a register document, and the values below say
-- exactly that: an empty JSON object no reader can mistake for a CourtRegisterDocument (which
-- always carries at least a `documentType`), the hearing day the request was received under taken
-- at its London start, the instant the output row was written as the nearest thing such a row has
-- to a register instant, and UNKNOWN for a flag that was never read because the flag did not exist
-- when the row was written. On such a row `request_digest` remains the digest of the bytes posted;
-- only rows written by the recorder carry a digest of `document`.
UPDATE processed_output o
   SET document            = '{}'::jsonb,
       hearing_id          = r.hearing_id,
       hearing_date        = r.hearing_day::timestamp AT TIME ZONE 'Europe/London',
       register_time       = o.created_at,
       recorded_flag_state = 'UNKNOWN'
  FROM processed_request r
 WHERE r.source = o.source
   AND r.request_id = o.request_id;

-- The one column the rollout lets us constrain outright, for the reason given at the top of this
-- file: UNKNOWN is what an unread flag means, so a writer that omits the column gets a true answer
-- rather than a guess. The default goes on before the NOT NULL so that no insert can fall between
-- the two.
ALTER TABLE processed_output
    ALTER COLUMN recorded_flag_state SET DEFAULT 'UNKNOWN';

ALTER TABLE processed_output
    ALTER COLUMN recorded_flag_state SET NOT NULL;

-- RECORDED -> GENERATED -> NOTIFIED with SUPERSEDED and FAILED beside them. PENDING and POSTED stay
-- valid rather than being migrated away: `courtregister.output=progression-post` still writes them,
-- and the 001 rows that carry them are the differential audit's evidence.
ALTER TABLE processed_output
    DROP CONSTRAINT processed_output_status_chk;

ALTER TABLE processed_output
    ADD CONSTRAINT processed_output_status_chk
        CHECK (status IN ('RECORDED', 'GENERATED', 'NOTIFIED', 'SUPERSEDED', 'FAILED',
                          'PENDING', 'POSTED')),
    ADD CONSTRAINT processed_output_flag_state_chk
        CHECK (recorded_flag_state IN ('ON', 'OFF', 'UNKNOWN')),
    -- The invariant the four nullable register columns would otherwise have lost. A row in any of
    -- the four states the recorder writes carries the register it recorded; a row in one of the
    -- three states a POST writes is not about a register at all and is left alone, which is what
    -- keeps the previous release's insert legal for the length of a rolling deployment.
    ADD CONSTRAINT processed_output_recorded_shape_chk
        CHECK (status NOT IN ('RECORDED', 'GENERATED', 'NOTIFIED', 'SUPERSEDED')
            OR (document IS NOT NULL
            AND hearing_id IS NOT NULL
            AND hearing_date IS NOT NULL
            AND register_time IS NOT NULL)),
    -- The batch is minted and persisted before anything is asked of another system, so a row can
    -- only ever be stamped with a batch this service already recorded.
    ADD CONSTRAINT processed_output_batch_fk
        FOREIGN KEY (batch_id) REFERENCES register_batch (batch_id),
    -- The newer row for the same hearing and batch key. Self-referential because supersession is a
    -- relationship between two registers, not a state one of them holds alone.
    ADD CONSTRAINT processed_output_superseded_by_fk
        FOREIGN KEY (superseded_by) REFERENCES processed_output (output_id);

-- The nightly job's only read: the active, unbatched rows for one court centre and register day.
-- Partial rather than plain because the sweep is interested in a vanishing fraction of a table that
-- grows by every hearing ever resulted, and a full scan of an unindexed register table is what
-- defect P8 cost.
CREATE INDEX idx_output_active_unbatched
    ON processed_output (court_centre_id, register_date)
    WHERE status = 'RECORDED' AND superseded_at IS NULL AND batch_id IS NULL;

-- Support read: "what was recorded for this hearing, and which of those rows is current?"
CREATE INDEX idx_output_hearing
    ON processed_output (hearing_id);
