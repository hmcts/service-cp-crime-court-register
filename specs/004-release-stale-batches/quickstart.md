# Quickstart: Release stale in-flight batches before batching

The walkthrough that proves the increment: a batch whose render outcome never arrives is failed and
released by the next run, its registers are batched again that same run, the court centre gets its
document, and the original batch's late `document-available` moves nothing.

Everything here runs against the local stack that `specs/002-consolidate-progression-leg/quickstart.md`
sets up. Only the differences are written out.

## Local dependencies

```bash
docker compose up -d postgres servicebus-emulator artemis fileservice-postgres wiremock
```

**Note the omission**: `sdg-echo` is *not* started. It is the helper that publishes
`document-available` back onto `public.event` after each `generate-document`, and for this
walkthrough the whole point is that no outcome ever arrives. Start it only for the last step, where
the late outcome is delivered on purpose.

WireMock's mappings need one change and one deletion:

- the `generate-document` mapping still answers **202**, as it always did;
- the **`GET document/{id}` mapping is deleted**. Nothing asks for it any more, and leaving it there
  would let a regression pass unnoticed. `docker/wiremock/README.md` loses its line about it.

## Run the service with generation enabled

The 002 command line, with two changes:

```diff
- COURTREGISTER_GENERATION_GRACE_PERIOD=10m
+ COURTREGISTER_GENERATION_STALE_AFTER=30m
```

and no `COURTREGISTER_GENERATION_COMPLETION` of any kind: the setting is gone, and a run with the
generation half enabled subscribes to `public.event` unconditionally.

## 1. Make a batch that will never hear anything

Record a register and run the generation once, with `sdg-echo` stopped:

```bash
# record one hearing's register (as 002's quickstart does)
./scripts/put-message.sh docker/samples/distribution-command.json

# then ask for a generation by hand
java -jar build/libs/*.jar generate-register --date 2026-09-21 --ignore-flag
```

The batch reaches `GENERATING` — WireMock answered 202 — and stops there, because nothing is going
to publish its outcome:

```sql
SELECT batch_id, status, requested_at, failure_reason FROM register_batch ORDER BY assembled_at DESC LIMIT 1;
-- GENERATING, requested_at = now, failure_reason NULL
```

Its registers are stamped:

```sql
SELECT count(*) FROM register_record WHERE batch_id = '<batch id>';
-- 2
```

## 2. Confirm nothing chases it

Wait fifteen minutes — longer than the ten-minute cadence the retired timer ran at — and read
WireMock's request journal:

```bash
curl -s localhost:8089/__admin/requests | jq '[.requests[].request.url] | unique'
```

The only URLs are the `generate-document` command and the flag read. **There is no
`document/{id}`**, on any schedule, ever. That is SC-004, and it is the whole of the removal seen
from outside.

The three readings are still moving, though, which is FR-011:

```bash
curl -s localhost:8082/actuator/metrics/courtregister_oldest_generating_age | jq '.measurements'
# a value in seconds, climbing, refreshed every courtregister.generation.batch-age-refresh (10m)
```

## 3. Age the batch past the minimum

Rather than waiting thirty minutes, move the stamp back:

```sql
UPDATE register_batch SET requested_at = requested_at - interval '31 minutes'
 WHERE batch_id = '<batch id>';
```

## 4. Run the night

```bash
java -jar build/libs/*.jar generate-register --date 2026-09-21 --ignore-flag
```

is **not** what to run here — the on-demand command does not run the release pass, by design
(spec Assumptions). Trigger the scheduled run instead, either by waiting for 18:00 London or by
starting the service with the cron brought forward:

```bash
COURTREGISTER_GENERATION_CRON='0 */2 * * * *' ...   # every two minutes, local only
```

What the run line says:

```text
event=register_generation_run run_id=... gate=proceed reason=overridden batches=1 requested=1
generating=1 failed=0 pending=0 deferred=0 rows=2 rows_generating=2 ... released=1 duration_ms=...
```

`released=1` — where the line used to carry `reconciled=`. And in the store:

```sql
SELECT batch_id, status, failure_reason, completed_by FROM register_batch ORDER BY assembled_at;
-- <old batch>  FAILED      NOT_COMPLETED_BY_NEXT_RUN   NULL
-- <new batch>  GENERATING  NULL                        NULL

SELECT batch_id, count(*) FROM register_record GROUP BY batch_id;
-- the two registers are on the NEW batch
```

The old batch keeps its row, carrying what happened to it; its registers moved. The court centre is
getting its document tonight.

## 5. Deliver the late outcome, and watch nothing happen

Now start `sdg-echo`, or publish by hand, a `document-available` naming the **old** batch's
correlation and payload id:

```bash
docker compose up -d sdg-echo
./scripts/publish-document-available.sh '<old batch payload file id>' '<old batch id>'
```

The listener acknowledges it and drops it:

```bash
curl -s localhost:8082/actuator/metrics/courtregister_public_events_ignored_total \
  | jq '.availableTags[] | select(.tag=="reason") | .values'
# includes "late-acceptance-ignored"
```

and the store is unchanged:

```sql
SELECT status, failure_reason FROM register_batch WHERE batch_id = '<old batch id>';
-- still FAILED, still NOT_COMPLETED_BY_NEXT_RUN
SELECT count(*) FROM register_notification WHERE batch_id = '<old batch id>';
-- 0
```

That is SC-003: the Youth Offending Team is told once, by the batch that actually rendered.

## 6. The other side of the boundary

Repeat from step 1 but age the batch by **ten** minutes instead of thirty, and run the night. The
batch is untouched, its registers are still stamped, and the run line carries `released=0` and
`deferred=1` — the assembler passed its court centre day over, exactly as it does today (US2.1,
US2.2).

## Startup refusals to try

```bash
COURTREGISTER_GENERATION_STALE_AFTER=0s ./gradlew bootRun
# refuses, naming courtregister.generation.stale-after

COURTREGISTER_GENERATION_BATCH_AGE_REFRESH=-1m ./gradlew bootRun
# refuses, naming courtregister.generation.batch-age-refresh

COURTREGISTER_GENERATION_COMPLETION=poll-only ./gradlew bootRun
# starts, and the variable does nothing: the key is gone. A deployment that still sets it is
# setting nothing, which is why the STE values are on the outside-this-repo list.
```

## Reading it in a deployed environment

```kql
// batches a night gave up on, per run
ContainerLogV2
| where LogMessage has "event=register_generation_run"
| extend released = extract(@"released=(\d+)", 1, LogMessage, typeof(int))
| summarize sum(released) by bin(TimeGenerated, 1d)
```

A night with a non-zero `released` is a night something did not hear from systemdocgenerator. A run
of them is a broker or a renderer to investigate — which is what the retired `reconciled` counter
used to say, said by the mechanism that replaced it.
