# Contracts — service-cp-crime-court-register

> **Status: as built.** Both contracts are implemented and enforced in the tree: the inbound
> schema is committed and held to the parser case for case; the outbound documents are validated
> against the vendored progression schemas before every POST.

## There is no REST API

This service is a message-driven pipeline: it consumes from the Azure Service Bus queue
`courtregister.requests` and submits `progression.add-court-register`. The only HTTP surface is
Spring Boot Actuator, which is operational, not a consumer contract. The two real contracts are
below; `doc/openapi.yaml` deliberately stays empty.

## 1. Inbound contract — the queue message

One JSON body per hearing-resulted distribution command:

```json
{ "source": "RESULTS", "requestId": "<uuid>", "hearingId": "<uuid>",
  "hearingDay": "2026-08-27", "sharedTime": "2026-08-27T14:31:02.115Z",
  "eventType": "Hearing_Resulted", "userId": "<uuid>" }
```

`additionalProperties: false`; required `source, requestId, hearingId, hearingDay, sharedTime,
eventType`; `userId` optional (absent, never null). `source` enum `["RESULTS"]`, `eventType` enum
`["Hearing_Resulted"]` — the court register has no SJP leg. The canonical schema lives at
`src/main/resources/contracts/distribution-command.schema.json` (draft-07; court-register `$id`),
jointly owned with the producer in `cpp-context-results`.

### Field semantics

- `hearingId` + `hearingDay` → the Redis claim-check key `INT_{hearingId}_{hearingDay}_result_`
  (dated form first, then the legacy undated twin).
- `sharedTime` → the register date and the `requestId` recipe; a business re-share carries a new
  `sharedTime` and therefore a new `requestId`.
- `userId` → the `CJSCPPUID` identity threaded to reference data and the progression POST.

**The two date fields state their own grammar.** `sharedTime` and `hearingDay` each carry a
`pattern` in the schema as well as a `format`, because draft-07's `date`/`date-time` formats are only
as wide as whichever validator happens to read them, and this contract is narrower than the widest
of them:

| Form | Accepted | Why |
| --- | --- | --- |
| `2026-08-27T14:31:02.115Z`, `…+01:00`, `…+00:00` | yes | the contract's shape; lower-case `t` and `z` are permitted, RFC 3339 allows them |
| `2026-08-27 14:31:02Z` (space separator) | no | RFC 3339's ABNF is `date-time = full-date "T" full-time`; the space form is only a readability note (§5.6) that *permits* an application to accept it |
| `2016-12-31T23:59:60Z` (leap second) | no | grammatical in RFC 3339, but there is no `Instant` for a 61st second — accepting one means inventing a value to store, and deciding which `:60` instants were real needs a leap-second table on the hot path |
| `2026-08-27T14:31:02-00:00` | no | RFC 3339 gives `-00:00` the distinct meaning "offset unknown"; this field is a true instant |
| `+12026-08-27`, `-0001-08-27` (expanded/signed year) | no | `java.time` accepts them, RFC 3339 does not |
| `2026-02-30`, `2025-02-29` | no | refused by `format`, not by the pattern — the pattern is lexical, the format is semantic |

The schema and `DistributionCommandParser` are held to each other case for case by
`DistributionCommandSchemaCorpusTest`, with **no exemption list**: a form one accepts and the other
refuses fails the build. Where they once diverged, the schema was narrowed rather than the parser
widened (2026-08-31 — see `doc/CHANGELOG.md`); the schema is the authority, and a contract document
that does not describe what the service accepts is the defect.

### Message properties (broker-level, part of the contract)

`messageId = "RESULTS:{requestId}"` (duplicate-detection key); `contentType = application/json`;
`correlationId = hearingId`. `requestId =
UUID.nameUUIDFromBytes(hearingId + "|" + hearingDay + "|" + sharedTime)`.

### Delivery and settlement semantics

Peek-lock with explicit `complete`/`abandon`/`deadLetter`; `maxDeliveryCount` 5 with dead-lettering
on expiry; duplicate detection on (set at queue creation — immutable afterwards).

### Replay rule

A dead-lettered body is re-sent **verbatim**, changing only the broker `messageId` — a resubmit
that clones the original `messageId` inside the duplicate-detection window is silently swallowed by
the broker. The `requestId` in the body is the idempotency key and stays unchanged.

### Failure behaviour (contractual, tested)

Contract-invalid body ⇒ dead-letter with a bounded reason, no processed-log row. Store unavailable
⇒ abandon + intake suspension. Redelivery of a request already in a terminal state ⇒ **settled from
the recorded status, without reprocessing**: a `COMPLETED` request is acknowledged (delivery
completed, no run, row untouched); a `FAILED` one is re-parked under the identity that exhausted it,
and replayed under any other identity. Nothing is published anywhere in either case — this service
has no status channel and no outbound contract other than `add-court-register` (§2), so "the
terminal status is republished" would name a channel that does not exist.

| Delivery condition | Settlement | Recorded state |
|---|---|---|
| Contract-invalid body (schema/parser refusal) | dead-letter, bounded reason | no processed-log row |
| Store unavailable | abandon; intake suspends until the probe recovers | none (nothing consumed against a dead store) |
| Duplicate of a `COMPLETED` request | complete (no run) | row untouched |
| Duplicate of a `FAILED` request, same `messageId` identity | dead-letter (re-parked) | row untouched |
| Replay of a `FAILED` request, fresh `messageId` | processed afresh | `FAILED → RECEIVED` and onward |
| Same `requestId`, different immutable content (fingerprint mismatch) | dead-letter | record untouched (C17) |
| Transient failure (payload/refdata unanswered, submission transient, deadline overrun) | abandon → broker redelivery | `RETRYING` + bounded reason |
| Transient failure on the final permitted delivery | dead-letter | `FAILED DELIVERY_LIMIT_EXHAUSTED` + `exhausted_message_id` |
| Non-transient failure (contract violation, refused read, rejected submission, fatal transformation) | dead-letter on first delivery | `FAILED` + bounded reason |
| Legitimate no-op (`group-proceedings`, `no-defendants`, `no-subscriptions`, `no-youth-defendants`) | complete | `COMPLETED` + that reason (C33) |

## 2. Outbound contract — `progression.add-court-register`

`POST {progression}/progression-command-api/command/api/rest/progression/court-register`,
media type `application/vnd.progression.add-court-register+json`, success **202 and nothing else**,
`CJSCPPUID` header carrying the system user.

The document schema is progression-owned: top level in
`cpp-context-progression/progression-command/progression-command-api/src/raml/json/schema/progression.add-court-register.json`,
nested components compiled from `criminal-court-public-model` **17.103.13**. The exact compiled
schemas are **vendored** at `src/main/resources/contracts/progression/` (`additionalProperties:
false` throughout); every outbound document is validated against them **before** sending
(`adapter/progression/OutboundContractValidator`, wired unconditionally) — a schema-invalid
document is an explicit `FAILED`, never a swallowed 400 (defect-fix C29).

The recorded failure is the bounded reason `OUTBOUND_CONTRACT_VIOLATION`, in
`processed_request.failure_reason` and in the dead-letter description; the **JSON pointer of the
offending field is a log-only diagnostic**, written once at `WARN` by
`pipeline/RegisterTransformationChain` alongside `source`/`requestId`/`hearingId` as
`violation=… path=…`. It is not persisted: this refusal happens in the transformation, before any
submission, so there is no `processed_output` row to carry it — the row is claimed by the POST that
never happens — and `failure_reason` is a bounded code the metrics, the dead-letter description and
the privacy suite all read as one. The pointer is safe to write because it is a **path and never a
value**: the validator builds it from the instance location plus, for a `required` failure, the
missing property's name, and the document is serialised from this repo's own records, so every
property name in it is one this repo wrote (asserted by
`TelemetryPrivacyTest.RefusedByTheContract`, over a hearing whose child is made entirely of
markers).

Content notes that differ deliberately from the legacy app are in
[DEFECT-FIXES.md](DEFECT-FIXES.md) (C11 filename, C23 verdict code, C24 wording newline, and the
other content-changing fixes gated on sign-off).

## Other outbound calls (not contracts this service owns)

- Redis claim-check read (`INT_` prefix) and the results-query fallback
  `GET /results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}`
  (`application/vnd.results.hearing-details-internal+json`).
- Reference data: `GET /referencedata-query-api/query/api/rest/referencedata/now-subscriptions?on={day}`.

## Actuator (operational surface only)

`/actuator/health/readiness` (groups `db`, `intakeStartup` — the Service Bus indicator is
deliberately in neither readiness nor liveness), `/actuator/health/liveness`, `/actuator/metrics`,
`/actuator/prometheus`.
