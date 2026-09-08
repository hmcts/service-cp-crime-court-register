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
review gate (new session) whose findings are fixed before the next phase starts. From Phase 3 on,
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

**Checkpoint**: build green, compose up, goldens present, register carries the P rows.
Review gate 1.

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

**Checkpoint**: `./gradlew build` green; V2 applies on a fresh and on a V1 database. review gate 2.

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

**Checkpoint**: US1 independently demonstrable via quickstart step 1. review gate 3.

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

**Checkpoint**: `check-flag` semantics proven at unit level; wiring to the job lands in Phase 5.
Review gate 4 (folded into gate 5 if Phase 5 follows immediately).

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

**Checkpoint**: batches render end to end against stubs; quickstart steps 2–3 work. review gate 5.

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
before P10 was appended under review). Review gate 6.

### Approved TDD exceptions (Phase 6)

Three. They are recorded here rather than argued for in a commit body, which is where the first of
them was argued for until this entry existed. Approver: **design owner, 2026-09-07**. Anything else
in Phase 6 that arrived test-after is a defect, not a precedent.

This block said "one" until the first review gate of Phase 6 found the second, and "two" until the
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
      case that pins the order `batchesOn` answers a day in, exception 6 below.
      **The phase gate found the same two statements defective a third and a fourth time, and three
      more defects in the commands' own code**, each a red/green pair. `1609e80` / `3fae1a2` ranks a
      key's registers
      by `(register_time, created_at, output_id)` - predicate and ORDER BY, in `markFailed`'s
      `stamped` CTE and in RELEASE_FAILED - so an equal-instant register that arrived later is the
      successor whatever its identity sorts like, which is the rule statement 1's `incumbent` `<=`
      already encodes; the identity half of the old order was a random tie-break, and the direction
      that lost a register unstamped the older row, made it active beside the register that had
      replaced it and left the write refused by `idx_output_active_register_key`. Red at `1609e80`
      against a real Postgres ("65 tests completed, 2 failed, every failure an assertion":
      "org.springframework.dao.DuplicateKeyException: PreparedStatementCallback; SQL [WITH failed AS
      ( ... ]; ERROR: duplicate key value violates unique constraint
      \"idx_output_active_register_key\"", then "Expecting actual: Optional[BatchOutcome[status=
      PENDING, failureReason=null, sdgReason=null]] to contain: BatchOutcome[status=FAILED,
      failureReason=PAYLOAD_STORE_UNAVAILABLE, sdgReason=null]" - in `markFailed` the release is a
      branch of the statement that marks the batch, so the mark went down with it), green at
      `3fae1a2` (RegisterStoreIT classes=14 tests=65, RegisterBatchRepositoryIT classes=8 tests=34,
      0 failures and 0 errors each; whole suite classes=508 tests=3127 failures=0 errors=0). Two of
      its four cases are mirror **[A]** characterisations - exception 8 below - and the rule is now
      stated in the port javadoc for `markFailed` and `releaseFailed`, in statement 1's javadoc as
      the ranking reading of its own `<=`, and in data-model.md beside statements 9 and 9a, whose
      `superseded_by` invariant had said "a later `register_time`" and never described an
      equal-instant pair. `9e02bd7` / `c0bf9bb` stops `CliMain.unreadable` attaching the parser's
      throwable or writing its message, so the token an operator typed into `--batch`, `--date`,
      `--recorded-before` or `--shared-before` never reaches the pod's log: the WARN carries the
      command name, the argument by the flag name this service owns (`argument=unnamed` where the
      parser refused the invocation's shape before any argument was recognised) and the refusing
      reader's class. Red at `9e02bd7` ("82 tests completed, 14 failed", every one an assertion,
      among them "Expecting throwable message: \"an argument is written --name, and this one is not:
      zqx7.marker@example.invalid\" not to contain: \"zqx7.marker@example.invalid\" but did"), green
      at `c0bf9bb` (`batch.cli.*` with `TelemetryPrivacyTest` classes=41 tests=182 failures=0
      errors=0). `Args.NAMES` is the bounded vocabulary a refusal may repeat, `GenerateRegisterCli`
      reads its three optional arguments one at a time so a refusal can name one, and
      `config/TelemetryPrivacyTest` gained the sweep that reads the log as well as the terminal over
      all five commands - which is the file T070 asks to extend, and T070 should be read against it
      as it now stands. And `07e325b` / `baa0c32` puts the report's destination behind
      `batch/cli/StandardOutput`, a UTF-8 writer over `java.io.FileDescriptor.out` flushed per line,
      so no compiled source in the service names a process stream while the report still reaches the
      descriptor a runbook greps - exception 9 below.
      **The gate's re-review found the successor search too wide as well as wrongly ordered**, and
      `605e6a2` / `382629b` closes it: both searches admitted any row that was not SUPERSEDED, which
      includes increment 001's own PENDING, POSTED and FAILED records of a POST to progression, so a
      POST row sharing the key could be written into `superseded_by` as the register that replaced a
      released one and the day's register was answered with nothing to re-assemble. Silently, in
      both statements: a POST row is outside `idx_output_active_register_key`, so nothing refused
      the write. They now name the states this store leaves a live register in,
      `status IN ('RECORDED', 'GENERATED', 'NOTIFIED')`, a list closed by
      `processed_output_status_chk` and by which statements write which state. Red at `605e6a2`
      against a real Postgres ("67 tests completed, 2 failed", among them "Expecting actual:
      Optional[SupersessionPair[supersededAt=2026-09-08T09:47:56.378295Z, supersededBy=
      a0288f7e-66c2-4501-aed1-66369726b63c]] to contain: SupersessionPair[supersededAt=null,
      supersededBy=null]"), green at `382629b` (RegisterStoreIT classes=14 tests=67,
      RegisterBatchRepositoryIT classes=8 tests=34, 0 failures each; whole suite classes=510
      tests=3153 failures=0 errors=0), with the rule in the port javadoc for both methods and in
      data-model.md, whose `superseded_by` invariant had described any row on the key.
      **And a report nobody could write was being reported as a context that would not start**:
      `19e52ff` / `b390eb6` gives the boundary's refusal its own type, `ReportNotWritten`, which
      `dispatch` lets past the catch that means the context could not be built, so a broken pipe
      (`startup.sh list-batches | head -1`, the everyday case) is answered on the failure code with
      one log line and no second write to the destination that just refused one - where before it
      was `reason=context-unavailable`, a throw inside the handler and the JVM's own exit 1 in place
      of the 2 the contract promises. Red at `19e52ff` ("40 tests completed, 6 failed", among them
      "expected: 2 but was: -1"), green at `b390eb6`, and `e2e/CliDispatchIT` proves the code
      through the built image against `/dev/full` in 35 s. **The refusal was then found being
      caught by the commands themselves**, one re-review later, and `f009bc5` / `2a2afb1` closes
      that: a report is written after the work is done, so the broad catch in
      `GenerateRegisterCli.generate`, `NotifyRegisterCli.resend`, `ListBatchesCli.listed` and
      `SupersedeBeforeCli.supersede` was turning a command that had already superseded the rows, or
      already re-requested the recipients, into a report that its own work had failed, with the true
      refusal arriving after it. Each now names the type ahead of that catch and rethrows it, the
      rule is stated once in `ReportNotWritten`'s javadoc, and `check-flag` needed no change because
      it holds no broad catch to get past. Red at `f009bc5` ("174 tests completed, 4 failed", among
      them "Expecting no elements of: [[ERROR] The registers shared before 2026-09-04T17:00:00Z
      could not be superseded, so this service still claims them. cause=...ReportNotWritten] to
      match given predicate but this element did"), green at `2a2afb1` (`batch.cli.*` classes=42
      tests=174 failures=0 errors=0, with notify-register's genuine store-outage ERROR lines still
      printed, which is the other half of the claim). That closes the follow-up `b390eb6`'s body
      left open rather than widening into.)
- [x] T066 [US5] `docker/startup.sh` dispatch: a recognised first argument runs `CliMain` with the
      remaining args; otherwise unchanged `exec java -jar`. Green: T064; `scripts/container-smoke.sh`
      gains `startup.sh check-flag` (exit 0 against the compose WireMock).
      (`ce238a5`: the five names are `CliMain.COMMANDS` and the two lists are one list; a recognised
      first argument is dispatched out of the fat jar through `PropertiesLauncher` and
      `-Dloader.main`, because a Boot 4 manifest names `JarLauncher` and `java -jar` cannot run a
      second main class out of the same archive; `exec`, so the container exits on the command's own
      code and an operator's Ctrl-C reaches the JVM; the one line the script prints goes to stderr,
      alone among its lines, so a runbook's grep of stdout is unaffected; and any first argument
      that was none of the five fell through to the unchanged `exec java -jar`. That commit records
      no run of its own - exception 3 below - so the verification is the smoke script's and T067's.
      **Only an empty argument list falls through now, and the task's "otherwise unchanged `exec
      java -jar`" above describes `ce238a5` rather than the delivered script.** At `674753b` (round
      2, the pair recorded under T067) the `case` gained the two arms it was missing: no arguments
      at all is the deployed pod - the only invocation the Dockerfile's `ENTRYPOINT` produces - and
      falls through to `exec java -jar`, while a **non-empty** first argument that is none of the
      five names is answered on stderr with `usage: startup.sh <command> [arguments]`, the five
      names, and exit 2. The two are distinguished because a mistyped name reaching the fall-through
      started a second whole application in the pod, dropped the operator's arguments, left
      `courtregister.cli` false and ended on 1 with none of the five names printed; 2 and not 1 for
      the reason the no-jar arm already gives, that the command could not be run rather than
      declined. Nothing about what was typed is echoed, as `CliMain`'s own usage does not echo it.
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
      inside the built image. Record. (**The two introduction cases are the [A] ones; the three
      added later were each driven from a failure first** - the mistyped name (`7e282ca`), stdout
      carrying the report alone (`fe4750d`) and, at the phase gate, the code a report nobody could
      write ends on (`19e52ff`, exit 1 rather than 2 through the built image against `/dev/full`).
      **first observed run: GREEN**, both cases, at `84ac9cc`:
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
rewrote it (`782b1e6`, which renumbered its steps and is the **last pre-review delivery commit** of
the phase rather than its last commit - forty-nine commits follow it, all of them review work or
the documentation corrections it produced). What that run recorded, against the stack the block
brings up:

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
of this phase the Build stage still owes.

**Three rounds of review followed `782b1e6`**, and the fixes each produced are recorded on the tick
lines above and in the exceptions block below rather than here.

- **Round 1**, `ba7670d` to `7d7008d`, 23 commits, this file's "review 7": both release statements
  re-driven for a released register the estate had already replaced (`ba7670d` / `6fb6fb3`,
  `8745144` / `7245d9c`), the narrowed regeneration's history and the batch it withholds
  (`261231e` / `c011bb1`, `b08a077` / `210695c`), `notify-register`'s report line and the exit codes
  taken from it (`879c9a3` / `f4b638a`), `--help` answered where the command is not wired
  (`bcd05e8` / `2e2d8ce`), where a batch with no court house sorts (`3c7e11e` / `fea8459`), both
  flag credentials' clients built through one factory (`50e5bad` / `dcbe21a`), and the
  characterisations `4601dfa` and `ed312b2` / `22a953a`, with `983c32d`, `7d7008d`, `c4c2da5` and
  `b2689f1` beside them.
- **Round 2**, `ce76e21` to `88ec762`, 18 commits, "review 8": the supersession bound to a register
  shared after the one being released (`ce76e21` / `6c8334a`), the order a day's batches are
  answered in (`fdaf331`), the summary line a day is answered with (`9a96898`), a flag-off refusal
  logged as the decline it is (`2bbda7d` / `8805d47`), `notify-register`'s usage promise
  (`67a6aa8` / `f1b5c9b`), a `courtregister.feature.endpoint` no App Configuration client can be
  built from (`507263d` / `c22509c`), a mistyped command name answered with the five names
  (`7e282ca` / `674753b`), the artefact the image is built from made unambiguous (`b544003`), and a
  command's report given stdout to itself (`fe4750d` / `294d93e`), with `58769d2` and `e2ee872`
  beside them; `88ec762` is where this record was brought level with the two rounds.
- **The phase gate**, `1609e80` to `980230d`, 17 commits over four re-reviews: six red/green
  pairs, one refactor/characterisation pair and three documentation commits. The pairs are the
  successor's ordering rule over `(register_time, created_at, output_id)` on both release
  statements (`1609e80` / `3fae1a2`, T065), an operator's own typing swept out of a command's log
  (`9e02bd7` / `c0bf9bb`, T065), the flag store's endpoint read by parsing rather than by its shape
  (`35d3277` / `5bf982f`, outside Phase 7's own tasks), only a register admitted as the register
  that replaced one, where 001's POST rows on the same key had been (`605e6a2` / `382629b`, T065),
  a report the destination refused told apart from a context that would not start
  (`19e52ff` / `b390eb6`, T065 and T067), and that refusal let past each command's own broad catch
  (`f009bc5` / `2a2afb1`, T065). The seventh pair is `07e325b` / `baa0c32`, the report's
  destination behind one boundary, which is an extraction pinned before and after rather than
  driven red and is exception 9 below, so it is named here rather than counted with the six. The
  three documentation commits are where this record was brought level with the gate as it went:
  `f199fd1` after its first eight commits, `d610df1` after the second re-review, `980230d` after
  the third, and the fourth is the commit carrying this line. Two exceptions come with them all,
  8 and 9 below.

### Approved TDD exceptions (Phase 7)

Nine, all approved - 1 to 5 as the phase was built, 6 and 7 at review round 2, 8 and 9 at the phase
gate. They are recorded here rather than argued for in a commit body, and each entry states what
was asked of the design owner, the evidence that stood in place of a red run, and the rework that
was available had approval been withheld. Approver for 1 to 5: **design owner, 2026-09-07**; for 6
to 9: **design owner, 2026-09-08**. Anything else in Phase 7 that arrived test-after is a defect,
not a precedent.

Three things in the phase were judged against this list and are deliberately not on it. `c3d8ff7`
(T064) is a test task with a recorded red run, and the ten cases of it that were green on
introduction characterise a property the service already had, with no implementation following them.
`84ac9cc` (T067) is an **[A]** acceptance task, which the preamble already exempts from a red run;
its observed run and its two reverted mutations are recorded in its tick line above rather than here.
`f009bc5`'s fifth case is the same shape as `c3d8ff7`'s ten: a test commit with four failing
assertions, whose one green-on-introduction case says that `check-flag` reaches the caller with a
refused report because it holds no broad catch, stated so that a `catch` added there later cannot
take the property away. No implementation follows it and its passing run is in that commit's body.

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
   - **`4601dfa`** is round 1's and was not written down when it landed: it characterises how
     `PropertiesValidator` recognised a real flag store - the authority only, on a trimmed and
     lower-cased value, unconditionally on the master switch - where one canonical endpoint had
     stood for all of it. Green on introduction, 128 tests 0 failures, with four mutations quoted in
     its body. The `REAL_FLAG_STORE` pattern it characterised no longer exists: `5bf982f` (the
     phase gate) replaced it with a `java.net.URI` parse and `namesARealFlagStore`, which keeps the
     authority-only, normalised, unconditional reading those cases state and adds the absolute-DNS
     and upper-case spellings to them.
   All three are the shape the Phase 3, 5 and 6 blocks record for the same thing: behaviour that
   already existed, stated by cases that pass on introduction, with non-vacuity shown by mutation
   because there is no red run to show.
   **What the design owner is being asked** is to accept three test-only **[A]** characterisations,
   with reverted mutations standing in for the red run that is not available, as the record of
   behaviour Phase 7 already had. **If approval is withheld**, each is reworked into a compliant
   red/green pair: the order `BATCHES_ON_DAY` answers a day in, the summary line's fields and the
   validator's recognition of a store are taken back out and driven in from a failing assertion -
   which the mutations quoted above already show is available in every case.
   **Approved: design owner, 2026-09-08**, on the ground the entry states: all three describe
   behaviour the service already had, no production code moved in any of them, and the reverted
   mutations show each case would catch the behaviour changing. Reworking them would mean taking
   correct code out to put it back unchanged.
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
   **What the design owner is being asked** is to accept that empirical evidence in place of a red
   run for a commit that is one shell step and one suite's own fixture. **If approval is withheld**,
   it is reworked into a compliant pair: the refusal is driven from a `CliDispatchIT` case that
   fails against a `build/libs` holding two jars - the run quoted above, landed as a test commit -
   with `packagedJar`'s check and the script's clearing step following it.
   **Approved: design owner, 2026-09-08**, on the ground the entry states: one half is a shell
   step and the other the suite's own fixture, so no failing assertion was available to drive it,
   and the empirical run above stands in place of one. The packaged artefact is unchanged.
8. **Two mirror [A] characterisations inside `1609e80`, from the phase gate.** That commit records a
   red run - two of its four cases fail against both release statements as they stood - and the two
   that do not are the same arrangements with the two identities the other way round:
   `Failure.a_failure_should_supersede_against_an_equal_time_re_share_that_sorts_last` and
   `Releasing.a_release_should_supersede_against_an_equal_time_re_share_that_sorts_last`, labelled
   **[A]** in their own javadoc.
   **Why no red run was recorded**: the identity tie-break the old order fell back on happened to
   agree with the recorder in that direction, so the behaviour already held and both cases were
   green on introduction, with no implementation commit following them. They are written because one
   direction on its own is also satisfied by a search that ranks a key's rows by identity and
   nothing else.
   **Non-vacuity was shown by mutation instead**, applied to both statements together, reverted
   before the commit and quoted in its body: the direction test reading `<` rather than `>`, so the
   search looks for a predecessor - "65 tests completed, 7 failed", both mirror cases on the same
   shapes as their pairs ("Expecting Optional to contain: c9217314-7771-4bb5-b21e-f4340b986cd5 but
   was empty.", "Expecting actual: [\"RECORDED\", \"RECORDED\"]") plus the three existing direction
   cases.
   This is `c3d8ff7`'s shape, which the paragraph above records as deliberately not on this list. It
   is written down anyway because what these two cases characterise is a **SQL statement**, and the
   preamble requires an integration red run for every change to one; the two directions of an
   equal-instant pair are one rule, and half of it arrived without a failing assertion.
   **What the design owner is being asked** is whether an [A] case may state the direction of a
   statement's rule that already held, inside the commit that drives the other direction red.
   **If approval is withheld**, the pair is reworked into a compliant one: the ranking is taken out
   of both statements so that neither direction holds, and both are driven back in from the
   DuplicateKeyException the mutation above already produces on the mirror cases.
   **Approved: design owner, 2026-09-08**: an [A] case may state the direction of a statement's
   rule that already held, inside the commit that drives the other direction red. The two
   directions are one rule, the red half is in the same commit, and the reverted mutation shows the
   mirror cases would fail if the rule were removed.
9. **`07e325b` / `baa0c32` extracted the report's destination and characterised it afterwards.**
   `batch/cli/StandardOutput` was taken out of the `System.out::println` that `CliMain.main` handed
   the dispatch - the token constitution Principle VI forbids in production code and tests alike -
   and `batch/cli/StandardOutputTest` (five cases, labelled **[A]**, green on introduction) followed
   in `baa0c32`, together with `CliMainTest`'s "where the lines go" group moved off `System.setOut`
   onto a second `StandardOutput` handed to nobody.
   **Why no red run was recorded**: `07e325b` claims Principle II's extract-with-no-behaviour-change
   exemption and records the characterisation the suites already held in place of one - pinned
   before the change (`batch.cli.*` classes=35 tests=159 failures=0 errors=0, and
   `e2e/CliDispatchIT` 4 tests 0 failures, including the case that asserts the container's stdout
   is exactly `flag=ON`) and taken again after it over the jar the image copies, so the real
   descriptor is proved by the container rather than by a JUnit case.
   **What is not a pure extraction, and is why this is written down**: three properties of the
   boundary are deliberate narrowings of what the static stream did, named in that commit's body -
   UTF-8 rather than the JVM's console encoding, one `\n` rather than the platform separator, and a
   line that cannot be written wrapped in `UncheckedIOException` rather than swallowed into an error
   flag nobody reads. No case failed before them, so that much of the pair is behaviour that arrived
   test-after rather than behaviour that already existed.
   **Non-vacuity was shown by mutation instead**, two of them, both reverted before `baa0c32` and
   quoted in its body: `lines.flush()` dropped and US-ASCII encoded together - "35 tests completed,
   6 failed", all five new cases and the moved one, "expected: \"flag=ON\ndate=2026-09-07
   released=0\n\" but was: \"\"" and, on the write-failure case, "Expecting actual not to be null",
   because a write held in an encoder's buffer never reaches the stream that would refuse it; and
   the encoding on its own, flush restored - "5 tests completed, 1 failed",
   `a_line_should_be_encoded_as_utf_8_rather_than_in_whatever_the_pod_reads_as_its_locale` on
   "expected: [... 70, -61, -76, 110, 10] but was: [... 70, 63, 110, 10]", byte 63 being the `?` a
   pod with no locale prints through a court house's name.
   **What the design owner is being asked** is to accept a pin taken before and after the change,
   with those mutations, in place of a red run for an extraction that narrowed three properties on
   its way out. **If approval is withheld**, the pair is reworked into a compliant one: the three
   narrowings come back out of `StandardOutput` and are driven in from the failing assertions the
   mutations above already show are available, which leaves `07e325b` the pure extraction it
   claims to be.
   **Approved: design owner, 2026-09-08**, the pin taken before and after the change accepted with
   those two mutations in place of a red run. The rework was judged the most valuable of the four
   available and was still not required: what it would add is a failing assertion behind the
   encoding, and the UTF-8 case named above holds that property either way. The third narrowing
   has since been driven red on its own account by `19e52ff` / `b390eb6`, which gave the refusal
   its own type, and by `f009bc5` / `2a2afb1`, which stopped the commands answering for it.

**The five store statements T065 held back are the Phase 6 rule working rather than an exception to
it**: `batchesOn`, `releaseFailed`, `recordedWhileOff`, `supersedeSharedBefore` and
`findByRegisterDate` were left as seams instead of landing untested, and each got its integration
red run against a real Postgres (`RegisterStoreIT`, `RegisterBatchRepositoryIT`, `903d33b`) before
`e43cca3` implemented it - and both statements that were later found defective were re-driven the
same way, `ba7670d` / `6fb6fb3`, `8745144` / `7245d9c`, `ce76e21` / `6c8334a` and, at the phase
gate, `1609e80` / `3fae1a2`.

**Review 8's other fixes are red/green pairs and are recorded on the tick lines they belong to**,
not here: the successor guard on both release statements (T065), the summary line and the flag-off
log sentence (T062), `notify-register`'s usage promise (T063), the entrypoint's answer to a mistyped
name and stdout carrying the report alone (T067), and one outside Phase 7's own tasks -
`507263d` / `c22509c`, which refuses a `courtregister.feature.endpoint` no App Configuration client
can be built from, under the setting's own name rather than as an Azure `IllegalArgumentException`
during refresh. Four documentation-only corrections landed with them (`58769d2`, `e2ee872`,
`fdaf331`'s port contract, and this file), each named in its own commit body.

**The phase gate's fixes are recorded the same way**: three of its four pairs on the tick lines
above - the successor's ordering rule and the two changes to the commands' own code, all three
T065's - and the fourth outside Phase 7's own tasks, as `507263d` / `c22509c` was.
`35d3277` / `5bf982f` replaces both endpoint patterns in `config/PropertiesValidator` with a
`java.net.URI` parse: `asEndpointUri` parses the trimmed value and requires a host, which is also
what requires a port that parses; `hostOf` lower-cases it and strips the root label's trailing dot
before `namesARealFlagStore` asks whether it ends `.azconfig.io`; and `requireAFlagStoreUrl`
additionally requires an http or https scheme. Red at `35d3277` ("137 tests completed, 5 failed",
each on "Expecting: <Started application [AnnotationConfigApplicationContext@...]> to have failed
but context started successfully") over three absolute-DNS spellings of a real store -
`https://courtregister-ste86.azconfig.io./`, `https://COURTREGISTER-STE86.AZCONFIG.IO.` and
`https://courtregister-ste86.azconfig.io.:443/kv` - and two endpoints naming no host,
`http://foo:bad` and `http://:`, which `java.net.URI` parses as a registry authority with a null
host. Green at `5bf982f` (`ConfigurationValidationTest` classes=20 tests=137, and `*config.*` with
`*appconfig.*` classes=75 tests=374, 0 failures and 0 errors, so every generation-enabled context
still starts). One deliberate narrowing is named in the fix's body: an authority `java.net.URI`
cannot read a host out of - an underscore in a hostname being the realistic case - is now refused
where the pattern admitted it, and nothing in `application.yaml`, `docker/`, `scripts/` or the
suites uses such a host. `c22509c`'s `FLAG_STORE_URL` and the older `REAL_FLAG_STORE` no longer
exist; every requirement they carried does, and the endpoint refusal's wording changed with them.

---

## Phase 8: User Stories 6 and 7 - register, audit, observability (Priority: P2)

- [x] T068 [P] [US6] `doc/DEFECT-FIXES.md` - confirm P1-P5, P9 and the appended P10 FIXED with
      their pinning tests named verbatim; P3/P4/P10 sign-off markers; P6/P7 RETIRED with the
      retirement-PR pointer; P8 MOOT; header counts updated (36 C rows + 10 P rows).
      (`5d1520a`. Documentation only, so no red run was available and none is owed - the footing
      Phase 7's own documentation commits were recorded on - and the runs are this task's evidence,
      read rather than trusted. Every pinning test the rows name was run by name: "BUILD SUCCESSFUL
      - 454 cases across the ten classes, 0 failures, 0 errors, 0 skipped", over
      `RegisterNotifierServiceTest`, `DocumentOutcomeSinkTest`, `GenerationReconcilerTest`,
      `RecipientSetTest`, `RegisterGenerationServiceTest`, `ConfigurationValidationTest`,
      `DefendantTypeResolverTest`, `RegisterStoreIT`, `SchemaMigrationV2IT` and
      `SchemaMigrationV3IT`. Pre-commit read, quoted in that body: "./gradlew -q compileJava
      compileTestJava checkstyleMain checkstyleTest pmdMain pmdTest / (no output; exit 0)".
      **The counts were already right and this task's own text was the stale side of the
      discrepancy**: the table holds thirty-six C rows and ten P rows, counted, and the header said
      so. "9 P rows" above was written before P10 was appended under review at `523cab5`, and it is
      corrected here - the correction the register asked for and could not make, only the stage
      whose task says so being permitted to edit this file.
      **No row moved status and no sign-off marker was softened.** P1-P5, P9 and P10 stay FIXED, P6
      and P7 RETIRED against progression's retirement PR (design §10.6), P8 MOOT; P3, P4 and P10
      keep their markers verbatim and no content-affecting change was found carrying none.
      **Three rows were stale and are corrected.** P3's absolute that a stamped row is "never
      touched by another batch or by supersession" now states the exception Phase 6 and 7 gave it:
      the two failure reasons that say the batch never left this service, and the operator's
      release, hand the stamp back, and where the estate re-shared the hearing in flight the release
      unstamps the row and only then supersedes it against its successor, because unstamping alone
      would collide with `idx_output_active_register_key` and take the whole mark down with it. P8
      named the V2 pair as though it were the whole of the store's indexing, and now names V3's
      partial unique `idx_output_active_register_key` and `SchemaMigrationV3IT`; it is an invariant
      rather than a read path, so the disposition stays MOOT. P10 had two wrong claims about the
      recorded goldens: all six base fixtures carry the absent-respondents shape rather than five
      of six, and `defendantType` is empty in the three base fixtures that produce a document at all
      (the other three recorded skipped with no document to type) because `getCourtApplicationId`
      reads element zero of `prosecutionCasesOrApplications`, always the prosecution case, rather
      than because none of them names a court application - every one does.
      Three halves delivered after their row was written and named by class or not at all are now
      named by case: P2's GENERATION_TIMED_OUT, which fails the batch through the store rather than
      the sink because a batch nothing can be learned about has no outcome to apply; P4's one
      `register_notification` row per address, which `RecipientSetTest` cannot prove; and P9's
      run-time half. P9 also gained a sentence the row did not have - a refusal that may answer
      differently is asked again under the same identity inside the shared attempt budget before any
      row is failed, and the row keeps the last status that came back - judged a strengthening of
      P9's own claim rather than a departure from it, so it earns no row of its own; if the review
      gate disagrees it needs a P-number. Every commit hash the P rows cite resolves under
      `git cat-file -e`, `5b424c1` included, whose unreachability from any branch is what P3 claims
      and is a different claim from absence; and P3's unescaped pipe inside a code span is escaped,
      so the table stops rendering an eighth column.)
- [x] T069 [P] [US6] `differential/DifferentialAuditTest` (extend) - the 001 document corpus is
      unchanged by 002 (digest equality), and every `PdfPayloadMapper` golden is reproduced; the
      `RegisteredDefectFixes` table gains the P numbers **a comparison of answers can carry, which
      is P10 alone** (this task said "the P numbers" until the tick below explained the singular).
      (`1fe0285`, an **[A]** characterisation: six cases, all green on introduction, with no
      implementation commit following. Before it the audit asserted one thing only - that each of
      381 recorded 001 legacy runs, put through the real chain, differs from its recording only
      where a C row of `doc/DEFECT-FIXES.md` claims the difference (383 cases with the corpus-size
      pin and the citation check) - and it never read the progression goldens at all, nor said that
      the recordings it compares against are the bytes T004 recorded.
      **Observed run, GREEN on introduction** (the preamble exempts an [A] task from a red run
      and asks for the initial observed result instead): `./gradlew test --tests
      '*DifferentialAuditTest' --tests '*RegisteredDefectFixesRejectionTest' --tests
      '*PdfPayloadMapperTest' --tests '*DefendantTypeResolverTest' --rerun-tasks` - "BUILD
      SUCCESSFUL, 619 tests, 0 failures, 0 errors, 0 skipped", with `DifferentialAuditTest` alone at
      389 tests and all six cases named PASSED in that body. The recomputed manifest digest is
      `20fcb12324bf674d3b141b4fa822076f2ff56be1aad43531000d575aa649d924`, which is `INDEX.json`'s
      own `corpusDigest`; all 177 goldens digest to their recorded `outputSha256`; all 168 payload
      goldens reproduce from their recorded inputs as one digest over the set; and the 52 recorded
      refusals are refused here too, with the refusal text deliberately not compared because it is a
      `javax.json` message on one side and a Jackson one on the other.
      **Non-vacuity by four reverted mutations, applied one at a time and quoted in that body**:
      `PdfPayloadMapper.DASH` "-" to "~" fails the goldens leg naming all 168 (389 tests, 1 failed,
      so only that leg broke); one byte of a recorded input fails three cases, each naming its own
      thing, which is the per-file pass earning its place; `DefendantTypeResolver.APPLICANT_TYPE`
      "Applicant" to "Applicants" gives "[the two shapes progression's rule cannot read are the
      whole of what this port answers differently] Expected size: 2 but was: 4"; and the P10
      predicate's own required answer changed to "Appellant" gives "Expected size: 1 but was: 0", so
      the attribution refuses and not only the count.
      **Only P10 could be registered honestly, and that is a finding rather than an omission.** P1
      to P5, P8 and P9 are batch, store and notifier rows whose fixes are batch states, marks,
      unions or startup refusals rather than values in a document or answers from a rule, which is
      why each names its own pinning test elsewhere; P8 is MOOT and P6 and P7 are RETIRED in
      progression's own tree with nothing here to pin. `RegisteredDefectFixes` gains
      `progressionLegRows()` and a `ProgressionRow` table read exactly as the C claims are, and the
      citation check that refuses an unregistered deviation is widened from `^(C\d+) ` to
      `^([CP]\d+) `. The audit's summary now carries the P row beside the C rows: "P10
      (defendant-type resolution throws on permitted shapes) - 2 actual difference(s)". The empty
      string and no answer at all are mapped to each other rather than reported, because that is
      what `DefendantTypeResolverTest` already states in those words.)
- [x] T070 [P] [US7] `config/TelemetryPrivacyTest` (extend) - recipient e-mail addresses, recipient
      names and `sdg_reason` free text never at INFO or above; batch and notification ids are.
      (`81d2b87`, an **[A]** characterisation, and **no leak was found**: the downstream leg is
      already hardened against all three values, deliberately and with the reasoning written down,
      so the commit records a passing run rather than a red one. A ninth group, "a register
      generated, and the teams told about it", drives the whole downstream leg over a batch and a
      recipient made of markers, and holds `sdg_reason` to the graded rule - forbidden at INFO and
      above and in every label, asserted present at DEBUG, where two statements deliberately keep
      it. **Passing run**: "`TelemetryPrivacyTest` classes=7 tests=32 failures=0 errors=0; whole
      suite classes=517 tests=3182 skipped=0 failures=0 errors=0".
      Coverage is by construction rather than by a list: all 62 log statements in the nine classes
      that write one are enumerated out of the sources by `support/LogStatement`, and one case
      insists the drive reached every one of them, so a statement added later is a failing test
      rather than a silent gap. The meters are enumerated the same way off `GenerationMetrics`' own
      name constants, with every label value held to a bounded vocabulary derived from that class
      and the enumerations it codes. `FileServicePayloadStore` is in the list for the opposite
      reason and is asserted to write no line at all.
      **Non-vacuity by two reverted mutations, quoted in that body**: `RegisterNotifierService`'s
      "was not accepted" WARN given ` address={}` fails one case and only that one - "[a recipient's
      e-mail address reached the log index, and the index is read by the whole estate] Expecting no
      elements of: [...] to match given predicate but this element did" - and
      `GenerationReconciler`'s `sdg_reason` line raised from DEBUG to INFO fails the graded case and
      only that one, on "[the generator's reason is free text about a document whose every defendant
      is a child; it goes to sdg_reason, not to the index]".
      **The positive half is a batch id, a notification id and a bounded reason code**, and not a
      court centre id: nothing in either leg writes `courtCentreId`, `courtCentreOuCode` or
      `courtHouse` to any line at any level, and `GenerationMetrics`' own javadoc forbids a court
      centre id as a label, so the case pins `BatchFailureReason.RENDER_REQUEST_FAILED` rather than
      claim a reading nothing takes. **`courtregister_generation_latency` is declared and recorded
      by nothing** - no production code calls `GenerationMetrics.generationLatency` - so the suite
      asserts the unmoved set rather than exempt the meter from the scan, and whoever wires the
      timer up is told by it to fold the timer into the drive. It carries no label, so it is an
      alerting gap and not a privacy one, and it is carried in the open items below. Two shared
      test-support files changed, each for one reason stated in that body: `CapturedLog` gained a
      public `rendering(event)` and `PersonalDataMarkers` gained `GENERATOR_REASON`.)
- [x] T071 [P] [US7] `e2e/ReadinessPolicyIT` (extend) - broker down: ready; file-service DB down
      outside a run: ready; during a run: not ready.
      (**The second claim was false against the service when the cases were written, and the code
      was what was wrong.** All three were written under this task and not committed, because the
      fix was a production-wiring decision the test stage would not take on its own and a
      `test(readiness)` commit carrying a red suite would have left the branch failing
      `./gradlew build`. They landed as a pair once the decision was made: `28a2fd5`
      "test(readiness): pin the three outages that may and may not roll a pod" then `1da7125`
      "fix(health): db means the register store, not every pool on the context".
      **The failing assertion at `28a2fd5`**, on
      `should_keep_readiness_up_while_the_file_service_database_is_down_outside_a_run`, after the
      case was sharpened to name the component that objected rather than time out: "[readiness is
      about the work this pod is being sent, and at 09:00 that is intake: a database nothing will
      touch until 18:00 must not roll a pod whose intake half is recording registers perfectly
      well. The components say who objected: {db=DOWN, fileServiceRun=UP, intakeStartup=UP}]
      expected: UP but was: DOWN" - 8 tests completed, 1 failed. `fileServiceRun` decided correctly
      throughout, at `run=idle, fileservice=not-probed`.
      **The defect**: `DataSourceHealthContributorAutoConfiguration` collects the context's
      datasource beans and, finding two on a generation-enabled pod, contributed `db` as a composite
      over both, so the platform file service being unreachable reported `db` DOWN at any hour and
      rolled the intake half, and every health poll asked another team's database for a connection.
      Both are harms FR-011 and `FileServiceRunHealthIndicator` exist to prevent, and the service
      said so in three places while doing the opposite.
      **The fix is option (c) of the three the open items listed**: the auto-configured contributor
      is switched off in `application.yaml` and `config/StoreHealth` contributes `db` over the
      primary pool alone. (a) was rejected because `autowire-candidate` would hide the pool from the
      two qualified injections that are the only intended way to reach it, and (b) because it would
      hand this service the pool's lifecycle for a health-naming problem. The component is
      unconditional and resolves its pool per probe, both deliberately: Spring validates
      health-group membership at startup, which `HttpSurfaceTest`'s generating context demonstrated
      while the fix was being written, and a `@ConditionalOnBean` in an ordinary configuration is
      evaluated before the auto-configuration that defines the pool, so it answers "no pool" on a
      pod that has one. No pool answers DOWN, the judgement `GenerationHealth` already records for
      the file service.
      **Green at `1da7125`**: `./gradlew test --tests '*ReadinessPolicyIT'` 8 tests, 0 failures,
      `{db=UP, fileServiceRun=UP, intakeStartup=UP}` throughout a file-service outage with no run
      on; `./gradlew build` green, 3200 tests, 0 failures. The third case is no longer confounded -
      readiness goes DOWN during a run because `fileServiceRun` says so and `db` stays UP - and
      `GenerationHealth.probe`'s javadoc now says what actually keeps `db` named `db`.
      The outage is staged by `PostgresTestSupport.refuseConnectionsTo`, which is
      `ALTER DATABASE ... ALLOW_CONNECTIONS false` plus `pg_terminate_backend`: the shared container
      holds the file-service database beside the processed log, so a freeze would take readiness
      DOWN through `db` and the case would assert the opposite of what it claims. No deployment
      manifest ships in this repository, so the sustained-outage window is not grounded in a real
      `failureThreshold` times `periodSeconds`, and the constant's javadoc says so.)
- [x] T072 [US7] Run report: one structured log line per run (`event=register_generation_run`) with
      the `RunReport` fields; gauges published; documented in the metrics section of the Confluence
      page (the note for the page owner is recorded below rather than in a PR description, the
      branch having no PR yet).
      (Three commits, and **most of the line predates this task**: `6d7aca8` gave every run,
      skipped ones included, one INFO line carrying all five `RunReport` components, `eaf1413` added
      `deferred`, and the three gauges the design names were published by the run and already pinned
      by name and label in `GenerationMetricsTest`. What the task still owed was that almost none of
      the line was held down - one case looked for "deferred=1" and nothing pinned the other nine
      fields, the skipped night's line or the arithmetic - and that a run which stopped part way
      wrote no line at all.
      **`23e4daf`**, an **[A]** characterisation of the delivered line: seven cases in a new
      [A]-labelled `TheLine`, all green on introduction, with no implementation commit following.
      Observed run: "./gradlew test --tests '*RegisterGenerationJobTest' BUILD SUCCESSFUL, 39 tests,
      0 failures, 0 errors". Non-vacuity by five mutations, each run with `-Dtest.noFailFast=true`
      and reverted before the commit: "pending={}" renamed "unrequested={}" (39 tests, 4 failed, the
      arithmetic case on "expected: 1 but was: 3"); the duration printed with `toSeconds()` under
      the `duration_ms` name ("...reconciled=4 duration_ms=180" against "...duration_ms=180000");
      `record(report)` moved inside the branch that generated, so only a run that went ahead leaves
      a line ("Expecting actual: [] to contain exactly [event=register_generation_run gate=skipped
      reason=flag-off batches=0 generating=0 failed=0 pending=0 deferred=0 reconciled=0
      duration_ms=0]"); `metrics.oldestRecordedUnbatchedAge(...)` dropped ("expected: 93600.0 but
      was: 0.0"); and `reasonOf` answering FLAG_ON for every Proceed ('to contain entries:
      ["reason"="overridden"]' against a line reading "reason"="flag-on").
      **`0aa2cc5` is the red half** for the night that stopped part way: "./gradlew test --tests
      '*RegisterGenerationJobTest' -Dtest.noFailFast=true, 45 tests completed, 4 failed" -
      `a_run_that_stopped_before_it_read_anything_should_still_leave_its_one_line` and
      `a_run_that_stopped_part_way_should_leave_the_line_the_night_had_got_to` each on "Expecting
      actual: [] to contain exactly (and in same order): [event=register_generation_run gate=proceed
      reason=flag-on ...] but could not find the following elements: [the same]",
      `a_run_that_stopped_part_way_should_name_what_stopped_it_in_a_line_of_its_own` on "Expecting
      any elements of: [] to match given predicate but none did.", and
      `a_run_that_stopped_while_requesting_should_still_gauge_what_it_had_assembled` on "expected:
      2.0 but was: 0.0". Two cases of that commit are green on introduction, labelled **[A]** in
      their own javadoc with no implementation following, and they bound the fix rather than drive
      it: the failure still leaves the run, so the reporting cannot be built by swallowing what it
      reports (Principle VI); and a run that learned nothing publishes nothing, so the gauge case
      cannot be satisfied by publishing zeroes the run has not earned.
      **`4021608` is the green half**: "./gradlew test --tests '*RegisterGenerationJobTest' BUILD
      SUCCESSFUL, 45 tests, 0 failures, 0 errors - the four red assertions quoted in 0aa2cc5 now
      pass, and the two [A] cases beside them still do". The counts accumulate in a private
      `RunTally` as the run earns them, `run()` writes the line wherever the run turns out to end,
      and the failure path adds an ERROR naming the class of what stopped it before rethrowing:
      reported and rethrown, because a failure only logged about has not been settled and both the
      schedule and the operations command decide what to do next from the throw. T070's completeness
      claim caught the new ERROR immediately - "[a statement the drive never reached is a statement
      outside every claim above] Expecting empty but was: [\"RegisterGenerationJob.java:234\"]" - so
      `GenerationLegs.theNightlyRun` gained `aNightThatStoppedPartWay` and the new line sits inside
      the sweep that holds every line to counts and bounded codes. Whole build: "BUILD SUCCESSFUL in
      7m 22s, 3195 tests, 0 failures, 0 errors, 0 skipped, PMD and Checkstyle clean".
      Checked as the task asked: a run that fails part way now reports; the counts add up, since
      `RegisterGenerationService` can only answer PENDING, GENERATING or FAILED and the case asserts
      `batches` equals both the assembler's answer and the three named counts summed, so a fourth
      state reaching the report shows up as a total that no longer adds up; nothing on the line is
      free text or anything a register carries, matched against an
      event/gate/reason/nine-numeric-fields pattern where `reason` is `[a-z-]+` only; and the run
      publishes exactly `OLDEST_RECORDED_UNBATCHED_AGE`, `DEFERRED_KEYS` and
      `PENDING_AFTER_DEADLINE`, read through the `GenerationMetrics` constants. Gates read green
      before each of the three commits: "./gradlew -q compileJava compileTestJava checkstyleMain
      checkstyleTest pmdMain pmdTest exit 0, no output"; one intermediate read was red on 12 PMD
      violations in the new nested class and was re-read green before the commit.)

**The note the metrics section of the Confluence page needs (T072's third clause).** It is written
down here rather than left in a PR description, because the branch has no PR yet and a note that
lives only in a workflow report has not been handed over. The page owner pastes it; nothing in this
repository edits that page, and T074 carries the handover.

- **The run report is two things, not one**: a single structured log line and three gauges. Both are
  emitted for every run, including a run the flag stopped and a run that failed part way.
- **The line**, one at INFO per run, from `batch/RegisterGenerationJob`, fields in this order.
  `event`, always `register_generation_run`, which is what an index filter or an alert query keys
  on. `gate`, `proceed` or `skipped`. `reason`, one of four bounded codes: `flag-on`, `overridden`
  (an operator overrode a flag that had not said ON), `flag-off`, `flag-unreadable` (the flag could
  not be read and the run failed closed). `batches`, the total the run accounted for, always equal
  to `generating` + `failed` + `pending`. `generating`, batches whose render the generator accepted.
  `failed`, batches the requesting leg failed. `pending`, batches left for the next run, the
  deadline having run out or the batch not having stamped. `deferred`, court centre days the
  assembler passed over because a batch of theirs is still in flight - they produce no batch and no
  document tonight and appear in none of the counts above, which is why the field exists.
  `reconciled`, outcomes the grace-period reconciler had to fetch rather than receive, so a
  sustained non-zero reading is the event subscription to investigate and not the renderer.
  `duration_ms`, measured on the run's own clock against the configured run deadline.
- Every value on the line is a count, a duration or a bounded code. No court centre, batch,
  recipient, defendant or generator reason text is ever on it (constitution Principle VII).
- **A run that stops part way writes the same line with what it had done when it stopped**, and a
  separate ERROR beside it naming the class of what stopped it; the failure is then rethrown, so the
  schedule and the operations command still see it. A line showing small counts alongside such an
  ERROR is a night that was cut short, not a quiet night. `courtregister_pending_after_deadline`
  from such a run reads what the run had counted at that point rather than a finished count:
  deliberate, because it is at least about tonight rather than stale from yesterday.
- **The three gauges the run publishes**, once at the end of every run that got as far as
  assembling, none of them labelled. `courtregister_oldest_recorded_unbatched_age`, seconds the
  oldest register still waiting to be batched has waited, which is the reading that says a night was
  missed, because a register that is never batched moves no counter.
  `courtregister_deferred_keys`, court centre days the run passed over, the companion of the age
  gauge: that says how long the worst has waited, this says how much of the estate is waiting.
  `courtregister_pending_after_deadline`, batches the run ended without asking the renderer for,
  which is the reading that says the night is no longer finishing inside its hour - the one failure
  a nightly flow can have repeatedly without anything ever failing. A run that stopped before it
  assembled publishes none of them, so the previous run's reading stands rather than being
  overwritten with a zero it did not earn.
- The rest of the generation surface is named by its own constants in `config/GenerationMetrics` and
  pinned by `config/GenerationMetricsTest`, which is the list to read the section against: four more
  unlabelled gauges (`oldest_generating_age`, `oldest_pending_age`, `oldest_generated_age`,
  `flag_read_ok`), the counters `batches_total{outcome}`,
  `generation_request_total{response_code}`, `generation_reconciled_total`,
  `generation_skipped_total{reason}`, `notifications_total{status,response_code}`,
  `notifications_ignored_total{reason}` and `public_events_ignored_total{reason}`, and the
  `generation_latency` timer. Every label value is drawn from a bounded enumeration; no batch id,
  court centre id or recipient address is ever a label, which is both a cardinality explosion and,
  on a register whose every defendant is a youth, a privacy breach. `generation_latency` is declared
  and recorded by nothing, so it has no series to document yet (open item 10 below).

**Checkpoint**: review gate 8. **What Phase 8 verified, and by which run**: `./gradlew build` green
on the branch at `4021608` - "BUILD SUCCESSFUL in 7m 22s, 3195 tests, 0 failures, 0 errors, 0
skipped, PMD and Checkstyle clean" - with the six gates read green before each of the phase's seven
commits. The register's ten P rows were confirmed against 454 cases run by name (T068, `5d1520a`);
both oracles are now held to their recordings on every build, the 001 corpus by manifest digest and
all 177 goldens by the `outputSha256` they were recorded under (T069, `1fe0285`); the batch and
notification legs are held to the privacy rule over all 62 log statements they can write and every
meter they can move, with no leak found (T070, `81d2b87`); and the run report's line, its ten
fields, its arithmetic and its three gauges are pinned, including the night that stops part way
(T072, `23e4daf` / `0aa2cc5` / `4021608`).

**T071 found a defect before it could be ticked, and both are now done.** The readiness policy's
second claim was false against the service: on a generation-enabled pod an unreachable file service
took readiness DOWN outside a run, through `db`'s composite over both pools, and rolled the intake
half over a database nothing would touch until 18:00. It landed as a pair, `28a2fd5` then
`1da7125`, whose evidence is on T071's line above; open items 1 to 5 below are closed by it and say
so. **Two of T070's findings were closed the same way**, `667b9e8` then `7332149`: both readers of
an optional field on a public event attached the parser's exception to a WARN, and those messages
quote the value the field held, which is another context's and did not parse. That is open item 7,
and it is the defect the phase gate fixed once already in `CliMain.unreadable`, one door along.

**One phase-gate finding of Phase 7's was closed here**, `29cbc9d` "test(store): state the
successors a release may pick past RECORDED". Both release statements admit `RECORDED`, `GENERATED`
and `NOTIFIED` as the states this store leaves a live register in, and no case reached past the
first of the three: with both predicates cut to `IN ('RECORDED')` the whole of `RegisterStoreIT`
stayed green. Two **[A]** characterisations close that, one per statement, each labelled [A] in its
own javadoc and green on introduction - "RegisterStoreIT classes=14 tests=69 failures=0 errors=0",
up from 67 - with non-vacuity shown by that same narrowing, applied to both statements together and
reverted before the commit: "69 tests completed, 2 failed", exactly those two and nothing else, the
release case on "Expecting actual: [\"RECORDED\", \"GENERATED\"] to contain exactly in any order:
[\"SUPERSEDED\", \"GENERATED\"]" and the failure case on the `NOTIFIED` mirror of it, both also
answering with, or leaving waiting, the register the estate had already replaced. No production code
changed in it, so the statements are unchanged and the Phase 6 rule requiring an integration red run
for a change to one is not engaged. A design fact it established, recorded so the next reader does
not go looking for the missing case: `markFailed`'s successor search can never see a GENERATED row,
because a successor past RECORDED is one some batch took there, `idx_register_batch_live_key` admits
one in-flight batch per court centre and register day, and `markFailed` needs its own batch in
flight to fail at all - so the only reachable state past RECORDED for that statement is NOTIFIED, by
way of a batch that has settled, which is why its case is a late delivery rather than a re-share.
Naming the three live states rather than excluding the dead ones is what keeps the list closed
against `processed_output_status_chk`, so the port javadoc for `markFailed` is wider than reachable
rather than mistaken.

**No approved TDD exceptions (Phase 8), and the absence is a judgement rather than an omission** -
an exception block is only worth reading if it is exhaustive, so what was judged against it is named
here instead. Phase 8 produced no test-after production behaviour. Its one implementation commit,
`4021608`, is the green half of `0aa2cc5`'s four failing assertions, and what the fix carried beyond
them is bounded by that commit's own two [A] cases: the gauges are published only where the run got
as far as assembling, and the failure still leaves the run. The one ordering the fix changed that no
assertion distinguishes - a completing run publishing its gauges after the reconciler rather than
before it - moves no value any case or any meter can read, so it is stated in the commit body rather
than claimed as an exception.

The [A] work of five commits was judged against that list and is deliberately not on it, all of it
the shape `c3d8ff7`'s ten cases and `f009bc5`'s fifth case have: an [A] case with no implementation
commit following it, whose observed run and reverted mutations are recorded on its own tick line
rather than in an exceptions entry. They are `1fe0285`'s six cases (T069), `81d2b87`'s ninth group
(T070), `23e4daf`'s seven cases and the two beside `0aa2cc5`'s four red ones (T072), and `29cbc9d`'s
two (the checkpoint above). `5d1520a` is documentation only and owes no pair, which is the footing
Phase 7's own three documentation commits were recorded on.

**What Phase 8 leaves open.** Each item names where it belongs, so that nothing is carried only in a
workflow report. Items 1 to 6 are T071's and the gate's; 7 to 10 came out of T070's drive; 11 to 14
are decisions rather than defects.

1. **CLOSED by `28a2fd5` / `1da7125`.** The `db` composite, fixed as option (c): the
   auto-configured contributor switched off and `config/StoreHealth` contributing `db` over the
   primary pool alone. (a) was rejected because `autowire-candidate` would hide the pool from the
   two qualified injections that are the only intended way to reach it, (b) because it would hand
   this service the pool's lifecycle to solve a health-naming problem.
2. **CLOSED by `1da7125`.** `GenerationHealth.probe()`'s javadoc had said the pool was invisible
   to the auto-configuration because it is `defaultCandidate = false`; it now says what actually
   keeps `db` named `db`, and names the flag that would have done what that claim described.
   `FileServiceDataSourceConfig`'s narrower claim about the auto-configurations' conditions was
   true and is untouched.
3. **PARTLY CLOSED by `28a2fd5`.** `ReadinessPolicyIT`'s new case now reads the health endpoint
   over a generation-enabled pod with one database out of service, which is what the defect needed
   and no generation end-to-end suite does. What is still uncharacterised is the shape of `db`
   itself - that it covers the processed log and nothing else - as distinct from its behaviour
   under this one outage. Worth a case in Phase 9 or the next increment.
4. **CLOSED by `1da7125`.** T071's third case is no longer confounded: readiness goes DOWN during
   a run because `fileServiceRun` says so, and `db` stays UP throughout, which is recorded on
   T071's line above.
5. **STILL OPEN, narrowed.** The two [A] cases landed inside `28a2fd5`, whose body records the
   failing assertion of the case that was red rather than a mutation for the two that were green.
   The mutations that would show those two non-vacuous are named here and have not been run: drop
   `fileServiceRun` from the readiness `include:` line in `application.yaml` and case 3 must fail;
   add `servicebus` to it and case 1 must fail.
6. **`PostgresTestSupport.refuseConnectionsTo` / `allowConnectionsTo`** is worth folding into the
   fixture on its own merits - `ALTER DATABASE ... ALLOW_CONNECTIONS false` plus
   `pg_terminate_backend` is the only way this build can stage an outage of one database inside the
   shared server, and `FileServicePayloadStoreIT` and the generation suites may want it. Note also
   that no deployment manifest ships in this repository, so T071's sustained-outage window is not
   grounded in a real `failureThreshold` times `periodSeconds`; the constant's javadoc says so.
7. **CLOSED by `667b9e8` / `7332149`.** `DocumentEventListener`'s two readers of an optional field
   attached the parser's exception to their WARN, and those messages quote what the field held:
   "Invalid UUID string: <value>" and "Text '<value>' could not be parsed". Both now report the
   field by the name this service owns and the refusing reader's class, and neither writes the
   value. The red run is two cases in `DocumentEventListenerTest` over
   `CapturedLog.renderings()`, because an attached exception reaches a log index exactly as a
   message does. Measured while T070 was written and still true: Jackson 3 redacts the source in a
   parse failure, so the envelope parse does not quote the event body and a `generation-failed`
   body carrying `sdg_reason` is safe there.
8. **`RegisterGenerationService:220` puts an English sentence in a `reason=` slot**, logging
   `unavailable.getMessage()`. Not a leak - the phrase is bounded and written in
   `FileServicePayloadStore`, which documents exactly that - but the suite's own stated rule is that
   `reason=` carries a bounded code, and `PAYLOAD_STORE_UNAVAILABLE` is already the batch's reason.
9. **The bounded-reason sweep is delivery-path only** (`reasonsIn` / `BOUNDED_REASONS`) and could be
   extended over the two legs now that `GenerationLegs` drives them, which would catch item 8 by
   construction. Deliberately outside T070, whose claim is the three named values.
10. **`courtregister_generation_latency` is declared and recorded by nothing.** An alerting gap
    rather than a privacy one, since it carries no label; `TelemetryPrivacyTest` asserts the unmoved
    set so that whoever wires the timer up is told to fold it into the drive.
11. **Neither the run report's line nor its new ERROR carries `requestId` or `hearingId`**, which
    Principle VII asks of every log line about processing. A run is not a delivery and has neither,
    and this is how the line has been since `6d7aca8` rather than anything T072 changed. The gate
    rules: either the principle is read as scoped to per-delivery lines, or the run's line gains a
    run correlation id of its own.
12. **The severity of a refused report (`CliMain.reported`, `CliMain.java:302`) is the design
    owner's to set**, and the behaviour is unchanged pending it. It breaks no stated rule: Principle
    VI's ERROR-plus-metric clause is conditional on a failure path taking one of the two settlement
    outcomes, and a command reached by `kubectl exec` takes neither and can carry neither id, while
    what does bind - that nothing is swallowed - is satisfied, the refusal being answered on exit 2
    and said once. For keeping ERROR: the level and the exit code agree throughout `CliMain` (every
    WARN path answers REFUSED and 1, every ERROR path FAILED and 2), the line is the only surviving
    record that a listing somebody may already be acting on is incomplete, and the pod cannot tell a
    deliberate `| head -1` from a listing killed part way through. For moving it to WARN: the write
    has already happened and the remedy is to re-run without the pipe, the path increments no metric
    so it is attention with no signal behind it, and an ERROR that fires on correct operator
    behaviour trains the estate's readers to discount this service's ERRORs. Smallest change either
    way: none, plus one sentence in `reported`'s javadoc recording the level as deliberate, since
    `CliMainTest.errorLines` already pins it in effect; or `LOG.error` to `LOG.warn` with
    `CliMainTest.errorLines` and its one case retargeted at `Level.WARN`, which under the loop is a
    red test commit followed by a one-word fix. Two things belong with the ruling: `wired`'s
    not-wired ERROR is as everyday as this one (`check-flag` against an intake-only pod is an
    ordinary first step of a cutover), so a ruling against everyday ERRORs reaches it too; and if
    ERROR is kept on Principle VI grounds, the same sentence pairs an ERROR with a metric an alert
    fires on, which this path has none of, so the consistent third option is ERROR plus a counter.
13. **The shipped default of `courtregister.cli` when nothing sets it at all is pinned nowhere.**
    Carried from Phase 7's exception 1 as a follow-up for T075 or Phase 8 and still open: both
    `CliModeConfigTest` and `HttpSurfaceTest` set the property explicitly, so `NOT_CLI` flipped from
    `"false"` to `"true"` leaves both green. It now belongs to T075, which names it.
14. **The 127-member batch's header still comes from progression's `stream().findAny()`** and this
    port reproduces it bug for bug. Nothing is wrong - the golden reproduces - but if the
    header-from-any-member behaviour is ever judged a defect it has no register row of its own.
    `PROVENANCE.md` calls it "the P4 shape", though P4's fix is the recipient union and not the
    header.

---

## Phase 9: Polish and documentation sync

- [ ] T073 [P] Rewrite `.claude/rules/design_rules.md` for the 002 shape (ports, `batch/`, adapters,
      state machines, the one-lever rule); rewrite "The Four Contracts" in
      `.claude/agents/spec-validator.md`; update `.claude/agents/software-engineer.md` rules (record
      not POST; the flag; ids before calls). Docs-only, exempt from the loop.
- [ ] T074 [P] `README.md` Status → 002 complete; `CLAUDE.md` unchanged unless a rule moved; the
      constitution's Sync Impact Report `⚠ pending` items → `✅`. Two handovers and four register
      corrections land here or with the owner of the file in question, which this task decides.
      **The handovers**: the metrics-section note for the Confluence page, written out under T072
      above, and nothing in this repository edits that page. **The register corrections T068
      identified and could not make**, all in `doc/DEFECT-FIXES.md`, which only the register's own
      stage may edit: the shared header cites "constitution v3.0.0 Principle I" in two places while
      the constitution is at v3.0.1 (the bump was a PATCH that completed the commit-type list and
      changed no principle, so the citation is defensible as historical and was left alone rather
      than edited out of scope); four prose lines in that file's C-half header (lines 7 and 23-25)
      run to 101-128 columns and predate T068, which did not reflow them because they belong to the
      C rows' half; P10's status cell says "FIXED - pinning tests passing" without naming the task
      that landed it, where every other FIXED row names one, and the behaviour arrived at T022 by
      way of the two Phase 3 exceptions, which a reader will want the pointer to; and the P-row
      reconciliation should note that P10 is now cited from test code as well as from its pinning
      tests, `RegisteredDefectFixes.progressionLegRows()` carrying it and the differential audit's
      own summary counting two differences against it.
- [ ] T075 [P] `scripts/container-smoke.sh` - readiness UP < 60 s with generation enabled against the
      compose stubs; `check-flag` exit 0. **Both were already recorded green in Phase 7** at
      `441d653` ("PASS: readiness reported UP within the 60s budget" and "PASS: startup.sh
      check-flag printed flag=ON and exited 0", the second through the deployed reader rather than a
      STUB override), so what this task owes is the re-run on the branch as it now stands, plus the
      one case Phase 7's exception 1 left open and Phase 8 did not close: the shipped default of
      `courtregister.cli` when nothing sets it at all. The host-side `bootRun` block of
      quickstart.md is the other thing still owed from Phase 7's checkpoint.
- [ ] T076 Spec checklists: `checklists/requirements.md` re-validated against the delivered behaviour;
      add `checklists/consolidation-audit.md` recording T069's result and the goldens' provenance.
      **T069 has already delivered what that file has to record, and turned it from a check taken
      once into an assertion on every build**: the 001 corpus reproduces by manifest digest
      (`20fcb12324bf674d3b141b4fa822076f2ff56be1aad43531000d575aa649d924`, which is `INDEX.json`'s
      own `corpusDigest`), all 177 goldens digest to their recorded `outputSha256`, all 168 payload
      goldens reproduce from their recorded inputs, the 52 recorded refusals are refused here too
      under C29's rule, and the one attributed deviation is P10's two shapes. `1fe0285`'s body
      mis-cites that evidence as T075's; it is this task's. Two more things belong in the same
      checklist: `PROVENANCE.md`'s "Verification" section could now cite the audit rather than the
      one-off `diff -r`, which is worth asking about first because that file is a recording
      artefact; and P6 and P7's RETIRED status depends on progression's retirement PR actually
      merging, which nothing in this repository can assert, so the residue checklist (design §10.6)
      needs them tracked to conclusion with the owner-and-trigger shape C18, C28 and C34 carry
      rather than assumed.
- [ ] T077 [A] Final `./gradlew build` (PMD, Checkstyle 0 warnings, JaCoCo gate) green on the branch;
      review gate 9 (whole increment) PASS; report token use per phase.

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
