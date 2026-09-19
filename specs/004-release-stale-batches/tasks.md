# Tasks: Release stale in-flight batches before batching

**Input**: Design documents from `/specs/004-release-stale-batches/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix. Where a task lands a test the matrix does not name, the
task says so and the matrix gains the row in the same commit.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs — interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` — so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**The red-run convention for a subtractive increment.** More than half of this increment is deletion,
and "the suite still compiles" is not a red run. A task that removes behaviour lands, *first*, the
test that asserts the behaviour is **gone** — no bean of that type on the context, no such method
declared on the port, no request to that path in WireMock's journal, no such key bound, no such
annotation on any method — watches it fail against the code that still has it, and only then deletes.
A deletion whose only evidence is a still-green suite is a deletion nobody tested, and the thing it
was supposed to remove can come back in a merge with nothing to catch it.

**Minimal implementation is part of the convention, not a shortcut.** Several pairs here are split
deliberately so the second test has a real red to record: the implementation task makes the test task
before it pass and **no more**. Where that is the point of a split, the task says so in as many words.

**[A] Acceptance/characterisation tasks** verify assembled behaviour (end-to-end suites, the privacy
sweep run, the full gate run) or pin behaviour that is **unchanged** by this increment and therefore
green on introduction. No implementation task follows them and no red run is required; the task
records the initial observed result and its commit body says which of the two kinds it is.

### Approved TDD exceptions

**None in advance**, and none is granted in advance. Every task below is either a red/green pair, an
`[A]` characterisation, or a documentation task exempt from the loop. If a pair cannot be formed, the
exception is written into this section with the design owner's dated approval **before** the commit
lands, in the shape 002's and 003's exception blocks use — never argued for afterwards in a commit
body. 003 recorded two that were written after the fact and carried a condition from the design
owner: **a wiring task's test is its context case**, and a third occurrence of a configuration class
landing without one is reverted and re-landed as a pair. That condition is live in this increment and
applies to `config/BatchSweepConfig` (T029) by name.

### The defect-fix register in this increment

**No row is added, and no row's claim changes.** Neither oracle had a reconciler, a query or a
stale-release pass: the function app never saw a batch, and progression's leg had no render timeout at
all. A task that finds itself wanting a `C` or `P` number has found a defect in 001 or 002, not in
this increment, and it stops and asks.

**One row is touched in one cell.** `P2`'s pinning-test list names
`GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`
for the half of its promise that covers "neither event arrived", and that test is deleted with its
class at T020. T038 re-points the cell at the test that holds the same promise afterwards and adds one
dated sentence saying the mechanism changed in 004. The row's legacy behaviour, its fixed behaviour,
its rationale and its status are untouched. `RegisteredDefectFixes` and `DifferentialAuditTest` stay
green throughout; the differential audit is over the **intake** half, which this increment does not
touch.

**Conventions**: package root `uk.gov.hmcts.cp.courtregister`; production code under
`src/main/java/uk/gov/hmcts/cp/courtregister/`, tests under
`src/test/java/uk/gov/hmcts/cp/courtregister/`. `*IT` suites need Docker and run inside
`./gradlew test`; there is no separate `integrationTest` task. Conventional Commits on
`004-release-stale-batches`; the accepted types are `feat`, `fix`, `chore`, `docs`, `test`,
`refactor`, `build`, `ci` and `style`. No AI attribution in any commit, comment, document or test
name. **Every phase ends with a green `./gradlew build`** — every `gradlew` invocation behind the
shared `flock`, never two Gradle builds at once — **and a review gate in a new session**, whose
findings land as a red test commit followed by an implementation commit before the next phase starts.
**Never two committing agents at once in this tree.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency
  on an unfinished task)
- **[A]**: acceptance/characterisation — see above
- **[US#]**: the spec user story the task traces to. Story-phase tasks only; Setup, removal,
  phase-close and Polish tasks carry no story label

---

## Phase 1: Setup and Foundational — the settings, the vocabulary, the schema and the store

**Purpose**: everything every later phase reads. The setting the pass works to, the reason it writes,
the constraint that admits it and the store statement that releases on it. Nothing here changes any
behaviour on its own: after this phase the new reason exists and is writable and nothing writes it.

**This phase combines Setup and Foundational** because in this increment they are the same four
things: there is no project initialisation to do, and the four below are each a blocking prerequisite
for every user story.

### Tests first ⚠️

- [ ] T001 [P] `config/ConfigurationValidationTest` (extend) and `config/ReportPropertiesTest`
      (extend) — **the renamed setting, the new one, the removed one and the un-borrowed one, all red
      before any of them is written**. `stale_after_defaults_to_thirty_minutes` and
      `a_zero_stale_after_refuses_to_start` / `a_negative_stale_after_refuses_to_start`, each
      asserting the message names `courtregister.generation.stale-after`;
      `batch_age_refresh_defaults_to_ten_minutes` and `a_non_positive_batch_age_refresh_refuses_to_start`
      naming `courtregister.generation.batch-age-refresh`;
      `the_completion_setting_is_no_longer_bound`, which sets
      `courtregister.generation.completion=poll-only` and asserts the context holds no such bound
      value and subscribes anyway; and in `ReportPropertiesTest`,
      `batch_generated_within_defaults_to_ten_minutes_without_reading_the_generation_half`, which
      asserts the resolved rendering limit is `10m` on a context whose `stale-after` is `30m` — the
      case that would have silently tripled the 07:00 report's threshold. Delete
      `an_explicit_batch_generated_within_is_honoured`'s sibling
      `a_zero_grace_period_makes_the_unset_rendering_limit_refuse`, whose subject no longer exists.
      Red: the keys do not exist and the resolution still reads the generation half.
- [ ] T003 [P] `domain/BatchFailureReasonTest` (extend) and `domain/BatchStateTest` (extend) — the
      widened vocabulary. `the_seven_reasons_are_the_bounded_set`;
      `not_completed_by_next_run_is_not_generator_attributed`;
      `not_completed_by_next_run_releases_its_rows`; `the_retired_reasons_are_exactly_generation_timed_out`
      over a new `isRetired()`; and in `BatchStateTest`, the schema-vocabulary case extended so the
      enum and `register_batch_failure_reason_chk` are asserted to agree **in both directions** over
      seven values. Red: `NOT_COMPLETED_BY_NEXT_RUN` and `isRetired()` do not exist (seams: the
      constant and a method returning `false`).
- [ ] T005 [P] `persistence/SchemaMigrationV2IT` (extend) — what V6 must make true.
      `v6_admits_not_completed_by_next_run` inserts a FAILED batch under the new reason with a null
      attribution and expects it to succeed; `v6_still_admits_the_retired_timeout_reason` inserts one
      under `GENERATION_TIMED_OUT` with `RECONCILER` and expects it to succeed, because history must
      stay legal; `the_new_reason_refuses_an_attribution` expects
      `register_batch_completed_by_shape_chk` to reject a `NOT_COMPLETED_BY_NEXT_RUN` row that names a
      mechanism, **with no change to that constraint**. Red: the first and third fail against V1–V5.
- [ ] T007 `persistence/RegisterStoreIT` (extend) — the store's half of the new reason.
      `marking_not_completed_by_next_run_releases_the_rows_in_the_same_statement` asserts the batch is
      FAILED and its registers are unstamped and active after one call;
      `a_release_supersedes_against_a_later_re_share` drives the existing supersession order over the
      new reason (US1.5); `mark_failed_refuses_a_retired_reason` and
      `mark_failed_refuses_a_reconciler_attribution` assert `IllegalArgumentException` before any
      statement is issued (FR-012); `a_row_carrying_a_retired_value_still_reads_back` writes one
      through the repository and reads it through `RegisterBatchRepository`, which is the read the
      07:00 report makes. Red: the reason is not releasing and neither refusal exists.

### Implementation

- [ ] T002 `config/GenerationProperties.java`, `config/PropertiesValidator.java`,
      `config/ReportProperties.java`, `src/main/resources/application.yaml` — make T001 green.
      Rename `gracePeriod` to `staleAfter` with `@DefaultValue("30m")`; add
      `batchAgeRefresh` with `@DefaultValue("10m")`; remove the `completion` component and the
      `COMPLETION_EVENT` / `COMPLETION_POLL_ONLY` constants and their validation. In the validator:
      rename `GENERATION_GRACE_PERIOD` to `GENERATION_STALE_AFTER` keeping the positive-value refusal
      verbatim, add the same refusal for the refresh, drop the `completion == event` conjunct from the
      broker-configuration rule so it applies whenever generation is enabled, and delete
      `resolvedBatchGeneratedWithin` and its call sites. In `application.yaml`: `grace-period: 10m` →
      `stale-after: 30m` with a comment saying what it now decides; add `batch-age-refresh: 10m` with
      the two-places note the intake key already carries; delete the `completion` key and its comment
      block; and re-point the three comment sites that describe the retired machinery — the intake
      block's "a value borrowed from `courtregister.generation.grace-period`", the notification block's
      "its own setting rather than the reconciler's grace-period above", and the report block's
      paragraph about resolving the rendering limit, which is replaced by the key itself.
- [ ] T004 `domain/BatchFailureReason.java`, `domain/CompletedBy.java` — make T003 green. Add
      `NOT_COMPLETED_BY_NEXT_RUN` with the javadoc data-model.md gives it; add `isRetired()`, true for
      `GENERATION_TIMED_OUT` alone; mark `GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER` retired
      in their javadoc, each saying **why it stays** — `RegisterBatchRepository` maps both columns
      through `valueOf` and the 07:00 report reads failed batches. `isGeneratorAttributed()` is
      unchanged and needs no edit.
- [ ] T006 `src/main/resources/db/migration/V6__batch_failure_reason_not_completed.sql` — make T005
      green. Drop and re-add `register_batch_failure_reason_chk` with the seventh value, per
      data-model.md. Additive and forward-only; V2 is not edited; no other constraint is touched.
- [ ] T008 `persistence/JdbcRegisterStore.java` — make T007 green. Add
      `NOT_COMPLETED_BY_NEXT_RUN` to `RELEASING_REASONS`; add the two write-path refusals beside the
      existing `attributionOf` check, each naming what it refused and why in one bounded sentence that
      quotes no row content.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 2: User Story 1 and 2 — the pass (Priority: P1) 🎯 MVP

**Goal**: the class that decides a batch has not completed by the next run, fails it and gives its
registers back — and the minimum age that stops it doing so to a render that is still going.

**Independent test**: `StaleBatchReleaserTest` alone. The pass is a plain object over a repository,
the store, the metrics, a `Duration` and a `Clock`; no Spring context, no Docker.

### Tests first ⚠️

- [ ] T009 [US1] `batch/StaleBatchReleaserTest` (new) — the release cases, with the two reads stubbed
      on `any()` so the cutoff is **not** pinned here.
      `a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released` (this is the test
      `doc/DEFECT-FIXES.md` P2 is re-pointed at, at T038);
      `a_batch_still_pending_past_the_minimum_age_should_be_failed_and_released`;
      `the_failure_names_no_completion_mechanism`, asserting the `markFailed` argument is `null`
      because nobody outside this service reported anything;
      `the_count_returned_is_batches_not_registers`;
      `a_batch_that_cannot_be_failed_leaves_the_others_attempted`, which makes the second of three
      throw and asserts the first and third were still attempted and the count is two;
      `every_line_names_the_batch_by_id_and_nothing_else`. Seam: `StaleBatchReleaser` with a
      `releaseStale()` throwing `UnsupportedOperationException`. Red: a failing assertion on the first
      case.
- [ ] T011 [US2] `batch/StaleBatchReleaserTest` (extend) — the boundary and the clock, which T010's
      minimal implementation is deliberately allowed not to satisfy.
      `the_cutoff_is_the_injected_clock_minus_the_minimum_age`, captured off both reads;
      `a_batch_younger_than_the_minimum_age_is_not_read_at_all` (the cutoff, not a filter after the
      read, is what excludes it — asserting the argument rather than the outcome is what makes the
      difference visible); `a_batch_at_exactly_the_minimum_age_is_stale` (US2.3);
      `a_generated_batch_is_never_read_by_this_pass`, asserting `generatedSince` is never called
      (US2.4, FR-002). Red: T010's implementation reads at `now`.

### Implementation

- [ ] T010 [US1] `batch/StaleBatchReleaser.java` — make T009 green **and no more**. Two reads, one
      `store.markFailed(batchId, NOT_COMPLETED_BY_NEXT_RUN, null, null)` per batch inside a
      per-batch try that classifies and logs by class at WARN, a count of the batches it failed,
      `metrics.released()` per batch. The split from T011 is deliberate: implementing the cutoff here
      would leave T011 with nothing to fail against.
- [ ] T012 [US2] `batch/StaleBatchReleaser.java` — make T011 green. The cutoff is
      `clock.instant().minus(staleAfter)`, computed once per pass and passed to both reads; the class
      holds no third read. The javadoc states the rule the retired class stated differently: PENDING
      and GENERATING are **one** rule here, because with no query there is nothing to tell them apart,
      and GENERATED is on no arm because it holds a document somebody is owed e-mails about.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 3: User Story 1, 2 and 4 — the run calls the pass and says so (Priority: P1) 🎯 MVP

**Goal**: the pass runs first, in the right place, on the right nights, and the run's line says how
many batches it released.

**Independent test**: `RegisterGenerationJobTest` for the ordering and the line;
`GenerationWiringContextTest` for the bean.

### Tests first ⚠️

- [ ] T013 [US1] `batch/RegisterGenerationJobTest` (extend) — where the pass sits.
      `the_releaser_runs_after_the_gate_and_before_the_store_is_read`, an `InOrder` over the gate, the
      releaser and `store.activeUnbatched()` — the assertion that the whole increment's correctness
      rests on, because a pass after assembly would release into a batch that had already been made;
      `a_skipped_run_does_not_release_anything` (FR-005, US4.3);
      `released_registers_reach_the_assembler_in_the_same_run` (US1.3);
      `a_releaser_that_throws_still_writes_a_line_and_rethrows`. Seam: the job's constructor takes a
      `StaleBatchReleaser` in place of the `GenerationReconciler`. Red: the job does not call it.
- [ ] T015 [US4] `batch/RegisterGenerationJobTest` (extend) and `domain/RunReportTest` (extend) — the
      line. `the_run_line_carries_released` over a captured log;
      `a_run_that_released_nothing_says_zero`; `the_run_line_carries_no_reconciled_anywhere`, a
      whole-line assertion rather than a field one, because the word must be gone from the format
      string and not merely zero; `RunReport.released` replaces `reconciled` with the same type and
      position. Red: the record component and the line still say `reconciled`.
- [ ] T017 [US1] `config/GenerationWiringContextTest` (extend) — the bean.
      `a_generation_enabled_context_holds_a_stale_batch_releaser`;
      `a_command_jvm_holds_no_stale_batch_releaser`;
      `the_releaser_takes_the_stale_after_setting_and_not_the_whole_record`, a constructor-argument
      case, because a class that took the record would state a dependency on ten settings it never
      reads. Red: no such bean.

### Implementation

- [ ] T014 [US1] `batch/RegisterGenerationJob.java` — make T013 green. `generate(tally)` calls
      `tally.released(releaser.releaseStale())` as its **first** statement, before
      `store.activeUnbatched()`; the end-of-run `tally.chased(reconciler.reconcile())` is deleted; the
      field, the constructor parameter and both javadoc mentions of the reconciler are replaced. The
      class javadoc's paragraph "The reconciler runs whatever the night held, and on nights the run
      does not" is replaced by the paragraph the flow now has: the release is the run's own first act
      and happens on the nights the run happens, which is the whole of the trade this increment makes.
- [ ] T016 [US4] `domain/RunReport.java`, `batch/RegisterGenerationJob.java`,
      `config/GenerationMetrics.java` — make T015 green. `reconciled` → `released` on the record, in
      `RunTally`, and in the `recorded(...)` format string; `GENERATION_RECONCILED` →
      `courtregister_generation_released_total` with `reconciled()` → `released()` and the javadoc
      saying what the number now is: batches a run gave up on, and a run of non-zero nights is a
      broker or a renderer to investigate.
- [ ] T018 [US1] `config/GenerationConfig.java`, `config/SchedulingConfig.java` — make T017 green. The
      `generationReconciler` bean becomes `staleBatchReleaser`, taking `properties.staleAfter()`; the
      job bean's `ObjectProvider<GenerationReconciler>` becomes
      `ObjectProvider<StaleBatchReleaser>`. **This is a wiring task, so its test is its context
      case** (the 003 condition), which T017 is.

**Phase close**: `flock … ./gradlew build` green; review gate. At this point the new behaviour is
live and the reconciler is dead code with a timer still on it — which the next phase removes.

---

## Phase 4: User Story 4 — the removal (Priority: P2)

**Goal**: the reconciler, its timer, its lock, the systemdocgenerator query path and the deployment
mode that depended on it are gone from the source, the wiring, the stubs and the local stack.

**Independent test**: the absence cases below, each of which fails before its deletion.

### Tests first ⚠️

- [ ] T019 [US4] `config/GenerationWiringContextTest` and `config/CliModeConfigTest` (both extend) —
      the reconciler's absence. `no_context_holds_a_generation_reconciler`;
      `the_generation_half_carries_exactly_one_scheduler_lock`, a reflection sweep over the `batch`
      package asserting one `@SchedulerLock` (the run's) and naming it;
      `the_generation_half_carries_exactly_one_cron`. Red: the bean and the second lock exist.
- [ ] T021 [US4] `application/DocumentRendererTest` (new, a reflection case) and
      `adapter/systemdocgenerator/SystemDocGeneratorClientTest` (extend) — the query's absence.
      `the_renderer_port_declares_one_method`, which is the case that stops the query being
      reintroduced quietly; `a_whole_generation_makes_no_request_to_the_document_endpoint`, asserting
      over WireMock's journal that no `GET document/…` was issued. Red: two methods, and the client
      still has the call.
- [ ] T023 [US4] `config/ConfigurationValidationTest` and `config/PublicEventsHealthIndicatorTest`
      (both extend) — the completion mode's absence.
      `a_generation_enabled_context_subscribes_without_being_told_to`;
      `a_generation_enabled_context_without_the_broker_configuration_refuses_to_start`, now
      unconditional. Red: `PublicEventsConfig` still reads `completion` and the refusal is still
      conditional. (T001 already asserted the key is unbound; this is the behaviour that used to hang
      off it.)

### Implementation

- [ ] T020 [US4] Delete `batch/GenerationReconciler.java` and
      `batch/GenerationReconcilerTest.java`; delete the bean left over in `config/GenerationConfig`;
      delete the `register-reconciliation` lock name. Make T019 green. The only behaviour lost here
      is the timer: its caller went at T014 and its query goes at T022.
- [ ] T022 [US4] Delete `DocumentRenderer.query`, `SystemDocGeneratorClient.query` and its answer
      parsing, `StubDocumentRenderer.query` and `domain/DocumentStatus.java`; delete the
      `GET document/{id}` WireMock mapping under `docker/wiremock/` and its line in
      `docker/wiremock/README.md`. Make T021 green. `DocumentRenderer`'s javadoc drops its "two
      conversations" paragraph and says what the port is now: one capability, ask for a document.
- [ ] T024 [US4] `config/GenerationProperties` (the component went at T002),
      `config/PublicEventsConfig.java`, `config/PropertiesValidator.java` — make T023 green.
      `PublicEventsConfig` subscribes on `generation.enabled` alone; the validator's broker rule loses
      its conjunct. `PublicEventsConfig`'s javadoc drops the sentence about the `poll-only` escape
      hatch that "asks for no broker and must therefore subscribe to nothing".
- [ ] T025 [A] [US4] `adapter/stub/StubGenerationAdaptersTest`, `e2e/GenerationFailureEndToEndIT`,
      `support/GenerationLegs`, `support/GenerationStackSupport`, `support/GeneratedRegisters` —
      remove the query wiring, the reconciler construction and the `GRACE_PERIOD` constants the
      support classes carry, and record the observed suite result. A characterisation of the deletion
      rather than a pair: what these hold is other suites' scaffolding, and the behaviour they set up
      is asserted by T019, T021 and T023.

**Phase close**: `flock … ./gradlew build` green; review gate. **Coverage is the thing to read on this
gate**: a phase that deleted several hundred covered lines is the one shape in which the ratchet can
be met by accident. Quote the report's numbers, do not adjust the gate.

---

## Phase 5: FR-011 — the readings survive the timer

**Goal**: the three in-flight age gauges the retired timer took on its way past keep their cadence,
on a sweep that holds no lock and settles nothing.

**Independent test**: `BatchAgeSweepTest` over a `SimpleMeterRegistry` and a stubbed repository.

### Tests first ⚠️

- [ ] T026 `batch/BatchAgeSweepTest` (new) — the readings.
      `the_three_gauges_report_the_age_of_the_oldest_of_each_kind`;
      `a_kind_with_nothing_in_flight_reads_zero`, which is what brings a gauge back down;
      `a_batch_holding_a_document_nobody_was_told_about_is_named_and_settled_nothing`, the WARN the
      retired pass wrote and the assertion that no store call follows it;
      `a_read_that_refuses_keeps_the_last_value_and_is_counted`, the design rules' one absorbed
      refusal — telemetry may not cost a Youth Offending Team its e-mail — asserting the WARN names
      the failure **by class**, the counter moves, and nothing is rethrown. Seam: the class with a
      `sweep()` throwing `UnsupportedOperationException`. Red: a failing assertion on the first case.
- [ ] T028 `batch/BatchAgeSweepTest` (extend), `config/ReportSchedulingConfigTest` and
      `config/CliModeConfigTest` (both extend) — the schedule it is on.
      `the_fixed_delay_reads_the_batch_age_refresh_key`, a reflection case over the annotation
      attribute, because a placeholder nobody asserts is one a later edit inlines;
      `the_sweep_names_its_own_scheduler`; `the_sweep_carries_no_scheduler_lock`, because a gauge
      describes the JVM that publishes it and a lock would make every other pod publish zero;
      `a_command_jvm_runs_no_batch_age_sweep`; `the_context_holds_four_task_schedulers`. Red: no
      annotation, no scheduler bean, no exclusion.

### Implementation

- [ ] T027 `batch/BatchAgeSweep.java` — make T026 green. Three reads at
      `clock.instant().minus(batchAgeRefresh window)` per data-model.md, the three
      `metrics.oldest…Age` calls, the parked-batch WARN, and the one absorbed refusal with its
      counter. It holds a repository, the metrics and a clock, and no store, no renderer and no lock.
      `RunCorrelation.under(...)` opens an id of its own, because a sweep on its own schedule is a
      unit of work in its own right — unlike the releaser, which adopts the run's.
- [ ] T029 `config/BatchSweepConfig.java` (new), `batch/BatchAgeSweep.java`,
      `config/CliModeConfig.java` — make T028 green. The config declares
      `BATCH_SWEEP_SCHEDULER` and a single-threaded `TaskScheduler` bean, in the shape
      `IntakeSweepConfig` already uses and for the same reason: a ten-minute reading queued behind a
      run that is asking for renders is a reading taken an hour late. **This is a configuration class,
      so T028's context case is its test** — the live condition from 003's second exception.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 6: User Story 3 — the guarantees, end to end

**Goal**: prove the two things the whole change rests on — a late outcome for a released batch moves
nothing, and the released registers reach their Youth Offending Teams exactly once.

### Tasks

- [ ] T030 [A] [US3] `application/DocumentOutcomeSinkTest` (extend) —
      `a_document_available_for_a_batch_not_completed_by_the_next_run_moves_nothing` and
      `a_generation_failed_for_one_moves_nothing`, each asserting the batch is unchanged, no
      notification is made, and the drop is counted under `late-acceptance-ignored` /
      `late-failure-ignored`. **Green on introduction and recorded as such**: the sink's behaviour is
      unchanged by this increment and a FAILED batch is a FAILED batch whatever failed it. It is here
      because this increment creates a new way to reach that state and FR-008 must be pinned for it by
      name, not because anything needs writing.
- [ ] T031 [A] [US3] `e2e/GenerationFailureEndToEndIT` (extend) — one case carrying SC-001 and
      SC-003: a batch left GENERATING since the previous evening is released by the run, its registers
      are assembled into a new batch for the same court centre and register date, that batch renders
      and notifies, **exactly one** notification exists per recipient for that key, and the original
      batch's late `document-available` — delivered afterwards — moves nothing and is counted.
- [ ] T032 `config/TelemetryPrivacyTest` (extend) — the privacy sweep over the two new classes.
      `GenerationLegs` drives `StaleBatchReleaser` and `BatchAgeSweep` and no longer names
      `GenerationReconciler`; every line they produce carries only a batch id, a count and a bounded
      code, and no throwable this service did not write. Red: the drive names a class that no longer
      exists and does not name the two that do.
- [ ] T033 Make T032 green: update `support/GenerationLegs`'s drive and fix any line the sweep
      rejects. A line that has to be changed to pass is a privacy finding and is called one in the
      commit body, not a test adjustment.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 7: Polish — the documents, the register cell and the gates

**Goal**: no document in this repository describes a query that no longer exists (FR-016), and the
whole increment passes the gates it has to pass.

### Tasks

- [ ] T034 [P] `.claude/rules/design_rules.md` — five edits and no others: the two-leg flow diagram
      loses the `GenerationReconciler` line and gains the release pass and the sweep; the batch state
      machine's `neither, past the grace period ───▶ FAILED, GENERATION_TIMED_OUT (reconciler)` arm
      becomes the one data-model.md gives; the bounded failure-reason list gains the new reason and
      marks the retired one; **"The reconciler invents nothing" is reworded** to the rule that
      replaces it — *a batch nothing can be learned about is failed by the next run through the store,
      with its rows released, and no outcome is applied, because there is no outcome and applying one
      would be inventing evidence*; the consumed-contracts table's systemdocgenerator row loses the
      query endpoint; and the package-structure list's `batch/` line swaps `GenerationReconciler` for
      `StaleBatchReleaser` and `BatchAgeSweep`. The "one absorbed refusal" clause gains the sweep
      beside `IntakeAgeSweep`. Nothing else on the page is touched.
- [ ] T035 [P] `README.md` — the generation section's "with a grace-period reconciler for the outcomes
      that never arrive" becomes the release pass, and a Status entry for increment 004 in the shape
      001–003 use.
- [ ] T036 [P] `specs/002-consolidate-progression-leg/quickstart.md` — the WireMock line loses
      "query document". A historical quickstart is still a runnable one, and it is the file the 004
      quickstart tells the reader to start from.
- [ ] T037 [P] `.claude/agents/spec-validator.md`, `.claude/agents/software-engineer.md`,
      `.claude/agents/qa.md`, `.claude/agents/code-reviewer.md` — the scope paragraphs name
      **004-release-stale-batches** alongside the three complete increments; `spec-validator`'s
      generation-leg read list swaps `batch/GenerationReconciler` for `batch/StaleBatchReleaser` and
      `batch/BatchAgeSweep`, and its "the outcome is learned, never assumed" bullet is re-pointed at
      the new arm.
- [ ] T038 `doc/DEFECT-FIXES.md` — the one cell. `P2`'s pinning-test list replaces
      `GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`
      with `StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`,
      and the row's status cell gains one dated sentence: the mechanism that keeps the promise changed
      in increment 004, from a grace-period query to the next run's release pass, and the promise —
      a failed or lost render is never silently dropped — is unchanged. **No new row, no changed
      claim, no changed status.** `RegisteredDefectFixes` must be green in the same commit.
- [ ] T039 [A] The gates, recorded. `flock … ./gradlew build` with the JaCoCo ratchet at
      **0.88 line / 0.85 branch** unchanged, PMD over main, Checkstyle over main and test, the
      differential audit and the consolidation audit; then the `quickstart.md` walkthrough end to end
      on the local stack, including step 6's other side of the boundary and the three startup
      refusals. Quote the numbers and the walkthrough's observed output. **If coverage falls, it is
      fixed with tests, not by moving the gate.**

**Phase close**: `flock … ./gradlew build` green; whole-increment review gate (code-reviewer, qa,
spec-validator, then Codex) before the merge to `main`.

---

## Dependencies & execution order

```text
Phase 1  (T001-T008)  settings, vocabulary, schema, store
   │   everything below writes or reads the new reason
   ▼
Phase 2  (T009-T012)  the pass                          [US1, US2]
   │   the run cannot call what does not exist
   ▼
Phase 3  (T013-T018)  the run calls it, and says so     [US1, US2, US4]
   │   the reconciler's last caller goes here; only then is it dead code
   ▼
Phase 4  (T019-T025)  the removal                       [US4]
   │   the sweep replaces readings the removal took
   ▼
Phase 5  (T026-T029)  the readings survive              [FR-011]
   │
   ▼
Phase 6  (T030-T033)  the guarantees, end to end        [US3]
   │   the end-to-end case needs the whole flow assembled
   ▼
Phase 7  (T034-T039)  documents, the P2 cell, the gates
```

**Hard orderings inside phases**: T001→T002, T003→T004, T005→T006, T007→T008 (each pair);
T009→T010→T011→T012 (T011's red depends on T010 being minimal); T013→T014, T015→T016, T017→T018;
T019→T020, T021→T022, T023→T024, and T025 after all three deletions; T026→T027→T028→T029;
T032→T033; T038 must land in the same commit as a green `RegisteredDefectFixes`.

**Cross-phase**: T038 depends on T009 (the test it names must exist) and T020 (the test it replaces
must be gone). T039 depends on everything.

### Parallel opportunities per phase

```text
# Phase 1 - four independent red runs, four different files:
T001  config/ConfigurationValidationTest + config/ReportPropertiesTest
T003  domain/BatchFailureReasonTest + domain/BatchStateTest
T005  persistence/SchemaMigrationV2IT
# T007 follows T005 in practice (both are Postgres *IT suites and the second reads V6)

# Phase 3 - the three test tasks touch three areas but two share RegisterGenerationJobTest:
T013 and T017 in parallel; T015 after T013 (same file)

# Phase 4 - three independent absence cases:
T019  config/GenerationWiringContextTest + config/CliModeConfigTest
T021  application/DocumentRendererTest + adapter/systemdocgenerator/SystemDocGeneratorClientTest
T023  config/ConfigurationValidationTest + config/PublicEventsHealthIndicatorTest

# Phase 7 - the four documentation tasks touch four different files:
T034  .claude/rules/design_rules.md
T035  README.md
T036  specs/002-consolidate-progression-leg/quickstart.md
T037  .claude/agents/*.md
```

## Implementation strategy

**MVP is Phases 1–3.** After T018 the increment's user-visible promise is met: a stale batch is
released and its court centre gets its document that night, and the run says so. Phases 4 and 5 are
the removal and its one replacement, and Phases 6 and 7 are the proof and the documents. If the
increment had to stop early it would stop after Phase 3 — with the reconciler still running, which is
harmless because its only remaining act would be to chase a batch the run has already released, find
it FAILED, and apply nothing.

**It cannot stop after Phase 4** without Phase 5, and that is the one ordering to respect under
pressure: Phase 4 deletes the timer that takes three continuous readings and Phase 5 is where they
come back.

## Notes

- **The `[A]` tasks are two different kinds** and the commit bodies must say which: T030 pins
  behaviour this increment does not change and is green on introduction; T025, T031 and T039 verify
  assembled behaviour. Neither kind is a licence to skip a pair that could have been formed.
- **Nothing in this increment touches the intake half**, the register document, the inbound message,
  supersession's rules, the notification leg or the cutover lever. A task that finds itself editing
  one of those has found a dependency the plan missed and stops.
- **Three things are outside this repository** and are flagged, not done: the Confluence design
  document's sections on the reconciler and the query, the Gliffy diagram's dashed query arrow and its
  step label, and the STE environment values for the renamed setting and the removed completion mode.
  The last of these is why T002 removes the old key rather than aliasing it: an alias would let a
  stale environment value read as configured while doing nothing.
