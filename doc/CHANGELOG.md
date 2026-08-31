# Changelog

All notable changes to this service are documented here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/); this repository is not yet
released, so everything sits under Unreleased.

## [Unreleased]

### Changed

- 2026-08-31 — **Inbound schema narrowed: `sharedTime` and `hearingDay` state their RFC 3339
  grammar.** Both fields now carry a `pattern` alongside their `format`. `sharedTime` admits the
  `T`/`t` separator only (not the space form of RFC 3339 §5.6's readability note), never a seconds
  field of `60` (a leap second is grammatical in RFC 3339, unrepresentable in `java.time`, and
  deciding which `:60` instants were real needs a leap-second table nobody wants on a contract's hot
  path), and never `-00:00` (RFC 3339 gives it the distinct meaning "offset unknown"). `hearingDay`
  admits RFC 3339 `full-date` only — no expanded or signed year. `format` still does the semantic
  half: an impossible date or hour is refused by it, not by the pattern.

  **Why:** `DistributionCommandSchemaCorpusTest` asserts that the committed schema and the
  hand-written parser agree case for case, and then exempted four bodies from that assertion with a
  written rationale. That is the two authorities disagreeing with a note attached: whichever a reader
  consults, one of them is wrong about what this service accepts. The schema is the authority
  (constitution Principle III), this service owns it, and no producer is live yet — so it was
  narrowed to state what the parser already enforced, rather than the parser being widened to a
  grammar the contract document does not describe. The exemption list is gone and the agreement
  assertion now covers every case in the corpus.

  **Compatibility:** none affected. `cpp-context-results` is not yet publishing, and it serialises
  with `Instant.toString()`, which emits neither a space separator nor a 61st second. No message
  shape that was accepted before and is refused now can be produced by the agreed producer.

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
