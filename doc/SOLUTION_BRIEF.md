# Solution Brief — service-cp-crime-court-register

## Overview

Replaces the court-register Azure Durable Functions app with a Spring Boot service on AKS. When a
hearing is resulted, the service builds the youth-defendant court register for the hearing's court
centre, matches Youth Offending Team recipients via NOW-subscription rules, and submits the
document to the progression context, which batches, renders a PDF nightly at 18:00 and emails it.

## Problem Statement

The legacy app fails silently end to end: the outbound POST swallows every error including
schema-rejections (a hearing's register can vanish without trace), the orchestration reports
success on every guard exit, retries never fire for the statuses that matter, and there is no
idempotency, no kill-switch and no record distinguishing "delivered" from "nothing to do". Azure
Functions hosting is also being retired estate-wide (Modern by Default).

## Proposed Solution

A queue-consuming Boot service (`courtregister.requests`) with a processed-request log,
`(source, requestId)` idempotency, explicit settlement, bounded retries, five named terminal
outcomes, and pre-send schema validation — plus **fixes for the 31 in-service catalogued legacy
defects** and the two appended under review (C35, C36); C18/C28/C34 are externally-owned
remediations tracked before cutover. Each fix is pinned by a test and tracked in
[DEFECT-FIXES.md](DEFECT-FIXES.md) with its sign-off state.

## Scope

The function-app half only: trigger → register assembly → subscription matching → document →
POST. Progression's intake, nightly generation, PDF and e-mail leg is out of scope (evaluated and
deferred in the design doc §10). The prison court register is a separate future migration.

## Key Integrations

Azure Service Bus (inbound commands, producer: `cpp-context-results`); Redis claim-check + results
query API (hearing payload); reference data (`now-subscriptions`); progression command API
(outbound document); Key Vault CSI + workload identity.

## Domain Model

`DistributionCommand` → hearing payload (Jackson tree, never bound to a typed model) → one
`RegisterFragment` (youth-filtered defendant contexts, 18-key vocabulary, three dates) → matched
subscriptions → one `CourtRegisterDocument` (typed records validated against the vendored
progression schemas: hearing venue, recipients, and per-defendant parent guardian, hearing,
aliases, prosecution cases/applications with offences and results, defence counsels). Persistence:
`processed_request` (state machine + claim protocol) and `processed_output` (0..1 per command —
`UNIQUE (source, request_id)` — carrying `request_digest`, `response_code` and the
`anomaly_summary` reason-code counts). Entity detail:
`specs/001-court-register-port/data-model.md`.

## API Surface

None. Actuator only. Contracts in [API_CONTRACTS.md](API_CONTRACTS.md).

## Idempotency

Deterministic `requestId` from `(hearingId, hearingDay, sharedTime)`; broker duplicate detection on
`messageId = source:requestId`; processed-log guard with fingerprint collision detection; a
duplicate POST is absorbed for generation by progression's latest-per-hearing sweep.

## Non-Functional Requirements

~160 commands/day per stack today; single replica processing with `maxConcurrentCalls` 2 (the AKS
fleet's pinned throttle); readiness gates on the store and gated start, never the broker; no
defendant PII in telemetry.

## Risks & Assumptions

| Risk / assumption | Mitigation / owner |
|---|---|
| Content-changing fixes alter what recipients see (dates, verdict code, wording, attendance, ethnicity, recipient sets) | Implemented now, **gated on sign-off before cutover** — per-row markers in the register's Sign-off column (business, progression, IG per Q15) |
| A live subscription relies on an accidental matching route (informant-code equality, vacuous major-creditor flag, non-court-register branches) | The Q9 `now_subscriptions` snapshot verifies before cutover (C4/C5/C30) |
| C24's newline handling could render differently | Verified once against a rendered `OEE_Layout5` PDF before cutover |
| `progression.add-court-register` is append-per-POST | Never shadow-run with sends enabled; duplicates are absorbed for generation by progression's latest-per-hearing sweep (assumption recorded in the spec) |
| A genuinely address-less youth defendant has no agreed fallback | Design-doc Q4 (refuse vs placeholder) stays open and gates cutover; meanwhile the document is an explicit, replayable `FAILED` (C29) |
| The outbound schema pin (`criminal-court-public-model` 17.103.13) could drift from progression's deployed version | The vendored copy is the frozen contract; a bump is a cross-team event with a provenance update |
| C18/C28/C34 remediations live outside this repo | Registered PENDING with owner + trigger; tracked to conclusion before cutover |

## Open Items

The design doc's §13 questions govern (Q4 address-less fallback, Q8 producer typing, Q9 snapshot,
Q11–Q13, Q15, Q16 content sign-offs).

## Out of Scope

Progression-side changes, the prison court register, the SJP pipeline (the court register has no
SJP leg), KEDA autoscaling (chart lacks a ScaledObject template), and the status-reply queue that
was designed but never built for the informant flow.
