# Tasks: Court Register Service — full pipeline port, fix-first

**Input**: Design documents from `/specs/001-court-register-port/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix. A fix task's test is written to **fail against the legacy
behaviour and pass against the fix**, and its DEFECT-FIXES row names it.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs — interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` — so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**Phase 1 bootstrap tasks are infrastructure, not TDD pairs** (constitution mechanical exemption):
their commits record verification evidence instead of a red assertion.

**[A] Acceptance/characterisation tasks** verify already-fixed behaviour (broker configuration,
assembled end-to-end behaviour, the container, the differential audit). No implementation task
follows them and no red run is required; the task records the initial observed result.

**Conventions**: package root `uk.gov.hmcts.cp.courtregister`; production code under
`src/main/java/uk/gov/hmcts/cp/courtregister/`, tests under
`src/test/java/uk/gov/hmcts/cp/courtregister/`; legacy sources referenced as `$DF` =
`cpp-context-azure-legalaidagency/azure-functions/durable-functions`. `*IT` suites need Docker and
run inside `./gradlew test`. Conventional Commits on `main`; no AI attribution. **Every task that
lands a C-fix updates that row of `doc/DEFECT-FIXES.md` (status → FIXED, pinning test confirmed)
in the same commit.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency)
- **[A]**: acceptance/characterisation — see above
- **[US#]**: the spec user story the task traces to

---

## Phase 1: Bootstrap (repo, Spec Kit, ledger, contracts)

- [x] T001 Scaffold the repository from the informant reference build (gradle/, config/, .github/,
      docker/, compose, smoke script, .editorconfig/.gitignore, Application) renamed for the court
      register; first green `./gradlew build`. *(done — commit `338094e`)*
- [x] T002 Vendor the frozen outbound contract: `progression.add-court-register.json` +
      `courtRegisterDocument/*.json` at `criminal-court-public-model` **v17.103.13** into
      `src/main/resources/contracts/progression/` with `PROVENANCE.md` (tag, jar digest,
      progression `pom.xml:75` coordinate) — main resources: the schemas are the runtime pre-send
      validation authority, not test-only. *(done — commit `e83693c`; relocated from test
      resources after the P0 review)*
- [x] T003 (commit 3e8821f) Spec Kit adaptation: constitution v2.0.0 (fix-first Principle I), `.claude/`
      agents/rules/context adapted to this service, `.specify/feature.json` →
      `specs/001-court-register-port`, `CLAUDE.md` SPECKIT block. Commit with the constitution's
      SYNC IMPACT report.
- [x] T004 (commit a37080f) `doc/DEFECT-FIXES.md`: all 34 rows with fix specifications, statuses PLANNED (or
      PENDING for C18/C28/C34 with owner + trigger); `README.md` Documentation table references
      it; doc skeletons (TECHNICAL_DESIGN, API_CONTRACTS, SOLUTION_BRIEF, CHANGELOG, openapi.yaml
      comment-only).
- [x] T005 [P] Shared test fixtures cloned from IR-REPO `support/`: `PostgresTestSupport`,
      `ServiceBusEmulatorTestSupport` (mounting `docker/servicebus-emulator/config.json`),
      `RedisTestSupport`, `ServiceTestSupport` (court-register properties), `AdjustableClock`,
      `CapturedLog`, plus `application-test.yaml` (`courtregister.consumer.enabled=false`,
      datasource excluded, readiness `ping`). Verify with a trivial context test.
- [x] T006 [P] Comparator vendored: `comparator-vectors/vectors.json`, `support/JsonParity`,
      `support/ComparatorContractTest`, `support/JsonParityTest` from IR-REPO (domain-independent;
      116 assertions). [A] — records the initial pass.

**Checkpoint**: build green; ledger exists with 34 rows; fixtures and comparator in place.

---

## Phase 2: Foundational — domain, inbound contract, persistence (blocking)

### Tests first ⚠️

- [x] T007 [P] [US1] Write `domain/DistributionCommandParserTest` +
      `domain/DistributionCommandSchemaCorpusTest` (red; seams: `DistributionCommand` record,
      parser skeleton, `config/JacksonConfig` skeleton) — six-field contract, closedness, enum
      values (`Hearing_Resulted` only), optional `userId`, the Q1a rename triple
      (`hearingDay`→ command field, `userId`→ caller identity), BigDecimal number pin; dual
      validation parser-vs-schema with the court-register `$id`.
- [x] T008 [P] [US2] Write `domain/RequestFingerprintTest` (red; seam: `RequestFingerprint`) —
      canonicalisation (uppercase-hex UUID, offset-vs-Z instants, fractional seconds), changed
      immutable field changes the hash.
- [x] T009 [P] [US4] Write `config/ConfigurationValidationTest` (red; seams: `CourtRegisterProperties`
      + `PropertiesValidator` signatures) — every `courtregister.*` property/default in the plan's
      Configuration table binds; deadline < lease; renewal margin; exactly-one credential source;
      LIVE requires system-user-ids; stub-refused-when-namespace; worst-case fetch + submission
      arithmetic; `submission.validate-outbound` refused false when a namespace is set.
- [x] T010 [P] [US4] Write `config/ProcessingMetricsTest` (red; seam: `ProcessingMetrics`) — one
      case per instrument incl. `courtregister_completions_total{reason}` for the five reasons,
      `courtregister_deadlettered_total{reason}`, and
      `courtregister_transformation_anomalies_total{reason}` (the C19/C20/C27 anomaly metric).
- [x] T011 [P] [US1] Write `persistence/SchemaMigrationIT` (red) — every data-model V1 fact,
      including the `processed_output` court-register columns, `UNIQUE (source, request_id)`,
      `response_code`, `anomaly_summary` (nullable text, the C19/C20/C27 bounded-count field),
      status CHECK PENDING/POSTED/FAILED, FK ON DELETE RESTRICT.

### Implementation

- [x] T012 [US1] Implement `domain/DistributionCommand`, `domain/CallerIdentity`,
      `inbound/DistributionCommandParser`, `config/JacksonConfig`,
      `src/main/resources/contracts/distribution-command.schema.json` (green for T007).
- [x] T013 [US2] Implement `domain/RequestFingerprint` (green for T008).
- [x] T014 [US4] Implement `config/CourtRegisterProperties` + `config/PropertiesValidator` (green
      for T009).
- [x] T015 [US4] Implement `config/ProcessingMetrics` (green for T010).
- [x] T016 [US1] Write `src/main/resources/db/migration/V1__create_processed_log.sql` per
      data-model.md (green for T011).

### Guard — tests first ⚠️

- [x] T017 [P] [US2] Write the guard PG suite (red; seams: `IdempotencyGuard`, repositories,
      domain records): `IdempotencyGuardIT` (one case per state-machine row incl. the five
      completion reasons), `ProcessedLogDurabilityIT`, `ClaimContentionIT`, `ClaimReclamationIT`,
      `StaleRunnerRejectionIT`, `CrashWindowIT`, `FailedReplayIT`, `IdempotencyCollisionIT`,
      `ProcessedOutputRepositoryIT` (digest-before-send kept after failure; POSTED replay skip).
- [x] T018 [US2] Implement `persistence/ProcessedRequestRepository`,
      `persistence/ProcessedOutputRepository`, `persistence/ProcessedLogProbe`,
      `application/IdempotencyGuard` + domain state records (green for T017; ports the informant
      SQL with the `processed_output` delta).

**Checkpoint**: state machine + schema proven against Testcontainers Postgres.

---

## Phase 3: Transport and lifecycle (US1/US2/US4 spine)

### Tests first ⚠️

- [x] T019 [P] [US1] Write `inbound/MessageListenerSettlementTest` +
      `inbound/SettlementFailureEdgeTest` (red; seams: listener + `StoreGate`) — exactly one
      settlement per delivery; COMPLETED redelivery acked without run; FAILED same-identity
      re-dead-letter; lock-loss counted.
- [x] T020 [P] [US4] Write `config/ServiceBusHealthIndicatorTest` (red; seam: indicator) — the
      broker-silence staleness model, refused settlements as fault inputs.
- [x] T021 [P] [US1] Write the emulator suite (red): `QueueSettlementIT`,
      `ContractValidationDeadLetterIT`, `DeliveryExhaustionIT`, `DuplicateDetectionIT`,
      `StoreOutageIT`, `ProlongedStoreOutageIT`, `ReadinessPolicyIT`, `StartupWithQueueDownIT`,
      `QueueOutageRecoveryIT` — against queue `courtregister.requests` with a stub pipeline.
- [x] T022 [US1] Implement `inbound/ServiceBusConsumerConfig`, `inbound/CourtRegisterMessageListener`,
      `inbound/ConsumerLifecycleController`, `inbound/StoreGate` impl,
      `application/DistributionPipeline` skeleton wired to stub ports,
      `config/{DeferredFlywayMigration,IntakeStartupHealth(+Indicator),ServiceBusHealthIndicator}`,
      the port interfaces (`HearingPayloadSource`, `NowSubscriptionsSource`, `RegisterTransformer`,
      `RegisterSubmissionClient`) with their exception types (`PayloadUnavailableException`,
      `ReferenceDataUnavailableException`, `TransformationFailedException`,
      `SubmissionFailedException`) and result records (`TransformationResult`,
      `SubmissionReceipt`) per the plan's port contracts,
      `adapter/stub/*` (green for T019–T021, serialised — all touch the listener/lifecycle).
      *(done — the payload port and its stub land here, which is what the skeleton calls. The other
      three ports are deferred to the phases whose tests demand them: `RegisterTransformer` and
      `RegisterSubmissionClient` are typed in `CourtRegisterDocument`, and tasks.md gives that record
      family to **T039a**, so declaring them here would mean inventing the outbound shape ahead of
      the vendored schemas; `NowSubscriptionsSource` and its refusing stub have no caller until the
      chain is wired in **T039**, and landing behaviour with no test that could fail first is
      refused by Principle II. Recorded here so the deferral is visible rather than silent.)*

**Checkpoint**: the walking-skeleton behaviours the informant proved, re-proven here.

---

## Phase 4: Pipeline core (US3/US4 — contexts, vocabulary, dates, matching)

### Tests first ⚠️

- [x] T023 [P] [US3] Write `pipeline/DatesTest` (red; seam: `Dates`) — DT1–DT8 twins minus DT5;
      **C10 fix**: `registerDate('2020-06-01T10:00:00Z')` is the same instant (fails vs legacy
      `11:00:00Z`); **C13 fix**: ISO parse of `YYYY-MM-DD`, a catch that cannot itself throw;
      `Invalid date format` case; **C12** (`DatesTest.bst_evening_share_uses_the_share_day`):
      `sharedTime = 2020-06-01T23:30:00Z` (00:30 BST on 2 June) resolves the refdata `on=` day
      as `2020-06-01` — fails vs legacy, whose +1 h relabelling reads `2020-06-02`.
- [x] T024 [P] [US3] Write `pipeline/DefendantContextBuilderTest` (red; seams: `DefendantContext`,
      builder) — DC1–DC4, DC6–DC10 twins (register configuration `(hearing, isRegister=true)`,
      result-level tagging, `isDeleted` dropped, youth flag); **C22 fix**: a court application
      whose applicant is not a prosecuting authority is excluded (fails vs legacy); DC5 as the
      informant-contrast case.
- [x] T025 [P] [US3] Write `pipeline/VocabularyBuilderTest` (red; seam: `VocabularyBuilder`) —
      `containsOnlyKeys` the 18 real keys; custody (police/prison, application+case), appearance,
      cps, youth/adult flags; **C30 fix**: all three major-creditor predicates consistently
      unmatchable for this flow.
- [x] T026 [P] [US3] Write `pipeline/CourtExtractFilterTest` (red) — RF1 twin (result+prompt
      levels, `courtExtract` `'Y'`/`'y'` fallback).
- [x] T027 [P] [US3] Write `pipeline/RegisterBuilderTest` (red; seam: `RegisterBuilder`,
      `RegisterFragment`) — S1 twin repointed (registerDate = true instant; hearingDate
      `2020-01-20T00:00:00Z`; hearingId; 1 register defendant, 4 results, none publishedForNows);
      `courtCentreId` (correct spelling) + `courtCentreOUCode` populated; 18-key vocabulary
      attached per defendant; empty context list yields an empty fragment (C6 path).
- [x] T028 [P] [US3] Write `pipeline/SubscriptionRulesTest` (red; seam: `SubscriptionRules`) —
      SS-CR1 twin; SS-CR2 repaired (the local vocabulary actually drives the assertion);
      included/excluded NOWS + prompt/result inc-exc branches; **C4 fix**: the court-centre OU
      code feeds the court-house rule only (an `informantCode` equal to the OU code no longer
      matches — fails vs legacy); **C5**: explicit court-register branch —
      `isCourtRegisterSubscription` subscriptions match via `selectedCourtHouses`;
      `matchCpsProsecuted` not reproduced; **C30 fix at matcher level**:
      `major_creditor_flags_never_match_a_court_register` — all three major-creditor flags
      require a non-empty applicable list (fails vs legacy, where `anyMajorCreditor` is
      vacuously true on `[]`).
- [x] T029 [P] [US3] Write `pipeline/SubscriptionMatcherTest` (red; seam: `SubscriptionMatcher`) —
      CS2/CS3 twins; CS4 with a real `ouCode` lock; CS1 split: empty answer ⇒ no matches
      (`no-subscriptions` downstream), unanswered ⇒ `ReferenceDataUnavailableException`;
      **C31 fix (N17–N19)**: adult-first/youth-second hearing matches a youth-vocabulary
      subscription (fails vs legacy `[0]`-only); judicialResults still collected across all
      defendants (N18).
- [x] T030 [P] [US4] Write `application/GroupProceedingsPolicyTest` (red; seam: policy type) —
      N8–N12 under the strict-boolean rule: `true` skips with reason; `false`/`null`/absent
      proceed; `"false"`/`"true"`/`1` proceed with WARN + metric (fails vs legacy loose `==`).
- [x] T031 [P] [US1] Write `application/DistributionPipelineTest` (red) — N1–N7: stage sequence and
      argument shapes; C32 cache+fallback miss ⇒ transient; C2 a throwing stage always records a
      terminal status; the four no-op reasons N29–N33 distinguishable; C6 empty fragment ⇒
      `no-defendants`; submission invoked once per document, zero on no-op.

### Implementation (serialised where files are shared)

- [x] T032 [US3] Implement `pipeline/Dates`, `pipeline/OrderedDates`, `pipeline/HearingDates`
      (green T023) — updates DEFECT-FIXES C10/C12/C13.
- [x] T033 [US3] Implement `pipeline/DefendantContext(+Builder)`, `pipeline/Json`,
      `pipeline/JsStrings` (green T024) — updates C22.
      *(done — the gather, `DefendantContext` and `Json` land here, which is what T024 drives.
      `JsStrings` is deferred to the phase whose test demands it: its only legacy call site is
      `RecipientMapper.js:41`'s `.trim()` of a subscription email, so the trim belongs with
      **T048**'s `RecipientMapperTest`. Landing it now would mean production code with no test that
      could have failed first, which Principle II refuses. Recorded here so the deferral is visible
      rather than silent.)*
- [x] T034 [US3] Implement `pipeline/VocabularyBuilder` (green T025) — the vocabulary half of C30.
      *(done — C30 stays PLANNED with its vocabulary half recorded in the register: the fix is only
      half implemented until T037 makes the three creditor predicates consistent, and a row marked
      FIXED on half a fix is the register lying.)*
- [x] T035 [US3] Implement `pipeline/CourtExtractFilter` (green T026).
- [x] T036 [US3] Implement `pipeline/RegisterBuilder` + `domain/RegisterFragment` (green T027) —
      updates C6 (and the C26 spelling on the fragment).
      *(done — C6 moves to FIXED; C26 stays PLANNED with its fragment half recorded. C26's fix is
      the whole outbound model being honest, which is `OutboundContractValidationTest`'s claim in
      the mapper phase; the fragment's spelling is one field of it.)*
- [x] T037 [US3] Implement `pipeline/SubscriptionRules` (green T028) — updates C4/C5/C30.
      *(done — C4, C5 and C30 all move to FIXED. C30's matcher half completes the vocabulary half
      T034 landed; the legacy's per-value `FCOMP`/`CREDITOR_NAME` creditor scan is deliberately not
      ported, because both of its call sites are already guarded by the same non-empty-list test the
      fix imposes on the third predicate, and a court register's creditor lists are empty by
      construction.)*
- [x] T038 [US3] Implement `pipeline/SubscriptionMatcher` (green T029) — updates C31.
      *(done — C31 moves to FIXED. The legacy's "no subscriptions" and "no defendants" early
      returns are not written as branches: an empty set in force filters to nothing and a register
      with no defendants satisfies nothing, so both end exactly where the legacy's returns end.)*
- [x] T039 [US1] Implement the group-proceedings policy + wire `DistributionPipeline` stages
      (green T030, T031) — updates C2/C7/C32/C33 (pipeline half).
      *(done — C2 and C7 move to FIXED. C32 and C33 stay PLANNED with their pipeline halves
      recorded: C32's remaining half is the Redis/query-fallback adapter (adapter phase) and C33's
      is the transformation that chooses three of the four no-op reasons (T054). The four classified
      failure types gained a shared `domain/ClassifiedFailure` interface so the core branches on the
      classification a throw site chose rather than on a Java type — a mechanical refactor, no
      behaviour change. `PipelineConfig` still builds the walking-skeleton pipeline: the
      `RegisterTransformer` bean has nothing to be yet, and a policy bean nothing consumes would be
      dead wiring. It is widened in the same commit as T056.)*

**Checkpoint**: a hearing payload becomes a matched fragment with fixed dates, eligibility,
vocabulary and matching semantics.

---

## Phase 5: The outbound document (US3 — twelve mappers + validation)

- [x] T039a [US3] Shared compile-safe seams for the mapper phase: the `domain/CourtRegisterDocument`
      record family (document, hearingVenue, defendant, parentGuardian, hearing, caseOrApplication,
      offence, result, recipient, alias, counsel, address) with signatures matching the vendored
      schemas, plus twelve mapper skeletons throwing `UnsupportedOperationException` — so T040–T051
      stay genuinely [P] (no two test tasks create the same seam file). Infrastructure task under
      the red-run convention's seam clause; no assertions of its own.
      *(done — thirteen skeletons, not twelve: `AggregationMapper` is added alongside the twelve so
      that T051 has a seam nobody else owns, which is what this task exists for; it is marked as
      landing in T056 rather than T054, per the implementation split. Two deliberate model
      decisions, both C26: `courtRegisterCaseOrApplication`'s five never-populated fields
      (`prosecutorName`, `applicationDecision(+Date)`, `applicationResponse(+Date)`) are **not**
      declared — the record declares what the mappers write, which is what C26's fixed behaviour
      says — and every list component keeps `null` distinct from empty, because each carries
      `minItems: 1` and the `Alias`/`Counsel` absent-vs-empty asymmetry is behaviour the comparator
      guards. `DistributionPipelineTest`'s one construction of the document was widened to the new
      signature; nothing it asserts changed. `TransformationAnomaly` already carried every code
      T047/T048/T050 name, so the enum is untouched. Follow-up in the same task: the ten inline
      defensive-copy ternaries took the branch gate from 0.85 to 0.84, so the rule they all state is
      written once in `domain/FrozenList` instead of ten times — `jacocoTestCoverageVerification`
      green again, and the threshold untouched.)*

### Tests first ⚠️ (all [P] — one file each; seams provided by T039a)

- [x] T040 [P] [US3] `pipeline/AddressMapperTest` — A1/A2 repaired: absent input ⇒ absent output
      (not `[]`); address1–5 pass-through; `postcode`→`postCode`.
- [x] T041 [P] [US3] `pipeline/AliasMapperTest` + `pipeline/CounselMapperTest` — twins plus the
      pinned asymmetry: aliases `[]`⇒`[]`/absent⇒absent; counsels absent-or-empty⇒absent; name
      composition incl. middle-name-absent; unmapped `legalEntityName` asserted absent.
- [x] T042 [P] [US3] `pipeline/DefendantMapperTest` — D1–D4 twins + explicit case-first,
      applications-only-if-empty precedence.
- [x] T043 [P] [US3] `pipeline/HearingMapperTest` — **C8/C9 fixed**: multi-entry attendance selects
      the correct defendant without mutating input (fails vs legacy assignment); date-compatible
      `defendantPresent` semantics per the kernel's orderedDate rule; all three
      `defendantAppearanceDetails` renderings; absent attendance ⇒ present=false; empty array ⇒
      guarded, not a TypeError (C19-family guard).
- [x] T044 [P] [US3] `pipeline/HearingVenueMapperTest` — HV1 twin + address body (postCode case),
      lja-absent, courtCentre-absent guarded failure.
- [x] T045 [P] [US3] `pipeline/OffenceMapperTest` — OF1 twin repaired (real indicatedPlea,
      allocationDecision, convictionDate asserted); **C23 fix**: `verdictCode =
      verdictType.verdictCode ?? verdictType.categoryType` (`"1234"`, fails vs legacy
      `"desc1234"`; plus `verdict_code_falls_back_to_category_type_when_absent` for the
      code-less live-payload shape — never the description); **C24 fix**: wording
      joined with `\n`, absent legislation ⇒ wording alone (no `####`, no `undefined`); offence-level
      result scoping pinned with two offence ids (the legacy-correct behaviour kept).
- [x] T046 [P] [US3] `pipeline/ParentGuardianMapperTest` — PG1 twin (real address5), guardian
      fallback, no-parent ⇒ absent, non-string role guarded.
      *(done — fifteen cases. The legacy fixture has no fifth address line, so the twin says so
      plainly and a constructed person carries one: a repair that needed no new fixture, because the
      shape under test is one address rather than a hearing. The empty-defendant-list dereference at
      `ParentGuardianMapper.js:15` is deliberately **not** asserted here — it is C19's construct and
      C19's fix, in the mapper that calls this one, makes it unreachable; asserting a guarded answer
      for it here would be an uncatalogued behaviour change.)*
- [x] T047 [P] [US3] `pipeline/ProsecutionCaseOrApplicationMapperTest` — PC1–PC7 twins (correct
      3-arg construction), SNI-9005 case-skip parity, **C20/C21 fixes**: absent/unmatched
      application and personDefendant-less own-record are guarded skips with WARN + an
      `anomaly_summary` bounded count (`unresolvable-application:1`) — not TRANSFORMATION_FAILED
      (fail vs legacy TypeError), C22 exhibit re-asserted at mapper level, dead methods not reproduced
      (C26).
      *(done — 35 cases, 34 red on the seam. PC4–PC7 are driven through `map` rather than through
      `getCourtApplicationOffences`: the legacy's two-arg construction of a three-arg mapper is not
      expressible against this seam, and reproducing the reach-past would leave the four cases about
      an application's offences still not touching the object that gathers them. Two deliberate
      decisions recorded in the file: the SNI-9005 **case** skip keeps its shape exactly — warn and
      skip, **uncounted** — because C20 names only the application path, and the asymmetry is
      asserted so that changing it later has to be a decision; and C22's applicant gate is asserted
      at the mapper as well as at the context builder, which is what the C22 row specifies ("in both
      the context-builder gate and the mapper"). The one green case is the C26 reflection guard that
      the two dead methods are not declared — a guard against reintroduction, with nothing to fail
      against yet.)*
- [x] T048 [P] [US3] `pipeline/RecipientMapperTest` — R1–R4 twins + `cr_standard` default pinned;
      **C27 fix**: letter-delivery and missing-email drops WARN-logged and counted in
      `anomaly_summary` (`letter-delivery-dropped:n`, `recipient-missing-email:n`,
      `recipient-not-for-distribution:n`) — drop itself preserved; emailAddress2 carried.
      *(done — 26 cases, all red on the seam. The three codes divide the mapper's single `if` by the
      reason a subscription failed it: letter delivery first, then not-for-email/not-for-distribution,
      then no address to send to. **One shape is deliberately left unasserted** and is carried to
      T054 as an open decision: the legacy's `recipient.emailAddress1 !== undefined` keeps a
      recipient whose address is an explicit JSON `null`, which the frozen contract then refuses —
      so parity loses the whole register (C29) and dropping it is a behaviour change C27's row does
      not authorise. Absent and whitespace-only addresses are asserted; explicit `null` is not, and
      needs a C-number or a legacy check before T054 chooses. The trimming R3/R4 pin is asserted at
      recipient level rather than against a helper, so `JsStrings` — deferred here by T033 — is
      whatever T054 needs it to be.)*
- [x] T049 [P] [US3] `pipeline/ResultMapperTest` — RS1 twin + null/empty ⇒ absent.
      *(done — ten cases, all red on the seam. The empty-list guard is the one that matters: this
      mapper is called at three scopes where an empty filtered list is the normal case, and every
      result list on the frozen contract carries `minItems: 1`, so answering `[]` instead of nothing
      is a document progression refuses. Neither guard is exercised by the legacy suite.)*
- [x] T050 [P] [US3] `pipeline/YouthDefendantMapperTest` — YD1 twin repaired (real nationality,
      three-part name, address5); **C19 fix**: legal-entity/unmatched defendant ⇒ guarded skip
      recorded as `anomaly_summary` `unresolvable-youth-defendant:1` + the anomaly metric, not
      TRANSFORMATION_FAILED (fails vs legacy TypeError); **C25 fix**: ethnicity observed-else-self-defined
      (three branches); real + mixed `postHearingCustodyStatus`; defence-counsel filtering.
      *(done — 42 cases, all red on the seam. The YD1 twin is written **twice**: once against the
      legacy fixture, saying plainly what it does and does not carry — no `nationalityDescription`,
      no `address5`, no middle name, an empty `defendantCaseJudicialResults` — and once against
      `base/hearing-with-surviving-youth-defendant.json`, whose child has the first three of those.
      Repairing the legacy fixture in place would have destroyed the record of what the legacy suite
      actually observes. **No fixture in the repo carries a person-level `address5`**, so that one
      repair is deferred to `AddressMapperTest`'s pass-through, which already pins it. C25 is four
      branches rather than three — both, observed-only, self-defined-only and neither — because the
      last is what distinguishes the fix from always emitting something. The C19 WARN is asserted to
      carry the bounded reason and **not** the child's name or date of birth, where C20's warning
      names its application id: that row authorises an id and this one does not.)*
- [x] T051 [P] [US3] `pipeline/AggregationMapperTest` — O1/O2 repointed (reasons), O3 repaired
      (concrete `courtCentreId`; **C11 fixed fileName** exact string incl. hearingId; venue,
      recipients, youth-only defendants asserted concretely); C33 wiring.
      *(done — 22 cases, all red on the seam. C33 at this level is asserted through the **log**:
      the seam returns `CourtRegisterDocument`, so a `null` is all two different outcomes can say
      structurally, and the assertion that they are distinguishable has to be that each names its
      own `CompletionReason` code. The chain that turns those into terminal states is T056's.
      Two decisions inherited from T039a's seam javadoc and pinned here rather than left implicit:
      **all recipients dropped answers `null`** ("or no recipient", which the legacy does not do —
      it posts a document with `recipients: undefined`) and is named as the same outcome as no
      subscription at all; and the file name's court-centre code is the **hearing's**
      `courtCentre.code`, not the fragment's `courtCentreOUCode`, which is what the legacy reads and
      which no fixture could ever have distinguished because the two always agree. The
      recipients-dropped answer is worth a C-number check before T056 — it is a behaviour change
      C27's row does not itself authorise, though C33 arguably covers it.)*
- [x] T052 [P] [US3] `adapter/progression/OutboundContractValidationTest` — N25–N27: address-less
      defendant/parent and empty address1 are named violations against the vendored schemas; a
      valid document passes; the violating path appears in the bounded reason (C29, C26).
      *(done — 16 cases, all red on the seam, which this task creates:
      `adapter/progression/OutboundContractValidator`, taking the service's own contract mapper so
      that what is validated is what is serialised. The two method names the DEFECT-FIXES rows
      already name are used verbatim — `records_match_the_vendored_schemas` (C26) and
      `a_missing_required_address_is_an_explicit_failure` (C29). C26's assertion is a **fully
      populated** document rather than a structural check: with `additionalProperties: false` on
      every schema, a document carrying every field the records declare passing validation *is* the
      claim that the records and the wire agree. `field()` carries a JSON pointer
      (`/defendants/0/parentGuardian/address`) — the existing `ContractValidationException` shape
      needed no change, and the pointer is a path, never a value. One classification decision for
      T055: `minItems` violations are `INVALID_FORMAT`, because `MISSING_FIELD` is defined as absent,
      null or empty **string** and an empty array is none of those.)*
- [x] T053 [P] [US3] Author the Java fixture set under `src/test/resources/fixtures/` — legacy
      fixtures copied byte-identical where sound; the seven bad-vocabulary fixtures rebuilt with
      the 18-key set; the six new base hearings (complete courtCentre with code; surviving youth;
      group proceedings; adult-first multi-defendant; non-prosecuting-authority application;
      address-less youth). [A] — fixture authoring, no red run; provenance notes in the fixture
      README.
      *(done — twelve byte-identical copies verified with `diff`, the seven vocabulary rebuilds
      already landed with the phases that needed them, four further repairs (real indicated
      plea/allocation decision + a legislation-less second offence; offence-level results for two
      offence ids; a fragment complete enough to assemble from; a contract-valid document request),
      the six base hearings, and `support/ModelObjects` for the one fixture that is code. Observed
      result recorded in `fixtures/README.md`: `RegisterBuilder` over all six base hearings gives
      `courtCentreOUCode = B01LY00` and the unrelabelled `2020-06-01T10:00:00Z` throughout, the
      adult ahead of the youth in the multi-defendant hearing (C31), and **zero** applications on
      the defence-led one (C22). The repaired document request validates against the vendored
      schemas with zero errors where the legacy fixture fails three ways, one of them being C26's
      plural `arrestSummonsNumbers`. Three named departures from the legacy in the base hearings —
      ISO ordered dates, the complete court centre, and one dropped defendant-level result naming a
      master defendant the hearing does not carry — are recorded in the README with their reasons;
      no C-fix lands here, so no DEFECT-FIXES row moves.)*

### Implementation (serialised: T054 → T055 → T056)

- [x] T054 [US3] Implement the twelve mappers + `domain/CourtRegisterDocument` records (green
      T040–T051) — updates C8/C9/C11/C19/C20/C21/C23/C24/C25/C27 and the mapper half of C26/C33.
      *(done — thirteen bodies, not twelve. `AggregationMapper` is implemented here rather than at
      T056 because **every case `AggregationMapperTest` writes calls `AggregationMapper.map`
      directly**: T051 has no chain-level case, so there was nothing the mapper surface could not
      satisfy and nothing to carry forward. T056 keeps the wiring — `RegisterTransformationChain`,
      `RegisterTransformer` — which is what its own description asks for; the assembly itself is
      green now. The `CourtRegisterDocument` record family needed no change: T039a's signatures
      matched the schemas and the mappers wrote exactly what they declare.
      Four decisions the suites forced and this task made:
      **(1)** `OffenceMapper` answers `null` rather than `[]` for an application that gathered no
      offence, which `ProsecutionCaseOrApplicationMapperTest.an_application_with_neither_carries_no_offences`
      requires and which `CourtRegisterCaseOrApplication`'s own `minItems: 1` note already
      specified — the legacy sends `[]`, a document progression rejects. **Reversed under review
      (2026-09-01)**: that was an uncatalogued content change no register row authorised. The
      mapper keeps the legacy's `[]` and the pre-send validator refuses it as an `INVALID_FORMAT`
      — the register is lost either way, and losing it loudly is C29's whole point. See the C29
      row and `OutboundContractValidationTest.an_empty_offence_list_on_a_case`.
      **(2)** An application that is *ineligible* (non-prosecuting applicant, or another
      defendant's subject) is skipped **in silence**; only a **dangling** reference is counted as
      `unresolvable-application`. Nothing is wrong with an ineligible application's payload.
      **(3)** The C19 warning carries the bounded reason and the **hearing id** and nothing else,
      where C20's names its application id — that row authorises an id and this one does not.
      **(4)** The register day in the C11 file name is `Dates.localDate(registerDate)` — the
      court's own calendar day — not a substring of the instant.
      Two shared helpers landed rather than four copies: `JsStrings` (the filter-on-truthiness name
      join the counsel, parent-guardian and youth-defendant mappers each spell out, plus the
      recipient trim and C24's wording join) and `Json.elements`, which is `Json.array`'s iteration
      rule for a caller already holding the array; `Json.array` delegates to it and no behaviour
      moved. `pmdMain` was **red at the start of this task** with six violations and is green at the
      end: four were the seam fields these bodies now use, and the two genuinely pre-existing ones
      (`CourtRegisterProperties` duplicate `@DefaultValue` literal, `NoRegisterReason` field/method
      name) took narrow inline suppressions with reasons. `OutboundContractValidator`'s unused
      `json` field carries a suppression that says it comes off with T055's body.)*
- [x] T055 [US3] Implement `adapter/progression/OutboundContractValidator` (green T052) — updates
      C29 (validation half) and C26 (schema authority).
      *(done — 16 red cases green, and both rows move to FIXED. Four decisions the schemas forced:
      **(1)** the root is `progression.add-court-register.json`, not `courtRegisterDocumentRequest`
      — the command is what this service POSTs, and the two differ (the request declares
      `defendantType` and `courtApplicationId`, which no register carries). **(2)** Every
      `http://justice.gov.uk/…` identity the contract `$ref`s is mapped explicitly to its vendored
      copy under `classpath:contracts/progression/`, with `preloadSchema` on: an unmapped identity
      fails when the validator is constructed, because a validator that silently degraded to "could
      not fetch the schema, so nothing was checked" would reinstate the exact blind spot C29 exists
      to close, and one that reached the network on the hot path would be worse. **(3)** The dialect
      is DRAFT_4 — the vendored schemas declare draft-04 and spell their identity `id`, not `$id`,
      so reading them as draft-07 would leave every reference unresolvable. Format assertions are
      **on**, which is draft-04's own reading and progression's. **(4)** Where a document breaks more
      than one rule the reason is chosen by sorted pointer rather than by the validator's traversal
      order, so one document always yields one reason, and the shorter pointer of a nested pair —
      the outer failure — wins. The `minItems ⇒ INVALID_FORMAT` classification T052 specified is
      written down where the mapping lives, with its reason. The seam's `PMD.UnusedPrivateField`
      suppression came off with the body, as it said it would.)*
- [x] T055a [US3] Write `pipeline/RegisterTransformationChainTest` (red; seam:
      `RegisterTransformationChain`) — fragment → matched subscriptions → validated document
      through the chained stages; a no-op at each stage surfaces its distinct reason; stage
      exceptions classify, never swallow.
      *(done — 20 cases, all red on the seam this task creates. Driven through the **real**
      collaborators over the six base payloads rather than through mocks: what the suite is about is
      the joins, and a mocked stage cannot get a join wrong. Three decisions recorded here:
      **(1)** the C6 case asserts `no-defendants` **and** asserts it is not `no-subscriptions`,
      because a register with no defendants satisfies no subscription either — asking the questions
      in the aggregation's own order would answer `no-subscriptions` for it, so the chain's first
      stage is what makes C6 real and the negative is what pins it. **(2)** The contract check is a
      chain stage, so a document progression would refuse leaves the chain as a **classified**,
      non-transient `TransformationFailedException`; the reason is `OUTBOUND_CONTRACT_VIOLATION`,
      the code `data-model.md` already reserves for the C29 pre-send check, rather than the generic
      `TRANSFORMATION_FAILED` — the pipeline's catch-all would otherwise read it as an unexpected
      TRANSIENT failure and hand the delivery back four more times for a document that reads the
      same every time. **(3)** The envelope guards are the chain's own: a payload carrying no
      hearing reaches `Json.dereferenced` today and fails with a message about `courtCentre`, and
      one carrying no shared time throws a bare `NullPointerException`, so both are asserted as
      classified failures and the chain has to say what it means.)*
- [x] T056 [US3] Implement `pipeline/RegisterTransformationChain` + `pipeline/AggregationMapper`
      wiring into `RegisterTransformer` (green T055a and T051 chain cases;
      `DistributionPipelineTest` end-to-end unit path now green). *(T051's cases are already green —
      `AggregationMapper`'s body landed with T054 — so what remains here is the chain and the
      transformer wiring, not the assembly.)*
      *(done — T055a's 20 cases green, and C6 moves to FIXED. `RegisterTransformationChain`
      implements the `RegisterTransformer` port over four stages: build, address, assemble, hold to
      the contract. Three decisions, and one thing this task deliberately did **not** do:
      **(1)** the chain asks the **gather** question before the subscription question, which is the
      opposite of `OutboundCourtRegister/index.js:17` before `:22` and is the whole of C6 — a
      register with no defendants matches no subscription either, so the legacy's order answers
      `no-subscriptions` for a hearing whose outcome is `no-defendants`. The aggregation keeps its
      own order and its own guards, because it is called directly by its suite and a stage that is
      only safe when its caller asked first is not safe; the two sites read the same flag on the same
      fragment and cannot disagree.
      **(2)** `AggregationMapper.map` was left exactly as T054 wrote it, returning `null`. Giving it
      a richer return would have been tidier for the chain and would have left `map` a wrapper called
      only from its own tests — production code with no production caller, which is the class of
      thing C26 exists to refuse. The chain names the remaining two outcomes itself instead: handed a
      non-empty match, the aggregation's only remaining answers are the youth filter and C36.
      **(3)** `TransformationFailedException` gained a second constructor taking a `ReasonCode`. The
      classification stays fixed — every transformation failure is non-transient — but a document the
      frozen contract refuses is `OUTBOUND_CONTRACT_VIOLATION`, the code `data-model.md` already
      reserves for the C29 pre-send check: support acts on it differently, because what is wrong is
      the register rather than the hearing.
      **What was not done: `PipelineConfig` is not widened.** The pipeline's full constructor needs
      `NowSubscriptionsSource` and `RegisterSubmissionClient`, and neither exists until T066/T067 —
      so wiring the transformer bean now would take every run past the skeleton's early completion
      and into an NPE on the ports that are still null, and declaring the transformation beans
      without wiring them is the dead wiring T039 refused for the same reason. The e2e ITs start the
      real context (they carry no `test` profile), so this is not hypothetical. The final wiring is
      T068a's task; this note is here so the omission is visible rather than silent.
      **Overturned under review (2026-09-01)**: leaving it undone left the deployed graph as the
      walking skeleton, which settles every message having produced nothing, and no suite could
      say so. The wiring landed early — see T068a — with the two absent ports served by stubs
      chosen and refused at startup, and `config/PipelineCompositionTest` as the test that fails
      whenever the assembled service stops producing a register.)*

**Checkpoint**: fragment → validated outbound document, all mapper fixes pinned.

---

## Phase 6: Live adapters (US1 — payload, refdata, submission)

### Tests first ⚠️

- [x] T057 [P] [US1] `adapter/payload/HearingPayloadCacheKeyTest` +
      `adapter/payload/CachedHearingPayloadAdapterTest` — dated key first then undated twin,
      cache-then-query order, RedisException-scoped absorb (a cache outage still asks the query
      side), query asked exactly once on cache hit = never.
      *(done — 22 cases red against four seams: `HearingPayloadCacheKey`, the `HearingPayloadCache`
      and `HearingPayloadQuery` ports, and `CachedHearingPayloadAdapter`. Two decisions the seams
      settle. **(1)** `HearingPayloadQuery.fetch` answers `Optional`, and empty means the query side
      answered and held none — a 404 or the empty-bodied 200 the results context serves for a
      hearing it does not have. A read that could not be made raises instead, so the one participant
      that knows *both* sources missed is the one that classifies it, which is what makes C32's
      named test `a_double_miss_is_transient_never_silent` a test of this adapter rather than of the
      client underneath it. **(2)** The RedisException-scoped absorb is the cache adapter's, not
      this one's: from here a cache that is down and a key that is absent are the same empty read,
      and the suite pins both halves — the query side is still asked, and a failure that is not the
      cache's own is not absorbed even when it happens to be a `RedisException` that escaped its own
      handler.)*
- [x] T058 [P] [US1] `adapter/payload/ResultsQueryHearingPayloadClientTest` (WireMock) — K1/K4/K6
      twins repaired (real base URI, media type, CJSCPPUID); K2 repointed to the retry policy;
      K3 repaired (absent cjscppuid outcome asserted); **C32**: empty body / 404 ⇒
      `PayloadUnavailableException` (transient), never silence.
      *(done — 30 cases red against the `ResultsQueryHearingPayloadClient` seam. Three things the
      suite decides. **(1)** K8's `?hearingDate=` query string is not twinned and its absence is
      asserted instead: that form belongs to the `EXT_` endpoint (`index.js:63-67`), and the `INT_`
      entry this flow uses takes the hearing id alone. **(2)** C32's two silences are told apart
      rather than merged — a query side that answered and held nothing (empty body, `{}`, 404) is
      an empty answer, and it is `CachedHearingPayloadAdapter` that turns it into a transient
      failure once the cache has missed too, which the `DoubleMiss` nest proves over real HTTP; a
      read that could not be made at all (exhausted retries, a refusal, a body that is not JSON)
      raises in the client. **(3)** K3's repair is the branch it was written over: `index.js:176-178`
      drops the run when no `cjscppuid` was supplied, so the twin asserts that a run with no
      identity anywhere is a recorded transient failure and that no unauthenticated request is sent.
      `retry_taxonomy_matches_the_submission_client` is a parameterised case over 408/429/5xx, with
      its counterpart over 400/401/403/422 — the fixed C3 taxonomy, and the one T061 will hold the
      progression gateway to.)*
- [x] T059 [P] [US1] `config/LivePayloadConfigTest` — **C15**: TLS verification on
      (`TransportSecurity`); C14 retirement asserted (no legacy retry env vars bound).
      *(done — 11 cases, 4 red against the `LivePayloadConfig.cacheUri` seam. The seam is the
      configuration class carrying that one static method and no `@Bean` yet: the payload beans
      belong to T065, and declaring them here would put a throwing bean in the default-selected
      configuration every context test boots. **The two halves are not the same kind of test, and
      the difference is worth stating.** C15 is a fix, so it goes red and then green. C14 is a
      *retirement* — a claim that four settings which do nothing have nowhere to bind — and a claim
      about an absence cannot go red without first adding the thing it denies; those three cases
      pass on arrival and are characterisation, pinning the shape so the knobs cannot creep back.
      They are written two ways for that reason: the settings record's components are named exactly,
      and an environment still exporting `max-retries`, `total-retry-time-in-ms`,
      `number-of-attempts` and `reject-unauthorized` is shown to bind to a record indistinguishable
      from one that never saw them.)*
- [ ] T060 [P] [US1] `adapter/refdata/ReferenceDataNowSubscriptionsClientTest` (WireMock) —
      `on=` day derived from the fixed registerDate (C12); unanswered/5xx ⇒ transient; empty set
      returned as empty (matcher decides `no-subscriptions`).
- [ ] T061 [P] [US1] `adapter/progression/ProgressionCommandGatewayTest` (WireMock) — N34–N45:
      202-only; 400/401/403/404/422 park; non-202 2xx = `SUBMISSION_NOT_ACCEPTED`; 408/429/5xx/
      connect retry with exponential backoff; `Retry-After` delta-seconds bounded, HTTP-date
      classified not parsed; exhaustion → FAILED + `exhausted_message_id` (C1, C3).
- [ ] T062 [P] [US1] `adapter/progression/ProgressionRegisterSubmissionClientTest` — P1 twin fixed
      (URL, media type, real CJSCPPUID, digest written before send, response_code recorded).
- [ ] T063 [P] [US1] `adapter/payload/LettuceHearingPayloadCacheIT` (Redis container) — live
      GET/dated-undated forms, TLS options honoured.
- [ ] T064 [P] [US2] `application/SubmissionRedeliveryIT` (PG + WireMock) — POSTED replay skips the
      POST; PENDING/FAILED replays re-attempt.

### Implementation

- [ ] T065 [US1] Implement `adapter/payload/*` (Lettuce cache, query client, cached adapter) +
      `config/LivePayloadConfig`/`StubPayloadConfig` (green T057–T059, T063) — updates
      C14/C15/C32.
- [ ] T066 [US1] Implement `adapter/refdata/ReferenceDataNowSubscriptionsClient` + configs (green
      T060).
- [ ] T067 [US1] Implement `adapter/progression/{ProgressionCommandGateway,
      ProgressionRegisterSubmissionClient}` (green T061, T062, T064) — updates C1/C3 and the
      submission half of C29.

**Checkpoint**: all live adapters proven against WireMock/containers; every C-fix landed except
documentation-final states.

---

## Phase 7: Assembly, e2e, docs (US1/US4)

- [ ] T068 [US4] Write `HttpSurfaceTest`, `SharedObjectMapperTest`, `TelemetryPrivacyTest`
      (red; the privacy test red on a seeded violation) — actuator-only surface; no PII at INFO+.
- [ ] T068a [US4] Implement `config/PipelineConfig` + the final `application.yaml`
      (green for T068; the logging swept against the privacy test).
      *(partly done, ahead of its phase — 2026-09-01 review. `PipelineConfig` now builds the real
      bean graph: dates, the group-proceedings policy, the fragment builder, the subscription
      matcher, the contract validator and `RegisterTransformationChain` behind
      `RegisterTransformer`, with the pipeline over all five ports and the cumulative deadline
      unchanged. `config/PipelineCompositionTest` drives that graph out of a Spring context over
      doubles for the four outward ports only — schema-invalid ⇒ FAILED with no submission,
      every-recipient-dropped ⇒ COMPLETED no-subscriptions with no submission, happy path ⇒ one
      submission. The two ports whose live adapters land at T066/T067 are served meanwhile by
      `adapter/stub/StubNowSubscriptionsSource` (an empty answer, refused at startup beside a LIVE
      payload source) and `adapter/stub/StubRegisterSubmissionClient` (a refusal, chosen by the
      payload mode). What remains for this task: the final `application.yaml` and the privacy
      sweep against T068's tests.)*
- [ ] T069 [A] [US1] `e2e/CourtRegisterEndToEndIT` — message → POST(202) → COMPLETED `submitted`,
      `processed_output.status = POSTED`; one case per no-op reason; runs the quickstart sequence.
- [ ] T070 [A] [US1] `e2e/MessageAccountingIT`, `e2e/TraceabilityIT`, `e2e/FailureSignalIT` —
      the inherited accounting/tracing/failure-signal proofs.
- [ ] T071 [A] [US4] Container smoke re-verified with the finished service
      (`scripts/container-smoke.sh` locally + the CI step); compose stack documented in README.
- [ ] T072 [US3] Documentation finalisation: DEFECT-FIXES all 31 fixable rows → FIXED with pinning
      tests verified by grep; TECHNICAL_DESIGN/API_CONTRACTS/SOLUTION_BRIEF/CHANGELOG completed;
      README quickstart + Documentation table final.

---

## Phase 8: Differential audit (US5)

- [ ] T073 [US5] Build the legacy oracle: adapt the informant parity-pack CLI to the three
      court-register activities (`SetCourtRegister`, `CourtRegisterSubscriptions`,
      `OutboundCourtRegister`) with clock + `TZ=Europe/London` pinned and provenance recorded;
      record the corpus from the six base hearings × the transferable operators (shared-time,
      drop-optional-field, null-field, empty-array, duplicate-array-entry, unicode-name,
      reorder-array, subscriptions-shape/cardinality, re-share-duplicate) + the new operators
      (group-proceedings, youth-presence, court-centre completeness, multi-defendant,
      legal-entity, address-less) under `src/test/resources/differential/recorded/`.
- [ ] T074 [US5] Write `support/RegisteredDefectFixes` + `DifferentialAuditTest` (red only in the
      sense that unattributed diffs fail) — every legacy-vs-port difference derives from a
      C-number; `SCHEMA_INVALID` corpus cases asserted as classified failures.
- [ ] T075 [A] [US5] Run the audit, commit the report to
      `specs/001-court-register-port/checklists/differential-audit.md` (zero unexplained
      differences), and reconcile DEFECT-FIXES rows against observed diffs (every content-changing
      fix must actually appear in the diff set — a fix that produces no diff is evidence the
      corpus misses its shape; extend the corpus, not the claim).

---

## Dependencies & execution order

- **Phase order is strict**: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Within a phase, `### Tests first`
  tasks complete (red recorded) before their implementation tasks start.
- **Only test tasks are ever [P].** Implementation tasks touching shared production files execute
  in task-ID order under a single owner: T022 (listener/lifecycle), T039 (pipeline wiring),
  T054 → T055 → T055a → T056 (document assembly), T065–T067 (adapter configs), T068 → T068a (final wiring).
- Fixture task T053 must complete before any mapper test that needs a rebuilt fixture records its
  red run against final fixtures (mapper tests may draft against interim fixtures but the recorded
  red/green narrative uses the final set).
- T072 depends on every C-fix task; T075 depends on T072–T074.
- Reviewer gates: each phase ends with the workflow's review pass (code review + QA + contract
  validation) and an external Codex review before its final commit; findings addressed or waived
  with reasons in the commit body.

## Notes

- A guard test that unexpectedly passes on first run is not claimed as a red run — investigate,
  then record honestly.
- Golden/differential artefacts are never edited to make a test pass; a pinned behaviour changes
  only in a commit that also updates the matching DEFECT-FIXES row.
- The legacy repo is read-only throughout; C28's broken cases exist here only as their repaired
  Java twins (T040).
- Commit cadence: at minimum one commit per test-task batch and one per implementation task group;
  phase-final commit follows the review gates.
