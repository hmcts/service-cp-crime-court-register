# Specification Quality Checklist: Release stale in-flight batches before batching

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) - the spec names systemdocgenerator
      and the public-event topic because they are the requirement (the query that goes, the events
      that stay), not the implementation; no class, method, table or setting key is named
- [x] Focused on user value and business needs - a court centre that gets its document tonight
      rather than never, and a Youth Offending Team that is never told twice
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain - the five open points found while writing the spec
      were answered under `/speckit-clarify`, and six more were answered under design review (the
      atomicity of the release, the 18:00 race, whether the late-outcome drop is really counted, the
      operator's batch spanning 18:00, the 07:00 report's kind, and the reversal of the
      retired-vocabulary answer). All eleven are in the two Clarifications sessions and in Assumptions
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified - including the release/outcome race in **both** winner orders, the
      late outcome for a released batch, the operator's batch spanning 18:00, the PENDING batch with
      no payload id, and the flag-OFF night
- [x] Scope is clearly bounded - with a separate "outside this repository" list for the design
      document, the diagram and the environment values
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Validated 2026-09-19 on the first pass.
- This increment is predominantly **subtractive**: a scheduled pass, a lock, a synchronous query, a
  port method, an adapter method, a domain record and a deployment mode all go. The checklist item
  most at risk is therefore "scope is clearly bounded" in the other direction - the spec must say
  what is *kept*, and it does: the event path, the notification leg, supersession, the register
  document, the intake half, the 07:00 report and the cutover lever are all named as untouched.
- Two consequences were found in the code while writing the spec and are requirements rather than
  discoveries for the plan to make: the query-only completion mode cannot survive the query
  (FR-013), and the three in-flight age readings must not go with the timer that took them
  (FR-011).
- **Re-validated 2026-09-19 after two independent design reviews.** Twelve findings applied; the
  substantive ones: the fail-and-release becomes one fenced store statement, because a read-then-mark
  pass strands registers on a crash and lets one refused transition end a whole night's generation
  (FR-003a, SC-009); the "late outcome is counted" guarantee was **not true today** and is made true
  with a new bounded reason (FR-008, SC-010); an operator's batch spanning 18:00 gets the longer
  grace so its render is not orphaned (FR-017); a released batch is informational at 07:00 rather
  than a failure (FR-019); the pass closes the gap the retired reads left for a PENDING batch with no
  payload id (FR-020); and the retired-vocabulary answer is reversed to removal, with its one
  migration caveat recorded (FR-012). One review claim did not check out and is recorded as checked:
  `docker/sdg-echo/sdg-echo.py` does not implement the query endpoint.
- **Re-validated 2026-09-19 after the Phase 1 implementation.** One item moved: FR-012 said the
  admission of the new reason and the removal of the retired vocabulary were the same forward
  migration, and they cannot be — the retired pass is the only writer of the retired values and is
  deleted long after the new reason is first written. FR-012 now asks for two migrations, the spec
  carries a third Clarifications session recording why, and `data-model.md` gives both. The end state
  is unchanged. The checklist item this tested was "requirements are testable and unambiguous": the
  requirement was unambiguous and testable and simply could not be satisfied, which is the kind of
  thing only an implementer holding the compiler finds.
