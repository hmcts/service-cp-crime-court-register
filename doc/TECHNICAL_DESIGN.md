# Technical Design — service-cp-crime-court-register

> **Status: as built.** The full pipeline port is implemented and green under the full quality
> gates (phases 1–7 of `specs/001-court-register-port/tasks.md`). What remains of the increment is
> the differential audit against the recorded legacy oracle (Phase 8, tasks T073–T075); the
> content-changing fixes remain gated on sign-off before cutover, per the register.

## Overview

A message-driven Spring Boot service that replaces the court-register Azure Durable Functions
pipeline. One command per resulted hearing arrives on `courtregister.requests`; the service fetches
the hearing payload (Redis claim-check, results-query fallback), builds the single youth-defendant
register fragment, matches Youth Offending Team subscriptions via reference data, maps the
aggregation document and POSTs `progression.add-court-register`. Progression's nightly generation,
PDF rendering and e-mail leg is unchanged. Of the thirty-four catalogued legacy defects, the
thirty-one that live in this service are fixed — as are the two found in review and appended
(C35, C36) — every one pinned by a named test ([DEFECT-FIXES.md](DEFECT-FIXES.md)); C18/C28/C34
are externally-owned remediations tracked to conclusion before cutover; legacy behaviour is the
oracle for everything not catalogued.

## The change in one picture

```
cpp-context-results (producer)                 courtregister-service (this repo, AKS)
┌──────────────────────────────────┐           ┌─────────────────────────────────────────┐
│ HearingResultedEventProcessor    │ ASB queue │ inbound/     ServiceBusProcessorClient   │
│  · Redis INT_ claim-check (as-is)│ courtregi │              peek-lock, explicit settle  │
│  · Event Grid publish (as-is     │ ster.     │ ─────────────────────────────────────── │
│    until cutover)                │ requests  │ application/ IdempotencyGuard            │
│  · NEW: queue publisher gated on │ (+DLQ)    │              (source, requestId)         │
│    the CourtRegisterService flag │──────────▶│              DistributionPipeline        │
└──────────────────────────────────┘           │ ─────────────────────────────────────── │
                                               │ pipeline/    RegisterBuilder             │
  Redis INT_{hearingId}_{hearingDay}_result_ ─▶│              SubscriptionMatcher         │
   (24 h TTL; miss ⇒ results-query fallback) ─▶│              AggregationMapper (12)      │
  referencedata now-subscriptions?on={day} ───▶│              OutboundContractValidator   │
                                               │ ─────────────────────────────────────── │
                                               │ persistence/ processed_request           │
                                               │              processed_output            │
                                               └───────────────────┬─────────────────────┘
                                                                   │ POST add-court-register
                                                                   ▼ (202 and nothing else)
                                               cpp-context-progression (unchanged)
                                                 → 18:00 sweep → PDF → email
```

The legacy half this replaces — `CourtRegisterEventGridTrigger` → `CourtRegisterOrchestrator` →
activities — is retired at cutover; everything right of the POST is untouched.

## Architecture — ports and adapters pipeline

Core use-case (`DistributionPipeline`) depends on four ports only: `HearingPayloadSource`,
`NowSubscriptionsSource`, `RegisterTransformer` (pure — no I/O, no clock, no randomness),
`RegisterSubmissionClient`. Adapters: Lettuce + results-query (payload), reference data client
(subscriptions), progression gateway (submission), stubs for local/test profiles. The application
layer never imports ASB, Redis, HTTP or JDBC types.

### Composition wiring

`config/PipelineConfig` is the one place the graph is assembled, and every bean it declares is
declared as its port type. The four ports that reach outside the service are not declared there at
all — each is served by a live/stub configuration pair chosen by a mode property
(`LivePayloadConfig`/`StubPayloadConfig` on `courtregister.payload.mode`;
`LiveSubscriptionsConfig`/`StubSubscriptionsConfig` on `courtregister.referencedata.mode`;
`LiveSubmissionConfig`/`StubSubmissionConfig` on the payload mode, since a stub run never reaches
the POST; `ProcessedLogConfig` for the store) — and `PropertiesValidator` refuses any stub wherever
the deployed credential source (`servicebus.namespace`) is in use. The transformation is wired as
the real `RegisterTransformationChain` (builder → matcher → aggregation → contract validation), and
the pre-send contract validator is wired **unconditionally**: `courtregister.submission.validate-outbound`
is a startup rule (refused `false` on the deployed credential source), not a runtime switch — no
bean reads it on the hot path, because a wiring that honoured it would put C29's blind spot back
behind one property.

## Package Structure

```
uk.gov.hmcts.cp.courtregister
├── inbound/       ServiceBusConsumerConfig, CourtRegisterMessageListener (one settlement per
│                  delivery), DistributionCommandParser, ConsumerLifecycleController (gated start,
│                  suspend-on-store-outage), StoreGate
├── application/   DistributionPipeline (the core use-case), IdempotencyGuard,
│                  GroupProceedingsPolicy (C7), RunAnomalies (C19/C20/C27 bounded counts),
│                  the four ports (HearingPayloadSource, NowSubscriptionsSource,
│                  RegisterTransformer, RegisterSubmissionClient), TransformationResult,
│                  RegisterSubmission, SubmissionReceipt
├── domain/        DistributionCommand, CallerIdentity, RequestStatus/RequestOutcome,
│                  CompletionReason (5 values), ReasonCode (bounded failure codes),
│                  GuardDecision, RunClaim, SettlementOperation, DeadLetterReason,
│                  DeliveryIdentity, RequestFingerprint, RegisterFragment, RegisterDefendant,
│                  RegisterVocabulary, CourtRegisterDocument + 11 component records,
│                  ContractViolation, ClassifiedFailure, the classified exceptions
├── pipeline/      (pure — no I/O, no clock) RegisterTransformationChain, RegisterBuilder,
│                  DefendantContext(+Builder), VocabularyBuilder, CourtExtractFilter,
│                  Dates/OrderedDates/HearingDates, SubscriptionMatcher, SubscriptionRules,
│                  AggregationMapper + the 12 mappers (HearingVenue, Recipient, YouthDefendant,
│                  ParentGuardian, Hearing, ProsecutionCaseOrApplication, Offence, Result,
│                  Defendant, Address, Alias, Counsel), Json + JsStrings (JS-semantics shims)
├── adapter/
│   ├── http/         RetryPolicy + RetryPause — the ONE retry policy all three HTTP clients hold
│   │                 (C3): taxonomy (connect/IO, 5xx, 429, 408), bounded doubling back-off,
│   │                 Retry-After in delta-seconds only, honoured on every retryable answer
│   ├── payload/      LettuceHearingPayloadCache, ResultsQueryHearingPayloadClient,
│   │                 CachedHearingPayloadAdapter (cache-then-query, RedisException-scoped
│   │                 absorb — C32), HearingPayloadCacheKey (dated form first)
│   ├── refdata/      ReferenceDataNowSubscriptionsClient
│   ├── progression/  ProgressionCommandGateway (202-only, deadline-aware retries),
│   │                 ProgressionRegisterSubmissionClient (fenced processed_output claim),
│   │                 OutboundContractValidator (C29 pre-send validation)
│   └── stub/         Stub payload / subscriptions / submission sources (never reachable on the
│                     deployed credential source)
├── persistence/   ProcessedRequestRepository, ProcessedOutputRepository, ProcessedLogProbe
└── config/        CourtRegisterProperties, PropertiesValidator (startup refusals + run-budget
                   arithmetic), JacksonConfig (BigDecimal floats), DeferredFlywayMigration,
                   ServiceBusHealthIndicator, IntakeStartupHealth(+Indicator), ProcessingMetrics,
                   PipelineConfig + the Live/Stub adapter configs
```

## Processing State Machine

`RECEIVED → RETRYING → COMPLETED | FAILED` on `processed_request`, claim-protocol columns for the
single-runner guarantee, and **five** bounded completion reasons: `submitted`, `group-proceedings`,
`no-defendants`, `no-subscriptions`, `no-youth-defendants` — the last four replacing the legacy's
undifferentiated `Success: true` (defect-fix C33). Success on the POST is 202 and nothing else.

The run decides in this order (each leg a recorded state, never silence):

```
delivery → guard: terminal row already?  ── COMPLETED ⇒ ack, no run · FAILED ⇒ re-park (replay
        │                                    under a fresh messageId only) · fingerprint mismatch
        │                                    ⇒ dead-letter, row untouched (C17)
        ├─ claim acquired → payload: cache → query fallback
        │     · both miss ⇒ RETRYING PAYLOAD_UNAVAILABLE (transient, C32)
        │     · query refused (4xx bar 404/408/429) ⇒ FAILED PAYLOAD_READ_REFUSED (parked at once)
        ├─ the payload's group-proceedings flag === true (strict)? ⇒ COMPLETED group-proceedings
        │     (C7; a non-boolean value is WARN + metric, never suppression)
        ├─ reference data: unanswered ⇒ RETRYING REFERENCE_DATA_UNAVAILABLE ·
        │     refused ⇒ FAILED REFERENCE_DATA_REFUSED
        ├─ transformation (pure): no defendants ⇒ COMPLETED no-defendants (C6) · nothing matched ⇒
        │     COMPLETED no-subscriptions (C33/C36) · youth filter left nobody ⇒ COMPLETED
        │     no-youth-defendants · fatal error ⇒ FAILED TRANSFORMATION_FAILED (C13) · document
        │     the vendored contract refuses ⇒ FAILED OUTBOUND_CONTRACT_VIOLATION (C29)
        └─ submission: fenced processed_output row claimed (digest + anomaly_summary) BEFORE the
              POST · 202 ⇒ COMPLETED submitted · other 2xx ⇒ FAILED SUBMISSION_NOT_ACCEPTED ·
              parking 4xx ⇒ FAILED SUBMISSION_REJECTED · transient exhausted in-run ⇒ RETRYING
              SUBMISSION_TRANSIENT · last permitted delivery ⇒ FAILED DELIVERY_LIMIT_EXHAUSTED
              (+ exhausted_message_id)
```

**`PROCESSING_DEADLINE_EXCEEDED` (transient) has two origins.** The run reads what is left of its
budget before the transformation, before the send and before every outcome write
(`DistributionPipeline`); and the submission transport is handed the instant the budget ends and
reads it **before every attempt and before every wait** — the back-off's and a server-supplied
`Retry-After` alike (`ProgressionCommandGateway`). A wait that would end after the deadline is
refused rather than shortened; the delivery is abandoned, not parked, and the redelivery gets a
whole fresh budget. It is deliberately not `SUBMISSION_TRANSIENT`: a rise in this code is a
capacity signal about this service, a rise in that one is a signal about progression.

## Queue and message

`courtregister.requests` (+ DLQ): `maxDeliveryCount` 5, duplicate detection on (immutable at
creation), lock PT1M with SDK auto-renewal validated to outlive the processing deadline. Contract
in [API_CONTRACTS.md](API_CONTRACTS.md).

## Idempotency

`(source, requestId)` composite key on the processed log; SHA-256 request fingerprint; a duplicate
delivery of a request already in a terminal state is settled from the recorded status without
reprocessing — acknowledged if it completed, re-parked if it failed, and nothing published either
way, because this service has no status channel; fingerprint mismatch dead-letters without
overwriting (defect-fix C17). `processed_output` carries at most one row per command — the court register has
no fan-out — keyed `UNIQUE (source, request_id)` with the court centre, register date and file
name as descriptive columns.

**The output row is a fence, not a receipt.** The submission adapter serialises the document and
claims the `processed_output` row **before** anything is sent, carrying `request_digest` (SHA-256
of exactly the bytes about to go) and `anomaly_summary` (the bounded reason-code counts of what
the register was assembled without — C19/C20/C27); the outcome, `response_code` included, is
written before any failure is rethrown. The log therefore never shows a submission in flight that
nothing will finish, a replay of an already-POSTED row skips the send and completes `submitted`,
and a settlement the log refuses (an overlapping delivery got there first, or the claim was
reclaimed) hands the delivery back transient rather than trusting this runner's verdict over the
recorded one.

## Porting map (JS → Java)

Design doc §5 governs. `pipeline/` classes are one-per-legacy-activity/mapper so the Jest → JUnit
twin mapping stays 1:1; JS-semantics shims (`Json`, `JsStrings`) carry the truthiness/trim rules.

| Legacy (`$DF`) | This service |
|---|---|
| `CourtRegisterEventGridTrigger` / `CourtRegisterQueueTrigger` | `inbound/` (listener + parser; the triggers themselves are retired — the producer publishes to the queue) |
| `CourtRegisterOrchestrator/index.js` | `application/DistributionPipeline` + `GroupProceedingsPolicy` |
| `HearingResultedCacheQuery` | `adapter/payload/` (Lettuce cache + results-query fallback behind `CachedHearingPayloadAdapter`) |
| `SetCourtRegister` + `NowsHelper/RegisterFragmentService` | `pipeline/RegisterBuilder` + `DefendantContextBuilder` + `Dates`/`OrderedDates`/`HearingDates` |
| `NowsHelper/VocabularyService` | `pipeline/VocabularyBuilder` (18-key vocabulary) + `CourtExtractFilter` |
| `CourtRegisterSubscriptions` + `NowsHelper/SubscriptionsService` | `pipeline/SubscriptionMatcher` + `SubscriptionRules` |
| `NowsHelper/ReferenceDataService` | `adapter/refdata/ReferenceDataNowSubscriptionsClient` |
| `OutboundCourtRegister/index.js` | `pipeline/AggregationMapper` + `adapter/progression/*` |
| `OutboundCourtRegister/CourtRegisterRequest/Mappers/*` (12) | `pipeline/{HearingVenue,Recipient,YouthDefendant,ParentGuardian,Hearing,ProsecutionCaseOrApplication,Offence,Result,Defendant,Address,Alias,Counsel}Mapper` |
| `CommonUtility/AxiosRetryWrapper` | `adapter/http/RetryPolicy` — one object held by all three HTTP clients (C3), not a wrapper cloned per call site |
| `CourtRegisterRequest/Models/*` (drifted) | `domain/CourtRegister*` typed records validated against the vendored schemas (C26/C29) |

## Quality gate — defect-fix-first with a differential audit

Every legacy Jest case has a JUnit twin (as-is, repointed at a registered fix, or repaired where
the legacy test was vacuous); the in-service fixes are pinned by the tests named in
[DEFECT-FIXES.md](DEFECT-FIXES.md) — all verified present by grep at documentation finalisation
(T072); a differential audit against the recorded legacy oracle (Phase 8) must attribute **every**
output difference to a C-number — an unattributed difference is a port defect.

## Configuration

`courtregister.*` typed properties (`CourtRegisterProperties`) with startup refusals
(`PropertiesValidator`): exactly one Service Bus credential source; stub modes refused wherever the
deployed credential source is in use (and a stub subscriptions source refused beside a live payload
source); LIVE modes require their `system-user-id`; `submission.validate-outbound` refused `false`
on the deployed credential source; deadline < lease; lock renewal ≥ deadline + 30 s; and the
**run-budget rule** — worst-case payload fetch + now-subscriptions read + submission + a fixed 30 s
margin must be strictly shorter than the processing deadline, with `Retry-After` budgeted at
`max-backoff` per wait since a remote service may ask for the ceiling every time.

| Key (under `courtregister.`) | Default | Purpose |
|---|---|---|
| `consumer.enabled` | `true` | Master intake switch (off in the `test` profile) |
| `servicebus.connection-string` / `servicebus.namespace` | emulator string / unset | Exactly one; `namespace` is the deployed credential source (workload identity) |
| `servicebus.queue-name` | `courtregister.requests` | Inbound queue |
| `servicebus.max-concurrent-calls` | `2` | Parity with the pinned Durable Functions throttle |
| `servicebus.max-delivery-count` | `5` | Mirrors the broker queue; recognises the final permitted delivery |
| `servicebus.max-auto-lock-renew-duration` | `5m` | ≥ deadline + 30 s renewal margin, validated |
| `servicebus.health-staleness` | `60s` | Broker-silence model for the (non-readiness) health indicator |
| `claim.lease` / `claim.processing-deadline` | `5m` / `4m` | Claim protocol; deadline strictly < lease |
| `store.probe-interval` | `10s` | Gated start + outage resume probing |
| `progression.base-url` / `progression.system-user-id` | `${PROGRESSION_BASE_URL:…}` / `${COURT_REGISTER_SYSTEM_USER_ID:}` | Submission target + `CJSCPPUID`; identity required in LIVE mode |
| `progression.max-attempts` / `initial-backoff` / `max-backoff` / `connect-timeout` / `read-timeout` | `4` / `500ms` / `5s` / `5s` / `10s` | Submission retry policy (C1/C3) |
| `progression.headers` | empty map | Optional static headers (mesh authorisation without a code change) |
| `submission.validate-outbound` | `true` | C29 — a startup rule, not a runtime switch |
| `results.base-url` / `results.system-user-id` | `${RESULTS_BASE_URL:…}` / as progression | Payload query **fallback only** |
| `referencedata.mode` | `LIVE` | LIVE \| STUB (stub refused deployed, and beside a live payload source) |
| `referencedata.base-url` / `system-user-id` / `headers` | `${REFERENCEDATA_BASE_URL:…}` / falls back to the service identity / empty | now-subscriptions lookup |
| `referencedata.max-attempts` / `initial-backoff` / `max-backoff` / `connect-timeout` / `read-timeout` | `3` / `1s` / `2s` / `5s` / `10s` | The same shared `RetryPolicy` keys |
| `payload.mode` | `LIVE` | LIVE \| STUB (stub refused on the deployed credential source) |
| `payload.redis.host` / `port` / `password` / `ssl` / `key-prefix` / `connect-timeout` / `command-timeout` | `localhost` / `6379` / unset / `false` / `INT_` / `5s` / `5s` | Claim-check cache; TLS **verified** when on (C15); retired legacy retry knobs bind nowhere (C14) |
| `payload.fallback.max-attempts` / `initial-backoff` / `max-backoff` / `connect-timeout` / `read-timeout` | `3` / `1s` / `2s` / `5s` / `10s` | The same shared `RetryPolicy` keys |
| `stub.payload-failure-mode` | `NONE` | NONE \| TRANSIENT — test/local fault injection, never a production surface |

Note the renames from the P0 plan: `payload.fallback.retry-interval` and
`referencedata.retry-interval` no longer exist — all three HTTP clients carry the same
`initial-backoff`/`max-backoff` pair because they hold the same `RetryPolicy` object (C3, amended
2026-09-01); `progression.max-backoff` dropped from 20 s to 5 s and the three read timeouts from
30 s to 10 s so the whole run fits one claim (see `doc/CHANGELOG.md`).

## Testing Strategy

`*Test` = no Docker (unit / WireMock / test-profile Spring context); `*IT` = Testcontainers
(Postgres 16, Service Bus emulator + Toxiproxy, Redis 7) via static per-JVM fixtures. Fixtures are
copied from the legacy repo where sound and rebuilt where the twin map found them stale (the 18-key
vocabulary, complete court centres). Everything runs under `./gradlew test`.

The suites in `e2e/` all drive real messages from the emulator queue through the assembled context
against a real store, and they divide into two kinds — the distinction matters when reading what one
of them proves:

- **Edge-level (queue to socket).** `CourtRegisterEndToEndIT`, `PayloadSourceEndToEndIT`,
  `RegisterAddressingEndToEndIT`, `SubmissionOutcomeEndToEndIT` and `RunDeadlineEndToEndIT` start
  `RegisterStackSupport`: a Redis container for the claim-check payload and one WireMock server
  answering the results-query, reference-data and progression contracts. Nothing between the queue
  and the socket is doubled, so these are the suites that can count what a delivery cost the world
  outside the pod — including the negative that a duplicate posts no second register.
- **Component-level (real broker and store, stubbed outward ports).** `MessageAccountingIT`,
  `RequestDedupeIT`, `DuplicateDetectionIT`, `DeliveryExhaustionIT`, `ContractValidationDeadLetterIT`,
  `QueueOutageRecoveryIT`, `ReadinessPolicyIT`, `TraceabilityIT`, `FailureSignalIT`, `StoreOutageIT`,
  `ProlongedStoreOutageIT` and `StartupWithQueueDownIT` select the stub payload source — which
  selects the stub subscriptions source and the stub submission client with it — and some also mock
  the payload port outright, because a request that fails on cue or stays in flight on a latch is not
  something a cache can be asked for. Their subject is settlement, the processed log, readiness and
  accounting; they reach no socket and prove nothing about one. `QueueSettlementIT` is narrower
  still by design: a real broker under a mocked pipeline, so that what a `complete`, an `abandon` and
  a `deadLetter` mean to the queue is proven without booting a context at all.

## Observability

Micrometer → Prometheus + App Insights agent; JSON logs with `source`/`requestId`/`hearingId`/
`hearingDay` in MDC and **no defendant PII at INFO or above** (asserted by `TelemetryPrivacyTest`,
which also refuses a shipped log level below INFO); completion-reason distribution metric;
`courtregister_transformation_anomalies_total{reason}` for the C19/C20/C27 guarded skips;
submission-to-row reconciliation is the C29 detector. DLQ depth from Azure Monitor.

## Security

Workload identity + Key Vault CSI; verified TLS to Redis (defect-fix C15); no secrets in the repo,
enforced by the secrets-scanner workflow (C16); the `CJSCPPUID` system identity injected, required
at startup.

## Deployment

GitHub CI → ADO pipeline → ACR; Flux + `springboot-app` chart, port 4550, single replica with
`maxConcurrentCalls` 2 (the informant precedent — the flow is ~160 commands/day and ordering is
protected by the claim protocol, not the replica count). Readiness gates on the store and the gated
start, never the broker; a broker blip must not roll the pods.

## Cutover and rollback

One lever: the `CourtRegisterService` App Configuration flag read by the producer's queue publisher
and both legacy triggers (the trigger wiring is legacy-repo work, defect-fix C18). Never
shadow-run with sends enabled — `progression.add-court-register` is not idempotent; duplicates are
absorbed for generation by progression's latest-per-hearing sweep but still land as rows.
Pre-cutover assurance beyond the differential audit — replaying recorded production-shaped traffic
through the assembled service — is the migration design doc's §6.5 (replay tooling, adapted from
the informant pack); the content-changing fixes additionally carry per-row sign-off gates in
[DEFECT-FIXES.md](DEFECT-FIXES.md).
