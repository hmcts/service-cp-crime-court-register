# Service Identity

- **Service:** service-cp-crime-court-register (deployment/release name `courtregister-service`)
- **Description:** Consumes thin hearing-resulted messages from a dedicated Azure Service Bus queue,
  fetches the hearing payload, rebuilds the court register — **one document per hearing, youth
  defendants only** — matches recipients via NOW-subscription rules keyed on the court centre, and
  POSTs one `add-court-register` command per hearing to `cpp-context-progression`. A fix-first port
  of the court-register function-app pipeline: all 34 catalogued defects (C1–C34) are fixed and
  registered in `doc/DEFECT-FIXES.md` (decision 2026-08-31).
- **Programme:** Crime Common Platform (CPP) — Modern by Default (MbD)
- **Team:** Resulting Assistant
- **Organisation:** HMCTS / Ministry of Justice
- **Jira:** none — this increment carries no ticket; work lands on plain `main`

## Technology Stack

| Component        | Value                                                                 |
|------------------|-----------------------------------------------------------------------|
| Framework        | Spring Boot 4.1                                                       |
| Language         | Java 25                                                               |
| Build tool       | Gradle (NEVER Maven)                                                  |
| Template origin  | `hmcts/service-hmcts-crime-springboot-template`                       |
| Root package     | `uk.gov.hmcts.cp` (service code under `uk.gov.hmcts.cp.courtregister`) |
| Local port       | 8082                                                                  |
| K8s port         | 4550                                                                  |
| HTTP surface     | **Actuator only — no REST API**                                        |
| Inbound transport| Azure Service Bus queue `courtregister.requests` (+ DLQ), `azure-messaging-servicebus` `ServiceBusProcessorClient` |
| Payload source   | Redis (`INT_` claim-check, dated key form first then the legacy undated twin) with results-query-api fallback |
| Outbound         | `POST add-court-register` → `cpp-context-progression` command API (`application/vnd.progression.add-court-register+json`, **202-only success**) |
| Database         | PostgreSQL — service-owned processed-log; **Flyway** migrations (never Liquibase); `processed_output` is `UNIQUE (source, request_id)` — no fan-out |
| Testing          | JUnit Jupiter + Mockito + AssertJ; Testcontainers (`servicebus-emulator`, Postgres, Redis); WireMock |
| Static analysis  | PMD (`.github/pmd-ruleset.xml`, explicit `pmdMain`); Checkstyle (`config/checkstyle/google_checks.xml`, in `check`) |
| CI/CD            | GitHub Actions → ADO Pipeline 460 → `crmdvrepo01.azurecr.io`; deploy via Flux (`springboot-app` chart) |

## Constraints

- NEVER use Maven or Spring Initializr; NEVER scaffold from scratch — the repo derives from the crime
  Spring Boot template via the informant-register reference implementation
- All code in `uk.gov.hmcts.cp.courtregister`
- **No REST API.** Do not add controllers, `springdoc`, or an OpenAPI spec. The inbound contract is
  the queue message; the outbound contract is the progression-owned `add-court-register` command,
  frozen at `criminal-court-public-model` 17.103.13 and vendored under
  `src/test/resources/contracts/progression/`
- **ASB health must never gate readiness** — a broker blip must not restart the pod
- Ports-and-adapters: the application layer depends on interfaces only; Azure/Redis/HTTP types live
  in adapters
- **Fix-first, not parity-first**: the 34 catalogued defects are fixed; every fix has a
  `doc/DEFECT-FIXES.md` row naming its pinning test. Everything NOT catalogued keeps legacy
  behaviour — an uncatalogued change needs written sign-off before merge
- **TDD is mandatory** — failing test first, on every commit; a fix's test must fail against the
  legacy behaviour and pass against the fix
- **NEVER swallow an exception** — silent failure is the defect this service exists to remove; the
  four no-op outcomes are recorded `completion_reason`s, never bare successes
- No defendant PII at `info` level or above — every defendant here is a **youth**
- Conventional commits; **no AI attribution anywhere** (commits, code, docs, PRs)
- Secrets via Key Vault CSI + workload identity — no static keys, no committed connection strings

## Build & Test Commands

```bash
./gradlew build                # Full build (compile + tests + Checkstyle + coverage gate)
./gradlew test                 # Unit + integration tests
./gradlew bootRun              # Run locally (port 8082)
./gradlew pmdMain              # PMD static analysis
./gradlew jacocoTestReport     # Coverage report

# Single test
./gradlew test --tests "uk.gov.hmcts.cp.courtregister.application.DistributionPipelineTest"
```

## Key Documentation

| Document                | Location                  |
|-------------------------|---------------------------|
| Solution Brief          | `doc/SOLUTION_BRIEF.md`   |
| Technical Design        | `doc/TECHNICAL_DESIGN.md` |
| Contracts (in + out)    | `doc/API_CONTRACTS.md`    |
| **Defect-fix register** | `doc/DEFECT-FIXES.md`     |
| Changelog               | `doc/CHANGELOG.md`        |
| Design doc (authoritative current-state + target) | `~/moj/analysis/results-distribution/CourtRegister/service-cp-crime-court-register-design.md` (§2 = legacy behaviour, §7 = the 34 defects) |
