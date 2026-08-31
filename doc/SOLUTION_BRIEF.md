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
defects** (C18/C28/C34 are externally-owned remediations tracked before cutover), each pinned by a
test and tracked in [DEFECT-FIXES.md](DEFECT-FIXES.md) with its sign-off state.

## Scope

The function-app half only: trigger → register assembly → subscription matching → document →
POST. Progression's intake, nightly generation, PDF and e-mail leg is out of scope (evaluated and
deferred in the design doc §10). The prison court register is a separate future migration.

## Key Integrations

Azure Service Bus (inbound commands, producer: `cpp-context-results`); Redis claim-check + results
query API (hearing payload); reference data (`now-subscriptions`); progression command API
(outbound document); Key Vault CSI + workload identity.

## Domain Model

`DistributionCommand` → hearing payload (Jackson tree) → one `RegisterFragment` (youth-filtered
defendant contexts, vocabulary, three dates) → matched subscriptions → one
`CourtRegisterAggregationRequest` (typed records validated against the vendored progression
schemas). *TODO: entity list finalised with P4/P5.*

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

Content-changing fixes are implemented now and **gated on sign-off before cutover** (see the
register's Sign-off column); the Q9 reference-data snapshot verifies no live subscription depends
on the accidental matching routes; the rendered-PDF check verifies C24's newline handling on
`OEE_Layout5`. *TODO: full table with P7.*

## Open Items

The design doc's §13 questions govern (Q4 address-less fallback, Q8 producer typing, Q9 snapshot,
Q11–Q13, Q15, Q16 content sign-offs).

## Out of Scope

Progression-side changes, the prison court register, the SJP pipeline (the court register has no
SJP leg), KEDA autoscaling (chart lacks a ScaledObject template), and the status-reply queue that
was designed but never built for the informant flow.
