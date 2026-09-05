# Contracts — pointers, not copies

The contracts are not duplicated here to avoid drift.

## Inbound

The queue-message contract is owned by this service, jointly agreed with the producing context:

- Canonical schema: `src/main/resources/contracts/distribution-command.schema.json` (draft-07,
  `additionalProperties: false`, six required fields, optional `userId`, court-register `$id`,
  `eventType` enum `["Hearing_Resulted"]`).
- Prose: the Confluence design page (field semantics, message properties, delivery/settlement,
  the replay rule). (`doc/API_CONTRACTS.md`, cited here originally, was retired on 2026-09-05.)

## Outbound

`progression.add-court-register` is owned by the Progression context and is frozen
(`additionalProperties: false`). This repo vendors the exact compiled version for validation:

- `src/main/resources/contracts/progression/progression.add-court-register.json` +
  `courtRegisterDocument/*.json` — extracted at `criminal-court-public-model` **v17.103.13**
  (see `PROVENANCE.md` beside them; the version is progression `pom.xml` `coredomain.version`,
  re-checked at cutover).
- The pre-send validator (`OutboundContractValidator`, defect fix C29) enforces the same schemas
  at runtime.

## HTTP

None beyond Spring Boot Actuator. (The comment-only `doc/openapi.yaml` placeholder was removed on 2026-09-05; there is no OpenAPI file.)
