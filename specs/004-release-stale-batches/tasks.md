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
ends with a green `./gradlew jacocoTestReport build`, in that order** — `build` does not produce a
coverage report, only the gate that reads one, so a close that quotes ratios without asking for the
report is quoting whatever report an earlier increment left on disk — every `gradlew` behind the
shared `flock`, never two Gradle builds at once — **and a review gate in a new session**, whose findings land as a red test commit
then an implementation commit before the next phase starts. **Never two committing agents at once in
this tree.**

**Decided 2026-09-19: two migrations, not one.** The Phase 1 implementer found a genuine cycle and
declined T003-T007 twice with evidence, which was the right call. `GenerationReconciler` is the only
writer of `BatchFailureReason.GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER` (lines ~159, ~414,
~422) and is not deleted until **T022**/**T025**, so the constants cannot go in Phase 1 without
re-pointing a live write at `EVENT` and putting a false claim in the one column that exists to say
which mechanism learned an outcome. Meanwhile Phase 2's `T009` writes `NOT_COMPLETED_BY_NEXT_RUN`
over Testcontainers Postgres, so the **admission** must land no later than Phase 2. The original
FR-012 asked for the admission and the removals in *one* forward migration, and those three
sentences do not fit together in any ordering.

The design owner's answer splits the migration and changes no behaviour:

- **`V6__admit_stale_release_reason.sql` (Phase 1, T006)** — admits `NOT_COMPLETED_BY_NEXT_RUN`
  **beside** the two retired values. It removes nothing, narrows nothing, and therefore refuses on no
  existing row.
- **`V7__retire_reconciler_vocabulary.sql` (Phase 5, T049)** — lands immediately after the reconciler
  and its query leg are deleted, removes the two retired constants from the enums and narrows all
  three CHECK constraints. **This** is the migration that refuses on a pre-004 row, so the
  `docker compose down -v` note and the local-stack observation travel to Phase 5 with it.

The state the increment reaches at the end of Phase 5 is exactly the one `data-model.md` describes.
What changed is the sequencing and FR-012's wording, not the destination.

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
- [x] T003 [P] `domain/BatchFailureReasonTest` (extend) and `domain/BatchStateTest` (extend) — the
      vocabulary **gains** the new reason; nothing is taken away here (the removals are T047/T048,
      after the reconciler that writes the retired values is gone).
      `the_seven_reasons_are_the_bounded_set`;
      `not_completed_by_next_run_is_not_generator_attributed`;
      `not_completed_by_next_run_releases_its_rows`; and in `BatchStateTest`, the schema-vocabulary
      case extended so the enum and `register_batch_failure_reason_chk` are held to each other in
      **both** directions over all seven values — which is the assertion that makes T006 and T048
      each provably complete, and the one the implementer correctly said could not be green against
      a half-done vocabulary. Red: the new constant does not exist (seam: the constant).
      (red with T005 on the seam tree: 128 tests, 8 failures, 0 errors, every one an assertion.
      `the_seven_reasons_are_the_bounded_set` and
      `the_failure_reasons_should_be_exactly_the_seven_the_schema_enumerates` each on "Expecting
      actual: [… six reasons …] to contain exactly in any order: [… seven …]";
      `not_completed_by_next_run_is_not_generator_attributed` on "Expecting Optional to contain a
      value but it was empty"; `not_completed_by_next_run_releases_its_rows` on "Expecting actual:
      [PAYLOAD_STORE_UNAVAILABLE, ASSEMBLY_FAILED] to contain exactly in any order" the three;
      `the_attribution_table_should_classify_every_reason_and_no_others` and its new twin
      `the_release_table_should_classify_every_reason_and_no_others` on the same shortfall from the
      other side.
      **The seam is the constant's name, not the constant.** A test that named
      `BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN` could not compile before T004, and the
      convention forbids a compile error as a red run — so both tables are keyed by the constant's
      *name*, which is what reaches `register_batch.failure_reason`, a metric label and the run
      report anyway, and a reason can therefore be specified here before it exists. `reasonNamed`
      looks the constant up in `values()`, so its absence is a failing assertion about the
      vocabulary.
      Three deviations, all additive. First, `BatchFailureReasonTest.ATTRIBUTION` is re-keyed from
      the constant to its name for the reason above, and gains `RELEASES_ROWS` beside it —
      data-model.md's "releases rows" column, held to `values()` in both directions and cross-checked
      against `isGeneratorAttributed()` by `an_attributed_reason_should_never_release_its_rows`, an
      ending somebody else reported having nothing to give back. `JdbcRegisterStore.RELEASING_REASONS`
      is private and is Phase 2's to change, so the table is the classification's home until
      `RegisterStoreIT` observes the release over a real batch at T008.
      Second, `the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates` is renamed for
      the seventh value and now **reads the migrations** rather than carrying a hand-transcribed
      copy of the constraint: `schemaFailureReasons()` concatenates `db/migration/V*.sql` in version
      order, takes the last definition of `register_batch_failure_reason_chk` and extracts its `IN`
      list. A hand-written list agrees with whatever it was typed from and cannot make T006 provably
      complete; the constraint's own text can. The case is therefore red from the constant landing
      until V6 lands.
      Third, `BatchStateTest` gains four private helpers and the imports they need; nothing in the
      state machine or the flag-decision nests is touched.)
- [x] T005 [P] `persistence/SchemaMigrationV2IT` (extend) — what V6 must make true, and what it
      must leave alone. `v6_admits_not_completed_by_next_run` (a FAILED batch under the new reason
      with a null attribution succeeds); `the_new_reason_refuses_an_attribution`, which
      `register_batch_completed_by_shape_chk` must enforce for it exactly as it does for the four
      other unattributed reasons; `v6_still_admits_the_retired_timeout_reason` and
      `v6_still_admits_the_retired_attribution`, which pin that this migration **widens only** — a
      narrowing here would refuse on a row the reconciler is still writing until Phase 5. Red: the
      first two fail against V1–V5.
      (red with T003 on the seam tree, in the same 128-test run: `v6_admits_not_completed_by_next_run`
      on "Expecting code not to raise a throwable but caught … violates check constraint
      \"register_batch_failure_reason_chk\"", and `the_new_reason_refuses_an_attribution` on the same
      refusal of its precondition row. The last two are green against V1–V5 and are meant to be:
      they assert what V6 must **not** change, so a green before and a green after is the whole
      claim, and a red on either would mean the retired vocabulary had already gone.
      One deviation, and it is what the task predicted only half of. Written as a bare refusal,
      `the_new_reason_refuses_an_attribution` **passed** against V1–V5: the attributed row violates
      the vocabulary constraint *and* the shape constraint, Postgres reported the shape one, and the
      assertion matched. A case that passes against the code it is written to change proves nothing,
      so the admitted row was made its explicit precondition — the reason is admitted, therefore the
      refusal that follows is about the attribution alone — which is both the truer statement and a
      real red.)
- [~] T007 **Moved to Phase 5 (T050)** — the local stack's clean-store step. Nothing in Phase 1
      needs it: `V6` widens only and refuses on no existing row, so there is no volume to clean
      before it. The observation belongs to `V7`, which is the migration that narrows, and it travels
      there with it. The id is kept and left here as the pointer; nothing is renumbered.

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
- [x] T004 `domain/BatchFailureReason.java` — make T003 green. Add `NOT_COMPLETED_BY_NEXT_RUN`
      with the javadoc data-model.md gives it: this service's own verdict, releasing, and naming no
      completion mechanism. `isGeneratorAttributed()` is **unchanged** here — it already answers
      `false` for a reason it does not name, and narrowing it is T048's, once the mechanism it names
      no longer exists. `CompletedBy` is not touched in this phase.
      (red re-run before the change, `BatchFailureReasonTest` and `BatchStateTest`: 50 tests,
      6 failures, 0 errors, every one an assertion — `not_completed_by_next_run_is_not_generator_attributed`
      on "Expecting Optional to contain a value but it was empty", the two table cases and the two
      bounded-set cases on the missing seventh name.
      green: 51 tests, 1 failure, 0 errors. `BatchFailureReasonTest` is 14 of 14, so every claim
      the enumeration can answer on its own is green. The one remaining failure is
      `the_failure_reasons_should_be_exactly_the_seven_the_schema_enumerates`, and it is the red
      T003 predicted for exactly this window: its **first** assertion — the enumeration holds the
      seven — now passes, and it fails on its **second**, "the values
      register_batch_failure_reason_chk admits after every committed migration", because V6 has not
      landed. The constant landing before its migration is the half-done vocabulary that case exists
      to refuse, and it goes green at T006. `checkstyleMain` and `pmdMain` green.
      Two deviations, both prose and both forced by the seventh constant. The type javadoc's "the
      six are six different investigations" becomes seven and names the new grouping, since only the
      first pair *and the last* leave their rows RECORDED; and `isGeneratorAttributed()`'s "the
      other four are this service's own verdict" becomes five, "could not ask for, could not hear
      about, or stopped waiting for". Neither sentence is a rule anything reads; the method's
      expression is untouched and `CompletedBy` is not opened.)
- [x] T006 `src/main/resources/db/migration/V6__admit_stale_release_reason.sql` — make T005 green.
      One statement: `register_batch_failure_reason_chk` is replaced by the same list plus
      `NOT_COMPLETED_BY_NEXT_RUN`, the two retired values still in it. The two attribution
      constraints are **not** touched: the new reason is not generator-attributed, so it falls in
      `register_batch_completed_by_shape_chk`'s third arm's `false = false` case with no edit.
      Additive and forward-only; `V2` is not edited; no column, table or index is added; and because
      it only widens, it applies to any store in any state.
      (red before the migration, `SchemaMigrationV2IT` over Testcontainers Postgres: 78 tests,
      3 failures, 0 errors, every one an assertion. `v6_admits_not_completed_by_next_run` and
      `the_new_reason_refuses_an_attribution` each on "Expecting code not to raise a throwable but
      caught … violates check constraint \"register_batch_failure_reason_chk\"" — the second on its
      precondition row, so the refusal it goes on to make is about the attribution alone; and
      `failure_reason_check_should_name_exactly_the_bounded_reasons`, which reads the live
      constraint against the enumeration and went red the moment T004 landed the constant, on
      "Expecting actual: \"CHECK (((failure_reason IS NULL) OR (failure_reason = ANY (ARRAY[… six
      …]))))\" to contain … NOT_COMPLETED_BY_NEXT_RUN". `v6_still_admits_the_retired_timeout_reason`
      and `v6_still_admits_the_retired_attribution` were green before and are green after, which is
      their whole claim: this migration takes nothing away.
      green: `SchemaMigrationV2IT`, `BatchStateTest` and `BatchFailureReasonTest` together,
      129 tests, 0 failures, 0 errors — so the two directions of the vocabulary cross-check close on
      the same commit, `the_failure_reasons_should_be_exactly_the_seven_the_schema_enumerates`
      against the migration text and
      `failure_reason_check_should_name_exactly_the_bounded_reasons` against the constraint Postgres
      actually holds. The migration itself has no deviations: one dropped constraint, one added, the
      same list plus the new value, and neither attribution constraint opened.
      One file beyond it had to move, and the full build is what found it.
      `persistence/SchemaMigrationV5IT` snapshots the schema at V4 and again after
      `flyway().load().migrate()` — the **head**, not V5 — and then asserts that the difference is
      five indexes and no constraint. That was right only while V5 was the last migration; with V6
      applied the suite was making a claim about every migration since V4 and failed on the
      constraint V6 widened. It is pinned to `target("5")`, which is what `SchemaMigrationV4IT`
      already does at the same line and for the same stated reason, and the only alternative — to
      stop asserting that a migration changed nothing else — would give up the suite's whole
      subject. No assertion is relaxed and the V6 cases are untouched.
      The full build also surfaced a `pmdTest` failure this range did not cause: T003's three new
      `BatchStateTest` fields — `MIGRATIONS`, `FAILURE_REASON_CHECK`, `QUOTED_CODE` — landed below
      `drawnMoves()`, three `FieldDeclarationsShouldBeAtStartOfClass` violations, and `pmdTest` runs
      in `build`. They are moved above the first method and nothing else about them changes.
      `pmdTest`, `checkstyleTest` and `BatchStateTest` green.)

**Phase close**: `flock … ./gradlew build` green; review gate.
(green at `20bab2b`: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` BUILD SUCCESSFUL,
exit 0, 3630 tests over 576 suites, 0 failures, 0 errors; `checkstyleMain` and `checkstyleTest` at
`maxWarnings = 0`, `pmdMain` and `pmdTest`, and `jacocoTestCoverageVerification` against the
unchanged gate of LINE 0.88 / BRANCH 0.85 — none of them loosened. **No ratio is quoted for this
tree, because none was measured on it**: `build` runs the gate and not the report, and the figures
this close first carried were read off a report increment 003 had left in `build/`. What ran here
is the gate, and it passed. The
phase closes on the tree as committed, which is what the two commits beyond T004 and T006 were for:
`290d898` pins `SchemaMigrationV5IT` to V5, and `20bab2b` moves `BatchStateTest`'s three
migration-reading fields above the first method. Review gate to follow.)

**Review gate 1 ran against the committed Phase 1 content** with three read-only reviewers plus
Codex. One finding above LOW, and where it was closed:

* nothing observed **V6's footprint**. T006's narrative claims "no column, table or index is added"
  and that neither attribution constraint was opened, but V4 and V5 each have a snapshot-diff suite
  saying so of themselves and V6 had none — and pinning `SchemaMigrationV5IT` to V5 removed the one
  head-running suite that would have failed noisily on a V6 that added anything else. Closed by
  `persistence/SchemaMigrationV6IT`, in the `SchemaMigrationV4IT`/`V5IT` shape and pinned at **both**
  ends (`target("5")` then `target("6")`), at `e4ddb89`, with the Test Matrix row in the same commit.
  A pin of an already-correct migration cannot be red against the tree, so it was made red against
  the migration instead: V6 was temporarily given an extra `ADD COLUMN` (red on
  `v6_should_add_no_column_table_or_index`, `register_batch.stale_note` in the diff), then a
  re-spelled `register_batch_completed_by_chk` (red on
  `v6_should_rewrite_only_the_failure_reason_check`). The second mutation is what earned the suite
  its shape: `v6_should_leave_every_other_constraint_untouched` was first written subtracting the
  diff from both snapshots, which makes the two sets equal by construction, and it **passed** the
  mutation. It now subtracts the one constraint V6 may rewrite **by name**, and both cases are red
  against that mutation. Green against the tree as committed: 4 of 4.

Two LOW findings were closed here as well, both cheap and both in files this phase already owns:

* `failure_reason_check_should_name_exactly_the_bounded_reasons` said "exactly" and asserted
  `contains`, which proves every constant reaches the database and nothing at all about a code the
  column admits and no constant names. The codes are now taken out of the live definition and
  compared `containsExactlyInAnyOrder` against `BatchFailureReason`, which is the direction **V7**
  needs and which `BatchStateTest` cannot supply, reading the migration *files* rather than the
  database they were supposed to produce. Red against a V6 temporarily carrying an eighth code
  (`ABANDONED`), green against the tree: 78 of 78. At `69dee54`.
* V6's comment said an attributed row under the new reason evaluates as `true = false` in
  `register_batch_completed_by_shape_chk`'s third arm. It evaluates as `false = true` — the reason
  is not in that arm's attributed list, and the attribution is present — and the same sentence sat
  in `the_new_reason_refuses_an_attribution`'s javadoc, where it also conflated the refused row
  with the admitted one. Both corrected; the row is refused either way and no behaviour changes.
  Editing V6's text changes its Flyway checksum, which is safe only because V6 has been applied to
  no environment: **V6 is frozen from here**, and the same correction after cutover would have been
  a V8. At `ecc3309`.

The remaining LOW findings are left, each with its reason, for the reviewers to re-judge: the five
stale reason-cardinality sentences (`JdbcRegisterStore` 586 and 1524, `RegisterStore` 295,
`RegisterStoreIT` 1777, `GenerationMetricsTest` 156) and `RELEASING_REASONS` itself belong to the
Phase 2 commit that opens `JdbcRegisterStore` — T008/T009 — and the enum shrinks back to six at
T048; `design_rules`' six-reason list is T041's; and renaming the Phase 1 cases to the `should_`
form would rewrite the red runs recorded against their current names at T003, T005 and T006.

Green after the remediation: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` BUILD
SUCCESSFUL, exit 0, 3634 tests over 577 suites, 0 failures, 0 errors — four more than the phase
close, being `SchemaMigrationV6IT`'s — with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest`
and `jacocoTestCoverageVerification` all green and none of them loosened.

---

## Phase 2: User Story 1 — the fenced store operation (Priority: P1) 🎯 MVP

**Goal**: one statement that fails every stale batch and releases its registers, atomically, fenced
on the staleness rule itself. **This is the correctness of the whole increment** and it is a phase of
its own for that reason.

**Independent test**: `RegisterStoreIT` and `StaleReleaseConcurrencyIT` against Testcontainers
Postgres. No pass, no run, no Spring context.

### Tests first ⚠️

- [x] T008 [US1] `persistence/RegisterStoreIT` (extend) — the predicate and the write.
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
      (red: `./gradlew test --tests '*RegisterStoreIT*' -Dtest.noFailFast=true`, 83 tests,
      **11 failures**, 0 errors - the eleven new cases and nothing else, every failure an assertion.
      The first case's second failure is the property under test:
      "Expecting actual: Optional[BatchOutcome[status=GENERATING, failureReason=null,
      sdgReason=null]] to contain: BatchOutcome[status=FAILED,
      failureReason=NOT_COMPLETED_BY_NEXT_RUN, sdgReason=null] but did not", and its third is
      "expected: 0L but was: 2L" on the stamped rows - the mark and the release, each named
      separately, because a mark without its release is the lost register this increment exists to
      end. The seam refusal is recorded as each case's *first* soft failure rather than as a stack
      trace out of the arrangement, which is what the suite's soft-assertion convention is for.
      Seams: `application/ReleasedBatch` (new record), `RegisterStore.failAndReleaseStale`, and
      `JdbcRegisterStore.failAndReleaseStale` throwing `UnsupportedOperationException`.
      Three deviations from the task's letter, each because the property could not otherwise be
      red. **The ages are written, not waited for**: `ageBatch` moves a batch's `assembled_at` and
      `requested_at` back by the database's own clock, and every cutoff a case states is in the
      **past** - a cutoff in the future would name every batch in the shared container, including
      the ones another suite is holding. **The COALESCE direction is pinned inside the first case**
      rather than as a case of its own: it ages `assembled_at` alone, asserts nothing matched, then
      ages `requested_at` and asserts the release. **The transaction case refuses the release on
      purpose**, under a partial unique index on this court centre that admits one unbatched
      register, and then asserts the mark went down with it - a single-threaded case cannot
      otherwise observe that there is no intermediate state, and the second half of the same case
      (index dropped, the same call made again) is what is red against the seam.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green; `pmdTest` needed the
      fixture's row count taken out of the `if` as `ONE_BATCH`, in the constant block at the top of
      the class, for the reason T003's three fields were moved there.)
- [x] T010 [US1] `persistence/StaleReleaseConcurrencyIT` (new) — **SC-009**, the reason the operation
      is fenced. `a_render_acceptance_racing_the_release_leaves_no_stranded_register` and
      `a_document_arrival_racing_the_release_leaves_no_stranded_register`, each run in **both**
      winner orders and repeated, asserting after every round that no `register_record` is stamped to
      a batch in a terminal state, that at most one live batch exists per (court centre, register
      date), and that at most one notification aggregate exists per key. Red: against T009's
      implementation this passes; **against a deliberately staged read-then-mark variant it does
      not**, and the task records that staged failure as its red, because the assertion being made is
      about the shape of the operation and a test that cannot fail against the wrong shape proves
      nothing. The staged variant is not committed.
      (red: `./gradlew test --tests '*StaleReleaseConcurrencyIT*' -Dtest.noFailFast=true`, 2 tests,
      **2 failures**, 0 errors, **7 failing assertions each** - six rounds' "the loser of a race is
      refused by the state machine and by nothing else" ("Expecting actual throwable to be an
      instance of: java.lang.IllegalStateException but was:
      java.lang.UnsupportedOperationException"), plus the staged first round's ending: "expected:
      Ending[status=FAILED, failureReason=NOT_COMPLETED_BY_NEXT_RUN] but was:
      Ending[status=GENERATED, failureReason=null]".
      **The staged read-then-mark variant is T009's, not this task's, and the task text is amended
      to say so.** The variant cannot be staged before the statement it is a mutation of exists:
      there is nothing to take apart at this point in the phase. The red recorded here is therefore
      the seam's, captured as an assertion rather than as a thrown refusal - the contenders' results
      are collected by `escaping(...)`, which is what the suite is about anyway, since the loser of
      a race is refused and that refusal is the subject. The mutation that proves the suite can
      fail against the wrong *shape* is run at T009 and recorded in its narrative, in the shape
      review gate 1 used for `SchemaMigrationV6IT`.
      Each round mints its own court centre and every cutoff stated is in the past, so no round can
      reach a batch another suite sharing the container is holding; `settleTheNight` then runs the
      rest of the night - a GENERATED batch is notified once, registers the pass gave back are
      assembled, rendered and notified - so the "one notification aggregate per key and address"
      invariant has rows to count rather than being an assertion about an empty table.
      `checkstyleTest` and `pmdTest` green: `concurrently` holds its pool in a try-with-resources
      (`CloseResource`) and `escaping` uses AssertJ's `catchThrowable` rather than a
      `catch (RuntimeException)` (`AvoidCatchingGenericException`, `OnlyOneReturn`).)

### Implementation

- [x] T009 [US1] `application/RegisterStore.java`, `persistence/JdbcRegisterStore.java` — make T008
      green. The port method and its `ReleasedBatch` answer; the single statement of data-model.md,
      with `COALESCE(requested_at, assembled_at)` and the `system_generated` `CASE`; the reuse of
      `MARK_FAILED`'s existing release-and-supersede branch over the matched set; and
      `NOT_COMPLETED_BY_NEXT_RUN` added to `RELEASING_REASONS`, so a per-batch `markFailed` from the
      operations surface releases on it too. The javadoc states the fence and says what a
      read-then-mark shape would cost — a stranded register that `activeUnbatched` can never see, and
      a refused transition that ends a night — because that is the reason the method exists at all
      rather than being a loop in the caller.
      (green: `./gradlew test --tests '*RegisterStoreIT*' --tests '*StaleReleaseConcurrencyIT*'
      -Dtest.noFailFast=true`, 85 tests, 0 failures, 0 errors — `RegisterStoreIT$StaleRelease` 11 of
      11 and `StaleReleaseConcurrencyIT` 2 of 2, with the other fifteen nested suites unchanged.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green.
      **The predicate is in the UPDATE's own `WHERE`, not in a CTE that feeds it**, and that is a
      correctness point rather than a style one. Under READ COMMITTED an `UPDATE` that meets a row
      another transaction has just committed re-evaluates *its own* qualification against the new
      row version and skips it where it no longer matches. A staleness rule computed in a preceding
      `SELECT` is evaluated once, against the statement's snapshot, and the update would then fail a
      batch whose render had been accepted in between. The fence is the re-check, so the rule has to
      be written where the re-check can see it.
      **The count answered is the registers still the day's to render**, which excludes one a
      re-share superseded as its stamp was cleared: FR-009 says the released registers are already
      inside the run's row totals because the same run re-batches them, and a superseded register is
      not one anything will re-batch. Recorded here as a decision, since the FR does not spell it
      out.
      `attributionOf` is **not** called by this statement — there is no batch identity to name
      before the rows are chosen. The statement writes `completed_by = NULL` and
      `register_batch_completed_by_shape_chk` is what refuses the contrary, which is the same rule
      rather than a second one. Where data-model.md says the reason is "called with `null`" it is
      describing an operator's per-batch `markFailed`, and that path is covered by the reason
      joining `RELEASING_REASONS`.
      The six stale reason-cardinality sentences review gate 1 left for this commit are re-pointed
      here: `RegisterStore` 295 and 330, `JdbcRegisterStore` 587, 639 and 696,
      `RegisterStoreIT`'s `Failure` javadoc and `GenerationMetricsTest`'s series-count comment.
      `JdbcRegisterStore` 1524's "the four reasons `RELEASING_REASONS` does not name" is **left
      alone**: seven less three is still four, and it was already right.
      **T010 was strengthened in this commit, and it had to be.** Staging the read-then-mark
      variant — the read, a 150 ms window, then a per-batch `markFailed` — showed the suite as
      committed at `dc4106c` **passing against it**: the loser's refusal is an `IllegalStateException`
      either way, no register is stranded without a crash, and nothing is notified twice because the
      batch the variant wrongly failed never reaches the notifier. Two assertions were added, and
      both are red against the variant: the pass's own escapes must be **empty** (FR-003a's "no
      single batch's outcome may end the run" — the variant's `markFailed` throws "batch … may not
      move from GENERATED to FAILED" straight out of the pass, which run inline in `generate()` costs
      every court centre its document), and **no batch may end FAILED under the new reason with a
      stamp later than the cutoff the round gave the pass** — which is the fence itself, and which
      the variant breaks in the render-acceptance race by failing a batch whose render had just been
      accepted. Mutation result: 2 tests, 2 failures, 3 and 4 failing assertions, all on the
      `TOGETHER` rounds. Reverted; green against the tree as committed. The variant is not
      committed.
      Per-method outage translation for `failAndReleaseStale` is covered by `StoreOutageTest` plus
      inspection of the call site rather than by a case of its own: the translator's seven cases
      hold it to its own rules and name no store method, and every statement in this class is made
      through `StoreOutage.translating`. A case over a closed `DataSource` would be the stronger
      proof and is not here.)

**Phase close**: `flock … ./gradlew jacocoTestReport build` green; review gate. **This gate is the
one to read closely**: everything after it assumes the statement is atomic and fenced.
(at the tree carrying T008, T010 and T009 — `e1ac365` — `flock -w 7200 … ./gradlew build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 5s, 3647 tests over 579 suites, 0 failures,
0 errors, `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all green and none of them loosened. No migration was added: the
phase writes `NOT_COMPLETED_BY_NEXT_RUN`, which V6 already admits, and adds no column, table or
index.
**This close is superseded by review gate 2's, and it is worth saying why rather than quietly
replacing it.** The tree it was taken on carried a production defect the suite did not yet ask
about — the re-share race below — so a green suite was not evidence that the statement was fenced,
only that nothing had asked. And the coverage ratios it quoted, LINE 0.9690 / BRANCH 0.8986, were
read off a report increment 003 had left in `build/`: `build` runs the gate and not the report, so
a close that quotes ratios without asking for one is quoting whatever is on disk. The close that
counts is under review gate 2 below, and the convention at the top of this file now names
`jacocoTestReport build`.)

**Review gate 1 ran against the committed Phase 2 content** with three read-only reviewers plus
Codex. What it found above LOW, and where each was closed:

* `StaleReleaseConcurrencyIT`'s **notification invariant was vacuous in the rounds the pass won**.
  A round the race left GENERATING had no half of the night left to run, so the day ended having
  told nobody and "no Youth Offending Team holds two aggregates for one register date" was
  satisfied by an empty list. Closed at `e1ac365`: the fixture now walks whatever the race left
  owed — PENDING, GENERATING or holding a document — to its document and its one e-mail, exactly as
  the renderer and the notifier do, and the invariant is read as the one aggregate SC-009 asks for.
  The same commit corrects `escaping()`'s javadoc, which still named `assertInvariants` by a
  signature that changed when the cutoff and the escapes became its arguments.
* **The re-share race** (HIGH at Codex, MEDIUM at `code-reviewer`) was **not closed at this gate**.
  It is closed at gate 2 below, where every reviewer re-raised it.

**Review gate 2 ran against the same phase after that remediation**, three read-only reviewers plus
Codex. What it found above LOW, and where each was closed:

* **The re-share race** — BLOCKER at Codex, HIGH at all three reviewers, and the finding this gate
  exists for. `failAndReleaseStale` is one statement and a statement reads one snapshot, so a
  hearing re-shared after this one began is a successor the `stamped` search cannot find, however
  plainly it is one by the time the write lands. The release then cleared the stale register's
  stamp *beside* the replacement it could not see, `idx_output_active_register_key` refused the
  second active row for the key, and the `DuplicateKeyException` escaped the pass — which, run
  inline in the night's generation, costs every court centre its document (FR-003a) and rolls back
  every other court centre's release with it.
  Pinned first, at `98d8826`, by `StaleReleaseConcurrencyIT`'s third contender and its
  `INSIDE_THE_WINDOW` round: the batch row the statement writes first is held `FOR UPDATE` by a
  session of its own, which stops the statement after its snapshot and before its release; the
  re-share is committed against the held statement and the row let go. Red on five assertions, all
  on that round — the pass escapes rather than escaping nothing, the batch is left GENERATING
  instead of FAILED under `NOT_COMPLETED_BY_NEXT_RUN`, the key holds three live registers rather
  than two, the re-shared hearing holds two active registers rather than one, and one address holds
  two notification aggregates.
  Closed at `9811570` by the idiom `recordAndComplete` already uses for the same index one method
  above: catch `DuplicateKeyException`, and where it `violates(…, ACTIVE_ROW_KEY)` make the whole
  statement again on a fresh snapshot — which has the re-share in it and supersedes against it — up
  to `RECORD_ATTEMPTS` times; a refusal on any other key is the store saying the write may never be
  made and is rethrown as itself. Each attempt is its own transaction, which the javadoc states
  along with the corollary that the call may not be put behind an outer transaction, or the first
  refusal would abort it and all three attempts would fail inside it. Green: `StaleReleaseConcurrencyIT`
  3 of 3, `RegisterStoreIT` 83 of 83.
  **One branch of that fix went in untested and left untested**, recorded here so the next reviewer
  does not re-derive it: `9811570`'s exhaustion path threw `StoreContendedException` after
  `RECORD_ATTEMPTS` refusals, and the only round that reached the retry — `INSIDE_THE_WINDOW` — pins
  one refusal followed by a successful attempt, so nothing ever drove the throw. It was deleted at
  gate 3 (`425beec`), still untested, and what replaced it is pinned by
  `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released`. HEAD is clean; the
  range's history carries one production branch no test ever ran.
* **What escapes an exhausted retry** — the decision gate 1 was asked to re-judge, and the
  reviewers split on it. Codex and `qa` accepted `ConcurrencyFailureException` provided the port
  said so; `spec-validator` refused it, on the ground that the class which has to decide is Phase
  3's `StaleBatchReleaser` in `batch/`, which may name no `org.springframework.dao` type
  (constitution Principle V) and could therefore only catch it as `RuntimeException` — the catch
  that swallows every programming error beside it. The narrower reading wins, because it is the one
  that satisfies all three: exhaustion escapes as a new `domain/StoreContendedException`, beside
  `StoreUnavailableException` and `StoreRefusedRowException`, and `RegisterStore`'s `@throws` says
  what it is and that the **pass**, not the port, decides between reporting it as a pass that
  released nothing and rethrowing it. `recordAndComplete` keeps `ConcurrencyFailureException`
  deliberately: its contention is settled by the listener's `catch (RuntimeException)` and never
  crosses into `batch/`.
* **The coverage figures at both phase closes had never been measured on the trees they describe**
  (MEDIUM at `code-reviewer`, `qa`, `spec-validator` and Codex). Phase 2's are requoted below from
  a report regenerated by `jacocoTestReport build` on the tree that carries the fix. Phase 1's
  cannot honestly be requoted — its tree is two remediations gone — so that close now states the
  gate that actually ran, and no ratios. The convention at the top of this file names
  `jacocoTestReport build`, in that order, so the trap does not recur.
* **This range had no full-build evidence at all**: `build/` held one filtered red run and a report
  from 2026-09-15. Closed by the run recorded below, whose XMLs are left in place.

Six LOW findings were closed here as well, all of them raised by more than one reviewer:

* `the_mark_and_the_release_are_one_transaction` accepted any `RuntimeException`. It now names
  `DuplicateKeyException` **and the index that refused it**, which is also what pins the other half
  of the retry: a refusal on a key other than the active-register one is rethrown as itself rather
  than made again three times.
* the inclusive `<=` staleness boundary the spec decides was asserted nowhere, because every case
  ages a batch by a duration against a cutoff taken from its own clock and the two can never be the
  same instant. A `stampBatch` fixture writes both in-flight stamps to an instant the case names,
  and `a_batch_stamped_exactly_at_its_cutoff_is_stale` puts one batch on the cutoff and one a
  second inside it.
* "never matched" pinned GENERATED alone, while the predicate is PENDING and GENERATING and nothing
  else. `no_batch_a_run_has_finished_with_is_ever_matched_at_any_age` stands an aged FAILED batch
  and an aged NOTIFIED one beside the day still waiting: re-failing the first would overwrite the
  reason support reads and hand back registers a resend may be about, and the second would say a
  night that worked did not.
  All three are at `2d2413c`, and all three are mutation-checked: widening the predicate to admit
  FAILED and NOTIFIED and narrowing `<=` to `<` fails four cases, the two new ones among them.
* `plan.md`'s Atomicity row said "exactly one live batch per key" where the suite asserts at most
  one — a released day has none until it is re-assembled — and did not name the third contender.
  Both corrected.
* the hard ordering above read T008→T009→T010; the phase was executed, and had to be executed,
  T008→T010→T009. Corrected.
* T009's narrative now records that per-method outage translation for this method is covered by
  `StoreOutageTest` plus inspection rather than by a case of its own.
* `spec.md`'s FR-009 now carries the decision T009's narrative had been the only record of: the
  registers counted are those still the day's to render, and one a re-share superseded during the
  release is not among them because nothing will re-batch it.

Two LOW findings are left, each with its reason, for the reviewers to re-judge: Codex's note that
`COALESCE(requested_at, assembled_at)` leans on a timestamp/state shape neither the columns nor
`RegisterBatchRepository.insert` enforce — latent, reachable only through a row no ordinary flow
writes, and the fix is a constraint rather than a predicate, so it belongs with the schema work
rather than inside this phase's statement; and Codex's note that
`StaleReleaseConcurrencyIT`'s `allSatisfy` on the losing contender's escapes passes on an empty
list and that live-batch uniqueness is read after settlement — both are about the two committed
rounds' assertions rather than about the pass, and tightening them means teaching the fixture which
contender was let go first, which is what `Order` deliberately does not decide for the `TOGETHER`
rounds.

**Green after the remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 14s, **3650 tests over 579 suites, 0 failures,
0 errors** — three more than the superseded close, being `StaleReleaseConcurrencyIT`'s third case
and `RegisterStoreIT$StaleRelease`'s two — with `checkstyleMain` and `checkstyleTest` at
`maxWarnings = 0`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all green and none of
them loosened. The coverage report was regenerated in that same run and reads **LINE 6572/6782 =
0.9690 and BRANCH 1994/2218 = 0.8990** against the unchanged gate of LINE 0.88 / BRANCH 0.85; it
contains `failAndReleaseStale`, which is how a reader can tell it is this tree's report and not an
earlier increment's. That run was made on the tree this gate's five commits produce, before this
record was written into it; the same command was then made again against the tree **as committed**
and answered identically — BUILD SUCCESSFUL, exit 0, 10m 19s, the same 3650 over 579 and the same
two counters — and it is that second run's XMLs and report that are left in `build/` for the next
gate to read.

**Review gate 3 ran against the same phase again**, and every reviewer's remaining finding turned
on one question gate 2 had left open: what the operation does with a batch it cannot release. The
design owner settled it on 2026-09-20, and the settlement changed the operation's shape rather than
only its `@throws` line.

* **The unit of atomicity was the whole pass, and it should be one batch** — the finding gate 2's
  fix had made visible without closing. `9811570` retried the statement on the active-row key, but
  the statement was still one `UPDATE` over *every* stale batch: a refusal met on one court centre's
  registers rolled back every other court centre's release with it, and an exhausted retry then left
  the run with an exception. Two different ways for one hearing shared at the wrong moment to cost
  the whole country its documents, and FR-003a forbids both.
  The staleness predicate is now read once, into a list that **decides nothing** — every batch it
  names is judged again by the statement that writes its row, so a batch that stopped being stale in
  between matches nothing exactly as before — and each batch is then failed and released by a
  statement of its own, narrowed by batch id, in its own transaction, with its own bounded retry.
  The fence is unchanged and deliberately so: the rule stays in the `UPDATE`'s own `WHERE`, which is
  where READ COMMITTED re-evaluates it against the row as it stands.
* **Exhaustion is reported, never thrown.** A batch whose every attempt met the same refusal is left
  exactly as it was found and named on `StaleReleaseOutcome.contended()`; the operation goes on to
  the batches after it and answers normally. `StoreContendedException`, which gate 2 had introduced
  for the opposite decision, has no writer and no reader and is deleted. The port's javadoc and
  FR-003a say what happens instead, and T011/T012 carry the pass's half of it: the contended batches
  are counted, said at WARN, and the run goes on to assemble.
  The gate-2 reasoning that produced the exception is not wrong and is worth keeping in view — a
  `batch/` class may name no `org.springframework.dao` type, so the persistence layer does have to
  translate. What changed is that there is now nothing to translate: the operation no longer has a
  failure to hand up, because no one batch's ending is the operation's ending.
* **The pinning test, and one deviation from the decision's letter.** The decision named a
  `StaleReleaseConcurrencyIT` round committing a fresh re-share inside every one of the
  `RECORD_ATTEMPTS` windows, staged with the `INSIDE_THE_WINDOW` fixture. That was **built and
  abandoned**, and the reason is recorded here because it is a fact about the fixture rather than a
  preference. Chaining the windows means the holder of window *k+1* must own the batch row before
  the attempt that follows window *k* asks for it, and the gap between a refused attempt's rollback
  and the next holder's grant cannot be closed from the test: the attempt is a client round trip and
  the grant is a server wakeup, and the run recorded window 3's attempt slipping past its holder
  while windows 1 and 2 held. A round that passes on the scheduler's goodwill is not a pin.
  `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released` stages the same refusal
  **as data**: a share of the batch's own hearing stamped *earlier* than the register the release
  would have to give back. The recorder writes it active — a batched register is not its to
  supersede — and the release's successor search is the mirror of the same ordering, so it is never
  a successor. Every attempt meets the second active row for the key, and no fresh snapshot helps.
  That is a share delivered out of order, which a broker that redelivers produces. A second court
  centre's stale batch stands beside it and is failed and released anyway, which is the isolation
  itself; the contended one is named, untouched, and nothing escapes.
  `a_batch_contended_once_is_released_by_the_attempt_that_follows` keeps the windowed fixture for
  the case it is reliable for — one refusal, then the attempt that reads a snapshot the re-share is
  in — and asserts it on the answer rather than on the rows.
  **The red run is the isolation's own evidence.** Against the seam, the out-of-order key did not
  only fail its own round: the store-wide statement carried the refusal into every other suite
  sharing the container, and `RegisterStoreIT$StaleRelease` and all three existing concurrency
  rounds went red with "a register was re-shared inside the statement's own window on each of 3
  attempts … so none of them was released". That is the blast radius the finding is about, observed
  rather than argued. Green after the fix: `RegisterStoreIT` 85 of 85 (`StaleRelease` 13 of 13),
  `StaleReleaseConcurrencyIT` 5 of 5. (The 83 quoted at gate 2 is correct for the moment it
  describes: `2d2413c` added the boundary and finished-batch cases after it.)
  The round gives the held key up when it is done, because the operation answers for the whole
  store: a key left held would have every other suite spend its three attempts on this round's batch
  at every call.
* The LOW findings gate 3 listed were **already closed at gate 2** and were re-checked rather than
  re-done: `DuplicateKeyException` named with its index at `RegisterStoreIT:3378`, the `<=` boundary
  at `a_batch_stamped_exactly_at_its_cutoff_is_stale`, the aged FAILED and NOTIFIED batches at
  `no_batch_a_run_has_finished_with_is_ever_matched_at_any_age`, `plan.md`'s "at most one live batch
  per key", the T008→T010→T009 hard ordering, and Phase 1's close stating the gate that ran rather
  than ratios it never measured.

**Green after gate 3's remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 18s, **3652 tests over 579 suites, 0 failures,
0 errors** — two more than gate 2's close, being the two new rounds — with `checkstyleMain` and
`checkstyleTest` at `maxWarnings = 0`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all
green and none of them loosened. The coverage report was regenerated in that same run and reads
**LINE 6589/6796 = 0.9695 and BRANCH 1998/2220 = 0.9000** against the unchanged gate of LINE 0.88 /
BRANCH 0.85; it contains `failAndReleaseStale`, which is how a reader can tell it is this tree's
report. That run was made on the tree this gate's commits produce, before this record was written
into it, and it is the report it regenerated that is left in `build/`. `flock -w 7200 … ./gradlew
build -Dtest.noFailFast=true` was then run against the tree **as committed** and answered
identically - BUILD SUCCESSFUL, exit 0, 10m 5s, the same 3652 over 579 - and it is that run's XMLs
that are left beside the report; `build` runs the coverage gate and not the report, so the ratios
above are the earlier run's measurement of the same code and are not requoted from a second one.

**Review gate 4 ran against the same phase again**, with the three read-only reviewers. It found no
BLOCKER and no HIGH in the code: what it found above LOW was that the design artefacts had been left
behind by gate 3's change of shape.

* **The design artefacts still described the retired shape** (MEDIUM at `code-reviewer`). Gate 3
  changed the operation — the predicate read once into a list that decides nothing, one fenced
  statement per batch in a transaction of its own, exhaustion reported rather than thrown — and
  re-pointed the port's javadoc and FR-003a at it, and nothing else. `data-model.md` still gave the
  return type as `List<ReleasedBatch>` and the shape as "one statement, in one transaction" over
  every stale batch, and its flow diagram, `plan.md`'s summary, inventory and decision list,
  `research.md`'s D2 and `spec.md`'s Assumptions all said the same. All five are re-pointed at
  `cd45160`, and the two dated Clarifications answers are kept as the record of the moment they were
  taken, each with a pointer to what gate 3 narrowed: a Q/A session is history, and history is not
  retro-edited.
* **The refusal no retry can settle crossed the port as a Spring type** (LOW at `code-reviewer`,
  `spec-validator` and the Codex wrapper's own observation — three reviewers, so it was closed
  rather than deferred). `attemptedRelease` rethrew `DuplicateKeyException` on any key but the
  active-register one. The class that has to read it is Phase 3's `StaleBatchReleaser` in `batch/`,
  which may name no `org.springframework.dao` type (constitution Principle V), so it could only have
  caught it as `RuntimeException` — the catch that swallows every programming error beside it, which
  is the same argument gate 2 settled the exhaustion question on. It is translated into
  `domain/RegisterNotReleasedException` with the collision as its cause, which is the idiom
  `recordAndComplete` uses for the same table one operation above, and the port's javadoc now says
  what it is and that it is allowed to end the run: FR-003a is about a batch's **ordinary** ending,
  the lost race for the day's key, and that one is reported and never raised. Red at `87bb6f5` on the
  assertion (`RegisterNotReleasedException` expected, `DuplicateKeyException` was), green at
  `5a26d3d`.
* **Two things the concurrency suite left to goodwill** (LOW at `qa`, the second of them the finding
  gate 2 left open for re-judgement), both closed at `4dc8768`. `assertInvariants` asserts the losing
  contender's escape with `allSatisfy`, which passes on an empty list, so a `markRequested` or a
  `markGenerated` that silently moved nothing against a FAILED batch would have left the staged
  rounds green; in `RELEASE_FIRST` the winner is known by construction, so `theRefusalExists` asserts
  exactly one escape — and only for that order, because which contender won a `TOGETHER` round is the
  decision `Order` deliberately declines to make. Mutation-checked at `hasSize(2)`: the two staged
  rounds go red and the re-share round, whose contender cannot be refused, does not. And
  `letTheKeyGo` ran as the round's last statement, so a read that threw before it would have left the
  out-of-order share holding the day's key for every other suite on the shared container; it runs in
  a `finally` now.
* **The other two settled endings were outside the predicate by construction alone** (LOW at `qa`),
  closed at `5410e9d`. `no_batch_a_run_has_finished_with_is_ever_matched_at_any_age` stood an aged
  FAILED batch and an aged NOTIFIED one beside the waiting day; `PARTIALLY_NOTIFIED` and
  `NOTIFIED_NOBODY` are endings a run has finished with too, and both now stand in the case at the
  same age, walked there by `walkedToSettled`, which takes the tally rather than assuming everybody
  was told. Mutation-checked: admitting either status to the predicate fails the case. The same
  commit corrects the `releaseFailed` class comment, which still said four of **six** reasons leave
  the stamp in place — the enum has been seven since `NOT_COMPLETED_BY_NEXT_RUN`, as the two
  statements it describes already say.
* **Gate 3's green was requoted wrong** (LOW at `spec-validator`): it said `RegisterStoreIT` 83 of 83
  where the XMLs read 85, `StaleRelease` being 13 since `2d2413c`. Corrected above, with the note
  that gate 2's own 83 is right for the moment it describes. The same commit records that
  `9811570`'s exhaustion branch went in untested and was retired untested.

**The whole-increment Codex gate did not run at gate 3, and has not run since.** Every call to
`mcp__codex__codex` on that round — the full review prompt and a one-word probe alike — was refused
with "You've hit your usage limit … try again at 1:00 PM", so that round has no Codex findings, and
the three items above attributed to "the Codex wrapper" are the wrapper's own observations rather
than Codex's. **No Phase 2 close and no Phase 3 start may be recorded on the strength of gate 3 or
gate 4**: the Codex leg of this range's gate is unmet and is owed a run. Nothing in the wrapper's own
checks blocks it — tree clean, every commit on `004-release-stale-batches`, no untracked files, every
touched file inside this tree's coordination scope.

Findings left open at this gate, each with its reason:

* ~~**A permanently contended batch has no operator remedy in this increment**~~ (LOW, the
  wrapper's observation) — **withdrawn at the second remediation below, along with the 005 hand-off
  it proposed.** The finding was that a batch whose hearing holds an earlier-stamped active
  unbatched share is reported contended by every run for ever, and that the remedy therefore had to
  come from the operations surface. The design owner settled it the other way on 2026-09-20: the
  release decides the share instead, so the condition no longer exists and there is nothing to hand
  off. Nothing is owed to 005 by this.
* **`STALE_BATCHES`' `batch_id` tiebreak is unasserted** (LOW at `qa`, marked optional there). The
  ordering case proves `register_date` with two days; proving the tiebreak needs two live batches on
  one date, which `idx_register_batch_live_key` allows only at two different court centres — and
  every `mine*` filter in `RegisterStoreIT` is written against the one court centre a case speaks
  for. That is fixture work of its own rather than an assertion, and the ordering it would pin is a
  presentation detail of a list the pass logs.
* **No case drives `failAndReleaseStale` over a broken `DataSource`** (LOW at `qa`). Outage
  translation for it is covered by `StoreOutageTest` plus the call site, as T009's narrative already
  records; the stronger proof belongs with Phase 3's `StaleBatchReleaserTest`, which owns the
  caller's half.
* ~~**Nothing pins that the call is made outside a transaction**~~ (LOW at `qa`, raised again as a
  MEDIUM at the Codex gate) — **closed at the Codex remediation below**, and closed the other way
  round from the reasoning recorded here. The finding was that the javadoc states the precondition
  and nothing enforces it, so a Phase 3 caller wrapping the pass in a `TransactionTemplate` would
  turn every retry into "current transaction is aborted" with no test going red. That was left for
  T011/T012 on the grounds that the guard belongs where the caller is. Codex's objection is the
  better one: a precondition nothing enforces is a comment, and the store is where the boundary can
  be taken rather than asked for. Each attempt now runs `REQUIRES_NEW`, and
  `a_release_is_committed_though_the_callers_transaction_rolls_back` pins it.
* **`COALESCE(requested_at, assembled_at)` leans on a timestamp/state shape the columns do not
  enforce** (LOW at Codex, gate 2, left open there for the same reason). Latent, reachable only
  through a row no ordinary flow writes, and the fix is a constraint rather than a predicate, so it
  belongs with the schema work.

**Green after gate 4's remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 11s, **3652 tests over 579 suites, 0 failures,
0 errors** — the same count as gate 3's close, because this round added assertions to existing cases
and no case of its own — with `checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain`,
`pmdTest` and `jacocoTestCoverageVerification` all green and none of them loosened. The coverage
report was regenerated in that same run and reads **LINE 6592/6799 = 0.9696 and BRANCH 1998/2220 =
0.9000** against the unchanged gate of LINE 0.88 / BRANCH 0.85; it contains `failAndReleaseStale`,
which is how a reader can tell it is this tree's report. That run was made on the tree this gate's
commits produce, before this record was written into it, and it is the report it regenerated that is
left in `build/`. `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` was then run against the
tree **as committed** and answered identically - BUILD SUCCESSFUL, exit 0, 10m 10s, the same 3652
over 579, 0 failures and 0 errors - and it is that run's XMLs that are left beside the report;
`build` runs the coverage gate and not the report, so the ratios above are the first run's
measurement of the same code.

**Gate 4's two MEDIUM findings were closed in a second remediation**, after the gate's own
artefact fixes above. Neither is a BLOCKER and neither changed what the operation is for; one is an
assertion the suite was leaving to timing, and one is a decision the design owner took about a batch
that could never be released.

* **The re-check the whole fence rests on was pinned only by luck** (MEDIUM at `qa`,
  `StaleReleaseConcurrencyIT`). The statement is safe because of a rule of READ COMMITTED - an
  `UPDATE` that waited on a row re-evaluates *its own* `WHERE` against the version it is granted -
  and that is why the staleness rule is written in the `UPDATE`'s predicate rather than in a clause
  that feeds it. What asserted it was the `TOGETHER` rounds, where the two contenders have to land
  inside the same handful of microseconds for the re-check to be reached at all: a property held at
  the scheduler's discretion is not a property.
  Closed at `f4fe47f` by two rounds with no timing in them, built on the `holdingTheBatchRow`
  fixture the re-share round already uses. A session takes the batch row `FOR UPDATE`; the pass
  reads `STALE_BATCHES` and blocks on its write; the **holder's own transaction** then moves the
  batch - `markRequested` on a PENDING one, `markGenerated` on a GENERATING one - and commits. The
  pass is granted a row version the rule no longer matches and changes nothing about it, which is
  asserted as three separate claims: the batch is not released, it is not reported **contended**
  either (a batch nothing was refused over lost no race), and the row stands exactly where the
  holder left it with both its registers still stamped to it.
  Red first, against the mutation the finding is about - the predicate lifted out of the `UPDATE`
  into a preceding `candidate` CTE, which is the read-then-mark shape written as one statement:
  `./gradlew test --tests '*StaleReleaseConcurrencyIT*' -Dtest.noFailFast=true`, 7 tests,
  **3 failures**, 0 errors. The render round failed **4** assertions ("expected:
  Ending[status=GENERATING, failureReason=null] but was: Ending[status=FAILED,
  failureReason=NOT_COMPLETED_BY_NEXT_RUN]", the batch named in `released`, "expected: 2L but was:
  0L" on its stamped registers, and the suite's own `prematurelyFailed` reading at 1) and the
  document round **2**; one `TOGETHER` round of an existing case went red beside them, which is the
  same defect found the old way. Mutation reverted. Green against the tree as committed: 7 of 7,
  `checkstyleTest` and `pmdTest` green - `() -> {}` rather than `() -> { }`, which `WhitespaceAround`
  refuses.
* **A batch whose key held a share it had overtaken could never be released** (MEDIUM at
  `code-reviewer`, `JdbcRegisterStore` ~953) - **a design decision, taken by the design owner on
  2026-09-20**, and the finding gate 4 had recorded as an accepted permanent condition with a 005
  hand-off. A share of the hearing delivered *behind* the register a batch already holds is recorded
  active and unbatched, because the recorder's incumbent search is over unbatched rows and a batched
  register is not its to supersede. It therefore holds `idx_output_active_register_key` for the day,
  the release's successor search is the mirror of the same ordering and never finds it, and the
  release is refused on every attempt - by every run, for ever, because no fresh snapshot removes a
  row committed before the statement began.
  The rule now reads the same total order **both ways**, which is what `recordAndComplete` already
  does one operation above: the register coming back is the later share, so the release supersedes
  the earlier active unbatched row against it (`superseded_by` = the register coming back) in the
  same statement, while a later-stamped active row supersedes the register coming back as before.
  Latest share wins in both directions, and the key keeps exactly one active row whichever of the
  two writers is the one that finds the pair together.
  Pinned by `RegisterStoreIT$StaleRelease.a_release_supersedes_the_share_it_overtook`; the
  later-stamped direction is `a_release_supersedes_against_a_later_re_share`, unchanged. Red at
  `5ca98f0` on **6** assertions, every one of them an assertion and not a refusal, because the
  contention is reported rather than thrown: the batch left `GENERATING` instead of FAILED under the
  new reason, its id in `contended`, the overtaken share's `superseded_by` empty, the key holding
  `["RECORDED", "RECORDED"]`, the wrong register left active and unbatched, and nothing in
  `released`. Green at `8d565ea`: `RegisterStoreIT` 86 of 86, `StaleReleaseConcurrencyIT` 7 of 7.
  **The supersession is chained ahead of the release, and that is a correctness point rather than a
  style one** - the same requirement `RECORD_REGISTER` has for the same index. The overtaken row
  holds the key until its update takes it out of the index, so a release issued first collides with
  the very row it is about to supersede; the release's source therefore counts the supersession's
  rows, because Postgres does not otherwise say which clause of one statement runs first.
  Mutation-checked: with the chain removed the new case fails on all six assertions again, three
  runs out of three.
* **The exhaustion round had to be re-staged, and the reason is worth recording.** Gate 3 staged
  `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released` **as data** - an
  out-of-order share holding the key against every attempt - precisely because chaining three timed
  windows could not be made reliable. That share is exactly what the decision above makes
  releasable, so the round would have stopped being about contention at all. And there is no
  arrangement of rows that replaces it: any active row for the key that is committed before the
  statement begins is in its snapshot, so the release either supersedes it or is superseded against
  it. The only refusal left is a share committing **after** the snapshot and before the write, on
  each of the three attempts.
  So it is staged by the database rather than by the scheduler: an `AFTER UPDATE` trigger, scoped by
  `WHEN` to the round's own hearing and dropped in a `finally`, takes the day's active register back
  the moment the release gives its own up - an existing superseded share of the same key, invisible
  to the statement's snapshot, made active again inside the attempt's own transaction. Every attempt
  meets `idx_output_active_register_key` and rolls back with it, which is what a re-share committing
  inside each window produces. **What is staged is the refusal; what is asserted is what the
  operation does with a batch it cannot release**, which is the contract the round exists for, and
  the round's own assertions are what prove the exhaustion path was reached - a trigger that did
  nothing would leave the batch released and `contended` empty. Scoped and dropped in the idiom
  `withOneUnbatchedRegisterAllowed` already uses in `RegisterStoreIT`, so no other suite sharing the
  container can see it, and nothing is left held afterwards: the share stays superseded, so the next
  run releases the batch like any other. The `letTheKeyGo` fixture gate 3 needed for that is
  therefore deleted.

The port's javadoc, `data-model.md`, `spec.md`'s edge-case list and `plan.md`'s Atomicity row are
re-pointed at the decision in the same commits, and the `COALESCE` note and the three remaining LOW
findings above are untouched by any of it.

**Green after the second remediation**: `flock -w 7200 … ./gradlew jacocoTestReport check
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 16s, **3655 tests over 579 suites, 0 failures,
0 errors** — three more than gate 4's close, being the two staged re-check rounds and the overtaken
share — with `checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all green and none of them loosened. The coverage report was
regenerated in that same run and reads **LINE 6592/6799 = 0.9696 and BRANCH 1998/2220 = 0.9000**
against the unchanged gate of LINE 0.88 / BRANCH 0.85; it contains `failAndReleaseStale`, which is
how a reader can tell it is this tree's report. The ratios are unchanged from gate 4's because the
decision is a change to one statement's SQL rather than to any Java branch, and the three new cases
cover code that was already covered. **Which run left which artefact**: gate 5 read the report and
the results this close left in `build/` and found them to be two runs of the same source - the
coverage report from the run quoted here, and the test XMLs from a later run of the same suite,
which answered identically at 3655 over 579 with 0 failures and 0 errors. Either describes the tree
as committed, so the verdict above stands under both, and the third remediation below says the same
about its own two runs.

**Gate 5's two MEDIUM findings were closed in a third remediation.** Neither is a BLOCKER and
neither changed what the operation does: one is a claim the suite was leaving to two random
identities, and one is a promise the port's javadoc was making more widely than the statement kept
it.

* **The per-batch isolation was asserted at UUID's discretion** (MEDIUM at `qa`,
  `StaleReleaseConcurrencyIT`). `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released`
  is the round that says the pass **goes on** to the batches after one it could not release - and it
  stood both of its batches on the same day. `STALE_BATCHES` answers `ORDER BY register_date,
  batch_id`, so which of the two was reached first was decided by two random identities, and a
  regression that stopped the walk at a contended batch - an early return, a `break`, or an
  exception on exhaustion - would have been caught only on the runs where the contended one
  happened to be walked first.
  Closed at `e4d1cf2` by standing the batch that must be released anyway on the **day after** the
  contended one: `staleBatch` and `batchFor` take the register date the round wants, the second
  court centre's registers are shared on `TUESDAY_SHARED`, and the walk therefore reaches it after
  the contended batch on every run. Nothing else about the round changed.
  Mutation-checked against the regression it is about - `eachStaleBatch` given a `break` as soon as
  a batch comes back contended: `./gradlew test --tests '*StaleReleaseConcurrencyIT*'
  -Dtest.noFailFast=true`, 7 tests, 1 failed, and that one failed on **3** assertions - the other
  court centre's batch still `Ending[status=GENERATING, failureReason=null]` where
  `FAILED/NOT_COMPLETED_BY_NEXT_RUN` was expected, its id absent from `released`, and `2L` registers
  still stamped to it where `0L` was expected. **Three runs out of three**, which is the point: the
  day is what makes the order a property rather than a coin. Mutation reverted; 7 of 7 green with
  `checkstyleTest` and `pmdTest`.
* **A refusal that is no key at all crossed the port as a Spring type** (MEDIUM at `code-reviewer`,
  `RegisterStore` ~479). The port promised that no `org.springframework.dao` type reaches it, and
  `attemptedRelease` translated only `DuplicateKeyException`: a `DataIntegrityViolationException`
  that is not one - a CHECK the bounded reason does not satisfy, which is what a pod running against
  a store `V6` never reached meets on **every** stale batch - was caught by nothing and crossed into
  `batch/`, where Phase 3's pass may name no such type and could only have read it as
  `RuntimeException`, the catch that swallows every programming error beside it.
  Closed in two halves. The **translation** at `0e99ef1`: a second `catch` arm after the
  `DuplicateKeyException` one hands the refusal to the same `unaccountedForRelease`, which now takes
  the wider class, and it is not attempted again - a rule is not a race. Red at `9ee0d74` with
  `RegisterStoreIT$StaleRelease.a_refusal_that_is_not_a_key_at_all_is_the_domains_own_class_too`,
  which narrows a CHECK onto this case's court centre (`NOT VALID`, dropped in a `finally`, the
  idiom `withOneUnbatchedRegisterAllowed` already uses) and asserts the class that escapes: 87 tests,
  1 failed, **2** assertions, both of them assertions - `RegisterNotReleasedException` expected and
  `DataIntegrityViolationException` was, and the same on the cause - while the other two claims
  passed red, because one statement rolls back whole and the mark went down with the release either
  way. Green at `0e99ef1`.
  And the **claim itself**, in the same commit: the port now says that no *refusal* crosses as a
  Spring type, and says what does - the store's own contention signal, a deadlock or a serialisation
  failure, which `StoreOutage.translating` hands on unchanged from **every** method on this port and
  which is the run's ordinary transient failure rather than anything this operation decides about.
  That is the adapter's store-wide policy (`recordAndComplete` raises a `ConcurrencyFailureException`
  of its own one operation above), and it is stated at the port so Phase 3 is not written against a
  promise the package does not make. The `@throws` clause that had wrapped into a five-word column
  is re-flowed in the same commit, which is gate 5's other cosmetic LOW.

**Four of gate 5's LOW findings are closed beside them**; three are left open with their reasons.

* `plan.md`'s inventory and test-matrix row said the fenced statement calls `attributionOf` with
  `null`, which T009 found it does not: it writes `completed_by = NULL` directly and
  `register_batch_completed_by_shape_chk` is what refuses an attribution. Both lines are re-pointed,
  and the matrix row now names `the_failure_names_no_completion_mechanism` as the pin, the overtaken
  share and gate 5's own two additions.
* The two test-only commits that recorded no mutation now have one each.
  **`e1ac365`** (the fixture settles the night, so the aggregate invariant is not vacuous): with
  `settleTheNight` made to return at once, `a_render_acceptance_racing_the_release_leaves_no_stranded_register`,
  `a_document_arrival_racing_the_release_leaves_no_stranded_register` and
  `a_re_share_racing_the_release_is_superseded_rather_than_unstamped` all go red on the aggregate
  assertion - "Expecting actual: [] to contain exactly (and in same order): [1L]" - 3 of 7 failed.
  **The re-staged exhaustion round's trigger**: with its `WHEN` clause re-pointed at a hearing
  nothing matches, so the trigger fires on nothing, the round goes red on **4** assertions -
  `contended` empty where the batch was expected, its id in `released`, its ending
  `FAILED/NOT_COMPLETED_BY_NEXT_RUN` where `GENERATING` was expected, and `0L` stamped registers
  where `2L` were. So the trigger is what stages the refusal, and the round's own assertions are
  what prove the exhaustion path is reached. Both mutations reverted.
* **Left open, and why.** (1) *The lost-then-won retry leaves no trace* - `JdbcRegisterStore` has no
  logger at all and is deliberately silent; giving one operation a log line is a change to the
  package's shape rather than to this statement, and it belongs with whatever gives the whole
  adapter a voice. (2) *Nothing pins that a `RegisterNotReleasedException` on one batch leaves the
  batches released before it committed* - `RegisterStoreIT`'s fixtures are single-court-centre by
  construction (`mine`, `mineReleased` and `mineContended` all filter on the case's own court
  centre), so the case wants a second court centre read by hand, and the per-batch **transaction**
  is the same property the concurrency suite's round now asserts deterministically on the ordinary
  path. (3) The three LOW findings gate 4 left open are untouched, as before.

**Green after the third remediation**: `flock -w 7200 … ./gradlew jacocoTestReport check
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 18s, **3656 tests over 579 suites, 0 failures,
0 errors** — one more than the second remediation's close, being the refusal that is no key — with
`checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all green and none of them loosened. The coverage report was
regenerated in that same run and reads **LINE 6594/6801 = 0.9696 and BRANCH 1998/2220 = 0.9000**
against the unchanged gate of LINE 0.88 / BRANCH 0.85; it contains `failAndReleaseStale`, which is
how a reader can tell it is this tree's report. That run was made on the tree these commits produce,
before this record was written into it, and it is the report it regenerated that is left in
`build/`. `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` was then run against the tree
**as committed** and answered identically - BUILD SUCCESSFUL, exit 0, 10m 10s, the same 3656 over
579, 0 failures and 0 errors - and it is that run's XMLs that are left beside the report; `build`
runs the coverage gate and not the report, so the ratios above are the first run's measurement of
the same code.

**The Codex leg is still owed and is still unmet.** Nothing in this remediation changes that: the
whole-increment Codex gate has not run since gate 2, and **no Phase 2 close and no Phase 3 start may
be recorded until it has**. *(Superseded by review gate 6 below, which is that run.)*

---

## Review gate 6 — the Codex leg, against Phase 2 (2026-09-20)

**The Codex leg owed since gate 2 has now run** against Phase 2 as committed at `68e3cae`. It
returned **two findings, no BLOCKER of its own beyond the first, and nothing else above LOW**. Both
are closed in the two test/fix pairs below, test-first, and both were closed in the adapter rather
than deferred to a caller.

* **The driver's account of a refused register travelled on the refusal** (BLOCKER at Codex,
  Principle VII). `RegisterNotReleasedException` carried the store's own
  `DataIntegrityViolationException` as its cause. `FAIL_AND_RELEASE_STALE` updates
  `processed_output`, and a `processed_output` row holds the register document itself, so Postgres
  reporting the refusal by quoting the row — `Failing row contains (...)` for a CHECK,
  `Key (...)=(...) already exists` for a unique index — puts a youth defendant's name and date of
  birth on the driver's message. The failure is raised out of the nightly run and written at ERROR
  into the estate's log index, and a stack trace is written whole. `persistence/StoreOutage` already
  discards an integrity refusal's cause for exactly this reason, so the release was the one place
  the rule was not kept.
  **Closed** by carrying a bounded classification written in this repository — `a unique key`, or
  `an integrity rule that is no key` — plus the batch's identity, and no cause at all. The
  constraint's own name goes with the cause: the only safe idiom this class has for naming one,
  `violates(DuplicateKeyException, String)`, asks the driver whether the refusal is a key *this
  class already knows by name*, and a refusal that reaches this translation is by construction none
  of them, so extracting a name would mean reading the message that may not be kept.
  **Red** (`42b7825`, the two `RegisterStoreIT` cases that had *required* the unsafe cause
  re-pointed at its absence): `flock -w 7200 … ./gradlew test --tests '…RegisterStoreIT'
  -Dtest.noFailFast=true` → **87 tests completed, 2 failed**, 4 assertion failures each and no
  compile error — the classification absent from the message, `hasNoCause` unmet, an
  `org.springframework.dao` type in the chain, and the driver's words in what the chain says.
  **Green** (`e6deb85`): the same command, BUILD SUCCESSFUL, 87 of 87, with `checkstyleMain` and
  `pmdMain` green.
  The two cases now walk the whole cause chain rather than the exception alone — a later change
  re-attaching the refusal underneath would otherwise leave them green — and assert against both
  detail lines Postgres uses, the court centre, and the fixture's `SMITH, John` / `2008-04-11`.
  `TelemetryPrivacyTest` was **not** extended: it is a Spring-context test over the intake pipeline's
  log lines and has no hook for an exception a store raised, so the assertion is made where the
  refusal is produced.

* **The per-batch isolation held only for a caller outside a transaction** (MEDIUM at Codex; the
  same thing `qa` raised as a LOW at gate 5 and this file left open for T011/T012). `release()` ran
  straight at the `JdbcClient` with no boundary of its own, and the javadoc asked not to be wrapped.
  A caller inside a transaction would have every attempt join it: the first refusal aborts that
  transaction, the attempts after it are made inside an aborted one, and every court centre already
  released is rolled back at the end — FR-003a's run-ending outcome reached by obeying the port
  rather than by breaking it.
  **Closed** by taking the boundary instead of asking for it. Each attempt runs through a second
  `TransactionTemplate` at `PROPAGATION_REQUIRES_NEW`, so the caller's transaction is suspended for
  the length of an attempt and resumed after it. `JdbcRegisterStore` now takes the
  `PlatformTransactionManager` rather than a template, because it needs two boundaries over the one
  data source and only one of them is the ordinary kind; `ProcessedLogConfig` and the four test
  construction sites hand it the manager, and `ProcessedLogTestSupport.transactionManager()` and
  `ReportReadsDatabase.transactionManager()` are the fixtures' half of that.
  **Red** (`aa44357`, `StaleReleaseConcurrencyIT.a_release_is_committed_though_the_callers_transaction_rolls_back`):
  `flock -w 7200 … ./gradlew test --tests '…StaleReleaseConcurrencyIT' -Dtest.noFailFast=true` →
  **8 tests completed, 1 failed**, 5 assertion failures and no compile error — what escaped was
  `org.springframework.jdbc.UncategorizedSQLException … SQL state [25P02]`, which is
  "current transaction is aborted" exactly as the finding predicted, and with it the released
  batch's ending, its stamps and both halves of the account.
  **Green** (`51307fd`): `flock -w 7200 … ./gradlew test --tests
  'uk.gov.hmcts.cp.courtregister.persistence.*' -Dtest.noFailFast=true` BUILD SUCCESSFUL, **427
  tests, 0 failures, 0 errors**, with `checkstyleMain` and `pmdMain` green.
  The round stands the released batch on the **earlier** day — the reverse of the exhaustion
  round's staging — so it is walked *before* the batch no attempt can release, and reads every row
  back **after** the surrounding transaction has ended, which is what makes "committed" the claim
  rather than "written". The port javadoc now says the separation is the implementation's to keep
  rather than the caller's to remember.

**What the exhaustion round's trigger actually is, recorded because Codex accepted it on this
description.** `withTheKeyTakenBackInsideEveryAttempt` is **deterministic failure injection at the
exception/retry boundary**, not a literal three-commit race. It stages the refusal the retry is
bounded for — the day's active-register key taken back inside every attempt — with an
`AFTER UPDATE` trigger scoped by `WHEN` to the round's hearing, which makes an existing superseded
share of the same key active again the moment the release clears the stamp it is giving back,
inside the attempt's own transaction. Chaining three genuine commit windows by timing was built and
abandoned at review gate 3: the gap between a refused attempt's rollback and the next holder taking
the row cannot be closed from the test. What is *staged* is the refusal; what is *asserted* is what
the operation does with a batch it cannot release, and that is the contract the round is for.

**Green after the Codex remediation**: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`
BUILD SUCCESSFUL, exit 0, **3657 tests over 579 suites, 0 failures, 0 errors** — one more than gate
5's close, being the round that calls the pass from inside a caller's transaction — with
`checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all
green and none of them loosened. The coverage report regenerated by the preceding
`jacocoTestReport check` run reads **LINE 6599/6807 = 0.9694 and BRANCH 1999/2222 = 0.8996** against
the unchanged gate of LINE 0.88 / BRANCH 0.85.

That `jacocoTestReport check` run is also what caught the one thing this remediation got wrong on
the way: `ReportReadsDatabase`'s new accessor and the field behind it were given the same name, and
`pmdTest`'s `AvoidFieldNameMatchingMethodName` refused it. The field is
`platformTransactionManager` and the accessor is `transactionManager()`; the suite, the coverage
gate and both Checkstyle tasks were already green in that run, so nothing but the name changed.

**Phase 2 may close on this gate.** The Codex leg is met, both of its findings are closed in the
tree, and the three reviewer legs passed at gate 5 with their remaining findings recorded above.

## Review gate 7 — the workflow leg, against Phase 2 as committed at `c07af34` (2026-09-20)

One HIGH, and it is about the tree rather than the code: *"the tree was left dirty after the last
commit"*, to be closed by committing or removing whatever a shell redirection had left behind.

**It does not reproduce, and nothing was lost closing it.** At `c07af34` — the gate-6 remediation's
last commit — `git status --porcelain` is empty, `git status --porcelain --ignored=matching -uall`
names nothing outside `build/` and `.gradle/`, which `.gitignore` has always carried, and
`git stash list` is empty. The 35 commits of `90001de..c07af34` add no file at the repository root
and touch nothing outside this tree's half of the coordination contract, so no redirection artefact
was committed either. Whatever the gate read was gone before this round opened; there was no file
to commit and none to remove, and the round therefore changed no code.

**Re-verified green at `c07af34`**: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`
BUILD SUCCESSFUL, exit 0, **3657 tests, 0 failures, 0 errors** — the same count gate 6 closed on —
with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification`
all green, and `git status --porcelain` still empty after the build.

## The two MEDIUM findings the Phase 2 gate left, closed before Phase 3 opened (2026-09-20)

Neither is a BLOCKER and neither changes what the release is for. One is a refusal the translator
does not know about, and one is a supersession the fenced statement learned at gate 4 and the two
older statements beside it did not.

* **A store lost between the read and a per-batch transaction escaped as a Spring type.**
  `StoreOutage.translating` names `org.springframework.dao` classes only, and since the Codex
  remediation each stale batch's release takes a `REQUIRES_NEW` boundary of its own *inside* that
  translation. A store that goes away in the gap between the `STALE_BATCHES` read and an attempt's
  `getTransaction` therefore refuses with
  `org.springframework.transaction.CannotCreateTransactionException`, which is outside every branch
  the translator lists: it crossed the port as itself, contrary to `failAndReleaseStale`'s own
  `@throws StoreUnavailableException`, and reached a pass in `batch/` that may name no Spring type
  at all (constitution Principle V) and could only have caught it as `RuntimeException`.
  **Closed** by a fourth branch: a `TransactionException` is the store going away exactly as a
  failure to acquire a connection is, so it becomes `StoreUnavailableException` carrying the
  statement's own name and the cause, and none of the driver's words.
  *(The second finding's record is below this one.)*
  **Red** (`StoreOutageTest.a_transaction_that_cannot_be_begun_becomes_the_domains_own_signal`, a
  `PlatformTransactionManager` whose `getTransaction` throws): `flock -w 7200 … ./gradlew test
  --tests '*StoreOutageTest*' -Dtest.noFailFast=true` → **8 tests completed, 1 failed**, one
  assertion and no compile error — "Expecting actual throwable to be an instance of:
  uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException but was:
  org.springframework.transaction.CannotCreateTransactionException".
  **Green**: the same command with `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest`
  beside it, BUILD SUCCESSFUL, **8 of 8**, all four analysis tasks green. The branch is the outage
  arm rather than one of its own, because a store that will not begin a transaction has said the
  same thing as a store that will not give a connection; `translatingWrite`'s refusal arm is
  untouched, and no other suite in this repository names a `org.springframework.transaction` type.

* **The overtaken share was decided by the fenced statement alone, and by neither statement beside
  it.** Gate 4 taught `FAIL_AND_RELEASE_STALE` to read the key's total order both ways, so a share
  the batched register *overtook* - a delivery that arrived behind the register it belongs in front
  of, which the recorder writes active because a batched register is not its to supersede - is
  superseded against the register coming back. `MARK_FAILED`'s released branch (statement 9, the one
  `:releaseRows` selects) and `RELEASE_FAILED` (statement 9a) still read it one way only: they clear
  the stamp beside the earlier active row, `idx_output_active_register_key` refuses the second active
  row for the day, and the whole statement goes down with it - taking the failure mark with it in
  statement 9's case, and an operator's `release-batch` in 9a's. One rule about one index, kept in
  one statement out of three.
  **Closed** by folding the same `overtaken` clause, chained ahead of the release exactly as it is in
  the fenced statement and for the same index, into both. In statement 9 it is guarded by
  `:releaseRows` through `stamped`, which already carries that guard, so a reason that keeps the
  stamp supersedes nothing.
  **Red** (`RegisterStoreIT`'s twins `Failure.a_failure_that_never_left_should_supersede_the_share_it_overtook`
  and `Releasing.a_release_should_supersede_the_share_it_overtook`): `flock -w 7200 … ./gradlew test
  --tests '*RegisterStoreIT*' -Dtest.noFailFast=true` → **89 tests completed, 2 failed**, **6
  assertion failures each** and no compile error. The first of the six in each is the refusal itself,
  caught as an assertion by the suite's soft-assertion convention - "Expecting code not to raise a
  throwable but caught org.springframework.dao.DuplicateKeyException … `idx_output_active_register_key`"
  - and the five after it are the properties the refusal takes with it: the batch left GENERATING or
  its release answering with nothing, the overtaken share's `superseded_by` empty, the key holding two
  RECORDED rows, the stamp still in place, and the wrong register left assemblable.
  **Green**: `flock -w 7200 … ./gradlew test --tests '*RegisterStoreIT*' --tests
  '*StaleReleaseConcurrencyIT*' checkstyleMain pmdMain checkstyleTest pmdTest
  -Dtest.noFailFast=true` BUILD SUCCESSFUL, **97 tests, 0 failures, 0 errors** — `RegisterStoreIT`
  89 of 89 and `StaleReleaseConcurrencyIT` 8 of 8 — with all four analysis tasks green.
  `stamped` gains the five columns the clause matches on in both statements, which is the whole of
  the change beside the clause itself; nothing else about either statement moved, and the eleven
  existing supersession cases across `Failure` and `Releasing` pass unchanged, which is what says
  the later-stamped direction is untouched.

---

## Phase 3: User Stories 1 and 2 — the pass and its cutoffs (Priority: P1) 🎯 MVP

**Goal**: the class that computes the two cutoffs, asks the store once, and reports what came back.

**Independent test**: `StaleBatchReleaserTest` — a plain object over the store, the metrics, two
durations and a clock. No Spring context, no Docker.

### Tests first ⚠️

- [x] T011 [US1] `batch/StaleBatchReleaserTest` (new) — the call and the account.
      `a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released` (**P2's
      re-pointed pinning test**, driven through the store mock);
      `the_two_numbers_are_batches_and_registers`;
      `one_line_per_released_batch_names_it_by_id_and_nothing_else`;
      `a_pass_that_released_nothing_says_so`;
      `a_contended_batch_is_counted_and_the_pass_goes_on` (**review gate 3**: the store reports a
      batch it could not release on `StaleReleaseOutcome.contended()` rather than throwing, so the
      account the pass keeps has a third number and the pass returns normally with it);
      `the_pass_adopts_the_runs_correlation`. Seam: the class with `releaseStale()` throwing
      `UnsupportedOperationException`. Red: a failing assertion on the first case.
      (red: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*'
      -Dtest.noFailFast=true`, **7 tests, 7 failed**, 0 errors — every failure an assertion, the
      seam's refusal recorded as each case's *first* soft failure in the convention `RegisterStoreIT`
      uses rather than as a stack trace out of the arrangement. The first case's second failure is
      the property under test: "expected: ReleaseTally[batches=1, registers=2, contended=0] but was:
      ReleaseTally[batches=-1, registers=-1, contended=-1]", the sentinel being what a pass that
      answered nothing reads as.
      **Seven cases, not six**: `a_store_that_cannot_be_reached_leaves_the_pass` is the seventh, and
      it is the per-method outage proof review gate 4 deferred from `RegisterStoreIT` to this suite.
      A store that went away is the *run's* failure and not one batch's, so it leaves the pass as the
      port's own `StoreUnavailableException` - asserted `isSameAs`, so a pass that wrapped or
      swallowed it fails - and nothing is counted for a pass that learned nothing.
      **Three seams, and one of them is an instrument.** `batch/StaleBatchReleaser` with
      `releaseStale()` refusing and the nested `ReleaseTally(batches, registers, contended)` record;
      and `GenerationMetrics`' three counters, which a test cannot name before they exist. The seam
      class deliberately holds **no fields**: five fields nothing reads is five `pmdMain` violations,
      so the constructor takes its five collaborators and T012 lands the fields with the code that
      reads them. `GenerationMetricsTest`'s two surface cases gain the three names in the same
      commit, so "exercising everything registers exactly the documented instruments" stays a claim
      about all of them.
      **The third counter is a deviation from the letter of T012's "two counters" and is recorded
      as one.** FR-003a says a contended batch is "counted by the pass's line and its counter", and
      the design rules say a path that leaves something undone moves one; `data-model.md`'s
      instrument table named only the two released totals. So
      `courtregister_generation_contended_total` is added beside them, unlabelled - a batch id may
      never be a series (cardinality, and privacy on a register whose every defendant is a child) -
      and `data-model.md` gains its row at T012.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green.)
- [x] T013 [US2] `batch/StaleBatchReleaserTest` (extend) — the two cutoffs, which T012's minimal
      implementation is deliberately allowed not to compute.
      `the_scheduled_cutoff_is_the_clock_minus_the_minimum_age`;
      `the_manual_cutoff_is_the_longer_of_the_minimum_age_and_the_run_lock` (FR-017), with a case
      each way round so neither ordering of the two settings is assumed;
      `a_batch_at_exactly_the_minimum_age_is_stale` (US2.3), asserted on the cutoff argument rather
      than on an outcome, because the boundary lives in the predicate. Red: T012 passes `now` for
      both.
      (red: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*'
      -Dtest.noFailFast=true`, **11 tests, 4 failed**, 0 errors — the four new cases and nothing
      else, every failure an assertion and none of them the seam's: T012 is green, and what these
      four are red against is the minimal implementation's `now` for both cutoffs. "expected:
      2026-09-21T16:30:00Z but was: 2026-09-21T17:00:00Z" for the scheduled cutoff,
      "2026-09-21T15:50:00Z" for the manual one against a seventy-minute lock, the same
      16:30 for the manual one against a **ten-minute** lock - which is the case each way round,
      so an implementation that simply always took the lock fails - and "expected: 30M but was: 0S"
      for the boundary, asserted as the distance from the clock rather than on an outcome, because
      the `<=` itself is the store's and is pinned there.
      `checkstyleTest` and `pmdTest` green; `SHORT_LOCK` is the one fixture the cases add.)

### Implementation

- [x] T012 [US1] `batch/StaleBatchReleaser.java` — make T011 green **and no more**: one call to
      `failAndReleaseStale`, the lines, the two counters, the two numbers back, all under
      `RunCorrelation.under(...)`, which adopts the run's ambient id. The contended batches the
      answer names are counted and said at WARN — a path that drops something moves a counter — and
      the pass returns; the run goes on to assemble what the rest of the pass gave back (FR-003a).
      The split from T013 is deliberate: computing the cutoffs here would leave T013 with nothing to
      fail against.
      (green: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*' --tests
      '*GenerationMetricsTest*' checkstyleMain checkstyleTest pmdMain pmdTest
      -Dtest.noFailFast=true` BUILD SUCCESSFUL, **52 tests, 0 failures, 0 errors** —
      `StaleBatchReleaserTest` 7 of 7 and `GenerationMetricsTest` 45 of 45 — with all four analysis
      tasks green.
      **The split is kept in the fields as well as in the arithmetic**: `staleAfter` and `runLock`
      are constructor parameters here and become fields at T014, because two fields nothing reads
      are two `pmdMain` violations and a deliberately minimal implementation should not have to
      suppress a rule to stay minimal. Both cutoffs are `clock.instant()`, which is exactly what
      T013 is red against.
      **Three counters, and the third is the deviation T011's record states**: the released batches,
      the released registers and `courtregister_generation_contended_total`. Every run moves all
      three, by nought where it released nothing, so the series exist to be alerted on from the
      first quiet night rather than appearing the first time something goes wrong.
      **The pass's own lines**: one INFO per released batch naming the batch, the court centre and
      the register date with the count; one WARN per contended batch naming it by identity alone;
      and one INFO summary carrying `released_batches=`, `released_registers=` and `contended=`,
      which is the line a quiet night still writes. All of them carry the run's `runId`, because
      `releaseStale()` runs under `RunCorrelation.under(...)`.
      `data-model.md`'s instrument table gains the contended row in the same commit.
- [x] T014 [US2] `batch/StaleBatchReleaser.java` — make T013 green. Both cutoffs computed once per
      pass from the injected clock and the two settings. The javadoc states the rule the retired
      class stated differently: PENDING and GENERATING are **one** rule, because with no query there
      is nothing to tell them apart; GENERATED is on no arm; and an operator's batch is given the
      longer grace because a manual generation holds no lock and has the whole deadline to work in.
      (green: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*' checkstyleMain
      checkstyleTest pmdMain pmdTest -Dtest.noFailFast=true` BUILD SUCCESSFUL, **11 tests, 0
      failures, 0 errors**, all four analysis tasks green.
      Both cutoffs come off one read of the clock, so every batch in one pass is judged against the
      same moment; the manual one is `max(staleAfter, runLock)` written as a comparison rather than
      as `Duration.max`, which this JDK does not offer. `staleAfter` and `runLock` become fields in
      this commit, which is where the code that reads them lands.
      The javadoc states the rule the retired class stated differently: PENDING and GENERATING are
      **one** rule, because with no query there is nothing that could tell them apart; GENERATED is
      on neither arm at any age; and which of the two cutoffs a batch is judged by is the store's to
      decide from `system_generated`, in the statement's own predicate.)

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

## Phase 5: User Story 4 — the removal, then the vocabulary retirement

**Goal**: the reconciler, its timer, its lock, the query path and the deployment mode that depended
on it are gone from the source, the wiring, the stubs and the local stack — and, once the last writer
of the two retired values has gone with them, the vocabulary they named is retired too (T047-T050,
the other half of the migration split).

**This is the longest phase and it has an internal boundary.** T021-T027 remove the mechanisms;
T047-T050 remove the words for them; T028 closes the phase by characterising the finished suite. The
boundary is not a preference: until T022 and T025 land, `GenerationReconciler` writes both retired
values on every pass.

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
### The vocabulary retirement — after the deletions above, and only after them

These four are the other half of the migration split (see "Decided" above Phase 1). They land
**after T022 and T025**, because until those two commits `GenerationReconciler` is still the writer
of both retired values and removing them would either fail to compile or force a false `completed_by`
claim. T028 runs after them, not before, so the suite it characterises is the finished one.

- [ ] T047 [US4] `domain/BatchFailureReasonTest` and `domain/BatchStateTest` (both extend) — the
      **deletion reds**. `the_seven_reasons_are_the_bounded_set` becomes
      `the_six_reasons_are_the_bounded_set`; `generation_timed_out_is_no_longer_a_reason`;
      `completed_by_has_one_constant`; `only_generation_failed_is_generator_attributed`; and the
      both-directions schema-vocabulary case of T003 re-run against the narrowed lists, which is
      what makes the retirement provably complete rather than merely started. Red: the enums still
      hold both constants. (Nothing here is new behaviour — these assertions were T003's in the
      pre-split list and have simply moved to where they can be true.)
- [ ] T048 [US4] `domain/BatchFailureReason.java`, `domain/CompletedBy.java` — make T047 green.
      Remove `GENERATION_TIMED_OUT`; narrow `isGeneratorAttributed()` to `GENERATION_FAILED`; remove
      `CompletedBy.RECONCILER`. `CompletedBy` stays a type with one constant, and its javadoc says
      why: it is an argument carried through the outcome sink into the store's marks, and a second
      mechanism is exactly the kind of thing that comes back.
- [ ] T049 [US4] `persistence/SchemaMigrationV2IT` (extend) and
      `src/main/resources/db/migration/V7__retire_reconciler_vocabulary.sql` — the narrowing, as a
      pair in one commit because the IT's red *is* the migration's absence. The test:
      `v7_refuses_the_retired_timeout_reason` and `v7_refuses_the_retired_attribution`; and
      `v7_refuses_to_apply_to_a_store_holding_a_retired_row`, which seeds a violating row on a fresh
      container and asserts the migration fails rather than silently dropping it — the one behaviour
      an operator has to know about, and therefore the one worth a test rather than a sentence. The
      migration: the three constraint rewrites `data-model.md` gives under V7, in that order.
- [ ] T050 [A] [US4] `docker/`, `specs/004-release-stale-batches/quickstart.md` — **[A]**, and this
      is T007 arriving where it belongs. Record that `docker compose down -v` is required before
      **V7** on any volume holding a pre-004 row, and confirm on a real local volume that the
      migration refuses without it and applies with it. No pair: it is an observation about Postgres,
      not a behaviour this repository implements.

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
Phase 5  (T021-T028, T047-T050)  the removal, then the vocabulary retirement   [US4]
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

**Hard orderings inside phases**: T001→T002, T003→T004, T005→T006 (pairs); T008→T010→T009;
T011→T012→T013→T014 (T013's red depends on T012 being minimal); T015→T016, T017→T018, T019→T020;
T021→T022, T024→T025, T026→T027; then T047→T048 and T049 and T050, all of them after T022 and T025;
T028 after every one of those, so the suite it characterises is the finished one;
T029→T030→T031→T032;
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
  T028 characterises a deletion whose behaviour three other tasks assert; T050 records what V7 does
  to a store that still holds a pre-004 row; T037, T038 and T046 verify
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
