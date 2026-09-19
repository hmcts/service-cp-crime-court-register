# Feature Specification: Operations REST API, replacing the operations CLI

**Feature Branch**: `005-operations-rest-api`
**Created**: 2026-09-19
**Status**: Draft
**Input**: Design owner's decision, 2026-09-19 — "the six operator CLI commands become a REST
operations API and the CLI is removed", because that is the estate's Spring Boot shape and because
the estate starters give authentication and audit that `kubectl exec` cannot.

## Context

Increments 001–003 left this service with six operations commands baked into the image
(`generate-register`, `notify-register`, `list-batches`, `supersede-before`, `check-flag`,
`report-exceptions`), reached by `kubectl exec ... -- ./startup.sh <command>` and dispatched by
`docker/startup.sh` into `batch/cli/CliMain`. They were the only operational surface, deliberately:
constitution Principle III at 3.2.0 said this service had no REST API at all.

Three things are wrong with that, and none of them is about convenience:

1. **Nobody is named.** A command runs as the pod. The cluster records that somebody with exec
   rights on the namespace started a process; it does not record who regenerated a register date
   for children's court appearances, or who overrode the cutover flag while doing it.
2. **Nothing is audited.** The estate has an audit context and every other CPP Spring Boot service
   publishes its REST calls to it. An exec'd command publishes nothing.
3. **Authorisation is namespace-wide.** Exec rights are not the same thing as "Second Line
   Support", and there is no way to make them the same thing.

Constitution **4.0.0** (this branch's first commit, `d73ef50`) redefines Principle III to permit an
**operations API** under four conditions — authorised, audited, flag-gated exactly where the command
it replaces was, and answering under Principle VII. This increment builds it and removes the CLI.

It is a **like-for-like port of an operational surface**, not a new capability. Every endpoint does
what one command did, takes what it took, refuses what it refused, and answers with the fields it
printed. Two deliberate differences are recorded in Assumptions and nowhere else.

**This increment is not a `doc/DEFECT-FIXES.md` event.** There is no legacy oracle for an
operational surface: neither the function app nor progression's leg had one, and replacing this
service's own CLI with this service's own API is not a deviation from either. No `C` or `P` row is
added, amended or flipped. A task that finds itself wanting a number has found a defect in 001–003.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Second Line Support reaches every operator action over HTTP, under their own name (Priority: P1)

A named member of Second Line Support calls an `/operations/**` endpoint with their `CJSCPPUID`
identity. The call is authorised against their usersgroups membership, published to the audit
context as a request and a response, and answered with the same information the equivalent command
printed — as JSON, with bounded codes, counts and identifiers only.

**Why this priority**: it is the whole increment. Without it the CLI cannot be removed, and with it
every other story is a detail of one endpoint or another.

**Independent Test**: call each endpoint with a `CJSCPPUID` resolving to a member of "Second Line
Support" and assert the documented success shape; assert an audit event was published for the call.

**Acceptance Scenarios**:

1. **Given** a caller in "Second Line Support", **When** they `GET /operations/flag`, **Then** the
   answer is `200 {"flag":"ON"}` or `200 {"flag":"OFF"}` — the same verdict `check-flag` printed.
2. **Given** a caller in "Second Line Support", **When** they `GET /operations/batches?date=D`,
   **Then** the answer lists that date's batches with, per batch, its id, court house, state, record
   count and recipients — each recipient's address **masked exactly as `list-batches` masked it**
   and its outcome beside it.
3. **Given** any authorised call to any endpoint, **When** it is answered, **Then** an audit event
   for the request and one for the response have been published to the audit context, carrying the
   caller's identity and the action.
4. **Given** any endpoint, **When** it answers — success or refusal — **Then** the body carries no
   defendant detail, no unmasked recipient address, no exception message, no store's or far end's
   own words, and no value the caller supplied.

---

### User Story 2 - Nobody outside Second Line Support reaches any of it (Priority: P1)

A caller whose identity is not a member of "Second Line Support", or who presents no identity at
all, is refused before the endpoint's work begins. There is no endpoint with no rule, and no
default-allow.

**Why this priority**: the authorisation is the reason the surface is permitted to exist at all
(constitution Principle III(a)). An endpoint that is reachable by anyone is worse than the
`kubectl exec` it replaced, because it is reachable from further away.

**Independent Test**: for each endpoint, call it with an identity that is not in the group and
assert the refusal; call it with one that is and assert it is served. No endpoint passes both.

**Acceptance Scenarios**:

1. **Given** a caller whose groups do not include "Second Line Support", **When** they call any
   `/operations/**` endpoint, **Then** the call is refused and no application service is reached.
2. **Given** a request with no `CJSCPPUID` header, **When** it reaches any `/operations/**`
   endpoint, **Then** it is refused.
3. **Given** the deployed rule set, **When** it is read, **Then** there is exactly one allow rule
   per action, each naming "Second Line Support" and no other group, and no rule admitting an action
   by default.
4. **Given** actuator, **When** `/actuator/health` is called, **Then** it answers as it always has:
   actuator is not part of this surface and is not behind these filters.

---

### User Story 3 - Regenerating a date is still gated by the one lever, and now cannot collide with the nightly run (Priority: P1)

An operator asks for a register date to be regenerated. The endpoint reads the `CourtRegisterService`
flag through the same gate the 18:00 run reads it through, at the same point `generate-register`
read it, and refuses when it says off unless the operator has explicitly asked to override. An
override is an operator decision that is written down: in the audit event and on the run report.
Additionally — and this is the one rule the CLI did not have — a regeneration is refused while the
18:00 run holds its lock.

**Why this priority**: this is the endpoint that changes production data, and it is the one the
cutover rule is about. Getting it wrong makes the flag stop being the one lever.

**Independent Test**: with the flag off, call generate without an override and assert the refusal
and that nothing was assembled; call it with the override and assert the assembly happened and the
override was recorded twice. Hold the nightly lock and assert the refusal.

**Acceptance Scenarios**:

1. **Given** the flag says off and the body does not ask to override, **When** the endpoint is
   called, **Then** it refuses with `409` and the bounded reason `FLAG_OFF`, nothing is assembled,
   nothing is released and nothing is requested.
2. **Given** the flag is unreadable and the body does not ask to override, **When** the endpoint is
   called, **Then** it refuses with `409` carrying the gate's own bounded reason — fail-closed,
   exactly as the nightly run fails closed.
3. **Given** the flag says off and the body asks to override, **When** the endpoint is called,
   **Then** the regeneration proceeds **and** the override is recorded in the audit event and on the
   run report, exactly as `generate-register` printed and counted it.
4. **Given** the 18:00 run holds its lock, **When** the endpoint is called, **Then** it refuses with
   `409 SCHEDULE_RUNNING` and nothing is assembled — whatever the flag says.
5. **Given** an accepted regeneration, **When** it answers, **Then** the answer carries the same
   tally the command printed: the date, and the counts of released, registers, batches, requested
   and deferred, plus each batch's id, state and record count, and each withheld batch with its
   bounded reason.

---

### User Story 4 - Every refusal says which kind it is, and none of them quotes the caller (Priority: P1)

The CLI's three exit codes meant three different things to a runbook step: `0` did it, `1` declined
and changed nothing, `2` tried and could not. The API keeps that distinction, because a runbook that
retried a refusal as a failure would be an operator overriding the cutover flag by accident.

**Why this priority**: the distinction is load-bearing operationally, and the "no input echoed"
rule is Principle VII. Both are cheap to lose and expensive to notice.

**Independent Test**: drive each endpoint into each of its refusals and assert the status, the
bounded reason and the absence of any supplied value in the body and in the log.

**Acceptance Scenarios**:

1. **Given** a request whose argument cannot be read (a date that is not a date, an id that is not
   an id, an instant that is not an instant), **When** it is answered, **Then** the status is `400`,
   the body names the **argument** by this service's own name for it, and the body does **not**
   contain the value that was sent.
2. **Given** a request missing an argument the endpoint cannot do without, **When** it is answered,
   **Then** the status is `400` with a bounded reason.
3. **Given** a state refusal — the flag is off, the schedule is running, the e-mail output is
   switched off — **When** it is answered, **Then** the status is `409` with a bounded reason and
   **nothing has changed**.
4. **Given** an endpoint that tried and could not finish, **When** it is answered, **Then** the
   status is `500` with the bounded reason the command printed, and the log line names the failing
   class and not its message.
5. **Given** any refusal, **When** the response is read, **Then** it is a `ProblemDetail` whose
   fields are bounded codes, counts and identifiers — no free text, no stack trace, no exception
   message.

---

### User Story 5 - The image starts the application, full stop (Priority: P1)

The CLI is removed: the command classes, the mode switch that kept a command's JVM from consuming
deliveries, the property behind it, and the entrypoint's command dispatch. A container started with
arguments no longer means anything special; a container started at all starts the application.

**Why this priority**: two operational surfaces is worse than either, and the mode switch existed
only to protect a process that no longer exists. Leaving it behind leaves a second way to start a
pod that does not consume.

**Independent Test**: build the image, start it, and assert the application starts; assert the
repository contains no `batch/cli/`, no `CliModeConfig`, no `courtregister.cli` and no dispatch in
`docker/startup.sh`.

**Acceptance Scenarios**:

1. **Given** the built image, **When** it is started with no arguments, **Then** the application
   starts exactly as it does today.
2. **Given** the repository, **When** it is searched, **Then** `batch/cli/`, `config/CliModeConfig`,
   the `courtregister.cli` property and every reference to them are gone, and the three conditionals
   that read that property (the consumer, the scheduler, the public-event listener) are
   unconditional again.
3. **Given** the documentation, **When** it is read, **Then** no page instructs an operator to
   `kubectl exec` a command; the quickstart's CLI examples are `curl` examples.

---

### User Story 6 - The remaining four actions answer exactly as their commands did (Priority: P2)

Resending a batch's owed recipients, superseding what was recorded before an instant, listing what
was recorded while the flag was off, and pulling the exception report for a window: four endpoints,
four commands, same arguments and same fields.

**Why this priority**: they are the rest of the surface. P2 rather than P1 only because each is
independently shippable once the filters and the error mapping of P1 and P4 exist.

**Independent Test**: each endpoint against a stubbed application service, asserting the fields the
command printed.

**Acceptance Scenarios**:

1. **Given** a batch with recipients no e-mail has been accepted for, **When** the notify endpoint
   is called for it, **Then** those recipients and only those are re-requested, and the answer
   carries the batch id, the accepted and failed counts, the batch state and the disposition.
2. **Given** a disposition that leaves the batch unsettled, **When** the notify endpoint answers,
   **Then** the status is `409` carrying that disposition as its bounded reason.
3. **Given** a supersede request, **When** it is answered, **Then** the answer carries the count
   superseded and the instant it was taken from; an absent instant is a `400` and is **never**
   defaulted to a value of the service's choosing.
4. **Given** the recorded-while-off listing, **When** it is answered, **Then** each row carries its
   record id, hearing id, register date and the flag state it was recorded under.
5. **Given** an exception-report request, **When** it is answered, **Then** the answer carries the
   window, the entries (each an identifier-only row), the per-kind counts, whether the list was
   truncated, what each sink did, the run id and the duration — the same fields the command printed
   — and the endpoint has read the cutover flag **nowhere**.
6. **Given** an exception-report request asking for e-mail while the e-mail output is switched off,
   or on with no sink behind it, **When** it is answered, **Then** the status is `409` carrying
   `EMAIL_OUTPUT_DISABLED` or `EMAIL_OUTPUT_NOT_WIRED` respectively.

---

### Edge Cases

- **A path or method nobody mapped.** Answered as the framework answers it, and never with a stack
  trace or the path that was tried.
- **A body that is not JSON, or JSON of the wrong shape.** A `400` with a bounded reason and no
  echo of the body.
- **An unknown field in a request body.** Refused, not ignored: the request contract is closed for
  the same reason the inbound queue message is — tolerating drift hides it until it matters.
- **An identity that usersgroups cannot be asked about** (the identity service is down or slow).
  The call is refused rather than admitted; fail-closed is the only safe direction for an
  authorisation decision, and it is the same direction the flag fails in.
- **The audit context is unreachable.** Decided in the plan from the starter's own behaviour, and
  recorded there: a call that cannot be audited must not quietly proceed as though it had been.
- **A regeneration that answers a caller who has gone away.** The work is not undone: the API has no
  equivalent of the CLI's "the report could not be written" case, because the answer is written once
  at the end rather than streamed line by line — which removes a whole class of the CLI's failure
  handling rather than porting it.
- **Two operators calling generate for the same date at once.** Behaves exactly as two `kubectl
  exec` runs did: the store's own claims and the assembler's in-flight deferral decide, and the
  endpoint invents no new locking of its own beyond the nightly-run check.
- **An exception report whose sinks did not all take it.** The report was built and is returned in
  full; the failure to deliver it is said separately and is not dressed up as a success.

## Requirements *(mandatory)*

### Functional Requirements

**The surface**

- **FR-001**: The service MUST expose exactly seven endpoints under `/operations/**`, one per
  operator action, and no other HTTP path besides Spring Boot Actuator.
- **FR-002**: Every endpoint MUST be described in an OpenAPI 3 document at
  `src/main/resources/openapi.yaml`, owned and versioned by this repository, covering its request,
  its success shape and every bounded `reason` it can refuse under. A contract test MUST assert that
  the controllers and the document agree in both directions.
- **FR-003**: The HTTP layer MUST be an inbound adapter in `uk.gov.hmcts.cp.courtregister.api`: it
  parses, calls application services, and maps the answer. It MUST NOT hold a repository, an HTTP
  client, a broker client or a business decision, and no logic MUST be rewritten on the way in —
  where a command class held orchestration, that orchestration moves into an application service
  unchanged rather than into a controller.
- **FR-004**: The endpoints MUST add no capability the CLI did not have. No new selection, no new
  filter, no new field that the command did not print.

**Authorisation and audit**

- **FR-005**: Every `/operations/**` request MUST be authorised by `cp-auth-rules-filter` against
  the caller's usersgroups membership, with identity taken from the `CJSCPPUID` header.
- **FR-006**: There MUST be exactly one explicit allow rule per action in
  `src/main/resources/acl/operations-rules.drl`, each admitting the group **"Second Line Support"**
  and no other. An action with no rule MUST be refused; there MUST be no default-allow.
- **FR-007**: A caller who is not in the group, and a request with no identity, MUST be refused
  before any application service is reached and before anything is read or changed.
- **FR-008**: Every `/operations/**` request and its response MUST be published as an audit event to
  the audit context by `cp-audit-filter-springboot`.
- **FR-009**: Actuator MUST be unaffected: not behind these filters, not in the OpenAPI document,
  and answering exactly as it does today.

**The cutover lever**

- **FR-010**: `POST /operations/batches/generate` MUST read the `CourtRegisterService` flag through
  the same gate the 18:00 run uses, at exactly the point `generate-register` read it, with no cache,
  and MUST refuse — changing nothing — when it says off or is unreadable and the request did not ask
  to override.
- **FR-011**: An override (`ignoreFlag: true`) MUST be recorded in the audit event **and** on the
  run report, exactly as the command printed and counted it.
- **FR-012**: `GET /operations/flag` MUST read the flag (that is its purpose). `POST
  /operations/exception-reports` MUST read it **nowhere**. No other endpoint MUST read it.
- **FR-013**: No endpoint MUST decide which implementation is live. The flag remains the one lever.

**The seven endpoints**

- **FR-014**: `GET /operations/flag` MUST answer `200` with the flag's verdict (`ON` or `OFF`), and
  MUST answer `503` with the verdict `UNREADABLE` and the bounded reason when the flag cannot be
  read — the three outcomes `check-flag` had.
- **FR-015**: `GET /operations/batches?date=D` MUST answer that register date's batches, each with
  its id, court house, state, record count and its recipients; every recipient address MUST be
  masked by the **same rule** `list-batches` masked by, and the unmasked address MUST NOT appear in
  the response or in any log line.
- **FR-016**: `GET /operations/registers/recorded-while-off` MUST answer the records recorded while
  the flag was off, each with its record id, hearing id, register date and recorded flag state.
- **FR-017**: `POST /operations/batches/generate` MUST take a required register date and the
  optional narrowings `courtHouse`, `batchId` and `recordedBefore`, plus `ignoreFlag` (default
  `false`), and on acceptance MUST answer `202` with the assembly tally: released, registers,
  batches, requested and deferred counts, each assembled batch's id, state and record count, each
  withheld batch with its bounded reason, and whether the flag was overridden.
- **FR-018**: `POST /operations/batches/generate` MUST refuse `409 SCHEDULE_RUNNING` while the 18:00
  run holds its lock, before reading or changing anything.
- **FR-019**: `POST /operations/batches/{batchId}/notify` MUST re-request the recipients of that
  batch no e-mail has been accepted for **and only those**, and MUST answer with the accepted and
  failed counts, the batch state and the disposition; the two dispositions that leave the batch
  unsettled MUST answer `409` carrying that disposition as the bounded reason.
- **FR-020**: `POST /operations/registers/supersede` MUST take a required instant and answer the
  count superseded and the instant it was taken from. The instant MUST NOT be defaulted: an absent
  one is a refusal.
- **FR-021**: `POST /operations/exception-reports` MUST take an optional window start (`since`) and
  `email` (default `false`), MUST answer the report — window, entries, per-kind counts, truncation,
  per-sink delivery, run id and duration — and MUST refuse `409 EMAIL_OUTPUT_DISABLED` or
  `409 EMAIL_OUTPUT_NOT_WIRED` when e-mail is asked for and unavailable. With no `since` the window
  MUST run from the previous scheduled report, exactly as the command computed it.

**Refusals, failures, and what a response may carry**

- **FR-022**: The CLI's three outcomes MUST map onto three status families: did it → `2xx`; declined
  and changed nothing → `409` for a state refusal and `400` for an argument that is missing or
  cannot be read; tried and could not → `500`. `GET /operations/flag`'s unreadable case is the one
  documented exception and answers `503`.
- **FR-023**: Every non-`2xx` response MUST be a `ProblemDetail` carrying a **bounded** `reason`
  code — the code the command printed, from a closed set named in the OpenAPI document.
- **FR-024**: No response and no log line MUST contain a value the caller supplied. A refusal over
  an argument names the **argument**, by this service's own name for it, never the value. No
  exception message and no throwable this service did not write MUST reach either.
- **FR-025**: No response MUST contain defendant detail of any kind, an unmasked recipient address,
  a payload, a register document or a fragment of one.
- **FR-026**: An unknown or unmapped path or method MUST answer as the framework does, without a
  stack trace and without quoting the path.
- **FR-027**: A request body carrying an unknown field MUST be refused with a bounded reason, not
  silently ignored.

**Removing the CLI**

- **FR-028**: `batch/cli/` (every class and every test), `config/CliModeConfig` and its test MUST be
  removed.
- **FR-029**: The `courtregister.cli` property MUST be removed, and the three conditionals that read
  it — the Service Bus consumer, the schedulers and the public-event listener container — MUST
  become unconditional.
- **FR-030**: `docker/startup.sh` MUST lose its command dispatch: the entrypoint starts the
  application and nothing else, and an argument is no longer special.
- **FR-031**: Every document that instructs an operator to run a command MUST instruct them to call
  an endpoint instead: `README.md`'s operations section, `specs/002-consolidate-progression-leg/quickstart.md`'s
  CLI examples (replaced by `curl`), and the container smoke script.
- **FR-032**: The removal MUST land **after** every endpoint has a passing test, in a phase of its
  own — never interleaved, so that at no commit is there neither surface.

**Settings**

- **FR-033**: The starters' settings MUST be typed and validated at start-up like every other
  setting this service has: the identity endpoint the auth filter resolves groups through, the
  audit switch `audit.http.enabled` (defaulting **off**, enabled per environment), the OpenAPI
  document location the audit filter resolves path parameters from, and the audit transport's
  connection.
- **FR-034**: No secret, no connection string and no static credential MUST appear in a committed
  value or an environment default.

### Key Entities

- **Operator action** — one of seven named things support can do, each with a stable action name of
  the form `courtregister-operations.<verb>`, an allow rule, an endpoint and an OpenAPI entry.
  Nothing else is an action.
- **Caller identity** — the `CJSCPPUID` on the request, resolved to a usersgroups membership. It is
  an identifier, and it is the only thing about the caller that is written down.
- **Refusal** — a bounded reason code with a status family: the same codes the CLI printed
  (`FLAG_OFF` and the gate's other codes, `SCHEDULE_RUNNING`, `EMAIL_OUTPUT_DISABLED`,
  `EMAIL_OUTPUT_NOT_WIRED`, the four notification dispositions, `MISSING_ARGUMENT`,
  `UNREADABLE_ARGUMENT`, and the four failure codes `generation-failed`, `resend-failed`,
  `listing-failed`, `supersession-failed`, `report-not-built`), normalised in the OpenAPI document.
- **Audit event** — what the audit context receives for every request and every response, carrying
  the caller, the action, and — where one was given — the fact of a flag override.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every one of the six commands has an endpoint that takes the same arguments and
  answers the same fields; a reviewer can put the command's output line and the endpoint's JSON side
  by side and find no field in one that is missing from the other.
- **SC-002**: 100% of `/operations/**` endpoints are refused for a caller outside "Second Line
  Support", proven by a test per endpoint rather than by inspection of the rule file.
- **SC-003**: 100% of `/operations/**` endpoints produce an audit event, proven by a test.
- **SC-004**: Zero responses and zero log lines contain a value the caller supplied, proven by the
  privacy sweep the repository already runs plus a case per refusal.
- **SC-005**: With the flag off and no override, a generate call changes nothing — no batch row, no
  released record, no render requested — proven by a test that asserts the store was not written to.
- **SC-006**: A generate call refused `SCHEDULE_RUNNING` while the nightly lock is held, proven by a
  test that holds the lock.
- **SC-007**: After the removal phase, a repository-wide search for `batch/cli`, `CliModeConfig`,
  `courtregister.cli` and the six command names finds nothing outside this spec, the constitution's
  history and the earlier increments' own records.
- **SC-008**: The built image starts the application when started with no arguments, and the
  container smoke exercises an endpoint rather than a command.
- **SC-009**: The build stays green under the existing gates, with the coverage thresholds unchanged
  (LINE ≥ 0.88, BRANCH ≥ 0.85) — the ratchet is never loosened to admit a controller.

## Out of Scope (this increment)

- **Any business endpoint.** No hearing submitted over HTTP, no register read out, no batch created
  by a caller, no status or replay surface. Widening one of the seven into a query surface is the
  same thing by degrees, and needs a constitution amendment.
- **A second caller group.** "Second Line Support" only, on every endpoint. Adding a group is a rule
  change with its own review, not a decision this increment takes in advance.
- **A user interface.** The endpoints are called by support with a tool, exactly as the commands
  were run by support with a shell.
- **Changing what any action does.** The generation, notification, supersession and reporting
  behaviour is 002's and 003's, and is not touched.
- **The infrastructure wiring.** The ingress/APIM route for `/operations/**`, the usersgroups
  network path, and the Artemis audit connection live in the sibling infra repositories. They are
  **gates on this increment's deployment**, listed below, not work in this repository.
- **Increment 004's changes** to the reconciler and the generation settings, which land on their own
  branch and merge first.

## Deployment gates (outside this repository)

Because the CLI is **removed**, a pod deployed without these has no operational surface at all.
005 MUST NOT be deployed to STE before all three land:

1. **An internal ingress / APIM route for `/operations/**`** in the `cpp-aks-deploy` values, not
   exposed outside the estate.
2. **usersgroups reachable from the pod** for the auth filter's identity client, with whatever
   network policy that requires.
3. **The Artemis audit connection and `HTTP_AUDIT_ENABLED=true`** in the STE values.

## Assumptions

Answers taken during clarification. Where something was genuinely open, the least
behaviour-changing option was chosen.

1. **The CLI's orchestration moves into application services, verbatim.** Three of the six command
   classes hold more than argument parsing and printing: `list-batches` reads two repositories and
   the store, `generate-register` releases, narrows, re-assembles and requests, and
   `report-exceptions` computes a window and selects sinks. A controller may not call a repository
   (design rules, "Persistence"), so that orchestration moves into application-layer services with
   its logic unchanged and the controller maps the result. This is the least behaviour-changing way
   to satisfy the layering rule; the alternative — a controller holding the reads — would have been
   a new architectural exception.
2. **`GET /operations/flag` answers `503`, not `500`, when the flag cannot be read.** The command
   exited `2` (tried and could not), which FR-022 maps to `500`. The design owner's decision names
   `503` for this one case, and it is the honest code: the dependency the endpoint exists to read is
   unavailable. Recorded as the single documented exception to the exit-code mapping.
3. **The two unsettled notification dispositions answer `409`, although the command exited `2`.**
   `CLAIM_LOST` and `INCOMPLETE` left the batch where it stood and changed nothing that would make a
   retry unsafe, which is the definition of a refusal rather than a failure. The design owner's
   decision names `409` for both. This is the second and last deliberate difference from the
   command's exit code, and it is recorded here rather than argued in a commit body.
4. **`reason` values are the codes the commands already printed**, normalised to one spelling in the
   OpenAPI document rather than invented. Where a code is a gate's or a disposition's own
   (`FLAG_OFF` and the gate's other reasons, the four dispositions), it is passed through as that
   type spells it; `SCHEDULE_RUNNING` is the one new code, for the one new rule.
5. **The request contract is closed.** An unknown field in a request body is refused, for the reason
   the inbound queue message's contract is closed (FR-027): tolerating drift hides it.
6. **The 18:00 lock is observed through the lock store**, not through a flag of the service's own:
   a second switch that said "a run is happening" could disagree with whether one is.
7. **Audit is switched off by default** (`audit.http.enabled=${HTTP_AUDIT_ENABLED:false}`) and
   enabled per environment, because the transport is an environment fact. Whether an endpoint may
   be served while auditing is off, and what happens when the audit transport is unreachable, are
   decided in the plan from the starter's own behaviour rather than assumed here — the principle
   is that a call nobody can audit must not quietly proceed as though it had been.
8. **Nothing about the exception report's window or sinks changes.** The endpoint computes the same
   window the command computed, from the same schedule, and asks the same sinks.
9. **No `doc/DEFECT-FIXES.md` row.** Stated in Context and repeated here because it is the rule a
   task is most likely to break: replacing this service's own operational surface is not a deviation
   from a legacy oracle.
10. **The five removal facts are one phase, landing last.** Nothing is deleted until every endpoint
    that replaces it has a passing test (FR-032).
