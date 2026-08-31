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
- [ ] T026 [P] [US3] Write `pipeline/CourtExtractFilterTest` (red) — RF1 twin (result+prompt
      levels, `courtExtract` `'Y'`/`'y'` fallback).
- [ ] T027 [P] [US3] Write `pipeline/RegisterBuilderTest` (red; seam: `RegisterBuilder`,
      `RegisterFragment`) — S1 twin repointed (registerDate = true instant; hearingDate
      `2020-01-20T00:00:00Z`; hearingId; 1 register defendant, 4 results, none publishedForNows);
      `courtCentreId` (correct spelling) + `courtCentreOUCode` populated; 18-key vocabulary
      attached per defendant; empty context list yields an empty fragment (C6 path).
- [ ] T028 [P] [US3] Write `pipeline/SubscriptionRulesTest` (red; seam: `SubscriptionRules`) —
      SS-CR1 twin; SS-CR2 repaired (the local vocabulary actually drives the assertion);
      included/excluded NOWS + prompt/result inc-exc branches; **C4 fix**: the court-centre OU
      code feeds the court-house rule only (an `informantCode` equal to the OU code no longer
      matches — fails vs legacy); **C5**: explicit court-register branch —
      `isCourtRegisterSubscription` subscriptions match via `selectedCourtHouses`;
      `matchCpsProsecuted` not reproduced; **C30 fix at matcher level**:
      `major_creditor_flags_never_match_a_court_register` — all three major-creditor flags
      require a non-empty applicable list (fails vs legacy, where `anyMajorCreditor` is
      vacuously true on `[]`).
- [ ] T029 [P] [US3] Write `pipeline/SubscriptionMatcherTest` (red; seam: `SubscriptionMatcher`) —
      CS2/CS3 twins; CS4 with a real `ouCode` lock; CS1 split: empty answer ⇒ no matches
      (`no-subscriptions` downstream), unanswered ⇒ `ReferenceDataUnavailableException`;
      **C31 fix (N17–N19)**: adult-first/youth-second hearing matches a youth-vocabulary
      subscription (fails vs legacy `[0]`-only); judicialResults still collected across all
      defendants (N18).
- [ ] T030 [P] [US4] Write `application/GroupProceedingsPolicyTest` (red; seam: policy type) —
      N8–N12 under the strict-boolean rule: `true` skips with reason; `false`/`null`/absent
      proceed; `"false"`/`"true"`/`1` proceed with WARN + metric (fails vs legacy loose `==`).
- [ ] T031 [P] [US1] Write `application/DistributionPipelineTest` (red) — N1–N7: stage sequence and
      argument shapes; C32 cache+fallback miss ⇒ transient; C2 a throwing stage always records a
      terminal status; the four no-op reasons N29–N33 distinguishable; C6 empty fragment ⇒
      `no-defendants`; submission invoked once per document, zero on no-op.

### Implementation (serialised where files are shared)

- [ ] T032 [US3] Implement `pipeline/Dates`, `pipeline/OrderedDates`, `pipeline/HearingDates`
      (green T023) — updates DEFECT-FIXES C10/C12/C13.
- [ ] T033 [US3] Implement `pipeline/DefendantContext(+Builder)`, `pipeline/Json`,
      `pipeline/JsStrings` (green T024) — updates C22.
- [ ] T034 [US3] Implement `pipeline/VocabularyBuilder` (green T025) — the vocabulary half of C30.
- [ ] T035 [US3] Implement `pipeline/CourtExtractFilter` (green T026).
- [ ] T036 [US3] Implement `pipeline/RegisterBuilder` + `domain/RegisterFragment` (green T027) —
      updates C6 (and the C26 spelling on the fragment).
- [ ] T037 [US3] Implement `pipeline/SubscriptionRules` (green T028) — updates C4/C5/C30.
- [ ] T038 [US3] Implement `pipeline/SubscriptionMatcher` (green T029) — updates C31.
- [ ] T039 [US1] Implement the group-proceedings policy + wire `DistributionPipeline` stages
      (green T030, T031) — updates C2/C7/C32/C33 (pipeline half).

**Checkpoint**: a hearing payload becomes a matched fragment with fixed dates, eligibility,
vocabulary and matching semantics.

---

## Phase 5: The outbound document (US3 — twelve mappers + validation)

- [ ] T039a [US3] Shared compile-safe seams for the mapper phase: the `domain/CourtRegisterDocument`
      record family (document, hearingVenue, defendant, parentGuardian, hearing, caseOrApplication,
      offence, result, recipient, alias, counsel, address) with signatures matching the vendored
      schemas, plus twelve mapper skeletons throwing `UnsupportedOperationException` — so T040–T051
      stay genuinely [P] (no two test tasks create the same seam file). Infrastructure task under
      the red-run convention's seam clause; no assertions of its own.

### Tests first ⚠️ (all [P] — one file each; seams provided by T039a)

- [ ] T040 [P] [US3] `pipeline/AddressMapperTest` — A1/A2 repaired: absent input ⇒ absent output
      (not `[]`); address1–5 pass-through; `postcode`→`postCode`.
- [ ] T041 [P] [US3] `pipeline/AliasMapperTest` + `pipeline/CounselMapperTest` — twins plus the
      pinned asymmetry: aliases `[]`⇒`[]`/absent⇒absent; counsels absent-or-empty⇒absent; name
      composition incl. middle-name-absent; unmapped `legalEntityName` asserted absent.
- [ ] T042 [P] [US3] `pipeline/DefendantMapperTest` — D1–D4 twins + explicit case-first,
      applications-only-if-empty precedence.
- [ ] T043 [P] [US3] `pipeline/HearingMapperTest` — **C8/C9 fixed**: multi-entry attendance selects
      the correct defendant without mutating input (fails vs legacy assignment); date-compatible
      `defendantPresent` semantics per the kernel's orderedDate rule; all three
      `defendantAppearanceDetails` renderings; absent attendance ⇒ present=false; empty array ⇒
      guarded, not a TypeError (C19-family guard).
- [ ] T044 [P] [US3] `pipeline/HearingVenueMapperTest` — HV1 twin + address body (postCode case),
      lja-absent, courtCentre-absent guarded failure.
- [ ] T045 [P] [US3] `pipeline/OffenceMapperTest` — OF1 twin repaired (real indicatedPlea,
      allocationDecision, convictionDate asserted); **C23 fix**: `verdictCode =
      verdictType.verdictCode ?? verdictType.categoryType` (`"1234"`, fails vs legacy
      `"desc1234"`; plus `verdict_code_falls_back_to_category_type_when_absent` for the
      code-less live-payload shape — never the description); **C24 fix**: wording
      joined with `\n`, absent legislation ⇒ wording alone (no `####`, no `undefined`); offence-level
      result scoping pinned with two offence ids (the legacy-correct behaviour kept).
- [ ] T046 [P] [US3] `pipeline/ParentGuardianMapperTest` — PG1 twin (real address5), guardian
      fallback, no-parent ⇒ absent, non-string role guarded.
- [ ] T047 [P] [US3] `pipeline/ProsecutionCaseOrApplicationMapperTest` — PC1–PC7 twins (correct
      3-arg construction), SNI-9005 case-skip parity, **C20/C21 fixes**: absent/unmatched
      application and personDefendant-less own-record are guarded skips with WARN + an
      `anomaly_summary` bounded count (`unresolvable-application:1`) — not TRANSFORMATION_FAILED
      (fail vs legacy TypeError), C22 exhibit re-asserted at mapper level, dead methods not reproduced
      (C26).
- [ ] T048 [P] [US3] `pipeline/RecipientMapperTest` — R1–R4 twins + `cr_standard` default pinned;
      **C27 fix**: letter-delivery and missing-email drops WARN-logged and counted in
      `anomaly_summary` (`letter-delivery-dropped:n`, `recipient-missing-email:n`,
      `recipient-not-for-distribution:n`) — drop itself preserved; emailAddress2 carried.
- [ ] T049 [P] [US3] `pipeline/ResultMapperTest` — RS1 twin + null/empty ⇒ absent.
- [ ] T050 [P] [US3] `pipeline/YouthDefendantMapperTest` — YD1 twin repaired (real nationality,
      three-part name, address5); **C19 fix**: legal-entity/unmatched defendant ⇒ guarded skip
      recorded as `anomaly_summary` `unresolvable-youth-defendant:1` + the anomaly metric, not
      TRANSFORMATION_FAILED (fails vs legacy TypeError); **C25 fix**: ethnicity observed-else-self-defined
      (three branches); real + mixed `postHearingCustodyStatus`; defence-counsel filtering.
- [ ] T051 [P] [US3] `pipeline/AggregationMapperTest` — O1/O2 repointed (reasons), O3 repaired
      (concrete `courtCentreId`; **C11 fixed fileName** exact string incl. hearingId; venue,
      recipients, youth-only defendants asserted concretely); C33 wiring.
- [ ] T052 [P] [US3] `adapter/progression/OutboundContractValidationTest` — N25–N27: address-less
      defendant/parent and empty address1 are named violations against the vendored schemas; a
      valid document passes; the violating path appears in the bounded reason (C29, C26).
- [ ] T053 [P] [US3] Author the Java fixture set under `src/test/resources/fixtures/` — legacy
      fixtures copied byte-identical where sound; the seven bad-vocabulary fixtures rebuilt with
      the 18-key set; the six new base hearings (complete courtCentre with code; surviving youth;
      group proceedings; adult-first multi-defendant; non-prosecuting-authority application;
      address-less youth). [A] — fixture authoring, no red run; provenance notes in the fixture
      README.

### Implementation (serialised: T054 → T055 → T056)

- [ ] T054 [US3] Implement the twelve mappers + `domain/CourtRegisterDocument` records (green
      T040–T051) — updates C8/C9/C11/C19/C20/C21/C23/C24/C25/C27 and the mapper half of C26/C33.
- [ ] T055 [US3] Implement `adapter/progression/OutboundContractValidator` (green T052) — updates
      C29 (validation half) and C26 (schema authority).
- [ ] T055a [US3] Write `pipeline/RegisterTransformationChainTest` (red; seam:
      `RegisterTransformationChain`) — fragment → matched subscriptions → validated document
      through the chained stages; a no-op at each stage surfaces its distinct reason; stage
      exceptions classify, never swallow.
- [ ] T056 [US3] Implement `pipeline/RegisterTransformationChain` + `pipeline/AggregationMapper`
      wiring into `RegisterTransformer` (green T055a and T051 chain cases;
      `DistributionPipelineTest` end-to-end unit path now green).

**Checkpoint**: fragment → validated outbound document, all mapper fixes pinned.

---

## Phase 6: Live adapters (US1 — payload, refdata, submission)

### Tests first ⚠️

- [ ] T057 [P] [US1] `adapter/payload/HearingPayloadCacheKeyTest` +
      `adapter/payload/CachedHearingPayloadAdapterTest` — dated key first then undated twin,
      cache-then-query order, RedisException-scoped absorb (a cache outage still asks the query
      side), query asked exactly once on cache hit = never.
- [ ] T058 [P] [US1] `adapter/payload/ResultsQueryHearingPayloadClientTest` (WireMock) — K1/K4/K6
      twins repaired (real base URI, media type, CJSCPPUID); K2 repointed to the retry policy;
      K3 repaired (absent cjscppuid outcome asserted); **C32**: empty body / 404 ⇒
      `PayloadUnavailableException` (transient), never silence.
- [ ] T059 [P] [US1] `config/LivePayloadConfigTest` — **C15**: TLS verification on
      (`TransportSecurity`); C14 retirement asserted (no legacy retry env vars bound).
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
