# Feature Specification: Release stale in-flight batches before batching

**Feature Branch**: `004-release-stale-batches`
**Created**: 2026-09-19
**Status**: Planned
**Input**: User description: "Release stale in-flight batches before batching, and retire the reconciler's timer and its systemdocgenerator query"

## Context

Increment 002 gave every batch two ways to learn what became of its render: the `document-available`
and `generation-failed` events on the shared `public.event` topic, and — for the batch whose event
never arrived — a grace-period reconciler that ran on its own ten-minute timer, asked
systemdocgenerator's query API what had happened to the payload, and applied the answer. The query
was the flow's one synchronous coupling to the renderer, and the timer was one of the two schedules
the generation half carried.

The design owner's decision of 2026-09-19 retires both. What replaces them is a single pass at the
start of the nightly run: a batch that is still waiting for its render when the next run begins,
and has been waiting longer than a minimum age, is failed under a new bounded reason and its
registers are given back, so the assembler puts them in a batch tonight and the court centre gets
its document tonight. Nothing is asked of systemdocgenerator between runs.

The grounds for the change, recorded here because this is the document that outlives the decision:

- **The event path already handles a slow render.** A render that takes twenty minutes still
  announces itself, and the batch still reaches GENERATED and still notifies. The query only ever
  mattered for an outcome that was *lost*, not for one that was late.
- **Visibility is already covered.** The 07:00 exception report has its own late-batch threshold and
  reports a batch that has been awaiting its render too long, every weekday morning, with no
  dependence on the query or the timer.
- **The query bought one cheap re-render and nothing else.** When it found a document, it saved a
  second render of the same batch; when it found a refusal, it recorded a bounded reason the next
  run would have reached anyway; when it found silence, it failed the batch and stranded its
  registers behind an operator decision. A re-render is cheap. The stranding was not.
- **Progression's leg, the oracle for this half, had no timeout at all.** Removing the timeout is a
  move back towards the behaviour this service ported, not away from it.

**The trade, recorded plainly**: a render that goes silent is now detected at the next scheduled run
rather than within ten minutes of going silent. Between 18:00 and the following 18:00, a batch whose
outcome is lost stays GENERATING and its registers stay stamped. The 07:00 report names it the next
morning; the run that follows releases it. This is a deliberate exchange of detection latency for
the removal of a synchronous dependency, a timer, a lock and an entire failure mode
(a batch failed on the strength of a query the renderer could not answer).

This is **not a defect fix**. Neither oracle — the function app for the intake half, progression's
leg for the downstream half — had a reconciler, a query or a stale-release pass, so nothing here is a
catalogued defect being fixed. No `doc/DEFECT-FIXES.md` row is added. One existing row (`P2`) names a
pinning test that this increment removes, and that row's pinning-test cell is re-pointed at the test
that holds the same promise afterwards — an amendment to a cell, not a new row and not a change to
what P2 claims. The differential audit is unaffected in both directions.

## Clarifications

### Session 2026-09-19

Five decision points were found by the ambiguity scan. Each was answered from the design owner's
decision of 2026-09-19 and from what the existing code makes possible; where the decision left
genuine latitude, the option that changes the least behaviour was taken. Each answer is applied in
the section named beside it.

- Q: Is a stale batch released by failing it and then asking for a separate release, or by a failure
  that releases in the same act? → A: **The same act.** The new reason joins the reasons that release
  a batch's registers as part of failing it. Two statements leave a window in which the batch is
  FAILED and its registers are still stamped, and a run that stopped in that window would strand them
  exactly as the behaviour this increment removes did. *(FR-003, Assumptions.)*
- Q: Does the run line's released count count batches or registers? → A: **Batches.** It replaces a
  count of batches and is read beside counts of batches; how many registers came back is already
  answerable from the assembly counts on the same line. *(FR-009, Assumptions.)*
- Q: Are the retired timeout reason and the retired completion mechanism removed from the bounded
  vocabularies, or kept? → A: **Kept as readable history, refused on the write path.** Rows already
  written carry them and every read of such a row must keep working — the read that would otherwise
  fail is the 07:00 report's, over the failed batches support most needs to see. Removing them would
  cost a forward-only migration to narrow the schema and would buy two fewer unproduced values.
  "Retired" is therefore enforced where writes happen. *(FR-012, Edge Cases, Assumptions.)*
- Q: What becomes of the deployment shape that learned outcomes only from the query? → A: **Removed
  outright**, rather than left as a setting with one legal value. After this change it would mean
  "learn no outcome, fail every batch at the next run, render every day twice" — strictly worse than
  refusing to start. A deployment with the generation half enabled must be configured for the
  public-event topic, which is what every deployment of this service is configured for today.
  *(FR-013, Edge Cases, Assumptions.)*
- Q: Does the 07:00 report's rendering limit follow the renamed setting, or get a value of its own?
  → A: **Its own value, keeping today's ten minutes.** The two durations now answer different
  questions — "when should support be told a render is late" and "when does a run give up and
  re-batch" — and following the renamed setting would silently move the report's threshold from ten
  minutes to thirty as a side effect of this increment. *(FR-014, Assumptions.)*

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A night's registers are never stranded by a render nobody heard about (Priority: P1)

A batch was assembled, its payload stored and its render requested, and the outcome never came back —
the event was lost, the pod died between the request and the mark, or systemdocgenerator simply never
answered. Today that batch sits in flight and its registers keep its stamp, so the next run passes
its court centre day over and the court centre gets no document, night after night, until a person
notices. After this change the next run's first act is to give up on it: the batch is failed under a
bounded reason that says exactly that, its registers are released, and the assembler puts them in
tonight's batch for the same court centre and register date.

**Why this priority**: This is the reason the increment exists. Everything else in it is the removal
of the machinery this replaces.

**Independent Test**: Seed a batch in GENERATING whose render was requested the previous evening, with
two registers stamped into it; run the nightly run; confirm the batch is FAILED under the new reason,
its two registers are unstamped and active, and tonight's assembly contains a new batch for the same
court centre and register date holding those two registers.

**Acceptance Scenarios**:

1. **Given** a batch in GENERATING whose render was requested longer ago than the minimum age,
   **When** the run starts, **Then** it is FAILED with the new bounded reason before any assembly
   happens, its registers are released, and it names no completion mechanism, because nobody outside
   this service reported anything about it.
2. **Given** a batch in PENDING that minted a payload longer ago than the minimum age and was never
   recorded as requested, **When** the run starts, **Then** it is FAILED under the same reason and
   released the same way: whether the request reached the renderer is a question this service can no
   longer ask, and both endings are "it did not complete before the next run".
3. **Given** a released batch's registers and a court centre day the assembler would otherwise have
   passed over because a batch of that key was in flight, **When** assembly runs in the same run,
   **Then** the key is no longer in flight, the registers are assembled into a new batch, and the
   court centre receives its document that night.
4. **Given** several stale batches, **When** the run starts, **Then** each is its own attempt: one
   that cannot be failed leaves the others to be failed anyway, oldest first.
5. **Given** a stale batch and a register that has since been re-shared for the same hearing,
   **When** the stale batch's registers are released, **Then** supersession decides which register is
   active, exactly as it does for the two existing releasing reasons — a release never makes two
   registers active for one hearing.
6. **Given** no stale batch at all, **When** the run starts, **Then** the pass does nothing, writes
   no row, and the run proceeds to assembly as it does today.

---

### User Story 2 - A render requested minutes ago is left alone (Priority: P1)

An operator asked for a court centre's register to be generated by hand at 17:52, and the nightly run
starts at 18:00. That batch is eight minutes into a render that is very probably going to succeed.
The run must not fail it, must not release its registers, and must not assemble a second batch for
the same court centre day — which is the double document and the double e-mail this service's
in-flight rule exists to prevent.

**Why this priority**: Without the minimum age the pass is not a safety net, it is a nightly
destruction of whatever the evening was doing. The rule is only safe because of the age.

**Independent Test**: Seed a batch in GENERATING requested ten minutes before the run against a
thirty-minute minimum age; run the nightly run; confirm the batch is untouched, its registers are
still stamped, and the assembler passed its court centre day over as it does today.

**Acceptance Scenarios**:

1. **Given** a batch in flight for less than the minimum age, **When** the run starts, **Then** it is
   not failed, its registers are not released, and nothing about it is counted.
2. **Given** that same batch, **When** assembly runs, **Then** its court centre day is deferred by the
   existing in-flight rule and appears in the run report's deferred counts, exactly as today.
3. **Given** a batch exactly at the minimum age, **When** the run starts, **Then** the boundary is
   decided once and stated: a batch is stale when it has been in flight for **at least** the minimum
   age, and the age is measured against the store's own clock rather than a pod's.
4. **Given** a batch in GENERATED that has a document but has not been notified, **When** the run
   starts, **Then** it is not touched by this pass at any age: it holds a document somebody is owed
   e-mails about, and failing it would throw that document away.

---

### User Story 3 - A Youth Offending Team is never told twice (Priority: P1)

The batch this service gave up on at 18:00 may still be alive inside systemdocgenerator, and its
`document-available` may arrive at 18:05 — after the registers have been released and re-batched.
That late outcome must move nothing. It is what stops the same court centre's register going out
twice: once from the batch the outcome belongs to and once from the batch tonight's run made.

**Why this priority**: It is the correctness guarantee the whole change rests on, and the behaviour is
**unchanged** — a late or duplicate outcome for a batch already FAILED has always moved nothing and
been counted. What is new is that this increment creates a new way to reach that state, so the rule
is pinned for the new reason by name.

**Independent Test**: Fail a batch under the new reason, then deliver a `document-available` for it;
confirm the batch is unchanged, no notification is sent, and the drop is counted under its existing
bounded reason.

**Acceptance Scenarios**:

1. **Given** a batch failed under the new reason, **When** a `document-available` for it arrives on
   the topic, **Then** nothing moves, no e-mail is sent, and the drop is counted under a bounded
   reason.
2. **Given** that same batch, **When** a `generation-failed` for it arrives, **Then** nothing moves,
   the batch keeps the reason this service gave it, and the drop is counted.
3. **Given** a batch failed under the new reason and re-batched, **When** the new batch completes
   normally, **Then** the court centre's Youth Offending Teams receive exactly one e-mail for that
   register date from that run.

---

### User Story 4 - The night says what it released, and asks nothing between runs (Priority: P2)

The run report is how support reads a night. It must say how many batches this run gave up on and
released, where it used to say how many outcomes the reconciler had to fetch. Between runs, nothing
queries systemdocgenerator at all, and no timer or lock exists for a reconciliation that no longer
happens. The readings that describe how long the oldest in-flight batch has been waiting must survive
the timer that used to take them, because a reading taken once a day is not a reading.

**Why this priority**: The removal is most of the work, and a removal that quietly took three
continuous measurements with it would be a regression nobody asked for.

**Independent Test**: Run a night with two stale batches and one fresh one; confirm the run line
carries the released count and no reconciled count; confirm no request is made to systemdocgenerator
other than the render requests the run itself makes; confirm the in-flight age readings are still
refreshed between runs.

**Acceptance Scenarios**:

1. **Given** a run that released two stale batches, **When** it writes its line, **Then** the line
   carries a released count of two and carries no reconciled count.
2. **Given** a run that released none, **When** it writes its line, **Then** the released count is
   zero — a night that released nothing and a night that did not report are different lines.
3. **Given** a run the flag stopped, **When** it writes its line, **Then** the release pass did not
   run at all: a run that may not generate may not decide a batch it is not allowed to re-render has
   failed, and the released count is zero.
4. **Given** the service running between two nightly runs, **When** nothing is scheduled to happen,
   **Then** systemdocgenerator receives no request of any kind, and the service holds no lock and no
   timer for reconciliation.
5. **Given** a batch that has been in flight for hours, **When** the in-flight age readings are taken,
   **Then** they report its age within one refresh interval, in every instance that is not a command,
   as they did when the retired timer took them.

---

### User Story 5 - The minimum age is a setting with a safe default (Priority: P2)

An operator can change what "too long in flight" means, per environment, without a release, and the
service refuses at start-up a value that cannot work.

**Why this priority**: Thirty minutes is a first guess. The pass is destructive — it fails a batch and
re-renders a day — so the guess must be correctable in production, and a zero or negative value must
be refused rather than discovered at 18:00 by a run that fails every batch it can see.

**Independent Test**: Start the service with nothing set and confirm the documented default; start it
with zero and with a negative value and confirm each refusal names the setting.

**Acceptance Scenarios**:

1. **Given** no value is provided, **When** the service starts, **Then** the minimum age is thirty
   minutes.
2. **Given** a zero or negative value, **When** the service starts, **Then** start-up is refused and
   the message names the setting.
3. **Given** a changed value in one environment, **When** that environment's next run starts,
   **Then** it uses the changed value and no other environment is affected.

---

### Edge Cases

- **A batch that is stale and whose outcome arrives during the run.** The release and the outcome race.
  Whichever reaches the batch first wins, because a batch state change is a compare-and-set: either
  the outcome lands and the batch is GENERATED (and the release finds nothing to fail), or the release
  lands and the outcome is a late one for a FAILED batch, which moves nothing and is counted. There is
  no third result and no lost update.
- **The release cannot be written.** The pass runs before assembly, inside the run, under the run's own
  lock. A batch that cannot be failed leaves the batches behind it to be failed anyway; a pass that
  cannot read at all fails the run the way any other unreadable store does — reported on the run line
  and rethrown, never logged and continued.
- **A run started by hand from the operations surface.** The pass belongs to the scheduled run's
  sequence. Whether the on-demand generation command runs it too is answered in the Assumptions
  below, because it decides whether an operator regenerating one court centre can disturb another's
  in-flight batch.
- **A stale batch whose registers a later re-share has superseded.** The existing supersession order
  decides, and it already covers exactly this case for the two reasons that release today.
- **Two pods.** Only one runs the nightly run; the pass is inside it and inherits its lock. The
  in-flight age readings are per pod, as every gauge in this service is, and an alert aggregates them
  with `max()`.
- **The retired reason on rows already written.** Rows written before this change may carry the retired
  timeout reason and the retired completion mechanism. Every read of those rows must keep working:
  the report reads FAILED batches every morning, and a read that could not name a value the row holds
  would fail on the one row support most needs to see.
- **The escape hatch that depended on the query.** One deployment shape existed in which the broker
  was not subscribed to and outcomes were learned only by the query. With the query gone, that shape
  would learn no outcome at all and would re-render every batch every night. It is addressed in the
  Requirements rather than left to mean something it no longer can.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The scheduled nightly run MUST, after reading the cutover flag and before assembling
  anything, fail every batch that is still awaiting its render and has been doing so for at least the
  configured minimum age, and MUST release that batch's registers so that the same run's assembly
  includes them.
- **FR-002**: "Still awaiting its render" MUST mean a batch in PENDING or in GENERATING and nothing
  else. A batch that holds a document MUST never be failed by this pass, at any age.
- **FR-003**: The failure MUST carry a new bounded reason of this service's own, distinct from every
  existing reason, meaning "this batch had not completed by the time the next run began". It MUST
  name no completion mechanism, because nobody outside this service reported anything about it, and
  it MUST be one of the reasons that release a batch's registers.
- **FR-004**: A batch in flight for less than the minimum age MUST be left exactly as it is, and the
  existing rule that defers a court centre day whose batch is in flight MUST continue to apply to it
  unchanged.
- **FR-005**: A run the flag stopped MUST NOT run the pass. A run that may not generate may not decide
  that a batch it would not be allowed to re-render has failed.
- **FR-006**: The service MUST NOT query systemdocgenerator about a payload's fate, on any schedule or
  at any point in a run. The only calls this service makes to systemdocgenerator are the render
  requests the run itself makes.
- **FR-007**: The service MUST NOT carry a scheduled reconciliation, nor a lock for one. After this
  change the generation half carries exactly one schedule: the nightly run.
- **FR-008**: An outcome that arrives for a batch this service has already failed MUST move nothing,
  MUST send no e-mail, and MUST be counted under a bounded reason — including for the new reason, and
  including for an outcome that arrives after the registers have been released and re-batched.
- **FR-009**: The run report MUST state how many batches the run released, in place of the count of
  outcomes it used to fetch, and MUST state zero rather than nothing where it released none. The
  released count MUST be a count of batches, matching the count it replaces.
- **FR-010**: The minimum age MUST be a configuration setting with a documented default of thirty
  minutes, and start-up MUST be refused, naming the setting, for a zero or negative value.
- **FR-011**: The readings that say how long the oldest batch awaiting a render, the oldest batch that
  never reached the renderer and the oldest batch holding an unnotified document have been waiting MUST
  continue to be refreshed between nightly runs, on a configurable interval, in every instance that is
  not a command, and MUST settle nothing and hold no lock.
- **FR-012**: Values that rows already written may carry but nothing will write again — the retired
  timeout reason and the retired completion mechanism — MUST remain readable. Every existing read MUST
  keep working over a row that carries them, and the write path MUST refuse to produce either of them
  again, so that "retired" is enforced where writes happen rather than asserted in a comment.
- **FR-013**: The deployment shape that learned outcomes only by the query MUST be removed rather than
  left to mean "learn no outcome at all". A deployment with the generation half enabled MUST learn its
  outcomes from the public-event topic, and MUST be refused at start-up if it is not configured to.
- **FR-014**: The setting that today names the reconciler's grace period MUST be renamed to name what
  it now decides. Every other setting that borrowed its value MUST either be re-pointed at the renamed
  setting or given a value of its own, and the choice MUST be stated in the plan rather than left to
  the reader of two records.
- **FR-015**: Every line this pass writes MUST carry the run's own correlation identifier, MUST name a
  batch by identifier only, and MUST carry no defendant or recipient personal data and no free text
  another system wrote.
- **FR-016**: The repository's own documentation of the flow — the two-leg diagram, the batch state
  machine, the rule about what the retired pass was allowed to invent, the consumed-contracts table,
  the README's account of the generation half, and the agent scope paragraphs — MUST be updated in the
  same increment, so that no document in this repository describes a query that no longer exists.

### Key Entities *(include if data involved)*

- **Stale batch**: A batch in PENDING or GENERATING whose in-flight stamp is at least the minimum age
  old, as read at the moment the run starts.
- **Minimum age**: How long a batch may be in flight before a run gives up on it. One setting,
  thirty minutes by default.
- **Release**: Failing a stale batch under the new bounded reason and giving its registers back in the
  same act, so the batch row remains the audit of what happened and the registers become assemblable.
- **Released count**: How many batches one run released, carried on the run report and counted.
- **Historical value**: A bounded value that rows already written may carry and that nothing writes any
  more — the retired timeout reason and the retired completion mechanism.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A batch whose render outcome is lost costs its court centre **one night** rather than
  every night until a person intervenes: the run after the loss releases it and produces the document.
- **SC-002**: Across a night with a mix of stale and fresh batches, every batch older than the minimum
  age is released and no batch younger than it is touched: zero false releases over a seeded set
  covering both sides of the boundary.
- **SC-003**: No Youth Offending Team receives two e-mails for one court centre and register date as a
  result of a release, verified by delivering a late outcome for a released batch whose registers have
  been re-batched and completed.
- **SC-004**: The service makes **zero** requests to systemdocgenerator between two nightly runs,
  verified over a window covering at least one former reconciliation interval.
- **SC-005**: The run line answers "how many did this night give up on" for every run, including the
  nights that gave up on none and the nights the flag stopped.
- **SC-006**: The in-flight age readings are no less current after this change than before it: a batch
  stuck for an hour is visible on them within one refresh interval, not at the next nightly run.
- **SC-007**: Changing the minimum age takes effect on the next run in that environment alone, with no
  release; a non-positive value stops the service starting and the refusal names the setting.
- **SC-008**: No document in this repository refers to a systemdocgenerator query, a grace period or a
  scheduled reconciliation after this increment, and the full quality gates — including the coverage
  ratchet and the differential and consolidation audits — are green.

## Out of Scope (this increment)

- Any change to what a batch does once its outcome **is** learned: the event path, the notification
  leg, the supersession rule and the register document are untouched.
- Any change to the intake half, to the 07:00 exception report's own logic, or to the cutover lever.
  The report keeps its own late-batch threshold and remains gated by the flag nowhere.
- Any retry of a released batch's render inside the same run beyond the ordinary assembly the release
  makes possible. A released batch is re-rendered because its registers are assemblable again, not
  because anything re-requests the old batch.
- Deleting historical rows, or migrating rows that carry a retired value to a new one. They are
  history and are read as such.
- Any REST or HTTP surface. This service has none and gains none here.
- The prison court register, SJP, and the legacy repositories.

## Outside this repository (flag, do not do here)

- **The Confluence design document** (*Court Register Service*, CRA space) sections describing the
  grace-period reconciler and the query API: the design owner's own write-up.
- **The Gliffy diagram**: the dashed service-to-systemdocgenerator query arrow and its step label.
- **STE and environment values**: the renamed setting's environment variable on the deployment
  branches, and the removal of the retired completion-mode variable where one is set.
- Nothing in this increment requires a change by another team: no consumed contract changes, and a
  contract this service simply stops calling is not a contract change.

## Assumptions

- **The minimum age is thirty minutes and the boundary is inclusive.** A batch is stale at exactly
  thirty minutes. Thirty minutes is comfortably longer than a render of this size takes and
  comfortably shorter than the gap between runs, and an inclusive boundary means the rule can be
  stated in one clause rather than two.
- **The age is measured from the stamp each state already carries**: from when the render was
  requested for a GENERATING batch, and from when the batch was assembled for a PENDING one. Both are
  columns the store already keeps and the retired pass already read.
- **The release is one statement, not two.** The new reason joins the reasons that release a batch's
  registers as part of failing it, rather than the run failing the batch and then asking for a
  separate release. Two statements leave a window in which a batch is FAILED and its registers are
  still stamped, and a run that stopped in that window would have stranded them exactly as the old
  behaviour did.
- **`released` counts batches.** It replaces a count of batches and is read beside counts of batches;
  how many registers came back is already answerable from the assembly counts on the same line.
- **The on-demand generation command does not run the pass.** An operator regenerating one court centre
  must not, as a side effect, give up on another court centre's in-flight batch. The pass belongs to
  the scheduled run. If an operator needs a stale batch released, the existing per-batch release the
  operations surface already offers is the supported way, and it is unchanged.
- **The retired timeout reason and the retired completion mechanism are kept as readable history and
  refused on the write path.** Nothing is deployed yet, so no production row carries either, but the
  local and test corpora do and the schema admits them; removing them from the vocabulary would make
  a read of such a row fail, and the read that would fail is the 07:00 report's. Keeping them readable
  costs two unproduced values; removing them costs a forward-only migration and a report that cannot
  read its own history. The schema's bounded lists are widened for the new reason and are not narrowed.
- **The escape hatch that learned outcomes only by the query is removed outright** rather than left as
  a setting with one legal value. After this change it would mean "learn no outcome, fail every batch
  at the next run, and render every day twice" — strictly worse than refusing to start. A deployment
  with the generation half enabled must be configured for the public-event topic, which is what every
  deployment of this service is configured for today.
- **The report's own rendering limit stops borrowing from the generation half and gets a value of its
  own, keeping today's ten minutes.** The two durations now answer different questions — one is "when
  should support be told a render is late", the other is "when does a run give up and re-batch" — and
  a borrowed value would silently change the 07:00 report's behaviour as a side effect of this
  increment. Ten minutes preserves the report exactly as it is.
- **The in-flight age readings keep a refresh of their own.** They were taken by the retired timer; they
  move to a refresh that holds no lock and settles nothing, in the shape this service already uses for
  its intake gauges. This is the one addition in an otherwise subtractive increment, and it exists only
  so that the removal does not cost three continuous measurements.
- **The `P2` defect-fix row's pinning-test cell is re-pointed, and nothing else about the row changes.**
  P2 promises that a failed render is never silently dropped; the event half of that promise is
  untouched, and the half that named the retired pass's timeout test is re-pointed at the test that
  holds the equivalent promise afterwards — that a batch nothing was ever learned about reaches an
  explicit recorded failure rather than sitting in flight. No new row is added and no row's claim
  changes.
