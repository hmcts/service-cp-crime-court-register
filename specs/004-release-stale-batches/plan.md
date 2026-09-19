# Implementation Plan: Release stale in-flight batches before batching

**Branch**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-release-stale-batches/spec.md`

## Summary

A batch that is still waiting for its render when the next nightly run begins, and has been waiting
at least `courtregister.generation.stale-after` (default **30m**), is failed
`BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN` and its registers are released in the same statement,
so the run's own assembly puts them in a batch tonight. The pass is the first thing the run does
after the flag gate and before `store.activeUnbatched()`, and it is the whole of the new behaviour.

Everything else in this increment is **removal**. `GenerationReconciler` goes, and with it its
`@Scheduled`/`@SchedulerLock` timer, its lock name, its cadence read from the grace period, the
end-of-run `tally.chased(reconciler.reconcile())`, and the entire systemdocgenerator query path:
`DocumentRenderer.query`, `SystemDocGeneratorClient.query` (the `GET document/{payloadFileId}` call
and its answer parsing), `StubDocumentRenderer.query` and `domain/DocumentStatus`. The
`courtregister.generation.completion` setting goes with them, because `poll-only` named a way of
learning an outcome that no longer exists (FR-013). What replaces `GenerationReconciler` is two
smaller classes that split what it conflated:

- **`batch/StaleBatchReleaser`** — the pass. Two reads (`pendingSince`, `generatingSince`), one
  `store.markFailed(..., NOT_COMPLETED_BY_NEXT_RUN, null, null)` per stale batch, a count returned to
  the run. No schedule, no lock of its own, no HTTP client, no renderer. Called by
  `RegisterGenerationJob` and by nothing else.
- **`batch/BatchAgeSweep`** — the three readings the retired timer used to take on its way past:
  `courtregister_oldest_generating_age`, `courtregister_oldest_pending_age` and
  `courtregister_oldest_generated_age`, refreshed on its own fixed delay, in every non-command JVM
  that has the generation half, under **no** lock, settling **nothing**, on a `TaskScheduler` of its
  own declared by a new `config/BatchSweepConfig`. It is `IntakeAgeSweep`'s
  shape exactly, for `IntakeAgeSweep`'s reason: a gauge describes the JVM that publishes it, and a
  reading taken once a night is not a reading (FR-011). It also keeps the "a batch holds a document
  nobody was told about" WARN the retired pass wrote, which was a report and never an ending.

The run report's `reconciled` becomes `released`, counting batches as `reconciled` did, and the
`courtregister_generation_reconciled_total` counter becomes
`courtregister_generation_released_total`. `courtregister.generation.grace-period` becomes
`courtregister.generation.stale-after` and its default moves from `10m` to `30m`;
`courtregister.report.batch-generated-within` stops resolving from it and takes its own
`@DefaultValue("10m")`, so the 07:00 report's threshold does not move as a side effect
(clarification 5).

Two values are **retired but kept readable**: `BatchFailureReason.GENERATION_TIMED_OUT` and
`CompletedBy.RECONCILER`. `RegisterBatchRepository` maps both columns back through `valueOf`, and the
07:00 report reads FAILED batches every morning, so narrowing either vocabulary would make a read of
a row written before this change throw. They stay in the enums and in the V2 check constraints, gain
an `isRetired()` statement of their own, and `JdbcRegisterStore` refuses to write either again
(FR-012). One additive migration, `V6__batch_failure_reason_not_completed.sql`, widens
`register_batch_failure_reason_chk` for the new reason and narrows nothing.

Nothing about the register document, the inbound message, the file service, notificationnotify or
the App Configuration flag changes. One consumed contract stops being *called* — systemdocgenerator's
query endpoint — which is not a contract change and needs nobody's agreement.

Design authority: the design owner's decision of 2026-09-19, recorded in the spec's Context with its
four grounds and its one trade.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (Gradle wrapper; unchanged from 001–003).

**Primary Dependencies**: **none added, and one use removed.** ShedLock loses a lock (the run keeps
its own); Spring's `@Scheduled` loses a trigger and gains one (`BatchAgeSweep`'s fixed delay, read as
a placeholder exactly as `IntakeAgeSweep` reads `courtregister.intake.gauge-refresh`); `RestClient`
loses a call. Micrometer, `JdbcClient`, Jackson and the rest are untouched.

**Storage**: PostgreSQL 16. One additive, forward-only migration,
`V6__batch_failure_reason_not_completed.sql`, which widens one CHECK constraint and adds no column,
no table and no index. `register_batch_completed_by_shape_chk` is **not** changed: the new reason is
not generator-attributed, so it falls in that constraint's third arm exactly as the four existing
unattributed reasons do, and the arm is already written as an equality rather than a list.

**Testing**: as 002 and 003. New and changed suites: `StaleBatchReleaserTest` (unit, Mockito+AssertJ)
and `BatchAgeSweepTest` (unit, `SimpleMeterRegistry`) replacing `GenerationReconcilerTest`;
`RegisterGenerationJobTest` gains the ordering, the boundary and the run-line cases;
`DocumentOutcomeSinkTest` gains the late-outcome-for-a-released-batch cases;
`SystemDocGeneratorClientTest`, `StubGenerationAdaptersTest`, `GenerationWiringContextTest`,
`GenerationFailureEndToEndIT`, `support/GenerationLegs`, `support/GenerationStackSupport` and
`support/GeneratedRegisters` lose their query wiring; `SchemaMigrationV2IT` and `RegisterStoreIT`
gain the new reason and the write-path refusals; `ConfigurationValidationTest` and
`ReportPropertiesTest` follow the rename and the un-borrowing; `BatchFailureReasonTest` and
`BatchStateTest` follow the widened enum; `TelemetryPrivacyTest` drives the two new classes;
`CliModeConfigTest` and `ReportSchedulingConfigTest` follow the schedule count from four to four (one
out, one in). One new end-to-end case, in `GenerationFailureEndToEndIT`, carries SC-001 and SC-003:
a batch left GENERATING overnight is released, re-batched, rendered and notified once, and the
original batch's late `document-available` moves nothing.

**Target Platform**: AKS, port 4550 (local 8082). After this change the generation half carries
**one** ShedLock lock (`register-generation`) and **one** cron, plus one lockless fixed delay on a
scheduler of its own; the context therefore holds four `TaskScheduler` beans where it held three, and
still four `@Scheduled` methods where it held four — the reconciliation timer out, the batch-age
sweep in. The report half is untouched.

**Performance Goals**: the pass adds two indexed reads and at most one statement per stale batch to
the front of a run whose budget is sixty minutes. On a healthy estate it finds nothing. Its worst
case is bounded by how many court centre days can be in flight at once, which is bounded by the
number of court centres, and each batch is its own attempt so one refusal does not cost the rest.
SC-004 (zero calls to systemdocgenerator between runs) is met by construction once the query path is
deleted: there is no code left that could make one.

**Constraints**: constitution v3.2.0 — ids before calls (unchanged; this pass writes no id); one
explicit settlement (not in play; this increment settles no message); bounded reason codes and
bounded metric labels; `runId` on every line of a scheduled run (Principle VII), which both new
classes take through `RunCorrelation` — the releaser **adopts** the run's ambient correlation because
it is called from inside the run and is part of it, and the sweep **opens** its own because it is a
unit of work in its own right; no PII; no throwable this service did not write attached to a line;
Flyway additive and forward-only; no AI attribution; TDD red-run convention.

**Scale/Scope**: five user stories, sixteen functional requirements, eight success criteria. Out of
scope per the spec: the event path, the notification leg, supersession, the register document, the
intake half, the 07:00 report's own logic, the cutover lever, historical-row migration, and any REST
surface.

### Configuration (this increment)

| Property | Before | After | Note |
|---|---|---|---|
| `courtregister.generation.grace-period` | `10m` | **renamed** `courtregister.generation.stale-after`, default `30m` | How long a batch may be in flight before the next run gives up on it. `PropertiesValidator`'s positive-value refusal is kept verbatim under the new key name (FR-010). The old key is **removed, not aliased**: a deployment that still sets it would otherwise read as configured while the value went nowhere, and nothing is deployed yet, so there is nobody to break. |
| `courtregister.generation.completion` | `event` \| `poll-only` | **removed** | `poll-only` named "learn outcomes from the query API", which no longer exists (FR-013). `PublicEventsConfig` subscribes whenever the generation half is enabled; `PropertiesValidator`'s existing "generation enabled requires the broker configuration" rule drops its `completion == event` conjunct and applies unconditionally. `GenerationProperties.COMPLETION_EVENT` / `COMPLETION_POLL_ONLY` and the record component go with it. |
| `courtregister.report.batch-generated-within` | unset, resolved to `generation.grace-period` | own `@DefaultValue("10m")` | Clarification 5. `PropertiesValidator.resolvedBatchGeneratedWithin` and the case that refuses an unset limit because the *generation* value was zero both go; the ordinary positive-value refusal under the report's own key stays. `application.yaml`'s long comment at the `courtregister.report` block explaining the borrowing is replaced by the key itself with its own comment. |
| `courtregister.generation.batch-age-refresh` | — | **new**, `10m` | `BatchAgeSweep`'s fixed delay, read as a placeholder by `@Scheduled(fixedDelayString = ...)` **and** bound on `GenerationProperties`, exactly as `courtregister.intake.gauge-refresh` is written twice for the same reason: a placeholder cannot see a record's `@DefaultValue`. `10m` keeps the readings as current as the retired timer made them (SC-006). Refused at start-up if not positive. |

Three `application.yaml` comment sites move with the rename: the intake block's line 92 explanation
of what it does *not* borrow, the notification block's line 336 contrast with "the reconciler's
grace-period above", and the report block's line 500 paragraph about resolving the rendering limit.
None of the three is a value; all three are wording that would otherwise describe machinery that no
longer exists (FR-016).

## Constitution Check

*GATE: evaluated against constitution v3.2.0 (2026-09-15). Re-check after Phase 1 design; record any
verdict that moves.*

| # | Principle | Verdict for this increment |
|---|---|---|
| I | Defect-Fix-First with Characterised Legacy | **PASS.** Neither oracle had a reconciler, a query or a stale-release pass: the function app is the intake half's oracle and never saw a batch, and progression's leg had **no render timeout at all**, so a batch whose event was lost sat in `GENERATED`-less limbo there too. Removing the timeout moves *towards* the oracle, not away from it. **No `doc/DEFECT-FIXES.md` row is added, and no row's claim changes.** One row is touched in one cell: `P2`'s pinning-test list names `GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out` for the half of its promise that covers "neither event arrived", and that test is removed with the class. The cell is re-pointed at `StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`, which holds the same promise afterwards — a render nobody reported reaches an explicit recorded failure instead of silence — and the row's narrative gains one dated sentence saying the mechanism changed in 004. `RegisteredDefectFixes` and `DifferentialAuditTest` stay green; the differential audit is over the **intake** half and this increment touches none of it. |
| II | Test-Driven Development | **PASS.** Every task in the Phase-2 list is a red/green pair or a documented exception kind. The red-run convention applies unchanged: a test task lands its compile-safe seams so the recorded red run is a failing assertion. **A subtractive increment needs the convention stated for deletion**, and this plan states it: a task that removes behaviour lands, *first*, the test that asserts the behaviour is gone (no bean of that type on the context, no such method on the port, no request to that path, no such key bound), watches it fail against the code that still has it, and only then deletes. "The suite still compiles" is not a red run, and a deletion whose only evidence is a green suite is a deletion nobody tested. |
| III | Message-Contract First | **PASS.** **No contract changes.** The inbound `distribution-command.schema.json`, the register document schemas, the file-service changesets, the notificationnotify body and the App Configuration flag are all untouched. systemdocgenerator's `generate-document` command and its two public events are untouched; this service simply **stops calling** its query endpoint, which is the service exercising less of somebody else's contract and is not a cross-team event. The `CourtRegisterService` flag gains no new reader: the pass is inside the run and behind the same single gate read (FR-005). |
| IV | Canonical JSON In, Typed Models Out | **PASS.** `DocumentStatus` — the one type that modelled somebody else's JSON answer — is deleted. Nothing else in this increment parses anything. The new reason is a bounded enum constant and the released count is an `int`. |
| V | SOLID with Ports and Adapters | **PASS, and the port surface shrinks.** `DocumentRenderer` goes from two methods to one, which is the port finally naming one capability: "ask for a document". The estate's port count is unchanged at fourteen; no port is added. `StaleBatchReleaser` holds a repository, the store, the metrics, a `Duration` and a `Clock` — no driver, no broker client, no HTTP client — and `BatchAgeSweep` holds a repository, the metrics and a clock and no lock, which is exactly what the design rules already permit `IntakeAgeSweep`. The one duration each takes arrives as a `Duration` rather than as the whole `GenerationProperties`, for the reason the retired class already gave. `RegisterGenerationJob`'s constructor swaps one collaborator for another and its arity does not grow. |
| VI | Explicit Failure — Nothing Is Ever Swallowed | **PASS.** The pass catches nothing it does not classify. A batch that cannot be failed is logged by class at WARN and the pass moves to the next — the same "every batch is its own attempt" rule the retired class carried, and for the same reason: the read returns oldest first, so a refusal that ended the pass would cost the court centres that have waited longest. A read that fails outright is not absorbed: it leaves the pass, the run reports what it had done and rethrows, which is the run's existing behaviour for a store that goes away. `BatchAgeSweep` takes the design rules' **one absorbed refusal** clause verbatim, as `IntakeAgeSweep` does: a reading that cannot be taken keeps the gauge's last value, is counted, is said at WARN by class, and does not cancel the fixed delay — it is telemetry, and telemetry may not cost a Youth Offending Team its e-mail. Nothing is retried on either path, and a released batch is not re-requested: it is re-rendered because its registers are assemblable again. |
| VII | Privacy in Telemetry | **PASS.** Every new line names a batch by id, a count, and a bounded reason. No court centre id, no register date, no address and no defendant datum reaches a line or a label. The new metric label value `NOT_COMPLETED_BY_NEXT_RUN` is drawn from the closed enumeration the batches counter already labels by. `runId` is on every line: the releaser adopts the run's ambient correlation (it is part of that run) and the sweep opens one of its own (it is not). No throwable this service did not write is attached anywhere; a caught failure is named by class. `TelemetryPrivacyTest`'s `GenerationLegs` drive covers `StaleBatchReleaser` and `BatchAgeSweep` in place of `GenerationReconciler`. |
| VIII | Estate Conventions | **PASS.** Gradle, no Maven; PMD and Checkstyle over main and test; the JaCoCo ratchet stays at **0.88 line / 0.85 branch** and is **not** loosened — a subtractive increment that removed covered code and left the ratchet where it was is the one shape in which coverage can silently fall, so the gate is re-run and quoted, not adjusted. Package root unchanged; Conventional Commits with no AI attribution; branch `004-release-stale-batches` merging to `main` the way 002 and 003 did. |

**Post-design re-check (after Phase 1)**: no verdict moved. The one thing Phase 1 sharpened is
Principle I's evidence: the P2 cell edit is the only `doc/DEFECT-FIXES.md` change in the increment,
it is named in a task of its own, and the task lands it in the same commit as the test it points at.

## Project Structure

### Documentation (this feature)

```text
specs/004-release-stale-batches/
├── spec.md
├── plan.md              # This file
├── research.md          # The six decisions, with what was rejected and why
├── data-model.md        # V6, the new reason, the two retired values, the state machine after
├── quickstart.md        # The local walkthrough that proves the release and the re-batch
├── contracts/
│   └── README.md        # Nothing vendored and nothing changed; what stops being called
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase-ordered TDD task list (/speckit-tasks)
```

### Source Code — additions, changes and deletions

```text
src/main/java/uk/gov/hmcts/cp/courtregister/
├── application/
│   ├── DocumentRenderer.java              # CHANGED: query() removed; one method left
│   └── (DocumentOutcomeSink, DocumentOutcomeSinkImpl unchanged in behaviour; javadoc
│        loses its "the reconciler fetches the ones that did not" half)
├── batch/
│   ├── GenerationReconciler.java          # DELETED
│   ├── StaleBatchReleaser.java            # NEW: the pass
│   ├── BatchAgeSweep.java                 # NEW: the three readings, lockless, own fixed delay
│   └── RegisterGenerationJob.java         # CHANGED: releaser first, no end-of-run chase,
│                                          #          released= on the line
├── adapter/
│   ├── systemdocgenerator/
│   │   └── SystemDocGeneratorClient.java  # CHANGED: query() and its answer parsing removed
│   └── stub/
│       └── StubDocumentRenderer.java      # CHANGED: query() removed
├── domain/
│   ├── DocumentStatus.java                # DELETED
│   ├── BatchFailureReason.java            # CHANGED: + NOT_COMPLETED_BY_NEXT_RUN,
│   │                                      #          GENERATION_TIMED_OUT marked retired
│   ├── CompletedBy.java                   # CHANGED: RECONCILER marked retired
│   └── RunReport.java                     # CHANGED: reconciled -> released
├── persistence/
│   ├── JdbcRegisterStore.java             # CHANGED: new reason joins RELEASING_REASONS;
│   │                                      #          markFailed refuses a retired value
│   └── RegisterBatchRepository.java       # UNCHANGED (its valueOf reads are why the two
│                                          #            retired values stay in the enums)
├── config/
│   ├── GenerationProperties.java          # CHANGED: gracePeriod -> staleAfter (30m),
│   │                                      #          + batchAgeRefresh (10m), - completion
│   ├── GenerationConfig.java              # CHANGED: reconciler bean -> releaser + sweep beans
│   ├── SchedulingConfig.java              # CHANGED: job's collaborator swap; scheduler constant
│   │                                      #          and the single-threaded bean unchanged
│   ├── PublicEventsConfig.java            # CHANGED: subscribes on generation.enabled alone
│   ├── PropertiesValidator.java           # CHANGED: rename, completion rule, un-borrowing,
│   │                                      #          new refresh refusal
│   ├── GenerationMetrics.java             # CHANGED: reconciled -> released counter
│   ├── BatchSweepConfig.java              # NEW: the sweep's own TaskScheduler bean and its
│   │                                      #      BATCH_SWEEP_SCHEDULER constant, in the shape
│   │                                      #      IntakeSweepConfig already uses
│   └── ReportProperties.java              # CHANGED: batchGeneratedWithin gains its own default
└── resources/
    ├── application.yaml                   # CHANGED: the four sites above
    └── db/migration/
        └── V6__batch_failure_reason_not_completed.sql   # NEW, additive
```

Tests mirror the same list; the file-by-file test matrix is below and the task list names each one.

## Design Decisions (summary; full rationale in research.md)

1. **The pass is a class, not a method on the job.** `RegisterGenerationJob` already holds the flag
   gate, the store, the assembler, the service and the metrics; a sixth responsibility inside it
   would be untestable without the whole run. The releaser is one object with one method returning a
   count, which is what the run needs from it.
2. **The release is the failing statement, not a second call** (clarification 1). The new reason
   joins `JdbcRegisterStore.RELEASING_REASONS`, so `markFailed` carries `releaseRows = true` and the
   batch and its rows move in one statement, under the supersession order the two existing releasing
   reasons already use.
3. **PENDING and GENERATING are one rule, not two.** The retired pass distinguished them because the
   query answered differently about each. With no query there is nothing to distinguish: both mean
   "this did not complete before the next run", and one reason says so. This also removes the
   `RENDER_REQUEST_FAILED`-for-an-unknown-payload ending, which was a verdict derived from a query's
   silence.
4. **`GENERATED` is never touched.** It holds a document somebody is owed e-mails about; the retired
   pass reported it and settled nothing, and the sweep keeps exactly that (FR-002, US2.4).
5. **The gauges survive the timer** (FR-011). Split out rather than left in the pass, because a pass
   that runs once a night cannot carry a reading and the run's own lock has no business being held
   for telemetry.
6. **The retired vocabulary stays readable and is refused on the write path** (clarification 3).
   Enforced at `JdbcRegisterStore.markFailed`, which every write goes through, and asserted by a
   `RegisterStoreIT` case rather than by a comment.

## Test Matrix

| Area | Suite | Kind | What it holds |
|---|---|---|---|
| The pass | `batch/StaleBatchReleaserTest` (new) | U | FR-001/002/003: a GENERATING batch past the age is failed `NOT_COMPLETED_BY_NEXT_RUN` with a null attribution and released; a PENDING one likewise; a batch at exactly the age is stale (US2.3); one under the age is untouched (US2.1); a GENERATED batch is untouched at any age (US2.4); the count returned is batches; one batch that refuses leaves the rest attempted (US1.4); the cutoff is read from the injected clock at the moment of the pass. **P2's re-pointed pinning test lives here.** |
| The readings | `batch/BatchAgeSweepTest` (new) | U | FR-011: the three gauges over a `SimpleMeterRegistry`, zero where there is none, the WARN-and-count for the parked batches, the absorbed read refusal that keeps the last value and does not cancel the delay, the fixed-delay attribute read as the new key's placeholder, and no lock annotation on the method (a reflection case, because an annotation nobody asserts is one a later edit adds). |
| The run | `batch/RegisterGenerationJobTest` (extended) | U | FR-001 ordering (the releaser is called after the gate and **before** `activeUnbatched`, asserted with an `InOrder`); FR-005 (a skipped run calls it not at all); FR-009 (`released=` on the line, zero when none, and no `reconciled=` anywhere in it); US1.3 (released registers reach the assembler in the same run); a releaser that throws still produces a line and rethrows. |
| The late outcome | `application/DocumentOutcomeSinkTest` (extended) | U | FR-008/US3: `document-available` and `generation-failed` for a batch FAILED under the new reason each move nothing, notify nobody, and count under `LATE_ACCEPTANCE_IGNORED` / `LATE_FAILURE_IGNORED`. |
| The store | `persistence/RegisterStoreIT` (extended) | IT | The new reason releases its rows in the same statement as the mark; supersession decides where a re-share exists (US1.5); `markFailed` refuses `GENERATION_TIMED_OUT` and refuses a `CompletedBy.RECONCILER` attribution (FR-012); a row already carrying either still reads back. |
| The schema | `persistence/SchemaMigrationV2IT` (extended) | IT | V6 admits the new reason, still admits the two retired values, and the shape constraint still refuses an attribution on it. |
| The enums | `domain/BatchFailureReasonTest`, `domain/BatchStateTest` (extended) | U | Seven reasons, the new one not generator-attributed and marked releasing; the retired set is exactly `{GENERATION_TIMED_OUT}`; the schema's vocabulary and the enum agree in both directions. |
| The port | `adapter/systemdocgenerator/SystemDocGeneratorClientTest`, `adapter/stub/StubGenerationAdaptersTest` (both reduced) | U/W | FR-006: the client offers one method; WireMock records **no** request to `document/{id}`; the stub has no query to be asked. |
| The wiring | `config/GenerationWiringContextTest`, `config/CliModeConfigTest`, `config/ReportSchedulingConfigTest` (extended) | U | No `GenerationReconciler` bean on any context; a `StaleBatchReleaser` and a `BatchAgeSweep` where the generation half is on and neither on a command JVM; the generation half carries one `@SchedulerLock` and one cron; `PublicEventsConfig` subscribes on `generation.enabled` alone (FR-013). |
| The settings | `config/ConfigurationValidationTest`, `config/ReportPropertiesTest` (extended) | U | FR-010: `stale-after` defaults to `30m`, a zero or negative one refuses naming the key; `batch-age-refresh` defaults to `10m` and refuses non-positive; `batch-generated-within` defaults to `10m` **without** reading the generation half (the old resolution case is deleted, not re-pointed); `completion` is no longer a bound key and a context that sets it is not thereby configured. |
| End to end | `e2e/GenerationFailureEndToEndIT` (extended) | IT | SC-001 and SC-003 in one case: a batch left GENERATING overnight is released, its registers are re-batched, rendered and notified **once**, and the original batch's late `document-available` moves nothing. |
| Privacy | `config/TelemetryPrivacyTest` (extended) | U | The two new classes on the `GenerationLegs` drive; no identifier but a batch id, no throwable, no free text. |

## Complexity Tracking

| Item | Why it is here | Why the simpler thing was rejected |
|---|---|---|
| `BatchAgeSweep`, a new class in a subtractive increment | Three continuous readings would otherwise be taken once a night | Leaving them in the pass makes them a daily sample; deleting them is an unasked-for observability regression; folding them into `IntakeAgeSweep` puts generation-half readings on the intake half's sweep, which runs where the generation half is off |
| Keeping two enum constants nothing produces | `RegisterBatchRepository` reads both columns through `valueOf`, and the 07:00 report reads FAILED batches | Removing them needs a forward-only migration that narrows a CHECK constraint and still leaves old rows unreadable; the cost lands on the one read support depends on |
| Removing `courtregister.generation.completion` rather than pinning it to one value | `poll-only` would mean "learn no outcome and re-render every night" | A setting with one legal value is a trap that reads as a choice |
