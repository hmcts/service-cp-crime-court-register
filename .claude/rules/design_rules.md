# Architecture & Domain Rules

This service is a **message-driven pipeline**, not a REST application. There is no controller layer
and no public HTTP API — actuator only. Everything below assumes that shape.

## Pipeline Architecture (ports and adapters)

```
ASB queue courtregister.requests
   │  (peek-lock delivery)
   ▼
CourtRegisterMessageListener            inbound adapter — parse + settle ONLY
   ▼
DistributionPipeline                    application service — the use case, no I/O of its own
   ├─▶ IdempotencyGuard                 (source, requestId) processed-log — has this been done?
   ├─▶ HearingPayloadSource      «port» fetch hearing payload   (Redis INT_ keys → results-query-api fallback)
   ├─▶ NowSubscriptionsSource    «port» reference-data now-subscriptions for the register date
   ├─▶ RegisterTransformer       «port» fragment → subscription matching → the single aggregation
   └─▶ RegisterSubmissionClient  «port» POST add-court-register, once per hearing, with retry
   ▼
ProcessingStateService                  writes processed_request / processed_output rows
```

- **Inbound adapter:** deserialises the queue message into `DistributionCommand`, calls the pipeline,
  and performs exactly one settlement (`complete` / `abandon` / `deadLetter`) on every path.
  NO business logic. NO transformation. NO downstream calls.
- **Application service (`DistributionPipeline`):** orchestrates the use case against **ports only**.
  It MUST NOT import Azure, Redis, or HTTP client types, nor any other infrastructure wire type.
  Jackson is the one qualified exception: the hearing payload crosses the core as the platform
  Jackson generation's `JsonNode` by design (Principle IV, "canonical JSON in"), treated as
  immutable — read it, derive from it, never mutate a node the core did not construct. That
  permission covers `JsonNode` and its subtypes only; Jackson's binding, streaming and
  `ObjectMapper` configuration machinery stays in the adapters and in `config/`.
  The service is unit-testable with plain mocks and no Spring context.
- **Ports:** Java interfaces owned by the application package. One port per external capability.
  Adapters implement them and live in their own package.
- **Adapters:** the only place infrastructure types appear. Stub adapters (logging no-ops behind the
  real port interfaces) are a legitimate transitional state while the real adapters land, with
  **zero change to the pipeline** when they are replaced. If swapping an adapter forces a pipeline
  edit, the port is wrong — fix the port, not the pipeline.
- **Persistence:** JdbcClient repositories, accessed only by `ProcessingStateService`
  and `IdempotencyGuard`. Never from the listener.

NEVER put business logic in the message listener.
NEVER call a repository or an HTTP client from the listener.
NEVER reference `ServiceBusReceivedMessage` outside the inbound adapter.

### Package structure

```
uk.gov.hmcts.cp.courtregister
├── inbound/       ServiceBusProcessorClient config, message listener, DistributionCommand parsing
├── application/   DistributionPipeline, IdempotencyGuard, ProcessingStateService, port interfaces
├── domain/        records + enums (DistributionCommand, RequestStatus, OutputStatus, …)
├── adapter/
│   ├── stub/      logging no-op implementations of every port (transitional; test/local profiles)
│   ├── payload/   Redis + results-query-api payload source
│   ├── refdata/   reference-data now-subscriptions client
│   └── progression/  add-court-register submission client
├── pipeline/      ported transformation: RegisterBuilder, SubscriptionMatcher, AggregationMapper (12 mappers)
├── persistence/   repositories; Flyway migrations in src/main/resources/db/migration
└── config/        typed @ConfigurationProperties, ObjectMapper, health indicators
```

## Domain Model

| Type | Kind | Fields / meaning |
|------|------|------------------|
| `DistributionCommand` | record (inbound message) | `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType`, optional `userId` |
| `ProcessedRequest` | entity | PK `(source, requestId)`; `hearingId`, `hearingDay`, `eventType`, `status`, `attempts`, `completionReason`, `failureReason`, claim triple, timestamps |
| `ProcessedOutput` | entity | PK `outputId`; FK `(source, requestId)`; `courtCentreId`, `courtCentreOuCode`, `registerDate`, `fileName`, `requestDigest`, `status`, `responseCode`, `postedAt`; **UNIQUE `(source, requestId)` — at most one output per command, the court register has no fan-out** |
| `RegisterFragment` | record | **One per hearing** — the register defendants after court-extract filtering, court centre id/OU code, the three dates, matched subscriptions |
| `CourtRegisterDocument` | record | The outbound `add-court-register` body: hearing venue, recipients, **youth defendants only**, validated against the vendored progression schemas before send (fix C29) |

Inbound is **JsonNode-canonical**: the hearing payload stays a Jackson tree and is read through a
typed facade; only what this service *produces* is modelled as typed records. Do not write a full
typed model of the hearing.

**The three dates are distinct and MUST NOT be conflated** (design doc §2.2): the command's
`hearingDay` keys the Redis claim-check and nothing else; the fragment's `hearingDate` derives from
the latest `judicialResult.orderedDate`; `registerDate` derives from `sharedTime` and drives the
reference-data `on=` day, the progression batching key, and the filename.

## Processing State Machine

Every command reaches an explicit recorded outcome. "Nothing happened" is never an acceptable end
state — silent failure is the disease this service exists to cure.

```
message received
   ▼
(source, requestId) already COMPLETED? ── yes ─▶ log + complete()  [no re-POST, no state change]
(source, requestId) already FAILED?    ── yes ─▶ FAILED → RECEIVED [audit note, attempts preserved]
   │ no                                          │  then reprocess below; a POSTED output
   │                                             │  short-circuits any re-POST
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
   ▼ submit once              (port) → the single processed_output row
   ├─ 202 ────────────────────────────▶ COMPLETED, completion_reason=submitted ─▶ complete()
   ├─ transient failure ──────────────▶ RETRYING, attempts++ ─▶ abandon()  → ASB redelivers
   └─ non-transient failure ──────────▶ FAILED + reason ─▶ deadLetter() → DLQ alert
        (also: attempts exhausted at maxDeliveryCount ⇒ FAILED + exhausted_message_id + deadLetter())
```

Statuses — request level: `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED`.
Statuses — output (`processed_output`): `PENDING`, `POSTED`, `FAILED`.

Rules:

- `COMPLETED` and `FAILED` are **terminal**, but they are not treated alike on a resubmission:
  - `COMPLETED` — acknowledged and `complete()`d without reprocessing. Nothing is re-POSTed and no
    state changes.
  - `FAILED` — **replayable**. A resubmitted message (fresh broker `messageId`, same `requestId`)
    makes the guard transition `FAILED` → `RECEIVED`, preserving `attempts` and writing an audit
    note recording the replay, then reprocess. An output already `POSTED` is skipped, so a
    successful POST is never repeated. This is the supported way to recover a dead-lettered
    request — there is no operator-remembered flag and no manual row edit.
  - Ordinary broker redelivery of the *same* message is unaffected: it is a redelivery, not a
    resubmission, and a terminal request short-circuits it.
- The four no-op outcomes (`group-proceedings`, `no-defendants`, `no-subscriptions`,
  `no-youth-defendants`) are business outcomes, not errors — recorded, bounded, and mutually
  distinguishable (fix C33). Two of them are this flow's **most common** results; dashboards are
  labelled accordingly.
- **Transient** (retry, `abandon()`): connection/IO errors, HTTP 5xx, HTTP 429 and 408 (honour
  bounded delta-seconds `Retry-After` — fix C3), payload source unavailable (both Redis and the
  fallback), reference data unanswered.
- **Non-transient** (straight to `FAILED`, `deadLetter()`): unparseable message, schema violation
  (inbound or outbound — fix C29), 4xx other than 429/408 from progression, any 2xx other than 202
  (`SUBMISSION_NOT_ACCEPTED`), transformation errors (fixes C19/C20/C21 keep the throw and report
  it).
- Every state transition is persisted **before** the message is settled. Settle last.
- Attempts counter is authoritative in the DB; it is cross-checked against the message's
  `deliveryCount` but never derived from it alone.

## Queue Semantics Rules

Queue **`courtregister.requests`** (+ its dead-letter queue), owned by this service.

- **Peek-lock only.** `ReceiveAndDelete` is banned — it loses messages on crash.
- **Auto-complete disabled.** Every path through the listener performs exactly one explicit
  `complete()`, `abandon()`, or `deadLetter()`. A path that can return without settling is a bug;
  reviewers reject it.
- `maxDeliveryCount` = **5**. After the fifth delivery the broker dead-letters. The service must
  reach `FAILED` + `deadLetter()` on its own before that where the failure is known to be terminal.
  The broker's `getDeliveryCount()` is 0-based: the final permitted delivery carries count 4.
- **Broker duplicate detection is ON.** `messageId` = `"{source}:{requestId}"`. Publishers must set
  it; the service must not depend on it alone — the `(source, requestId)` processed-log is the real
  guard.
- **Replay tooling always mints a fresh `messageId`** (and keeps the original `requestId`), so a
  deliberate replay is never swallowed by the broker's duplicate-detection window. A replay is then
  filtered by the processed-log, which is the intended, observable behaviour.
- `maxConcurrentCalls` starts at **2** (parity with the AKS fleet's pinned Durable throttle). Raise
  only once golden tests prove the pipeline is stateless.
- Lock renewal must cover the worst-case pipeline duration; a lock-lost exception is transient.
- **ASB health MUST NEVER gate readiness.** Register the processor health as a non-readiness
  indicator (or `management.endpoint.health.group.readiness` excluding it). A broker blip must not
  restart the pod.
- Message parsing failures dead-letter with a reason; they are never silently dropped and never
  abandoned into an infinite redelivery loop.

## Idempotency Log Design

`add-court-register` is **not idempotent** — every POST appends a `CourtRegisterRecorded` event and
inserts a new `court_register_request` row in progression. The processed-log is what makes
at-least-once delivery safe.

**The guarantee, stated honestly.** At-most-once submission in all normal operation, redeliveries and
replays included; across a crash in the instant between a successful POST and recording it,
at-least-once — the duplicate row is absorbed downstream like a re-share (progression's RECORDED
sweep selects `max(register_time)` per `hearing_id`, so the later row supersedes; the extra row
persists in the table and that divergence is acknowledged with the progression team, design doc
§3.5/§13 Q3). Strict at-most-once is impossible without progression-side idempotency, which is out
of scope (frozen contract). Consequently an **ambiguous POST** — timeout, dropped connection,
outcome unknown — is **retried**: prefer a possible duplicate, which is absorbed, over a possible
loss, which is silent. Do not write code, or a comment, that promises more than this.

- `processed_request` — PK `(source, request_id)`. Insert on first sight; the insert itself is the
  claim. A unique-violation on insert means a concurrent delivery is already processing: treat it as
  a duplicate, not an error.
- `processed_output` — **one row per `(source, requestId)`** (the court register has no fan-out
  dimension), written **before** the POST (status `PENDING`) and updated after. On redelivery or
  replay, an output already `POSTED` is **skipped**. `court_centre_id`, `court_centre_ou_code`,
  `register_date` and `file_name` are descriptive columns, not key columns.
- `request_digest` (SHA-256 of the outbound body) is written before the POST and left in place after
  a failure — what was attempted is the evidence — and is used for reconciliation and replay diffing.
- Migrations are **Flyway** (`src/main/resources/db/migration/V<n>__<description>.sql`) — never
  Liquibase, which is the WildFly-context convention.
- Hearing payloads are **never persisted**. Redis and the results query API remain the payload source.
- The log doubles as the support answer to "was this hearing processed?" — keep it queryable by
  `hearing_id` and `hearing_day`.

## Fix-First and the Defect-Fix Register

This port **fixes all 34 catalogued defects** (C1–C34, design doc §7) and keeps legacy behaviour
everywhere else. The register is `doc/DEFECT-FIXES.md` (constitution Principle I).

- Every fix MUST have a register row: defect ref, legacy behaviour (`file:line`), fixed behaviour,
  rationale/impact, **the pinning test**, sign-off status. A fix without a row is reverted.
- Fixes that change business-visible content (recipients, dates, the PDF's inputs) carry a
  **sign-off-before-cutover** marker — they are implemented now, gated at deployment.
- Do NOT fix behaviour that is not on the register, however wrong it looks. An uncatalogued change
  needs written sign-off first — either it earns a new C-number (append-only) or the legacy
  behaviour stands.
- Golden files encode **fixed** behaviour; a golden file changes only in the same commit as a
  DEFECT-FIXES entry (new or amended).
- The differential audit replays a recorded corpus through the legacy Node oracle and this pipeline:
  every difference MUST map to a C-number; a reproduced catalogued defect or an unexplained
  difference is build-blocking.
- Behaviours that are deliberately KEPT and easy to mistake for defects: the group-proceedings
  **skip itself** (a business rule — what C7 fixes is its type-handling and its silence, not the
  skip); the `####` join in offence wording is replaced by a real newline (C24) — progression's PDF
  generator passes newlines through; letter-delivery subscriptions are still email-only (C27 fixes
  the *silence* of the drop, not the drop).

## Error Handling and Logging (domain-specific)

- **NO swallowed exceptions, ever.** No empty catch, no `catch (Exception e) { log.debug(...); }`,
  no returning a "success" object from a catch block. Catch to classify and rethrow, or to map onto
  a `FAILED` state that is persisted and settled explicitly.
- Every log line carries `requestId` and `hearingId` (MDC). `source` and court-centre id/OU code
  where relevant.
- **No defendant PII at `info`** — no names, addresses, dates of birth, ASNs, or URNs. Identifiers
  only. Every defendant on this register is a **youth**. PII-bearing detail belongs at `debug` and
  must be off in deployed environments.
- `completion_reason` / `failure_reason` are bounded codes — never raw exception text, never a
  fragment of the message body.

## Out of Scope — do not build here

- Any REST API. If a status/replay surface is ever wanted, it is a separate, agreed story.
- The prison court register — its own pipeline, its own future migration. (The shared kernel this
  port produces is what the PCR migration will consume; keep the seams clean.)
- Progression's court-register leg — the `court_register_request` table, the 18:00 generation sweep,
  systemdocgenerator rendering, and notify fan-out are untouched.
- SJP hearings — the court register has no SJP leg at all (unlike informant).
- Any change to the shape of the `add-court-register` command — it is progression-owned and
  `additionalProperties: false`, frozen at `criminal-court-public-model` 17.103.13.
- The legacy function-app repo — C18 (kill-switch wiring in the legacy triggers), C28 (its dead
  test file) and C34 (its packaging) are registered as PENDING items owned elsewhere.
