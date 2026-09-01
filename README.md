# service-cp-crime-court-register

When a hearing is resulted on the Common Platform, the court-register flow assembles one register
document per hearing covering **youth defendants only**, matches recipients (Youth Offending Teams)
against NOW-subscription rules keyed on the court centre, and submits it to the progression context
— which batches per (court centre, register date), renders a PDF nightly and emails it out. Today
the first half of that flow is a Node.js Azure Durable Functions app that fails silently by design:
the final POST swallows every error, four separate guards report success when nothing happened, and
a schema-invalid document loses the whole hearing's register without a trace.

This service replaces that function app with a Spring Boot pipeline on AKS. The target design:
consume hearing commands from the Azure Service Bus queue `courtregister.requests`, build the
register from the Redis claim-check payload (with the results-query fallback), match
subscriptions, and POST `progression.add-court-register` — with idempotency, explicit settlement,
bounded retries, and a recorded terminal state for every command. It lands phase by phase per
`specs/001-court-register-port/tasks.md`; the Status section below says what exists today.

It is deliberately **not** a bug-for-bug port. Of the thirty-four defects catalogued in the
migration design, the thirty-one that live in this service are **fixed**, each with a pinning test
and a sign-off state, in the [defect-fix register](doc/DEFECT-FIXES.md); the other three
(C18/C28/C34) are externally-owned remediations the register tracks to conclusion before cutover.
Legacy behaviour remains the oracle for everything not catalogued there.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.courtregister`                       |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Status — court-register-port (P0 bootstrap)

What exists today: the Boot shell (an empty application that builds green under the full quality
gates), the governance layer (constitution, Spec Kit feature docs, the defect-fix register), and
the vendored outbound contract. Everything else on this page is the **target design**, being built
test-first against the feature specification in `specs/001-court-register-port/` — inbound
transport and idempotency, then the register pipeline, then the outbound progression gateway.
This service exposes **no REST API**. The only HTTP surface is Spring Boot Actuator.

## Prerequisites

- ☕️ Java 25 on `PATH` (the build resolves a 25 toolchain; use `./gradlew`, never a system Gradle)
- 🐳 Docker (the compose stack — Postgres and the Service Bus emulator with its SQL Server
  companion — plus the Redis and WireMock fixtures the `*IT` suites start for themselves)

## Quickstart

```bash
./gradlew build                 # compile + tests + PMD + Checkstyle (0 warnings) + JaCoCo gate
./gradlew test                  # test suite only; the *IT suites in it need Docker
./gradlew checkstyleMain        # style gate on main sources
./gradlew pmdMain               # PMD on main sources; `check` runs pmdMain and pmdTest as well
./gradlew jacocoTestReport      # coverage report → build/reports/jacoco
./gradlew bootRun               # local run against docker-compose dependencies (see below)
./scripts/container-smoke.sh    # packaged-artefact smoke: compose up + readiness gate
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
service in `docker-compose.yml` sets the same three variables for the same reason.

The emulator's queue definition lives in `docker/servicebus-emulator/config.json`; the `*IT` test
fixtures mount the same file, so local, CI and deployed queue properties cannot drift. Compose is
local-only, and `bootRun` does not inherit compose environment variables — which is why the command
above passes them itself.

## Documentation

| Document                                          | Location                                           |
|---------------------------------------------------|----------------------------------------------------|
| Solution brief                                    | [doc/SOLUTION_BRIEF.md](doc/SOLUTION_BRIEF.md)     |
| Technical design                                  | [doc/TECHNICAL_DESIGN.md](doc/TECHNICAL_DESIGN.md) |
| Contracts (inbound message and outbound command)  | [doc/API_CONTRACTS.md](doc/API_CONTRACTS.md)       |
| **Defect-fix register** — the 34 legacy defects this port fixes, each with its pinning test and sign-off state | [doc/DEFECT-FIXES.md](doc/DEFECT-FIXES.md) |
| Changelog                                         | [doc/CHANGELOG.md](doc/CHANGELOG.md)               |

Repository working conventions live in `CLAUDE.md`; the engineering constitution —
including the defect-fix-first rule the register above enforces — is
`.specify/memory/constitution.md` and takes precedence where they overlap.

### Contribute to this repository

See [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md).

## Licence

Released under the MIT Licence — see [LICENSE](LICENSE).
