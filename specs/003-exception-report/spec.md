# Feature Specification: Exception report for production support

**Feature Branch**: `003-exception-report`
**Created**: 2026-09-14
**Status**: Planned
**Input**: User description: "Exception report for production support: a scheduled and on-demand report of failed and late requests, batches and notifications, written to Azure Log Analytics and e-mailed to support, with the intake instruments the design promised; every threshold configurable"

## Context

Increments 001 and 002 made every request and every batch reach a recorded terminal state, so
that nothing this service handles can fail silently. What they did not do is turn that record into
something support reads. Today a request that failed, or one that has sat unfinished for an hour,
is findable only by querying the processed log by hand, and the intake half publishes no
measurement of how long its oldest unfinished request has been waiting. The design (Confluence
*Court Register Service*, §11) promised an "oldest unfinished request" gauge with an alert, a
failure count, a request-duration measurement and queue depth; none of the four was built, and
the currency review of 2026-09-14 recorded the gap.

Support for this estate works in Azure Log Analytics and reads e-mail. The decision taken on
2026-09-14 is to build both: a report of exceptions, produced every weekday morning and on demand,
written as structured events that Log Analytics can query field by field, and sent as an e-mail
with the full report attached; and the intake instruments, refreshed continuously so that an alert
does not have to wait for the morning run. The thresholds that define "late" are provisional. They
ship as defaults and must be adjustable per environment without a release.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Support sees every failure and every late item in Log Analytics each morning (Priority: P1)

Every weekday morning, before the working day starts, the service examines its own records for the
previous reporting window and writes one summary event and one event per exception into its logs,
where the platform's log collection carries them into Log Analytics. Support opens a saved query
and sees, without touching a database, which requests failed and why, which requests have been
unfinished for longer than allowed, which batches missed a stage deadline, which batches failed
outright, and which notifications failed. Every event carries only identifiers, bounded reason codes and timings, never a defendant's
name or a recipient's address.

**Why this priority**: This is the reason the increment exists. Everything the report says is
already recorded; making it visible where support looks is the whole value, and it ships with no
dependency on anyone outside the team.

**Independent Test**: Seed the processed log with a failed request, an unfinished request older
than the threshold, a batch past its rendering deadline, a batch that reached FAILED and a failed
notification; trigger the morning run; confirm one summary event with the five counts and five
exception events, each carrying the expected identifiers and nothing else.

**Acceptance Scenarios**:

1. **Given** a request that ended FAILED inside the window, **When** the morning run completes,
   **Then** one exception event of kind "request failed" names its source, request id, hearing id,
   hearing day, attempts and bounded failure reason.
2. **Given** a request still RECEIVED or RETRYING for longer than the configured intake threshold,
   **When** the run completes, **Then** one exception event of kind "request late" names it with its
   age in seconds, whether or not it was created inside the window.
3. **Given** a batch still awaiting its render past the configured rendering limit, or generated
   but not notified past the configured notification limit, or a recorded register that was not
   batched by the most recent scheduled generation run, **When** the run completes, **Then** one
   exception event of kind "batch late" names the batch (or the register) with its court centre,
   register date, status and age.
4. **Given** a notification that ended FAILED inside the window, **When** the run completes,
   **Then** one exception event of kind "notification failed" names the batch and the notification,
   and carries no recipient address at all: the address is never read from the store, so it cannot
   reach the event.
5. **Given** a window with nothing to report, **When** the run completes, **Then** exactly one
   summary event is written with every count at zero, so that "nothing was wrong" and "the report
   did not run" are distinguishable.
6. **Given** the same exception across two consecutive runs (a request still late on Tuesday that
   was late on Monday), **When** both runs complete, **Then** it appears in both reports; a report
   is a snapshot of the window, not a ledger of new arrivals.
7. **Given** a batch that ended FAILED inside the window, **When** the run completes, **Then** one
   exception event of kind "batch failed" names the batch, its court centre, its register date and
   its bounded failure reason, and carries no free text the generating system wrote about it.

---

### User Story 2 - The oldest unfinished request is measurable and alertable at any time (Priority: P1)

The intake half publishes, continuously, the age of its oldest unfinished request and the number
of unfinished requests older than the threshold, alongside how long each request took to reach a
terminal state. An alert on the age can fire within one refresh interval of a request being
stranded, rather than the next morning.

**Why this priority**: The morning report answers "what went wrong yesterday"; the gauges answer
"is something wrong now". The design promised both and the second is the cheaper of the two.

**Independent Test**: Insert an unfinished request with a creation time older than the threshold;
wait one refresh interval; read the two gauges and confirm the age is reported in seconds and the
count is one; complete the request; wait one interval; confirm both return to zero.

**Acceptance Scenarios**:

1. **Given** no unfinished requests, **When** the instruments are read, **Then** the oldest-age
   gauge and the over-threshold count are both zero, and both existed from start-up rather than
   appearing on first use. Each instance publishes its own reading, so an alert aggregates the
   gauge across pods with `max()`.
2. **Given** one unfinished request forty minutes old and a thirty-minute threshold, **When** one
   refresh interval has elapsed, **Then** the oldest-age gauge reports at least 2,400 seconds and the
   count reports one.
3. **Given** a request that reaches a terminal state, **When** it completes, **Then** its
   duration from receipt to terminal state is recorded once on the request-duration measurement,
   labelled only by its outcome.
4. **Given** the report job is disabled in an environment, **When** the instruments are read,
   **Then** they still exist and refresh, because the refresh belongs to the intake half and not to
   the report.

---

### User Story 3 - Support pulls the report on demand during an incident (Priority: P2)

During an incident support runs one operations command inside the pod, asks for the exceptions
since a given moment or over a given duration, and reads the same report as a table on standard
output, with the option of sending it by e-mail as well. The command reads; it never generates,
never notifies a Youth Offending Team, and never needs the cutover flag.

**Why this priority**: The morning run is the steady state. An incident does not wait for morning,
and the existing five operations commands are the surface support already uses.

**Independent Test**: With seeded exceptions, run the command with a window that covers them and
confirm the table lists each once with the same fields the events carry; run it with a window that
covers none and confirm a single line saying so; run it with a malformed window and confirm a
refusal that names the argument.

**Acceptance Scenarios**:

1. **Given** exceptions in the last two hours, **When** support runs the command with `--since 2h`,
   **Then** the output lists each exception once, oldest first, and ends with the five counts.
2. **Given** the command is run with `--since` as an instant, **When** it runs, **Then** the window
   is from that instant to now.
3. **Given** the command is run with `--email`, **When** the e-mail output is enabled and its
   template is configured, **Then** the same report is sent to the configured recipients and the
   output records that it was accepted; **When** the e-mail output is not enabled, **Then** the
   command refuses the flag and says why, without writing anything.
4. **Given** the command is run with an argument it does not accept, **When** it parses,
   **Then** it refuses with the usage line and exits with the refusal code, like every other
   operations command.

---

### User Story 4 - Support receives the report by e-mail with the detail attached (Priority: P3)

Each morning the configured support recipients receive one e-mail per run: the five counts and
the window in the body, and the full list of exceptions attached as a CSV file. The e-mail is sent
through the platform's notification service, like the register itself, and is switchable
independently of the Log Analytics output.

**Why this priority**: It is the push half of the requirement and depends on a notification
template owned by another team. The report is complete without it; this makes it arrive.

**Independent Test**: With the e-mail output enabled and a stubbed notification service, trigger
a run with seeded exceptions; confirm one send per configured recipient, each accepted, each
referencing an attachment whose content is the CSV of the same exceptions, and confirm the
attachment carries identifiers only.

**Acceptance Scenarios**:

1. **Given** three configured recipients, **When** the run completes, **Then** three sends are
   made, one per address, each recorded as accepted or failed with its response.
2. **Given** the notification service refuses one send, **When** the run completes, **Then** the
   failure is recorded and counted, the other two sends still happen, and the Log Analytics events
   were written regardless.
3. **Given** the e-mail output is enabled but no template or no recipient is configured,
   **When** the service starts, **Then** start-up refuses with a message naming the missing
   setting, rather than running mornings that send nothing.
4. **Given** a report with no exceptions, **When** the run completes, **Then** the e-mail is still
   sent, saying so, because silence must never be the signal.

---

### User Story 5 - Every threshold and schedule is a setting, with a safe default (Priority: P1)

An operator can change what "late" means, when the report runs (which is also how far back it
looks, since the window reaches back to the previous scheduled run), how often the instruments
refresh, who receives the e-mail and which template is used, per environment, without a code
change. Every setting has a documented default and start-up refuses a value that
cannot work.

**Why this priority**: The thresholds are guesses. The increment is only useful if the guesses can
be corrected in production without a release.

**Independent Test**: Start the service with each setting at its default and confirm the defaults;
start it with a zero or negative threshold, a schedule outside London time without the explicit
acknowledgement, or the e-mail output enabled with no recipients, and confirm each refusal names
the offending setting.

**Acceptance Scenarios**:

1. **Given** no report settings are provided, **When** the service starts, **Then** the report is
   disabled, and enabling it with nothing else set gives a 07:00 London weekday schedule, a window
   reaching back to the previous scheduled run of that schedule, a 30-minute intake threshold, a
   rendering threshold equal to the generation grace period and a 15-minute notification threshold.
   The gauge-refresh interval is the intake half's own setting and defaults to ten minutes whether
   or not the report is enabled.
2. **Given** a threshold of zero, **When** the service starts, **Then** it refuses and the message
   names the setting.
3. **Given** the intake threshold is changed in one environment, **When** that environment's
   instruments and report run, **Then** they use the changed value and no other environment is
   affected.

---

### Edge Cases

- **One output fails.** The e-mail half cannot be sent or the attachment cannot be stored: the
  failure is recorded with a bounded reason and counted, the Log Analytics events are still
  written, and the run is reported as partially delivered. The reverse holds too.
- **The window is empty.** A summary with zero counts is still written and still e-mailed.
- **The report run overlaps the 18:00 generation run or the reconciler.** They never share a
  thread or a lock; the report reads while the others write and reports the state it saw.
- **Two pods.** Only one pod produces the morning report. The instruments are per pod and every
  pod refreshes its own, because a gauge describes the JVM that publishes it; an alert therefore
  aggregates across pods with `max()`. The on-demand command works from any pod.
- **The report is asked for on a command-line JVM.** The scheduled run and the instrument refresh
  never start there; the on-demand command is the only path.
- **A request is late and then fails.** It appears as "request failed" once it has failed, and no
  longer as "request late"; a request is reported under one kind per run.
- **A recorded register that will never be batched because the flag was off.** It is reported as
  late only if it was recorded while the flag was on; registers recorded while the flag was off are
  the existing review command's concern and are not exceptions.
- **The reporting window is longer than retention of any input.** The report covers what the
  processed log holds; it does not invent history.
- **Clock and time zone.** The schedule is expressed in London time like the generation run;
  ages are computed against the database's own clock, never a pod's.
- **Recipient addresses.** They never appear in a log event, in the attachment or in the operator
  table; the address column is never selected by any read this feature makes, so a failed send is
  identified by its notification id, its batch and its response. Masking applies only where an
  address genuinely flows - the e-mail sink's own lines about who it sent to.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST produce an exception report over a reporting window that contains
  every request that reached FAILED inside the window, every request still unfinished for longer
  than the intake threshold at the time of the report, every batch past its stage limit at the time
  of the report (awaiting render longer than the rendering limit, generated but not notified longer
  than the notification limit, or recorded and unbatched after the most recent scheduled generation
  run), every batch that reached FAILED inside the window, and every notification that reached
  FAILED inside the window. The scheduled run's window MUST begin at the previous scheduled report
  time and end at the moment the run started, so that every failure lands in exactly one report and
  none falls between two.
- **FR-002**: Each exception MUST carry exactly one kind from a bounded set of five, and MUST carry
  only: the kind, source, request id, hearing id, hearing day, batch id, notification id, court
  centre id, register date, status, attempts, bounded reason code and age in seconds, as applicable
  to its kind. No personal data of a defendant or a recipient is ever included, and no free text
  written by another system is carried: a reason is a bounded code of this service's own.
- **FR-003**: The scheduled run MUST execute on a configurable schedule, by default at 07:00
  Europe/London on Monday to Friday, exactly once per scheduled time across all running instances,
  and MUST derive its window from that same schedule: from the previous scheduled time to now.
  A Monday run therefore covers from Friday's run, and the window is not a separate setting that
  could disagree with the schedule.
- **FR-004**: The scheduled run MUST run whether or not the generation half is enabled, and the
  intake instruments MUST refresh whether or not either half is enabled. Neither MUST ever run on a
  JVM started for an operations command, and neither MUST ever delay or block the 18:00 generation
  run or the reconciler.
- **FR-005**: The Log Analytics output MUST write one summary event per run carrying the run
  identifier, the window, and the count per kind, and one event per exception carrying the fields of
  FR-002 as individually queryable fields rather than inside free text.
- **FR-006**: The e-mail output MUST send one e-mail per configured recipient per run through the
  platform notification service, with the counts and window in the body and the full exception list
  attached as a CSV file referenced by the platform file service, and MUST be independently
  switchable from the Log Analytics output.
- **FR-007**: A failure of either output MUST be recorded with a bounded reason and counted, MUST
  not prevent the other output, and MUST leave the run reported as partially delivered rather than
  as successful or as not run.
- **FR-008**: The service MUST publish continuously the age in seconds of the oldest unfinished
  request, the number of unfinished requests older than the intake threshold, and a request-duration
  measurement from receipt to terminal state labelled by outcome only; the two gauges MUST exist
  from start-up and MUST be refreshed on a configurable interval in every instance that is not a
  command JVM. Each instance publishes its own reading of a shared store, so an alert on either
  gauge aggregates across pods with `max()`.
- **FR-009**: The service MUST provide an operations command that produces the same report on
  demand for a window given either as an instant or as a duration before now, writes it as a table
  to standard output, and optionally sends it by e-mail; the command MUST refuse the e-mail option
  when the e-mail output is disabled, and MUST require no cutover flag. When `--since` is absent the
  window MUST start at the most recent scheduled occurrence before now, so the bare command answers
  what has happened since the last report was written rather than a different question: asked before
  the morning run it reads the window that run is about to read, and asked after it, it reads what
  has gone wrong since.
- **FR-010**: The schedule, its time zone, the three thresholds, the gauge-refresh interval (which
  belongs to the intake half and not to the report), the e-mail switch, the recipients and the
  template MUST each be a configuration setting with a documented default, and start-up MUST refuse
  a non-positive threshold or interval, a schedule outside London time without explicit
  acknowledgement, or an enabled e-mail output with no recipients or no template, naming the
  setting in each case. There is no separate window setting to refuse: the window is the schedule.
- **FR-011**: Every log line and event of the report and of the refresh MUST carry the run
  identifier of the run it belongs to, and MUST carry no defendant or recipient personal data at
  any level that is enabled in a deployed environment.
- **FR-012**: A window with no exceptions MUST still produce the summary event and, when enabled,
  the e-mail.
- **FR-013**: A request MUST be reported under at most one kind per run: a failed request is
  never also late.
- **FR-014**: The report MUST NOT change any recorded state: it reads the processed log and
  writes nothing to it.

### Key Entities *(include if feature involves data)*

- **Exception report**: One run's findings: the run identifier, the window (from, to), the
  moment the snapshot was taken, the count per kind, and the list of exceptions.
- **Exception**: One thing wrong, of one kind, identified by the identifiers of the record it
  concerns and carrying its status, attempts, bounded reason and age.
- **Exception kind**: The bounded set of five: request failed, request late, batch late, batch
  failed, notification failed.
- **Report settings**: The schedule, zone, intake threshold, rendering threshold, notification
  threshold, e-mail switch, recipients and template identifier - plus the intake half's own
  gauge-refresh interval, which is a setting of the intake half rather than of the report.
- **Delivery record**: For each run and each output, whether it was delivered, and if not, the
  bounded reason.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every request that reached FAILED during a reporting window appears exactly once in
  that window's report, under the "request failed" kind, in every run: zero misses and zero
  duplicates across a seeded set of at least fifty mixed records.
- **SC-002**: A request left unfinished is visible on the oldest-age gauge within one refresh
  interval of crossing the threshold, and the gauge returns to zero within one interval of the
  request completing.
- **SC-003**: The morning report's events are queryable in Log Analytics by kind, by request id
  and by batch id as separate fields, with no field parsing of message text needed.
- **SC-004**: When the e-mail output is enabled, every configured recipient receives one e-mail per
  run, and a refused send for one recipient does not prevent the others or the events.
- **SC-005**: Changing any threshold or the schedule takes effect on the next run or refresh in
  that environment alone, with no release. There is no separate window to change: the window is the
  schedule.
- **SC-006**: The on-demand command returns its table within ten seconds for a `--since 24h`
  window over a processed log holding one week of production-scale data.
- **SC-007**: No event, line, table row or attachment produced by this feature contains a
  defendant's name, date of birth, address, ASN or URN, or an unmasked recipient address, verified
  by the existing privacy sweep extended to every new component.
- **SC-008**: The 18:00 generation run's duration is unaffected by a concurrent report run or
  refresh, verified against the existing run-report timings.

## Out of Scope (this increment)

- Alert rules, dashboards and saved queries in Azure Monitor or Log Analytics: they remain under
  the observability waiver and land with the operability story; this increment makes them possible.
- Any HTTP or REST surface for the report.
- Any change to the register document, the inbound message contract, or the consumed platform
  contracts; the notification service and file service are used as they are.
- Queue depth and dead-letter depth: read from the platform's own metrics, as the design already
  states.
- Retention or archiving of past reports.

## Assumptions

- Support's tooling ingests the service's container logs into Log Analytics and can query a JSON
  line's fields; the platform's standard log collection provides this and no new ingestion path is
  needed.
- A notification template for the report e-mail will be provided by the team that owns the
  notification service; until it is, the e-mail output stays disabled and User Story 4 is not
  deliverable, while every other story is.
- Attaching the CSV through the platform file service is acceptable to the platform, as it is
  for the register PDF; if it is refused, the fallback is an e-mail body without an attachment,
  recorded as a deviation.
- The recipients list and the template identifier are provided per environment as secrets in the
  same way every other per-environment value is, never in a chart value.
- The default thresholds (30 minutes intake, the generation grace period for rendering, 15 minutes
  notification) are provisional and will be tuned from production experience. The window is not
  among them: it is derived from the schedule rather than guessed.
- A Monday run reports back to Friday's run, because the window is the interval since the previous
  scheduled run rather than a fixed duration. That is what makes every FAILED request and every
  FAILED notification land in exactly one report, weekends included, without an operator having to
  remember to widen a Monday window; changing the schedule changes the window with it.
- The processed log is the only source of the report; nothing is queried from the platform's
  services at report time.
