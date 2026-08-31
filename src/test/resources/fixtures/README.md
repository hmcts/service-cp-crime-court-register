# Fixtures

Four directories, and the difference between them is the whole point.

| Directory | What is in it | May it be edited? |
|---|---|---|
| `nowshelper/` | Byte-identical copies of `$DF/NowsHelper/service/test/` | Never |
| `courtregister/` | Byte-identical copies of the court-register functions' own `test/` fixtures | Never |
| `rebuilt/` | Repairs of legacy fixtures the design proves stale or vacuous | Only with a provenance entry |
| `base/` | Six hearings authored here, because the legacy has nothing of that shape | Yes, with a note |

`$DF` = `cpp-context-azure-legalaidagency/azure-functions/durable-functions`, at HEAD `0d63f3ae`.

The rule is constitution Principle I. The legacy is the oracle for every behaviour the fix register
does not catalogue, and a fixture edited on the way in is an oracle that agrees with the port by
construction. So a repair never overwrites its original: it lands as a new file whose name says what
changed, beside a copy that still says what the legacy has. `support/LegacyFixtures` has one reader
per directory — `read`, `readCourtRegister`, `readRebuilt`, `readBase` — so a test says which kind of
fixture it is reading in the call itself.

## `courtregister/` — the byte-identical copies

Verified with `diff` at copy time, file by file, against the paths below.

| Here | `$DF` |
|---|---|
| `setcourtregister/hearing-results-for-court-register.json` | `SetCourtRegister/test/` |
| `outboundcourtregister/hearing-resulted.json` | `OutboundCourtRegister/test/` |
| `processoutboundcourtregister/court-register-document-request.json` | `ProcessOutboundCourtRegister/test/` |
| `mappers/defendant/hearing-resulted.json` | `.../Mappers/Defendant/test/` |
| `mappers/defendant/hearing-resulted-with-courtApplication-and-prosecutioncase.json` | `.../Mappers/Defendant/test/` |
| `mappers/defendant/hearing-resulted-with-only-courtApplication.json` | `.../Mappers/Defendant/test/` |
| `mappers/defendant/hearing-resulted-with-no-matching-defendants.json` | `.../Mappers/Defendant/test/` |
| `mappers/offence/offences.json` | `.../Mappers/Offence/test/` |
| `mappers/parentguardian/hearing-resulted.json` | `.../Mappers/ParentGuardian/test/` |
| `mappers/prosecutioncaseorapplication/hearing-resulted.json` | `.../Mappers/ProsecutionCaseOrApplication/test/` |
| `mappers/prosecutioncaseorapplication/hearing-resulted-with-matching-prosecutionCases-and-courtApplications.json` | `.../Mappers/ProsecutionCaseOrApplication/test/` |
| `mappers/youthdefendant/hearing-resulted.json` | `.../Mappers/YouthDefendant/test/` |

The originals of the seven bad-vocabulary fixtures are deliberately **not** copied. There is nothing
a twin could do with the seven-key block except reproduce a defect no legacy test can see, so those
seven exist here only in `rebuilt/`, where `PROVENANCE.md` records what each carried.

## `rebuilt/` — the repairs

`rebuilt/PROVENANCE.md` is the authority: the seven vocabulary rebuilds and, in its second half, the
four further repairs — a real indicated plea and allocation decision on the offences, offence-level
results for two offence ids, a fragment complete enough to assemble a document from, and a
document-request that the frozen contract actually accepts.

## `base/` — the six authored hearings

Six shapes the fixes are about, and not one of them is in a court-register fixture today. They are
**payloads**, not bare hearings — `{"hearing": …, "sharedTime": …}`, the shape the claim-check hands
the pipeline — so a mapper test that wants the hearing alone reads `.get("hearing")`.

All six share a spine, and the spine is `mappers/youthdefendant/hearing-resulted.json` with three
named changes:

1. **A complete court centre.** `id` kept (`853b1ff8-…`, the id the outbound twin needs to assert);
   `name`, `code`, `address` and `lja` taken from `setcourtregister/hearing-results-for-court-register.json`,
   the only court-register fixture that has them. Every other court-register fixture carries an id
   and an LJA and nothing else, which is why `courtHouse` — the contract's required venue field —
   comes out absent today and the venue mapper's address body has never once executed.
2. **ISO ordered dates.** The youth fixture records `orderedDate: "20-01-2020"`; every other fixture,
   and every payload, records `2020-01-20`. Defect C13 is about parsing that field, so a base hearing
   carrying a form no producer sends would prove nothing.
3. **One dropped defendant-level result.** The fixture's second `defendantJudicialResults` entry
   names master defendant `ded76309-…`, whom no case or application on the hearing carries. The
   mapper suite never gathers, so nothing there notices; the port classifies an ungatherable
   defendant-level result as a transformation failure, and a base hearing that cannot be gathered is
   no use to anybody.

`sharedTime` is `2020-06-01T10:00:00Z` throughout — the instant the C10 fix is pinned on, and the one
a British Summer Time relabelling turns into `11:00:00Z`.

| File | What it is for | Built from the spine by |
|---|---|---|
| `hearing-with-complete-court-centre.json` | The venue: name, code and a real address, so `HearingVenueMapper.address()` runs and the file name carries a court-centre code | the spine alone — its one defendant is not youth-flagged, so it is also the `no-youth-defendants` hearing |
| `hearing-with-surviving-youth-defendant.json` | A child who survives every filter to the outbound document | `isYouth: true`; a youth date of birth; a middle name (the composition the legacy asserts around); a real `nationalityDescription`; both ethnicity descriptions (C25); two `defendantCaseJudicialResults`, the first `Not Applicable` and the second not, so `postHearingCustodyStatus` reaches a real value for the first time; an `associatedDefenceOrganisation`; a two-entry `defendantAttendance` with the youth's record **second**, so C8's element-zero selection picks the wrong defendant; and the prosecuting-authority court application from `hearing-resulted-with-matching-prosecutionCases-and-courtApplications.json`, its subject youth-flagged |
| `hearing-with-group-proceedings.json` | C7 | the surviving-youth hearing plus `isGroupProceedings: true` |
| `hearing-with-adult-first-youth-second.json` | C31 — matching runs on defendant[0], and defendant[0] is the adult | an adult defendant (own ids, own name, own offence id, `isYouth: false`, no associated persons) inserted **ahead** of the youth |
| `hearing-with-non-prosecuting-authority-application.json` | C22 — the application the legacy includes and the fix excludes | the surviving-youth hearing with the applicant's `prosecutingAuthority` removed and a `masterDefendant` put in its place: a defence-led application, eligible under the legacy's subject-only gate and not under the fix |
| `hearing-with-address-less-youth-and-parent.json` | C29, both halves, separably | the youth's `personDetails.address` removed, plus a **second** youth who has an address but whose parent has none |

### What they gather

Recorded by running `RegisterBuilder` over each of them, which is this task's observed result. Every
one produces `courtCentreOUCode = B01LY00`, `courtCentreId = 853b1ff8-fc2a-44d1-a621-0cd16419f54a`,
`registerDate = 2020-06-01T10:00:00Z` (the shared instant, unrelabelled) and
`hearingDate = 2020-01-20T00:00:00Z`.

| File | Register defendants |
|---|---|
| complete court centre | 1 — not youth, 2 results, 1 case, 0 applications |
| surviving youth defendant | 1 — youth, 4 results, 1 case, 1 application |
| group proceedings | 1 — youth, 4 results, 1 case, 1 application |
| adult first, youth second | 2 — the **adult** first (3 results), then the youth (4 results, 1 application) |
| non-prosecuting-authority application | 1 — youth, 4 results, 1 case and **0 applications**: the fix excludes it |
| address-less youth and parent | 2 — both youth, 4 and 3 results |

The last two rows are the fixtures earning their place. The application count of zero *is* C22, and
it is one line of evidence rather than an argument.

## `ModelObjects` — the fixture that is code

`$DF/…/ProsecutionCaseOrApplication/test/ModelObjects.js` is the only court-register fixture written
as JavaScript rather than JSON: four Jest cases build their court applications with it because what
they are about is the shape of an application, not the content of a hearing. It is ported as
`support/ModelObjects`, class names kept, and two JavaScript quirks kept with them — the single
offence each of `CourtOrder` and `CourtApplicationCase` wraps in a one-element array, and the fields
the constructors name without assigning, which are **absent** here rather than null.
