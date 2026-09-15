# Data Model: Exception report for production support

What this increment adds to the store (two indexes and nothing else), what it reads, and the typed
model the report is. Everything below is additive: no column, constraint, table or state machine of
V1, V2 or V3 changes, and the report writes nothing back (FR-014).

## `V4__processed_request_report_indexes.sql`

Additive and forward-only. Two indexes, no columns, no tables, no constraints.

```sql
-- V4 - the reads the exception report and the intake sweep make.
--
-- Neither index changes what the store holds. They exist because two of this service's reads are
-- now made on a schedule rather than by a support engineer with time to wait: the sweep's read runs
-- every gauge-refresh interval for the life of every pod, and the morning report's failed-since read
-- runs over whatever a week of deliveries left behind.

-- The sweep's read, and the report's REQUEST_LATE read. Partial, because the rows it serves are the
-- handful that are still in flight: a full index on created_at would be the size of the table and
-- would be scanned past every terminal row in it. The predicate is written exactly as the query's
-- own WHERE clause writes it, because Postgres matches a partial index by proving the query's
-- predicate implies the index's, and a differently spelled equivalent is a planner coin toss.
CREATE INDEX idx_request_non_terminal_created
    ON processed_request (created_at)
 WHERE status IN ('RECEIVED', 'RETRYING');

-- The report's REQUEST_FAILED read, and the support query that asks what failed yesterday. Not
-- partial: it serves every status, and the window boundary is on updated_at because that is when a
-- row reached the state being asked about - created_at would answer "which requests that ARRIVED
-- yesterday failed", which is a different and less useful question on a morning after an outage.
CREATE INDEX idx_request_status_updated
    ON processed_request (status, updated_at);
```

`SchemaMigrationV4IT` asserts both exist, that the partial one carries its predicate, and that every
V1-V3 object is unchanged.

## What the report reads, and from where

| Kind | Source | Predicate | Age measured from |
|---|---|---|---|
| `REQUEST_FAILED` | `processed_request` | `status = 'FAILED' AND updated_at >= window.from` | `updated_at`, the moment it was parked |
| `REQUEST_LATE` | `processed_request` | `status IN ('RECEIVED','RETRYING') AND created_at < now - request-terminal-within` | `created_at`, the moment it arrived |
| `BATCH_LATE` (awaiting render) | `register_batch` through `RegisterBatchRepository.latePending(Instant)` and `.lateGenerating(Instant)` | `status = 'PENDING' AND assembled_at < :before`, and `status = 'GENERATING' AND requested_at < :before` | `assembled_at` / `requested_at`, **in SQL** |
| `BATCH_LATE` (told nobody) | `register_batch` through `RegisterBatchRepository.lateGenerated(Instant)` | `status = 'GENERATED' AND generated_at < :before` | `generated_at`, **in SQL** |
| `BATCH_LATE` (never batched) | `processed_output` through `RegisterStore.recordedUnbatchedBefore(Instant)` | active, unsuperseded, unbatched, recorded while the flag was ON, **and** `register_time < :before` | `register_time`, the moment it was recorded, **in SQL** |
| `BATCH_FAILED` | `register_batch` through `RegisterBatchRepository.failedSince(Instant)` | `status = 'FAILED' AND failed_at >= window.from` | `failed_at`, the moment the batch was failed, **in SQL** |
| `NOTIFICATION_FAILED` | `register_notification` joined to its `register_batch` | `status = 'FAILED' AND sent_at >= window.from` | `sent_at`, the moment the send was settled |

Notes that matter:

- **The two late kinds are not bounded by the window** and must not be (acceptance scenario 1.2,
  FR-001). A request that has been stuck for three days is late this morning whether or not it
  arrived inside the last 24 hours; a window filter would make the longest-running problem the first
  one to disappear from the report.
- **The three failed kinds are bounded by the window**, so a report is a snapshot of a period rather
  than a growing ledger. The same still-late request appears in Monday's report and in Tuesday's
  (acceptance scenario 1.6), and that is correct: a report is a statement about a moment.
- **A request is reported under at most one kind per run** (FR-013). The two intake predicates are
  disjoint by construction - `FAILED` is terminal and `RECEIVED`/`RETRYING` are not - so a request
  that was late and has since failed appears once, as `REQUEST_FAILED`.
- **A register recorded while the flag was OFF is never late.** `recordedUnbatchedBefore(...)`
  carries `activeUnbatched()`'s own predicate, which already excludes it, and that is exactly the
  spec's rule: those rows are the existing `list-batches --recorded-while-off` command's concern.
- **A failed batch carries its bounded reason and nothing else.** `register_batch.sdg_reason` is
  systemdocgenerator's own words about a document, written by another system, and it is **never**
  carried onto an event, a table row, a CSV cell or a metric label: constitution Principle VII bars
  free text this service did not write from telemetry, and a reason a support engineer pastes into a
  ticket must be a code with a fixed meaning. `BATCH_FAILED` therefore reports `failure_reason`
  alone, which is a `BatchFailureReason` value and bounded by that enum.
- **Every age is computed by the database**, as
  `extract(epoch from (now() - <column>))::bigint AS age_seconds`, in the same statement that selects
  the row - for the batch and register kinds as much as for the intake ones. That is why the three
  `BATCH_LATE` sources are **new** reads rather than 002's `pendingSince` / `generatingSince` /
  `generatedSince` and `activeUnbatched()`: those four answer **entities**, which carry no age, and
  deriving one in the JVM from a stored timestamp is exactly the cross-clock comparison V1's
  single-time-authority rule forbids. The new reads answer **projections** that carry `ageSeconds`
  from the same statement. 002's four entity reads are untouched and keep their existing callers. No JVM clock reading is compared against a stored timestamp, which is V1's
  single-time-authority rule. The cut-off parameters above (`window.from`, `now - threshold`,
  `LastScheduledRun`) are a different thing: they are boundaries the caller chose, and passing one in
  states what was asked for rather than comparing two clocks.
- **Recorded, not resolved**: `register_batch`'s five timestamps and `register_notification.sent_at`
  are written from the JVM by the code that settles the row; only `processed_request.created_at` and
  `updated_at` default to the database's `now()`. So the two batch ages and the notification age are
  `now()` minus a pod-written timestamp. The error is at most a pod's clock skew, it affects a
  reported number and never a decision about a claim, and changing it would mean a migration that
  rewrote the 002 state machines' defaults.
- **The two window reads rely on a write-path invariant, and it holds** (review gate 2 asked, and the
  write paths were read before the reads were left alone). `BATCH_FAILED` bounds on `failed_at` and
  measures its age from it, and `NOTIFICATION_FAILED` does the same with `sent_at`; a null in either
  would drop the row out of the predicate silently, which is a dead batch or an untold Youth
  Offending Team missing from the morning report. **Neither can be null on a FAILED row.**
  `JdbcRegisterStore.MARK_FAILED` writes `failed_at = now()` in the same `UPDATE` that writes the
  status, and it is the only production path a batch reaches FAILED by -
  `RegisterBatchRepository.compareAndSet`'s whole-row write has no production caller.
  `RegisterNotifierService.settledAs` stamps `clock.instant()` on every terminal attempt, an
  acceptance and a refusal alike, including a connection that reached no status line at all; a minted
  PENDING row is the one shape with no stamp, and PENDING is not FAILED. So the reads carry **no
  `COALESCE`**: a fallback there would be a second answer to a question the write path only ever
  answers one way, and it would hide the day that stopped being true. The invariant is pinned where
  it is produced, by `RegisterStoreIT.a_failed_batch_always_carries_its_failed_at` and
  `RegisterNotifierServiceTest.a_failed_notification_always_carries_its_sent_at`.
- **Every window predicate is inclusive at its start** (`>= :since`) and exclusive at the cut-off
  (`< :before`). Consecutive windows therefore abut: a row settled on the very instant a run closed
  its window belongs to the next report rather than to neither, which is the boundary a run is
  likeliest to have been in the middle of writing. Pinned by
  `failed_since_includes_a_row_failed_exactly_at_the_window_start` in the request and notification
  suites.
- **`recordedUnbatchedBefore` orders on `register_time, output_id`**, the two columns
  `activeUnbatched()` has always ordered on. A court centre's registers are recorded inside a single
  microsecond often enough that the timestamp alone is no order at all, and two readings of one
  predicate that disagree about a morning are two answers waiting to be compared.

## New repository reads

### `ProcessedRequestRepository`

```java
List<ProcessedRequestSummary> failedSince(Instant since);
List<ProcessedRequestSummary> nonTerminalOlderThan(Instant createdBefore);
long countNonTerminalOlderThan(Instant createdBefore);
Optional<ProcessedRequestSummary> oldestNonTerminal();
```

```sql
-- failedSince
SELECT source, request_id, hearing_id, hearing_day, status, attempts, failure_reason,
       created_at, updated_at,
       extract(epoch from (now() - updated_at))::bigint AS age_seconds
  FROM processed_request
 WHERE status = 'FAILED'
   AND updated_at >= :since
 ORDER BY updated_at

-- nonTerminalOlderThan   (oldest first: the worst problem is read first, on a screen and in a table)
SELECT source, request_id, hearing_id, hearing_day, status, attempts, failure_reason,
       created_at, updated_at,
       extract(epoch from (now() - created_at))::bigint AS age_seconds
  FROM processed_request
 WHERE status IN ('RECEIVED', 'RETRYING')
   AND created_at < :createdBefore
 ORDER BY created_at

-- countNonTerminalOlderThan  (the sweep's second gauge; the SAME predicate, answered as a number)
SELECT count(*)
  FROM processed_request
 WHERE status IN ('RECEIVED', 'RETRYING')
   AND created_at < :createdBefore

-- oldestNonTerminal      (the sweep's first gauge; LIMIT 1 over the same partial index)
SELECT source, request_id, hearing_id, hearing_day, status, attempts, failure_reason,
       created_at, updated_at,
       extract(epoch from (now() - created_at))::bigint AS age_seconds
  FROM processed_request
 WHERE status IN ('RECEIVED', 'RETRYING')
 ORDER BY created_at
 LIMIT 1
```

All four go through `StoreOutage.translating(...)` like every other statement in the class, so an
unreachable store is the intake half's own signal rather than an untranslated driver failure.

**`countNonTerminalOlderThan` is a count and not a `.size()`** (review gate 3). The sweep wants one
number; sizing `nonTerminalOlderThan`'s list to get it is a read whose cost grows with the backlog
it is reporting - slowest on the morning the reading matters most - and carries every unfinished
request's row into the JVM to be counted and dropped, which on this register is a youth's case. Its
predicate is `nonTerminalOlderThan`'s **character for character**, for the reason the migration
gives: Postgres matches a partial index by proving the query's predicate implies the index's.

**The cut-off is exclusive, and both callers share it.** `created_at < :cutOff` means a request
created exactly the intake threshold ago is *not* over it. The gauge and the report's REQUEST_LATE
list read the same boundary, so neither may nudge its own cut-off to soften it - two readings that
disagree about the same request are two answers waiting to be compared.

### `RegisterNotificationRepository`

```java
List<FailedNotification> failedSince(Instant since);
```

```sql
SELECT n.notification_id, n.batch_id, n.status, n.response_code, n.attempts, n.sent_at,
       b.court_centre_id, b.register_date,
       extract(epoch from (now() - n.sent_at))::bigint AS age_seconds
  FROM register_notification n
  JOIN register_batch b ON b.batch_id = n.batch_id
 WHERE n.status = 'FAILED'
   AND n.sent_at >= :since
 ORDER BY n.sent_at
```

The join is what makes this one statement rather than one per row: `RegisterNotification` carries
neither its batch's court centre nor an age, and looking each batch up separately would be N+1 reads
on precisely the morning when the list is longest. **`email_address` is deliberately not selected.**
It is the one personal value in the table, the report never carries it, and a column that is never
read cannot be logged by accident.

### `RegisterBatchRepository`

Four new reads, all answering the `BatchException` projection and all computing the age in SQL:

```java
List<BatchException> latePending(Instant assembledBefore);
List<BatchException> lateGenerating(Instant requestedBefore);
List<BatchException> lateGenerated(Instant generatedBefore);
List<BatchException> failedSince(Instant since);
```

```sql
-- latePending           (age from assembled_at)
SELECT batch_id, court_centre_id, register_date, status, failure_reason, attempts,
       extract(epoch from (now() - assembled_at))::bigint AS age_seconds
  FROM register_batch
 WHERE status = 'PENDING'
   AND assembled_at < :assembledBefore
 ORDER BY assembled_at

-- lateGenerating        (age from requested_at)
SELECT batch_id, court_centre_id, register_date, status, failure_reason, attempts,
       extract(epoch from (now() - requested_at))::bigint AS age_seconds
  FROM register_batch
 WHERE status = 'GENERATING'
   AND requested_at < :requestedBefore
 ORDER BY requested_at

-- lateGenerated         (age from generated_at)
SELECT batch_id, court_centre_id, register_date, status, failure_reason, attempts,
       extract(epoch from (now() - generated_at))::bigint AS age_seconds
  FROM register_batch
 WHERE status = 'GENERATED'
   AND generated_at < :generatedBefore
 ORDER BY generated_at

-- failedSince           (age from failed_at)
SELECT batch_id, court_centre_id, register_date, status, failure_reason, attempts,
       extract(epoch from (now() - failed_at))::bigint AS age_seconds
  FROM register_batch
 WHERE status = 'FAILED'
   AND failed_at >= :since
 ORDER BY failed_at
```

`sdg_reason` is **deliberately not selected** by any of the four, for the reason the note above
gives: it is another system's free text, and a column that is never read cannot reach a line, a
label or the CSV.

These are **new** reads rather than the three 002 already has. `pendingSince`, `generatingSince`
and `generatedSince` return `RegisterBatch` entities, which carry five timestamps and no age; a
report that used them would have to subtract a stored timestamp from a JVM reading, which is the
one comparison V1's header comment forbids. 002's three reads keep their existing callers in the
generation leg and are not touched, renamed or widened.

### `RegisterStore`

```java
List<RecordedRegisterSummary> recordedUnbatchedBefore(Instant recordedBefore);
```

```sql
SELECT output_id, hearing_id, court_centre_id, register_date, register_time,
       extract(epoch from (now() - register_time))::bigint AS age_seconds
  FROM processed_output
 WHERE <the same active, unsuperseded, unbatched, recorded-while-on predicate activeUnbatched() uses>
   AND register_time < :recordedBefore
 ORDER BY register_time
```

The predicate is `activeUnbatched()`'s own, written once in `JdbcRegisterStore` and shared by both
reads, because two spellings of "active and unbatched" are two answers waiting to disagree. What
this read adds is the `register_time` cut-off - the most recent scheduled generation run, from
`LastScheduledRun` - and the SQL-computed age, which is why it is a projection rather than
`activeUnbatched()` filtered in the JVM. `activeUnbatched()` itself is unchanged and keeps the
generation job as its caller.

### Batch reads, reused unchanged from 002

`RegisterBatchRepository.pendingSince(Instant)`, `.generatingSince(Instant)`,
`.generatedSince(Instant)` and `RegisterStore.activeUnbatched()` are used **by the generation leg**
as they are. Nothing is added to them and the report reads none of them.

## New domain types

### `ExceptionKind`

```java
public enum ExceptionKind {
    REQUEST_FAILED, REQUEST_LATE, BATCH_LATE, BATCH_FAILED, NOTIFICATION_FAILED
}
```

Closed, five values, and the `kind` field of every event and the `kind` label of
`courtregister_exceptions_reported_total`. A sixth kind is a spec change, not an addition: the
report's whole claim is that these five are what can be wrong. `BATCH_FAILED` is the one the two
state machines make unavoidable - a batch that reached its own terminal failure is the downstream
half's equivalent of a `FAILED` request, and a report that named the late batches but not the dead
ones would be reporting the symptom and hiding the outcome.

### `ReportWindow`

| Field | Type | Meaning |
|---|---|---|
| `from` | `Instant` | The window's start: the **previous scheduled report time** for a scheduled run, `--since` for a command |
| `to` | `Instant` | The window's end, which is always the moment the run started |

`from` must be **strictly** before `to`; the record refuses otherwise, a window of no width included,
because either reports nothing and looks exactly like a quiet morning.

```java
static ReportWindow forScheduledRun(String cron, String zone, Instant firedAt);
static ReportWindow sinceLastScheduledRun(String cron, String zone, Instant now);
```

**Two factories, because two callers ask two different questions** (review gate 2).

`forScheduledRun` is the scheduled run's only way of making a window. The run **has an occurrence of
its own** - the schedule is what woke it - so the first step back through `LastScheduledRun` is
`atOrBefore(cron, zone, firedAt)`, and the second is `before(...)` that occurrence. A strictly
earlier first step would skip the run's own occurrence whenever it fired on the instant it was due,
opening the window a whole period early and reporting the same failures twice. `to` is `firedAt` and
not the occurrence, so a run held up covers its own period **and** the delay: the window widens and
never shrinks, and nothing falls into a gap between two reports. There is deliberately **no
tolerance** around the occurrence - "near enough to count as on it" is a second boundary to get
wrong, and the at-or-before step answers the only case a tolerance was ever for.

`sinceLastScheduledRun` is what a caller with **no occurrence of its own** asks, which is the bare
`report-exceptions` command (FR-009). One step: nothing woke it on a schedule, so the most recent
occurrence strictly before `now` is the run that last reported. An operator asking at 06:59 reads the
window this morning's run is about to read; one asking at 09:00 reads what has gone wrong since that
run reported rather than repeating it. A scheduled run must never use it - the moment it fires is its
own occurrence or a hair past it, and one step from there is a window a few milliseconds wide and a
morning that looks quiet.

Both reuse `domain/LastScheduledRun` - the most-recent-occurrence computation the never-batched
`BATCH_LATE` rule already needs for the **generation** cron - given the **report** cron and the
report zone instead. That class answers both boundaries: `before(cron, zone, instant)` strictly, and
`atOrBefore(cron, zone, instant)` inclusively, one search with the comparison written once. It lives in `domain/` rather than `batch/` because it is a pure computation over a cron
expression, a zone and an instant: it reaches nothing, holds nothing, and `ReportWindow` - itself a
domain record - is one of its two callers, so a `domain` type would otherwise depend on a `batch`
one. There is no
`courtregister.report.window` setting and there is deliberately no fixed default duration: a
duration and a schedule are two statements of the same fact, and the morning they disagree is the
morning a failure falls into the gap between two windows or is reported twice. A Monday 07:00 run
therefore reads back to Friday 07:00, and a schedule changed to twice a day changes the window with
it, with no second setting to remember.

### `ExceptionEntry`

One thing wrong, of one kind. Every field that does not apply to the kind is `null`, and the log sink
**omits** absent fields rather than emitting nulls.

| Field | Type | `REQUEST_FAILED` | `REQUEST_LATE` | `BATCH_LATE` | `BATCH_FAILED` | `NOTIFICATION_FAILED` |
|---|---|---|---|---|---|---|
| `kind` | `ExceptionKind` | yes | yes | yes | yes | yes |
| `source` | `String` | yes | yes | - | - | - |
| `requestId` | `UUID` | yes | yes | - | - | - |
| `hearingId` | `UUID` | yes | yes | - | - | - |
| `hearingDay` | `LocalDate` | yes | yes | - | - | - |
| `batchId` | `UUID` | - | - | yes (absent for a never-batched register) | yes | yes |
| `notificationId` | `UUID` | - | - | - | - | yes |
| `courtCentreId` | `UUID` | - | - | yes | yes | yes |
| `registerDate` | `LocalDate` | - | - | yes | yes | yes |
| `status` | `String` | `FAILED` | `RECEIVED` / `RETRYING` | `PENDING` / `GENERATING` / `GENERATED` / `RECORDED` | `FAILED` | `FAILED` |
| `attempts` | `Integer` | yes | yes | - | - | yes |
| `reason` | `String` | the row's bounded `failure_reason` | - | the bounded stage that is overdue | the row's bounded `BatchFailureReason`, **never its `sdg_reason`** | the bounded response code |
| `ageSeconds` | `long` | yes | yes | yes | yes | yes |

Thirteen components **including `kind`**, one shape for five kinds. Every value in the table is an identifier, a bounded
code, a count or a timing. There is no defendant name, address, date of birth, ASN or URN anywhere,
and **no recipient address at all** - a failed send is identified by its notification id and its
batch, which is what the spec's last edge case asks for. Nor is there any free text another system
wrote: `BATCH_FAILED`'s `reason` is this service's own bounded `BatchFailureReason` and never
systemdocgenerator's `sdg_reason` (Principle VII).

`status` is a `String` rather than a union of three enums because the five kinds read three different
state machines; the values are bounded by those machines' own `CHECK` constraints, and the service
never composes one.

### `ExceptionReport`

| Field | Type | Meaning |
|---|---|---|
| `runId` | `String` | The correlation the caller opened and passed in, the same value `RunCorrelation` put in the MDC |
| `window` | `ReportWindow` | What was asked for |
| `snapshotAt` | `Instant` | When the reads were taken, which is not the same as when the events were written |
| `entries` | `List<ExceptionEntry>` | Oldest first, across all five kinds |

Plus `Map<ExceptionKind, Integer> counts()`, which answers **zero for every kind that has none**
rather than omitting it, so the summary event always carries five numbers and an empty morning is
distinguishable from a morning the report did not run (FR-012, acceptance scenario 1.5).

The `runId` is an argument rather than something the report reads for itself:
`ExceptionReportService.build(ReportWindow window, String runId)` takes it, and whoever opened the
correlation - the job or the command - passes `RunCorrelation.current()` in. That is what keeps
`application/` free of the MDC and satisfies FR-011 on both paths rather than only on the scheduled
one.

### `DeliveryOutcome`, `ReportSinkName`, `DeliveryStatus`, `ReportDeliveryReason`

```java
public record DeliveryOutcome(ReportSinkName sink, DeliveryStatus status,
                              ReportDeliveryReason reason, int accepted, int refused) { }

public enum ReportSinkName { LOG, EMAIL }

public enum DeliveryStatus { DELIVERED, PARTIALLY_DELIVERED, NOT_DELIVERED }
```

`accepted` and `refused` are per-recipient counts for `EMAIL` (three recipients, one refused, is
`PARTIALLY_DELIVERED` with 2 and 1) and are 1 and 0 for a delivered `LOG`.

`ReportDeliveryReason` is bounded and is the only thing a failure contributes to a label or a line:

| Value | When |
|---|---|
| `NONE` | Delivered. The reason field of a success is not absent, it is `NONE` |
| `ATTACHMENT_UNWRITABLE` | The CSV could not be rendered at all |
| `ATTACHMENT_STORE_UNAVAILABLE` | The file-service write did not durably store the CSV, for any reason |
| `SEND_REFUSED` | notificationnotify answered 4xx, or a 2xx that is not 202. Never retried |
| `SEND_FAILED` | notificationnotify answered 5xx after the shared attempt budget |
| `SEND_UNANSWERED` | The send was never answered: connect failure, read timeout, dropped connection |
| `NO_RECIPIENTS` | The e-mail output is on and the resolved recipient list is empty at run time. Startup refuses this configuration, so it can only mean the list was emptied under a running pod |
| `LOG_WRITE_FAILED` | The log sink's own write threw, which is a broken appender rather than a broken report |

Never a raw exception message, never a status line quoted back, never a fragment of a downstream's
body (constitution Principle VII).

### `ReportRunOutcome` and `SweepFailureReason` - the two labels review gate 3 made unspellable

```java
public enum ReportRunOutcome { DELIVERED, PARTIAL, FAILED }

public enum SweepFailureReason { STORE_UNAVAILABLE, UNEXPECTED }
```

Both render through `ProcessingMetrics`'s `code(Enum)` helper - `delivered`/`partial`/`failed` and
`store-unavailable`/`unexpected` - so the published labels are exactly the words they always were.
What changed is who may say them: `ProcessingMetrics.exceptionReportRun` and `intakeSweepFailure`
take these types and no longer take a `String`, because a label a caller spells is a label a caller
can mistype, and a mistyped label is not a wrong reading but a new series on which the alert written
against the right one is silent for ever.

`ReportRunOutcome`'s three are the same three the run line carries: a dashboard filtered on the
counter and a query over the run lines must partition a morning the same way. `SweepFailureReason`
has two and must keep two - it is the only evidence the service's one absorbed refusal leaves, and
an outage of theirs and a bug of ours need telling apart, because one counter for both would make
the second invisible inside the first.

### How the two borrowed and defaulted durations resolve

One mechanism each, and never two for one value:

| Setting | Default | How it resolves |
|---|---|---|
| `courtregister.report.batch-generated-within` | none; binds `null` | `PropertiesValidator` resolves the null to `GenerationProperties.gracePeriod()` and publishes the resolved value for the report to read. **No `application.yaml` placeholder**, because nothing reads this key through the placeholder resolver - it is read from a record, and writing it twice would let the two copies disagree |
| `courtregister.intake.gauge-refresh` | `10m`, its own `@DefaultValue` | Read as a placeholder by `@Scheduled(fixedDelayString = "${courtregister.intake.gauge-refresh}")` and bound on the intake record. It borrows nothing from the generation half: the sweep runs where that half is switched off, so a value resolved from `courtregister.generation.grace-period` would be a value an intake-only pod could not read |

The rule behind both rows: a duration is either a record value resolved once in the validator, or a
placeholder with a literal default - never both. The earlier plan gave `gauge-refresh` a
`@DefaultValue`-less binding *and* a yaml placeholder onto the grace period, which is two mechanisms
for one number and a test that could pass while the shipped pod read the other one.

### The four projections

```java
public record ProcessedRequestSummary(String source, UUID requestId, UUID hearingId,
        LocalDate hearingDay, RequestStatus status, int attempts, String failureReason,
        Instant createdAt, Instant updatedAt, long ageSeconds) { }

public record FailedNotification(UUID notificationId, UUID batchId, UUID courtCentreId,
        LocalDate registerDate, NotificationStatus status, Integer responseCode, int attempts,
        Instant sentAt, long ageSeconds) { }

public record BatchException(UUID batchId, UUID courtCentreId, LocalDate registerDate,
        BatchStatus status, BatchFailureReason failureReason, int attempts, long ageSeconds) { }

public record RecordedRegisterSummary(UUID outputId, UUID hearingId, UUID courtCentreId,
        LocalDate registerDate, Instant registerTime, long ageSeconds) { }
```

Projections, not entities: each is exactly the columns one statement selects, and none is ever
written back. `BatchException` carries `ageSeconds` for the same reason the other three do - the
statement is what computes it - and carries **no** `sdgReason` field at all, so there is no place
for another system's free text to be put. `courtCentreId` is a `UUID`, as it is on `RegisterBatch`
and on `CourtCentreDay`. `FailedNotification` carries no address **because no read this feature makes selects
`register_notification.email_address` at all**: the column is never in a select list, so there is no
value to mask, no value to forget to mask, and nothing for a debugger to print. Masking survives in
exactly one place - the e-mail sink's own lines about the recipients it is sending to, where an
address genuinely flows through the code.

### `ReportMail` and `MailOutcome`

The e-mail sink does not hold an HTTP client and does not reuse `RegisterNotifier`, whose
`send(RegisterNotification, UUID, ...)` is shaped around a batch's notification row and whose body
carries `personalisation.yotsName` and nothing else. It holds a second, narrower port:

```java
public interface ReportMailer {

    /**
     * Sends one report e-mail, answering how it went rather than throwing.
     */
    MailOutcome send(ReportMail mail);
}

public record ReportMail(UUID notificationId, UUID templateId, String sendToAddress,
                         UUID fileId, Map<String, String> personalisation) { }

public record MailOutcome(MailStatus status, Integer responseCode) { }

public enum MailStatus { ACCEPTED, REFUSED, FAILED, UNANSWERED }
```

`personalisation` is a `Map<String, String>` rather than a fixed record because the vendored
schema permits it: in
`specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json`
the `personalisation` property is `"type": "object"` with an empty `properties` block and
**`"additionalProperties": true`**, inside a body that is otherwise `"additionalProperties": false`
and requires `templateId` and `sendToAddress`. So the counts and the window travel in the
personalisation map as strings and the template renders them (FR-006, acceptance scenario 4.4:
"nothing was wrong" is four zeroes and a window, substituted into fixed template text). The values
are strings because Notify substitutes text.

Every value in the map is a count, a window boundary or a bounded code. No identifier and no
address is ever a personalisation key or value; the exception list travels as the CSV attachment,
referenced by `fileId`.

The adapter is `adapter/notificationnotify/NotificationNotifyReportMailer`, beside
`NotificationNotifyClient` and sharing its HTTP shape - the same `COMMAND_PATH`, the same
`EMAIL_MEDIA_TYPE`, the same `CJSCPPUID` identity header, the same 202-and-nothing-else rule and
the same transient/refused classification - through a **package-private request builder** the two
adapters both call. The builder is what makes "the same shape" a fact rather than a claim, and it
is why the register path's own `NotificationNotifyClientTest` cases stay green unchanged: nothing
about `RegisterNotifier`, `RegisterNotification` or the `yotsName` body moves.

## The CSV attachment

One header row and one row per entry, in the entries' own order, UTF-8, `\n` line endings, RFC 4180
quoting. The columns are the union of the entry table above - thirteen, one per component, `kind`
included - with an empty field where the kind does not carry it:

```
kind,source,request_id,hearing_id,hearing_day,batch_id,notification_id,court_centre_id,register_date,status,attempts,reason,age_seconds
```

A `BATCH_FAILED` row fills `kind`, `batch_id`, `court_centre_id`, `register_date`, `status`, `reason`
(its bounded `BatchFailureReason`) and `age_seconds`, and leaves the rest empty. The fifth kind
therefore adds **no column**: it is a row shape the thirteen already carry, and the one value it
could have wanted a column for - `sdg_reason` - is the free text Principle VII bars from the CSV
exactly as it bars it from an event. No address appears in any column, because no read selects one.

The metadata row written beside it is the same five keys `PayloadMetadata` already carries, spelled
the way the framework spells them:

| Key | Value |
|---|---|
| `fileName` | `court-register-exceptions_{yyyy-MM-dd}.csv`, the date being the run's own day in `Europe/London` |
| `conversionFormat` | `csv` |
| `templateName` | `courtregister-exception-report` |
| `numberOfPages` | `1` |
| `fileSize` | the CSV's byte count |

The two inserts are `FileServicePayloadStore`'s own, character for character, in the same order:
**content first**, because `metadata.file_id` is a foreign key onto `content.file_id`. The content
column is `bytea` and takes the CSV's UTF-8 bytes. **`FileServicePayloadStoreIT` owns the
two-statement pin** - it is the suite that owns `FileServicePayloadStore`, and `storeText` is that
class's method - and asserts that exactly those two statements are issued, in that order, and no
third. `EmailReportSinkStoreIT` owns the CSV's own composition and asserts nothing about statement
counts, so neither suite restates the other's claim.

## State predicates - what the report asserts

The report has no state machine of its own; it is a read. What it does have is a set of predicates
that must hold, and each is a named test:

| Predicate | Holds because |
|---|---|
| Every `FAILED` request whose `updated_at` is inside the window appears exactly once | the read is a single statement over a primary-key-unique table; `SC-001` pins it over fifty mixed seeded rows |
| No request appears under two kinds in one run | the two intake predicates partition on `status` (FR-013) |
| A report with no entries still produces a summary with five zeroes | `counts()` answers zero for an absent kind (FR-012) |
| The report writes nothing | every repository the service holds is a read interface in the test, verified with `verifyNoMoreInteractions` over the write methods (FR-014) |
| The two gauges exist and read zero from start-up | both are registered in `ProcessingMetrics`' constructor (acceptance scenario 2.1) |
| A run that delivered to one sink and not the other is `PARTIALLY_DELIVERED` | the service folds the two `DeliveryOutcome`s rather than short-circuiting on the first (FR-007) |
| The scheduled run holds a lock nothing else holds | `@SchedulerLock(name = "exception-report")`, which is neither `"register-generation"` nor `"register-reconciliation"` (SC-008) |
| Every scheduled method runs on the scheduler its own configuration declares | each of the four carries `@Scheduled(scheduler = ...)` naming its bean - three `TaskScheduler` beans on a context do not route anything by themselves, and without the attribute Spring picks one and the three-scheduler claim is a comment rather than a behaviour (SC-008) |
| A batch or register age is never derived in the JVM | the four `RegisterBatchRepository` reads and `RegisterStore.recordedUnbatchedBefore` answer projections carrying `age_seconds` from `extract(epoch from (now() - <column>))`, so no stored timestamp is subtracted from a JVM reading (V1's single-time-authority rule) |
| The run's own delivery facts are written once, by whoever held them | the summary event carries the report's ten fields and no delivery status; the `exception_report_run` line carries `delivered_log`, `delivered_email` and `outcome`, and the job writes it only after every sink has returned |
| The sweep holds no lock at all | `IntakeAgeSweep.sweepScheduled()` carries `@Scheduled` and **no** `@SchedulerLock`: a gauge describes the JVM that publishes it, so every pod must refresh its own or a two-pod deployment publishes one pod's view under two names. An alert aggregates with `max()` across pods |
| No free text from another system reaches an event, a row or a label | `sdg_reason` is in no select list this feature writes (Principle VII) |

## `--since` parse rules

`ReportExceptionsCli` resolves the window's `from` from the `--since` value, in this order. The first
form that parses wins; nothing falls through to a default once a value was given.

| Form | Example | `from` |
|---|---|---|
| ISO-8601 instant | `2026-09-14T06:00:00Z` | that instant |
| ISO-8601 duration | `PT2H`, `PT30M`, `P1D` | `now` minus it |
| Shorthand days | `2d` | `now` minus two days |
| Shorthand hours | `2h` | `now` minus two hours |
| Shorthand minutes | `30m` | `now` minus thirty minutes |
| Shorthand seconds | `90s` | `now` minus ninety seconds |
| absent | | the most recent scheduled report occurrence before now (`ReportWindow.sinceLastScheduledRun`) |

`to` is always `now`, read from the injected `Clock`. An absent `--since` opens the window at the most
recent scheduled occurrence **before now** - `sinceLastScheduledRun`, not `forScheduledRun`, because
the command has no occurrence of its own to be at. Before this morning's run that is yesterday's run,
which is the window this morning's run is about to read; after it, it is this morning's run, so the
bare command answers what has gone wrong since the report was written rather than repeating it. A zero or negative duration, a shorthand with
no digits, an instant in the future and anything else are refused as `unreadable-argument` naming
`--since`, with the class of the reader that refused it on the log line and **the token never
quoted** - an operator's terminal is pasted into tickets, and `--since` is as likely to receive a
half-remembered runbook step as an instant (the argument `TelemetryPrivacyTest`'s operator-token
group already makes about the other five commands).

## Log event field shapes

### `courtregister_exception_report` - once per run

| Field | Type | Notes |
|---|---|---|
| `event` | string | `courtregister_exception_report` |
| `run_id` | string | the run's correlation, the same value on every line of the run |
| `window_from`, `window_to`, `snapshot_at` | ISO-8601 instants | |
| `request_failed`, `request_late`, `batch_late`, `batch_failed`, `notification_failed` | integers | always present, zero included |

**Ten fields**: `event`, `run_id`, the three instants and the five counts. That number is stated
once, here, and the log sink's test counts against it.

**The summary event carries no delivery status, deliberately.** It is written *by* a sink, while the
other sink may not have been asked yet and this one cannot know how it went itself - a sink that
announced "delivered_email=DELIVERED" would be reporting an outcome nobody had observed. The summary
event states only what the **report** says; how the report *travelled* is the run's own fact and
belongs to whoever held every outcome, which is the job (or the command), after every sink returned.

### `exception_report_run` - the run's own line, written by the job

`ExceptionReportJob` writes one flat `key=value` line **after every sink has returned**, exactly as
`RegisterGenerationJob.recorded` writes `event=register_generation_run` after its night:

```
event=exception_report_run run_id=<id> window_from=<instant> window_to=<instant> entries=<n>
  delivered_log=ok|failed delivered_email=ok|failed|skipped|disabled
  outcome=delivered|partial|failed duration_ms=<n>
```

| Field | Values |
|---|---|
| `event` | `exception_report_run` |
| `run_id` | the run's correlation |
| `window_from`, `window_to` | the window that was read |
| `entries` | how many exceptions the report holds, across all five kinds |
| `delivered_log` | `ok` or `failed` |
| `delivered_email` | `ok`, `failed`, `skipped` (the command was run without `--email`) or `disabled` (`courtregister.report.email.enabled` is false, so there is no sink on the context) |
| `outcome` | `delivered` (every sink asked said ok), `partial` (at least one sink asked failed and at least one said ok), or `failed` (the run could not build the report, or no sink asked said ok) |
| `duration_ms` | how long the run took, from opening the correlation to writing this line |

It is a flat line rather than a structured event for the reason the run report already is one: it is
one line per run, read by eye and by a single-field filter, not a row a saved query aggregates.
`ReportExceptionsCli` prints the **equivalent** as its last line, so an on-demand run says the same
four things about its own delivery that the 07:00 run does.

### `courtregister_exception` - once per exception

`event`, `run_id`, `kind`, then the applicable subset of `source`, `request_id`, `hearing_id`,
`hearing_day`, `batch_id`, `notification_id`, `court_centre_id`, `register_date`, `status`,
`attempts`, `reason`, `age_seconds`, per the entry table above - thirteen components **including
`kind`**, of which each kind carries only its own. Fields that do not apply to the kind
are **absent**, so a KQL `isnotempty()` distinguishes "does not apply" from "was not known".

Both are emitted as structured arguments, through `StructuredArguments.value(...)`, and reach
the encoder as top-level JSON
fields because both `logback.xml` and `logback-cli.xml` declare an `<arguments/>` provider. Without
that provider the values are still rendered into the message text and every query would need
`parse()`, which is what `TelemetryPrivacyTest.ShippedConfiguration` is extended to prevent.
