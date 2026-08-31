# Implementation Plan: Court Register Service — full pipeline port, fix-first

**Branch**: `main` | **Date**: 2026-08-31 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-court-register-port/spec.md`

## Summary

Build the whole court-register service in one increment: the delivery machinery cloned from
`service-cp-crime-informant-register` (Service Bus consumer with explicit settlement, durable
Postgres idempotency guard, deferred Flyway, suspend-on-store-outage, the agreed health policy),
plus the full ported pipeline — payload fetch (Lettuce + results-query fallback), register
building, subscription matching, the twelve-mapper outbound document, pre-send contract validation
against the vendored Progression schemas, and a 202-only submission gateway with a real failure
taxonomy. All 34 catalogued defects are fixed and pinned; `doc/DEFECT-FIXES.md` is the ledger.

Technical approach: identical ports-and-adapters shape to the informant service. The clone is
mechanical for `inbound/`, `application/`, `persistence/`, the domain state machine and the
operability config; the new work is `pipeline/` (the ported transformation, fix-first) and
`adapter/{payload,refdata,progression}`. TDD throughout: the test-twin map (see research.md §12)
classifies every legacy Jest case as twin-as-is / twin-repointed-at-fix / repaired, and enumerates
the new tests N1–N45; test tasks strictly precede the implementation they guard.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (Gradle 9.7 wrapper; the informant build files
cloned verbatim)

**Primary Dependencies**: exactly the informant dependency set (see its plan.md table — BOM-managed
Azure SDK 1.3.8, starter-jdbc/flyway, flyway-database-postgresql, postgresql, lettuce-core,
micrometer-prometheus, actuator/OTEL/web; test: testcontainers postgresql/junit-jupiter/azure +
mssql-jdbc, wiremock-standalone 3.13.2, networknt json-schema-validator 3.0.7 test-only) plus
nothing new. The one dependency-shaped difference is data, not code: the vendored
`courtRegisterDocument/*` schemas at `criminal-court-public-model` **v17.103.13** under
`src/test/resources/contracts/progression/` (and the same schemas used by the production pre-send
validator from `src/main/resources/contracts/progression/` — see research §10).

**Storage**: PostgreSQL 16; Flyway V1 creates `processed_request` (identical to informant) and
`processed_output` (court-register columns; `UNIQUE (source, request_id)` — no fan-out).

**Testing**: JUnit Jupiter 6 + Mockito + AssertJ; WireMock for the three HTTP adapters;
Testcontainers (Service Bus emulator 1.1.2 + mssql companion + Toxiproxy, postgres:16, redis:7)
via the cloned static `support/` fixtures; `*Test` needs no Docker, `*IT` does; everything runs
under `./gradlew test`.

**Target Platform**: AKS (port 4550; local 8082); local dev via `docker-compose.yml` (Postgres,
Service Bus emulator; queue `courtregister.requests` declared in
`docker/servicebus-emulator/config.json`).

**Project Type**: single Spring Boot service, actuator-only HTTP surface.

**Performance Goals**: modest (~160 requests/day/stack); `maxConcurrentCalls` 2 (parity with the
AKS fleet's pinned Durable throttle); ready < 60 s.

**Constraints**: as the informant plan (peek-lock explicit settlement; maxDeliveryCount 5 judged by
broker delivery count; store gates readiness, queue never; processing deadline < claim lease;
single replica; no PII at INFO+; no AI attribution) plus: outbound POSTs go to **Progression**, and
`progression.add-court-register` is append-per-POST (duplicates absorbed read-side per hearing), so
shadow-running with sends enabled is forbidden.

**Scale/Scope**: the full pipeline. Out of scope per spec: cutover/flag, producer increment,
legacy-repo items, Progression leg, KEDA, alert wiring.

### Configuration (this increment)

The informant configuration table carries over with the prefix `courtregister.*` and these
differences:

| Property | Local default | Purpose |
|----------|---------------|---------|
| `courtregister.servicebus.queue-name` | `courtregister.requests` | Inbound queue |
| `courtregister.progression.base-url` | `${PROGRESSION_BASE_URL:http://localhost:8080}` | Outbound command API (replaces `results.base-url` as the submission target) |
| `courtregister.progression.system-user-id` | `${COURT_REGISTER_SYSTEM_USER_ID:}` | `CJSCPPUID` for the POST; startup refusal if absent in LIVE mode |
| `courtregister.progression.max-attempts` / `initial-backoff` / `max-backoff` / `connect-timeout` / `read-timeout` | `4` / `500ms` / `20s` / `5s` / `30s` | Submission retry policy (C1/C3) |
| `courtregister.results.base-url` + `system-user-id` | `${RESULTS_BASE_URL:…}` | Payload query **fallback only** (the results context keeps the query API) |
| `courtregister.referencedata.*` | as informant | now-subscriptions lookup |
| `courtregister.payload.redis.*` | as informant, `key-prefix: INT_`, **`ssl` verified when on (C15)** | claim-check cache |
| `courtregister.submission.validate-outbound` | `true` | Pre-send contract validation (C29); never disabled in a deployed profile |

Startup validation clones the informant `PropertiesValidator` rules, with the worst-case-fetch
arithmetic extended to include the submission policy: exactly-one credential source; deadline
< lease; lock renewal ≥ deadline + 30 s; LIVE modes require system-user-ids; stub modes refused
when a namespace is set.

## Constitution Check

*GATE: evaluated against constitution v2.0.0 (fix-first) at plan time; re-checked when the
constitution file lands in the same bootstrap phase. Where wording is still in flight the check is
against the approved v2.0.0 design (Principle I = Defect-Fix-First with Characterised Legacy).*

| # | Principle | Verdict for this increment |
|---|-----------|----------------------------|
| I | Defect-Fix-First with Characterised Legacy | **PASS — this increment is the principle's reason to exist.** All 34 catalogued fixes are pre-approved; every fix lands with its DEFECT-FIXES row and pinning test in the same commit; legacy behaviour is reproduced for everything uncatalogued and the differential audit (Phase 8) enforces it. |
| II | Test-Driven Development | **PASS** — the test matrix below names the planned test for every FR/SC and every C-fix; test tasks precede implementation tasks; red runs quoted in commit narratives; fix tests are written to fail against the legacy behaviour and pass against the fix. |
| III | Message-Contract First | **PASS with one named cross-team fact** — inbound schema owned here (court-register `$id`); outbound is Progression's frozen `add-court-register` (`additionalProperties: false`). Several fixes change outbound **values** (dates, verdict code, wording, filename) but none change the outbound **shape**; the pre-send validator enforces the frozen shape on every document. The content changes are the sign-off items DEFECT-FIXES flags for Progression/business before cutover. |
| IV | Canonical JSON In, Typed Models Out | **PASS** — hearing payloads stay `JsonNode` (platform Jackson generation, BigDecimal floats) end-to-end; outbound is typed records validated against the vendored schemas (C26 is exactly this principle applied). |
| V | SOLID with Ports and Adapters | **PASS** — same core/port shape as informant; `pipeline/` classes are pure (no I/O, no clock reads — the clock is injected); adapters own all transport. |
| VI | Explicit Failure | **PASS (inherited waiver on alert wiring)** — the whole C1/C2/C3/C32/C33 fix family is this principle; four bounded no-op completion reasons replace silence; DLQ countable; ERROR + metric on every failure path. |
| VII | Privacy in Telemetry | **PASS** — correlation set only; the register handles youth-defendant data, so the privacy tests matter more here: no names, addresses, ethnicity or dates of birth in any log or label; C25's fix changes document content, never telemetry. |
| VIII | Estate Conventions | **PASS** — cloned build/gates/CI; package root `uk.gov.hmcts.cp.courtregister`; Conventional Commits on `main`, no ticket prefix (user decision), no AI attribution. |

## Project Structure

### Documentation (this feature)

```text
specs/001-court-register-port/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Decisions with rationale and alternatives
├── data-model.md        # processed-log deltas, state machine, fragment/document entities
├── quickstart.md        # Local run + end-to-end demo
├── contracts/
│   └── README.md        # Pointers to the canonical inbound + vendored outbound contracts
├── checklists/
│   └── requirements.md  # Spec quality checklist (+ the differential audit report lands here)
└── tasks.md             # Phase-ordered TDD task list
```

### Source Code (repository root)

```text
src/main/java/uk/gov/hmcts/cp/courtregister/
├── inbound/          # ServiceBusConsumerConfig, CourtRegisterMessageListener,
│                     # DistributionCommandParser, ConsumerLifecycleController, StoreGate
├── application/      # DistributionPipeline, IdempotencyGuard,
│                     # ports: HearingPayloadSource, NowSubscriptionsSource,
│                     #        RegisterTransformer, RegisterSubmissionClient
├── domain/           # DistributionCommand, RequestStatus/OutputStatus, GuardDecision,
│                     # RunClaim, SettlementOperation, CompletionReason (5 values),
│                     # ReasonCode, RequestFingerprint, RegisterFragment, RegisterDefendant,
│                     # CourtRegisterDocument + component records, exceptions
├── adapter/
│   ├── payload/      # LettuceHearingPayloadCache, ResultsQueryHearingPayloadClient,
│   │                 # CachedHearingPayloadAdapter, HearingPayloadCacheKey
│   ├── refdata/      # ReferenceDataNowSubscriptionsClient
│   ├── progression/  # ProgressionCommandGateway (retry/Retry-After/backoff),
│   │                 # ProgressionRegisterSubmissionClient, OutboundContractValidator
│   └── stub/         # Stub payload/subscriptions sources (test/local profiles)
├── pipeline/         # RegisterTransformationChain, RegisterBuilder, DefendantContext(+Builder),
│                     # VocabularyBuilder, CourtExtractFilter, OrderedDates, HearingDates, Dates,
│                     # SubscriptionMatcher, SubscriptionRules, AggregationMapper +
│                     # {HearingVenue,Recipient,YouthDefendant,ParentGuardian,Hearing,
│                     #  ProsecutionCaseOrApplication,Offence,Result,Defendant,Address,Alias,
│                     #  Counsel}Mapper, Json, JsStrings
├── persistence/      # ProcessedLogProbe, ProcessedRequestRepository, ProcessedOutputRepository
└── config/           # CourtRegisterProperties, PropertiesValidator, JacksonConfig,
                      # DeferredFlywayMigration, ServiceBusHealthIndicator,
                      # IntakeStartupHealth(+Indicator), ProcessingMetrics, PipelineConfig,
                      # Live/Stub adapter configs
```

### Port contracts (this increment)

Same four ports as the informant service, court-register generics; none mentions an Azure, Redis,
HTTP or JDBC type.

```java
public interface HearingPayloadSource {
    JsonNode fetch(DistributionCommand command) throws PayloadUnavailableException;
}

public interface NowSubscriptionsSource {
    JsonNode subscriptionsOn(LocalDate registerDay, CallerIdentity caller)
        throws ReferenceDataUnavailableException;
}

public interface RegisterTransformer {
    TransformationResult transform(DistributionCommand command, JsonNode hearingPayload)
        throws TransformationFailedException;
    // TransformationResult = COMPLETED(reason ∈ {group-proceedings, no-defendants,
    //   no-subscriptions, no-youth-defendants}) | Document(CourtRegisterDocument)
}

public interface RegisterSubmissionClient {
    SubmissionReceipt submit(CourtRegisterDocument document, CallerIdentity caller)
        throws SubmissionFailedException;   // carries transient/non-transient classification
}
```

### Test matrix

Layers: **U** unit · **W** WireMock · **PG** Postgres `*IT` · **SB** emulator `*IT` · **R** Redis
`*IT` · **E2E** full context · **CS** container smoke. Twin/N/C references are the test-twin map's
(research §12). **Task authors and DEFECT-FIXES rows use these names verbatim.**

| Area | Planned test | Layer | Covers |
|------|--------------|-------|--------|
| Inbound contract | `DistributionCommandParserTest` | U | FR-002-inherited, Q1a field renames, Q2/N14 invalid → refusal |
| Inbound contract | `DistributionCommandSchemaCorpusTest` | U | dual-validation corpus, closed contract |
| Fingerprint | `RequestFingerprintTest` | U | canonicalisation, collision detection |
| Config | `ConfigurationValidationTest` | U | property binding + startup refusals (incl. progression policy, C29 validator never off deployed) |
| Metrics | `ProcessingMetricsTest` | U | instrument names/labels incl. completions{reason} |
| Schema | `SchemaMigrationIT` | PG | V1 facts incl. `processed_output` court-register columns + `UNIQUE (source, request_id)` |
| Guard | `IdempotencyGuardIT`, `ProcessedLogDurabilityIT`, `ClaimContentionIT`, `ClaimReclamationIT`, `StaleRunnerRejectionIT`, `CrashWindowIT`, `FailedReplayIT`, `IdempotencyCollisionIT`, `ProcessedOutputRepositoryIT` | PG | inherited FR-004…008/016/018 semantics; N15 |
| Listener | `MessageListenerSettlementTest`, `SettlementFailureEdgeTest` | U | one settlement per delivery; edges |
| Transport | `QueueSettlementIT`, `ContractValidationDeadLetterIT`, `DeliveryExhaustionIT`, `DuplicateDetectionIT`, `StoreOutageIT`, `ProlongedStoreOutageIT`, `ReadinessPolicyIT`, `StartupWithQueueDownIT`, `QueueOutageRecoveryIT` | SB/PG | inherited transport semantics on `courtregister.requests` |
| Health | `ServiceBusHealthIndicatorTest` | U | broker-silence model |
| Pipeline | `DistributionPipelineTest` | U | N1–N7 orchestration; C2 always-a-terminal-status; C32 transient; four no-op reasons N29–N33; C6 |
| Pipeline | `GroupProceedingsPolicyTest` | U | N8–N12; C7 strict boolean + WARN + reason |
| Register build | `RegisterBuilderTest` | U | S1 twin repointed (C10: `registerDate` = the true instant), three-dates trap, `courtCentreOUCode`, 18-key vocabulary attached |
| Contexts | `DefendantContextBuilderTest` | U | DC1–DC10 twins; C22 fix (eligibility = subject **and** prosecuting-authority applicant); result-level tagging |
| Vocabulary | `VocabularyBuilderTest` | U | 18 keys exact (`containsOnlyKeys`), custody/appearance/cps branches, C30 consistent emptiness |
| Court extract | `CourtExtractFilterTest` | U | RF1 twin; result+prompt level filtering |
| Dates | `DatesTest` | U | DT twins; C10 fix; C13 fix (ISO parse, catch that cannot throw); BST-boundary refdata day (C12) |
| Matching | `SubscriptionMatcherTest` | U | CS1–CS4 twins (CS1 split empty-vs-unanswered; CS4 real `ouCode` lock); N17–N19 C31 per-defendant matching |
| Matching | `SubscriptionRulesTest` | U | SS-CR1 twin, SS-CR2 repaired, included/excluded NOWS + prompt/result branches, C4 fix (court-house rule only), C5 explicit court-register branch |
| Mappers | `AggregationMapperTest` | U | O1–O3 (repointed C33 / repaired courtCentreId + C11 fixed unique fileName), venue/recipients/defendants assembly |
| Mappers | `HearingVenueMapperTest`, `RecipientMapperTest`, `YouthDefendantMapperTest`, `ParentGuardianMapperTest`, `HearingMapperTest`, `ProsecutionCaseOrApplicationMapperTest`, `OffenceMapperTest`, `ResultMapperTest`, `DefendantMapperTest`, `AddressMapperTest`, `AliasMapperTest`, `CounselMapperTest` | U | every twin/repair in the map; C8/C9 (HearingMapper), C19/C25 + custody statuses + name composition (YouthDefendant), C29 shapes + guardian fallback (ParentGuardian), C20/C21/C22 exhibits + PC1–PC7 (ProsecutionCaseOrApplication), C23/C24 + offence-level scoping pin (Offence), A1/A2 repaired + `postcode`→`postCode` (Address), absent≠empty asymmetries (Alias/Counsel), `cr_standard` + logged drops C27 (Recipient) |
| Outbound contract | `OutboundContractValidationTest` | U | C26/C29: mapped documents validate against the vendored schemas; address-less defendant/parent → named violation (N25–N27) |
| Payload adapter | `HearingPayloadCacheKeyTest`, `CachedHearingPayloadAdapterTest` | U | key forms (dated first), cache-then-query order, RedisException-scoped absorb |
| Payload adapter | `LettuceHearingPayloadCacheIT` | R | live cache reads |
| Payload adapter | `ResultsQueryHearingPayloadClientTest` | W | K twins repaired (real base URI, real 202/500 answers), C32, C3 retry classes |
| Payload security | `LivePayloadConfigTest` | U | C15: TLS verification on (`TransportSecurity` case, informant precedent) |
| Refdata adapter | `ReferenceDataNowSubscriptionsClientTest` | W | subscriptions fetch, `on=` day from the fixed `registerDate` (C12), unanswered → transient |
| Submission | `ProgressionCommandGatewayTest` | W | N34–N45: 202-only, C1 taxonomy, C3 backoff + delta-seconds `Retry-After` bounded, exhaustion |
| Submission | `ProgressionRegisterSubmissionClientTest` | U | P1 twin (URL/media type/CJSCPPUID), digest-before-send, response-code recording |
| Submission | `SubmissionRedeliveryIT` | PG+W | replay skips already-POSTED output |
| HTTP surface | `HttpSurfaceTest`, `ActuatorIntegrationTest`, `SharedObjectMapperTest` | U | actuator-only, BigDecimal pin |
| Privacy | `TelemetryPrivacyTest` | U | no PII at INFO+ (youth-defendant fields named explicitly) |
| E2E | `CourtRegisterEndToEndIT` | E2E | SC-103 happy path → POSTED; each no-op reason observable |
| E2E | `MessageAccountingIT`, `TraceabilityIT`, `FailureSignalIT` | E2E | SC-104/SC-106 inherited accounting/tracing |
| Differential | `ComparatorContractTest`, `JsonParityTest` | U | the comparator itself (vendored vectors) |
| Differential | `DifferentialAuditTest` | U | SC-105: recorded legacy corpus vs Java pipeline; every diff maps to a C-number via `RegisteredDefectFixes` |
| Smoke | Container smoke | CS | image starts ready < 60 s |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Alert wiring waived (inherited informant waiver) | Alert rules live in the platform observability stack with the operability story; metrics and ERROR logs ship now | Wiring alerts here couples the port to config this repo cannot test |
| Production pre-send validator reads schemas the *test* tree also vendors | C29 demands the frozen contract at runtime; duplicating the files main/test keeps test-only tooling (networknt) off the runtime path decision open until implementation measures it | A runtime schema-validator dependency is acceptable if measurement shows it; the task notes record the choice |
