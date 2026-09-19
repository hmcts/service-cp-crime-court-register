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
      (the release mechanism, what `released` counts, the fate of the retired vocabulary, the fate
      of the query-only deployment shape, and where the report's rendering limit gets its value)
      were each answered under `/speckit-clarify` and recorded in Assumptions
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified - including the release/outcome race, the late outcome for a
      released batch, and the rows that carry values nothing will write again
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
