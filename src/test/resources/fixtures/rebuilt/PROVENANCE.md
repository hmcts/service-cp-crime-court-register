# Rebuilt legacy fixtures

Two halves. The first is the seven fixtures whose vocabulary block is wrong; the second is four
further repairs, each named after what it repairs and each sitting beside a byte-identical copy of
the original under `fixtures/courtregister/`. `fixtures/README.md` is the index.

## Part one — the vocabulary key set

Seven court-register Jest fixtures carry a `vocabulary` block with **seven** keys, two of them
mis-capitalised, where the shared kernel produces **eighteen**
(`$DF/NowsHelper/service/VocabularyService.js:11-28`). No Jest test inspects a vocabulary object
closely enough to notice, so the defect is invisible in the legacy suite and would be invisible in a
port that took its key set from these files — subscription matching would fail in production against
`youthDefendant`, which is this flow's entire business rule, and against `anyCourtHearing`, which the
`SubscriptionsService` court-register case shows is what actually matches.

The files in this directory are those seven, rebuilt. They are **not** byte-identical copies and are
kept apart from `fixtures/nowshelper/` for that reason: everything there is the legacy oracle
verbatim, everything here is a repair.

## What each file carries

| Path here | Legacy source (`$DF`) |
|---|---|
| `courtregistersubscriptions/register-defendant.json` | `CourtRegisterSubscriptions/test/register-defendant.json` |
| `outboundcourtregister/court-register-fragment.json` | `OutboundCourtRegister/test/court-register-fragment.json` |
| `mappers/offence/defendant-context-base.json` | `OutboundCourtRegister/CourtRegisterRequest/Mappers/Offence/test/defendant-context-base.json` |
| `mappers/parentguardian/defendant-context-base.json` | `.../Mappers/ParentGuardian/test/defendant-context-base.json` |
| `mappers/prosecutioncaseorapplication/defendant-context-base.json` | `.../Mappers/ProsecutionCaseOrApplication/test/defendant-context-base.json` |
| `mappers/youthdefendant/youth-defendants.json` | `.../Mappers/YouthDefendant/test/youth-defendants.json` |
| `mappers/youthdefendant/defendant-context-base.json` | `.../Mappers/YouthDefendant/test/defendant-context-base.json` |

All seven carried the identical seven-key block:

```json
{"custodyLocationIsPolice": false, "custodyLocationIsPrison": false,
 "atLeastOneCustodialResult": false, "allNonCustodialResults": true,
 "atLeastOneNonCustodialResult": true, "appearedInPerson": false,
 "appearedByVideoLink": false}
```

## What was changed, and by what rule

**Nothing outside the `vocabulary` object.** Every other field of every file is the legacy's, byte
for byte.

Inside it, the two mis-capitalised keys were renamed to the kernel's spelling — `atLeastOne…` to
`atleastOne…`, lower-case `l` — carrying their values unchanged, and the eleven missing keys were
added by the kernel's **own formulas** applied to the values already in the file
(`VocabularyService.js:146-170`), never by choosing a value that looked right:

| Key | Rule |
|---|---|
| `isCpsProsecuted` | `getHasAtleastOneProsectorIsCps()` — no fixture carries a CPS prosecutor, so `false` |
| `anyAppearance` | `appearedInPerson \|\| appearedByVideoLink` |
| `inCustody` | `custodyLocationIsPrison \|\| custodyLocationIsPolice` |
| `youthDefendant` | `!!defendantContextBase.isYouthDefendant`, read from the same object the vocabulary hangs on |
| `adultDefendant` | `!youthDefendant` |
| `adultOrYouthDefendant` | `youthDefendant \|\| adultDefendant` — always `true` |
| `welshCourtHearing` | `!!courtCentre.welshCourtCentre`; no fixture's court centre is Welsh, so `false` |
| `englishCourtHearing` | `!welshCourtHearing` |
| `anyCourtHearing` | `welshCourtHearing \|\| englishCourtHearing` — always `true` |
| `prosecutorMajorCreditor` | `[]` — the two-argument construction, defect C30 |
| `nonProsecutorMajorCreditor` | `[]` — same |

The key order is `VocabularyInfo`'s constructor order, so a file here reads in the same order as the
object the port produces.

## One open item for the task that consumes these (part one)

`courtregistersubscriptions/register-defendant.json` is the only one of the seven whose defendant
carries **no `isYouthDefendant` field at all** — it has three keys in total
(`masterDefendantId`, `results`, `vocabulary`), which is itself a gap the twin map records. The rule
above therefore gives it `youthDefendant: false`, which is what the kernel would compute. A
subscription-matching test that needs a youth register defendant must add the flag deliberately and
say so, rather than assume this file supplies one.

---

## Part two — four further repairs

Each of these has a byte-identical original under `fixtures/courtregister/`. Nothing below overwrites
it; the twin that pins legacy behaviour reads the original, and the twin that pins the fix reads the
repair.

### `mappers/offence/offences-with-real-plea-and-allocation.json`

The legacy `offences.json` carries `"indicatedPlea": {}` and `"allocationDecision": {}`, so the one
Jest assertion that names an indicated plea compares `undefined` to `undefined` and the allocation
decision is asserted by nothing at all. Changes:

- `indicatedPlea` given a real value — `INDICATED_GUILTY`, dated, with the offence and originating
  hearing ids the rest of the file uses;
- `allocationDecision` given a real `motReasonDescription`, which is the field the mapper reads;
- **a second offence added**, `f4a88647-…` / `TH68001`, deliberately carrying **no
  `offenceLegislation`** — the C24 case where the legacy emits a literal `"…####undefined"` — and a
  verdict type with no `verdictCode`, which is the C23 fallback shape live payloads have been
  observed in.

Everything on the first offence is otherwise the legacy's, `verdictType` included: it already carries
`verdictCode: "1234"` alongside `description: "desc1234"`, which is exactly what makes C23 provable
without inventing data.

### `mappers/offence/defendant-context-base-with-offence-results.json`

The rebuilt context beside it has `"results": []`, faithfully, so the offence-level result scoping at
`OffenceMapper.js:24-26` has never executed — and that scoping is the one place the court register is
more correct than its informant sibling. This copy carries four results at four scopes: two at
offence level against the **two different offence ids** above, one at case level, one at defendant
level. A mapper that ignored the scoping would put all four on both offences, and a mapper that
scoped by level alone would put the case and defendant results there too.

### `outboundcourtregister/court-register-fragment-complete.json`

The legacy `court-register-fragment.json` cannot produce a document: it has no `hearingId`, no
`hearingDate` and no `courtCentreOUCode`, its one register defendant has no results, and its single
matched subscription carries neither `emailDelivery` nor `forDistribution`, so the recipient
predicate never passes and `recipients` comes out absent. The outbound Jest case asserts
`toBeTruthy()` and three dates, and nothing about any of that. This copy adds:

- the three missing fragment fields, at the fixed values (`registerDate` the unrelabelled instant);
- a defendant-level result and an `orderedDate`, so the defendant has something to print;
- **two** matched subscriptions that pass the predicate — the first naming an `emailTemplateName`,
  the second not, so the `cr_standard` default is reachable; the first's `emailAddress1` padded with
  spaces, because trimming is asserted only against a helper today and never through the mapper.

### `processoutboundcourtregister/court-register-document-request.json`

The legacy fixture is a document progression would reject. Validated against the vendored contract it
fails three ways:

```
'hearingDate' is a required property
'hearingId' is a required property
defendants[0]: Additional properties are not allowed ('arrestSummonsNumbers' was unexpected)
```

The third is defect C26 in one line: the model declares `arrestSummonsNumbers`, plural and on the
defendant; every mapper writes `arrestSummonsNumber`, singular and on the case, which is what the
schema has. The repair carries the singular in its right place, adds the two required fields, and
moves four values onto the fixed behaviour their C-numbers specify:

| Field | Legacy | Here | Why |
|---|---|---|---|
| `fileName` | `court-register_2019-02-01_oucode.csv` | `court-register_2020-06-01_B01LY00_1828f356-…pdf` | C11 — and a `.csv` the code stopped producing before it became `.pdf` |
| `registerDate` | `2019-02-01` | `2020-06-01T10:00:00Z` | the contract says `date-time`; C10 says which instant |
| `verdictCode` | `Guilty` | `1234` | C23 — a code in the field named for one |
| `offences[0].wording` | wording only | wording, a real newline, legislation | C24 — no `####`, no `undefined` |

It validates against `progression.add-court-register.json` and the eight `courtRegisterDocument/*`
schemas with **zero errors**, which is what makes it usable as the "valid document" half of the
contract-validation suite.
