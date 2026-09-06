# Data Model: Consolidate the progression court-register leg

The 001 processed log (`processed_request`, `processed_output`) is unchanged in its role; this file
records what 002 adds. All migrations are Flyway, service-owned Postgres: `V2__register_store.sql`
and `V3__active_row_unique.sql`. The second datasource (framework file service) is **not** migrated
by this service — its DDL is vendored under `contracts/fileservice/` for tests only.

## `processed_output` — becomes the register store

Existing (V1): `output_id PK`, `(source, request_id)` FK → `processed_request` ON DELETE RESTRICT,
`court_centre_id`, `court_centre_ou_code`, `register_date`, `file_name`, `request_digest`, `status`,
`response_code`, `sent_at`, `UNIQUE (source, request_id)`.

V2 adds:

| Column | Type | Notes |
|---|---|---|
| `document` | `jsonb` | The `CourtRegisterDocument` as recorded, validated **as it is written** against the vendored `courtRegisterDocumentRequest.json` (schema v17.103.13) - the register-document schema rather than the `add-court-register` command's, because the recorded document carries `defendantType` and the command declares no such field; `request_digest` is now its SHA-256. Nullable in the column, required of a recorded row by the shape check below |
| `hearing_id` | `uuid` | From the document; same nullability rule |
| `hearing_date` | `timestamptz` | From the document; same nullability rule |
| `court_house` | `text` | `hearingVenue.courtHouse` (as progression's column) |
| `register_time` | `timestamptz` | The document's `registerDate` instant (progression's `register_time`); `register_date` stays the London date part. Same nullability rule |
| `defendant_type` | `text` | `Applicant` / `Appellant` / `Respondent` / null (no court application) |
| `batch_id` | `uuid` FK → `register_batch` | NULL until assembled |
| `superseded_at` | `timestamptz` | Set in the recording transaction that superseded this row |
| `superseded_by` | `uuid` FK → `processed_output(output_id)` | The newer row |
| `recorded_flag_state` | `text NOT NULL DEFAULT 'UNKNOWN'` | `ON` / `OFF` / `UNKNOWN` — the flag as last read when recorded. The default is what an unread flag means, so a writer that omits the column states something true |
| `status` | widened | `RECORDED` → `GENERATED` → `NOTIFIED`; `SUPERSEDED`; `FAILED` (001's `PENDING`/`POSTED` remain valid for `progression-post` mode) |

Check: `processed_output_recorded_shape_chk CHECK (status NOT IN ('RECORDED','GENERATED','NOTIFIED',
'SUPERSEDED') OR (document IS NOT NULL AND hearing_id IS NOT NULL AND hearing_date IS NOT NULL AND
register_time IS NOT NULL))` — the four register columns are required of every row the recorder
writes and of no other.

**Rollout (expand now, contract later).** The four register columns are deliberately nullable rather
than `NOT NULL`. Flyway runs deferred, at the new pod's startup, so for the length of a rolling
deployment a pod on the previous release is still serving the queue against a schema that has
already moved, and it writes the old `progression-post` shape: no `document`, no `hearing_id`, no
`hearing_date`, no `register_time`. `NOT NULL` on those columns would fail every one of those
inserts and lose every register in flight until the rollout completed. The shape check carries the
invariant instead, and binds only the four statuses the recorder produces. A later **contract**
migration may tighten the four to `NOT NULL` and drop the check, once no pre-002 release can still
be running. `recorded_flag_state` needs no such treatment: a default is safe there because `UNKNOWN`
is exactly what a row written without reading the flag means.

Indexes: `idx_output_active_unbatched ON processed_output (court_centre_id, register_date) WHERE
status = 'RECORDED' AND superseded_at IS NULL AND batch_id IS NULL`; `idx_output_hearing ON
processed_output (hearing_id)`; and V3's `idx_output_active_register_key` below.

Invariants (asserted by `RegisterStoreIT`):
- At most one **active** row (RECORDED, unsuperseded) per `(hearing_id, court_centre_id, register_date)`.
- A row with a `batch_id` is never superseded and never edited except by `mark*` for its own batch.
- `superseded_by` points to a row with the same `hearing_id` and batch key and a later `register_time`.

**Enforcement of "at most one active row" (V3).** The recorder keeps the invariant by reading the
hearing's active row and superseding what it finds, and a read is what two re-shares of one hearing
can both do before either has committed: both find the same incumbent, both supersede it and both
insert an active register (`RegisterStoreIT.two_concurrent_re_shares_leave_exactly_one_active_row`).
`V3__active_row_unique.sql` therefore adds `idx_output_active_register_key UNIQUE ON
processed_output (hearing_id, court_centre_id, register_date) WHERE status = 'RECORDED' AND
superseded_at IS NULL AND batch_id IS NULL` - the same predicate the sweep and the recorder read
"active" with, so a superseded row, a batched row and 001's PENDING/POSTED rows are all outside it
(`SchemaMigrationV3IT`). `JdbcRegisterStore.recordAndComplete` meets the refusal as a
`DuplicateKeyException`, rolls the attempt back, re-reads the incumbent the winner left and records
against that instead, up to three attempts; a losing re-share is therefore recorded rather than
failed, and only a key that lost the race three times over raises a `ConcurrencyFailureException`
(the store answering: the delivery is handed back, intake keeps running). V1's
`processed_output_unique_request` is a different refusal wearing the same exception type and is told
apart from it on the driver's own message: that one is this command delivered again, and is answered
from the row it already wrote rather than retried as a race. Both keys are recognised by name and
neither by elimination: a duplicate-key refusal that is neither of them is a constraint the recorder
cannot act on, and it is raised as a classified non-transient `RegisterNotRecordedException` rather
than retried (`RegisterStoreIT.a_unique_violation_that_is_not_the_active_row_race_is_propagated_not_retried`). The recording statement supersedes **before** it
inserts, because the row being replaced holds the key until the update takes it out of the index.

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
| `sdg_reason` | `varchar(512)` | SDG's `reason` from `generation-failed` / query, never logged at INFO. Bounded before the write by `RegisterBatch.boundedReason`: a longer message is stored as its first 500 characters plus the marker ` [truncated]`, so the row is exactly 512 and a reader can tell there is more |
| `system_generated` | `boolean NOT NULL` | true from the schedule, false from the CLI (progression's flag) |
| `completed_by` | `text` | `EVENT` \| `RECONCILER`, feeds the `reconciled` metric. Written by the `mark` that learned the outcome, in that mark's own statement: a batch state change is a compare-and-set, so there is no moment either side of the transition in which this could be set on its own. NOT NULL on `GENERATED` and on the three notified states it is reached through; on `FAILED`, set exactly for the two generator-attributed reasons (`GENERATION_FAILED`, `GENERATION_TIMED_OUT`, which is `BatchFailureReason.isGeneratorAttributed()`) and NULL for the other four, which are this service's own verdict about a render nobody outside it answered for |
| `assembled_at`, `requested_at`, `generated_at`, `notified_at`, `failed_at` | `timestamptz` | |
| `attempts` | `int NOT NULL DEFAULT 0` | Lifetime tally, never a control variable |
| `supplement_of` | `uuid` FK → `register_batch(batch_id)` | The batch this one follows for the same key; NULL on a day's first batch |
| `supplement_index` | `int NOT NULL DEFAULT 0` | 0 on a day's first batch, counting up from 1 on each supplementary one; the file name is built from it |

Constraint: `UNIQUE (court_centre_id, register_date) WHERE status IN ('PENDING','GENERATING',
'GENERATED')` (partial unique) — one **in-flight** batch per key, those being the three states in
which a batch is still owed something (a render request, a render outcome, an e-mail). The four
terminal states are alike: a FAILED batch may be re-assembled (new `batch_id`, rows re-stamped), and
a NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY one may be followed by a supplementary batch
(below).

Check: `register_batch_completed_by_shape_chk`, the `completed_by` rule above as a shape the row
keeps, for the writers that do not go through the store (the CLI's whole-row write): NULL on
`PENDING` / `GENERATING`, which are the states that have no outcome for a mechanism to have learned;
NOT NULL on `GENERATED` / `NOTIFIED` / `PARTIALLY_NOTIFIED` / `NOTIFIED_NOBODY`; and on `FAILED`
present exactly when `failure_reason` is one of the two generator-attributed reasons. Written as
three implications rather than a disjunction with an "everything else" arm, so all seven states are
covered and none is covered by omission; a status outside the vocabulary is
`register_batch_status_chk`'s refusal to report. `JdbcRegisterStore` refuses a
contradictory `markGenerated` or `markFailed` before it issues a statement and
`RegisterBatchRepository` refuses an attribution on a batch that has not finished, so the same rule
is enforced twice and stated once (`BatchFailureReason.isGeneratorAttributed()`).

**Supplementary batches for late re-shares (design Q27, decided 2026-09-06).** A same-day re-share
recorded after its (court centre, register date) batch is terminal becomes a **supplementary batch
for the same key**, assembled by the next run once **every** earlier batch for that key is terminal.
The supplementary batch names the batch it follows in `supplement_of` and carries the next
`supplement_index` (1 for the first supplement, counting up); a day's first batch has `supplement_of`
NULL and `supplement_index` 0. While any batch for the key is still in flight the rows simply wait,
because the narrowed partial unique index above admits one PENDING / GENERATING / GENERATED batch per
key and no more.

A supplementary batch's **file name** is the first row's `fileName` with `-supplementary-<index>`
inserted before the extension - `courtregister_2026-08-20.json` becomes
`courtregister_2026-08-20-supplementary-1.json`. `BatchAssembler` (T043) builds it; the schema only
records the index it is built from.

The alternatives Q27 weighed were a second unrelated live batch for the key, which records nothing
about why a day has two documents, and surfacing such rows through list-batches for CLI generation,
which makes a routine re-share an operator's job. The link keeps the day's documents ordered and
attributable, and leaves the nightly job able to send them without being asked.

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
no-youth-defendants} | FAILED{SCHEMA_INVALID | …}`. The output row is **written RECORDED in the
same transaction that completes the command**. The completion is a second statement against
`processed_request` and it belongs to `IdempotencyGuard`, not to the store, so the pipeline hands it
to `RegisterStore.recordAndComplete` as the thing to do inside the recording's transaction and the
adapter issues it there, through a client over the same datasource: neither write survives without
the other (`RegisterStoreIT.a_completion_that_could_not_be_written_takes_the_recording_with_it`,
`…a_completion_the_guard_refused_takes_the_recording_with_it`, and
`CrashWindowIT.a_crash_between_the_register_and_its_completion_should_leave_neither`). A completion
the guard refuses - the claim was reclaimed while the run worked - rolls the recording back for the
same reason it would be wrong to keep it: the new owner records the register again, and this one
would only be superseded.

There is therefore no window between the register and its command's completion. A delivery can still
stop *after* both, before the broker learns the message was settled, and the message is delivered
again; the guard answers most of those `ALREADY_COMPLETED` without reaching the store, and the
recording is **idempotent on `(source, request_id)`** for the rest - each attempt reads that key
inside the recording transaction and answers a command it has already recorded with the row it
wrote, the row that recording superseded included, writing nothing and superseding nothing
(`RegisterStoreIT.a_redelivered_command_is_answered_with_the_register_it_already_recorded` and
`…a_redelivered_re_share_supersedes_nothing_a_second_time`). It is the property 001's POST path gets
from `ON CONFLICT (source, request_id)`, kept rather than lost.

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
- `RegisterDocumentValidator` - the port the core asks before the write, served by a second
  `OutboundContractValidator` instance over `courtRegisterDocumentRequest.json`. The document
  the transformation validated is not the document that is stored: `defendantType` is attached
  after the command check, and since 002 the stored document is what the batch reads back and
  what the PDF payload is built from, so the final document is held to the schema that
  describes it.
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
