# Tasks: Consolidate the progression court-register leg

**Input**: Design documents from `/specs/002-consolidate-progression-leg/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix. A P-fix task's test is written to **fail against the
progression behaviour and pass against the fix**, and its DEFECT-FIXES row names it.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs — interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` — so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**Phase 1 tasks are infrastructure, not TDD pairs** (constitution mechanical exemption): their
commits record verification evidence instead of a red assertion.

**[A] Acceptance/characterisation tasks** verify assembled behaviour (end-to-end suites, the
container, the differential audit). No implementation task follows them and no red run is required;
the task records the initial observed result.

### Approved TDD exceptions (Phase 3)

Two, and they are recorded here rather than argued for in a commit body:

- **`22fe4b4`** "test(pipeline): pin the two golden shapes progression throws on" is an **[A]
  characterisation of `c1786da`'s answer**, not the red half of a pair. T022 (`c1786da`) landed
  `DefendantTypeResolver` under T019's red run and, in answering the shapes progression's
  `getDefendantType` refuses, produced a behaviour no case then held down; `22fe4b4` states both
  halves of that difference - what the goldens recorded progression doing, and what the port does
  instead - so it passes on introduction by construction, exactly as an end-to-end characterisation
  does. Its commit body records the passing run rather than a red one.
  **Approved: design owner, 2026-09-06.**
  From that approval on, the behaviour is owned by **row P10 of `doc/DEFECT-FIXES.md`**, which names
  `DefendantTypeResolverTest.the_shapes_progression_throws_on_are_answered_applicant` as its pinning
  test and carries the sign-off-before-cutover marker; the javadoc notes in `DefendantTypeResolver`
  and its suite that said the row was owed describe how it was found, not where it lives.
- **`9cf66f3`** "fix(pipeline): put back the resolver's answer pending a register row" changed the
  P10 expectations and the resolver in one commit, with no red test before it. It is a
  **review-remediation commit that re-pinned an existing characterisation together with its
  implementation while the P10 row was still being decided**: it reverted `1113931` and `664152e`
  (which had made the three shapes refuse) back to the answer `c1786da` shipped, because the
  refusal was itself an uncatalogued deviation the differential audit refuses without a register
  row, and the row could not be written from that change. The pair it reverted to was already
  characterised rather than driven, so there was no red run left to record for it. The behaviour is
  now owned by **row P10 of `doc/DEFECT-FIXES.md`**, whose third shape is pinned by
  `DefendantTypeResolverTest.a_respondent_without_a_master_defendant_is_answered_applicant`
  (`2ca9263`): a test-only **[A]** characterisation of behaviour the resolver already had, green on
  introduction, with no implementation commit following it. History is left as it stands:
  splitting unpushed commits for a review fix was judged higher risk than recording the exception,
  which is the same judgement the Phase 2 block records.
  **Approved: design owner, 2026-09-06.**

No other exception of this kind is pre-approved.

**Conventions**: package root `uk.gov.hmcts.cp.courtregister`; production code under
`src/main/java/uk/gov/hmcts/cp/courtregister/`, tests under
`src/test/java/uk/gov/hmcts/cp/courtregister/`; progression sources referenced as `PROG` =
`cpp-context-progression` at `main` `79edf7cf3d`. `*IT` suites need Docker and run inside
`./gradlew test`. Conventional Commits on `002-consolidate-progression-leg`; no AI attribution.
The accepted types are `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `build`, `ci` and `style`
(`config` is not one of them; a configuration change is a `chore` or a `build`).
**Every task that lands a P-fix flips that row of `doc/DEFECT-FIXES.md` (status → FIXED, pinning
test confirmed) in the same commit.** Every phase ends with a `./gradlew build` that is green and a
Codex review (new session) whose findings are fixed before the next phase starts. From Phase 3 on,
review-gate fixes also land as a red test commit followed by an implementation commit; no further
exceptions of this kind are pre-approved. **Never two committing agents at once.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency)
- **[A]**: acceptance/characterisation — see above
- **[US#]**: the spec user story the task traces to

---

## Phase 1: Setup (dependencies, configuration surface, local loop, goldens)

- [x] T001 Add dependencies to `build.gradle`: `spring-boot-starter-artemis` (with the `javax.jms`
      / `artemis-jms-client` exclusions as `service-cp-crime-results-enforcementgateway/build.gradle:57-60`),
      `com.azure:azure-data-appconfiguration` (BOM-managed), `net.javacrumbs.shedlock:shedlock-spring`
      + `shedlock-provider-jdbc-template`, test-only `org.apache.activemq:artemis-jakarta-server`;
      `./gradlew dependencies --configuration runtimeClasspath` shows no duplicate JMS API.
- [x] T002 [P] Add the 002 configuration keys to `src/main/resources/application.yaml` and
      `application-test.yaml` exactly as the plan's configuration table (`courtregister.output`,
      `courtregister.generation.*`, `courtregister.feature.*`, `courtregister.fileservice.*`,
      `courtregister.endpoints.systemdocgenerator|notificationnotify`,
      `courtregister.email.templates.cr_standard`, `spring.artemis.*`, `spring.jms.*`,
      `courtregister.publicevents.*`), with the same "LOCAL DEFAULT ONLY" comments the 001 keys carry;
      `./gradlew bootRun` still refuses for the documented reasons (record the message).
- [x] T003 [P] Extend `docker-compose.yml` with `artemis` (`artemis-jakarta-server` image or the
      estate `hmcts/artemis_ubuntu`, `public.event` multicast address), `fileservice-postgres`
      (postgres:16 seeded from `specs/002-consolidate-progression-leg/contracts/fileservice/` via an
      init script) and `wiremock` (mappings under `docker/wiremock/` for SDG command 202 + query,
      NN 202, App Configuration `kv` flag ON, `__admin/flag/off|on`), plus an `sdg-echo` helper that
      publishes `document-available` onto `public.event` after each `generate-document`; `docker compose
      up -d` brings all up healthy (record the `ps` output). (delivered with two deviations, both
      recorded in quickstart.md and `docker/wiremock/README.md`: the broker is the public
      `apache/activemq-artemis` image, since `hmcts/artemis_ubuntu` sits in a private ACR a fresh
      clone cannot pull; and the flag switch is `PUT /flag/off|on`, since WireMock reserves
      `/__admin` for its own API and never serves stub mappings there)
- [x] T004 [P] Record the progression goldens (research §6): in a local, uncommitted module of `PROG`,
      run `CourtRegisterPdfPayloadGenerator.mapPayload` over the 001 recorded documents grouped per
      (court centre, register date) and `CourtRegisterHandler.getDefendantType` over the base hearings;
      write `src/test/resources/goldens/progression/pdf-payload/*.json`,
      `…/defendant-type/*.json` and `…/PROVENANCE.md` (PROG commit, core-domain version, corpus
      digest, batch composition). Record the count of goldens per kind. (recorded: 161 document and
      7 batch pdf-payload goldens, 9 defendant-type goldens of which 6 are synthesised, and 52
      refusals recorded in `INDEX.json`; delivered with two deviations, both recorded in
      `src/test/resources/goldens/progression/PROVENANCE.md`: `getDefendantType` is carried as a
      verified verbatim transcription in `…/goldens/progression/harness/CourtRegisterHandlerRule.java`,
      because `CourtRegisterHandler` cannot be compiled in isolation; and the Applicant/Appellant/
      Respondent branches are reached through six synthesised inputs, because no base fixture reaches
      those branches)
- [x] T005 [P] [A] Verify the vendored file-service DDL against the deployed schema: in an STE stack,
      `\d metadata` and `\d content` on the `fileservice` database match
      `contracts/fileservice/` changesets 001–006 (columns, types, defaults). Record the result in
      `contracts/README.md` (date, stack). If it differs, re-vendor and note the delta **before the
      first deploy of the generation half to any stack**.
      **Done 2026-09-07, against SIT rather than STE** (read-only): verified against SIT
      `fileservice` on server `psf-sit-ccm01-fileservice`. `metadata(file_id uuid PK, metadata jsonb
      NOT NULL, FK file_id -> content)` and `content(file_id uuid PK, content bytea, deleted boolean
      NOT NULL DEFAULT false, deleted_at timestamptz)` match changesets 001, 002, 004, 005, 006 (003
      is H2-only and not applied). **Nothing re-vendored**: the vendored DDL is a faithful copy of
      the deployed schema for both tables, so the `FileServicePayloadStoreIT` seed pins what the two
      inserts really run against. The deployed `databasechangelog` additionally lists
      `008-add-index-on-deleted-at-column-in-content-table` (2025-08-04), an index
      `content_deleted_at_index` on `deleted_at` that the local `framework-libraries` clone
      (`58aad8664`, 2023-12-22) predates and that does not affect the two inserts; it is recorded by
      name in `contracts/fileservice/008-DEPLOYED-NOTE.md`, because no local clone holds its XML.
- [x] T006 [P] Append rows **P1–P9** to `doc/DEFECT-FIXES.md` as **PLANNED** (P6, P7 as RETIRED with the
      retirement-PR pointer; P8 as MOOT with the index citation), each with the progression `file:line`
      citation from the design §3.4, the fixed behaviour from §7.3 and the pinning test name from the
      plan's test matrix; P3 and P4 carry the sign-off-before-cutover marker. Update the register header
      counts.

**Checkpoint**: build green, compose up, goldens present, register carries the P rows. Codex review 1.

**Checkpoint note - T005 closed (2026-09-07)**: T005 is **done** and its box is ticked. The check
was run read-only against **SIT** rather than an STE stack, which is the one departure from the task
as written: SIT's `fileservice` was reachable and answers the same question, and the task's point was
a live schema rather than a particular stack number. `metadata` and `content` match the vendored
changesets 001, 002, 004, 005 and 006 (003 is H2-only and not applied), so **nothing was
re-vendored** and the `FileServicePayloadStoreIT` seed is a faithful copy of the deployed schema.
The deployed `databasechangelog` carries one changeset the vendored set does not,
`008-add-index-on-deleted-at-column-in-content-table` (2025-08-04) - an index on `content.deleted_at`
that no local `framework-libraries` clone holds the XML for and that this service's two inserts
neither read nor plan against; it is recorded by name in
`contracts/fileservice/008-DEPLOYED-NOTE.md`. The deadline below - before the first deploy of the
generation half to any stack - is therefore met, and the record of why it was owed is kept as
written.

The deferral as it stood until then, kept because it is the account of a risk that was carried for
two days:

- **Not verified**: that `contracts/fileservice/` changesets 001–006 (vendored from `framework-libraries`
  `58aad8664`, **2023-12-22**) still match the deployed `fileservice` schema - the columns, types and
  defaults of `metadata` and `content`. The vendored DDL is nearly three years old, so the
  `FileServicePayloadStoreIT` Testcontainers seed may not be what the platform actually runs.
- **Why deferred**: no STE access from the machine this increment is being built on. The check needs a
  live stack, not a local clone, so it cannot be closed from here.
- **Who and where**: the implementer who took T033/T044 (the file-service leg) runs `\d metadata`
  and `\d content` against the `fileservice` database on an **STE stack** (per `~/moj/cpp-knowledgebase/ENVIRONMENTS.md`;
  STE-86 is the canonical reference), records the date, the stack number and the result in the
  `fileservice/` provenance row of `contracts/README.md`, and re-vendors the changesets if they differ.
- **What actually happened**: T033 (`FileServicePayloadStoreIT`) and T044 (`FileServicePayloadStore`)
  landed on **2026-09-06 against the unverified 2023-12-22 DDL**, and the earlier deadline below - that
  the check complete before T033 started - was not met. The suite's own javadoc carries the caveat, so
  a reader of the test is told what it pins: the vendored DDL, not yet a verified copy of the deployed
  schema. Nothing was re-vendored, because nothing has been compared.
- **Deadline**: this must complete **before the first deploy of the generation half to any stack**.
  A Testcontainers suite seeded from the vendored changesets is green whether or not they describe the
  deployed schema, so what the check now protects is not the test but the first night a run writes a
  real payload into a real file service: a column that has moved is a batch that fails
  PAYLOAD_STORE_UNAVAILABLE for every key, and it is cheaper to learn that from `\d metadata` than
  from a night's registers. Same owner and the same re-vendoring instruction as above. The dependency
  note below carries the same deadline.

---

## Phase 2: Foundational (schema, domain, ports, store, validators, metrics)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests first ⚠️

- [x] T007 [P] `persistence/SchemaMigrationV2IT` — V2 facts: new `processed_output` columns and status
      values, `register_batch` with its partial unique constraint, `register_notification` with
      `UNIQUE (batch_id, email_address)`, `shedlock`, the two indexes (data-model.md). Red: table
      `register_batch` does not exist.
- [x] T008 [P] `persistence/RegisterStoreIT` — `record` inserts RECORDED with document, hearing,
      register time, defendant type, flag state; same-key re-share supersedes in one transaction
      (`superseded_by` set, only the newer row active); a row with a `batch_id` is never superseded;
      a later-date re-share starts a fresh row; `activeUnbatched()` excludes superseded, batched and
      `recorded_flag_state <> 'ON'` rows; `markGenerated(batchId)` flips only that batch's rows
      (**P3 pin: `generation_flips_only_the_batchs_own_rows`** — fails against a court-centre-wide
      flip). Red: `UnsupportedOperationException` from the seam replaced by a failing assertion on
      the row count.
- [x] T009 [P] `config/ConfigurationValidationTest` (extend) — generation enabled requires
      fileservice url, flag endpoint/label, SDG and NN endpoints, template id; zone must be
      `Europe/London` unless `zone-override-acknowledged` (`SchedulingConfigTest.job_is_scheduled_in_europe_london`
      lives here as a binding test); `completion=event` requires broker url; STUB modes refused with a
      namespace; **P9 pin: `blank_email_template_refuses_to_start_in_live_mode`**. Red: context starts.
- [x] T010 [P] `config/GenerationMetricsTest` — instrument names and tags:
      `courtregister.batches{outcome}`, `courtregister.generation.request{response_code}`,
      `courtregister.generation.latency`, `courtregister.generation.reconciled`,
      `courtregister.generation.skipped{reason}`, `courtregister.notifications{status,response_code}`,
      gauges `oldest_recorded_unbatched_age`, `oldest_generating_age`, `pending_after_deadline`,
      `flag_read_ok`. Red: meter absent.
- [x] T011 [P] `domain/BatchStateTest` — `BatchStatus` transitions permitted/refused per the
      data-model state machine; `BatchFailureReason` and `NotificationStatus` codes bounded;
      `FlagDecision` never carries free text beyond a bounded reason code. Red: illegal transition
      not refused.

### Implementation

- [x] T012 `src/main/resources/db/migration/V2__register_store.sql` per data-model.md (columns,
      constraints, partial indexes, `shedlock`). Green: T007.
- [x] T013 [P] Domain types in `domain/`: `RegisterBatch`, `BatchStatus`, `BatchFailureReason`,
      `RegisterNotification`, `NotificationStatus`, `RecordedFlagState`, `FlagDecision`,
      `CourtCentreDay`, `RegisterRecord`, `RenderRequest`, `DocumentStatus`, `RunReport`,
      `GenerationFailedException`, `NotificationFailedException`, `PayloadStoreUnavailableException`;
      `CompletionReason.RECORDED` replaces `SUBMITTED` (keep `SUBMITTED` only for
      `progression-post` mode, documented). Green: T011.
- [x] T014 [P] Ports in `application/`: `RegisterStore`, `PayloadFileStore`, `DocumentRenderer`,
      `DocumentOutcomeSink`, `RegisterNotifier`, `FeatureFlagReader` exactly as the plan's port
      contracts; no Azure/HTTP/JDBC/JMS type in any signature.
- [x] T015 `persistence/ProcessedOutputRepository` (extend) + `persistence/RegisterBatchRepository` +
      `persistence/RegisterNotificationRepository` + `persistence/JdbcRegisterStore` implementing
      `RegisterStore` with write-time supersession and batch-scoped `mark*`. Green: T008 incl. P3 —
      **flip P3 to FIXED in this commit.**
- [x] T016 [P] `config/GenerationProperties`, `config/FeatureFlagProperties`,
      `config/FileServiceDataSourceConfig` (second `DataSource` + `JdbcClient`, Hikari
      `initialization-fail-timeout: -1`, `socketTimeout: 30`), `config/PropertiesValidator` (extend)
      — the T009 rules; template id validated as UUID in LIVE mode. Green: T009 incl. P9 — **flip P9
      to FIXED in this commit.**
- [x] T017 [P] `config/GenerationMetrics`. Green: T010.
- [x] T018 [P] `adapter/stub/Stub{PayloadFileStore,DocumentRenderer,RegisterNotifier,FeatureFlagReader}`
      and `config/StubGenerationConfig` for test/local profiles (flag ON by default, overridable).
      Landed untested; `adapter/stub/StubGenerationAdaptersTest` now pins all four stubs, the
      per-mode selection and the ON-by-default answer as an [A] characterisation.

**Checkpoint**: `./gradlew build` green; V2 applies on a fresh and on a V1 database. Codex review 2.

**Approved TDD exceptions (Phase 2)**. Principle II is non-negotiable and these are recorded, not
excused: the pieces of behaviour listed below were pinned after the code, or in the same commit as
it, rather than before it, and each is named here with the reason it was allowed. Approver:
**design owner, 2026-09-06**. Anything else in Phase 2 that arrived test-after is a defect, not a
precedent.

1. **The V1-to-V2 backfill case (`SchemaMigrationV2IT.BackfillOfADeployedV1Row`), written after
   T012.** The migration's backfill values were chosen and hand-checked when V2 was written, and the
   case accepts an existing migration rather than specifying a new behaviour: there was no design
   decision left for a red run to make. Recorded because the promise the migration's comment makes
   was, until that case, asserted nowhere.
2. **`FlagDecision`'s bounded-reason behaviour, landed in the compile-safe seams commit before
   T011.** The seam had to carry the invariant - a decision that can never hold free text - because
   five test authors were writing against it in parallel and a seam that left the question open
   would have had each of them answer it differently. The invariant is pinned by `BatchStateTest`,
   which is T011's own file and was written against the seam rather than after the implementation.
3. **The seven Phase 2 review-gate remediation commits, each landing its new test and the
   implementation it pins in one commit.** They are `ad553fb` "fix(schema): keep V2 compatible with
   a live pre-002 pod during rollout", `461b96c` "fix(store): record the court centre OU code the
   batch needs", `6918069` "fix(store): assemble a batch atomically or not at all", `8ed4e65`
   "fix(store): progression-post statements cannot touch register rows", `2ee4178` "fix(store):
   batch state changes are compare-and-set through the state machine", `06b4f64` "fix(metrics):
   register the generation instruments in the context" and `20c2b38` "fix(store): bound the
   generator's failure reason at 512 characters". The failing assertion is quoted in each commit
   body and the test precedes the code within the commit, so the red run is recorded and reviewable
   where the convention asks for it; splitting unpushed history for a review fix was judged higher
   risk than recording the exception.
4. **T018's stub adapters (`d1d081c` "feat(stubs): generation-side stub adapters for test and local
   profiles"), landed before their characterisation test.** Stub adapters that deliberately do
   nothing were committed first and `3dd8532` "test(stubs): characterise the generation stubs"
   followed; approved as an **[A]** characterisation after the fact, which is what the test turned
   out to be - it accepts four stubs, the per-mode selection and the ON-by-default answer as they
   stand rather than specifying behaviour a red run could have driven.
5. **The exhaustive attribution-mapping pin (`81e13d0` "test(store): pin the attribution mapping for
   every failure reason"), written after `3d76be9` introduced `BatchFailureReason.isGeneratorAttributed()`.**
   The mapping was specified by the review finding it answers and landed with its own red run in
   `e2879e2`/`3d76be9` for GENERATION_FAILED; `81e13d0` widens the pin to every constant (an
   EnumSource table, both directions of GENERATION_TIMED_OUT, and the schema check naming every
   state) and is an **[A]** characterisation whose non-vacuity was shown by mutation. Approved as
   such; the mapping itself changed nothing.

---

## Phase 3: User Story 1 — record, not POST (Priority: P1) 🎯 MVP

**Goal**: a command ends as a RECORDED row with the validated document; nothing is sent to progression.

**Independent Test**: publish a command → one RECORDED row, reason `recorded`, no HTTP to progression;
re-share supersedes; schema-invalid fails SCHEMA_INVALID with no row.

### Tests first ⚠️

- [x] T019 [P] [US1] `pipeline/DefendantTypeResolverTest` — goldens from T004: Applicant default,
      Appellant (appeal + applicantAppellant flags with applicant masterDefendant), Respondent
      (respondent masterDefendantId among defendants), null when no court application; the
      as-at-hearing deviation pinned (`respondents_are_read_from_the_hearing_not_the_aggregate`).
      Red: seam throws → failing equality.
- [x] T020 [P] [US1] `application/DistributionPipelineTest` (extend) — in `record` mode the pipeline
      calls `RegisterStore.record` with the validated document, defendant type and flag state and
      completes `recorded`; SCHEMA_INVALID is raised before any record; store failure ⇒ abandon +
      suspend; in `progression-post` mode the 001 submission path is used unchanged. Red: reason is
      `submitted`.
- [x] T021 [P] [US1] `pipeline/RegisterTransformationChainTest` (extend) — the chain sets
      `defendantType` on the document; the 001 goldens are otherwise byte-identical. Red: field absent.

### Implementation

- [x] T022 [US1] `pipeline/DefendantTypeResolver` — port of `PROG CourtRegisterHandler.getDefendantType`
      (`:131-153`) over `hearing.courtApplications[]` by `courtApplicationId`. Green: T019.
- [x] T023 [US1] `pipeline/RegisterTransformationChain` (extend) wires the resolver; `domain/CourtRegisterDocument`
      gains `defendantType` (already a legal field in the frozen schema — confirm with
      `OutboundContractValidationTest`). Green: T021.
- [x] T024 [US1] `application/DistributionPipeline` — `RegisterStore` replaces `RegisterSubmissionClient`
      in `record` mode; `config/PipelineConfig` selects by `courtregister.output`; `adapter/progression`
      retained behind `progression-post`. Green: T020.
- [x] T024a [US1] V3 partial unique index enforcing one active row per `(hearing_id,
      court_centre_id, register_date)` and the unique-violation retry path in
      `JdbcRegisterStore.record`; `RegisterStoreIT` case
      `two_concurrent_re_shares_leave_exactly_one_active_row` red first (T020 group), then the
      migration + code. Green: that case.
- [x] T025 [A] [US1] `e2e/RecordEndToEndIT` — emulator + Postgres + real payload cache: command →
      RECORDED row with digest of the stored document, reason `recorded`, **zero** requests to the
      progression WireMock; re-share ⇒ supersession; schema-invalid ⇒ dead-letter, no row. Record the
      first observed result.

**Checkpoint**: US1 independently demonstrable via quickstart step 1. Codex review 3.

---

## Phase 4: User Story 4 + 7 — the flag is the one lever (Priority: P1)

**Goal**: the nightly job reads `CourtRegisterService` first and does nothing when OFF/unreadable;
the CLI respects it; recorded-while-off rows are stamped and excluded.

**Independent Test**: flag OFF ⇒ skipped `flag-off`; unreadable ⇒ `flag-unreadable`; ON ⇒ proceeds;
CLI refuses without `--ignore-flag`.

### Tests first ⚠️

- [x] T026 [P] [US4] `adapter/appconfig/AppConfigurationFlagReaderTest` (WireMock on the App
      Configuration `kv` endpoint) — `enabled:true` ⇒ ON; `false` ⇒ OFF; 404 / 403 / 5xx / timeout /
      malformed ⇒ UNREADABLE with a bounded reason; label and key are passed; never throws. Red: seam
      throws.
- [x] T027 [P] [US4] `batch/FeatureFlagGateTest` — OFF/UNREADABLE ⇒ `Skipped(reason)` + metric
      `generation.skipped{reason}` + `flag_read_ok` gauge; ON ⇒ `Proceed`; `ignoreFlag=true` ⇒
      `Proceed(overridden)` logged. Red: proceeds on OFF.
- [x] T028 [P] [US4] `inbound/RecordedFlagStateTest` - `RecordedFlagStateSource` renews the reading
      on a fixed 30 s schedule (half `FlagStateSnapshot.WINDOW`) for as long as the pod is consuming,
      with a single on-demand refresh for an arrival that still finds none; the listener attaches the
      last reading (≤ 60 s old) as ON/OFF and UNKNOWN where there is none, and recording never waits
      on a read. Red: state absent. (Landed as an on-arrival refresh and corrected to the schedule at
      `8abc073`; research §12 carries the traffic rationale.)

### Implementation

- [x] T029 [US4] `adapter/appconfig/AppConfigurationFlagReader` (`ConfigurationClient` +
      `WorkloadIdentityCredential`, 2 s timeout, feature-flag JSON `enabled`) + `config/LiveFeatureFlagConfig`.
      Green: T026.
- [x] T030 [US4] `batch/FeatureFlagGate` + `inbound` flag-state attachment (`RecordedFlagState` on the
      command context, stamped by `RegisterStore.record`). Green: T027, T028.

**Checkpoint**: `check-flag` semantics proven at unit level; wiring to the job lands in Phase 5. Codex
review 4 (folded into review 5 if Phase 5 follows immediately).

---

## Phase 5: User Story 2 — the nightly batch renders one PDF per court centre and date (Priority: P1)

**Goal**: 18:00 Europe/London, flag-gated, one batch per key, payload byte-identical to progression's,
file-service insert, `generate-document` 202, outcome from `public.event` with the reconciler safety net.

**Independent Test**: seeded rows → job → two batches GENERATING with golden payloads → events →
GENERATED / FAILED with reason; grace period → reconciler.

### Tests first ⚠️ (all [P] — one file each; seams from T013/T014)

- [x] T031 [P] [US2] `pipeline/PdfPayloadMapperTest` — byte-identical to every T004 pdf-payload golden;
      `sentinel_is_substituted_exactly_as_progression_did` (C24 `####` → `\n`, `:336`);
      `DASH` fallbacks, date formats, `getAge`, aliases, counsel, application validity
      (`isApplicationValid`) each pinned on a golden that exercises it. Red: seam throws.
- [x] T032 [P] [US2] `batch/BatchAssemblerTest` — grouping by (court centre, register date); first
      row's `fileName`; recorded-while-off and superseded rows excluded; `batch_id` stamped;
      `system_generated` from the trigger source. Red: one batch for two keys.
- [x] T033 [P] [US2] `adapter/fileservice/FileServicePayloadStoreIT` (Testcontainers Postgres seeded
      from `contracts/fileservice/`) — inserts `metadata` (JSONB with progression's five keys) and
      `content` (bytea, `deleted=false`) under the given `file_id`; unavailable DB ⇒
      `PayloadStoreUnavailableException`; no other statement issued (statement log). Red: seam throws.
- [x] T034 [P] [US2] `adapter/systemdocgenerator/SystemDocGeneratorClientTest` (WireMock) — body
      verbatim (`templateIdentifier=OEE_Layout5`, `conversionFormat=pdf`, `payloadFileServiceId`,
      `sourceCorrelationId=batch_id`, `originatingSource=CourtRegisterService`), media type,
      `CJSCPPUID`; 202 only (200 ⇒ `RENDER_REQUEST_REJECTED`); `retry_taxonomy_matches_the_submission_client`
      (shared `RetryPolicy`); `query` maps the four optional fields. Red: seam throws.
- [x] T035 [P] [US2] `adapter/publicevents/DocumentEventListenerTest` — parses a framework
      `JsonEnvelope` body, reads `CPPNAME`, ignores `originatingSource != CourtRegisterService`
      (acknowledged, counted), routes `document-available` / `generation-failed` to the sink with
      `sourceCorrelationId` and `payloadFileServiceId`. Red: sink not called.
- [x] T036 [P] [US2] `adapter/publicevents/DocumentEventListenerIT` (embedded Artemis) — durable
      subscription: an event published while the listener is stopped is delivered on restart; the
      selector excludes other `CPPNAME`s; two events for one batch ⇒ one outcome. Red: event lost.
- [x] T037 [P] [US2] `application/DocumentOutcomeSinkTest` — `documentAvailable` ⇒ batch GENERATED,
      `document_file_id`, `completed_by=EVENT`, rows of **this batch only** → GENERATED (reuses the
      P3 pin through the store); `generationFailed` ⇒ FAILED `GENERATION_FAILED` + `sdg_reason`
      (**P2 pin: `generation_failed_event_fails_the_batch_with_reason`**); unknown correlation ⇒
      counted, ignored; duplicate ⇒ idempotent. Red: FAILED not recorded.
- [x] T038 [P] [US2] `batch/GenerationReconcilerTest` — GENERATING older than grace ⇒ one `query`;
      answer applied via the sink with `completed_by=RECONCILER` + `reconciled` metric; still pending
      ⇒ FAILED `GENERATION_TIMED_OUT`. Red: no query.
- [x] T039 [P] [US2] `application/RegisterGenerationServiceTest` — payload id minted and persisted
      before `store`; store failure ⇒ FAILED `PAYLOAD_STORE_UNAVAILABLE`, rows stay RECORDED; 202 ⇒
      GENERATING + `requested_at`; transient ⇒ retry within the run deadline then
      `RENDER_REQUEST_FAILED`; **P5 pin: `assembly_failure_fails_the_batch_and_the_run_continues`**.
      Red: seam throws.
- [x] T040 [P] [US2] `batch/RegisterGenerationJobTest` — reads the flag first (gate outcome ends the
      run); sequential batches; run deadline bounds requesting only; `RunReport` emitted with counts,
      flag decision, duration; `@Scheduled` cron `0 0 18 * * MON-FRI` zone `Europe/London` and
      `@SchedulerLock` present (`job_is_scheduled_in_europe_london`). Red: runs with flag OFF.
- [x] T041 [P] [US2] `config/PublicEventsHealthIndicatorTest` + `config/FileServiceRunHealthIndicatorTest`
      — broker state and last-delivery age reported, never in readiness; file-service datasource DOWN
      affects readiness only while a run is in progress. Red: readiness includes broker.

### Implementation (serialised where files are shared)

- [x] T042 [US2] `pipeline/PdfPayloadMapper` — Java→Java port of `PROG CourtRegisterPdfPayloadGenerator`
      (364 ln), `javax.json` → Jackson tree, every helper verbatim. Green: T031.
- [x] T043 [P] [US2] `batch/BatchAssembler` (resolve data-model.md's open question / design Q27
      first). Green: T032.
- [x] T044 [P] [US2] `adapter/fileservice/FileServicePayloadStore` (JdbcClient over the second
      DataSource; the two INSERTs from data-model.md). Green: T033.
- [x] T045 [P] [US2] `adapter/systemdocgenerator/SystemDocGeneratorClient` implementing
      `DocumentRenderer` (RestClient + shared `RetryPolicy`). Green: T034.
- [x] T046 [P] [US2] `adapter/publicevents/DocumentEventListener` (`@JmsListener`, destination /
      subscription / selector from properties) + `PublicEventEnvelope` + `config/PublicEventsConfig`
      (listener container factory, durable, client-id, `auto-startup` tied to generation enabled).
      Green: T035, T036.
- [x] T047 [US2] `application/DocumentOutcomeSinkImpl` (one code path for event and reconciler).
      Green: T037 — **flip P2 to FIXED in this commit.**
- [x] T048 [US2] `batch/GenerationReconciler`. Green: T038.
- [x] T049 [US2] `application/RegisterGenerationService`. Green: T039 — **flip P5 to FIXED in this
      commit.**
- [x] T050 [US2] `batch/RegisterGenerationJob` (+ `config/SchedulingConfig` with ShedLock provider and
      the zone validation) wiring gate → assembler → service → report. Green: T040.
- [x] T051 [P] [US2] `config/PublicEventsHealthIndicator`, `config/FileServiceRunHealthIndicator`;
      readiness group unchanged for the broker. Green: T041.
- [x] T052 [A] [US2] `e2e/GenerationEndToEndIT` — seeded RECORDED rows, flag ON (WireMock), job run →
      file-service rows present → SDG WireMock received `generate-document` → embedded Artemis
      `document-available` → GENERATED → (Phase 6 completes the notify leg; until then assert
      GENERATED and the run report). Record the first observed result. (**first observed run: RED**,
      and on the assembly rather than on the suite. Two defects only an assembled context could show:
      the reconciler's schedule and lock sat on the counting `reconcile()`, which returns a primitive
      and which ShedLock's interceptor therefore refuses to lock, so a generating pod's first
      proceeding run died at the reconcile step; and nothing constructed the downstream half at all,
      so a pod with `courtregister.generation.enabled=true` registered no `@JmsListener` and scheduled
      no run. Both were fixed before this suite's recorded run - the wiring at `602474c` under its own
      red run `a24ac4f`, the ShedLock defect at `935a1c3` under its own red run `4b34bd3` - and the
      run recorded in `f562e52` is the green one after them.)
- [x] T053 [A] [US2] `e2e/FlagGateEndToEndIT` — flag OFF ⇒ run skipped, nothing requested, rows stay
      RECORDED; unreadable (WireMock 500) ⇒ skipped `flag-unreadable`; ON ⇒ requested. Record.
      (**first observed run: RED**, for the same two reasons T052's was, and this suite is what found
      the ShedLock one: it is the only place the run's proxy exists. Green after `602474c` and
      `935a1c3`; the `935a1c3` commit also narrowed the suite's render-request count to the batch it
      seeded, because the shared stack holds other suites' active registers and a night is entitled to
      batch them. The green run of both suites is recorded in `f562e52`.)

**Checkpoint**: batches render end to end against stubs; quickstart steps 2–3 work. Codex review 5.

### Approved TDD exceptions (Phase 5)

One, and it is recorded here rather than argued for in a commit body, which is where it was argued
for until this entry existed. Approver: **design owner, 2026-09-06**. Anything else in Phase 5 that
arrived test-after is a defect, not a precedent.

1. **`5599ae7` "test(persistence): pin the two batch statements that landed without a case",
   written after `7c6ffe6`.** `7c6ffe6` "feat(batch): request a render for each batch, or record
   exactly why not" landed `JdbcRegisterStore.batched` (`BATCH_REGISTERS`) and
   `markPayloadMinted` (`MARK_PAYLOAD_MINTED`) with no automated test of either:
   `RegisterGenerationServiceTest` mocks the `RegisterStore` port, so it pins that the service calls
   them and nothing about what the statements do, and `RegisterStoreIT` was not extended in that
   commit. `5599ae7` added six `RegisterStoreIT` cases afterwards and recorded a passing run.
   **Why no red run was recorded**: the two statements were written as part of T049's requesting
   sequence rather than as behaviour of their own, and by the time the gap was seen the statements
   already existed - so the cases that closed it are **[A]** characterisations of statements that
   were already correct, not the red half of a pair. They accept `batched`'s ordering and
   batch-scoping and `markPayloadMinted`'s PENDING fence as they stand. Splitting unpushed history
   to manufacture a red run for behaviour nobody was going to change was judged higher risk than
   recording the exception, which is the same judgement the Phase 2 and Phase 3 blocks record.
   The statements themselves are unchanged and remain pinned by those six cases.

---

## Phase 6: User Story 3 — every matched Youth Offending Team receives the register once (Priority: P1)

**Goal**: recipient union, one `send-email-notification` per address with the PDF attached, per-recipient
accounting, NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY, resend of every row not ACCEPTED
(`af3a089` widened this from FAILED only: a row minted PENDING and never settled is the same debt to
the same team, and reading only the refusals left it untouched for ever).

**Independent Test**: two-record batch with overlapping recipients ⇒ three requests; one refusal ⇒
PARTIALLY_NOTIFIED; resend ⇒ NOTIFIED.

### Tests first ⚠️

- [x] T054 [P] [US3] `batch/RecipientSetTest` — union by `emailAddress1`, name from first occurrence,
      order stable; **P4 pin: `recipients_are_the_union_across_the_batch_not_the_first_rows`** (fails
      against first-row-only). Red: seam throws. (red at `6e2d7e1`, over the compile-safe seam
      `56eef87`.)
- [x] T055 [P] [US3] `adapter/notificationnotify/NotificationNotifyClientTest` (WireMock) — body
      verbatim (`templateId`, `sendToAddress`, `fileId`, `personalisation.yotsName`) with no
      `notificationId` in it, media type `application/vnd.notificationnotify.email+json`, `CJSCPPUID`,
      path `/notifications/{notificationId}` carrying the id; 202 only; retry reuses the same id in
      the path; `retry_taxonomy_matches_the_submission_client`. Red: seam throws. (red at `8ef978c`.)
- [x] T056 [P] [US3] `application/RegisterNotifierServiceTest` — rows minted PENDING before any POST;
      ACCEPTED/FAILED per recipient; batch NOTIFIED / PARTIALLY_NOTIFIED; **P1 pin:
      `a_batch_with_no_recipients_ends_notified_nobody_not_generated_forever`**; `resendFailed(batchId)`
      re-requests every row not ACCEPTED, PENDING included (`af3a089`; the task was written as FAILED
      only). Red: seam throws. (red at `86db5b5`.)

### Implementation

- [x] T057 [US3] `batch/RecipientSet`. Green: T054 — **flip P4 to FIXED in this commit** (sign-off
      marker stays). (`11e077a`; P4 flipped to FIXED there, sign-off marker kept.)
- [x] T058 [P] [US3] `adapter/notificationnotify/NotificationNotifyClient` implementing
      `RegisterNotifier`. Green: T055. (`fbce03e`.)
- [x] T059 [US3] `application/RegisterNotifierService` (+ wiring from `DocumentOutcomeSinkImpl` on
      GENERATED). Green: T056 — **flip P1 to FIXED in this commit.** (`322fc07`; P1 flipped to FIXED
      there.)
- [x] T060 [A] [US3] `e2e/GenerationEndToEndIT` (complete) — … → NN WireMock received one request per
      distinct recipient with the document id → NOTIFIED; run report counts. Record. (**first observed
      run of the completed suite: GREEN**, all three cases, at `1337c78`. The suite as T052 left it was
      **RED at `322fc07`** and it is the only thing that was: the full `./gradlew build` failed on its
      one case, whose 30s await for GENERATED could not be met, because wiring the notifying leg to
      the mark that records the document means a delivered outcome no longer leaves a batch at
      GENERATED - the stack stubbed no notificationnotify, so every e-mail was refused 404 and the
      batch settled PARTIALLY_NOTIFIED. That is what "complete" meant here, so the case was rewritten
      rather than adjusted: two hearings, three distinct recipients, one POST each at
      `/notifications/{id}` under the identity its own `register_notification` row was minted with,
      `fileId` the document's id rather than the payload's, and all three rows ACCEPTED on 202. The run
      report is asserted for what it can say - a GENERATING batch and no notified one, since the
      document arrives long after the run has ended.)
- [x] T061 [A] [US3] `e2e/GenerationFailureEndToEndIT` — `generation-failed` ⇒ FAILED with reason; no
      event ⇒ reconciler completes; NN 500 for one recipient ⇒ PARTIALLY_NOTIFIED; resend via the
      service ⇒ NOTIFIED. Record. (**first observed run: GREEN**, all four cases, at `0d85ead`. The
      un-answered batch reaches the reconciler by having its own `requested_at` moved into the past
      rather than by shortening the grace period, because that read is over a `register_batch` table
      every suite in this JVM shares and shortening it would make every other suite's in-flight batch
      overdue at the same moment; for the same reason the reconciled count is asserted positive rather
      than exactly one. The resend case asserts the WireMock paths: three POSTs, two of them the
      refused team's own path, which is the whole of what makes the retry reach the attempt it is
      retrying rather than send a second e-mail.)

**Checkpoint**: the whole downstream leg works against stubs; seven P rows FIXED (P1, P2, P3, P4,
P5, P9 and the appended P10, as `doc/DEFECT-FIXES.md` counts them; the phase was planned as six,
before P10 was appended under review). Codex review 6.

### Approved TDD exceptions (Phase 6)

Three. They are recorded here rather than argued for in a commit body, which is where the first of
them was argued for until this entry existed. Approver: **design owner, 2026-09-07**. Anything else
in Phase 6 that arrived test-after is a defect, not a precedent.

This block said "one" until the first Codex review of Phase 6 found the second, and "two" until the
second review found the third, so the claim of completeness it made was wrong for as long as each of
those stood: an exception block is only worth reading if it is exhaustive, and the entries below were
missing from it rather than judged and allowed.

1. **`1b1bf17` "test(notify): pin the hand-on from a generated batch to its recipients", written
   after `322fc07`.** T059 (`322fc07`) wired `DocumentOutcomeSinkImpl.documentAvailable` to mark the
   batch GENERATED and then call `RegisterNotifierService.notify`, and no case held either half of
   that down: `DocumentOutcomeSinkTest` gained the mock and the constructor argument and nothing
   else. So the claim that a redelivered `document-available` is recognised and never notified twice
   - made in the sink's javadoc, in that suite's, and in `data-model.md` - was pinned nowhere at unit
   level, and the rewritten `GenerationEndToEndIT` was green on its first observed run, so it is not
   the red half either. `1b1bf17` added seven cases in one nested class over the seams the suite
   already had.
   **Why no red run was recorded**: they are **[A]** characterisations of behaviour that already
   existed, so the run recorded is a passing one, exactly as the Phase 3 and Phase 5 blocks record
   for the same shape.
   **Non-vacuity was shown by mutation instead**, and the runs are in the commit body: with
   `notifier.notify(...)` removed from `documentAvailable`,
   `a_redelivered_document_available_should_tell_the_recipients_once` and
   `a_generated_batch_should_be_marked_before_its_recipients_are_told(CompletedBy)[1]` fail on
   "Wanted but not invoked: registerNotifierService.notify(" (20 tests completed, 2 failed, 1
   skipped); with `notify` moved ahead of `markGenerated`, the same parameterised case fails on
   "Verification in order failure / Wanted but not invoked:" and
   `a_mark_that_did_not_take_should_not_be_followed_by_an_e_mail` fails with it (20 tests completed,
   2 failed, 1 skipped). The sink is unchanged and green under both mutations reverted.
   No production code and no defect-register row moved in that commit: P1 stays pinned by
   `RegisterNotifierServiceTest`.
2. **`56eef87` "test: compile-safe seams for the notification slice" landed the LIVE notifier wiring
   complete.** The commit is a seams commit and the other three seams in it are what a seams commit
   is for - `RecipientSet.unionOf`, `NotificationNotifyClient` and `RegisterNotifierService`, each
   throwing `UnsupportedOperationException` with the task that would implement it. `config/
   LiveNotificationConfig` is not: it arrived finished, with both conditions
   (`courtregister.generation.enabled`, `courtregister.generation.nn-mode` LIVE with
   `matchIfMissing`), the endpoint, the `system-user-id` identity and both timeouts on the request
   factory. No red run preceded it and no case then held any of it down - the client's own suite
   builds a `RestClient` by hand and `GenerationWiringContextTest` asks only that a
   `DocumentRenderer` and a `PayloadFileStore` resolve live - so a condition inverted, an endpoint
   read off the wrong setting or a timeout left unset would have been found by a deployed pod.
   **Rationale**: the seam that the three parallel test authors of T057, T058 and T055 had to build
   against was a real bean graph, and the graph is what this configuration *is* - a
   `@Configuration` whose bean method throws contributes nothing a context can resolve, so a
   throwing seam here would have left all three of them without the thing they were writing
   against. The wiring was therefore carried by the seams commit deliberately, and the cost is that
   it went in uncharacterised.
   **Behaviour is now characterised** by `config/LiveNotificationConfigTest` (`d6a4b4a`
   "test(config): characterise the live notifier wiring"), an **[A]** characterisation labelled as
   one in its javadoc: eight cases over which notifier a context resolves under each of the two
   conditions, the test profile and an unnamed mode, and what the live client was built out of - the
   endpoint and the `CJSCPPUID` identity asked of a real socket, the read timeout asked of a socket
   that goes quiet, and the template's deliberate absence. Green on introduction, with non-vacuity
   shown by two mutations quoted in that commit body.
   **Approved: design owner, 2026-09-07.**
   No production code moved for it: the configuration is unchanged, and this is the exception being
   recorded rather than a fix being made.
3. **`31d51bc` "fix(notify): claim the batch before notifying and make accepted rows terminal"
   landed the claim's repository and schema assertions beside the implementation they pin.** The red
   half of that pair, `b74e880` "test(notify): two notifiers on one batch post once and never demote
   an accepted row", covered the service over a doubled repository only: it asserted that a second
   notifier is refused, posts nothing and answers `ALREADY_NOTIFYING`, and that a settlement the
   store refused is counted rather than believed. What arrived unpinned-then-pinned-in-one-commit is
   the store's own half - `RegisterBatchRepositoryIT.Claiming`'s six cases (the advisory lock and
   compare-and-set, the second notifier's refusal, the token-fenced release, and the takeover once
   the lease has run out) and `SchemaMigrationV2IT`'s `notifying_since` / `notifier_token` columns
   and `register_batch_notifier_claim_chk`.
   **Rationale**: the claim's SQL was shaped by the review finding it answers - two notifiers over
   one generated batch - and the shape was decided and pinned in the same commit, so there was no
   design decision left for a red run over the statement to make; the behaviour itself was driven
   red-first at service level by `b74e880`. Those repository and schema assertions are therefore
   **[A]** characterisations that arrived with the implementation rather than the red half of a pair,
   and they are the only assertions in `31d51bc`.
   **Approved: design owner, 2026-09-07.**

**From this round on, every change to a statement or to the schema has its integration red run
first**: the failing assertion recorded for it is the `RegisterBatchRepositoryIT` /
`RegisterNotificationRepositoryIT` / `SchemaMigrationV2IT` case against a real Postgres, never a
service test over a doubled repository, because a double is free to agree with whatever the caller
believes the statement does. The three review fixes that follow this entry - the unconditional
attempt tally, the notification-specific lease with token-fenced renewal, and the three-way claim
answer - are held to it.

---

## Phase 7: User Story 5 — operations CLI in the image (Priority: P2)

**Goal**: `generate-register`, `notify-register`, `list-batches`, `supersede-before`, `check-flag`,
dispatched by `docker/startup.sh`, no HTTP endpoint.

### Tests first ⚠️

- [ ] T062 [P] [US5] `batch/cli/GenerateRegisterCliTest` — `--date` re-assembles FAILED and unbatched
      rows for the date (optionally `--court-house`, `--batch`, `--recorded-before`); refuses on flag OFF
      without `--ignore-flag`; with it proceeds and prints the override; `system_generated=false`.
- [ ] T063 [P] [US5] `batch/cli/NotifyRegisterCliTest`, `ListBatchesCliTest`, `SupersedeBeforeCliTest`,
      `CheckFlagCliTest` — behaviours per spec US5 and FR-016; outputs are stable, line-oriented, PII-free
      (addresses masked in `list-batches`).
- [ ] T064 [P] [US5] `config/HttpSurfaceTest` (extend) — still zero controllers with generation enabled.

### Implementation

- [ ] T065 [US5] `batch/cli/CliMain` (+ the five commands) running the context with
      `courtregister.cli=true` (no listener, no scheduler, generation adapters LIVE). Green: T062, T063.
- [ ] T066 [US5] `docker/startup.sh` dispatch: a recognised first argument runs `CliMain` with the
      remaining args; otherwise unchanged `exec java -jar`. Green: T064; `scripts/container-smoke.sh`
      gains `startup.sh check-flag` (exit 0 against the compose WireMock).
- [ ] T067 [A] [US5] `CliDispatchIT` (container) — `generate-register --help` and `check-flag` exit 0
      inside the built image. Record.

**Checkpoint**: quickstart steps 2–4 run as written. Codex review 7.

---

## Phase 8: User Stories 6 and 7 — register, audit, observability (Priority: P2)

- [ ] T068 [P] [US6] `doc/DEFECT-FIXES.md` — confirm P1–P5, P9 FIXED with their pinning tests named
      verbatim; P3/P4 sign-off markers; P6/P7 RETIRED with the retirement-PR pointer; P8 MOOT; header
      counts updated (36 C rows + 9 P rows).
- [ ] T069 [P] [US6] `differential/DifferentialAuditTest` (extend) — the 001 document corpus is
      unchanged by 002 (digest equality), and every `PdfPayloadMapper` golden is reproduced; the
      `RegisteredDefectFixes` table gains the P numbers.
- [ ] T070 [P] [US7] `config/TelemetryPrivacyTest` (extend) — recipient e-mail addresses, recipient
      names and `sdg_reason` free text never at INFO or above; batch and notification ids are.
- [ ] T071 [P] [US7] `e2e/ReadinessPolicyIT` (extend) — broker down: ready; file-service DB down outside
      a run: ready; during a run: not ready.
- [ ] T072 [US7] Run report: one structured log line per run (`event=register_generation_run`) with
      the `RunReport` fields; gauges published; documented in the metrics section of the Confluence
      page (note for the page owner in the PR description).

**Checkpoint**: Codex review 8.

---

## Phase 9: Polish and documentation sync

- [ ] T073 [P] Rewrite `.claude/rules/design_rules.md` for the 002 shape (ports, `batch/`, adapters,
      state machines, the one-lever rule); rewrite "The Four Contracts" in
      `.claude/agents/spec-validator.md`; update `.claude/agents/software-engineer.md` rules (record
      not POST; the flag; ids before calls). Docs-only, exempt from the loop.
- [ ] T074 [P] `README.md` Status → 002 complete; `CLAUDE.md` unchanged unless a rule moved; the
      constitution's Sync Impact Report `⚠ pending` items → `✅`.
- [ ] T075 [P] `scripts/container-smoke.sh` — readiness UP < 60 s with generation enabled against the
      compose stubs; `check-flag` exit 0.
- [ ] T076 Spec checklists: `checklists/requirements.md` re-validated against the delivered behaviour;
      add `checklists/consolidation-audit.md` recording T069's result and the goldens' provenance.
- [ ] T077 [A] Final `./gradlew build` (PMD, Checkstyle 0 warnings, JaCoCo gate) green on the branch;
      Codex review 9 (whole increment) PASS; report token use per phase.

---

## Dependencies & execution order

- Phase 1 → Phase 2 → Phase 3 (US1) → Phase 4 (US4/US7 gate) → Phase 5 (US2) → Phase 6 (US3) →
  Phase 7 (US5) → Phase 8 → Phase 9. Phases 3 and 4 may run in parallel after Phase 2 (different
  files) if two implementers are available, but **their commits are serialised**.
- Within each "Tests first" block every task is [P]; the implementation block is serialised where a
  file is shared (`DistributionPipeline`, `RegisterTransformationChain`, `PipelineConfig`,
  `PropertiesValidator`, `DocumentOutcomeSinkImpl`).
- T004 (goldens) blocks T031 and T019. T005 (DDL verification) blocks nothing in code and did not
  hold up T033/T044, which landed against the unverified DDL on 2026-09-06; it was **closed on
  2026-09-07 against SIT with no delta** (checkpoint note above), so the deploy deadline it carried
  is met. T006 (P rows
  PLANNED) blocks every "flip P# to FIXED" commit.

## Notes

- **MVP** = Phases 1–3: the service records instead of POSTing. It is deployable behind
  `courtregister.output=record` with generation disabled, which is exactly the PH.02 state.
- The 001 progression adapter and its tests stay green throughout; they are exercised in
  `progression-post` mode by T020.
- Commit narrative convention: test commits quote the red assertion; implementation commits quote the
  green run and, where a P row flips, the register diff.
