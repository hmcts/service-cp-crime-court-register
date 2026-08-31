# Contracts — pointers, not copies

The contracts are not duplicated here to avoid drift.

## Inbound

The queue-message contract is owned by this service, jointly agreed with the producing context:

- Canonical schema: `src/main/resources/contracts/distribution-command.schema.json` (draft-07,
  `additionalProperties: false`, six required fields, optional `userId`, court-register `$id`,
  `eventType` enum `["Hearing_Resulted"]`).
- Prose: `doc/API_CONTRACTS.md` §1 (field semantics, message properties, delivery/settlement,
  the replay rule).

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

None beyond Spring Boot Actuator (see `doc/openapi.yaml` — deliberately a comment block only).
