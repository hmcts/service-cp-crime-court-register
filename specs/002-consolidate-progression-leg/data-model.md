# Data Model: Consolidate the progression court-register leg

The 001 processed log (`processed_request`, `processed_output`) is unchanged in its role; this file
records what 002 adds. All migrations are Flyway, service-owned Postgres, `V2__register_store.sql`.
The second datasource (framework file service) is **not** migrated by this service — its DDL is
vendored under `contracts/fileservice/` for tests only.

## `processed_output` — becomes the register store

Existing (V1): `output_id PK`, `(source, request_id)` FK → `processed_request` ON DELETE RESTRICT,
`court_centre_id`, `court_centre_ou_code`, `register_date`, `file_name`, `request_digest`, `status`,
`response_code`, `sent_at`, `UNIQUE (source, request_id)`.

V2 adds:

| Column | Type | Notes |
|---|---|---|
| `document` | `jsonb NOT NULL` | The validated `CourtRegisterDocument` as recorded (schema v17.103.13); `request_digest` is now its SHA-256 |
| `hearing_id` | `uuid NOT NULL` | From the document |
| `hearing_date` | `timestamptz NOT NULL` | From the document |
| `court_house` | `text` | `hearingVenue.courtHouse` (as progression's column) |
| `register_time` | `timestamptz NOT NULL` | The document's `registerDate` instant (progression's `register_time`); `register_date` stays the London date part |
| `defendant_type` | `text` | `Applicant` / `Appellant` / `Respondent` / null (no court application) |
| `batch_id` | `uuid` FK → `register_batch` | NULL until assembled |
| `superseded_at` | `timestamptz` | Set in the recording transaction that superseded this row |
| `superseded_by` | `uuid` FK → `processed_output(output_id)` | The newer row |
| `recorded_flag_state` | `text NOT NULL` | `ON` / `OFF` / `UNKNOWN` — the flag as last read when recorded |
| `status` | widened | `RECORDED` → `GENERATED` → `NOTIFIED`; `SUPERSEDED`; `FAILED` (001's `PENDING`/`POSTED` remain valid for `progression-post` mode) |

Indexes: `idx_output_active_unbatched ON processed_output (court_centre_id, register_date) WHERE
status = 'RECORDED' AND superseded_at IS NULL AND batch_id IS NULL`; `idx_output_hearing ON
processed_output (hearing_id)`.

Invariants (asserted by `RegisterStoreIT`):
- At most one **active** row (RECORDED, unsuperseded) per `(hearing_id, court_centre_id, register_date)`.
- A row with a `batch_id` is never superseded and never edited except by `mark*` for its own batch.
- `superseded_by` points to a row with the same `hearing_id` and batch key and a later `register_time`.

Enforcement: the "at most one active row" invariant is asserted by `RegisterStoreIT` but not yet
enforced by the database; two concurrent re-shares of one hearing could both insert. V3 (task T024a,
Phase 3) adds a partial unique index on `(hearing_id, court_centre_id, register_date) WHERE status =
'RECORDED' AND superseded_at IS NULL AND batch_id IS NULL`, and `RegisterStore.record` handles the
unique violation by re-reading and superseding.

## `register_batch`

| Column | Type | Notes |
|---|---|---|
| `batch_id` | `uuid PK` | Minted at assembly; sent to SDG as `sourceCorrelationId` |
| `court_centre_id` | `uuid NOT NULL` | |
| `court_centre_ou_code` | `text` | |
| `court_house` | `text` | |
| `register_date` | `date NOT NULL` | |
| `file_name` | `text NOT NULL` | First record's `fileName` (as progression) |
| `payload_file_id` | `uuid` | Minted **before** the file-service insert; sent as `payloadFileServiceId` |
| `document_file_id` | `uuid` | From `document-available` (`documentFileServiceId`) or the query API |
| `status` | `text NOT NULL` | `PENDING` → `GENERATING` → `GENERATED` → `NOTIFIED` \| `PARTIALLY_NOTIFIED` \| `NOTIFIED_NOBODY` \| `FAILED` |
| `failure_reason` | `text` | `PAYLOAD_STORE_UNAVAILABLE` \| `RENDER_REQUEST_FAILED` \| `RENDER_REQUEST_REJECTED` \| `GENERATION_FAILED` \| `GENERATION_TIMED_OUT` \| `ASSEMBLY_FAILED` |
| `sdg_reason` | `text` | SDG's `reason` from `generation-failed` / query (bounded length, never logged at INFO) |
| `system_generated` | `boolean NOT NULL` | true from the schedule, false from the CLI (progression's flag) |
| `completed_by` | `text` | `EVENT` \| `RECONCILER` — feeds the `reconciled` metric |
| `assembled_at`, `requested_at`, `generated_at`, `notified_at`, `failed_at` | `timestamptz` | |
| `attempts` | `int NOT NULL DEFAULT 0` | Lifetime tally, never a control variable |

Constraint: `UNIQUE (court_centre_id, register_date) WHERE status <> 'FAILED'` (partial unique) — one
live batch per key; a FAILED batch may be re-assembled (new `batch_id`, rows re-stamped).

**Open design question (before T043/T050).** A same-day re-share recorded after that day's batch is
GENERATED or NOTIFIED becomes a fresh active unbatched row whose key already has a live batch, so the
partial unique constraint above blocks a second batch for that key. The options are (a) permit a
second live batch once the first is terminal, (b) a supplementary batch attached to the first, or
(c) surface such rows via list-batches for CLI generation. The decision is recorded in the design as
Q27 and is needed before `BatchAssembler` (T043) and the job (T050).

## `register_notification`

| Column | Type | Notes |
|---|---|---|
| `notification_id` | `uuid PK` | Sent to NN as `notificationId`; minted **before** the POST; reused on retry |
| `batch_id` | `uuid NOT NULL` FK → `register_batch` | |
| `email_address` | `text NOT NULL` | `recipient.emailAddress1` |
| `recipient_name` | `text` | `recipient.recipientName` → `personalisation.yotsName` |
| `template_name` | `text NOT NULL` | `cr_standard` |
| `template_id` | `uuid NOT NULL` | Resolved at startup |
| `status` | `text NOT NULL` | `PENDING` → `ACCEPTED` \| `FAILED` |
| `response_code` | `int` | |
| `sent_at` | `timestamptz` | |
| `attempts` | `int NOT NULL DEFAULT 0` | |

Constraint: `UNIQUE (batch_id, email_address)`.

## `shedlock`

Standard ShedLock JDBC schema: `name varchar(64) PK`, `lock_until timestamptz`, `locked_at
timestamptz`, `locked_by varchar(255)`.

## State machines

**Per command** (unchanged from 001 except the last leg):
`RECEIVED → … → COMPLETED{recorded | group-proceedings | no-defendants | no-subscriptions |
no-youth-defendants} | FAILED{SCHEMA_INVALID | …}`. The output row is written RECORDED in the same
transaction that completes the command.

**Per batch**:

```
PENDING ──payload stored + 202──▶ GENERATING ──document-available──▶ GENERATED ──all 202──▶ NOTIFIED
   │                                 │                                  │             ├─ some FAILED ──▶ PARTIALLY_NOTIFIED
   │                                 ├─ generation-failed ──▶ FAILED    │             └─ no recipients ▶ NOTIFIED_NOBODY
   │                                 └─ grace period → reconcile → GENERATED | FAILED(GENERATION_FAILED | GENERATION_TIMED_OUT)
   ├─ store unavailable ──▶ FAILED(PAYLOAD_STORE_UNAVAILABLE)   [rows stay RECORDED; next run re-assembles]
   └─ request rejected / exhausted ──▶ FAILED(RENDER_REQUEST_*)
PARTIALLY_NOTIFIED ──notify-register --batch (resend FAILED only)──▶ NOTIFIED
FAILED ──generate-register --batch (new batch_id)──▶ PENDING
```

Rows follow their batch: `RECORDED → GENERATED` on `document-available` (**only this batch's rows**),
`GENERATED → NOTIFIED` when the batch reaches NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY; a
FAILED batch leaves its rows RECORDED with `batch_id` cleared on re-assembly.

**Per run**: `STARTED → FLAG_READ{ON | OFF | UNREADABLE} → (SKIPPED | ASSEMBLING → REQUESTING →
DONE)`, recorded as the run report (log + gauges), not as a table.

## Entities the pipeline adds

- `RegisterRecord` (a `processed_output` row view: id, hearing, key, document, recipients,
  `defendantType`, flag state).
- `CourtCentreDay(court_centre_id, register_date)` — the batch key.
- `RenderRequest(payloadFileId, batchId, templateIdentifier, conversionFormat, originatingSource)`.
- `DocumentStatus` (from the query API: `documentFileServiceId?`, `generatedTime?`, `failedTime?`,
  `reason?`).
- `FlagDecision` — `ON | OFF | UNREADABLE(reason)`.
- `RunReport` — counts per outcome, flag decision, duration, reconciled count.

## File-service tables (not owned; vendored DDL for tests)

From `contracts/fileservice/` (changesets 001–006): `metadata(file_id uuid PK, metadata jsonb)`;
`content(file_id uuid PK, content bytea, deleted boolean default false,
deleted_at timestamp with time zone)`. The timestamp column is named `deleted_at`, not
`date_deleted`: only the changeset file (`006-add-date-deleted-column-to-content-table.xml`) carries
the older name, and the column it adds is `deleted_at`.
This service issues exactly:

```sql
INSERT INTO metadata(metadata, file_id) VALUES (to_json(?::json), ?);
INSERT INTO content(file_id, content, deleted) VALUES (?, ?, false);
```

with metadata `{"fileName": …, "conversionFormat": "pdf", "templateName": "OEE_Layout5",
"numberOfPages": 1, "fileSize": <bytes>}`. No reads, no updates, no deletes (role permits INSERT only).
