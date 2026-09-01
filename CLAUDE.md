# service-cp-crime-court-register

Consumes hearing-resulted messages from a dedicated Azure Service Bus queue and produces one
youth-defendant court register submission per hearing to `cpp-context-progression` — a fix-first
port of the court register function app. Thirty-one of the thirty-four catalogued defects are
fixed in this service; C18, C28 and C34 are externally-owned remediations (the legacy function-app
repo and the producer), registered as PENDING and tracked to conclusion before cutover. Every fix
is registered in `doc/DEFECT-FIXES.md`, which now also carries the rows appended under review
(C35, C36) — an appended row carries the same obligations as an original one.

## Programme
Crime Common Platform (CPP) — Modern by Default (MbD)
Team: Resulting Assistant

## Stack
- Spring Boot 4.1, Java 25, Gradle
- Package: uk.gov.hmcts.cp.courtregister
- Port: 8082 (local) / 4550 (Kubernetes)

## Key Documentation
| Document           | Location                      |
|--------------------|-------------------------------|
| Solution Brief     | doc/SOLUTION_BRIEF.md         |
| Technical Design   | doc/TECHNICAL_DESIGN.md       |
| API Contract (OpenAPI) | doc/openapi.yaml          |
| API Contracts (docs)   | doc/API_CONTRACTS.md      |
| Defect-fix register    | doc/DEFECT-FIXES.md       |
| Changelog          | doc/CHANGELOG.md              |

## Message-Contract Rule
This service exposes NO REST API (actuator only). Its inbound contract is the
`courtregister.requests` queue message; its outbound contract is the
Progression-owned `add-court-register` command (frozen, `additionalProperties: false`,
`criminal-court-public-model` 17.103.13, vendored under
`src/main/resources/contracts/progression/`). See `doc/API_CONTRACTS.md`. Contract
changes are cross-team events, agreed jointly with `cpp-context-results` (inbound)
or `cpp-context-progression` (outbound). The spec-validator agent checks contract
compliance, the defect-fix register, and the absence of REST after implementation.

## Fix-First Rule
The legacy JS pipeline is the oracle for every behaviour NOT catalogued in
`doc/DEFECT-FIXES.md`; every catalogued defect is fixed or externally owned, each with a
register row naming its pinning test. A fix without a row is reverted; an uncatalogued behaviour
change needs written sign-off before merge. See constitution Principle I.

## Build & Test
```bash
./gradlew build              # Compile + the full test suite + PMD + Checkstyle (main and test
                             # sources for both) + the JaCoCo coverage gate. Every analysis runs
                             # in `check`; none of them has to be named separately
./gradlew test               # The whole suite: unit and *IT alike — there is no separate
                             # integrationTest task; the Testcontainers suites run here and
                             # need Docker only when those tests are in the selection
./gradlew checkstyleMain checkstyleTest   # Checkstyle alone (google_checks, maxWarnings 0)
./gradlew pmdMain pmdTest    # PMD alone; src/test uses .github/pmd-test-ruleset.xml
./gradlew jacocoTestReport check          # The order CI uses: the coverage report is written
                             # before jacocoTestCoverageVerification reads it, so a failing gate
                             # still leaves a report saying which lines were missed
./gradlew bootRun            # Run locally
```

## Setup

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/001-court-register-port/plan.md` (with `research.md`,
`data-model.md`, `quickstart.md` and `contracts/` alongside it).
<!-- SPECKIT END -->
