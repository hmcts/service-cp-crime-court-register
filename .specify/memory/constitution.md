<!--
SYNC IMPACT REPORT
==================
Version change: 2.0.1 → 2.0.2
Bump rationale: PATCH — the register grew its first appended row (C35,
                2026-09-01: the hearing-date wall-clock legs, found in the
                pipeline-core review). Principle I already provided the
                append mechanism in its final bullet; this amendment makes
                the catalogue language match it: "the 34" now reads as the
                design document's original catalogue (C1–C34) PLUS any row
                appended later under review, with appended rows carrying
                exactly the same obligations (fix specification, pinning
                test, sign-off state). No principle's requirements change.

Modified sections (this amendment): Principle I opening paragraph and first
bullet — "the 34 behaviours that are catalogued" widened to "the catalogued
behaviours (C1–C34 from the design document §7, plus rows appended under
review — C35 onward)"; the pre-approval sentence now names the original
catalogue explicitly, since an appended row is approved by the review that
appends it, not in advance.

Previous amendment (2.0.0 → 2.0.1):
Bump rationale: PATCH — delivery-scope precision after the P0 review
                (2026-08-31): the register carries 34 rows, but three of them
                (C18, C28, C34) are remediations owned outside this repository
                (the legacy function-app repo and the producer). The
                constitution now states the scope consistently everywhere:
                31 defects are fixed in this service; C18/C28/C34 are
                externally-owned remediations registered as PENDING with an
                owner and a trigger, tracked to conclusion before cutover.
                No principle's requirements change.

Modified sections (this amendment): the preamble delivery claim, Principle I
first bullet, and the Current-increment section — "all 34 fixed" reworded to
the 31-plus-3 form above.

Previous amendment (1.0.2 → 2.0.0):
Bump rationale: MAJOR — Principle I is rewritten from parity-first to
                fix-first, a redefinition that invalidates the previous
                practice of porting catalogued defects bug-for-bug. This
                constitution was adopted from service-cp-crime-informant-
                register at its 1.0.2 and re-ratified for
                service-cp-crime-court-register (2026-08-31, user decision):
                the court-register port fixes all 34 catalogued legacy
                defects (C1–C34) outright and documents every fix in
                doc/DEFECT-FIXES.md. The legacy pipeline remains the oracle
                for every behaviour NOT catalogued; nothing else about the
                engineering discipline is relaxed.

Modified principles (this amendment):
  - I. Behaviour-Parity First → I. Defect-Fix-First with Characterised
    Legacy Behaviour — retitled and rewritten. Golden files now encode FIXED
    behaviour; the deviations register is replaced by the defect-fix register
    (doc/DEFECT-FIXES.md); the differential audit fails any catalogued defect
    still reproduced AND any uncatalogued behaviour change.
  - II. Test-Driven Development — one addition: every defect fix carries a
    test that would fail against the legacy behaviour and passes against the
    fix, and that test is named in the fix's DEFECT-FIXES row.
  - III. Message-Contract First — re-targeted at this service's contracts
    (inbound courtregister.requests; outbound progression-owned
    add-court-register, 202-only). New clause: a fix that changes the shape
    of an outbound component is a cross-team event requiring progression-team
    notification before ship.
  - IV–VII — re-targeted at the court register flow (single fragment,
    youth defendants only, PDF produced downstream in progression); the
    requirements themselves are unchanged.
  - VIII. Estate Conventions — package root uk.gov.hmcts.cp.courtregister;
    branch policy is plain main with no ticket prefixes (user decision,
    2026-08-31); everything else unchanged.
  - Technology Stack & Deployment, Development Workflow & Quality Gates,
    Governance — rewritten for the court register: queue, processed-log key
    (no fan-out), progression gateway, golden-file rule re-pointed at
    DEFECT-FIXES.md with polarity flipped.

History:
  - 2.0.1 (2026-08-31) Delivery scope stated as 31-in-service plus three
    externally-owned remediations (C18/C28/C34), after the P0 review.
  - 2.0.0 (2026-08-31) Re-ratified for service-cp-crime-court-register.
    Principle I inverted to fix-first with the 34-entry defect-fix register;
    all sections re-targeted at the court-register flow. Lineage: adopted
    from service-cp-crime-informant-register constitution 1.0.2.
  - 1.0.2 (2026-08-21) [informant lineage] Principle VIII static-analysis
    reality sync: Checkstyle + coverage gate adopted; merge checklist updated.
  - 1.0.1 (2026-08-20) [informant lineage] Principle IV Jackson coordinates
    made version-neutral.
  - 1.0.0 (2026-08-20) [informant lineage] Initial ratification.

Added sections: None (structure carried over).
Removed sections: None.

Templates requiring updates:
  - .specify/templates/plan-template.md       ✅ compatible — the "Constitution
      Check" block is filled per-feature by /speckit-plan; plan authors MUST
      gate on Principles I–VIII as amended.
  - .specify/templates/spec-template.md       ✅ compatible — spec authors MUST
      express behaviour in terms of the queue message in and the
      progression.add-court-register command out (Principle III), never REST
      endpoints, and MUST cite the C-number for any behaviour that a fix
      changes.
  - .specify/templates/tasks-template.md      ✅ compatible — already carries
      the local amendment making test tasks mandatory and ordered before the
      implementation they guard (Principle II); no further change needed.
  - .specify/templates/checklist-template.md  ✅ compatible — no changes.
  - CLAUDE.md                                 ✅ aligned — message-contract rule
      in place of any API-first rule; SPECKIT block points at
      specs/001-court-register-port/plan.md.
  - .claude/rules/*.md                        ✅ aligned — retained as
      quick-reference; this constitution is authoritative where they disagree.

Follow-up TODOs: None. All placeholders resolved.
-->

# service-cp-crime-court-register Constitution

This service is a Spring Boot port, onto AKS, of the court register Node.js
function app (the `CourtRegister*` pipeline in
`cpp-context-azure-legalaidagency/azure-functions/durable-functions/`). It
consumes thin hearing-resulted messages from a dedicated Azure Service Bus
queue, assembles **one court register per hearing** covering **youth
defendants only**, matches recipients via NOW-subscription rules keyed on the
court centre, and POSTs the result to the Progression context's existing
`add-court-register` command; progression batches, renders the PDF at 18:00
and emails it — that half is untouched. The legacy app fails silently and
carries 34 catalogued defects; this port exists to end both. Unlike its
informant-register predecessor, **this is deliberately not a bug-for-bug
port**: thirty-one of the thirty-four catalogued defects are fixed in this
service, the remaining three (C18, C28, C34) are externally-owned
remediations tracked to conclusion before cutover, and every fix is
registered.

## Core Principles

### I. Defect-Fix-First with Characterised Legacy Behaviour (NON-NEGOTIABLE)

The legacy JavaScript function app under
`cpp-context-azure-legalaidagency/azure-functions/durable-functions/` —
together with its Jest fixtures — is the **oracle for every behaviour that is
not catalogued as a defect**. For the catalogued behaviours — C1–C34 from
the court-register design document §7, plus any row appended to this repo's
`doc/DEFECT-FIXES.md` under review (C35 onward) — the fixed behaviour
specified in the register is the requirement, and reproducing the defect is
itself a defect. An appended row carries exactly the same obligations as an
original one: a fix specification, a pinning test, and a sign-off state.

- The 34 original catalogued defects (C1–C34) are **pre-approved fixes**: no
  further authorisation is needed to implement them; a row appended later is
  approved by the review that appends it. **Thirty-one are implemented in
  this service; C18, C28 and C34 are externally-owned remediations** (the
  legacy function-app repo and the producer), registered as PENDING with an
  owner and a trigger and tracked to conclusion before cutover. Fixes whose
  output is
  business-visible (content, recipients, dates, the PDF) carry a
  **sign-off-before-cutover** marker in the register — sign-off gates
  deployment, never implementation.
- Every fix MUST have a row in `doc/DEFECT-FIXES.md` carrying: the defect
  reference (C-number), the legacy behaviour with `file:line` citations, the
  fixed behaviour, the rationale/impact, **the test that pins the fix**, and
  the sign-off status. **A fix merged without a DEFECT-FIXES entry MUST be
  reverted** or registered retrospectively with named approval.
- Golden files and fixtures encode **fixed** behaviour. Legacy Jest fixtures
  are the raw material, repaired where the design document proves them stale
  or vacuous (wrong vocabulary key set, `.csv` filenames, mis-spelled field
  names); every repair is recorded in the fixture's provenance note.
- Any behaviour change that is **not** on the register is an uncatalogued
  deviation: it requires the same written sign-off the old parity regime
  demanded, before merge. "It looked wrong so I fixed it" is not a category —
  either it gets a C-number appended to the register with review, or the
  legacy behaviour stands.
- The **differential audit** (the final increment) replays a recorded corpus
  through the legacy Node oracle and the ported pipeline and compares:
  every difference MUST map to a C-number. A catalogued defect still
  reproduced fails the audit; an unexplained difference fails the audit.
  Both are build-blocking.

**Rationale**: the informant port proved the parity-first discipline works,
and its replays then proved the same defects were silently losing registers
in production-shaped data. The business decision for this flow (2026-08-31)
is to stop carrying known defects forward. What parity protected — the
thousands of uncatalogued behaviours downstream consumers depend on — is
still protected: the oracle, the twins, and the differential audit remain;
only the 34 catalogued behaviours are, deliberately and traceably, different.

### II. Test-Driven Development (NON-NEGOTIABLE)

Red → Green → Refactor for every behaviour change, without exception.

1. Write the failing test first. It MUST run and fail for the *correct* reason
   — the assertion, not a missing class or a compile error.
2. Write the minimum production code to make it pass.
3. Refactor with the test still green.

Because "it really did fail first" cannot be proved from a commit graph, the
evidence is a convention that a reviewer can audit:

- Test tasks precede implementation tasks in every task list, and each test
  task is closed before the implementation task it guards is opened.
- Each task's commit narrative records the observed red run before the green
  run — the failing assertion, quoted.
- Reviewers reject implementation commits whose tests could not have failed
  first: assertions that are tautologically true, tests that assert only that
  no exception was thrown, coverage added in the same breath as the code with
  no red run recorded.
- **Every defect fix carries a test that would fail against the legacy
  behaviour and passes against the fix.** That test is named in the fix's
  `doc/DEFECT-FIXES.md` row; a fix row with no named pinning test is
  incomplete and blocks merge.

The `qa` reviewer agent gates on that convention. Production code arriving
without an accompanying failing-then-passing test is a FAIL, not a style
comment.

Exempt: pure mechanical refactors (rename, move, extract with no behaviour
change), formatting, and comment-only edits.

**Rationale**: fix-first (Principle I) is only meaningful if it is
executable. A test written after the code encodes what the code does; a test
written from the register encodes what the fix is supposed to do. Only the
second one protects the register — in both senses.

### III. Message-Contract First (NON-NEGOTIABLE)

This service has **no business REST API**. Its contracts are:

- **Inbound** — the message on `courtregister.requests`:
  `{ source, requestId, hearingId, hearingDay, sharedTime, eventType,
  userId? }` (`userId` optional; absent, never null). Agreed jointly with
  `cpp-context-results` (the publisher); changes are a cross-team event.
- **Outbound** — the Progression context's existing `add-court-register`
  command (`application/vnd.progression.add-court-register+json`). It is
  **progression-owned, fixed, and `additionalProperties: false`**, with its
  nested `courtRegisterDocument/*` schemas compiled at
  `criminal-court-public-model` **17.103.13** and vendored into this repo as
  the frozen contract. **Success is `202 Accepted` and nothing else.** This
  service adapts to the contract; it does not negotiate it mid-story.

Rules:

- Both contracts MUST be documented in this repo (`doc/API_CONTRACTS.md`) and
  the inbound message MUST have a JSON schema, versioned with the repo, that
  contract tests assert against.
- A change to either contract is a **cross-team event**: it requires a spec,
  an agreed change with the owning context, and a compatibility plan
  (consumers and producers deploy independently — assume the old shape is in
  flight).
- **A defect fix that changes the shape or population of an outbound
  component is a cross-team event too**: progression MUST be notified before
  the fix ships, and the DEFECT-FIXES row records that notification. Fixes
  that change only *values* within the frozen shape (dates, text, recipients)
  carry the sign-off-before-cutover marker instead.
- The only HTTP this service exposes is Spring Boot Actuator.
  `doc/openapi.yaml` does not describe business endpoints, and adding a
  business endpoint requires a constitution amendment, not just a spec.

**Rationale**: the queue message and the progression command are the whole
external surface. Treating them with the discipline other services give an
OpenAPI spec is what keeps a redeploy on either side from silently dropping
registers.

### IV. Canonical JSON In, Typed Models Out (NON-NEGOTIABLE)

Inbound hearing payloads (from Redis, or the results-query-api fallback) are
large, sparsely populated, and owned elsewhere. They MUST be handled
end-to-end as the JSON tree model (`JsonNode`) of the Jackson generation the
platform (Spring Boot) provides — currently Jackson 3
(`tools.jackson.databind.JsonNode`) under Spring Boot 4.1:

- No POJO/record mapping of the inbound hearing payload. Unknown fields MUST
  survive untouched; binding to a typed model silently discards what it does
  not know, and this service is not the owner of that shape.
- Jackson MUST be configured with the platform equivalent of
  `USE_BIG_DECIMAL_FOR_FLOATS` so monetary and numeric values round-trip
  exactly — no binary-float drift into a register.
- Inbound trees are **immutable in practice**: never mutate a `JsonNode` you
  did not construct. Derive new nodes; do not edit inputs in place. (The
  legacy pipeline mutates its hearing object in place and is saved only by
  the Durable Functions serialisation boundary; the port does not get that
  accident, so immutability is the rule that replaces it.)
- Output is the opposite: everything this service *produces* — the register
  fragment, the outbound aggregation, the `add-court-register` payload, the
  processed-log rows — MUST be typed Java records, **validated against the
  vendored progression schemas before submission** (this validation is
  itself defect fix C29: a schema-invalid document is an explicit FAILED,
  never a swallowed 400).

**Rationale**: fidelity in, contract-checking out. `JsonNode` guarantees we
cannot lose a field we did not anticipate; typed records validated against
the frozen contract guarantee we cannot send a document progression will
reject — and cannot lose a hearing's register without a trace when we would.

### V. SOLID with Ports and Adapters (NON-NEGOTIABLE)

The pipeline is expressed as an application core surrounded by adapters:

```
ASB listener (adapter)
    → DistributionPipeline (application core)
        → IdempotencyGuard         (processed-log port)
        → HearingPayloadSource     (Redis adapter; results-query-api fallback)
        → NowSubscriptionsSource   (reference-data adapter)
        → RegisterTransformer      (pure, no I/O — fragment, matching, mapping)
        → RegisterSubmissionClient (progression add-court-register adapter)
```

- Transport, payload source, reference data, and submission MUST each sit
  behind an interface owned by the core. The core MUST NOT import Azure
  Service Bus, Redis, HTTP client, or JDBC types.
- The transformation stage MUST be pure: JSON in, typed documents out, no
  I/O, no clock, no randomness (inject any of those). It is where all but a
  handful of the 34 fixes live, and it MUST be testable with golden files
  alone.
- Dependency injection MUST be constructor parameters with `private final`
  fields (explicit constructor or Lombok `@RequiredArgsConstructor`).
  Field-level `@Autowired` is forbidden, including in tests.
- Stubbed adapters are a legitimate, temporary state. A stub MUST implement
  the real port interface, MUST log at a level that makes its no-op-ness
  obvious, and MUST NOT be reachable in a production profile once the real
  adapter lands.

**Rationale**: the fix work is in the transformation; the risk is in the
adapters. Separating them lets the golden-file suite run in milliseconds with
no broker, no cache, and no Progression context, and lets each adapter be
replaced without reopening ported logic.

### VI. Explicit Failure — Nothing Is Ever Swallowed (NON-NEGOTIABLE)

**No exception is ever caught and ignored.** Not "for robustness", not "to
keep the consumer alive", not in a `finally`, not in a stub.

Every failure path MUST terminate in one of exactly two outcomes:

1. **Retry (abandon)** — a transient failure (connect, IO, 5xx, 429) retried
   with backoff, or the message abandoned so the broker redelivers it.
2. **Dead-letter** — a poison or exhausted message explicitly dead-lettered
   with a reason and description, visible on the DLQ.

**Alerting is required in addition, never instead.** Whichever of the two
outcomes is taken, the failure MUST also produce an ERROR log carrying
`requestId` and `hearingId` and increment a metric an alert fires on (DLQ
depth > 0; failures sustained 15 minutes). An alert is not a way of settling
a message; a message that is only alerted about has not been settled at all.

"Nothing to publish" is a state, not silence: the four legitimate no-op
outcomes — `group-proceedings`, `no-defendants`, `no-subscriptions`,
`no-youth-defendants` — are recorded as `COMPLETED` with a bounded
`completion_reason`, mutually distinguishable in the processed log. Two of
them are this flow's most common results; an undifferentiated success is the
legacy defect (C33), not an acceptable simplification.

Specific rules:

- The ASB consumer uses peek-lock with **explicit** `complete` / `abandon` /
  `deadLetter`. Auto-complete is forbidden. A message is completed only after
  the work it represents is durably done.
- `catch` blocks MUST rethrow, wrap-and-rethrow, or perform one of the two
  outcomes above. An empty `catch`, a `catch` whose body is only a `debug`/
  `trace` log, and a swallowed `InterruptedException` (without restoring the
  interrupt flag) are all build-blocking.
- **Service Bus health MUST NOT gate readiness.** A broker blip must not roll
  the pods; queue health is an alerting concern, surfaced as its own metric
  and a non-readiness health group.
- `System.out`, `System.err`, and `printStackTrace()` are forbidden in
  production code and tests; diagnostics go through SLF4J.

**Rationale**: silent failure is the disease this service was commissioned to
cure. The legacy app swallows the final POST's errors (C1), reports success
from four silent guards (C33), and loses whole registers on a 400 with no
trace (C29). A loud, retried, dead-lettered failure is a success of this
design; a quiet one is the only true outage.

### VII. Privacy in Telemetry (NON-NEGOTIABLE)

Logs, metrics, traces, and exception messages MUST NOT carry defendant
personal data at `INFO` level or above — no names, dates of birth, addresses,
NINOs, contact details, or free-text that may contain them. **Every defendant
on this register is a youth**; treat that as raising the stakes, not as a
nuance.

- The permitted correlation set at `INFO` is: `requestId`, `hearingId`,
  `hearingDay`, `source`, court-centre id / OU code, counts, and timings.
  Every log line about processing MUST carry `requestId` and `hearingId`.
- Whole payloads, register fragments, and outbound documents MUST NOT be
  logged at any level in a deployed environment. Where a payload dump is
  genuinely needed for local diagnosis it goes behind `DEBUG` **and** an
  explicit local-only profile guard.
- Metric labels and dimensions are logs too: never label a metric with
  anything identifying a person.
- `completion_reason` and `failure_reason` are bounded codes — never raw
  exception text, never a fragment of the message body.
- Secrets, connection strings, and tokens MUST NOT appear anywhere in output.

**Rationale**: this pipeline handles criminal-court results for named
children across the whole estate's log shipping. Correlation IDs are enough
to debug it; personal data in a log index is an incident.

### VIII. Estate Conventions (NON-NEGOTIABLE)

- **Build**: Gradle (wrapper committed). Maven is forbidden.
- **Static analysis**: PMD via `.github/pmd-ruleset.xml` with
  `ignoreFailures = false`, run explicitly as `./gradlew pmdMain` (an `onlyIf`
  keeps it out of `build`; `pmdTest` is disabled). **Checkstyle**:
  `google_checks` via `config/checkstyle/google_checks.xml` and
  `gradle/checkstyle.gradle`, `maxWarnings = 0`, main sources only
  (`checkstyleTest` disabled), wired into `check` and therefore `build`.
  **Coverage gate**: `jacocoTestCoverageVerification` in `check` — LINE ≥
  0.88, BRANCH ≥ 0.85, excluding the application entry point and `config/**`;
  thresholds are a ratchet, never loosened in passing. Warnings are not
  tolerated as normal. Suppressions MUST be inline, narrow, and carry a
  reason.
- **Package root**: `uk.gov.hmcts.cp`; this service's code lives under
  `uk.gov.hmcts.cp.courtregister`.
- **Commits**: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`,
  `refactor:`, `test:`).
- **Branches**: work lands on `main`; no ticket prefixes (user decision,
  2026-08-31 — there is no Jira ticket for this increment). If a ticketed
  workflow arrives later, branch naming is revisited by PATCH amendment.
- **No AI attribution anywhere** — not in commit messages, branch names, PR
  titles or descriptions, code comments, or documentation. No
  `Co-Authored-By` trailers naming a tool, no generated-with footers. All
  output reads as developer-authored work.
- Logging is SLF4J + Logback. `src/main/resources/logback.xml` configures a
  single console appender using `LoggingEventCompositeJsonEncoder`
  (`net.logstash.logback`), so JSON is emitted **unconditionally** — there is
  no `json` Spring profile and no plain-text alternative. MDC is a declared
  provider, which is what puts `requestId` and `hearingId` on every line
  (Principle VII).

**Rationale**: this repo is one of ~70 in the CPP estate and is operated by
people who did not write it. Uniform build, analysis, naming, and history let
them read it the same way they read everything else.

## Technology Stack & Deployment

- **Java**: 25. **Framework**: Spring Boot 4.1, from
  `hmcts/service-hmcts-crime-springboot-template`.
- **Ports**: local `8082`; Kubernetes `4550`.
- **Messaging**: Azure Service Bus queue `courtregister.requests` + its
  dead-letter queue, consumed via `azure-messaging-servicebus`
  `ServiceBusProcessorClient`.
  - Peek-lock, explicit settlement, **`maxDeliveryCount` 5**.
  - Broker **duplicate detection on**; `messageId` = `source:requestId`.
  - **Replay tooling MUST always mint a fresh `messageId`** — deliberate
    replay must not be silently discarded by duplicate detection.
  - `maxConcurrentCalls` starts at 2, matching the AKS fleet's pinned Durable
    Functions throttle.
- **Idempotency**: PostgreSQL processed-log — `processed_request` keyed
  `(source, request_id)`, plus `processed_output` with
  **`UNIQUE (source, request_id)`**: the court register produces exactly one
  document per hearing, so the output cardinality is 0..1 and there is no
  fan-out dimension (the informant's per-authority key does not apply).
  Schema migrations via **Flyway**. The POST to `add-court-register` is not
  idempotent on the Progression side (every POST appends an event and a
  row), but a duplicate POST for the same hearing is **absorbed for
  generation** by progression's `max(register_time) per hearing_id` sweep.
  The honest guarantee: **at-most-once submission in all normal operation**,
  redeliveries and replays included; across a crash in the instant between a
  successful POST and recording it, **at-least-once** — the duplicate row is
  superseded downstream like a re-share. An ambiguous POST (timeout, unknown
  outcome) is therefore **retried**: a possible duplicate, which is
  absorbed, is preferred to a possible loss, which is silent.
- **Payload source**: Redis `INT_` claim-check (dated key form first, then
  the legacy undated twin) with a results-query-api fallback; verified TLS on
  both (fix C15). A cache miss AND fallback miss is a recorded transient
  failure, never a silent stop (fix C32).
- **Outbound**: one HTTP POST of `add-court-register` per hearing to
  `cpp-context-progression`, body validated against the vendored
  `courtRegisterDocument/*` schemas before send (fix C29), with retry on
  connect/IO/5xx/429/408 honouring bounded delta-seconds `Retry-After`
  (fix C3), and dead-letter on exhaustion (fix C1). **202 and nothing else
  is success.**
- **HTTP surface**: Spring Boot Actuator only — health, readiness/liveness,
  metrics. No business endpoints (Principle III).
- **Test stack**: JUnit Jupiter 6 (the Boot 4.1 test starter) + Mockito
  (unit); golden-file/fixture tests for the ported transformation;
  **Testcontainers** — Service Bus emulator and PostgreSQL — for integration
  tests (suffix `*IT`); **WireMock** for the progression, results-query and
  reference-data stubs.
- **Observability**: structured JSON logs with `requestId`/`hearingId`;
  metrics for processed/failed, the `completion_reason` distribution, and
  queue + DLQ depth; alerts on DLQ > 0 and on failures sustained for
  15 minutes. Expect a high COMPLETED-but-not-submitted rate: two of the four
  no-op reasons are this flow's most common legitimate outcomes.
- **Secrets/identity**: workload identity + Key Vault CSI.
- **Deployment**: AKS via the standard Flux route. This service contains no
  scheduler, no PDF generation, and no GOV.UK Notify code — progression's
  18:00 sweep, systemdocgenerator rendering and notify fan-out are untouched.
- **Out of scope by construction**: the prison court register (its own
  pipeline and future migration), progression's court-register leg (the
  `court_register_request` table, generation, distribution), SJP hearings
  (the court register has no SJP leg), and the legacy function-app repo
  itself (C18/C28/C34 are registered as pending items owned elsewhere).

### Current increment — 001 "court-register-port"

The full pipeline port with the 31 in-service defect fixes landed
(C18/C28/C34 tracked externally to conclusion before cutover): ASB consumer,
idempotency guard, the ported transformation (fragment build, subscription
matching, the 12-mapper aggregation), the Redis + results-query payload
adapter, the reference-data adapter, and the progression submission adapter —
finished by the differential audit against the legacy oracle. Every phase is
built test-first under Principle II; every fix lands with its DEFECT-FIXES
row under Principle I.

## Development Workflow & Quality Gates

- The contract artefacts (inbound message schema, `doc/API_CONTRACTS.md`,
  the vendored progression schemas) MUST be updated **before** any code
  change that affects a contract (Principle III).
- Every feature built via spec-kit lives under `specs/NNN-slug/` containing
  at least `spec.md`, `plan.md`, and `tasks.md`. Flow:
  `/speckit-specify → /speckit-plan → /speckit-tasks → /speckit-implement
  → /speckit-analyze`.
- Non-trivial changes flow through `Spec → Write → Code Review → QA →
  Spec-Validate → Fix → Ship`. The reviewer agents (`code-reviewer`, `qa`,
  `spec-validator`) report findings only; they MUST NOT modify code. The
  primary agent or a human applies fixes and re-runs until all three return
  PASS / COMPLIANT. Exempt: markdown-only edits, whitespace/import-only
  edits, and `.claude/rules/*` or `CLAUDE.md` updates.
- Required to run cleanly before merge:
  - `./gradlew build` — compilation, the full test suite, **Checkstyle** and
    the **JaCoCo coverage verification** (both run in `check`). PMD is the
    one analysis `build` does not run.
  - `./gradlew test` — the whole suite; there is no separate
    `integrationTest` task, so the Testcontainers suites run here and need
    Docker only when those tests are in the selection.
  - `./gradlew pmdMain` — static analysis, failures not ignored. It must be
    named explicitly: an `onlyIf` in `gradle/pmd.gradle` skips it otherwise,
    and `pmdTest` is disabled.
  - `./gradlew jacocoTestReport` — coverage report.
- Any change to ported logic MUST run the golden-file suite; **a golden file
  is only updated in the same commit as a `doc/DEFECT-FIXES.md` entry (new
  or amended)** — the fix register is what authorises a golden to move
  (Principle I).
- Pull requests: the description MUST state which principle(s) the change
  touches. Any deviation requires explicit written justification in the PR
  description and MUST be flagged in the plan's "Complexity Tracking"
  section.
- Reviewers MUST specifically look for: a swallowed exception
  (Principle VI), an auto-completed message, a typed model bound over an
  inbound payload (Principle IV), PII in a log line (Principle VII),
  production code with no preceding failing test (Principle II), **a
  catalogued defect reproduced instead of fixed, and a fix with no
  DEFECT-FIXES row or no pinning test** (Principle I).

## Governance

This constitution supersedes the informal conventions in `.claude/rules/`
and the template-derived guidance in `CLAUDE.md` — including any API-first
rule, which does not apply to a service with no business REST API. Where
this document and those files disagree, this document wins; they are
retained as quick-reference material and MUST be kept in sync.

**Amendment procedure**:

1. Propose the change in a feature spec under `specs/`.
2. Bump `Version` per semantic versioning:
   - **MAJOR** — a breaking principle change, removal, or redefinition that
     invalidates existing practice.
   - **MINOR** — a new principle, new section, or materially expanded
     guidance.
   - **PATCH** — clarifications, wording, typo fixes, or non-semantic
     refinements.
3. Re-run `/speckit-analyze` on every in-flight feature spec to verify it
   still aligns with the amended principles; update or waive as required.

**Compliance expectations**:

- All PRs MUST honour these principles.
- Deviations MUST be explicitly justified in the PR description and, where
  relevant, in the plan's "Complexity Tracking" table.
- Reviewers MUST block merges that silently violate a NON-NEGOTIABLE
  principle without a written waiver.
- Behaviour relative to the legacy function app is governed by the
  **defect-fix register** (`doc/DEFECT-FIXES.md`, Principle I), not by PR
  discussion alone. The polarity is: **a catalogued fix merged without a
  register entry is the defect** and MUST be reverted or registered
  retrospectively with named approval; an **uncatalogued** behaviour change
  needs the same written sign-off the old parity regime demanded, before
  merge. C-numbers are stable: renumber never, append only.

**Version**: 2.0.2 | **Ratified**: 2026-08-31 | **Last Amended**: 2026-09-01
