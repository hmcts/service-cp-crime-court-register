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
-- Add, backfill, then constrain - deliberately, rather than ADD COLUMN ... DEFAULT. A deployed V1
-- database may already hold rows written in `courtregister.output=progression-post` mode, and four
-- of the ten added columns are NOT NULL, so the values those rows get have to be chosen. Choosing
-- them in the backfill statement below states them once, for the rows that existed; a surviving
-- column default would go on stating them for every row written afterwards, and a default on
-- `document` or on `recorded_flag_state` would let a later writer omit the very fact the column
-- exists to record. No default is left on any of the ten.

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
    -- run report prints, and the free text is never logged at INFO.
    failure_reason        text,
    sdg_reason            text,

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
    -- does not need, so UNKNOWN is a statement the recorder makes rather than an absence.
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

ALTER TABLE processed_output
    ALTER COLUMN document            SET NOT NULL,
    ALTER COLUMN hearing_id          SET NOT NULL,
    ALTER COLUMN hearing_date        SET NOT NULL,
    ALTER COLUMN register_time       SET NOT NULL,
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
