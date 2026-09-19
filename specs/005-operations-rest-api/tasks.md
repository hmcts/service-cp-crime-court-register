# Tasks: Operations REST API, replacing the operations CLI

**Input**: design documents from `/specs/005-operations-rest-api/`
**Prerequisites**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`

**Tests are MANDATORY** (constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix in the same commit.

**Red-run convention (every test task)**: the test task includes the minimal **compile-safe seams**
its test needs — interface declarations, record signatures, class skeletons whose methods throw
`UnsupportedOperationException` — so the recorded red run is a **failing assertion**, never a
missing class or a compile error. The failing assertion is quoted in the test task's commit
narrative; the paired implementation task's narrative quotes the green run.

**[A] tasks** are acceptance/characterisation: they verify assembled behaviour and record the
observed result rather than a red run. No implementation task follows one.

### Approved TDD exceptions

**None in advance.** Two task kinds are exempt by the constitution's mechanical exemption and say so
where they appear: the dependency/wiring tasks of Phase 1 (which record verification evidence) and
the deletion tasks of Phase 10 (a deletion has no red run — its evidence is that the suite the
deleted code was proven by is gone with it and the build is green). Everything else is a pair. If a
pair cannot be formed, the exception is written into **this section** with the design owner's dated
approval **before** the commit lands — never argued for afterwards in a commit body.

### Two standing rules for this increment

1. **No `doc/DEFECT-FIXES.md` row is added, amended or flipped anywhere.** There is no legacy oracle
   for an operational surface. `RegisteredDefectFixes` and `DifferentialAuditTest` are untouched and
   must stay green. A task that finds itself wanting a `C` or `P` number has found a defect in
   001–003, not in this increment, and it stops and asks.
2. **The coordination contract in `plan.md` is binding.** 005 must not touch
   `batch/RegisterGenerationJob`, `batch/GenerationReconciler`, `application/DocumentRenderer`,
   `adapter/systemdocgenerator/*`, the `courtregister.generation.*` block of `application.yaml`, or
   README's generation section. Where a task is near one of those, it says so.

**Conventions**: package root `uk.gov.hmcts.cp.courtregister`; production code under
`src/main/java/uk/gov/hmcts/cp/courtregister/`, tests under `src/test/java/uk/gov/hmcts/cp/courtregister/`.
`*IT` suites need Docker and run inside `./gradlew test`. Conventional Commits on
`005-operations-rest-api`; accepted types `feat`, `fix`, `chore`, `docs`, `test`, `refactor`,
`build`, `ci`, `style`. No AI attribution in any commit, comment, document or test name. Every
`./gradlew` invocation goes behind the shared `flock`. Every phase ends with a green
`./gradlew build` and a review gate before the next phase starts. **Never two committing agents at
once.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no
  dependency on an unfinished task)
- **[A]**: acceptance/characterisation
- **[US#]**: the spec user story the task traces to

---

## Phase 0: Governance (1 task)

**Purpose**: close the constitution's own amendment procedure before any code lands. Step 1 (the
proposal) is in `spec.md`; step 2 (the bump) is commit `d73ef50`; step 3 is this.

- [x] **T001** [A] **Run `/speckit-analyze` for `specs/005-operations-rest-api/`, and again for the
      concurrent `specs/004-release-stale-batches/`** (in the main checkout —
      `/home/sachin/moj/service-cp-crime-court-register`, **read-only**: never edit that tree from
      this branch). Governance step 3 requires every in-flight spec to be re-checked against the
      amended principles and each conflict updated or explicitly waived. Update this spec where the
      analysis finds a conflict; for 004, **record** each conflict in this task's commit narrative
      for the orchestrator to act on. The known one to look for: 004's run-report line and 005's
      operator trigger both touch `domain/RunReport` (`batch/` in an earlier draft of this task and
      of the plan; the class has always been in `domain/`), and 004's pre-batching pass must skip
      operator-initiated batches (plan, coordination contract). Evidence: the analyse output for
      both specs, and the conflict list.

---

## Phase 1: The two starters, the component scan, and the settings (7 tasks)

**Purpose**: get the dependencies onto the classpath **without the context failing**, and make every
start-up refusal that the amendment's condition (b) requires. Three of research's findings are
context-will-not-start traps and all three are closed here. Phase 1's wiring tasks carry the
mechanical exemption and record verification evidence; T004/T005 and T006/T007 are ordinary pairs.

- [x] **T002** [US1] `config/AuditComponentScanTest` (new) — **the context starts, and holds no
      component-scanned audit bean**. `@SpringBootTest` over the application's own configuration
      with the operations and audit switches off, asserting the context starts and that no bean of
      type `uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser` was created by scanning.
      Seam: none — the assertion is about the context that already exists.
      Red: passes today (there is no such dependency), and **fails the moment T003 adds it**, which
      is the point: the task order is test-then-dependency so the failure is observed rather than
      predicted. Record both runs.
      (run on this commit's tree: `./gradlew test --tests '...AuditComponentScanTest'` - 3 tests,
      0 failures, all three PASSED, the predicted green-today. The context refreshed in 2.064s on
      the `test` profile with `cp.audit.enabled=false`, `audit.http.enabled=false`,
      `authz.http.enabled=false` and `courtregister.operations.enabled=false`; nothing on the
      classpath is in `uk.gov.hmcts.cp.filter.audit`, so `auditStarterBeans()` is empty for the
      only reason it can be today. The second of the three runs - the one that fails - is recorded
      against T003, which is where the dependency arrives. Deviation from the task text, additive:
      the assertion is written over the whole `uk.gov.hmcts.cp.filter.audit` package rather than
      over `OpenApiSpecificationParser` alone, and by **class name** rather than by type, because a
      `.class` literal would not compile in the state this suite is deliberately written in. The
      named parser keeps a case of its own.)
- [x] **T003** [US1] **Add the dependencies and close the component-scan clash.**
      `gradle/libs.versions.toml`: `cp-auth-rules-filter = "1.0.7"`,
      `cp-audit-filter-springboot = "1.0.5"`, plus the `uk.gov.hmcts.cp:` module lines;
      `build.gradle`: `implementation libs.cp.auth.rules.filter`,
      `implementation libs.cp.audit.filter.springboot`, and `spring-boot-starter-web` **if it is not
      already a first-class dependency** (check first; actuator alone will not map a controller).
      `src/main/java/uk/gov/hmcts/cp/Application.java`: the `@ComponentScan` exclude filter for
      `uk\\.gov\\.hmcts\\.cp\\.filter\\.audit\\..*`, verbatim from research R9 — `@AutoConfiguration`
      classes are not subject to `@ComponentScan` filters, so the auto-configuration still runs.
      `application.yaml`: `cp.audit.enabled: false` for the local and test profiles (research R6 —
      the library is **on** by default and validates its Artemis settings even with HTTP audit off).
      Green: T002 passes again with the dependency present. Evidence: the failing run between T002
      and the exclude filter, quoted.
      (red, with both starters on the classpath and no exclude filter: `AuditComponentScanTest`
      3 tests, 3 failed - *"Error creating bean with name 'openApiSpecificationParser' defined in
      URL [jar:.../cp-audit-filter-springboot-1.0.5.jar!/uk/gov/hmcts/cp/filter/audit/parser/
      OpenApiSpecificationParser.class]: Unsatisfied dependency expressed through constructor
      parameter 1: No qualifying bean of type 'java.lang.String' available"*, with
      `cp.audit.enabled=false` and `audit.http.enabled=false` set, which is the point: the scan
      answers to neither.
      Then a **second red the task did not predict**, and the one worth keeping: with the filter
      added verbatim from research R9 the context still failed, now on *"The bean 'objectMapper',
      defined in class path resource [.../ReportEmailConfigTest$EmailWiringTestConfiguration.class],
      could not be registered. A bean with that name has already been defined in class path
      resource [.../LiveNotificationConfigTest$NotificationTestConfiguration.class]"*. Declaring a
      `@ComponentScan` **replaces** the one `@SpringBootApplication` carries, and with it the
      `TypeExcludeFilter` that keeps one slice suite's nested `@Configuration` out of another's
      context. R9's snippet - and the reference implementation it is copied from - omits both of
      Boot's own filters; this repository's suites are what notice. Fixed by restating
      `TypeExcludeFilter` and `AutoConfigurationExcludeFilter` beside the audit regex, which is the
      documented recipe. Research R9 is left as the record of what the reference implementation
      does; the deviation is in `Application`'s javadoc, where somebody deleting a filter will read
      it.
      Green: `AuditComponentScanTest` 3 tests, 0 failures; then the whole suite behind the shared
      lock, `./gradlew build -Dtest.noFailFast=true` **exit 0** in 10m 35s - so neither starter
      disturbs the existing classpath (the audit library's Jackson 2 alongside this service's
      Jackson 3 was the risk, and it does not materialise).
      `spring-boot-starter-web` was already a first-class dependency, so it is not added; the two
      starters go in a new `gradle/libs.versions.toml` as the task says, which is not a breach of
      `build.gradle`'s "keep every dependency here" comment - that comment is about the
      `apply from:` files, which dependabot cannot read, and a version catalogue is one it can.)
- [x] **T004** [P] [US1] `config/OperationsPropertiesTest` (new) and `config/ConfigurationValidationTest`
      (extend) — **the defaults and the refusals**. Defaults off an `ApplicationContextRunner`:
      `courtregister.operations.enabled=true`, `supersede-max-age=30d`, `lock-wait=0s`. Refusals,
      each asserting the message **names the offending setting**:
      `operations_enabled_with_http_audit_disabled_refuses_to_start`,
      `operations_enabled_with_an_unconfigured_audit_transport_refuses_to_start`,
      `http_audit_enabled_with_no_openapi_spec_key_refuses_to_start`,
      `a_zero_supersede_max_age_refuses_to_start`,
      `a_negative_lock_wait_refuses_to_start`.
      Seams: `config/OperationsProperties` declared with its components and **no** `@DefaultValue`s;
      `PropertiesValidator` gains a package-private `validateOperations(...)` that returns without
      looking. Red: the defaults case reads `null` where `30d` was expected; every refusal case on
      "Expecting <Started application> to have failed but context started successfully".
      (red: 166 tests, 6 failures, 0 errors, every one an assertion. The five refusals all on
      *"Expecting <Started application [...]> to have failed but context started successfully"*,
      the predicted red. The defaults case on **`Expecting value to be true but was false`**
      rather than on `30d`/null - `enabled` is read before `supersedeMaxAge` and AssertJ stops the
      case at its first failure, exactly as 003's T001 recorded of its own defaults case; an
      undefaulted `boolean` binds to `false` where an undefaulted `Duration` binds to `null`, so
      the prediction was right about the cause and wrong about which line reports it.
      **Three deviations, all recorded rather than argued after the fact.**
      (1) The five refusals live in `ConfigurationValidationTest` and the two binding cases in the
      new `OperationsPropertiesTest`, which is how both files named by the task are touched without
      either restating the other - 003's T001 split the same way for the same reason.
      (2) The two audit refusals are **deployed-environment rules**, drawn on the
      `courtregister.servicebus.namespace` discriminator `StubReachability` and `OutboundValidation`
      already draw deployment on. `quickstart.md`'s "Local" section is the source: *"Locally
      `authz.http.enabled` and `cp.audit.enabled` are off ... a deployed pod refuses to start with
      the operations API enabled and HTTP audit off."* Unconditional refusals would have made
      `./gradlew bootRun`, `docker compose up` and the container smoke refuse the moment T005
      landed, and would have forced `courtregister.operations.enabled: false` into
      `application.yaml` in flat contradiction of FR-044's documented default. Two counterpart
      "should start" cases pin both halves of the discriminator. The two value rules
      (`supersede-max-age`, `lock-wait`) are unconditional: an unusable value is unusable anywhere.
      (3) `validateOperations(operations, properties, environment)` reads its own inputs rather
      than taking seven values, and is called from `afterPropertiesSet` rather than from the static
      `validate(...)` - so the existing static's signature is untouched and 004's rename of the
      generation grace period meets an added method rather than a reshaped class. The constructor
      gains one parameter and the class two fields.)
- [ ] **T005** [US1] `config/OperationsProperties` and `config/PropertiesValidator` — the record
      bound at `@ConfigurationProperties(prefix = "courtregister.operations")` with its
      `@DefaultValue`s, following `GenerationProperties`' style, and the five refusals of T004
      written as the validator's existing helpers write generation's. **`PropertiesValidator` is
      shared with 004** (which renames the generation grace period): add a method, do not reshape
      the class. Green: T004's cases.
- [ ] **T006** [P] [US1] `config/PublicEventsFactoryTest` (new) — **the listener container is built
      on the public-event connection factory, not the audit one** (research R8: the audit starter's
      `auditConnectionFactory` and `auditJmsTemplate` are `@Primary`, and `PublicEventsConfig`
      currently injects `ConnectionFactory` by type). A context case that asserts the container
      factory's connection factory is the Boot-provided one. Red: it is the audit library's.
- [ ] **T007** [US1] `config/PublicEventsConfig` — take the connection factory **by name** rather
      than by type, so the injection says which one it means and cannot be won by somebody else's
      `@Primary`. This is the only change 005 makes to a production class outside `api/`,
      `application/`, `config/` settings and the deletions, and it is a correctness fix forced by
      the dependency. Green: T006.

---

## Phase 2: Authorisation — the rules, the action name, the refusal body (6 tasks)

**Purpose**: Principle III condition (a), end to end, before a single endpoint exists. The rules
file and the action filter are what make "one explicit allow rule per action, and no default-allow"
true rather than claimed.

- [ ] **T008** [P] [US2] `api/OperationsRulesTest` (new) — **the drools rules, with no Spring**.
      Build a `KieContainer` from `acl/operations-rules.drl`, mock the
      `UserAndGroupProvider` global (note `inv.getRawArguments()[1]` for the varargs, per research
      R3), insert an `Outcome` and an `Action`, fire, read `outcome.isSuccess()`. Cases: each of the
      seven action names **allowed** for a caller in "Second Line Support"; each **denied** for a
      caller in some other group; an action name the file does not carry denied; and a text
      assertion that the file names no group but "Second Line Support". Seam: an empty
      `src/main/resources/acl/operations-rules.drl` carrying only the imports and the global.
      Red: every allow case on `expected: true but was: false` — default-deny with no rules.
- [ ] **T009** [US2] `src/main/resources/acl/operations-rules.drl` — seven allow rules in the
      reference implementation's exact form (research R3): no `package` declaration, the two
      imports, the global, `$o: Outcome()` / `$a: Action(name == "courtregister-operations.<verb>")`
      / `eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a, "Second Line Support"))` /
      `$o.setSuccess(true)`. No deny rule anywhere — absence is the denial. Green: T008.
- [ ] **T010** [P] [US2] `api/OperationsActionFilterTest` (new) — **the action name is derived by
      this service and overrides the caller**. `MockHttpServletRequest` plus an
      `ArgumentCaptor<HttpServletRequest>` on the chain, asserting `getHeader("CPP-ACTION")` on the
      wrapped request. Cases: all seven path+method combinations map to their action name
      (data-model §1–7); a caller-supplied `CPP-ACTION` naming a different action is **overridden**;
      the lookup is case-insensitive and `getHeaderNames()` includes it; an unrecognised path passes
      through with the header untouched; `POST` and `GET` on the same path map to different actions
      where they differ. Seams: `api/OperationsActionFilter` and `api/ActionRequestWrapper`
      skeletons. Red: the derived header is `null`.
- [ ] **T011** [US2] `api/OperationsActionFilter` and `api/ActionRequestWrapper` — a path+method to
      action map for the seven endpoints, at `Ordered.HIGHEST_PRECEDENCE` (research R11: ours,
      then authz at `+30`, then audit at `+50`), wrapping the request so the server's value wins.
      Registered in `config/OperationsWebConfig`. Green: T010.
- [ ] **T012** [P] [US2] `api/OperationsErrorAttributesTest` (new) — **the `/error` body carries
      nothing the caller typed**. The authorisation filter refuses through `sendError`, which
      forwards to `/error` and never reaches a `@RestControllerAdvice` (research R5), and Spring's
      default body echoes the request path. Cases: a 401 body and a 403 body carry `status`, `title`
      and a bounded `reason` and **no** `path`, `trace`, `message` or `exception`; an unmapped path's
      body does not contain the path that was tried. Seam: `api/OperationsErrorAttributes`
      skeleton. Red: the body contains `path`.
- [ ] **T013** [US2] `api/OperationsErrorAttributes` and the `authz.http.*` settings block in
      `application.yaml` — the block exactly as research R1 records it, including
      `exclude-path-prefixes` re-listing `/actuator` **and** `/error` (setting it replaces the
      library's list wholesale, and dropping `/actuator` makes the probes answer 401),
      `reload-on-each-request: false` in deployed environments (the library default is `true`), and
      `deny-when-no-rules: true`. Green: T012.

---

## Phase 3: The read endpoints — the flag and the two listings (6 tasks)

**Purpose**: the first three endpoints, the simplest, and with them the shape every later controller
copies: parse, call one application service, map. The batch listing is where the masking rule and
the "a controller may not hold a repository" rule both land.

- [ ] **T014** [P] [US1] `api/FlagControllerTest` (new) — `@WebMvcTest` with `FeatureFlagReader`
      mocked and both filters off (`authz.http.enabled=false`, `cp.audit.enabled=false`).
      Cases: `Enabled` → `200 {"flag":"ON"}`; `Disabled` → `200 {"flag":"OFF"}`; `Unreadable` →
      **`200`** `{"flag":"UNREADABLE","reason":"<the reading's bounded code>"}` — **not** 503 (spec
      assumption 2); the reader is asked exactly once and its answer is not cached. Seams:
      `api/FlagController` and its response record. Red: 404, no mapping — **no**: the seam maps the
      path and throws `UnsupportedOperationException`, so the red is a failing assertion on the body.
- [ ] **T015** [US1] `api/FlagController` and `api/dto/FlagResponse` — the three readings through a
      switch expression over `FlagDecision`, as `CheckFlagCli.answered` did. Green: T014.
- [ ] **T016** [P] [US6] `application/BatchListingServiceTest` (new) — **`ListBatchesCli`'s reads,
      moved and unchanged**. Cases: a date's batches in the statement's order, each with its record
      count from `RegisterStore.batched` and its recipients from `RegisterNotificationRepository`;
      a batch with no court house yields `null` (the command printed `-`); the masking rule
      character for character — a local part longer than one keeps its first character, one of
      length one keeps none, an address with nothing before its `@` masks to `***@…`, an absent
      address masks to `***`; `recordedWhileOff` yields record id, hearing id, register date and
      flag state; a store that will not answer propagates rather than returning a partial listing.
      Seams: `application/BatchListingService` and the two result records. Red: the masking case.
- [ ] **T017** [US6] `application/BatchListingService` — the body of `ListBatchesCli.listDate`,
      `print` and `listRecordedWhileOff` with the `Consumer<String>` printing removed and typed
      records returned. **Move, do not rewrite**: the diff is a move plus a return type. Green: T016.
- [ ] **T018** [P] [US1] [US6] `api/BatchesControllerTest` (new, listing cases only) and
      `api/RegistersControllerTest` (new, recorded-while-off cases only) — `@WebMvcTest` with
      `BatchListingService` mocked. Cases: the listing shape of data-model §2 and §3; a `date` that
      is absent → `400 missing-argument`; a `date` that will not read → `400 unreadable-argument`
      with `argument: date` and **without the value that was sent**; a store failure → `503`.
      Seams: the two controllers and their response records. Red: the body shape.
- [ ] **T019** [US1] [US6] `api/BatchesController#list`, `api/RegistersController#recordedWhileOff`
      and their dtos. Green: T018.

---

## Phase 4: The exception report, and the error mapping every endpoint uses (6 tasks)

**Purpose**: the last read endpoint, and with it the `@RestControllerAdvice` that turns every
refusal in the increment into a `ProblemDetail`. It lands here rather than in Phase 3 because the
exception report has the widest refusal set of any endpoint.

- [ ] **T020** [P] [US6] `application/OnDemandExceptionReportServiceTest` (new) — **`ReportExceptionsCli`'s
      window and sinks, moved and unchanged**. Cases: `since` as an ISO instant, as an ISO-8601
      duration and as `<n>d`/`<n>h`/`<n>m`/`<n>s`; a zero or negative window refused; no `since` →
      the window from the previous scheduled run, computed from the report cron and zone exactly as
      the command computed it; e-mail asked with the output switched off → the
      `email-output-disabled` refusal; e-mail asked with the output on and no sink → the
      `email-output-not-wired` refusal; the sink selection (log always, e-mail only when asked); and
      "not every sink took it" distinguished from "the report could not be built". **The flag reader
      is a strict mock and is never touched** — the command read it nowhere and neither does this.
      Seams: `application/OnDemandExceptionReportService` and its result record. Red: the window
      forms.
- [ ] **T021** [US6] `application/OnDemandExceptionReportService` — the body of
      `ReportExceptionsCli.asked` and `reported` minus the parsing and the printing, returning the
      report, the per-sink outcomes, the run id and the duration. `RunCorrelation.under(...)` is
      kept: an on-demand report is a run and carries a run id, as it did. Green: T020.
- [ ] **T022** [P] [US6] `api/ExceptionReportsControllerTest` (new) — `@WebMvcTest`. Cases: the
      `200` shape of data-model §7, entries and counts and truncation and per-sink delivery and the
      run id and the duration; an empty window answers `entries: []` and the counts, never silence;
      `409 email-output-disabled`; `409 email-output-not-wired`; `400 unreadable-argument` with
      `argument: since` and no echo of the value; `500 report-not-built`; `500
      report-not-delivered` carrying which sink refused. Seams: the controller and its dtos.
      Red: the body shape.
- [ ] **T023** [US6] `api/ExceptionReportsController` and its dtos. Every entry field is an
      identifier, a bounded code or a count, and absent fields are **omitted** rather than rendered
      — the same rule `ReportExceptionsCli.carried` applied. Green: T022.
- [ ] **T024** [P] [US4] `api/OperationsExceptionHandlerTest` (new) — **the status map and the
      no-echo rule, once, for everything**. Cases, one per row of data-model "Common": a body that
      will not parse → `400`; a body with an unknown field → `400` (the request contract is closed,
      FR-028); every bounded reason reaches `reason` and none reaches `detail`; **no response body
      contains a value from the request** — driven by sending distinctive values in every field and
      asserting none appears; an exception message never reaches the body; an unmapped method on a
      mapped path answers without a stack trace. Seam: `api/OperationsExceptionHandler`. Red: the
      unknown-field case is accepted.
- [ ] **T025** [US4] `api/OperationsExceptionHandler` (`@RestControllerAdvice`,
      `@Order(HIGHEST_PRECEDENCE)`) and the `ObjectMapper` setting that makes an unknown field a
      failure on the request records only. The full map of data-model FR-023: `400`, `404`, `409`,
      `500`, `501`, `502`, `503`, `504`. **`@ControllerAdvice` is permitted for this package and
      nowhere else** (`technical-rules.md`); it must be unreachable from the listeners and the jobs.
      Green: T024.

---

## Phase 5: Supersede — the endpoint that is stricter than its command (4 tasks)

**Purpose**: the second cutover lever that this endpoint would otherwise be. Every protection here
was confirmed by the design owner on 2026-09-19 and is a requirement, not a precaution.

- [ ] **T026** [P] [US6] `application/OperationsSupersessionServiceTest` (new) — the guard, with the
      store and the flag reader mocked. Cases: the flag **OFF** → the store is asked and the count
      returned; the flag **ON** → `FLAG_ON`, and **the store is never touched**; the flag
      **unreadable** → `flag-unreadable`, store never touched (fail-closed); `dryRun` → the count is
      read and **nothing is superseded**; an instant in the future → refused; an instant older than
      `supersede-max-age` → refused; the flag is read **uncached**, once per call; a store failure →
      the `supersession-failed` classification. Seam: `application/OperationsSupersessionService`.
      Red: the flag-ON case supersedes.
- [ ] **T027** [US6] `application/OperationsSupersessionService` — the flag read through the same
      uncached `FeatureFlagReader` path, the two bounds against `Clock` and `OperationsProperties`,
      the dry run, and `RegisterStore.supersedeSharedBefore` otherwise. There is **no** override
      parameter and none may be added. Green: T026.
- [ ] **T028** [P] [US6] `api/RegistersControllerTest` (extend) — the supersede slice cases of
      data-model §6: `200` with the count, the instant and `dryRun`; `400 missing-argument` for an
      absent instant — **never defaulted**; `400 unreadable-argument` with `argument: sharedBefore`;
      `400 SUPERSEDE_INSTANT_IN_FUTURE`; `400 SUPERSEDE_INSTANT_TOO_OLD`; `409 FLAG_ON`;
      `409 flag-unreadable`; `503 supersession-failed`. Red: the mapping.
- [ ] **T029** [US6] `api/RegistersController#supersede` and its request/response records. Green:
      T028.

---

## Phase 6: Regeneration — the asynchronous one (8 tasks)

**Purpose**: the endpoint that changes production data. It is the largest departure from its
command's shape (`202`, a background run, the nightly lock **taken**) and every part of that
departure is forced — research R16 has the reasoning.

- [ ] **T030** [P] [US3] `application/RegisterRegenerationServiceTest` (new) — **`GenerateRegisterCli`'s
      orchestration, moved and unchanged**, its existing cases re-pointed from the CLI class.
      Cases: narrowing by court house and by batch id; a FAILED batch released; a batch withheld
      `key-in-flight` when its key has one in flight; a batch withheld `outside-the-bound` when the
      operator's narrowing excludes one of its registers; `activeUnbatched` folded in only when no
      batch id was given; the de-duplication by `outputId`; the run deadline; the tally — released,
      registers, batches, requested, deferred — matching the command's counts exactly for the same
      inputs. Seams: `application/RegisterRegenerationService` and its `Selection` and
      `RegenerationTally` records. Red: the withheld-reason case.
- [ ] **T031** [US3] `application/RegisterRegenerationService` — the body of
      `GenerateRegisterCli.generate`, `narrowed`, `released`, `withheldReason`, `registers` and
      `request`, with the printing replaced by the tally record. `Selection` moves with it. **Does
      not touch `RegisterGenerationJob` or `GenerationReconciler`** (004's). Green: T030.
- [ ] **T032** [P] [US3] `application/OperationsRunLauncherTest` (new) — **the lock is taken, not
      asked about** (research R12). With a `LockProvider` mock: the run id is minted and recorded
      **before** the work is submitted (ids before calls); the launcher attempts the lock with the
      register-generation lock name and a non-blocking configuration; an empty `Optional` → the work
      does not run and `SCHEDULE_RUNNING` is the run's recorded outcome; a lock obtained → the work
      runs and the lock is released in a `finally`, including when the work throws; the work is
      submitted to the generation scheduler's executor and not run on the calling thread. Seam:
      `application/OperationsRunLauncher`. Red: the work runs without a lock.
- [ ] **T033** [US3] `application/OperationsRunLauncher` — the `202` hand-off: validate, read the
      flag through `FeatureFlagGate`, mint and record the run id, submit to the generation
      executor, and inside the submitted task take `LockProvider.lock(...)` for
      `RegisterGenerationJob.LOCK_NAME`, run `RegisterRegenerationService`, release. Green: T032.
- [ ] **T034** [P] [US3] `api/BatchesControllerTest` (extend) — the generate slice cases of
      data-model §4: `202` with the run id and `overridden`; `400 missing-argument` for an absent
      date; `400 unreadable-argument` for each of `date`, `batchId`, `recordedBefore`, naming the
      argument and never the value; **`400 OVERRIDE_REQUIRES_BATCH` for `ignoreFlag: true` with no
      `batchId`**, and the accepted override *with* one (design owner, 2026-09-19);
      `409 flag-off`; `409 flag-unreadable`; a date-wide regeneration with the flag ON accepted.
      Red: the override cases.
- [ ] **T035** [US3] `api/BatchesController#generate` and its request/response records. Green: T034.
- [ ] **T036** [P] [US3] `domain/RunReportTest` (**new** — there is no such suite today; the line is
      asserted only inside `batch/RegisterGenerationJobTest`, which is 004's and must not be
      touched) — **the operator run says it was one**. The run line carries `trigger=operator` for a
      regeneration launched over HTTP and the scheduler's own value otherwise, and
      `reason=overridden` where the flag was overridden — the same field `FeatureFlagGate` already
      counts and logs, which is **kept**, not replaced.
      ⚠ **`domain/RunReport` is also touched by 004** (its run line loses `reconciled=` and gains
      `released_batches=`/`released_registers=`). Add a field; do not reshape the line. Expect a
      textual conflict on the rebase and resolve it by keeping both. Red: the trigger is absent.
- [ ] **T037** [US3] `domain/RunReport` — the trigger field, added so that **the existing call site
      in `batch/RegisterGenerationJob` does not change**: the scheduler's value is the default and
      the operator's is a second factory. The job is 004's and 005 does not edit it; if the field
      cannot be added without editing it, the task stops and goes back to the orchestrator rather
      than reaching into the other tree's file. Green: T036.

---

## Phase 7: Notify, and the pod without a generation half (5 tasks)

**Purpose**: the last endpoint, and the one honest answer for a deployment that cannot serve three
of them.

- [ ] **T038** [P] [US6] `api/BatchesControllerTest` (extend) — the notify cases of data-model §5,
      with `RegisterNotifierService` mocked. `SETTLED` → `200` with the tally;
      **`ALREADY_NOTIFYING` → `409`** (another notifier holds the claim; this call changed nothing);
      **`CLAIM_LOST` and `INCOMPLETE` → `500`** carrying the disposition (the call tried and got
      part-way); a `batchId` that is not a UUID → `400` naming `batchId`; a batch that does not
      exist → `404 UNKNOWN_BATCH`, **distinguished from** a store outage → `503 STORE_UNAVAILABLE`;
      notificationnotify refusing → `502`, not answering → `504`. Red: the three-way disposition
      split.
- [ ] **T039** [US6] `api/BatchesController#notify` and the disposition mapping. The service must
      distinguish "no such batch" from "the store would not answer" — the CLI caught both as one
      `RuntimeException`; if it does not already, that distinction is made **in the application
      service**, not in the controller. Green: T038.
- [ ] **T040** [P] [US6] `api/NotWiredControllerTest` (new) — `501 COMMAND_NOT_WIRED` on a pod with
      `courtregister.generation.enabled=false` for the three endpoints that need those beans
      (generate, notify, the batch listing), and the other four served normally. This is exactly
      what the CLI answered on such a pod; a `404` would read as a mistyped URL and a `500` as a
      bean-definition error reaching an operator. Red: the context fails to start for want of a
      bean, or the path answers 404.
- [ ] **T041** [US6] `api/NotWiredController` and `config/OperationsWebConfig` — the controllers
      that need the generation beans registered `@ConditionalOnProperty` on
      `courtregister.generation.enabled`, the not-wired fallback registered when it is off, and the
      whole set conditional on `courtregister.operations.enabled`. **Reads the generation property;
      does not change it** (004 owns that block). Green: T040.

---

## Phase 8: The contract, the audit facts, and the real filter (7 tasks)

**Purpose**: the three conditions that cannot be proven endpoint by endpoint — that the OpenAPI
document and the controllers agree, that the audit event carries what it must and no body, and that
the **real** authorisation filter refuses the people it should.

- [ ] **T042** [P] [US1] `api/OpenApiContractTest` (new) — **both directions**. Parse
      `src/main/resources/openapi.yaml`; assert every mapped path and method in
      `RequestMappingHandlerMapping` (excluding actuator) is described, and every path described is
      mapped; assert `/operations/batches/{batchId}/notify` declares `batchId` as an `in: path`
      parameter — the audit filter registers a path **only** if it does (research R7); assert every
      bounded `reason` the handler can emit appears in the document's enumerations. Seam: an
      `openapi.yaml` with the info block and no paths. Red: seven paths mapped, none described.
- [ ] **T043** [US1] `src/main/resources/openapi.yaml` — the seven endpoints as data-model describes
      them, plus the `audit.http.*` settings block: `enabled: ${HTTP_AUDIT_ENABLED:false}`,
      `openapi-rest-spec: openapi.yaml` (a **suffix** glob — it must match a file on the classpath
      or start-up fails), `include-payload-body: false` **explicitly** (the library default is
      `true` and would publish every response body). Green: T042.
- [ ] **T044** [P] [US1] `api/OperationsAuditFactsTest` (new) — the payload carries the action, the
      outcome (status family + bounded reason), `flagOverride` on the regeneration endpoint, the run
      id, and the superseded count on the supersede endpoint; and it carries **no** request or
      response body. Driven through the publisher seam — `AuditService` is a plain class registered
      `@ConditionalOnMissingBean` (research R10), so the test context holds a capturing one. Seams:
      `api/OperationsAuditFacts` (request-scoped) and `api/OperationsAuditService`. Red: the facts
      are absent from the payload.
- [ ] **T045** [US1] `api/OperationsAuditFacts` and `api/OperationsAuditService` — the controllers
      and the advice populate the facts; the service merges them into the payload's `content` and
      delegates. Nothing else is added to the event. Green: T044.
- [ ] **T046** [US2] `api/OperationsAuthzIT` (new) — **the real filter, wired as deployed**, with
      usersgroups stubbed at the HTTP boundary by WireMock and
      `@DynamicPropertySource` over `authz.http.identity-url-template` (the
      `LoggedInUserPermissionsResponse` body shape is in research A9/R4). Cases: a caller in
      "Second Line Support" → served, on every one of the seven; a caller in another group → `403`,
      on every one of the seven; no `CJSCPPUID` → `401`; the identity service answering `500` →
      `403` **and not `500`** (the client never throws; an outage is an empty identity, research
      R4); a forged `CPP-ACTION` naming an action the caller may not reach → still refused;
      `/actuator/health` with no headers → `200`. A test that mocks `DroolsAuthzEngine` proves the
      test, and is not acceptable here. Red: whatever the real wiring gets wrong — expect the
      `exclude-path-prefixes` list and the filter order to be the first two.
- [ ] **T047** [US2] Whatever T046 exposes: the settings, the filter order, the registration. No new
      behaviour — if this task wants a behaviour change, it has found a gap in an earlier phase and
      goes back there. Green: T046.
- [ ] **T048** [A] [US1] `api/OperationsAuditIT` (new) — one authorised call to each endpoint
      produces a request event and a response event on the seam, each carrying the caller, the
      action and the outcome, and **no** body. Records the observed result; no implementation task
      follows.

---

## Phase 9: Concurrency and privacy (4 tasks)

**Purpose**: what the separate CLI JVM used to make impossible. An endpoint is served by a pod where
the consumer, both schedulers and the public-event listener are all running, and `CliModeConfig` is
not there to switch them off.

- [ ] **T049** [US5] `e2e/OperationsConcurrencyIT` (new) — Testcontainers Postgres, the real store
      and the real lock. Cases: **two notify calls for one batch at once** → the dispositions
      `RegisterNotifierService`'s existing claim defines, and **no recipient is e-mailed twice**;
      **a regeneration racing the scheduled run**, in both orders — the scheduler holding the lock,
      and the regeneration holding it; **two regenerations for one date on two contexts** → the
      loser's `releaseFailed` returns no rows and its assemble meets the live-key index, surfacing
      as a **bounded recorded outcome and never an unexplained failure**; **a public document event
      arriving during a regeneration** → applied exactly as it is during a scheduled run. Red:
      expect the two-regenerations case to be an unexplained failure first — that is the finding.
- [ ] **T050** [US5] Whatever T049 exposes, as a bounded refusal in the application service. No new
      locking beyond the register-generation lock, and no reproduction of a claim that already
      exists. Green: T049.
- [ ] **T051** [P] [US4] `config/TelemetryPrivacyTest` (extend) and `support/PersonalDataMarkers` —
      **the sweep now covers responses, not only log statements**. Cases: no controller response
      record and no `ProblemDetail` field can carry a personal-data marker; `detail` never carries
      a store's or an exception's message; the `CJSCPPUID` value appears in **no** log statement in
      `api/` (it belongs in the audit event, which is the one place the caller is named on purpose);
      no response carries an unmasked address. Red: whatever the sweep finds.
- [ ] **T052** [US4] The fixes T051 finds. Green: T051.

---

## Phase 10: Remove the CLI (6 tasks)

**Purpose**: the deletion, last, after every endpoint that replaces a command has a passing test
(FR-051). At no commit is there neither surface. The deletion tasks carry the mechanical exemption:
a deletion has no red run, and its evidence is the green build with the replaced suite gone.

- [ ] **T053** [US5] Delete `src/main/java/uk/gov/hmcts/cp/courtregister/batch/cli/` (all ten
      classes) and `src/test/java/uk/gov/hmcts/cp/courtregister/batch/cli/` (all nine suites), plus
      `src/main/resources/logback-cli.xml`. Nothing else in this commit. Evidence: the build is
      green and the test count drops by exactly the deleted suites' cases.
- [ ] **T054** [US5] Delete `config/CliModeConfig` and `config/CliModeConfigTest`; remove
      `courtregister.cli` from `application.yaml`; make the three conditionals that read it
      unconditional — `inbound/ServiceBusConsumerConfig`, `config/SchedulingConfig` and
      `config/ReportSchedulingConfig`, and `config/PublicEventsConfig`. **`PublicEventsConfig`'s
      javadoc about a CLI JVM not subscribing goes with it** — the rule is retired with the JVM it
      was about. Evidence: the build is green; a context still starts with the schedulers and the
      listener present.
- [ ] **T055** [US5] `docker/startup.sh` — the command dispatch, the `CLI_MAIN`, `CLI_COMMANDS` and
      `BOOT_LAUNCHER` variables and the whole `case` go; the entrypoint starts the application, full
      stop. Delete `e2e/CliDispatchIT`. `scripts/container-smoke.sh` calls `GET /operations/flag`
      through the readiness gate instead of running two commands. Evidence: the smoke passes.
- [ ] **T056** [US5] `config/HttpSurfaceTest` — re-pointed from "no controller exists" to "actuator
      and `/operations/**`, and nothing else": every mapped path is under one of the two, and no
      path submits a hearing, reads a register out or creates a batch. This is the test that stops
      the next increment from quietly adding a business endpoint.
- [ ] **T057** [A] [US5] Full `./gradlew build` behind the `flock`: compile, the whole suite, PMD
      main and test, Checkstyle main and test, and the JaCoCo gate at the **unchanged** thresholds
      (LINE ≥ 0.88, BRANCH ≥ 0.85 — the ratchet is never loosened to admit a controller). Records
      the counts.
- [ ] **T058** [US5] The documentation sweep. `specs/002-consolidate-progression-leg/quickstart.md`:
      the CLI examples replaced by the `curl` ones from this increment's `quickstart.md`. `README.md`
      and `CLAUDE.md`: the final read-through — the operations paragraphs landed in `d73ef50` and
      this is the check that nothing else in either still says "command". Then a repository-wide
      grep for `batch/cli`, `CliModeConfig`, `courtregister.cli`, `startup.sh <command>` and the six
      command names: nothing outside this spec, the constitution's history and the earlier
      increments' own records (SC-007). **README's generation section is 004's — do not touch it.**

---

## Phase 11: Close-out (2 tasks)

- [ ] **T059** [A] After the rebase onto `main` (004 merges first, per the plan's merge order):
      re-run the `spec-validator` agent against the amended constitution, and `/speckit-analyze`
      against this spec. Gate 8 of `workflow.md` — the operations API's four conditions — is the one
      to read carefully. Records the verdicts.
- [ ] **T060** [A] The handover note: the five deployment gates from `spec.md`, the one open item
      (the estate's audit header allowlist), and the behaviour 004 owes 005 (its pre-batching pass
      skipping operator-initiated batches). This increment **must not be deployed to STE** until the
      five gates land — the CLI is gone, so a pod without them has no operational surface at all.

---

## Dependencies and execution order

```
Phase 0  T001                          governance, before any code
Phase 1  T002→T003, T004→T005, T006→T007      the classpath and the settings
Phase 2  T008→T009, T010→T011, T012→T013      authorisation, before any endpoint
Phase 3  T014→T015, T016→T017, T018→T019      the read endpoints
Phase 4  T020→T021, T022→T023, T024→T025      the report and the error map
Phase 5  T026→T027, T028→T029                 supersede
Phase 6  T030→T031, T032→T033, T034→T035, T036→T037   regeneration
Phase 7  T038→T039, T040→T041                 notify and not-wired
Phase 8  T042→T043, T044→T045, T046→T047, T048        contract, audit, the real filter
Phase 9  T049→T050, T051→T052                 concurrency and privacy
Phase 10 T053→T054→T055→T056→T057→T058        the removal, last
Phase 11 T059, T060                           close-out
```

- **Phase 1 blocks everything**: nothing compiles against a starter that is not on the classpath,
  and the context does not start until the component-scan clash is closed.
- **Phase 2 blocks Phases 3–7**: an endpoint with no rule is denied, so an endpoint written before
  its rule cannot be tested end to end. (The `@WebMvcTest` slices run with the filters off, so the
  slice tests themselves do not depend on it; `OperationsAuthzIT` in Phase 8 does.)
- **Phase 4's T024/T025 block nothing but improve everything after them**: the controllers in
  Phases 5–7 map refusals through the advice the report endpoint forced into existence.
- **Phase 10 depends on all of Phases 3–8**: FR-051.
- Within a phase, `[P]` test tasks touch different files and may run together; an implementation
  task never runs before the test task it is paired with is closed.

### Parallel opportunities

```
# Phase 1 - two independent pairs after T003:
T004  T006

# Phase 2 - three independent test files:
T008  T010  T012

# Phase 3 - the flag and the listing service share nothing:
T014  T016

# Phase 4 - the service, the controller and the advice are three files:
T020  T024

# Phase 6 - the service, the launcher and the run report are three files:
T030  T032  T036

# Phase 9 - the concurrency IT and the privacy sweep are independent:
T049  T051
```

## Notes

- Every `./gradlew` invocation goes behind the shared `flock`; never two Gradle builds at once in
  this tree, and never two committing agents.
- The red run recorded for a test task is a **failing assertion**. If it is a compile error, the
  seams were not landed and the task is not finished.
- `RegisteredDefectFixes` and `DifferentialAuditTest` are untouched throughout and must stay green.
  If either moves, something outside this increment's scope has been changed.
