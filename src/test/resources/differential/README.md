# Differential corpus — the legacy oracle, recorded

`recorded/` is 351 recordings of what the **legacy** Node function app
(`cpp-context-azure-legalaidagency/azure-functions/durable-functions`, HEAD `0d63f3ae`) does with a
hearing payload. It is the left-hand side of the differential audit (T073–T075).

This service is deliberately **not** a bug-for-bug port, so this is not a golden-equality suite. The
audit's rule is constitution Principle I, restated in `doc/DEFECT-FIXES.md`:

> the port encodes FIXED behaviour, the legacy is the oracle for everything uncatalogued — every
> legacy-vs-port difference must derive from a `doc/DEFECT-FIXES.md` row (C1–C36); an unattributed
> difference is a port defect.

The corpus says what the legacy does, case by case, with provenance, so the audit can subtract the
C-rows and require the remainder to be empty.

## Where the oracle lives

The harness that produced this is **not in this repository**. It is an analysis-side pack at

```
/home/sachin/moj/analysis/results-distribution/CourtRegister/differential-pack/
```

with `README.md` there as the authority on the operators, the two contract axes, the stub register,
the coverage table and the corpus's declared blind spots. Read it before writing an assertion
against a case here. `recorded/provenance.json` carries everything needed to identify the build:
the Node commit, the package-lock digest, the vendored contract-bundle digest, the oracle digest,
the clock pin and the timezone pin.

## Layout

```
recorded/
├── index.json         one row per case — grep this first
├── provenance.json    what produced the corpus
├── subscriptions/     the three shared reference-data bodies, written once
└── <case-id>/
    ├── inputs/hearing.json
    ├── inputs/params.json            { sharedTime, cjscppuid, clockPinIso, subscriptions…, refdata? }
    ├── inputs/subscriptions.json     only when the case changed the reference-data body (37 cases)
    ├── expected.json                 the outbound document, or null
    ├── expected-alternate-clock.json (2 clock-dependent cases)
    ├── expected-second-delivery.json (6 re-share cases)
    └── meta.json                     provenance + what the oracle observed + both contract axes
```

`expected.json` is a single object, not an array: `OutboundCourtRegister` returns one
`CourtRegisterAggregationRequest` or `null`.

## Partition on `contractStatus` before asserting

Every row carries two independent classifications. The first decides **how** a case may be asserted.

| `contractStatus` | Cases | What the port owes |
|---|---|---|
| `IN_CONTRACT` | 123 | Progression accepts the legacy's document. Golden equality — any difference must derive from a C-row. |
| `SCHEMA_INVALID` | 66 | Progression answers 400 and the legacy swallows it (C1). The port must **not** reproduce the document: assert a classified, persisted, dead-lettered contract failure naming `meta.contract.violations`. |
| `NO_DOCUMENT` | 162 | The legacy produced nothing. Assert `meta.observed.noDocumentReason`, not silence. |

The second, `inputContractStatus`, says whether the producer could have sent the payload at all
(`IN_CONTRACT` 220 / `SCHEMA_INVALID` 99 / `PRODUCER_IMPLAUSIBLE` 32). A case is a full parity
obligation only when **both** axes say `IN_CONTRACT` — 70 of the 351.

## Reading a case's reference-data body

Cases carry exactly one of:

* `params.subscriptionsFixture` — a path under `recorded/`, e.g.
  `subscriptions/now-subscriptions-cr-sample.json` (302 cases);
* `params.subscriptionsInline: true` — read the case's own `inputs/subscriptions.json` (37 cases);
* `params.subscriptionsAbsent: true` — the case made the reference-data call fail; stub the payload
  source to fail the way `params.refdata` declares (12 cases).

`meta.digests.subscriptions` pins the bytes either way.

## Cases needing special handling

| Flag | Cases | What the test must do |
|---|---|---|
| `clockDependent: true` | 2 | pin the clock to `meta.oracle.clockPinIso`, or exclude `registerDate` and `fileName` |
| `outcome == "swallowed-exception"` | 11 | the legacy lost the register silently. Assert no document **and** a classified failure; `meta.observed.swallowedErrors` quotes what it discarded |
| `outcome == "skipped-group-proceedings"` | 9 | C7 — the business skip is kept but recorded. Assert the recorded skip |
| `reshare` present | 6 | two deliveries; `expected-second-delivery.json` is the second |

## Do not edit these files

They are recordings. A recording edited to agree with the port is not an oracle. Rebuild them from
the pack instead — `node oracle/build-corpus.js` — and a rebuild that changes an `expected.json`
without changing a field in `provenance.json` is a genuine non-determinism to investigate, not to
re-record.
