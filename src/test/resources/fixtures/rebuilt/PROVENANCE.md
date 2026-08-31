# Rebuilt legacy fixtures — the vocabulary key set

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

## One open item for the task that consumes these

`courtregistersubscriptions/register-defendant.json` is the only one of the seven whose defendant
carries **no `isYouthDefendant` field at all** — it has three keys in total
(`masterDefendantId`, `results`, `vocabulary`), which is itself a gap the twin map records. The rule
above therefore gives it `youthDefendant: false`, which is what the kernel would compute. A
subscription-matching test that needs a youth register defendant must add the flag deliberately and
say so, rather than assume this file supplies one.
