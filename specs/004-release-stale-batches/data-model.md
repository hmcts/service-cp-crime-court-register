# Data Model: Release stale in-flight batches before batching

**Feature**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Plan**: [plan.md](plan.md)
**Revised**: 2026-09-19 after the two design reviews.

No table is added, no column is added, no column is dropped and no row is migrated. Three CHECK
constraints are rewritten, one bounded statement is added to the store, and two vocabularies change.

## The migration

`src/main/resources/db/migration/V6__stale_batch_release.sql` — additive and forward-only. `V2` is
applied and is never edited; its constraints are replaced by new ones in this migration.

```sql
-- 1. The failure vocabulary: one value in, one value out.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN'));

-- 2. The attribution vocabulary: the mechanism that no longer exists goes.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_completed_by_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_completed_by_chk
    CHECK (completed_by IS NULL OR completed_by = 'EVENT');

-- 3. The shape: the attributed list narrows to the one reason somebody outside this service
--    still reports. The three-arm structure and its COALESCE are unchanged.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_completed_by_shape_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_completed_by_shape_chk
    CHECK ((status NOT IN ('PENDING', 'GENERATING') OR completed_by IS NULL)
       AND (status NOT IN ('GENERATED', 'NOTIFIED', 'PARTIALLY_NOTIFIED', 'NOTIFIED_NOBODY')
                OR completed_by IS NOT NULL)
       AND (status <> 'FAILED'
                OR ((COALESCE(failure_reason, '') = 'GENERATION_FAILED')
                     = (completed_by IS NOT NULL))));
```

**The one operational caveat, and it is the reason this is a decision and not a tidy-up.** A CHECK
constraint cannot be added to a table that already holds a violating row. Statements 1 and 2
therefore **refuse to apply** to any store still holding a batch failed `GENERATION_TIMED_OUT` or
completed by `RECONCILER`. Nothing is deployed, so no environment anybody depends on holds one; a
developer's local volume, a seeded container or a replayed SIT snapshot may, and is cleaned or
recreated before the migration runs. `quickstart.md` says so. Doing this now costs a `docker compose
down -v`; doing it after the first real evening costs a data migration.

**`NOT_COMPLETED_BY_NEXT_RUN` needs no change to statement 3** beyond the narrowing: it is not
generator-attributed, so it falls in the third arm's `false = false` case exactly as the four other
unattributed reasons do, and `attributionOf` is called with `null` for it.

## The store operation

`RegisterStore.failAndReleaseStale(Instant scheduledCutoff, Instant manualCutoff)` →
`List<ReleasedBatch>` (`batchId`, `courtCentreId`, `registerDate`, `releasedRegisters`).

**One statement, in one transaction, whose `WHERE` clause is the staleness rule.** There is no read
followed by a mark, and that is the whole point: a read-then-mark pass can be overtaken between the
two, and then either the mark is refused — which, run inline in `generate()`, throws out of the run
and loses the entire night's generation — or, worse, the mark lands and the separate release does
not, leaving registers stamped to a terminal batch where no later run can see them, because
`activeUnbatched`'s predicate is `batch_id IS NULL`. That is a **lost register**, and it is the exact
failure this increment exists to end.

The predicate:

```sql
WHERE status IN ('PENDING', 'GENERATING')
  AND COALESCE(requested_at, assembled_at)
        <= CASE WHEN system_generated THEN :scheduledCutoff ELSE :manualCutoff END
```

- `COALESCE(requested_at, assembled_at)` is the rule in one expression: a GENERATING batch has a
  `requested_at` and is measured from it; a PENDING one has none and is measured from
  `assembled_at`. The column is `assembled_at` — there is no `created_at` on this table.
- `system_generated` picks the cutoff. A batch the schedule made gets `now - staleAfter`; a batch an
  operator asked for gets `now - max(staleAfter, lockAtMostFor)`, because a manual generation holds
  no run lock and has the whole requesting deadline to work in (FR-017).
- `GENERATED` is not in the list, at any age (FR-002).
- A batch with a null `payload_file_id` **is** included — it is PENDING and it is old, and how far it
  got is not the question. That is the gap the retired reads left open, in which a batch sat in
  flight for ever and deferred its court centre day at every run (FR-020).
- A batch that ceased to match between the operation being asked for and the row being written is
  simply not among the rows returned. Zero rows is an answer, not an error.

The write, in the same statement's scope: `status = 'FAILED'`,
`failure_reason = 'NOT_COMPLETED_BY_NEXT_RUN'`, `completed_by = NULL`, and the **existing** release
and supersession branch of `MARK_FAILED` (the one `:releaseRows` selects) applied to the matched
batches' registers. `NOT_COMPLETED_BY_NEXT_RUN` joins `JdbcRegisterStore.RELEASING_REASONS`, so a
per-batch `markFailed` — which the operations surface may still make — releases on it too.

## Vocabulary

### `BatchFailureReason` — six, one in and one out

| Constant | Produced by | Releases rows | Attributed |
|---|---|---|---|
| `PAYLOAD_STORE_UNAVAILABLE` | the requesting leg | yes | no |
| `ASSEMBLY_FAILED` | the requesting leg | yes | no |
| `RENDER_REQUEST_FAILED` | the requesting leg | no | no |
| `RENDER_REQUEST_REJECTED` | the requesting leg | no | no |
| `GENERATION_FAILED` | the `generation-failed` event | no | **yes** (`EVENT`) |
| `NOT_COMPLETED_BY_NEXT_RUN` | **new** — the stale-batch pass | **yes** | no |
| ~~`GENERATION_TIMED_OUT`~~ | **removed** — nothing produced it after the reconciler went | — | — |

`NOT_COMPLETED_BY_NEXT_RUN` means: *this batch was still waiting for its render when the next
scheduled run began, and had been waiting long enough, so this service stopped waiting and gave its
registers back.* It says nothing about whether systemdocgenerator ever received the request, because
after 004 that is not a question this service can ask — and the ending is the same either way.

`isGeneratorAttributed()` narrows to `GENERATION_FAILED` alone.

### `CompletedBy` — one

`EVENT`. `RECONCILER` is removed with the mechanism it named. The type stays a type rather than
collapsing into a boolean: it is an argument carried through `DocumentOutcomeSink` into the store's
`mark` calls, and a second mechanism is exactly the kind of thing that comes back.

### `ExceptionKind` — six **[review]**

`REQUEST_FAILED`, `REQUEST_LATE`, `BATCH_LATE`, `BATCH_FAILED`, `NOTIFICATION_FAILED`, and new:

- **`BATCH_RELEASED`** — a batch the nightly run gave up on and released. Read over the window like
  the other failure kinds, so it lands in exactly one report. **Informational**: its registers were
  re-rendered the same night, so support is being told what happened, not asked to act. The service
  derives it from the reason: a FAILED batch under `NOT_COMPLETED_BY_NEXT_RUN` is `BATCH_RELEASED`,
  every other FAILED batch is `BATCH_FAILED`. `ExceptionReportService`'s `nameOf(dead)` and its
  "four stages" text are both checked for exhaustiveness in the same task.

*(003's spec says the bounded set is five. That spec is the record of what 003 did and is not
retro-edited; this document is the live statement of the vocabulary.)*

### The ignored-outcome vocabulary **[review]**

`courtregister_public_events_ignored_total{reason}` gains **`terminal-batch`**: an outcome for a batch
the state machine will not move. Today that drop is a WARN and nothing else — the counter fires for a
foreign source, an unknown correlation, a payload mismatch and three envelope faults, and
`late-acceptance-ignored` / `late-failure-ignored` belong to the *notifications* counter and describe
a different thing entirely (a second notifier racing over one recipient). After 004 this drop is the
guarantee that a released batch's late `document-available` does not produce a second e-mail, and a
guarantee that moves no counter is not one you can alert on.

A **redelivery** of such an outcome — which is what happens when the pass wins the race, the sink's
mark is refused, the listener rethrows and the broker offers the message again — is counted under the
same reason, never as an unknown correlation.

## The batch state machine, after

```text
recorded rows, active and unbatched
   ▼ (18:00 run, flag ON)
   ▼ STALE-BATCH RELEASE PASS   ── one statement: every PENDING or GENERATING batch older than
   │                               its cutoff (stale-after for the schedule's own batches, the
   │                               longer of stale-after and the run lock for an operator's)
   │                               ▼ FAILED / NOT_COMPLETED_BY_NEXT_RUN, rows released
   ▼ BatchAssembler groups by (court centre, register date)
   │  a court centre day whose batch is still in flight - and younger than its cutoff, because
   │  the pass has just run - is DEFERRED
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

  any outcome arriving for a batch already past the state it would move to:
      acknowledged, not applied, counted terminal-batch
```

What changed: the last arm of the GENERATING branch, which used to read *"neither, past the grace
period → FAILED, GENERATION_TIMED_OUT (reconciler)"*, decided between runs by a timer that asked
systemdocgenerator. A PENDING batch that never reached the renderer takes the same arm now, where it
used to be asked about separately and failed `RENDER_REQUEST_FAILED` on the strength of a query's
silence — and a PENDING batch that never even minted a payload takes it too, where it used to take
no arm at all.

## Ages and where they are read from

| Batch state | Stamp | Used by |
|---|---|---|
| `PENDING` | `assembled_at` | the pass (via `COALESCE`), `BatchAgeSweep` (`pendingSince`) |
| `GENERATING` | `requested_at` | the pass (via `COALESCE`), `BatchAgeSweep` (`generatingSince`) |
| `GENERATED` | `generated_at` | `BatchAgeSweep` only (`generatedSince`) — never the pass |

Cutoffs are computed once per pass from the injected clock: `scheduledCutoff = now - staleAfter`,
`manualCutoff = now - max(staleAfter, lockAtMostFor)`. A batch is stale when its stamp is **at or
before** its cutoff, so a batch at exactly the minimum age is stale.

## Instruments

| Name | Before | After |
|---|---|---|
| `courtregister_generation_reconciled_total` | outcomes the reconciler fetched | **retired** |
| `courtregister_generation_released_batches_total` | — | **new**: batches a run released |
| `courtregister_generation_released_registers_total` | — | **new**: registers that came back with them |
| `courtregister_oldest_generating_age` | the retired timer | `BatchAgeSweep`, same meaning and cadence |
| `courtregister_oldest_pending_age` | the retired timer | `BatchAgeSweep`, same meaning and cadence |
| `courtregister_oldest_generated_age` | the retired timer | `BatchAgeSweep`, same meaning and cadence |
| `courtregister_batches_total{outcome}` | six failure reasons | six, with the swap |
| `courtregister_public_events_ignored_total{reason}` | six reasons | seven: + `terminal-batch` |

The three gauges are now **per pod**, which they were not before: under the retired timer they were
taken under a lock, so one pod published and the others published nothing. An alert aggregates them
with `max()`, which is the rule the design rules already state for `IntakeAgeSweep`'s gauges. **A
Micrometer gauge never decays** — this is why the sweep exists at all: a gauge whose publisher goes
away does not fall to zero, it holds the last value it was given for ever.

## The run report

`RunReport.reconciled` → `releasedBatches` and `releasedRegisters`; the line's `reconciled=` becomes
`released_batches=` and `released_registers=`. **Neither is a third sum.** The registers counted by
`releasedRegisters` are re-batched by the same run and are therefore already inside `rows=` and the
row outcomes; the two numbers are a diagnostic about what the night had to undo, beside the two
accounts the report already claims add up. `RunReport`'s javadoc says so where it says what does add
up, because a number on a line of totals that is not part of any total has to say so or it will be
added to one.
