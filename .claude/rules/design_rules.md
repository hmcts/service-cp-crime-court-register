# Architecture & Domain Rules

This service is a **message-driven pipeline with a scheduled second leg**, not a REST application.
There is no controller layer and no public HTTP API — actuator only. Operational actions are a CLI
baked into the image. Everything below assumes that shape.

Since increment 002 the service owns **both halves** of the court-register flow: the intake half
ported from the function app, and the downstream half absorbed from `cpp-context-progression`.

## The two legs

```
ASB queue courtregister.requests
   │  (peek-lock delivery)
   ▼
CourtRegisterMessageListener            inbound adapter — parse + settle ONLY
   ▼
DistributionPipeline                    application service — the use case, no I/O of its own
   ├─▶ IdempotencyGuard                 (source, requestId) processed-log — has this been done?
   ├─▶ HearingPayloadSource      «port» fetch hearing payload   (Redis INT_ keys → results-query fallback)
   ├─▶ NowSubscriptionsSource    «port» reference-data now-subscriptions for the register date
   ├─▶ RegisterTransformer       «port» fragment → subscription matching → the single aggregation
   ├─▶ RegisterDocumentValidator «port» the frozen schemas, enforced at the write (fix C29)
   └─▶ RegisterStore             «port» record the register document — NOT a POST
   ▼
ProcessingStateService                  writes processed_request / processed_output rows

──────────────────────────────  18:00 Europe/London, Mon–Fri  ──────────────────────────────

RegisterGenerationJob                   the scheduled run, one ShedLock-held run per night
   ├─▶ FeatureFlagGate                  reads CourtRegisterService once per run, no cache, fail-closed
   ├─▶ BatchAssembler                   recorded rows → one batch per (court centre, register date)
   ├─▶ RegisterGenerationService        per batch: assemble payload, mint ids, request the render
   │      ├─▶ PayloadFileStore   «port» write the PDF payload into the platform file service
   │      └─▶ DocumentRenderer   «port» systemdocgenerator generate-document (202)
   ├─▶ GenerationReconciler             the grace-period sweep for outcomes that never arrived
   └─▶ RunReport                        one structured line + three gauges, every run

DocumentEventListener                   inbound adapter on Artemis public.event — parse + drop ONLY
   ▼
DocumentOutcomeSink              «port» document-available / generation-failed → the batch
   ▼
RegisterNotifier                 «port» notificationnotify send-email-notification, one per YOT
```

- **Inbound adapters** (`CourtRegisterMessageListener`, `DocumentEventListener`) deserialise, and
  settle or drop. NO business logic. NO transformation. NO downstream calls. The queue listener
  performs exactly one settlement (`complete` / `abandon` / `deadLetter`) on every path; the topic
  listener acknowledges by returning and counts every drop under a bounded reason.
- **Application services** (`DistributionPipeline`, `RegisterGenerationService`,
  `DocumentOutcomeSinkImpl`, `RegisterNotifierService`) orchestrate against **ports only**. They
  MUST NOT import Azure, Redis, JMS or HTTP client types, nor any other infrastructure wire type.
  Jackson is the one qualified exception: the hearing payload crosses the core as the platform
  Jackson generation's `JsonNode` by design (Principle IV, "canonical JSON in"), treated as
  immutable — read it, derive from it, never mutate a node the core did not construct. That
  permission covers `JsonNode` and its subtypes only; Jackson's binding, streaming and
  `ObjectMapper` configuration machinery stays in the adapters and in `config/`.
- **The job is application code too.** `RegisterGenerationJob` may hold a clock, a lock and ports;
  it may not hold a driver, a broker client or an HTTP client.
- **Ports:** Java interfaces owned by the application package. One port per external capability.
  Adapters implement them and live in their own package.
- **Adapters:** the only place infrastructure types appear. Stub adapters (logging no-ops behind the
  real port interfaces) are a legitimate transitional state, and both adapter modes default to
  `LIVE` — a service that has to be told to fetch payloads is one that will be deployed not
  fetching them. If swapping an adapter forces a pipeline edit, the port is wrong — fix the port,
  not the pipeline.
- **Persistence:** JdbcClient repositories, accessed only by `ProcessingStateService`,
  `IdempotencyGuard` and `JdbcRegisterStore`. Never from a listener, never from the job directly.

NEVER put business logic in a message listener.
NEVER call a repository or an HTTP client from a listener.
NEVER reference `ServiceBusReceivedMessage` outside the queue's inbound adapter, or `TextMessage`
outside the topic's.

### Package structure

```
uk.gov.hmcts.cp.courtregister
├── inbound/       ServiceBusProcessorClient config, message listener, DistributionCommand parsing
├── application/   DistributionPipeline, RegisterGenerationService, DocumentOutcomeSinkImpl,
│                  RegisterNotifierService, IdempotencyGuard, ProcessingStateService, and the
│                  twelve port interfaces (RegisterStore, DocumentRenderer, PayloadFileStore,
│                  RegisterNotifier, DocumentOutcomeSink, FeatureFlagReader, RenderProgress, …)
├── domain/        records + enums (DistributionCommand, RequestStatus, BatchStatus,
│                  BatchFailureReason, NotificationStatus, CompletionReason, RegisterBatch, …)
├── adapter/
│   ├── stub/               logging no-op implementations (test/local profiles only)
│   ├── payload/            Redis + results-query-api hearing payload source
│   ├── refdata/            reference-data now-subscriptions client
│   ├── fileservice/        the framework file-service metadata + content write (pinned 001–006)
│   ├── systemdocgenerator/ generate-document command client
│   ├── notificationnotify/ send-email-notification command client
│   ├── publicevents/       the Artemis public.event listener and envelope parsing
│   ├── appconfig/          the Azure App Configuration flag reader
│   ├── http/               the shared HTTP concerns the four clients above sit on
│   └── progression/        the 001 add-court-register client, retained for progression-post mode
├── batch/         RegisterGenerationJob, BatchAssembler, FeatureFlagGate, GenerationReconciler,
│                  RecipientSet
│   └── cli/       CliMain and the five operations commands
├── pipeline/      ported transformation: RegisterBuilder, SubscriptionMatcher, AggregationMapper
├── persistence/   repositories; Flyway migrations in src/main/resources/db/migration
└── config/        typed @ConfigurationProperties, ObjectMapper, health indicators, the two
                   metrics classes, CliModeConfig
```

## Domain Model

| Type | Kind | Fields / meaning |
|------|------|------------------|
| `DistributionCommand` | record (inbound message) | `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType`, optional `userId` |
| `ProcessedRequest` | entity | PK `(source, requestId)`; `hearingId`, `hearingDay`, `eventType`, `status`, `attempts`, `completionReason`, `failureReason`, claim triple, timestamps |
| `ProcessedOutput` | entity | PK `outputId`; FK `(source, requestId)`; `courtCentreId`, `courtCentreOuCode`, `registerDate`, `fileName`, `requestDigest`, `status`, `responseCode`, `postedAt`; **UNIQUE `(source, requestId)`** |
| `RegisterFragment` | record | **One per hearing** — the register defendants after court-extract filtering, court centre id/OU code, the three dates, matched subscriptions |
| `CourtRegisterDocument` | record | The register document: hearing venue, recipients, **youth defendants only**, validated against the vendored schemas **before the write into the store** (fix C29) |
| `RegisterRecord` | entity | One recorded register document, keyed to its hearing, carrying its court centre day and its `shared_time`; the unit the nightly job batches |
| `RegisterBatch` | entity | One (court centre, register date) batch: `batchId`, `status`, `payloadFileId`, `documentFileId`, `requestedAt`, `generatedAt`, `failureReason`, `sdgReason`, per-recipient notification rows |

Inbound is **JsonNode-canonical**: the hearing payload stays a Jackson tree and is read through a
typed facade; only what this service *produces* is modelled as typed records. Do not write a full
typed model of the hearing.

**The three dates are distinct and MUST NOT be conflated** (design §2.2): the command's `hearingDay`
keys the Redis claim-check and nothing else; the fragment's `hearingDate` derives from the latest
`judicialResult.orderedDate`; `registerDate` derives from `sharedTime` and drives the reference-data
`on=` day, the batching key, and the filename.

## Processing State Machine — the intake leg

Every command reaches an explicit recorded outcome. "Nothing happened" is never an acceptable end
state — silent failure is the disease this service exists to cure.

```
message received
   ▼
(source, requestId) already COMPLETED? ── yes ─▶ log + complete()  [no re-record, no state change]
(source, requestId) already FAILED?    ── yes ─▶ FAILED → RECEIVED [audit note, attempts preserved]
   │ no
   ▼ INSERT processed_request status=RECEIVED, attempts=1
   ▼ fetch payload            (port)   both cache and fallback miss ⇒ TRANSIENT (fix C32)
   ├─ isGroupProceedings ─────────────▶ COMPLETED, completion_reason=group-proceedings (fix C7)
   ▼ build fragment           (port)
   ├─ no register defendants ─────────▶ COMPLETED, completion_reason=no-defendants
   ▼ match subscriptions      (port)   refdata unanswered ⇒ TRANSIENT, never an empty register
   ├─ none matched ───────────────────▶ COMPLETED, completion_reason=no-subscriptions
   ▼ youth filter + map aggregation
   ├─ no youth defendants ────────────▶ COMPLETED, completion_reason=no-youth-defendants
   ▼ validate against vendored schema (fix C29)  invalid ⇒ FAILED + deadLetter, reason recorded
   ▼ record once              (port) → the register store, superseding any earlier row for the
   │                                    hearing at the write
   ├─ recorded ───────────────────────▶ COMPLETED, completion_reason=recorded ─▶ complete()
   ├─ transient failure ──────────────▶ RETRYING, attempts++ ─▶ abandon()  → ASB redelivers
   └─ non-transient failure ──────────▶ FAILED + reason ─▶ deadLetter() → DLQ alert
```

Statuses — request level: `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED`.
Completion reasons: `recorded`, `group-proceedings`, `no-defendants`, `no-subscriptions`,
`no-youth-defendants` — plus `submitted`, which only `courtregister.output=progression-post`
produces.

Rules:

- `COMPLETED` and `FAILED` are **terminal**, and are not treated alike on a resubmission:
  - `COMPLETED` — acknowledged and `complete()`d without reprocessing.
  - `FAILED` — **replayable**. A resubmitted message (fresh broker `messageId`, same `requestId`)
    makes the guard transition `FAILED` → `RECEIVED`, preserving `attempts` and writing an audit
    note, then reprocess. This is the supported way to recover a dead-lettered request.
  - Ordinary broker redelivery of the *same* message is unaffected.
- The four no-op outcomes are business outcomes, not errors — recorded, bounded, and mutually
  distinguishable (fix C33). Two of them are this flow's **most common** results.
- **Transient** (retry, `abandon()`): connection/IO errors, HTTP 5xx, 429 and 408 (honour bounded
  delta-seconds `Retry-After` — fix C3), payload source unavailable, reference data unanswered,
  register store unavailable.
- **Non-transient** (`FAILED`, `deadLetter()`): unparseable message, schema violation (inbound or
  outbound — fix C29), transformation errors (C19/C20/C21 keep the throw and report it).
- Every state transition is persisted **before** the message is settled. Settle last.

## Batch State Machine — the generation leg

Every batch reaches an explicit terminal state too, and a night that generated nothing says which
kind of nothing it was.

```
recorded rows, active and unbatched
   ▼ (18:00 run, flag ON) BatchAssembler groups by (court centre, register date)
   │  a court centre day whose batch is still in flight is DEFERRED — no batch, no document tonight
   ▼ PENDING            the batch exists and holds its rows
   ▼ assemble payload → PayloadFileStore  ⇒ failure: FAILED/ASSEMBLY_FAILED
   │                                          or FAILED/PAYLOAD_STORE_UNAVAILABLE
   ▼ mint ids, then request the render (ids before calls, always)
   ▼ GENERATING         systemdocgenerator accepted (202)
   │     ├─ refused / undeliverable ─▶ FAILED, RENDER_REQUEST_REJECTED / RENDER_REQUEST_FAILED
   │     ├─ document-available (public.event) ─▶ GENERATED
   │     ├─ generation-failed  (public.event) ─▶ FAILED, GENERATION_FAILED (+ sdg_reason)
   │     └─ neither, past the grace period ───▶ FAILED, GENERATION_TIMED_OUT (reconciler)
   ▼ GENERATED          the PDF exists in the file service
   ▼ notify every matched Youth Offending Team, once each
   ├─ all accepted ──────────────────▶ NOTIFIED
   ├─ some accepted ─────────────────▶ PARTIALLY_NOTIFIED   (the rest are resendable)
   └─ nobody to tell ────────────────▶ NOTIFIED_NOBODY
```

Statuses — batch level: `PENDING`, `GENERATING`, `GENERATED`, `NOTIFIED`, `PARTIALLY_NOTIFIED`,
`NOTIFIED_NOBODY`, `FAILED`.
Failure reasons (bounded): `ASSEMBLY_FAILED`, `PAYLOAD_STORE_UNAVAILABLE`, `RENDER_REQUEST_FAILED`,
`RENDER_REQUEST_REJECTED`, `GENERATION_FAILED`, `GENERATION_TIMED_OUT`.
Per-recipient notification statuses: `PENDING`, `ACCEPTED`, `FAILED`.

Rules:

- **Ids before calls.** The payload file id and the batch id are minted and written down *before*
  the render is requested, so an outcome that arrives can always be correlated. A call made before
  its id is recorded is an outcome nothing can be applied to.
- **A failed batch releases its rows.** `releaseFailed` puts the registers back for the next run;
  a failure must never leave a register stranded in a dead batch.
- **The reconciler invents nothing.** A batch nothing can be learned about is failed
  `GENERATION_TIMED_OUT` through the store rather than through the sink — there is no outcome to
  apply, and applying one would be inventing evidence.
- **A late or duplicate outcome moves nothing** and is counted: a team that has been told has been
  told. Every acknowledged-and-dropped path on the subscription carries a bounded reason on
  `courtregister_public_events_ignored_total`.
- **The run report is not a tally of the night.** `generated` and `notified` are a snapshot taken at
  the moment the line is written, of a night that may still be settling; `snapshot=taken|unread`
  says whether they were read at all, and an unread snapshot is counted on
  `courtregister_generation_unrecorded_total`.
- Every batch transition is persisted before the message or event that caused it is settled or
  acknowledged.

## The Cutover Rule — one lever

The whole flow is switched between the legacy implementation and this service by **one Azure App
Configuration feature flag, `CourtRegisterService`**.

- The nightly job reads it **once per run, with no cache**, and does nothing when it is off or
  unreadable. Fail-closed: every failure to read leaves the legacy in charge.
- **Never add a second switch** — no Helm value, no static-data patch, no endpoint — that decides
  which implementation is live. `courtregister.output` and `courtregister.generation.enabled` are
  deployment shape, not cutover levers, and neither may be documented as one.
- The regeneration CLI refuses to run without `--ignore-flag`.
- Never run generation with notification enabled against production data outside cutover.
- `courtregister.cli` decides **who starts** (it switches off the consumer, the scheduler and the
  event listener) and nothing else. It is deliberately not the inverse of
  `generation.enabled`: a command and the schedule must read the same configuration.

## Queue and Topic Semantics

Queue **`courtregister.requests`** (+ its dead-letter queue), owned by this service.

- **Peek-lock only.** `ReceiveAndDelete` is banned — it loses messages on crash.
- **Auto-complete disabled.** Exactly one explicit `complete()`, `abandon()` or `deadLetter()` on
  every path. A path that can return without settling is a bug; reviewers reject it.
- `maxDeliveryCount` = **5**; the broker's `getDeliveryCount()` is 0-based, so the final permitted
  delivery carries count 4.
- **Broker duplicate detection is ON.** `messageId` = `"{source}:{requestId}"`. The service must not
  depend on it alone — the `(source, requestId)` processed-log is the real guard.
- **Replay tooling always mints a fresh `messageId`** and keeps the original `requestId`.
- `maxConcurrentCalls` starts at **2**. Raise only once golden tests prove the pipeline is
  stateless.
- **ASB health MUST NEVER gate readiness.** A broker blip must not restart the pod.

Topic **`public.event`** on Artemis, the estate's shared topic, consumed through a **durable
subscription that admits exactly one consumer**.

- The subscription is a filter, not a guarantee: every context publishes here, and
  systemdocgenerator announces every document it renders for anybody. Three things must hold before
  an outcome touches a batch — the message says what it is (envelope, not just the `CPPNAME`
  header), the document is this service's (`originatingSource`), and the outcome names a batch this
  service recorded.
- **Acknowledge and drop, never nack.** A durable subscription offers a nacked message again for
  ever, and a foreign document is never going to become ours.
- Every drop is counted under a bounded reason. Nothing of an unreadable body reaches the log or a
  label.
- Because the subscription admits one consumer, a CLI JVM must not subscribe — see the
  `courtregister.cli` rule above.

## Idempotency and Supersession

The register document is written into **this service's own store**, so the 001 argument about
progression absorbing a duplicate POST no longer applies. What replaces it:

- `processed_request` — PK `(source, request_id)`. The insert is the claim; a unique-violation means
  a concurrent delivery is already processing and is handled, not logged as an error.
- **Supersession happens at the write.** A re-share of the same hearing supersedes the earlier
  recorded register rather than appending a second one — `V3__active_row_unique.sql` is what makes
  "one active register per hearing" a constraint rather than a convention.
- `request_digest` (SHA-256 of the document) is written before the write and left in place after a
  failure — what was attempted is the evidence.
- An **ambiguous write** is retried: prefer a possible duplicate, which supersession absorbs, over a
  possible loss, which is silent.
- Migrations are **Flyway** (`V<n>__<snake_case_description>.sql`) — never Liquibase. Additive and
  forward-only; never edit an applied migration.
- Hearing payloads are **never persisted**. Redis and the results query API remain the source.
- The log doubles as the support answer to "was this hearing processed, and did its register go
  out?" — keep it queryable by `hearing_id`, `hearing_day` and batch.

## The Consumed Platform Contracts

This service **adapts to** four contracts it does not own, and never redefines them (Principle III):

| Contract | Owner | What this service may not do |
|---|---|---|
| systemdocgenerator `generate-document` (REST, 202) + the `document-available` / `generation-failed` public events | systemdocgenerator | Add a field, treat any 2xx but 202 as success, or infer an outcome no event carried |
| notificationnotify `send-email-notification` (REST, 202) | notificationnotify | Batch recipients into one call, or retry a 4xx |
| the framework file-service `metadata` + `content` table schema (write-only, pinned to changesets 001–006) | the framework | Read through it, or migrate it |
| the `CourtRegisterService` App Configuration flag | the cutover | Cache it, default it open, or add a second reader with different semantics |

Plus the two this service's own increments froze: the **inbound queue message**
(`distribution-command.schema.json`, `additionalProperties: false`, agreed with
`cpp-context-results`) and the **register document** (`courtRegisterDocument/*` at
`criminal-court-public-model` 17.103.13, vendored, enforced at the write into the store).

A change to any of these is a cross-team event, not a local edit.

## Fix-First and the Defect-Fix Register

This port fixes every catalogued defect and keeps legacy behaviour everywhere else. Since 002 the
register has **two oracles**: the JavaScript function app for the intake half, and progression's
court-register leg for the downstream half. The register is `doc/DEFECT-FIXES.md` (Principle I).

- Every fix MUST have a register row: defect ref, legacy behaviour (`file:line`), fixed behaviour,
  rationale/impact, **the pinning test**, sign-off status. A fix without a row is reverted.
- `C` rows are the function-app catalogue (C1–C34 from the design, plus anything appended under
  review); `P` rows are progression's leg. An appended row carries the same obligations as an
  original one.
- Fixes that change business-visible content carry a **sign-off-before-cutover** marker.
- Do NOT fix behaviour that is not on the register, however wrong it looks. An uncatalogued change
  needs written sign-off first — either it earns a number (append-only) or the legacy stands.
- Golden files encode **fixed** behaviour; a golden changes only in the same commit as a
  DEFECT-FIXES entry.
- The differential audit is an assertion on every build, in both directions: a catalogued defect
  still reproduced, or a difference that maps to no row, is build-blocking.
- Behaviours deliberately KEPT and easy to mistake for defects: the group-proceedings **skip
  itself** (a business rule — C7 fixes its type-handling and its silence); the `####` join replaced
  by a real newline (C24); letter-delivery subscriptions still email-only (C27 fixes the *silence*
  of the drop, not the drop); the batch header taken from any member of the batch (progression's
  `stream().findAny()`, reproduced deliberately and noted in the goldens' provenance).

## Error Handling and Logging (domain-specific)

- **NO swallowed exceptions, ever.** No empty catch, no `catch (Exception e) { log.debug(...); }`,
  no returning a "success" object from a catch block. Catch to classify and rethrow, or to map onto
  a state that is persisted and settled explicitly.
- **The one absorbed refusal** is telemetry: a round-trip reading that cannot be taken may not cost
  a Youth Offending Team its e-mail, so it stops where it happens, is counted, and is said at WARN.
  Every other refusal still leaves.
- **Never attach a throwable this service did not write.** A caught exception is named by **class**;
  its message belongs to whatever library raised it and is exactly where a connection string or a
  fragment of a statement turns up. The log-statement sweep enforces this, and it governs INFO and
  above.
- Every log line carries `requestId` and `hearingId` (MDC) on the intake leg, and the batch id on
  the generation leg. `source` and court-centre id/OU code where relevant.
- **No defendant PII at `info`** — no names, addresses, dates of birth, ASNs, or URNs. Identifiers
  only. Every defendant on this register is a **youth**. PII-bearing detail belongs at `debug` and
  must be off in deployed environments.
- `completion_reason`, `failure_reason` and every metric label are bounded codes — never raw
  exception text, never a fragment of a message body, never a court centre id or an e-mail address
  as a label (cardinality *and* privacy).
- A path that drops something must move a counter. "It is in the log index" is not an alerting
  surface.

## Out of Scope — do not build here

- Any REST API. If a status/replay surface is ever wanted, it is a separate, agreed story. The CLI
  is the operational surface.
- The prison court register — its own pipeline, its own future migration. Keep the seams clean; the
  shared kernel this port produces is what the PCR migration will consume.
- SJP hearings — the court register has no SJP leg at all (unlike informant).
- Any change to the register document's shape, or to the four consumed platform contracts.
- The legacy function-app repo and progression's retirement PR. C18, C28, C34 (legacy repo) and P6,
  P7 (progression's deletions) are registered items owned elsewhere and tracked to conclusion
  before cutover.
