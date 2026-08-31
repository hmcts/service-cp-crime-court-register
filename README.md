# service-cp-crime-court-register

When a hearing is resulted on the Common Platform, the court-register flow assembles one register
document per hearing covering **youth defendants only**, matches recipients (Youth Offending Teams)
against NOW-subscription rules keyed on the court centre, and submits it to the progression context
— which batches per (court centre, register date), renders a PDF nightly and emails it out. Today
the first half of that flow is a Node.js Azure Durable Functions app that fails silently by design:
the final POST swallows every error, four separate guards report success when nothing happened, and
a schema-invalid document loses the whole hearing's register without a trace.

This service replaces that function app with a Spring Boot pipeline on AKS: it consumes hearing
commands from the Azure Service Bus queue `courtregister.requests`, builds the register from the
Redis claim-check payload (with the results-query fallback), matches subscriptions, and POSTs
`progression.add-court-register` — with idempotency, explicit settlement, bounded retries, and a
recorded terminal state for every command.

It is deliberately **not** a bug-for-bug port. The thirty-four defects catalogued in the migration
design are **fixed**, each with a pinning test and a sign-off state, in the
[defect-fix register](doc/DEFECT-FIXES.md). Legacy behaviour remains the oracle for everything not
catalogued there.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.courtregister`                       |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Status — court-register-port

The port is being built test-first against the feature specification in
`specs/` — inbound transport and idempotency, then the register pipeline, then the outbound
progression gateway. This service exposes **no REST API**. The only HTTP surface is Spring Boot
Actuator.

## Prerequisites

- ☕️ Java 25 on `PATH` (the build resolves a 25 toolchain; use `./gradlew`, never a system Gradle)
- 🐳 Docker (Service Bus emulator, Postgres, and the `*IT` test fixtures)

## Quickstart

```bash
./gradlew build                 # compile + tests + Checkstyle (0 warnings) + JaCoCo gate
./gradlew test                  # test suite only
./gradlew checkstyleMain        # style gate on main sources
./gradlew pmdMain               # PMD (explicit-only; not part of `check`)
./gradlew jacocoTestReport      # coverage report → build/reports/jacoco
./gradlew bootRun               # local run against docker-compose dependencies
./scripts/container-smoke.sh    # packaged-artefact smoke: compose up + readiness gate
```

Local dependencies:

```bash
docker compose up -d postgres servicebus-emulator
```

The emulator's queue definition lives in `docker/servicebus-emulator/config.json` and is the same
file the `*IT` fixtures mount, so local, CI and deployed queue properties cannot drift. Compose is
local-only; `bootRun` does not inherit compose environment variables.

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
