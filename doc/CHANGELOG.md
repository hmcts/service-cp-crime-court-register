# Changelog

All notable changes to this service are documented here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/); this repository is not yet
released, so everything sits under Unreleased.

## [Unreleased]

### Added

- 2026-09-01 — **The differential audit, and the register read adversarially.**
  `DifferentialAuditTest` puts 381 recordings of the real Node function app through this port's
  own chain and requires every difference to name the `doc/DEFECT-FIXES.md` row that allows it; an
  unattributed one is a port defect. Two rows (C35, C36) were appended for shapes the catalogue had
  not, and the report is at `specs/001-court-register-port/checklists/differential-audit.md`. The
  attribution machinery is itself pinned in both directions:
  `RegisteredDefectFixesRejectionTest` reads every registered entry as a control and a near miss,
  so a claim that had quietly become an exclusion fails rather than passes.

- 2026-08-31 — **New alerting instrument `courtregister_intake_suspension_failures_total`.**
  Counted when a store outage asks intake to stop and the processor refuses the stop; the
  lifecycle stays in `SUSPENDING`, retries on the next delivery, and this counter is the loud
  signal that the outage response itself is failing. Part of the transport-review remediation
  (review finding 2); the metric surface is pinned by `ProcessingMetricsTest`.
- 2026-08-31 — **Defect-fix register.** `doc/DEFECT-FIXES.md` catalogues the 34 legacy defects
  (design doc §7, C1–C34) this port fixes rather than reproduces, each with its legacy citation,
  fix specification, planned pinning test and sign-off state. All in-service rows start PLANNED
  and move to FIXED only in the commit whose pinning test passes; content-changing fixes are
  additionally gated on sign-off before cutover; C18/C28/C34 are legacy-repo items tracked as
  pending with owner and trigger. (Two further rows, C35 and C36, were appended under review on
  2026-09-01 — the register is append-only.)
- 2026-08-31 — **Repository scaffold.** Gradle 9.7 / Java 25 / Spring Boot 4.1 build with the
  estate quality gates (Checkstyle google_checks at zero warnings, JaCoCo line 0.88 / branch 0.85,
  PMD — explicit-only at scaffold time, moved into `check` on 2026-09-01, see Changed), CI
  workflow set, Service Bus emulator compose stack, and the container smoke script — cloned from
  the informant-register reference implementation and renamed for the court register (queue
  `courtregister.requests`, package `uk.gov.hmcts.cp.courtregister`).

### Changed

- 2026-09-01 — **Two port defects the audit caught, in code the pinning suites had agreed with.**
  `JsStrings.jsTrim` trimmed with Java's `String.trim()` where the legacy trims with
  `String.prototype.trim()`; the two disagree in both directions, so a child's name reached the
  register still padded with the U+00A0 it arrived in. It is now ECMA-262's own whitespace set, and
  the email trim behind C27 gets it too. And C11's file name took its day from London's calendar
  rather than the share instant, so a 23:00Z share was filed under the following day — and under a
  different day from the subscription set it was addressed by. `Dates.registerDay` answers the UTC
  day and is asserted equal to `subscriptionDay` on the same value. C10 and C11 were amended to
  record what the audit found.

- 2026-09-01 — **Documentation finalised to as-built.** `TECHNICAL_DESIGN.md`, `API_CONTRACTS.md`
  and `SOLUTION_BRIEF.md` lose their "target design" banners and TODO markers: the state machine
  (both `PROCESSING_DEADLINE_EXCEEDED` origins), the shared `RetryPolicy`, the fenced
  `processed_output` claim with `anomaly_summary`, the composition wiring, the porting map and the
  full configuration table (including the renamed back-off keys) are documented as implemented.
  `DEFECT-FIXES.md`: every pinning test re-verified against `src/test` by grep; C16 moves
  PLANNED → FIXED (the fix-by-omission is complete in-repo — secrets-scanner gate plus the
  startup refusal; rotation of the legacy-exposed keys stays externally tracked); C15 and C16 now
  cite their pinning tests by the names the suites actually carry; C35 records its landed fix.
  README's Status section describes the finished increment. No code change.

- 2026-09-01 — **The configuration says what it does, and the log surface is asserted rather than
  assumed.** `application.yaml` now documents `courtregister.referencedata.headers`, bound and empty
  since the reference-data client landed and until now the one setting the file did not mention —
  written up, like its `courtregister.progression` twin, as the place the mesh's undocumented
  authorisation scheme goes without a code change — and gains a
  note on the `logging:` block saying that nothing below INFO may be shipped, which is now a test
  rather than an intention. `courtregister.submission.validate-outbound` is documented for what it
  is: a **startup rule**, refusing `false` wherever the deployed credential source is in use, and
  not a runtime switch. The validator is wired unconditionally in `PipelineConfig` and no bean reads
  the value, because a wiring that honoured it would put C29's blind spot back behind one property.
  Not operator-affecting: no default changes, and no key is added or removed.

- 2026-09-01 — **Two parity WARNs stop naming the reference they skipped.** The transformation's
  unnamed-application-case warning carried `applicationId=`, and the ported SNI-9005 guard carried
  the prosecution case id in the legacy's `[Case ID: …]` form. Neither identifier is in Principle
  VII's permitted correlation set (`requestId`, `hearingId`, `hearingDay`, `source`, court-centre id
  / OU code, counts, timings), and neither is authorised by a defect-fix row: the one row that
  authorises an id in a log line is C20, and that is a different warning — the unresolvable
  *application* line in `ProsecutionCaseOrApplicationMapper`, which is unchanged. Both lines still
  say which guard fired and that something was skipped, and `requestId`/`hearingId` reach them
  through the MDC from the run-aware layer, as they do for every transformation log line. A sweep of
  the other new adapter and parity warnings found no further instance. Not operator-affecting beyond
  the log text, which now carries less.

- 2026-09-01 — **C3's shared retry policy is now actually shared, and it costs three renamed
  settings.** The defect-fix register promises "a config-driven retry policy applied identically to
  all three named clients"; what the three shared was the status taxonomy, and nothing else. The
  progression gateway had a doubling back-off and read `Retry-After`; the payload fallback and the
  reference-data read kept the legacy's fixed one-second interval and ignored the header. All three
  now hold one `RetryPolicy` object, which owns the taxonomy, the doubling back-off bounded by
  `max-backoff`, and `Retry-After` — honoured on **every** retryable answer rather than only on a
  429, in delta-seconds only (an HTTP-date is classified, never parsed), and capped by the same
  ceiling.

  **Operator-visible, and a breaking rename.** `courtregister.payload.fallback.retry-interval` and
  `courtregister.referencedata.retry-interval` are **gone**, replaced by `initial-backoff` (default
  `1s`) and `max-backoff` (default `2s`) — the same two keys, with the same meanings, that
  `courtregister.progression` already had. An environment still setting `retry-interval` is setting
  a key nothing reads. `courtregister.progression.max-backoff` drops from **20s to 5s**, for the
  arithmetic below.

  **The startup budget is stricter, because the policy is more generous.** A `Retry-After` is now
  honoured on every retryable answer in every client, and the only thing bounding what a remote
  service can ask for is `max-backoff` — so the worst case for a wait is the ceiling, not the
  doubling schedule, and `PropertiesValidator` now budgets `(max-attempts - 1) × max-backoff` per
  client. The shipped numbers satisfy it with room: a payload fetch of 69s (20s of cache reads,
  three attempts of 5s + 10s, two waits of 2s), a now-subscriptions read of 49s, a submission of 75s
  (four attempts of 5s + 10s, three waits of 5s) and the fixed 30s margin come to 223s against a
  four-minute deadline. An environment that lengthens an attempt count or a ceiling must keep the
  sum inside the deadline or the pod refuses to start, with a message naming each step's
  contribution.

- 2026-09-01 — **A read the other side refused is parked, not redelivered to exhaustion.** The
  payload query and the reference-data clients already knew which statuses were worth asking again —
  the C3 taxonomy — but threw the same always-TRANSIENT failure whichever way the answer went. A
  4xx the other side understood and declined is refused identically on every redelivery, so the
  delivery budget was spent re-reading it and the request parked at the end under
  `DELIVERY_LIMIT_EXHAUSTED`, which tells support the service ran out of tries rather than that its
  credential or its route is wrong. Both exceptions now carry the classification the throw site
  chose, as the submission failure already did, and the pipeline parks a refusal immediately.

  **Operator-visible:** two new bounded reason codes, `PAYLOAD_READ_REFUSED` and
  `REFERENCE_DATA_REFUSED`, appear on `processed_request.failure_reason` and on dead-letter
  descriptions; a request in either state is dead-lettered on its first delivery rather than its
  fifth. A rise in them is a credential or a route to look at, where a rise in
  `PAYLOAD_UNAVAILABLE` is a producer or a cache, and a rise in `REFERENCE_DATA_UNAVAILABLE` is
  reference data's own health. **The payload query's 404 is unchanged** — the resource is
  per-hearing, so its absence stays the empty answer that becomes a transient `PAYLOAD_UNAVAILABLE`
  only once the cache has missed too (C32). Reference data's 404 is a refusal, because that resource
  always exists and a 404 on it is a misconfigured path. `doc/DEFECT-FIXES.md` rows C3 and C32 are
  amended to record both.

- 2026-09-01 — **A claimed submission row is always settled, and a settlement the log refuses hands
  the delivery back.** Two holes on the same path. A failure nothing anticipated — anything other
  than the classified `SubmissionFailedException` — escaped the submission leg after the
  `processed_output` row had been claimed, leaving PENDING behind with nothing going to finish it;
  the row is now moved to FAILED, by type and a bounded reason and never the failure's own words,
  before the failure continues upward unchanged. And a fenced `recordPosted`/`recordFailed` that
  affected no row was an ERROR line the code then ignored: it means an overlapping delivery reached
  the row first, or this runner's claim was reclaimed while it worked, so what the runner believes
  happened is not what the log says. Both cases now hand the delivery back TRANSIENT — including
  where the write that failed was recording a NON_TRANSIENT refusal, because parking is a terminal
  verdict and this runner's verdict is not the recorded one.

  **Operator-visible:** a run in either state is RETRYING rather than COMPLETED `submitted`, and the
  redelivery decides from the row: POSTED is terminal and its claim is refused, so the replay
  completes `submitted` with no second POST; PENDING or FAILED is re-claimed and re-sent, the
  crash-window trade this service already makes — a duplicate progression's
  `max(register_time) per hearing_id` sweep absorbs, in preference to a loss nothing absorbs.

- 2026-09-01 — **The submission's retries are bounded by the run's claim, not only by
  `max-backoff`.** The processing deadline was read before the POST started and never again, so the
  transport's own policy — up to `courtregister.progression.max-attempts` attempts, each able to
  spend a connect and a read timeout, with a doubling back-off or a server-chosen `Retry-After`
  between them — could keep waiting long after the run had promised to stop. A POST made then is a
  POST made under a claim another delivery may already hold, and `add-court-register` *appends*: the
  second runner's send is a second register for the hearing. The run now hands the instant its
  budget ends to the submission port, and the transport reads it **before every attempt and before
  every wait**, the back-off's and progression's alike. A wait that would end after the deadline is
  refused rather than shortened — a truncated wait would hammer a service that has just asked for
  room.

  **Operator-visible:** an overrun inside the submission is reported as the TRANSIENT reason
  `PROCESSING_DEADLINE_EXCEEDED`, which until now only a run that overran *outside* the POST could
  produce; the delivery is handed back and the redelivery gets a whole fresh budget with nothing sent
  twice. It is deliberately not `SUBMISSION_TRANSIENT` — a rise in this code is a capacity signal
  about this service, where a rise in that one is a signal about progression.

- 2026-09-01 — **Static analysis now gates `src/test`, and the coverage report is written before
  the gate reads it.** `pmdMain` loses the `onlyIf` that kept it out of `build` and joins `check`
  alongside a newly enabled `pmdTest` (its own ruleset, `.github/pmd-test-ruleset.xml` — the main
  ruleset minus rules inapplicable to test code) and a newly enabled `checkstyleTest` (with
  `config/checkstyle/checkstyle-suppressions.xml`, which suppresses only `MethodName` over test
  sources, for the underscore-separated naming convention). PMD is pinned to 7.22.0. Turning the
  two on surfaced 255 Checkstyle warnings across 36 files and 36 PMD findings; all were fixed in
  the test sources rather than excluded. `./gradlew build` now runs every analysis this repo has —
  the standing note that PMD had to be named explicitly no longer applies.

  Coverage: `jacocoTestCoverageVerification` is ordered after `jacocoTestReport`, and CI asks for
  them in that order, so a build that fails the gate still leaves a report saying which lines were
  missed; that report is uploaded with the test reports. A pull request that lowers a `minimum`
  threshold in `gradle/test.gradle` is now failed by a ratchet guard in the Test job, which names
  the before and after values — Principle VIII's "never loosened in passing" made checkable.

  What was deliberately **not** taken from `service-cp-crime-hearing-results-validator` in the same
  pass — renovate (that repo has none either; dependabot stays), the API-Test job and API-spec
  version validation (this service publishes no spec), entity/persistence coverage exclusions (a
  loosening in configuration's clothing), Drools excludes, and a version-catalog migration (which
  would end dependabot's version updates) — is recorded as decision 17 in
  `specs/001-court-register-port/research.md`.

- 2026-09-01 — **One time budget for a whole run, and shorter read timeouts to fit inside it.**
  The processing deadline used to be read once, after the payload fetch; the now-subscriptions read,
  the transformation and the `add-court-register` POST that follow it were bounded only by their own
  timeouts. A run could therefore start its POST after its claim had become reclaimable, and
  progression's `add-court-register` appends a register rather than replacing one — so a second
  runner taking the same request adds a second register for the hearing rather than overwriting the
  first. The run now reads what is left of its budget before the transformation, before the send and
  before every outcome write, and records an overrun as a TRANSIENT
  `PROCESSING_DEADLINE_EXCEEDED` so the delivery is redelivered with a whole fresh budget. The one
  write never withheld is the completion of a register that *was* sent.

  Startup gained the matching rule: the payload fetch, the now-subscriptions read and the submission
  worst cases plus a fixed 30s margin for the guard's writes and the transformation must be strictly
  shorter than `courtregister.claim.processing-deadline`. The three per-step rules stay — they name
  the offending step precisely — but they could each pass while the run as a whole could not, which
  is what the shipped numbers did.

  **Operator-visible:** `courtregister.payload.fallback.read-timeout`,
  `courtregister.referencedata.read-timeout` and `courtregister.progression.read-timeout` drop from
  **30s to 10s**, which is what makes the shipped configuration satisfy the new rule
  (67s + 47s + 63.5s + 30s against a four-minute deadline). An environment that overrides any of
  them — or lengthens an attempt count, a retry interval or a back-off — must keep the sum inside
  the deadline or the pod will refuse to start, with a message naming each step's contribution.

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

