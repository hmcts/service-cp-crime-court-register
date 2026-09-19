# Tasks: Release stale in-flight batches before batching

**Input**: Design documents from `/specs/004-release-stale-batches/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md
**Revised**: 2026-09-19, after the two design reviews the spec's second Clarifications session
records. The tasks below are the revised list; nothing from the pre-review draft survives unchanged
in Phases 1, 2 and 6.

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
annotation on any method, no such constant in the enum — watches it fail against the code that still
has it, and only then deletes. A deletion whose only evidence is a still-green suite is a deletion
nobody tested, and the thing it removed can come back in a merge with nothing to catch it.

**Minimal implementation is part of the convention, not a shortcut.** Two pairs here are split
deliberately so the second test has a real red to record; where that is the point of a split, the
task says so in as many words.

### Approved TDD exceptions

**None in advance**, and none is granted in advance. Every task below is either a red/green pair, an
`[A]` characterisation, or a documentation task exempt from the loop. If a pair cannot be formed, the
exception is written into this section with the design owner's dated approval **before** the commit
lands, in the shape 002's and 003's exception blocks use — never argued for afterwards in a commit
body. 003's second exception carried a live condition from the design owner: **a wiring task's test
is its context case**, and a third occurrence of a configuration class landing without one is
reverted and re-landed as a pair. That condition applies to `config/BatchSweepConfig` (T032) by name.

### The defect-fix register in this increment

**No row is added, and no row's claim changes.** Neither oracle had a reconciler, a query or a
stale-release pass: the function app never saw a batch, and progression's leg had no render timeout
at all. A task that finds itself wanting a `C` or `P` number has found a defect in 001 or 002, not in
this increment, and it stops and asks.

**One row is touched in one cell.** `P2`'s pinning-test list names
`GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`,
deleted with its class at T023. T042 re-points the cell at
`StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`
and adds one dated sentence saying the mechanism changed in 004. The row's legacy behaviour, fixed
behaviour, rationale and status are untouched. `RegisteredDefectFixes` and `DifferentialAuditTest`
stay green throughout.

**Conventions**: package root `uk.gov.hmcts.cp.courtregister`; production under
`src/main/java/…`, tests under `src/test/java/…`. `*IT` suites need Docker and run inside
`./gradlew test`. Conventional Commits on `004-release-stale-batches`; accepted types `feat`, `fix`,
`chore`, `docs`, `test`, `refactor`, `build`, `ci`, `style`. No AI attribution anywhere. **Every phase
ends with a green `./gradlew build`** — every `gradlew` behind the shared `flock`, never two Gradle
builds at once — **and a review gate in a new session**, whose findings land as a red test commit
then an implementation commit before the next phase starts. **Never two committing agents at once in
this tree.**

**T003-T007 are blocked on Phase 5 and did not land with T001-T002** (found 2026-09-19 while
implementing this phase; the range is otherwise untouched and the tree is green at T002).
`GenerationReconciler` is the only writer of the two constants T004 removes: line 159 builds its
silence ending from `BatchFailureReason.GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER`, and lines
414 and 422 pass `CompletedBy.RECONCILER` into the store's two marks. The class is not deleted until
**T022**, three phases later, so T004 cannot compile in Phase 1, and there is no honest substitute -
the reconciler *is* the mechanism `RECONCILER` names, and re-pointing its silence at
`NOT_COMPLETED_BY_NEXT_RUN` or its two marks at `EVENT` would write into `completed_by` a claim that
is false. The collateral in the suite is the same shape and about sixty-five references
(`GenerationReconcilerTest` 34, `RegisterStoreIT` 15, `RegisterBatchRepositoryIT` 5,
`DocumentOutcomeSinkTest` 4, `SchemaMigrationV2IT` 4, and five single uses), all of it rewritten in
Phase 1 and deleted in Phase 5.

T005 and T006 travel with them, because `SchemaMigrationV2IT` asserts the constraint against the
enumeration (`failure_reason_check_should_name_exactly_the_bounded_reasons`,
`completed_by_check_should_name_exactly_the_two_completion_mechanisms`): V6's narrowed lists and an
enum that still holds the retired constants cannot both be green. T007 travels with them because its
observation is about V6 applying.

Two resequencings are possible and the choice is the design owner's, because the second one moves what
`data-model.md` says:

1. **Move T003-T006 (and T007) to sit after T022 and T025**, leaving Phase 1 as the settings alone.
   Nothing in `data-model.md` or the spec changes; the vocabulary and the migration land in the same
   phase as the deletion that frees them, which is where the coupling actually is.
2. **Split each removal across two migrations**: Phase 1 adds `NOT_COMPLETED_BY_NEXT_RUN` and a V6
   that admits it, Phase 5 removes the two retired constants and narrows the three constraints in a
   V7. This contradicts FR-012 and `data-model.md`, both of which say the admission and the removals
   are *the same forward migration*, so it needs the design owner's word before it is taken.

**One thing to know before Phase 1 runs anywhere**: T006's migration narrows two CHECK constraints,
and Postgres refuses to add a constraint to a table holding a violating row. Any local volume,
container or snapshot still holding a batch failed `GENERATION_TIMED_OUT` or completed by
`RECONCILER` must be cleaned or recreated (`docker compose down -v`) before the migration runs. The
Testcontainers suites start clean and are unaffected.

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency
  on an unfinished task)
- **[A]**: acceptance/characterisation — verifies assembled behaviour; no red run required, and the
  task records the observed result
- **[US#]**: the spec user story the task traces to. Story-phase tasks only

---

## Phase 1: Setup and Foundational — the settings, the vocabulary and the schema

**Purpose**: everything every later phase reads. The settings the pass works to, the reason it
writes, the reason it stops writing, and the constraints that admit one and refuse the other.
Nothing here changes behaviour on its own.

**Setup and Foundational are one phase here** because there is no project initialisation to do and
each of the four below is a blocking prerequisite for every user story.

### Tests first ⚠️

- [x] T001 [P] `config/ConfigurationValidationTest` (extend) and `config/ReportPropertiesTest`
      (extend) — the renamed setting, the new one, the removed one and the un-borrowed one.
      `stale_after_defaults_to_thirty_minutes`; `a_zero_stale_after_refuses_to_start` and
      `a_negative_stale_after_refuses_to_start`, each asserting the message names
      `courtregister.generation.stale-after`; `batch_age_refresh_defaults_to_ten_minutes` and
      `a_non_positive_batch_age_refresh_refuses_to_start` naming
      `courtregister.generation.batch-age-refresh`; `the_completion_setting_is_no_longer_bound`,
      which sets `courtregister.generation.completion=poll-only` and asserts the context binds no
      such value; and in `ReportPropertiesTest`,
      `batch_generated_within_defaults_to_ten_minutes_without_reading_the_generation_half`, over a
      context whose `stale-after` is `30m` — the case that would otherwise have silently tripled the
      07:00 report's threshold. Delete `a_zero_grace_period_makes_the_unset_rendering_limit_refuse`,
      whose subject no longer exists. Red: the keys do not exist and the resolution still reads the
      generation half.
      (red on the seam tree: 166 tests, 7 failures, 0 errors, every one an assertion.
      `stale_after_defaults_to_thirty_minutes` on "expected: 30M but was: 10M";
      `batch_age_refresh_defaults_to_ten_minutes` on "expected: 10M but was: null";
      `a_zero_stale_after_refuses_to_start`, `a_negative_stale_after_refuses_to_start` and
      `a_non_positive_batch_age_refresh_refuses_to_start` each on "Expecting
      <Started application [...]> to have failed but context started successfully";
      `batch_generated_within_defaults_to_ten_minutes_without_reading_the_generation_half` and
      `report_defaults_are_the_documented_ones` on "expected: 10M but was: 1M".
      The seams are the record components themselves, since a configuration default has no other
      seam: `staleAfter` and `batchAgeRefresh` landed on `GenerationProperties` carrying the old
      ten minutes and no default at all, and `ReportProperties.batchGeneratedWithin` landed
      carrying one minute, so every red is an assertion on a value rather than a missing accessor.
      Two deviations, both forced and both additive. First, `the_completion_setting_is_no_longer_bound`
      and the broker rule's own cases live in a renamed nested class,
      `GenerationOutcomeAndDurations`, because the class they were in was named after the setting
      that goes; `poll_only_completion_without_a_broker_should_start` is deleted with its subject and
      `event_completion_with_generation_disabled_should_start` is renamed
      `a_disabled_generation_without_a_broker_should_start`. Second,
      `an_unset_batch_generated_within_resolves_to_the_generation_grace_period` is deleted with
      `resolvedBatchGeneratedWithin` and `an_explicit_batch_generated_within_is_honoured` keeps its
      claim without naming the generation half.)
- [ ] T003 [P] `domain/BatchFailureReasonTest` (extend) and `domain/BatchStateTest` (extend) — the
      swapped vocabulary. `the_six_reasons_are_the_bounded_set`;
      `not_completed_by_next_run_is_not_generator_attributed`;
      `not_completed_by_next_run_releases_its_rows`;
      `generation_timed_out_is_no_longer_a_reason` and `completed_by_has_one_constant`, both of which
      are **deletion reds** — they fail against an enum that still has them; and in `BatchStateTest`,
      the schema-vocabulary case extended so the enum and
      `register_batch_failure_reason_chk` are held to each other in **both** directions. Red: the new
      constant does not exist and the two retired ones still do.
- [ ] T005 [P] `persistence/SchemaMigrationV2IT` (extend) — what V6 must make true.
      `v6_admits_not_completed_by_next_run` (a FAILED batch under the new reason with a null
      attribution succeeds); `v6_refuses_the_retired_timeout_reason` and
      `v6_refuses_the_retired_attribution` (deletion reds against the current schema);
      `the_new_reason_refuses_an_attribution`, which
      `register_batch_completed_by_shape_chk` must still enforce after its narrowing. Red: the first
      three fail against V1–V5.
- [ ] T007 `docker/`, `specs/004-release-stale-batches/quickstart.md` — **[A]** the local stack's
      clean-store step. Record that `docker compose down -v` is required before V6 on any volume
      holding a pre-004 row, and confirm on a real local volume that the migration refuses without it
      and applies with it. No pair: this is an observation about Postgres, not a behaviour this
      repository implements.

### Implementation

- [x] T002 `config/GenerationProperties.java`, `config/PropertiesValidator.java`,
      `config/ReportProperties.java`, `src/main/resources/application.yaml` — make T001 green.
      `gracePeriod` → `staleAfter` with `@DefaultValue("30m")`; add `batchAgeRefresh`
      `@DefaultValue("10m")`; remove the `completion` component and its two constants and its
      validation. In the validator: rename `GENERATION_GRACE_PERIOD` → `GENERATION_STALE_AFTER`
      keeping the positive refusal verbatim, add the same refusal for the refresh, drop the
      `completion == event` conjunct from the broker rule so it applies whenever generation is
      enabled, and delete `resolvedBatchGeneratedWithin` and its call sites. In `application.yaml`:
      `grace-period: 10m` → `stale-after: 30m`; add `batch-age-refresh: 10m` with the written-twice
      note the intake key carries; delete the `completion` key and its comment block; and re-point
      the three comment sites that describe the retired machinery (the intake block's "borrowed
      from …grace-period", the notification block's "rather than the reconciler's grace-period
      above", and the report block's paragraph about resolving the rendering limit, replaced by the
      key itself).
      (green: `ConfigurationValidationTest` and `ReportPropertiesTest`, 166 tests, 0 failures,
      0 errors. Four files beyond the four this task names had to move with the removal, because the
      component and the key they read no longer exist: `config/PublicEventsConfig` (the subscription
      autostarts on `generation.enabled()` rather than on the completion mechanism — FR-013's end
      state, reached here because there is nothing else left to read),
      `config/ProcessedLogConfig` (the report's rendering limit is now `report.batchGeneratedWithin()`
      rather than the deleted resolution), `config/GenerationConfig` and `batch/GenerationReconciler`
      (the transitional reconciler's `@Scheduled` placeholder is re-pointed at
      `${courtregister.generation.stale-after}`, so its cadence is thirty minutes until T022 deletes
      it, and `GenerationReconcilerTest`'s placeholder assertion follows).
      `PropertiesValidator.validateReport` loses its `GenerationProperties` parameter, which nothing
      in it read any more, and `CourtRegisterProperties`'s javadoc reference to the grace period is
      re-pointed.)
- [ ] T004 `domain/BatchFailureReason.java`, `domain/CompletedBy.java` — make T003 green. Add
      `NOT_COMPLETED_BY_NEXT_RUN` with the javadoc data-model.md gives it; remove
      `GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER`; narrow `isGeneratorAttributed()` to
      `GENERATION_FAILED`. `CompletedBy` stays a type with one constant, and its javadoc says why: it
      is an argument carried through the outcome sink into the store's marks, and a second mechanism
      is exactly the kind of thing that comes back.
- [ ] T006 `src/main/resources/db/migration/V6__stale_batch_release.sql` — make T005 green. The three
      constraint rewrites of data-model.md, in that order. Additive and forward-only; `V2` is not
      edited; no column, table or index is added.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 2: User Story 1 — the fenced store operation (Priority: P1) 🎯 MVP

**Goal**: one statement that fails every stale batch and releases its registers, atomically, fenced
on the staleness rule itself. **This is the correctness of the whole increment** and it is a phase of
its own for that reason.

**Independent test**: `RegisterStoreIT` and `StaleReleaseConcurrencyIT` against Testcontainers
Postgres. No pass, no run, no Spring context.

### Tests first ⚠️

- [ ] T008 [US1] `persistence/RegisterStoreIT` (extend) — the predicate and the write.
      `a_generating_batch_past_its_cutoff_is_failed_and_its_registers_released`;
      `a_pending_batch_past_its_cutoff_is_failed_and_its_registers_released`;
      `a_pending_batch_with_no_payload_id_is_released_too` (FR-020 — the gap the retired reads left);
      `a_generated_batch_is_never_matched_at_any_age` (FR-002);
      `a_manually_generated_batch_uses_the_longer_cutoff` (FR-017);
      `a_batch_inside_its_cutoff_is_not_matched`;
      `the_mark_and_the_release_are_one_transaction`, asserting no intermediate state is observable;
      `the_failure_names_no_completion_mechanism`, so `attributionOf` is satisfied with `null`;
      `a_release_supersedes_against_a_later_re_share` (US1.5);
      `a_batch_that_no_longer_matches_yields_zero_rows_and_no_error` (FR-003a);
      `the_operation_returns_what_it_changed_with_its_register_counts`. Seam:
      `RegisterStore.failAndReleaseStale` and the `ReleasedBatch` record, with the Jdbc
      implementation throwing `UnsupportedOperationException`. Red: a failing assertion on the first
      case.
- [ ] T010 [US1] `persistence/StaleReleaseConcurrencyIT` (new) — **SC-009**, the reason the operation
      is fenced. `a_render_acceptance_racing_the_release_leaves_no_stranded_register` and
      `a_document_arrival_racing_the_release_leaves_no_stranded_register`, each run in **both**
      winner orders and repeated, asserting after every round that no `register_record` is stamped to
      a batch in a terminal state, that at most one live batch exists per (court centre, register
      date), and that at most one notification aggregate exists per key. Red: against T009's
      implementation this passes; **against a deliberately staged read-then-mark variant it does
      not**, and the task records that staged failure as its red, because the assertion being made is
      about the shape of the operation and a test that cannot fail against the wrong shape proves
      nothing. The staged variant is not committed.

### Implementation

- [ ] T009 [US1] `application/RegisterStore.java`, `persistence/JdbcRegisterStore.java` — make T008
      green. The port method and its `ReleasedBatch` answer; the single statement of data-model.md,
      with `COALESCE(requested_at, assembled_at)` and the `system_generated` `CASE`; the reuse of
      `MARK_FAILED`'s existing release-and-supersede branch over the matched set; and
      `NOT_COMPLETED_BY_NEXT_RUN` added to `RELEASING_REASONS`, so a per-batch `markFailed` from the
      operations surface releases on it too. The javadoc states the fence and says what a
      read-then-mark shape would cost — a stranded register that `activeUnbatched` can never see, and
      a refused transition that ends a night — because that is the reason the method exists at all
      rather than being a loop in the caller.

**Phase close**: `flock … ./gradlew build` green; review gate. **This gate is the one to read
closely**: everything after it assumes the statement is atomic and fenced.

---

## Phase 3: User Stories 1 and 2 — the pass and its cutoffs (Priority: P1) 🎯 MVP

**Goal**: the class that computes the two cutoffs, asks the store once, and reports what came back.

**Independent test**: `StaleBatchReleaserTest` — a plain object over the store, the metrics, two
durations and a clock. No Spring context, no Docker.

### Tests first ⚠️

- [ ] T011 [US1] `batch/StaleBatchReleaserTest` (new) — the call and the account.
      `a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released` (**P2's
      re-pointed pinning test**, driven through the store mock);
      `the_two_numbers_are_batches_and_registers`;
      `one_line_per_released_batch_names_it_by_id_and_nothing_else`;
      `a_pass_that_released_nothing_says_so`;
      `the_pass_adopts_the_runs_correlation`. Seam: the class with `releaseStale()` throwing
      `UnsupportedOperationException`. Red: a failing assertion on the first case.
- [ ] T013 [US2] `batch/StaleBatchReleaserTest` (extend) — the two cutoffs, which T012's minimal
      implementation is deliberately allowed not to compute.
      `the_scheduled_cutoff_is_the_clock_minus_the_minimum_age`;
      `the_manual_cutoff_is_the_longer_of_the_minimum_age_and_the_run_lock` (FR-017), with a case
      each way round so neither ordering of the two settings is assumed;
      `a_batch_at_exactly_the_minimum_age_is_stale` (US2.3), asserted on the cutoff argument rather
      than on an outcome, because the boundary lives in the predicate. Red: T012 passes `now` for
      both.

### Implementation

- [ ] T012 [US1] `batch/StaleBatchReleaser.java` — make T011 green **and no more**: one call to
      `failAndReleaseStale`, the lines, the two counters, the two numbers back, all under
      `RunCorrelation.under(...)`, which adopts the run's ambient id. The split from T013 is
      deliberate: computing the cutoffs here would leave T013 with nothing to fail against.
- [ ] T014 [US2] `batch/StaleBatchReleaser.java` — make T013 green. Both cutoffs computed once per
      pass from the injected clock and the two settings. The javadoc states the rule the retired
      class stated differently: PENDING and GENERATING are **one** rule, because with no query there
      is nothing to tell them apart; GENERATED is on no arm; and an operator's batch is given the
      longer grace because a manual generation holds no lock and has the whole deadline to work in.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 4: User Stories 1, 2 and 4 — the run calls the pass and says so (Priority: P1) 🎯 MVP

**Goal**: the pass runs first, on the right nights, and the run's line carries the two released
numbers.

### Tests first ⚠️

- [ ] T015 [US1] `batch/RegisterGenerationJobTest` (extend) — where the pass sits.
      `the_releaser_runs_after_the_gate_and_before_the_store_is_read`, an `InOrder` over the gate, the
      releaser and `store.activeUnbatched()` — the assertion the whole increment rests on, because a
      pass after assembly would release into a batch already made;
      `a_skipped_run_does_not_release_anything` (FR-005, FR-018);
      `released_registers_reach_the_assembler_in_the_same_run` (US1.3);
      `a_releaser_that_throws_still_writes_a_line_and_rethrows`. Seam: the constructor takes a
      `StaleBatchReleaser` in place of the `GenerationReconciler`. Red: the job does not call it.
- [ ] T017 [US4] `batch/RegisterGenerationJobTest` (extend) and `domain/RunReportTest` (extend) — the
      line. `the_run_line_carries_both_released_numbers`; `a_run_that_released_nothing_says_zero`;
      `the_run_line_carries_no_reconciled_anywhere`, a whole-line assertion rather than a field one,
      because the word must be gone from the format string and not merely zero;
      `the_released_registers_are_not_added_to_either_total`, which pins the one thing a reader of a
      line of totals will otherwise assume. Red: the record and the line still say `reconciled`.
- [ ] T019 [US1] `config/GenerationWiringContextTest` (extend) — the bean.
      `a_generation_enabled_context_holds_a_stale_batch_releaser`;
      `a_command_jvm_holds_no_stale_batch_releaser`;
      `the_releaser_takes_the_two_durations_and_not_the_whole_record`. Red: no such bean.

### Implementation

- [ ] T016 [US1] `batch/RegisterGenerationJob.java` — make T015 green. `generate(tally)` calls
      `tally.released(releaser.releaseStale())` as its **first** statement, before
      `store.activeUnbatched()`; the end-of-run `tally.chased(reconciler.reconcile())` is deleted;
      the field, the constructor parameter and both javadoc mentions of the reconciler go. The class
      javadoc's paragraph "The reconciler runs whatever the night held, and on nights the run does
      not" is replaced by what is true now: the release is the run's own first act, it happens on the
      nights the run happens, and a flag-OFF night releases nothing — which is the accepted cost
      FR-018 states.
- [ ] T018 [US4] `domain/RunReport.java`, `batch/RegisterGenerationJob.java`,
      `config/GenerationMetrics.java` — make T017 green. `reconciled` → `releasedBatches` and
      `releasedRegisters` on the record, in `RunTally` and in the `recorded(...)` format string, with
      the javadoc saying — where it says what **does** add up — that these two do not, because the
      registers they count are re-batched by the same run and are already inside `rows()`. Retire
      `GENERATION_RECONCILED` and `reconciled()`; add
      `courtregister_generation_released_batches_total` and `_released_registers_total`.
- [ ] T020 [US1] `config/GenerationConfig.java`, `config/SchedulingConfig.java` — make T019 green. The
      `generationReconciler` bean becomes `staleBatchReleaser`, taking `properties.staleAfter()` and
      `properties.lockAtMostFor()`; the job bean's `ObjectProvider<GenerationReconciler>` becomes
      `ObjectProvider<StaleBatchReleaser>`. **A wiring task's test is its context case**, which T019
      is.

**Phase close**: `flock … ./gradlew build` green; review gate. The new behaviour is live from here;
the reconciler is dead code with a timer still on it, which the next phase removes.

---

## Phase 5: User Story 4 — the removal

**Goal**: the reconciler, its timer, its lock, the query path and the deployment mode that depended
on it are gone from the source, the wiring, the stubs and the local stack.

### Tests first ⚠️

- [ ] T021 [US4] `config/GenerationWiringContextTest` and `config/CliModeConfigTest` (both extend) —
      the reconciler's absence. `no_context_holds_a_generation_reconciler`;
      `the_generation_half_carries_exactly_one_scheduler_lock`, a reflection sweep over the `batch`
      package naming the one it expects; `the_generation_half_carries_exactly_one_cron`. Red: the
      bean and the second lock exist.
- [ ] T024 [US4] `application/DocumentRendererTest` (new, reflection) and
      `adapter/systemdocgenerator/SystemDocGeneratorClientTest` (extend) — the query's absence.
      `the_renderer_port_declares_one_method`, which is what stops the query being reintroduced
      quietly; `a_whole_generation_makes_no_request_to_the_document_endpoint`, over WireMock's
      journal. Red: two methods, and the client still has the call.
- [ ] T026 [US4] `config/ConfigurationValidationTest` and `config/PublicEventsHealthIndicatorTest`
      (both extend) — the completion mode's absence.
      `a_generation_enabled_context_subscribes_without_being_told_to`;
      `a_generation_enabled_context_without_the_broker_configuration_refuses_to_start`, now
      unconditional. Red: `PublicEventsConfig` still reads `completion` and the refusal is still
      conditional.

### Implementation

- [ ] T022 [US4] Delete `batch/GenerationReconciler.java` and `batch/GenerationReconcilerTest.java`;
      delete the leftover bean in `config/GenerationConfig`; delete the `register-reconciliation`
      lock name. Make T021 green.
- [ ] T023 [US4] `batch/RunCorrelation.java`, `persistence/RegisterBatchRepository.java`,
      `application/{DocumentOutcomeSink,RegisterNotifierService,NotificationDisposition,NotificationSummary}.java`,
      `domain/{BatchStatus,RegisterBatch}.java`, `config/{SchedulingConfig,SchedulingInfrastructureConfig,
      CliModeConfig,ProcessedLogConfig,CourtRegisterProperties}.java`,
      `adapter/publicevents/DocumentEventListener.java` — **the javadoc and comment sweep**. Every
      place that names the reconciler, the grace period or the query now names what is there instead.
      `RunCorrelation`'s "two independently scheduled units … and the first calls into the second"
      becomes the run and the sweep, with the ambient-adoption branch kept and explained: the releaser
      calls `under(...)` from inside the run and adopts its id. `RegisterBatchRepository`'s three
      in-flight reads keep their claims and lose "the reconciler asks", and the paragraph documenting
      the PENDING-with-no-payload batch nothing revisits is replaced by the note that the pass now
      covers it. **A comment that describes a mechanism that no longer exists is a defect in this
      repository**, which is why this is a task and not a tidy-up.
- [ ] T025 [US4] Delete `DocumentRenderer.query`, `SystemDocGeneratorClient.query` and its answer
      parsing, `StubDocumentRenderer.query` and `domain/DocumentStatus.java`; delete the
      `GET document/{id}` WireMock mapping and its line in `docker/wiremock/README.md`. Make T024
      green. `docker/sdg-echo/sdg-echo.py` is **not** touched — it implements no query endpoint.
      `DocumentRenderer`'s javadoc drops its "two conversations" paragraph.
- [ ] T027 [US4] `config/PublicEventsConfig.java`, `config/PropertiesValidator.java` — make T026
      green. Subscribe on `generation.enabled` alone; the broker rule loses its conjunct;
      `PublicEventsConfig`'s javadoc drops the `poll-only` sentence.
- [ ] T028 [A] [US4] `adapter/stub/StubGenerationAdaptersTest`, `e2e/GenerationEndToEndIT`,
      `e2e/GenerationFailureEndToEndIT`, `config/GenerationMetricsTest`,
      `persistence/RegisterBatchRepositoryIT`, `persistence/RegisterBatchReportReadsIT`,
      `adapter/publicevents/DocumentEventListenerTest` and `…IT`, `support/GenerationLegs`,
      `support/GenerationStackSupport`, `support/GeneratedRegisters` — remove the query wiring, the
      reconciler construction, the retired counter and the `GRACE_PERIOD` constants, and record the
      observed suite result. A characterisation of the deletion: what these hold is other suites'
      scaffolding, and the behaviour is asserted by T021, T024 and T026.

**Phase close**: `flock … ./gradlew build` green; review gate. **Read coverage on this gate**: a phase
that deleted several hundred covered lines is the one shape in which the ratchet is met by accident.
Quote the numbers; do not adjust the gate.

---

## Phase 6: FR-011 — the readings survive the timer

**Goal**: the three in-flight age gauges keep their cadence, on a sweep that holds no lock and
settles nothing. **A Micrometer gauge never decays**: without this phase they would freeze at
whatever the last reconciliation saw and go on looking live.

### Tests first ⚠️

- [ ] T029 `batch/BatchAgeSweepTest` (new) — the readings.
      `the_three_gauges_report_the_age_of_the_oldest_of_each_kind`;
      `a_kind_with_nothing_in_flight_reads_zero`, which is what brings a gauge back down;
      `a_batch_holding_a_document_nobody_was_told_about_is_named_and_settled_nothing`, the WARN the
      retired pass wrote plus the assertion that no store call follows it;
      `a_read_that_refuses_keeps_the_last_value_and_is_counted`, the design rules' one absorbed
      refusal — telemetry may not cost a Youth Offending Team its e-mail — asserting the WARN names
      the failure **by class**, the counter moves, and nothing is rethrown. Seam: the class with
      `sweep()` throwing `UnsupportedOperationException`. Red: a failing assertion on the first case.
- [ ] T031 `batch/BatchAgeSweepTest` (extend), `config/ReportSchedulingConfigTest` and
      `config/CliModeConfigTest` (both extend) — the schedule it is on.
      `the_fixed_delay_reads_the_batch_age_refresh_key`, a reflection case over the annotation
      attribute, because a placeholder nobody asserts is one a later edit inlines;
      `the_sweep_names_its_own_scheduler`; `the_sweep_carries_no_scheduler_lock`, because a gauge
      describes the JVM that publishes it and a lock would make every other pod publish nothing;
      `a_command_jvm_runs_no_batch_age_sweep`; `the_context_holds_four_task_schedulers`. Red: no
      annotation, no scheduler bean, no exclusion.

### Implementation

- [ ] T030 `batch/BatchAgeSweep.java` — make T029 green. The three reads, the three
      `metrics.oldest…Age` calls, the parked-batch WARN and the one absorbed refusal with its
      counter. It holds a repository, the metrics and a clock, and no store, no renderer and no lock.
      `RunCorrelation.under(...)` **opens** an id of its own, because a sweep on its own schedule is
      a unit of work in its own right — unlike the releaser, which adopts the run's.
- [ ] T032 `config/BatchSweepConfig.java` (new), `batch/BatchAgeSweep.java`,
      `config/CliModeConfig.java` — make T031 green. The config declares `BATCH_SWEEP_SCHEDULER` and
      a single-threaded `TaskScheduler`, in the shape `IntakeSweepConfig` uses and for its reason: a
      ten-minute reading queued behind a run that is asking for renders is a reading taken an hour
      late. **This is a configuration class, so T031's context case is its test** — the live condition
      from 003's second exception.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 7: User Story 3 and FR-019 — the drop is counted, and the morning says the right thing

**Goal**: the two guarantees that keep a Youth Offending Team from being told twice and keep support
from being sent after something already put right.

### Tests first ⚠️

- [ ] T033 [US3] `application/DocumentOutcomeSinkTest` (extend) — **the drop that is not counted
      today**. `a_document_available_for_a_batch_not_completed_by_the_next_run_moves_nothing_and_is_counted`
      and `a_generation_failed_for_one_moves_nothing_and_is_counted`, each asserting the batch is
      unchanged, no notification is made, **and the ignored counter moves under `terminal-batch`**;
      `a_redelivery_of_such_an_outcome_is_counted_under_the_same_reason`, never as an unknown
      correlation. Red: the counter does not move — the current code logs at WARN and counts nothing.
- [ ] T035 `application/ExceptionReportServiceTest` (extend) and
      `batch/cli/ReportExceptionsCliTest` (extend) — **FR-019**.
      `a_batch_released_by_the_run_is_reported_as_batch_released_not_batch_failed`;
      `a_batch_that_genuinely_failed_is_still_batch_failed`;
      `the_kind_switch_stays_exhaustive`, over `nameOf(dead)` and the stage naming; and in the CLI
      suite, that the table and the CSV carry the new kind. Red: a released batch reports as
      `BATCH_FAILED` and the kind does not exist (seam: the constant).

### Implementation

- [ ] T034 [US3] `application/DocumentOutcomeSinkImpl.java`, `config/GenerationMetrics.java` — make
      T033 green. The refused-transition branch counts `courtregister_public_events_ignored_total`
      under a new bounded reason `terminal-batch`, beside the WARN it already writes. The constant's
      javadoc says why it exists and why it is not one of the notification counter's late-* labels,
      which describe two notifiers racing over one recipient and are a different event entirely.
- [ ] T036 `domain/ExceptionKind.java`, `application/ExceptionReportService.java` — make T035 green.
      Add `BATCH_RELEASED`, read over the window like the other failure kinds; derive it from the
      reason at the one place FAILED batches become entries; check the "four stages" text and any
      switch over kinds for exhaustiveness. The javadoc says what makes it different from the others:
      it is informational, because its registers were re-rendered the same night.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 8: The proof and the privacy sweep

**Goal**: the assembled behaviour, end to end, and the sweep that holds every new line to Principle
VII.

### Tasks

- [ ] T037 [A] [US1] [US3] `e2e/GenerationFailureEndToEndIT` (extend) — one case carrying **SC-001**
      and **SC-003**: a batch left GENERATING since the previous evening is released by the run, its
      registers are assembled into a new batch for the same court centre and register date, that
      batch renders and notifies, **exactly one** notification exists per recipient for that key, and
      the original batch's late `document-available`, delivered afterwards, moves nothing and is
      counted under `terminal-batch`.
- [ ] T038 [A] [US2] `e2e/GenerationEndToEndIT` (extend) — the other side of the boundary and the
      operator's batch: a batch ten minutes old is untouched and its court centre day is deferred as
      today; a `system_generated = false` batch forty minutes old is untouched because its cutoff is
      the longer one.
- [ ] T039 `config/TelemetryPrivacyTest` (extend) — the privacy sweep over the two new classes.
      `GenerationLegs` drives `StaleBatchReleaser` and `BatchAgeSweep` and no longer names
      `GenerationReconciler`; every line they produce carries only a batch id, a count and a bounded
      code, and no throwable this service did not write. Red: the drive names a class that no longer
      exists and does not name the two that do.
- [ ] T040 Make T039 green: update `support/GenerationLegs`'s drive and fix any line the sweep
      rejects. **A line that has to be changed to pass is a privacy finding** and is called one in the
      commit body, not a test adjustment.

**Phase close**: `flock … ./gradlew build` green; review gate.

---

## Phase 9: Polish — the documents, the register cell and the gates

- [ ] T041 [P] `.claude/rules/design_rules.md` — six edits and no others: the two-leg flow diagram
      loses the `GenerationReconciler` line and gains the release pass and the sweep; the batch state
      machine's `neither, past the grace period ───▶ FAILED, GENERATION_TIMED_OUT (reconciler)` arm
      becomes the one data-model.md gives; the bounded failure-reason list swaps one value for
      another; **"The reconciler invents nothing" is reworded** to the rule that replaces it — *a
      batch nothing can be learned about is failed by the next run through the store, with its rows
      released, and no outcome is applied, because there is no outcome and applying one would be
      inventing evidence*; the consumed-contracts table's systemdocgenerator row loses the query
      endpoint; and the package-structure list's `batch/` line swaps the reconciler for
      `StaleBatchReleaser` and `BatchAgeSweep`. The "one absorbed refusal" clause gains the sweep
      beside `IntakeAgeSweep`, and the "every drop is counted under a bounded reason" rule gains
      `terminal-batch`. Nothing else on the page is touched.
- [ ] T042 [P] `README.md` — the generation section's "with a grace-period reconciler for the
      outcomes that never arrive" becomes the release pass, and a Status entry for increment 004 in
      the shape 001–003 use.
- [ ] T043 [P] `specs/002-consolidate-progression-leg/quickstart.md` and
      `specs/003-exception-report/quickstart.md` — the WireMock line loses "query document"; the 003
      quickstart's sample exception event stops using the retired reason. Historical quickstarts are
      still runnable ones.
- [ ] T044 [P] `.claude/agents/{spec-validator,software-engineer,qa,code-reviewer}.md` — the scope
      paragraphs name **004-release-stale-batches** alongside the three complete increments;
      `spec-validator`'s generation-leg read list swaps `batch/GenerationReconciler` for
      `batch/StaleBatchReleaser` and `batch/BatchAgeSweep`, its contract row loses the query
      endpoint, and its "the outcome is learned, never assumed" bullet is re-pointed at the new arm.
- [ ] T045 `doc/DEFECT-FIXES.md` — the one cell. `P2`'s pinning-test list replaces
      `GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`
      with `StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`,
      and the status cell gains one dated sentence: the mechanism that keeps the promise changed in
      increment 004, from a grace-period query to the next run's release pass, and the promise — a
      failed or lost render is never silently dropped — is unchanged. **No new row, no changed claim,
      no changed status.** `RegisteredDefectFixes` green in the same commit.
- [ ] T046 [A] The gates, recorded. `flock … ./gradlew build` with the JaCoCo ratchet at **0.88 line /
      0.85 branch** unchanged, PMD over main, Checkstyle over main and test, the differential audit
      and the consolidation audit; then the `quickstart.md` walkthrough end to end on a **clean** local
      stack, including step 6's other side of the boundary and the three startup refusals. Quote the
      numbers and the observed output. **If coverage falls, it is fixed with tests, not by moving the
      gate.**

**Phase close**: `flock … ./gradlew build` green; whole-increment review gate (code-reviewer, qa,
spec-validator, then Codex) before the merge to `main`.

---

## Dependencies & execution order

```text
Phase 1  (T001-T007)  settings, vocabulary, schema
   │   nothing can write the new reason until the constraint admits it
   ▼
Phase 2  (T008-T010)  the fenced store operation          [US1]
   │   the pass has nothing to call until this exists
   ▼
Phase 3  (T011-T014)  the pass and its cutoffs            [US1, US2]
   │   the run has nothing to call until this exists
   ▼
Phase 4  (T015-T020)  the run calls it, and says so       [US1, US2, US4]
   │   the reconciler's last caller goes here; only then is it dead code
   ▼
Phase 5  (T021-T028)  the removal                          [US4]
   │   the sweep replaces readings the removal took
   ▼
Phase 6  (T029-T032)  the readings survive                 [FR-011]
   │
   ▼
Phase 7  (T033-T036)  the drop is counted; the morning kind [US3, FR-019]
   │   the end-to-end case asserts both
   ▼
Phase 8  (T037-T040)  the proof and the privacy sweep
   ▼
Phase 9  (T041-T046)  documents, the P2 cell, the gates
```

**Hard orderings inside phases**: T001→T002, T003→T004, T005→T006 (pairs); T008→T009→T010;
T011→T012→T013→T014 (T013's red depends on T012 being minimal); T015→T016, T017→T018, T019→T020;
T021→T022, T024→T025, T026→T027, and T028 after all three deletions; T029→T030→T031→T032;
T033→T034, T035→T036; T039→T040.

**Cross-phase**: T045 depends on T011 (the test it names must exist) and T022 (the test it replaces
must be gone). T037 depends on T034 (the counter it asserts). T046 depends on everything.

### Parallel opportunities per phase

```text
# Phase 1 - three independent red runs; T007 is an observation and needs none of them:
T001  config/ConfigurationValidationTest + config/ReportPropertiesTest
T003  domain/BatchFailureReasonTest + domain/BatchStateTest
T005  persistence/SchemaMigrationV2IT

# Phase 4 - T015 and T019 in parallel; T017 follows T015 (same file)

# Phase 5 - three independent absence cases:
T021  config/GenerationWiringContextTest + config/CliModeConfigTest
T024  application/DocumentRendererTest + adapter/systemdocgenerator/SystemDocGeneratorClientTest
T026  config/ConfigurationValidationTest + config/PublicEventsHealthIndicatorTest

# Phase 7 - two independent test files:
T033  application/DocumentOutcomeSinkTest
T035  application/ExceptionReportServiceTest + batch/cli/ReportExceptionsCliTest

# Phase 9 - four documentation tasks, four different files:
T041  .claude/rules/design_rules.md
T042  README.md
T043  specs/002-.../quickstart.md + specs/003-.../quickstart.md
T044  .claude/agents/*.md
```

## Implementation strategy

**MVP is Phases 1–4.** After T020 the promise is met: a stale batch is released, its court centre
gets its document that night, and the run says so. Phases 5 and 6 are the removal and its one
replacement; 7 and 8 are the guarantees and the proof; 9 is the documents.

**Two orderings to respect under pressure.** Phase 2 must not be collapsed into Phase 3 — the
atomicity is the correctness of the feature and it belongs in the store with its own gate. And Phase
5 must not ship without Phase 6: it deletes the timer that takes three continuous readings, and a
Micrometer gauge whose publisher goes away does not fall to zero, it freezes at the last value it was
given and goes on looking live.

## Notes

- **The `[A]` tasks are verification, not exemption.** T007 records an observation about Postgres;
  T028 characterises a deletion whose behaviour three other tasks assert; T037, T038 and T046 verify
  assembled behaviour. None is a licence to skip a pair that could have been formed.
- **Nothing in this increment touches the intake half**, the register document, the inbound message,
  supersession's rules, the notification leg or the cutover lever. The 07:00 report is touched in
  exactly two places (T036's kind and T002's un-borrowed threshold) and nowhere else. A task that
  finds itself editing anything else has found a dependency the plan missed and stops.
- **Three things are outside this repository** and are flagged, not done: the Confluence design
  document's sections on the reconciler and the query; the Gliffy diagram's dashed query arrow and
  its step label; and a check of the deployment branches for a raw
  `courtregister.generation.grace-period` or `.completion` override — probably empty, since neither
  key has an environment placeholder in this repository, which is also why T002 removes the old key
  rather than aliasing it.
