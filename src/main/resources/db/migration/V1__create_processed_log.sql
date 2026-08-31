-- V1 — the processed log.
--
-- Two tables. `processed_request` is the durable memory that makes this service idempotent: one row
-- per (source, request_id), carrying the request's immutable identity, its state, the lifetime
-- attempt count and the single-runner claim. `processed_output` records the one document this flow
-- produces for that request — the court register makes exactly one POST per hearing — and is written
-- before the POST so that what was attempted is on record even when the attempt fails.
--
-- The database clock is the single time authority: `created_at`/`updated_at` default to now(), every
-- update sets `updated_at = now()` in SQL, and claim expiry is written as now() + lease and compared
-- against now() in SQL. No JVM clock reading is ever compared against a stored timestamp, so clock
-- skew between pods cannot grant two runners the same claim.

CREATE TABLE processed_request (
    -- Identity: written once at insert, never updated.
    source                text        NOT NULL,
    request_id            uuid        NOT NULL,
    hearing_id            uuid        NOT NULL,
    hearing_day           date        NOT NULL,
    shared_time           timestamptz NOT NULL,
    event_type            text        NOT NULL,

    -- SHA-256 hex over the canonical form of the immutable fields. The collision comparison: a
    -- delivery whose fingerprint differs from the stored one is dead-lettered and the row left
    -- untouched.
    request_fingerprint   text        NOT NULL,

    status                text        NOT NULL,

    -- Lifetime count of pipeline-run starts, successes included. Incremented in the same statement
    -- that acquires the claim, so a crashed runner can never leave a claim with no attempt recorded.
    -- Never a control variable: retry exhaustion is judged by the broker delivery count.
    attempts              integer     NOT NULL DEFAULT 0,

    -- Which of the five ways the run ended well: `submitted`, or one of the four no-op reasons
    -- (`group-proceedings`, `no-defendants`, `no-subscriptions`, `no-youth-defendants`). Two of
    -- those are this flow's most common outcomes, and telling them apart is the point.
    completion_reason     text,

    -- Bounded reason code plus a sanitised summary. Never raw exception text, never a fragment of
    -- the message body.
    failure_reason        text,

    -- Broker messageId of the delivery that exhausted maxDeliveryCount, written in the same
    -- transaction as status = 'FAILED'. A later delivery under this same identity is re-dead-lettered
    -- without a run; a different identity is a deliberate resubmission and replays.
    exhausted_message_id  text,

    audit_note            text,

    -- The claim triple: written together, cleared together, never independently.
    claim_owner           text,
    claim_token           uuid,
    claim_expires_at      timestamptz,

    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT processed_request_pkey
        PRIMARY KEY (source, request_id),
    CONSTRAINT processed_request_status_chk
        CHECK (status IN ('RECEIVED', 'RETRYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT processed_request_attempts_chk
        CHECK (attempts >= 0),
    CONSTRAINT processed_request_claim_triple_chk
        CHECK ((claim_owner IS NULL) = (claim_expires_at IS NULL)
           AND (claim_owner IS NULL) = (claim_token IS NULL)),
    -- The cheap half of the exhausted-identity rule. The other half — absent unless FAILED — is
    -- enforced by the replay statement clearing it, because a column-level constraint would have to
    -- encode the FAILED -> RECEIVED transition.
    CONSTRAINT processed_request_exhausted_id_chk
        CHECK (status <> 'FAILED' OR exhausted_message_id IS NOT NULL)
);

-- Support query: "was this hearing processed?"
CREATE INDEX processed_request_hearing_idx
    ON processed_request (hearing_id, hearing_day);

CREATE TABLE processed_output (
    -- Application-generated (UUID v4). Deliberately no database default: the row is written by code
    -- that already holds the identifier it will report.
    output_id                 uuid        NOT NULL,

    source                    text        NOT NULL,
    request_id                uuid        NOT NULL,

    -- Descriptive, not key: the court centre the register was assembled for. The OU code is
    -- nullable because the hearing payload may not carry `hearing.courtCentre.code`.
    court_centre_id           uuid        NOT NULL,
    court_centre_ou_code      text,

    -- The register day derived from the command's sharedTime, and the file name built from it.
    -- Both are reported to support and both are descriptive columns, not key columns.
    register_date             date        NOT NULL,
    file_name                 text        NOT NULL,

    status                    text        NOT NULL,

    -- The status line of the POST, recorded when it settles; null until then, because the row is
    -- written before the request is sent.
    response_code             integer,

    -- SHA-256 of exactly the bytes sent, written before the POST and left in place after a failure:
    -- what was attempted is the evidence, and it is what reconciliation and replay diffing read.
    request_digest            text,

    -- Bounded reason-code counts for guarded, non-fatal transformation anomalies — a register that
    -- was produced with a part missing rather than lost whole (e.g.
    -- 'unresolvable-youth-defendant:1,letter-delivery-dropped:2'). Codes and counts only, never free
    -- text and never anything that could name a defendant.
    anomaly_summary           text,

    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT processed_output_pkey
        PRIMARY KEY (output_id),
    CONSTRAINT processed_output_status_chk
        CHECK (status IN ('PENDING', 'POSTED', 'FAILED')),
    -- One output per request: this flow has no fan-out dimension, so the request itself is the key.
    -- Should one ever appear, the constraint widens rather than being rewritten.
    CONSTRAINT processed_output_unique_request
        UNIQUE (source, request_id),
    -- RESTRICT, not CASCADE: the processed log is append-only support evidence, and deleting a
    -- request out from under its output would destroy the record of what was submitted.
    CONSTRAINT processed_output_request_fk
        FOREIGN KEY (source, request_id) REFERENCES processed_request (source, request_id)
        ON DELETE RESTRICT
);
