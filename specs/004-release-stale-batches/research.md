# Research: Release stale in-flight batches before batching

**Feature**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Plan**: [plan.md](plan.md)

Six decisions. Each names what was chosen, why, and what was rejected. Five of them are the spec's
clarifications worked through against the code; the sixth is the one the removal forced.

---

## D1 — The release is the failing statement, not a second call

**Decision**: `BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN` joins
`JdbcRegisterStore.RELEASING_REASONS`, so `markFailed` binds `releaseRows = true` and the batch's
move to FAILED and the unstamping of its registers are one statement.

**Rationale**: The store already has exactly this mechanism, built in 002 for
`PAYLOAD_STORE_UNAVAILABLE` and `ASSEMBLY_FAILED` — the two reasons that say the batch never left
this service. `NOT_COMPLETED_BY_NEXT_RUN` says something adjacent and equally releasable: whatever
happened, nothing is coming, and these registers belong to tonight. Riding the existing branch means
the supersession order, the "only a register may replace a register" rule and the refusal-takes-the-
mark-with-it behaviour are inherited rather than reimplemented, and there is no window in which the
batch is FAILED and its rows are still stamped.

**Alternatives rejected**:
- *`markFailed` then `releaseFailed`, two calls from the pass.* Closer to the words in the feature
  description, but it opens a window: a pass that stopped between them would leave a FAILED batch
  whose registers are still stamped — which is precisely the stranding this increment exists to end,
  reintroduced at a smaller scale. `releaseFailed` also exists for the operator's per-batch decision
  about the reasons that do *not* release, and using it here would blur that.
- *A new `markFailedAndRelease` method on the port.* A second method for a case the first already
  models by its reason.

---

## D2 — `released` counts batches

**Decision**: The run report's `released` is a count of batches, replacing `reconciled` one for one;
`RunReport.reconciled` becomes `RunReport.released` and the log line's `reconciled=` becomes
`released=`.

**Rationale**: It sits on a line whose other counts are batches and registers side by side, and it
replaces a count of batches. How many registers came back is already readable from the same line:
they are in `rows=` and in the row outcomes of the batches the run then assembled, because a released
register is assembled by that same run. A second register count for the release would be the same
hearings counted twice in one line.

**Alternatives rejected**:
- *Count registers.* `markFailed` returns void, so counting registers means either a second read or a
  changed store signature, for a number the line already implies.
- *Carry both.* Two numbers where the arithmetic of the line does not need either.

---

## D3 — The retired vocabulary stays readable and is refused on the write path

**Decision**: `BatchFailureReason.GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER` remain in their
enums, remain in the V2 CHECK constraints, gain an explicit statement that they are retired, and
`JdbcRegisterStore.markFailed` refuses to write either.

**Rationale**: `RegisterBatchRepository` maps `failure_reason` and `completed_by` back into the enums
with `valueOf` (`RegisterBatchRepository:842,846`). Every read of a batch row goes through it,
including the 07:00 exception report's `BATCH_FAILED` read. A row written before this change and
carrying either value would therefore throw on the read that support most needs to work. Removing
them would additionally need a forward-only migration to narrow `register_batch_failure_reason_chk`
and `register_batch_completed_by_chk`, which would refuse nothing that is written any more and break
nothing that is read — a migration whose whole effect is to make old rows illegal. The write-path
refusal is what makes "retired" enforceable rather than aspirational, and it sits at the one gate
every write passes.

**Alternatives rejected**:
- *Delete both.* Costs a narrowing migration and breaks the report's read over history. Nothing is
  deployed to STE yet, so no production row carries either — but the local corpus, the container
  suites and any SIT→STE replay do, and "it is safe because nothing exists yet" stops being true the
  first evening the service runs.
- *Delete them and map unknown strings to `null` on read.* Turns a bounded vocabulary into a silent
  best-effort, which is the disease this service exists to cure.
- *Keep them with no refusal.* Leaves two producible values that nothing is supposed to produce, and
  the first mistake to produce one would look exactly like history.

---

## D4 — `courtregister.generation.completion` is removed, not pinned

**Decision**: The setting, both of its constants and the record component go.
`PublicEventsConfig` subscribes whenever the generation half is enabled, and `PropertiesValidator`'s
"generation enabled requires the broker configuration" rule drops its `completion == event` conjunct.

**Rationale**: `poll-only` meant "do not subscribe to the topic; the reconciler's query will learn
the outcomes". With the query gone it would mean "do not subscribe and never learn an outcome", under
which every batch reaches the next run in flight, is released, and is rendered again — for ever. That
is a worse failure than refusing to start, and it would be invisible: every night would look busy.
The setting also carries a second, quieter cost: it is the only reason
`PropertiesValidator`'s broker rule is conditional, and a conditional rule with one live branch is a
rule that only ever refuses what nobody configures.

**Alternatives rejected**:
- *Keep the key, accept only `event`.* A setting with one legal value reads as a choice and is not
  one; the next person to see it will look for the other branch.
- *Keep `poll-only` meaning "no outcome learning, and fail the batch immediately".* Inventing a new
  meaning for a retired word, for a deployment shape nobody uses.

---

## D5 — The 07:00 report's rendering limit gets its own value

**Decision**: `courtregister.report.batch-generated-within` takes `@DefaultValue("10m")`.
`PropertiesValidator.resolvedBatchGeneratedWithin` and its "a zero generation grace period makes the
unset rendering limit refuse" case are deleted; the ordinary positive-value refusal under the
report's own key stays.

**Rationale**: The borrowing was justified in 003 by the two durations answering the same question —
"how long is too long for a render" — because the grace period *was* the interval after which
something decided a render had not happened. After 004 they answer different questions: the
report's is "when should support be told", the generation half's is "when does a run give up and
re-batch", and the second is now much the longer of the two. Following the rename would move the
report's threshold from ten minutes to thirty as a silent side effect of this increment, which is a
behaviour change to a shipped feature with no requirement behind it.

**Alternatives rejected**:
- *Re-point the resolution at `stale-after`.* Silently triples the report's threshold.
- *Keep the resolution and set `stale-after` to 10m.* Makes the destructive pass run on the shortest
  of the plausible ages, which is the one most likely to fail a render that was about to succeed.

---

## D6 — The three in-flight age readings move to a sweep of their own

**Decision**: A new `batch/BatchAgeSweep` publishes `courtregister_oldest_generating_age`,
`courtregister_oldest_pending_age` and `courtregister_oldest_generated_age` on its own fixed delay
(`courtregister.generation.batch-age-refresh`, `10m`), under no lock, in every non-command JVM that
has the generation half, settling nothing, and keeps the WARN line about batches holding a document
nobody has been told about.

**Rationale**: The retired timer took those readings on its way past; deleting the timer without
replacing them would leave three gauges refreshed once every twenty-four hours, which is not a gauge
and would make the alert the design promised unfireable. The shape is not invented here:
`IntakeAgeSweep` is the same thing for the intake half and the design rules already state its rule —
a gauge describes the JVM that publishes it, so it holds no lock and an alert aggregates the replicas
with `max()`. Splitting the reading from the settling is also what makes both testable: the pass can
be asserted without a meter registry and the sweep without a store that writes.

**Alternatives rejected**:
- *Leave the readings in the pass.* A daily sample, and it would hold the run's own lock while taking
  telemetry.
- *Fold them into `IntakeAgeSweep`.* That sweep runs where the generation half is switched off, and
  would then publish three readings about batches the pod cannot have.
- *Drop the readings.* An observability regression nobody asked for, in an increment whose whole
  argument is that the existing visibility is sufficient.

---

## Facts established while researching (not decisions)

- `RegisterBatchRepository.generatingSince(Instant)` and `.pendingSince(Instant)` already return the
  exact sets the pass needs, oldest first, and are the reads the retired class made. No new query.
- `register_batch_completed_by_shape_chk`'s third arm is an equality between "the reason is one of
  the two generator-attributed ones" and "an attribution is present", so a new unattributed reason
  needs no change to it. Only `register_batch_failure_reason_chk` widens (V6).
- The late-outcome drops are already counted under the bounded reasons `late-acceptance-ignored` and
  `late-failure-ignored` on `courtregister_public_events_ignored_total`. FR-008 needs no new reason,
  only cases that pin the behaviour for a batch failed under the new reason.
- The generation scheduler bean (`SchedulingConfig.GENERATION_SCHEDULER`) is single-threaded and is
  named by both retiring and remaining `@Scheduled` methods. After this increment the nightly run is
  the only method naming it, and `BatchAgeSweep` names it too — a lockless ten-minute reading on the
  run's thread would queue behind a run that is asking for renders, so the sweep names **its own**
  scheduler bean rather than the run's, in the shape `IntakeSweepConfig` already uses.
- `doc/DEFECT-FIXES.md` row `P2` is the only register row that names a test this increment removes.
  No other row references the reconciler, the query or the grace period.
