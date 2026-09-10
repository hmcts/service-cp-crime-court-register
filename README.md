# service-cp-crime-court-register

When a hearing is resulted on the Common Platform, the court-register flow assembles one register
document per hearing covering **youth defendants only**, matches recipients (Youth Offending Teams)
against NOW-subscription rules keyed on the court centre, batches the documents per (court centre,
register date), renders a PDF at 18:00 each weekday and e-mails it to the matched teams. Today that
flow is split across a Node.js Azure Durable Functions app (assembly and matching) and the
progression context (batching, the nightly PDF through systemdocgenerator, the e-mail through
notificationnotify), and it fails silently at several points on both halves.

This service replaces **both halves** with one Spring Boot pipeline on AKS. It consumes hearing
commands from the Azure Service Bus queue `courtregister.requests`, builds the register from the
Redis claim-check payload (with the results-query fallback), matches subscriptions, validates the
document against the frozen register contract and **records** it in its own store; a service-owned
job at **18:00 Europe/London, Monday to Friday** batches the recorded rows, writes the PDF payload
into the platform file service, asks systemdocgenerator to render the unchanged `OEE_Layout5`
template, learns the outcome from systemdocgenerator's public events, and sends one
notificationnotify e-mail per Youth Offending Team with the PDF attached. Every command and every
batch has a recorded terminal state; nothing is swallowed.

The whole flow is switched between the legacy implementation and this service by **one Azure App
Configuration feature flag, `CourtRegisterService`**, read by the results producer, by the legacy
function-app triggers and by this service's nightly job. Flag on: the producer publishes, the legacy
stands down, this service generates. Flag off: the reverse, and progression's still-scheduled job
generates again. Every failure to read the flag leaves the legacy in charge.

It is deliberately **not** a bug-for-bug port. The defects catalogued in the design are **fixed**,
each with a pinning test and a sign-off state, in the [defect-fix register](doc/DEFECT-FIXES.md);
legacy behaviour remains the oracle for everything not catalogued there. Externally-owned
remediations (the legacy repo's kill-switch, the producer) are registered as pending and tracked to
conclusion before cutover.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.courtregister`                       |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Design

The design lives on Confluence and is the authority for what this service does and why:

**[Court Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004104319/Court+Register+Service)**
(CRA space) — as-is topology and sequence, the to-be architecture, the consolidation of progression's
court-register leg, the one-flag cutover, and the open questions.

This repository carries no design narrative of its own. What it does carry:

| Artefact | Location | Purpose |
|---|---|---|
| **Defect-fix register** | [doc/DEFECT-FIXES.md](doc/DEFECT-FIXES.md) | Every catalogued legacy defect (function-app `C` rows and progression-leg `P` rows), its fix, its pinning test and its sign-off state — the quality gate the constitution enforces |
| Engineering constitution | [.specify/memory/constitution.md](.specify/memory/constitution.md) | The non-negotiable principles (fix-first, TDD, message-contract first, ports and adapters, nothing swallowed, privacy, estate conventions) |
| Specifications | [specs/](specs/) | Spec Kit increments: `001-court-register-port` (complete) and `002-consolidate-progression-leg` (in progress) — spec, plan, research, data model, tasks, checklists |
| Inbound contract | [src/main/resources/contracts/distribution-command.schema.json](src/main/resources/contracts/distribution-command.schema.json) | The `courtregister.requests` message, `additionalProperties: false` |
| Register contract | [src/main/resources/contracts/progression/](src/main/resources/contracts/progression/) | The `courtRegisterDocument/*` schemas frozen at `criminal-court-public-model` 17.103.13, with provenance — enforced at the write into the register store |
| Working conventions | [CLAUDE.md](CLAUDE.md) | Build loop, contract rule, fix-first rule, build and test commands |

## Status

- **Increment 001 — court-register-port: complete.** The intake half is implemented and green
  under the full quality gates: transport with explicit settlement and the durable idempotency guard,
  the ported transformation (fragment build, subscription matching, the twelve-mapper aggregation
  document), contract validation against the vendored schemas, and the terminal-state processed log.
  The differential audit against 381 recorded runs of the real function app found zero unattributed
  differences.
- **Increment 002 — consolidate-progression-leg: complete.** The POST to progression is replaced by
  the register store, with supersession enforced at the write; the nightly job assembles one batch
  per (court centre, register date), writes the PDF payload into the file service, asks
  systemdocgenerator for the unchanged `OEE_Layout5` render, learns the outcome from the
  `public.event` topic — with a grace-period reconciler for the outcomes that never arrive — and
  sends one notificationnotify e-mail per matched Youth Offending Team. The flag gate, the five
  operations commands and the run report land with it, and the progression-leg `P` rows are appended
  to the defect-fix register. The consolidation audit reproduces the recorded progression corpus by
  manifest digest on every build, with one attributed deviation (P10). Task-level detail is the
  checkbox state in `specs/002-consolidate-progression-leg/tasks.md`.
- **Cutover** is a separate step now that both increments are signed off: the producer's queue
  publisher and the legacy kill-switch already exist as patterns; the flag is the only lever. Two
  register rows are tracked to conclusion first — P6 and P7 depend on progression's retirement PR
  merging, which nothing in this repository can assert — alongside the legacy-repo items C18, C28
  and C34 and the SIT→STE replay gate.

This service exposes **no REST API**. The only HTTP surface is Spring Boot Actuator. Operational
actions (regenerate a date, resend a batch's failed notifications, list batches, review rows recorded
while the flag was off) are a CLI baked into the image and run with `kubectl exec`.

## Prerequisites

- ☕️ Java 25 on `PATH` (the build resolves a 25 toolchain; use `./gradlew`, never a system Gradle)
- 🐳 Docker (the compose stack — Postgres and the Service Bus emulator with its SQL Server
  companion — plus the Redis, WireMock and, from increment 002, Artemis and file-service fixtures the
  `*IT` suites start for themselves)

## Quickstart

```bash
./gradlew build                 # compile + tests + PMD + Checkstyle (0 warnings) + JaCoCo gate
./gradlew test                  # test suite only; the *IT suites in it need Docker
./gradlew checkstyleMain        # style gate on main sources
./gradlew pmdMain               # PMD on main sources; `check` runs pmdMain and pmdTest as well
./gradlew jacocoTestReport      # coverage report → build/reports/jacoco
./gradlew bootRun               # local run against docker-compose dependencies (see below)
./scripts/container-smoke.sh    # packaged-artefact smoke: compose up, readiness gate, then
                                # `startup.sh check-flag` through the entrypoint that dispatches
                                # the operations commands
```

Local dependencies:

```bash
docker compose up -d postgres servicebus-emulator
COURTREGISTER_PAYLOAD_MODE=STUB COURTREGISTER_REFERENCEDATA_MODE=STUB \
  COURT_REGISTER_SYSTEM_USER_ID=00000000-0000-0000-0000-000000000000 ./gradlew bootRun
```

Both adapter modes default to `LIVE` — a service that has to be told to fetch payloads is one that
will be deployed not fetching them — so a bare `bootRun` refuses to start: startup demands upstream
endpoints and a `CJSCPPUID`, and compose has neither results nor reference data to call. The `app`
service in `docker-compose.yml` sets the same three variables for the same reason. Generation is
disabled by default in a bare `bootRun`; enabling it demands the file-service datasource, the
broker and the systemdocgenerator and notificationnotify endpoints, for the same reason. The `app`
service does enable it, against the committed stubs - `wiremock` for systemdocgenerator,
notificationnotify and Azure App Configuration, `fileservice-postgres` for the payload store,
`artemis` for `public.event` - with `courtregister.feature.credential=local-test`, which is what
lets the real flag reader read a plain-HTTP stub at all; startup refuses that credential wherever
the endpoint names a real store or the pod is deployed. See
`specs/002-consolidate-progression-leg/quickstart.md` for the whole local loop.

The emulator's queue definition lives in `docker/servicebus-emulator/config.json`; the `*IT` test
fixtures mount the same file, so local, CI and deployed queue properties cannot drift. Compose is
local-only, and `bootRun` does not inherit compose environment variables — which is why the command
above passes them itself.

### Contribute to this repository

See [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md). Repository working conventions live in
`CLAUDE.md`; the engineering constitution is `.specify/memory/constitution.md` and takes precedence
where they overlap.

## Licence

Released under the MIT Licence — see [LICENSE](LICENSE).
