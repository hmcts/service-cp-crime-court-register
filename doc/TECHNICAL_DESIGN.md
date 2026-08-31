# Technical Design — service-cp-crime-court-register

## Overview

A message-driven Spring Boot service that replaces the court-register Azure Durable Functions
pipeline. One command per resulted hearing arrives on `courtregister.requests`; the service fetches
the hearing payload (Redis claim-check, results-query fallback), builds the single youth-defendant
register fragment, matches Youth Offending Team subscriptions via reference data, maps the
aggregation document and POSTs `progression.add-court-register`. Progression's nightly generation,
PDF rendering and e-mail leg is unchanged. All thirty-four catalogued legacy defects are fixed
([DEFECT-FIXES.md](DEFECT-FIXES.md)); legacy behaviour is the oracle for everything not catalogued.

## The change in one picture

*TODO: ASCII before/after diagram lands with P7 (see design doc §4.1 for the source picture).*

## Architecture — ports and adapters pipeline

Core use-case (`DistributionPipeline`) depends on four ports only: `HearingPayloadSource`,
`NowSubscriptionsSource`, `RegisterTransformer` (pure — no I/O, no clock, no randomness),
`RegisterSubmissionClient`. Adapters: Lettuce + results-query (payload), reference data client
(subscriptions), progression gateway (submission), stubs for local/test profiles. The application
layer never imports ASB, Redis, HTTP or JDBC types.

## Package Structure

`uk.gov.hmcts.cp.courtregister.{inbound, application, domain, pipeline, adapter, persistence,
config}` — the informant-register reference layout with the pipeline package rewritten for the
court register's shape (one fragment per hearing, youth filter, 12 aggregation mappers).
*TODO: full tree with per-class responsibilities lands with P7.*

## Processing State Machine

`RECEIVED → RETRYING → COMPLETED | FAILED` on `processed_request`, claim-protocol columns for the
single-runner guarantee, and **five** bounded completion reasons: `submitted`, `group-proceedings`,
`no-defendants`, `no-subscriptions`, `no-youth-defendants` — the last four replacing the legacy's
undifferentiated `Success: true` (defect-fix C33). Success on the POST is 202 and nothing else.
*TODO: decision tree lands with P3.*

## Queue and message

`courtregister.requests` (+ DLQ): `maxDeliveryCount` 5, duplicate detection on (immutable at
creation), lock PT1M with SDK auto-renewal validated to outlive the processing deadline. Contract
in [API_CONTRACTS.md](API_CONTRACTS.md).

## Idempotency

`(source, requestId)` composite key on the processed log; SHA-256 request fingerprint; duplicate
delivery re-publishes the terminal status; fingerprint mismatch dead-letters without overwriting
(defect-fix C17). `processed_output` carries at most one row per command — the court register has
no fan-out — keyed `UNIQUE (source, request_id)` with the court centre, register date and file
name as descriptive columns.

## Porting map (JS → Java)

Design doc §5 governs. `pipeline/` classes are one-per-legacy-activity/mapper so the Jest → JUnit
twin mapping stays 1:1; JS-semantics shims (`Json`, `JsStrings`) carry the truthiness/trim rules.
*TODO: the table lands as the pipeline phases complete.*

## Quality gate — defect-fix-first with a differential audit

Every legacy Jest case has a JUnit twin (as-is, repointed at a registered fix, or repaired where
the legacy test was vacuous); the 34 fixes are pinned by the tests named in
[DEFECT-FIXES.md](DEFECT-FIXES.md); a differential audit against the recorded legacy oracle must
attribute **every** output difference to a C-number — an unattributed difference is a port defect.

## Configuration

`courtregister.*` typed properties with startup refusals: exactly one Service Bus credential
source; stub modes refused in deployed environments; `system-user-id` required; worst-case fetch
times validated against the processing deadline. *TODO: full key table lands with P7.*

## Testing Strategy

`*Test` = no Docker (unit / WireMock / test-profile Spring context); `*IT` = Testcontainers
(Postgres 16, Service Bus emulator + Toxiproxy, Redis 7) via static per-JVM fixtures. Fixtures are
copied from the legacy repo where sound and rebuilt where the twin map found them stale (the 18-key
vocabulary, complete court centres).

## Observability

Micrometer → Prometheus + App Insights agent; JSON logs with `source`/`requestId`/`hearingId`/
`hearingDay` in MDC and **no defendant PII at INFO or above**; completion-reason distribution
metric; submission-to-row reconciliation is the C29 detector. DLQ depth from Azure Monitor.

## Security

Workload identity + Key Vault CSI; verified TLS to Redis (defect-fix C15); no secrets in the repo
(C16); the `CJSCPPUID` system identity injected, required at startup.

## Deployment

GitHub CI → ADO pipeline 460 → ACR; Flux + `springboot-app` chart, port 4550, `replicas` per the
informant precedent (see design doc §4.7 note). *TODO: finalise with P7.*

## Cutover and rollback

One lever: the `CourtRegisterService` App Configuration flag read by the producer's queue publisher
and both legacy triggers (the trigger wiring is legacy-repo work, defect-fix C18). Never
shadow-run with sends enabled — `progression.add-court-register` is not idempotent; duplicates are
absorbed for generation by progression's latest-per-hearing sweep but still land as rows.
