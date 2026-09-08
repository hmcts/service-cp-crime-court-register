# Tasks: Consolidate the progression court-register leg

**Input**: Design documents from `/specs/002-consolidate-progression-leg/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix - do not
rename them without updating the matrix. A P-fix task's test is written to **fail against the
progression behaviour and pass against the fix**, and its DEFECT-FIXES row names it.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs - interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` - so that the recorded red run is a **failing
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
- **[A]**: acceptance/characterisation - see above
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

- [x] T007 [P] `persistence/SchemaMigrationV2IT` - V2 facts: new `processed_output` columns and status
      values, `register_batch` with its partial unique constraint, `register_notification` with
      `UNIQUE (batch_id, email_address)`, `shedlock`, the two indexes (data-model.md). Red: table
      `register_batch` does not exist.
- [x] T008 [P] `persistence/RegisterStoreIT` - `record` inserts RECORDED with document, hearing,
      register time, defendant type, flag state; same-key re-share supersedes in one transaction
      (`superseded_by` set, only the newer row active); a row with a `batch_id` is never superseded;
      a later-date re-share starts a fresh row; `activeUnbatched()` excludes superseded, batched and
      `recorded_flag_state <> 'ON'` rows; `markGenerated(batchId)` flips only that batch's rows
      (**P3 pin: `generation_flips_only_the_batchs_own_rows`** - fails against a court-centre-wide
      flip). Red: `UnsupportedOperationException` from the seam replaced by a failing assertion on
      the row count.
- [x] T009 [P] `config/ConfigurationValidationTest` (extend) - generation enabled requires
      fileservice url, flag endpoint/label, SDG and NN endpoints, template id; zone must be
      `Europe/London` unless `zone-override-acknowledged` (`SchedulingConfigTest.job_is_scheduled_in_europe_london`
      lives here as a binding test); `completion=event` requires broker url; STUB modes refused with a
      namespace; **P9 pin: `blank_email_template_refuses_to_start_in_live_mode`**. Red: context starts.
- [x] T010 [P] `config/GenerationMetricsTest` - instrument names and tags:
      `courtregister.batches{outcome}`, `courtregister.generation.request{response_code}`,
      `courtregister.generation.latency`, `courtregister.generation.reconciled`,
      `courtregister.generation.skipped{reason}`, `courtregister.notifications{status,response_code}`,
      gauges `oldest_recorded_unbatched_age`, `oldest_generating_age`, `pending_after_deadline`,
      `flag_read_ok`. Red: meter absent.
- [x] T011 [P] `domain/BatchStateTest` - `BatchStatus` transitions permitted/refused per the
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
      `RegisterStore` with write-time supersession and batch-scoped `mark*`. Green: T008 incl. P3  - 
      **flip P3 to FIXED in this commit.**
- [x] T016 [P] `config/GenerationProperties`, `config/FeatureFlagProperties`,
      `config/FileServiceDataSourceConfig` (second `DataSource` + `JdbcClient`, Hikari
      `initialization-fail-timeout: -1`, `socketTimeout: 30`), `config/PropertiesValidator` (extend)
      - the T009 rules; template id validated as UUID in LIVE mode. Green: T009 incl. P9 - **flip P9
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

## Phase 3: User Story 1 - record, not POST (Priority: P1) 🎯 MVP

**Goal**: a command ends as a RECORDED row with the validated document; nothing is sent to progression.

**Independent Test**: publish a command → one RECORDED row, reason `recorded`, no HTTP to progression;
re-share supersedes; schema-invalid fails SCHEMA_INVALID with no row.

### Tests first ⚠️

- [x] T019 [P] [US1] `pipeline/DefendantTypeResolverTest` - goldens from T004: Applicant default,
      Appellant (appeal + applicantAppellant flags with applicant masterDefendant), Respondent
      (respondent masterDefendantId among defendants), null when no court application; the
      as-at-hearing deviation pinned (`respondents_are_read_from_the_hearing_not_the_aggregate`).
      Red: seam throws → failing equality.
- [x] T020 [P] [US1] `application/DistributionPipelineTest` (extend) - in `record` mode the pipeline
      calls `RegisterStore.record` with the validated document, defendant type and flag state and
      completes `recorded`; SCHEMA_INVALID is raised before any record; store failure ⇒ abandon +
      suspend; in `progression-post` mode the 001 submission path is used unchanged. Red: reason is
      `submitted`.
- [x] T021 [P] [US1] `pipeline/RegisterTransformationChainTest` (extend) - the chain sets
      `defendantType` on the document; the 001 goldens are otherwise byte-identical. Red: field absent.

### Implementation

- [x] T022 [US1] `pipeline/DefendantTypeResolver` - port of `PROG CourtRegisterHandler.getDefendantType`
      (`:131-153`) over `hearing.courtApplications[]` by `courtApplicationId`. Green: T019.
- [x] T023 [US1] `pipeline/RegisterTransformationChain` (extend) wires the resolver; `domain/CourtRegisterDocument`
      gains `defendantType` (already a legal field in the frozen schema - confirm with
      `OutboundContractValidationTest`). Green: T021.
- [x] T024 [US1] `application/DistributionPipeline` - `RegisterStore` replaces `RegisterSubmissionClient`
      in `record` mode; `config/PipelineConfig` selects by `courtregister.output`; `adapter/progression`
      retained behind `progression-post`. Green: T020.
- [x] T024a [US1] V3 partial unique index enforcing one active row per `(hearing_id,
      court_centre_id, register_date)` and the unique-violation retry path in
      `JdbcRegisterStore.record`; `RegisterStoreIT` case
      `two_concurrent_re_shares_leave_exactly_one_active_row` red first (T020 group), then the
      migration + code. Green: that case.
- [x] T025 [A] [US1] `e2e/RecordEndToEndIT` - emulator + Postgres + real payload cache: command →
      RECORDED row with digest of the stored document, reason `recorded`, **zero** requests to the
      progression WireMock; re-share ⇒ supersession; schema-invalid ⇒ dead-letter, no row. Record the
      first observed result.

**Checkpoint**: US1 independently demonstrable via quickstart step 1. Codex review 3.

---

## Phase 4: User Story 4 + 7 - the flag is the one lever (Priority: P1)

**Goal**: the nightly job reads `CourtRegisterService` first and does nothing when OFF/unreadable;
the CLI respects it; recorded-while-off rows are stamped and excluded.

**Independent Test**: flag OFF ⇒ skipped `flag-off`; unreadable ⇒ `flag-unreadable`; ON ⇒ proceeds;
CLI refuses without `--ignore-flag`.

### Tests first ⚠️

- [x] T026 [P] [US4] `adapter/appconfig/AppConfigurationFlagReaderTest` (WireMock on the App
      Configuration `kv` endpoint) - `enabled:true` ⇒ ON; `false` ⇒ OFF; 404 / 403 / 5xx / timeout /
      malformed ⇒ UNREADABLE with a bounded reason; label and key are passed; never throws. Red: seam
      throws.
- [x] T027 [P] [US4] `batch/FeatureFlagGateTest` - OFF/UNREADABLE ⇒ `Skipped(reason)` + metric
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

## Phase 5: User Story 2 - the nightly batch renders one PDF per court centre and date (Priority: P1)

**Goal**: 18:00 Europe/London, flag-gated, one batch per key, payload byte-identical to progression's,
file-service insert, `generate-document` 202, outcome from `public.event` with the reconciler safety net.

**Independent Test**: seeded rows → job → two batches GENERATING with golden payloads → events →
GENERATED / FAILED with reason; grace period → reconciler.

### Tests first ⚠️ (all [P] - one file each; seams from T013/T014)

- [x] T031 [P] [US2] `pipeline/PdfPayloadMapperTest` - byte-identical to every T004 pdf-payload golden;
      `sentinel_is_substituted_exactly_as_progression_did` (C24 `####` → `\n`, `:336`);
      `DASH` fallbacks, date formats, `getAge`, aliases, counsel, application validity
      (`isApplicationValid`) each pinned on a golden that exercises it. Red: seam throws.
- [x] T032 [P] [US2] `batch/BatchAssemblerTest` - grouping by (court centre, register date); first
      row's `fileName`; recorded-while-off and superseded rows excluded; `batch_id` stamped;
      `system_generated` from the trigger source. Red: one batch for two keys.
- [x] T033 [P] [US2] `adapter/fileservice/FileServicePayloadStoreIT` (Testcontainers Postgres seeded
      from `contracts/fileservice/`) - inserts `metadata` (JSONB with progression's five keys) and
      `content` (bytea, `deleted=false`) under the given `file_id`; unavailable DB ⇒
      `PayloadStoreUnavailableException`; no other statement issued (statement log). Red: seam throws.
- [x] T034 [P] [US2] `adapter/systemdocgenerator/SystemDocGeneratorClientTest` (WireMock) - body
      verbatim (`templateIdentifier=OEE_Layout5`, `conversionFormat=pdf`, `payloadFileServiceId`,
      `sourceCorrelationId=batch_id`, `originatingSource=CourtRegisterService`), media type,
      `CJSCPPUID`; 202 only (200 ⇒ `RENDER_REQUEST_REJECTED`); `retry_taxonomy_matches_the_submission_client`
      (shared `RetryPolicy`); `query` maps the four optional fields. Red: seam throws.
- [x] T035 [P] [US2] `adapter/publicevents/DocumentEventListenerTest` - parses a framework
      `JsonEnvelope` body, reads `CPPNAME`, ignores `originatingSource != CourtRegisterService`
      (acknowledged, counted), routes `document-available` / `generation-failed` to the sink with
      `sourceCorrelationId` and `payloadFileServiceId`. Red: sink not called.
- [x] T036 [P] [US2] `adapter/publicevents/DocumentEventListenerIT` (embedded Artemis) - durable
      subscription: an event published while the listener is stopped is delivered on restart; the
      selector excludes other `CPPNAME`s; two events for one batch ⇒ one outcome. Red: event lost.
- [x] T037 [P] [US2] `application/DocumentOutcomeSinkTest` - `documentAvailable` ⇒ batch GENERATED,
      `document_file_id`, `completed_by=EVENT`, rows of **this batch only** → GENERATED (reuses the
      P3 pin through the store); `generationFailed` ⇒ FAILED `GENERATION_FAILED` + `sdg_reason`
      (**P2 pin: `generation_failed_event_fails_the_batch_with_reason`**); unknown correlation ⇒
      counted, ignored; duplicate ⇒ idempotent. Red: FAILED not recorded.
- [x] T038 [P] [US2] `batch/GenerationReconcilerTest` - GENERATING older than grace ⇒ one `query`;
      answer applied via the sink with `completed_by=RECONCILER` + `reconciled` metric; still pending
      ⇒ FAILED `GENERATION_TIMED_OUT`. Red: no query.
- [x] T039 [P] [US2] `application/RegisterGenerationServiceTest` - payload id minted and persisted
      before `store`; store failure ⇒ FAILED `PAYLOAD_STORE_UNAVAILABLE`, rows stay RECORDED; 202 ⇒
      GENERATING + `requested_at`; transient ⇒ retry within the run deadline then
      `RENDER_REQUEST_FAILED`; **P5 pin: `assembly_failure_fails_the_batch_and_the_run_continues`**.
      Red: seam throws.
- [x] T040 [P] [US2] `batch/RegisterGenerationJobTest` - reads the flag first (gate outcome ends the
      run); sequential batches; run deadline bounds requesting only; `RunReport` emitted with counts,
      flag decision, duration; `@Scheduled` cron `0 0 18 * * MON-FRI` zone `Europe/London` and
      `@SchedulerLock` present (`job_is_scheduled_in_europe_london`). Red: runs with flag OFF.
- [x] T041 [P] [US2] `config/PublicEventsHealthIndicatorTest` + `config/FileServiceRunHealthIndicatorTest`
      - broker state and last-delivery age reported, never in readiness; file-service datasource DOWN
      affects readiness only while a run is in progress. Red: readiness includes broker.

### Implementation (serialised where files are shared)

- [x] T042 [US2] `pipeline/PdfPayloadMapper` - Java→Java port of `PROG CourtRegisterPdfPayloadGenerator`
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
      Green: T037 - **flip P2 to FIXED in this commit.**
- [x] T048 [US2] `batch/GenerationReconciler`. Green: T038.
- [x] T049 [US2] `application/RegisterGenerationService`. Green: T039 - **flip P5 to FIXED in this
      commit.**
- [x] T050 [US2] `batch/RegisterGenerationJob` (+ `config/SchedulingConfig` with ShedLock provider and
      the zone validation) wiring gate → assembler → service → report. Green: T040.
- [x] T051 [P] [US2] `config/PublicEventsHealthIndicator`, `config/FileServiceRunHealthIndicator`;
      readiness group unchanged for the broker. Green: T041.
- [x] T052 [A] [US2] `e2e/GenerationEndToEndIT` - seeded RECORDED rows, flag ON (WireMock), job run →
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
- [x] T053 [A] [US2] `e2e/FlagGateEndToEndIT` - flag OFF ⇒ run skipped, nothing requested, rows stay
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

## Phase 6: User Story 3 - every matched Youth Offending Team receives the register once (Priority: P1)

**Goal**: recipient union, one `send-email-notification` per address with the PDF attached, per-recipient
accounting, NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY, resend of every row not ACCEPTED
(`af3a089` widened this from FAILED only: a row minted PENDING and never settled is the same debt to
the same team, and reading only the refusals left it untouched for ever).

**Independent Test**: two-record batch with overlapping recipients ⇒ three requests; one refusal ⇒
PARTIALLY_NOTIFIED; resend ⇒ NOTIFIED.

### Tests first ⚠️

- [x] T054 [P] [US3] `batch/RecipientSetTest` - union by `emailAddress1`, name from first occurrence,
      order stable; **P4 pin: `recipients_are_the_union_across_the_batch_not_the_first_rows`** (fails
      against first-row-only). Red: seam throws. (red at `6e2d7e1`, over the compile-safe seam
      `56eef87`.)
- [x] T055 [P] [US3] `adapter/notificationnotify/NotificationNotifyClientTest` (WireMock) - body
      verbatim (`templateId`, `sendToAddress`, `fileId`, `personalisation.yotsName`) with no
      `notificationId` in it, media type `application/vnd.notificationnotify.email+json`, `CJSCPPUID`,
      path `/notifications/{notificationId}` carrying the id; 202 only; retry reuses the same id in
      the path; `retry_taxonomy_matches_the_submission_client`. Red: seam throws. (red at `8ef978c`.)
- [x] T056 [P] [US3] `application/RegisterNotifierServiceTest` - rows minted PENDING before any POST;
      ACCEPTED/FAILED per recipient; batch NOTIFIED / PARTIALLY_NOTIFIED; **P1 pin:
      `a_batch_with_no_recipients_ends_notified_nobody_not_generated_forever`**; `resendFailed(batchId)`
      re-requests every row not ACCEPTED, PENDING included (`af3a089`; the task was written as FAILED
      only). Red: seam throws. (red at `86db5b5`.)

### Implementation

- [x] T057 [US3] `batch/RecipientSet`. Green: T054 - **flip P4 to FIXED in this commit** (sign-off
      marker stays). (`11e077a`; P4 flipped to FIXED there, sign-off marker kept.)
- [x] T058 [P] [US3] `adapter/notificationnotify/NotificationNotifyClient` implementing
      `RegisterNotifier`. Green: T055. (`fbce03e`.)
- [x] T059 [US3] `application/RegisterNotifierService` (+ wiring from `DocumentOutcomeSinkImpl` on
      GENERATED). Green: T056 - **flip P1 to FIXED in this commit.** (`322fc07`; P1 flipped to FIXED
      there.)
- [x] T060 [A] [US3] `e2e/GenerationEndToEndIT` (complete) - … → NN WireMock received one request per
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
- [x] T061 [A] [US3] `e2e/GenerationFailureEndToEndIT` - `generation-failed` ⇒ FAILED with reason; no
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

## Phase 7: User Story 5 - operations CLI in the image (Priority: P2)

**Goal**: `generate-register`, `notify-register`, `list-batches`, `supersede-before`, `check-flag`,
dispatched by `docker/startup.sh`, no HTTP endpoint.

### Tests first ⚠️

- [x] T062 [P] [US5] `batch/cli/GenerateRegisterCliTest` - `--date` re-assembles FAILED and unbatched
      rows for the date (optionally `--court-house`, `--batch`, `--recorded-before`); refuses on flag OFF
      without `--ignore-flag`; with it proceeds and prints the override; `system_generated=false`.
      (red at `8749f83`, over the compile-safe seams `62aa056`: 26 tests completed, 26 failed, every
      one an `AssertJMultipleFailuresError` and none a compile error, a null dereference or an
      unverified interaction. `the_days_failed_batches_should_be_released_and_re_assembled` on
      "Expecting code not to raise a throwable but caught java.lang.UnsupportedOperationException:
      T065" and then "expected: [d65c68fe-1b79-4899-a206-43841a1fce9b] but was: []";
      `a_flag_that_says_off_should_refuse_with_its_own_code_and_change_nothing` on "expected: 1 but
      was: -1" and on Expecting actual "" to contain "flag-off". Green at `98c8a10`:
      generate-register 26 tests, 0 failures, 0 errors.
      **Four red/green pairs landed on this command after the tick, under reviews 7 and 8**, each
      quoting its own red assertion in its commit narrative: `261231e` / `c011bb1` (a narrowed
      regeneration reckons its history from the whole day), `b08a077` / `210695c` (a batch this run
      would not re-assemble is withheld rather than released), `2bbda7d` / `8805d47` (a run the flag
      stopped is logged as the decline it is, through `CliMain.declined`, rather than as arguments
      that were not usable) and `ed312b2` / `22a953a` (which instant `--recorded-before` is read
      against - a characterisation and the wording it corrected). `9a96898` adds the case that reads
      the summary line itself, exception 6 below.)
- [x] T063 [P] [US5] `batch/cli/NotifyRegisterCliTest`, `ListBatchesCliTest`, `SupersedeBeforeCliTest`,
      `CheckFlagCliTest` - behaviours per spec US5 and FR-016; outputs are stable, line-oriented, PII-free
      (addresses masked in `list-batches`).
      (red at `964da97`: 64 tests completed, 64 failed over the four suites.
      `CheckFlagCliTest.a_flag_read_as_on_should_exit_zero_and_say_so` on "expected: 0 but was: -1"
      and Expecting actual [] to contain exactly ["flag=ON"];
      `ListBatchesCliTest.each_batch_should_be_listed_with_its_state_its_records_and_its_recipients`
      on Expecting actual [] to contain exactly the batch line and the two masked recipient lines;
      `NotifyRegisterCliTest.a_resend_should_ask_for_the_failed_recipients_and_never_the_whole_batch`
      and `SupersedeBeforeCliTest.the_period_asked_for_should_be_exactly_the_one_that_was_typed` on
      "Wanted but not invoked: registerNotifierService.resendFailed(6f1d0c62-...)" and
      "registerStore.supersedeSharedBefore(2026-09-04T17:00:00Z)". Green at `98c8a10`:
      notify-register 13, list-batches 25, supersede-before 13, check-flag 13, each 0 failures and
      0 errors.
      **`notify-register`'s output contract then changed, at `879c9a3` / `f4b638a` (review 7)**, and
      the outputs this task calls stable are stable from there rather than from `98c8a10`: the
      report line now ends `disposition=<code>` and the exit code is taken from it - SETTLED and
      ALREADY_NOTIFYING 0, CLAIM_LOST and INCOMPLETE 2 - because a tally that came back is the batch
      as it stood rather than what this call did, and a runbook step that read exit 0 over a batch
      left unsettled would not run the one command that recovers it. `67a6aa8` / `f1b5c9b`
      (review 8) then corrected what the usage line promises: the command re-requests the
      recipients no e-mail has been accepted for, which is every non-ACCEPTED row and a fresh row
      for a recipient the batch holds none for, rather than "the FAILED recipients". `3c7e11e` /
      `fea8459` moved where a batch with no court house sorts in `list-batches`, which is the other
      output this task pins.)
- [x] T064 [P] [US5] `config/HttpSurfaceTest` (extend) - still zero controllers with generation enabled.
      (`config/CliModeConfigTest` lands with it, because "zero controllers" and "no consumer, no
      schedule, no listener" are one property's job, and it is the suite that boots the same
      generating pod twice differing by `courtregister.cli` alone. Red at `c3d8ff7`: 14 tests, 3
      failed, all three failing assertions - `holds no Service Bus consumer` on "Expecting empty but
      was: [courtRegisterProcessorClient]", `schedules nothing` on "Expecting empty but was:
      [uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob.run, ...]" and `runs no listener
      container` on "Expecting empty but was: [org.springframework.jms.listener
      .DefaultMessageListenerContainer@61cd3317]". Green at `98c8a10`: CliModeConfigTest 8 tests,
      HttpSurfaceTest 6 tests, 0 failures. The other ten cases of the two suites were green on
      introduction and the commit body says so: they state a property the service already had and
      that Phase 7 must not take away, so no implementation follows them.)

### Implementation

- [x] T065 [US5] `batch/cli/CliMain` (+ the five commands) running the context with
      `courtregister.cli=true` (no listener, no scheduler, generation adapters LIVE). Green: T062, T063.
      (`98c8a10`, green as quoted under T062 to T064. What `courtregister.cli` turns off is three
      configurations rather than three beans, through one `Condition` beside the property's own name:
      `ServiceBusConsumerConfig` owns the processor and the only component permitted to start it,
      `SchedulingConfig` owns `@EnableScheduling` as well as the job, `PublicEventsConfig` owns the
      container factory as well as the listener, so switching the configuration off is what makes
      each absence complete. It deliberately left five persistence statements as
      `UnsupportedOperationException("T065")` seams rather than land them untested -
      `JdbcRegisterStore.batchesOn` / `releaseFailed` / `recordedWhileOff` / `supersedeSharedBefore`
      (statements 4b, 9a, 11, 12) and `RegisterBatchRepository.findByRegisterDate` (statement 12) -
      and they were closed against a real Postgres afterwards: red at `903d33b` (88 tests completed,
      16 failed, each a failing assertion quoting "java.lang.UnsupportedOperationException: T065" at
      the seam's own line, the other 72 cases of the two suites passing unchanged), green at
      `e43cca3` (classes=22 tests=88 failures=0 errors=0), which also cleared the branch's one open
      `pmdMain` violation, the four repeated `"T065"` literals. Until they landed,
      `generate-register`, `list-batches --date` and `supersede-before` refused at the store rather
      than at the argument. What this commit landed uncharacterised is exception 2 below.
      **Two of those five statements were then found defective and fixed, each with its own
      integration red run against a real Postgres**, which is why the "closed against a real
      Postgres afterwards" above is the beginning of their record rather than the end of it:
      `releaseFailed` at `ba7670d` / `6fb6fb3` and `markFailed`'s releasing branch at `8745144` /
      `7245d9c` (review 7), which supersede a released register the estate has already replaced
      instead of handing it back; and both statements again at `ce76e21` / `6c8334a` (review 8),
      which bound that supersession to a register shared *after* the one being released - the pair
      the other way round had the current register written SUPERSEDED against the one it replaced
      and dropped from the answer, so no run and no command reached it again. `fdaf331` adds the
      case that pins the order `batchesOn` answers a day in, exception 6 below.)
- [x] T066 [US5] `docker/startup.sh` dispatch: a recognised first argument runs `CliMain` with the
      remaining args; otherwise unchanged `exec java -jar`. Green: T064; `scripts/container-smoke.sh`
      gains `startup.sh check-flag` (exit 0 against the compose WireMock).
      (`ce238a5`: the five names are `CliMain.COMMANDS` and the two lists are one list; a recognised
      first argument is dispatched out of the fat jar through `PropertiesLauncher` and
      `-Dloader.main`, because a Boot 4 manifest names `JarLauncher` and `java -jar` cannot run a
      second main class out of the same archive; `exec`, so the container exits on the command's own
      code and an operator's Ctrl-C reaches the JVM; the one line the script prints goes to stderr,
      alone among its lines, so a runbook's grep of stdout is unaffected; any other first argument
      falls through to the unchanged `exec java -jar`. That commit records no run of its own -
      exception 3 below - so the verification is the smoke script's and T067's.
      `./scripts/container-smoke.sh` exit 0 at `441d653`, printing "PASS: readiness reported UP
      within the 60s budget" and "PASS: startup.sh check-flag printed flag=ON and exited 0"; that is
      also the commit that dropped the smoke's `COURTREGISTER_GENERATION_FLAG_MODE=STUB` override, so
      the reading is taken through the deployed reader. **The recorded smoke run was made with a
      local, uncommitted `docker-compose.override.yml`** dropping the four host port publications
      (5432, 5433, 8161, 61616) unrelated long-running containers on that machine already hold; it is
      named in that commit body and was deleted before it, and nothing in the script reaches a
      dependency from the host. `9cb7303` / `d7c4319` are what let the container read the flag at
      all: `courtregister.feature.credential`, `workload-identity` by default and `local-test` for
      the local loop, refused by `PropertiesValidator` wherever the endpoint's host ends
      `.azconfig.io` or a Service Bus namespace says the pod is deployed. `441d653` runs the compose
      `app` service generation-enabled against the committed stubs, and `15c1ae2` corrects the
      quickstart's generation-enabled `bootRun` block, which could never have started as written -
      the `workload-identity` default with none of the three projected variables present.)
- [x] T067 [A] [US5] `CliDispatchIT` (container) - `generate-register --help` and `check-flag` exit 0
      inside the built image. Record. (**first observed run: GREEN**, both cases, at `84ac9cc`:
      "check-flag reads the one lever through the deployed reader and exits 0 PASSED" and
      "generate-register --help prints what the command takes and exits 0 PASSED", BUILD SUCCESSFUL
      in 28s, over an image Testcontainers builds from the repo's own Dockerfile and starts with no
      arguments, so `docker/startup.sh` falls through to the application as a deployed pod does.
      `./startup.sh check-flag` exit 0, stdout `flag=ON`; `./startup.sh generate-register --help`
      exit 0, stdout "usage: generate-register --date D [--court-house H] [--batch B]
      [--ignore-flag] [--recorded-before T]"; both also assert the script's stderr notice "Running
      the <command> command from /app/", which is what tells a dispatch out of the fat jar apart
      from a second application having been started. The extra stack is one WireMock serving the
      committed App Configuration `kv` mapping, on the `local-test` credential and **not**
      `COURTREGISTER_GENERATION_FLAG_MODE=STUB`, which the task offered: STUB is unavailable, and
      empirically rather than by assumption - with generation disabled the image answered
      `generate-register --help` "outcome=failed reason=command-not-wired" exit 2, because
      `CliMain.registryOf` resolved the generation beans as the command was built, and with
      generation enabled `PropertiesValidator` refuses STUB outright. **That first half was a defect
      and is fixed** (review 7): `CliMain.wired` now answers `--help` with the command's own usage
      before it resolves a bean, so an intake-only pod prints the usage and exits 0 - red at
      `bcd05e8` in `CliMainTest`, green with the fix. `check-flag` still needs generation enabled,
      because the flag reader is one of the beans that deployment builds, which is why this suite
      still forces it. Non-vacuity, two mutations
      applied together and reverted before the commit: `CheckFlagCli.FLAG` "flag=" to "flagging="
      and `GenerateRegisterCli.USAGE` "usage: " to "takes: ", each failing its own assertion
      ("could not find the following element(s): [\"flag=ON\"]" and the usage line) with both exit
      codes still 0, so the failure is on the printed line rather than on the dispatch. `eb4b411`
      adds `test.dependsOn(bootJar)` so the suite has the jar the image copies under a plain
      `./gradlew test` rather than skipping on an assumption - exception 5 below, which is where the
      `build --dry-run` scheduling evidence lives, that commit having recorded a
      `test --tests '*CliDispatchIT'` listing instead. Two more cases landed on this suite under
      review 8: a mistyped command name answered with the five names and exit 2 (`7e282ca` /
      `674753b`), and stdout carrying the report and nothing else (`fe4750d` / `294d93e`).)

**Checkpoint**: the compose block of quickstart.md **has now been run**, and running it is what
rewrote it (`782b1e6`, which renumbered its steps and is the last commit of the phase). What that
run recorded, against the stack the block brings up:

- `docker compose exec app ./startup.sh generate-register --date 2026-09-07` prints
  `date=2026-09-07 released=0 registers=0 batches=0 requested=0 deferred=0` and exits 0; the
  observed line is carried beside the step as a comment.
- `list-batches --date D` on an empty day prints no batch line and still exits 0, "which is what
  it did".
- the flag-off gate ran verbatim: `PUT /flag/off` 200, then
  `command=generate-register outcome=refused reason=flag-off` with the usage line and exit 1,
  `check-flag` `flag=OFF` exit 0, `--ignore-flag`
  `command=generate-register reason=overridden` and the summary line, exit 0; then `PUT /flag/on`
  200 and `check-flag` `flag=ON` exit 0.

**"through PENDING to NOTIFIED" is dropped, because that block cannot reach it in principle.** The
compose `app` sets `COURTREGISTER_PAYLOAD_MODE=STUB` and the stub payload source fetches nothing,
so a command published to `courtregister.requests` completes `no-defendants` and writes no
`processed_output` row - verified rather than reasoned, `select count(*)` on `processed_output` and
`register_batch` both 0 after a valid `Hearing_Resulted`. The RECORDED-to-NOTIFIED sequence is
proved by `e2e/RecordEndToEndIT` and `e2e/GenerationEndToEndIT` under `./gradlew test`, which is
what quickstart.md now says. Also verified: `check-flag` through the entrypoint against the compose
WireMock on the `local-test` credential (`./scripts/container-smoke.sh`, both PASS lines,
`441d653`), and `check-flag` plus `generate-register --help` inside the built image
(`CliDispatchIT`, `84ac9cc`). Still not re-run: the **host-side** `bootRun` block, whose correction
at `15c1ae2` was reasoned from a recorded refusal because 5432 was occupied - it is the one thing
of this phase the Build stage still owes. Codex review 7.

### Approved TDD exceptions (Phase 7)

Five approved, and two more recorded below awaiting approval. They are recorded here rather than
argued for in a commit body. Approver for 1 to 5: **design owner, 2026-09-07**. Anything else in
Phase 7 that arrived test-after is a defect, not a precedent.

Two things in the phase were judged against this list and are deliberately not on it. `c3d8ff7`
(T064) is a test task with a recorded red run, and the ten cases of it that were green on
introduction characterise a property the service already had, with no implementation following them.
`84ac9cc` (T067) is an **[A]** acceptance task, which the preamble already exempts from a red run;
its observed run and its two reverted mutations are recorded in its tick line above rather than here.

1. **`62aa056` "test: compile-safe seams for the operations CLI" landed `config/CliModeConfig`
   finished.** Seven of the eight classes in it are what a seams commit is for - `CliMain.main` and
   `.run`, `Args.parse` and the five command bodies, each throwing
   `UnsupportedOperationException("T065")`. `CliModeConfig` is not: it arrived complete, a
   `@Configuration` reading `courtregister.cli` through `@Value` and answering `cliMode()`, with
   `application.yaml` shipping the key false beside it. No red run preceded either, and nothing in
   that commit could have held them down, because what the property does is turn three
   configurations off and the three conditionals arrived with T065.
   **Why no red run was recorded**: the property had to be named and read before three
   configurations could condition on it, and the name is the thing all three agree on - a throwing
   seam for a property read is not available. The behaviour it carries was then driven red-first
   where it is observable: `c3d8ff7`'s three failing assertions are a context started with
   `courtregister.cli=true` still holding the consumer, the schedule and the listener, and `98c8a10`
   is the green. In that commit `CliModeConfig` stopped being a bean at all - its reading was the
   condition all along - so the finished class this one carried no longer exists in that shape; the
   property's name, its default and the one condition that reads it do.
   **Non-vacuity was shown by mutation instead**: with `CliModeConfig.CLI_PROPERTY` changed to
   `courtregister.cli-mode`, so the condition reads a name nothing sets, `./gradlew test --tests
   '...config.CliModeConfigTest' -Dtest.noFailFast=true` gives "8 tests completed, 3 failed" -
   `holds no Service Bus consumer, so a command takes no delivery` on "Expecting empty but was:
   [courtRegisterProcessorClient]", `schedules nothing, so a command cannot generate the night
   twice` on "Expecting empty but was: [uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob
   .run, ...]", and `runs no listener container, so the durable subscription is left alone` on
   "Expecting empty but was: [org.springframework.jms.listener.DefaultMessageListenerContainer]".
   Mutation reverted before this documentation commit.
   **The same exercise found that the shipped default is pinned nowhere**: with `NOT_CLI` flipped
   from `"false"` to `"true"`, so an unset property means CLI mode, `CliModeConfigTest` and
   `HttpSurfaceTest` are both still green (BUILD SUCCESSFUL in 49s) - each context sets the property
   explicitly, so neither asks what an ordinary pod gets when nothing sets it at all. That half of
   what this commit landed is still uncharacterised; it is carried as a follow-up rather than
   approved, and a case for it belongs with T075 or Phase 8.
   **Approved: design owner, 2026-09-07.**
2. **`98c8a10` "feat(cli): the operations commands, run inside the service's own context" landed
   `CliMain`'s own dispatch half and `Args`' grammar uncovered.** The five commands and the three
   conditionals were driven red-first (`8749f83`, `964da97`, `c3d8ff7`), and they are most of the
   commit. What no case held down is the entry point itself and the parser: `main`, `run`,
   `dispatch`, the `COMMANDS` list, the exit-code propagation, `usage()` and the three report
   helpers, and `Args`' own refusals - a name given twice, a value where a name was expected, `--`
   on its own. The five command suites reach the parser only through the invocations they make of
   it and none of them asserts a rule of the grammar, and `generate-register --help` was uncovered
   as well.
   **Why no red run was recorded**: the dispatch was written as the plumbing under the five commands
   T062 and T063 drive rather than as behaviour of its own, and by the time the gap was seen it
   already existed - so the cases that closed it are **[A]** characterisations, which is the shape
   the Phase 3, Phase 5 and Phase 6 blocks record for the same thing.
   **Behaviour is now characterised** by `batch/cli/CliMainTest` and `batch/cli/ArgsTest`
   (`025ec21` "test(cli): characterise the entry point and the one argument parser", 49 cases green
   on introduction, labelled **[A]** in both class javadocs).
   **Non-vacuity was shown by mutation instead**, three of them, each run and reverted before that
   commit and quoted in its body: `CliMain.run` answering `FAILED` where it refuses an unknown or a
   missing name - "no_command_name_at_all_should_be_refused_with_the_five_names FAILED / expected: 1
   / but was: 2", 25 tests completed, 3 failed; `usage()` without `COMMANDS.forEach`, so the refusal
   lists no names - "could not find the following elements: [the five names]", 25 tests completed,
   4 failed; `Args.parse` without its duplicate check - "a_name_given_twice_should_be_refused_
   whichever_value_it_carried FAILED / Expecting code to raise a throwable.", 24 tests completed,
   6 failed.
   **The characterisation found one real defect, and that one was driven red-first**: `dispatch`
   asked the immutable `COMMANDS` list whether it contained a null name, so an invocation with no
   command name left `main` on a `NullPointerException` and exit 1 - the code that means declined -
   with none of the five names printed. Red at `3420ce3` ("Expecting code not to raise a throwable
   but caught java.lang.NullPointerException at ...ImmutableCollections$ListN.indexOf ... at
   uk.gov.hmcts.cp.courtregister.batch.cli.CliMain.dispatch(CliMain.java:196)", then "expected: 1
   but was: -1", 27 tests completed, 2 failed), green at `7ff5592` (51 tests, then 141 over
   `batch.cli.*`, 0 failures, 0 errors). It is a defect in 002's own code rather than a progression
   one, so no `doc/DEFECT-FIXES.md` row moves for it. `2c6d7bb` is a javadoc-only follow-up to the
   same suite, narrowing `CliMainTest`'s **[A]** label so it does not claim those two dispatch cases
   were green on introduction; no test and no behaviour moved in it.
   **Approved: design owner, 2026-09-07.**
3. **`ce238a5` "build(image): dispatch the operations commands from the entrypoint" carries no
   narrative at all.** The convention this file states twice is that a test commit quotes the red
   assertion and an implementation commit the green run; that commit's body is its subject line and
   nothing else, so the 46 lines it adds to `docker/startup.sh` and the 46 it adds to
   `scripts/container-smoke.sh` went in with nothing recorded about how either was verified.
   **Why no red run was recorded**: both halves are shell. The dispatch is in the entrypoint rather
   than in the application - which is the point of FR-016, since `kubectl exec ... -- ./startup.sh
   <command>` reaches it with the pod's own identity and needs no data-plane credential of its own -
   and no JUnit suite can reach it; `scripts/container-smoke.sh` is the thing that runs it. A shell
   change is the mechanical exemption the preamble grants Phase 1 infrastructure, but that exemption
   is "records verification evidence instead of a red assertion", and this commit recorded none.
   **Verification is recorded here instead, out of runs two later commits made**:
   `./scripts/container-smoke.sh` exit 0 at `441d653`, printing "PASS: readiness reported UP within
   the 60s budget" and "PASS: startup.sh check-flag printed flag=ON and exited 0" - the second
   through the deployed reader, that commit having dropped the smoke's STUB override - and
   `e2e/CliDispatchIT` at `84ac9cc`, which asks the built image for `check-flag` and
   `generate-register --help` by exec and asserts the stderr notice "Running the <command> command
   from /app/". Its two reverted mutations (quoted under T067) fail on the printed lines with both
   exit codes still 0, which is the dispatch being exercised and not the exit code alone.
   **Approved: design owner, 2026-09-07.**
4. **`9cb7303` / `d7c4319` "read the flag under a second identity, and refuse it where it matters"
   carried three things no red run preceded.** The pair itself is red-first, and its discriminating
   cases are the two refusals: `the_local_test_credential_against_a_real_store_should_fail_startup`
   and `the_local_test_credential_on_a_deployed_pod_should_fail_startup` were red at `9cb7303`
   ("Expecting: <Started application [AnnotationConfigApplicationContext@1964ef9 ...]> to have
   failed but context started successfully") and green at `d7c4319`. What arrived without one is
   (a) two cases of that test commit that were green on introduction -
   `the_local_test_credential_against_the_compose_stub_should_start`, which is the accepted case the
   task asked for and where green before and after is what "accepted" means, and
   `the_deployed_credential_against_a_real_store_should_start`, which asserts the `@DefaultValue`
   that landed as the compile-safe seam in the same commit; (b) two **[A]** characterisations of the
   credential that already worked, labelled as such in their javadoc; and (c) a production message
   change - `LiveFeatureFlagConfig`'s missing-variable refusal now names
   `courtregister.feature.credential=local-test` before `courtregister.generation.flag-mode=STUB`,
   and no case asserted the old wording.
   **Why no red run was recorded**: (a) and (b) state behaviour that already held, and (c) is the
   wording of a refusal whose only assertion is that it names the missing variable.
   **Non-vacuity of the two characterisations was shown by mutation instead**, both reverted before
   the commit and quoted in its body: authorising the `workload-identity` branch with a connection
   string instead kills `the_workload_identity_credential_should_not_reach_a_plain_http_store` -
   "Expecting actual: Enabled[] to be an instance of uk.gov.hmcts.cp.courtregister.domain
   .FlagDecision.Unreadable but was instance of ...FlagDecision.Enabled"; disabling the
   missing-variable throw in `LiveFeatureFlagConfig.workloadIdentity` kills
   `the_workload_identity_credential_should_still_refuse_an_incomplete_pod` - "workload-identity
   still refuses to start on a pod missing a projected variable FAILED / Expecting code to raise a
   throwable."
   **Two deviations from the task's wording go on the record with it.** The refusals live in
   `config/ConfigurationValidationTest` as a new `@Nested LocalTestCredential` and the credential
   characterisations in `GenerationWiringContextTest` as a new `@Nested FlagCredential`, because
   neither `PropertiesValidatorTest` nor `config/LiveFeatureFlagConfigTest` exists in this
   repository and every `PropertiesValidator` refusal already lives in the former. And `local-test`
   yields a fixed HMAC connection-string `ConfigurationClient` rather than the `TokenCredential` the
   wording asked for: azure-core's `BearerTokenAuthenticationPolicy` refuses any request whose URL
   is not https before a socket is opened ("token credentials require a URL using the HTTPS protocol
   scheme", read out of its bytecode and confirmed by a throwaway spike whose real reader answered
   `Unreadable[reason=CALL_FAILED]` over http with a fixed `AccessToken`), so a token credential
   cannot read the compose stub at all. The connection-string shape is the one
   `support/GenerationStackConfiguration` and `AppConfigurationFlagReaderTest` already replace the
   credential with, so only the parameter type differs from the wording.
   **Approved: design owner, 2026-09-07.**
5. **`eb4b411` "build(gradle): package the application before the test task runs" has no test
   pair.** It adds `test.dependsOn(bootJar)`, so `e2e/CliDispatchIT` has the fat jar the image
   copies under a plain `./gradlew test` rather than skipping on an assumption.
   **Why no red run was recorded**: task ordering is not behaviour a test can pin - a case asserting
   the jar is there would be asserting the thing the dependency arranges - so verification evidence
   stands in its place. What that commit records is `./gradlew test --tests '*CliDispatchIT'`
   scheduling `:bootJar` ahead of `:test`, with the five-line task listing under it, and the six
   gates green. **The `build --dry-run` evidence is this record's and not that commit's** - no
   commit in the phase mentions `--dry-run` - and it was re-taken here rather than left as written:
   `./gradlew build --dry-run` schedules 28 tasks, `:bootJar` 9th, ahead of `:test` 23rd, `:check`
   27th and `:build` 28th, so no cycle is introduced. The positions first written down here (12, 26,
   30, 31) were the output's line numbers rather than the tasks' positions, three preamble lines
   ahead of each. That is the shape the preamble grants Phase 1 infrastructure; the grant does not
   reach Phase 7, which is why it is written down here.
   **Approved: design owner, 2026-09-07.**

6. **Three [A] characterisations of behaviour Phase 7 already had, from review 8.** Each is
   labelled **[A]** in its own javadoc, each records a passing run rather than a red one, and each
   quotes at least one reverted mutation in its commit body:
   - **`fdaf331`** pins the order `RegisterStore.batchesOn` answers a day in, which the port
     disclaimed ("in no particular order") while `GenerateRegisterCli.released` depended on it - a
     key's base batch has to be released before its supplement. The statement is unchanged and the
     contract now states the order. Mutation: `BATCHES_ON_DAY` ordered `court_centre_id, batch_id`
     answers the supplement first, 3 tests completed 1 failed.
   - **`9a96898`** reads the summary line `generate-register` answers a day with, which
     quickstart.md quotes verbatim and the checkpoint above records as observed, and which no test
     read. Mutations: `released=` printed as `freed=`, and `batches=` printed from
     `registers.size()`.
   - **`4601dfa`** is review 7's and was not written down when it landed: it characterises how
     `PropertiesValidator.REAL_FLAG_STORE` recognises a real flag store - the authority only, on a
     trimmed and lower-cased value, unconditionally on the master switch - where one canonical
     endpoint had stood for all of it. Green on introduction, 128 tests 0 failures, with four
     mutations quoted in its body.
   All three are the shape the Phase 3, 5 and 6 blocks record for the same thing: behaviour that
   already existed, stated by cases that pass on introduction, with non-vacuity shown by mutation
   because there is no red run to show.
   **Awaiting approval: recorded 2026-09-08.**
7. **`b544003` "build(image): make the artefact the image is built from unambiguous" has no test
   pair.** It clears `build/libs/*.jar` in `scripts/container-smoke.sh` before the image is built
   and makes `CliDispatchIT.packagedJar` refuse more than one candidate instead of choosing between
   them, which is what let a stale jar be smoke-tested while the script printed PASS.
   **Why no red run was recorded**: one half is a shell step and the other is the suite's own
   fixture - a case asserting the jar is unambiguous would be asserting the thing the check
   arranges. **Verification evidence stands in its place, and it is empirical**: with a second jar
   put in `build/libs` by hand, `./gradlew test --tests '*CliDispatchIT' -Dtest.noFailFast=true`
   fails before the image is built - "more than one packaged jar in .../build/libs
   [service-cp-crime-court-register-0.0.1.jar, service-cp-crime-court-register-0.0.999.jar]" - and
   is green again with the extra jar removed. Same shape as exception 5, and the same reason it is
   written down: the preamble grants that exemption to Phase 1 infrastructure and not to Phase 7.
   **Awaiting approval: recorded 2026-09-08.**

**The five store statements T065 held back are the Phase 6 rule working rather than an exception to
it**: `batchesOn`, `releaseFailed`, `recordedWhileOff`, `supersedeSharedBefore` and
`findByRegisterDate` were left as seams instead of landing untested, and each got its integration
red run against a real Postgres (`RegisterStoreIT`, `RegisterBatchRepositoryIT`, `903d33b`) before
`e43cca3` implemented it - and both statements that were later found defective were re-driven the
same way, `ba7670d` / `6fb6fb3`, `8745144` / `7245d9c` and `ce76e21` / `6c8334a`.

**Review 8's other fixes are red/green pairs and are recorded on the tick lines they belong to**,
not here: the successor guard on both release statements (T065), the summary line and the flag-off
log sentence (T062), `notify-register`'s usage promise (T063), the entrypoint's answer to a mistyped
name and stdout carrying the report alone (T067), and one outside Phase 7's own tasks -
`507263d` / `c22509c`, which refuses a `courtregister.feature.endpoint` no App Configuration client
can be built from, under the setting's own name rather than as an Azure `IllegalArgumentException`
during refresh. Four documentation-only corrections landed with them (`58769d2`, `e2ee872`,
`fdaf331`'s port contract, and this file), each named in its own commit body.

---

## Phase 8: User Stories 6 and 7 - register, audit, observability (Priority: P2)

- [ ] T068 [P] [US6] `doc/DEFECT-FIXES.md` - confirm P1–P5, P9 FIXED with their pinning tests named
      verbatim; P3/P4 sign-off markers; P6/P7 RETIRED with the retirement-PR pointer; P8 MOOT; header
      counts updated (36 C rows + 9 P rows).
- [ ] T069 [P] [US6] `differential/DifferentialAuditTest` (extend) - the 001 document corpus is
      unchanged by 002 (digest equality), and every `PdfPayloadMapper` golden is reproduced; the
      `RegisteredDefectFixes` table gains the P numbers.
- [ ] T070 [P] [US7] `config/TelemetryPrivacyTest` (extend) - recipient e-mail addresses, recipient
      names and `sdg_reason` free text never at INFO or above; batch and notification ids are.
- [ ] T071 [P] [US7] `e2e/ReadinessPolicyIT` (extend) - broker down: ready; file-service DB down outside
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
- [ ] T075 [P] `scripts/container-smoke.sh` - readiness UP < 60 s with generation enabled against the
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
