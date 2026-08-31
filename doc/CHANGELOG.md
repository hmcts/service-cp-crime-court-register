# Changelog

All notable changes to this service are documented here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/); this repository is not yet
released, so everything sits under Unreleased.

## [Unreleased]

### Added

- 2026-08-31 — **Repository scaffold.** Gradle 9.7 / Java 25 / Spring Boot 4.1 build with the
  estate quality gates (Checkstyle google_checks at zero warnings, JaCoCo line 0.88 / branch 0.85,
  PMD explicit-only), CI workflow set, Service Bus emulator compose stack, and the container smoke
  script — cloned from the informant-register reference implementation and renamed for the court
  register (queue `courtregister.requests`, package `uk.gov.hmcts.cp.courtregister`).
- 2026-08-31 — **Defect-fix register.** `doc/DEFECT-FIXES.md` catalogues the 34 legacy defects
  (design doc §7, C1–C34) this port fixes rather than reproduces, each with its legacy citation,
  fix specification, planned pinning test and sign-off state. All in-service rows start PLANNED
  and move to FIXED only in the commit whose pinning test passes; content-changing fixes are
  additionally gated on sign-off before cutover; C18/C28/C34 are legacy-repo items tracked as
  pending with owner and trigger.
