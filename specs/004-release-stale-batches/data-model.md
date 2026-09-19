# Data Model: Release stale in-flight batches before batching

**Feature**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Plan**: [plan.md](plan.md)

No table is added, no column is added, no column is dropped and no row is migrated. One CHECK
constraint is widened by one value. Everything else here is vocabulary and state machine.

## The migration

`src/main/resources/db/migration/V6__batch_failure_reason_not_completed.sql`

```sql
ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED',
                              'GENERATION_TIMED_OUT', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN'));
```

- **Additive and forward-only.** `V2__register_store.sql` is applied and is never edited; the
  constraint is replaced by a wider one in a new migration, which is the only way this repository
  changes an applied schema.
- **`GENERATION_TIMED_OUT` stays in the list.** Rows already carry it. Narrowing the list would
  make a historical row illegal and would refuse nothing that is ever written again (research D3).
- **`register_batch_completed_by_chk` is not touched.** `RECONCILER` stays admissible for the same
  reason, and nothing writes it any more.
- **`register_batch_completed_by_shape_chk` is not touched.** Its third arm is an equality between
  "the reason is `GENERATION_FAILED` or `GENERATION_TIMED_OUT`" and "an attribution is present". A
  new reason that is neither therefore requires `completed_by IS NULL`, which is exactly what the new
  reason requires, with no edit. `SchemaMigrationV2IT` asserts both directions.

## Vocabulary

### `BatchFailureReason` — seven, one of them new and one of them retired

| Constant | Produced by | Releases rows | Attributed |
|---|---|---|---|
| `PAYLOAD_STORE_UNAVAILABLE` | the requesting leg | yes | no |
| `ASSEMBLY_FAILED` | the requesting leg | yes | no |
| `RENDER_REQUEST_FAILED` | the requesting leg | no | no |
| `RENDER_REQUEST_REJECTED` | the requesting leg | no | no |
| `GENERATION_FAILED` | the `generation-failed` event | no | **yes** (`EVENT`) |
| `NOT_COMPLETED_BY_NEXT_RUN` | **new** — the stale-batch pass | **yes** | no |
| `GENERATION_TIMED_OUT` | **nothing, since 004** | no | yes (`RECONCILER`, historical) |

- `NOT_COMPLETED_BY_NEXT_RUN` means: *this batch was still waiting for its render when the next
  scheduled run began, and had been waiting at least the minimum age, so this service stopped waiting
  and gave its registers back.* It says nothing about whether systemdocgenerator ever received the
  request, because after 004 that is not a question this service can ask — and the ending is the same
  either way.
- It **releases**, which is what makes tonight's assembly include its registers. It joins
  `JdbcRegisterStore.RELEASING_REASONS`; the release happens in the same statement as the mark
  (research D1).
- It is **not generator-attributed**: nobody outside this service reported anything, so
  `completed_by` is `NULL`. `BatchFailureReason.isGeneratorAttributed()` is unchanged and already
  answers `false` for it.
- `GENERATION_TIMED_OUT` gains `isRetired()` (true for it alone). It remains
  generator-attributed, because the historical rows that carry it carry `RECONCILER` beside it and
  the shape constraint pairs them.

### `CompletedBy` — two, one of them retired

`EVENT` is what every completion names from 004 onwards. `RECONCILER` is retained so that rows
written before 004 still read (`RegisterBatchRepository` maps the column through `valueOf`), and is
marked retired. Nothing writes it.

### The write-path refusal

`JdbcRegisterStore.markFailed` refuses, before it issues any statement:

- a `reason` that `isRetired()`, and
- a `completedBy` of `RECONCILER`.

Both are `IllegalArgumentException`, beside the existing attribution check, and both are held by
`RegisterStoreIT`. This is what makes "retired" a rule rather than a comment: the enums and the
schema stay wide so history reads, and the one gate every write passes stays narrow.

## The batch state machine, after

```text
recorded rows, active and unbatched
   ▼ (18:00 run, flag ON)
   ▼ STALE-BATCH RELEASE PASS   ── every PENDING or GENERATING batch at least
   │                               courtregister.generation.stale-after old
   │                               ▼ FAILED / NOT_COMPLETED_BY_NEXT_RUN, rows released
   ▼ BatchAssembler groups by (court centre, register date)
   │  a court centre day whose batch is still in flight - and younger than the minimum
   │  age, because the pass has just run - is DEFERRED
   ▼ PENDING            the batch exists and holds its rows
   ▼ assemble payload → PayloadFileStore  ⇒ FAILED/ASSEMBLY_FAILED
   │                                        or FAILED/PAYLOAD_STORE_UNAVAILABLE
   ▼ mint ids, then request the render (ids before calls, always)
   ▼ GENERATING         systemdocgenerator accepted (202)
   │     ├─ refused / undeliverable ─▶ FAILED, RENDER_REQUEST_REJECTED / RENDER_REQUEST_FAILED
   │     ├─ document-available (public.event) ─▶ GENERATED
   │     ├─ generation-failed  (public.event) ─▶ FAILED, GENERATION_FAILED (+ sdg_reason)
   │     └─ neither, and still so at the next run ─▶ FAILED, NOT_COMPLETED_BY_NEXT_RUN,
   │                                                 rows released into that run's batches
   ▼ GENERATED          the PDF exists in the file service
   ▼ notify every matched Youth Offending Team, once each
   ├─ all accepted ──────────────────▶ NOTIFIED
   ├─ some accepted ─────────────────▶ PARTIALLY_NOTIFIED   (the rest are resendable)
   └─ nobody to tell ────────────────▶ NOTIFIED_NOBODY
```

What changed: the last arm of the GENERATING branch. It used to read *"neither, past the grace
period → FAILED, GENERATION_TIMED_OUT (reconciler)"*, decided between runs by a timer that asked
systemdocgenerator. It now reads *"neither, and still so at the next run"*, decided by the next run
itself, with the rows released rather than stranded. A PENDING batch that never reached the renderer
takes the same arm for the same reason, where it used to be asked about separately and failed
`RENDER_REQUEST_FAILED` on the strength of a query's silence.

**GENERATED is on no arm of this pass.** It holds a document somebody is owed e-mails about; the
sweep names it and settles nothing, and the resend paths are what owe it.

## Ages and where they are read from

| Batch state | Stamp the age is measured from | Read |
|---|---|---|
| `PENDING` | `assembled_at` | `RegisterBatchRepository.pendingSince(cutoff)` |
| `GENERATING` | `requested_at` | `RegisterBatchRepository.generatingSince(cutoff)` |
| `GENERATED` | `generated_at` | `RegisterBatchRepository.generatedSince(cutoff)` — **sweep only**, never the pass |

- The cutoff is `clock.instant().minus(staleAfter)`, read at the moment the pass runs and not carried
  from anywhere. A batch is stale when its stamp is **at or before** the cutoff, so a batch exactly
  at the minimum age is stale (US2.3) — which is what `...Since(cutoff)` already means, so the
  boundary is inherited rather than re-decided.
- All three reads return oldest first, which is what makes "every batch is its own attempt" fair:
  the court centres that have waited longest are attempted first.
- No new read, no new index. These are the three reads the retired class already made.

## Instruments

| Name | Before | After |
|---|---|---|
| `courtregister_generation_reconciled_total` | outcomes the reconciler fetched | **renamed** `courtregister_generation_released_total`: batches a run failed `NOT_COMPLETED_BY_NEXT_RUN` and released |
| `courtregister_oldest_generating_age` | published by the retired timer | published by `BatchAgeSweep`, same meaning, same cadence |
| `courtregister_oldest_pending_age` | published by the retired timer | published by `BatchAgeSweep`, same meaning, same cadence |
| `courtregister_oldest_generated_age` | published by the retired timer | published by `BatchAgeSweep`, same meaning, same cadence |
| `courtregister_batches_total{outcome}` | six failure reasons possible | seven; `NOT_COMPLETED_BY_NEXT_RUN` is a bounded label value like the rest |
| `courtregister_public_events_ignored_total{reason}` | unchanged | unchanged — `late-acceptance-ignored` and `late-failure-ignored` already cover the late outcome for a released batch |

The three gauges are **per pod** and an alert aggregates them with `max()`, which is the rule the
design rules already state for `IntakeAgeSweep`'s gauges and which now applies to these for the first
time — under the retired timer they were taken under a lock, so one pod published and the others
published zero, which is the shape that makes an aggregation lie.

## The run report

`RunReport.reconciled` → `RunReport.released`, same type (`int`), same position, same meaning-shape
(a count of batches this run settled about earlier nights). The line's `reconciled=` becomes
`released=`. Nothing else on the line moves: the batch outcomes, the row outcomes, the deferred
counts, the snapshot and the duration are unchanged, and the two sums the report claims still hold.
