# Specification Quality Checklist: Court Register Service — full pipeline port, fix-first

**Purpose**: Validate specification completeness and quality before planning
**Created**: 2026-08-31 (authored at bootstrap alongside the spec, not generated post-hoc)
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details leak into user stories (technology named only where it is the
      requirement itself — the queue, the 202, the vendored contract)
- [x] Focused on user/operational value (registers delivered; failures visible; fixes audited)
- [x] Mandatory sections completed (user scenarios, requirements, success criteria)
- [x] The fix-first policy is stated as scope, not discovered in requirements

## Requirement Completeness

- [x] Inherited transport requirements are cited to their source spec rather than re-specified
- [x] Every fix-bearing requirement (FR-101…FR-110) names its C-numbers
- [x] Requirements are testable; the plan's test matrix names a test per requirement
- [x] Success criteria are measurable (gates, 34/34 rows, e2e observable, audit report)
- [x] Out-of-scope list is explicit (cutover, producer, legacy repo, Progression leg, KEDA)
- [x] Edge cases enumerate the content-affecting boundaries (group-proceedings typing,
      legal-entity, address-less, the three dates)
- [x] Assumptions record the duplicate-absorption dependency on Progression and the schema
      version pin
- [ ] Business sign-off obtained for content-changing fixes — **deliberately open**; tracked per
      row in `doc/DEFECT-FIXES.md`, gates cutover not implementation

## Feature Readiness

- [x] Plan (constitution check, configuration, structure, test matrix) complete
- [x] Research decisions recorded with alternatives (research.md §1–§15)
- [x] Data model records the one structural delta and the completion-reason vocabulary
- [x] Tasks executed to completion — tracked in tasks.md checkboxes
- [x] Differential audit report committed (`checklists/differential-audit.md`) — Phase 8 output:
      381 recorded legacy runs, zero unattributed differences, and the reverse reconciliation of
      every content-changing register row against the observed diff set

## Notes

Authored at bootstrap together with spec/plan/tasks; the one open box is the increment's known open
end, not a gap in the specification — business sign-off gates cutover, not implementation. Review
rounds are recorded in the phase-final commit narratives rather than re-listed here.

**Re-verified at documentation finalisation (T072, 2026-09-01):** every ticked box above still
holds against the tree as built. The "34/34 rows" measure reads as "all 34 catalogued rows
present": the register has since grown two appended rows (C35, C36) under the constitution's
append mechanism (v2.0.2), which extends the catalogue rather than contradicting the criterion —
every FIXED row's pinning test was verified present in `src/test` by grep. The three unticked
boxes remain deliberately open: business sign-off gates cutover, and the last two close with
Phase 8 (T073–T075, the differential audit).
