# Progression goldens - provenance

What progression's own classes do, recorded by running them. Research §6: the PDF payload and the
defendant type are pure functions of JSON, so the binding record of them is progression's output, not
our reading of progression's code. `PdfPayloadMapperTest` (T031) and `DefendantTypeResolverTest`
(T019) assert against these files.

Nothing here is hand-written except the six synthesised inputs under `defendant-type/synthetic/`,
which say so in their own `note` field and are listed below.

## Source of truth

| | |
|---|---|
| Repository | `cpp-context-progression` |
| Commit | `79edf7cf3dab8a8f4fb667bc0946f36cc4547ee6` ("Updating develop poms back to pre merge state", 2026-07-09) |
| Working tree | the commit's blobs, read with `git show`, not a dirty checkout |

### Executed

`progression-event/progression-event-processor/src/main/java/uk/gov/moj/cpp/progression/processor/CourtRegisterPdfPayloadGenerator.java`
- whole file, 364 lines, sha256 `1c5107366a048a79f3f0d26dc570d3f401c6cc0fec4f529e1c76360257ad590c`
- compiled unmodified and called through `mapPayload(JsonObject)` (`:45-94`)

`progression-domain/progression-domain-common/src/main/java/uk/gov/moj/cpp/progression/domain/constant/DateTimeFormats.java`
- whole file, sha256 `b316854db01e0027c9d70ad2d1be8ad8901bde5a95c4400028d56bfc89cdcf92`
- compiled alongside it: the generator's two `DateTimeFormatter`s are built from `STANDARD`
  (`yyyy-MM-dd`) and `DATE_SLASHED_DD_MM_YYYY` (`dd/MM/yyyy`)

### Copied verbatim

`progression-command/progression-command-handler/src/main/java/uk/gov/moj/cpp/progression/handler/CourtRegisterHandler.java`
- sha256 `add0fb94a76408d9b583bb7a680ab6abb29a717511b23eb97e93131a33e607de`
- `getDefendantType(CourtRegisterDocumentRequest, CourtApplication)` - **lines 131-153**
- `getCourtApplicationId(CourtRegisterDocumentRequest)` - **lines 226-235**

Both are in `harness/CourtRegisterHandlerRule.java` character for character, private modifiers
included; the only additions are two package-private wrappers so the recorder can call them. The
class itself cannot be compiled outside progression - it extends `AbstractCommandHandler` and injects
`EventSource`, `AggregateService` and `Requester` - which is why the rule is carried rather than the
class. The caller's own behaviour is recorded too: `CourtRegisterHandler:84` seeds `defendantType`
with `StringUtils.EMPTY` and `:86-92` only replaces it when `getCourtApplicationId` returns non-null,
so "no court application" is the empty string here, not `null`.

## Classpath

JDK: `/usr/lib/jvm/java-17-openjdk`, `openjdk version "17.0.20.1" 2026-08-18`, OpenJDK Runtime
Environment (build 17.0.20.1+1). Progression is a JDK 17 build; this repository's Java 25 toolchain
plays no part in the recording, and Maven was never invoked on progression.

Jars taken from `target/dependency` directories a previous progression build had already populated:

| Jar | sha256 | Why |
|---|---|---|
| `javax.json-1.1.4.jar` (`org.glassfish`, from `progression-command/progression-command-api`) | `17fdeb7e22375a7fb40bb0551306f6dcf2b5743078668adcdf6c642c9a9ec955` | JSON-P API and implementation in one bundle - the generator is `javax.json` throughout |
| `utilities-core-17.103.0.jar` | `9405e720355170ed031fd4a44563193a30c941ee452b56034d8bc80ba7c4e3c6` | `uk.gov.justice.services.common.converter.ZonedDateTimes`, used by `formatZonedDate` (`:361-363`) |
| `guava-32.1.3-jre.jar` | `6d4e2b5a118aab62e6e5e29d185a0224eed82c85c40ac3d33cf04a270c3b3744` | `com.google.common.base.Strings` in `buildAliases` and `buildCourtHouseAddress` |
| `commons-lang3-3.12.0.jar` | `d919d904486c037f8d193412da0c92e22a9fa24230b9d67a57855c5c31c7e94e` | `StringUtils.capitalize` in `buildParentGuardianNameAndAddress` |
| `commons-collections-3.2.2.jar` | `eeeae917917144a68a741d4c0dff66aa5c5c5fd85593ff217bced3fc8ca783b8` | `CollectionUtils.isEmpty`, statically imported by `getCourtApplicationId` |
| `progression-domain-message-17.0.270-SNAPSHOT.jar` | `2df2a6be7f633397274ee943cb8c4d14c32529b8a77bcab71432e494e84ed11b` | the model classes the defendant-type rule names |

The model classes (`CourtRegisterDocumentRequest`, `CourtRegisterDefendant`,
`CourtRegisterCaseOrApplication`, `CourtApplication`, `CourtApplicationType`,
`CourtApplicationParty`, `MasterDefendant`) are **generated from** `criminal-court-public-model`
**17.103.13** - sha256 `a971c0efb5b17068a9dbd48bbe9e4966362d70ea60cb26fd14ccdc8d99efd137`, the same
version whose `courtRegisterDocument/*` schemas are frozen under
`src/main/resources/contracts/progression/` - but they are *compiled into*
`progression-domain-message`, so that jar and not the model jar is what is on the classpath. The
model jar ships schemas only; it holds no `.class` file.

The JSON-P implementation cannot leak into a golden: every file is written by the recorder's own
canonical writer - keys sorted, two-space indent, one trailing newline, `\n`/`\t`/`\uXXXX` escapes -
rather than by a `JsonWriter`.

## Inputs and the corpus digest

Everything the recorder read, 404 files:

| Input | Count |
|---|---|
| `src/test/resources/differential/recorded/index.json` | 1 |
| `src/test/resources/differential/recorded/*/expected.json` | 381 (205 documents, 176 `null`) |
| `.../expected-second-delivery.json` | 6 (4 documents) |
| `.../expected-alternate-clock.json` | 4 (4 documents) |
| `src/test/resources/fixtures/base/*.json` | 6 |
| `goldens/progression/defendant-type/synthetic/*.json` | 6 |

**Corpus digest `20fcb12324bf674d3b141b4fa822076f2ff56be1aad43531000d575aa649d924`** - sha256 over the
sorted list of input file digests, formed as the UTF-8 bytes of one
`<sha256><two spaces><repo-relative path>\n` line per input, sorted by path. Each file's own sha256
is in `INDEX.json` under `inputDigests`, so a changed input can be found rather than only detected.

## `pdf-payload/` - what was recorded

The generator takes a whole batch: `{"courtRegisterDocumentRequests": [ … ]}`. Both shapes are here.

**One document per batch** - `<caseId>.json`, and `<caseId>__second-delivery.json` /
`<caseId>__alternate-clock.json` for the corpus's alternate recordings of the same case. This is the
primary set: 161 goldens.

**Whole batches** - `batch__<courtCentreId>__<registerDate date part>.json`, formed by grouping the
recorded documents on `(courtCentreId, registerDate[0:10])`, members ordered by case id. Only
`contractStatus == IN_CONTRACT` documents are grouped, because a batch is what the nightly job
assembles out of *recorded* rows and a `SCHEMA_INVALID` document is dead-lettered before the store
ever sees it. The alternate recordings are individual goldens only - putting a case's second delivery
in the same batch as its first would invent a batch the pipeline cannot produce. 7 goldens,
135 members:

| Batch | Members | `cases` in the payload |
|---|---|---|
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2020-03-29` | 1 | 2 |
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2020-06-01` | 127 | 263 |
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2020-06-02` | 1 | 2 |
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2020-10-25` | 1 | 2 |
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2020-12-01` | 1 | 2 |
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2021-03-11` | 3 | 6 |
| `853b1ff8-fc2a-44d1-a621-0cd16419f54a__2026-08-21` | 1 | 2 |

The 127-member batch is the P4 shape: one payload assembled from many documents, whose header fields
(`registerDate`, `ljaName`, `courtHouse`, `courtHouseAddress`) the generator takes from
`stream().findAny()` (`:47`) - the first element in practice, and a batch whose first row differs
from the rest is the thing that makes that line worth pinning.

### Counts

| Kind | Count |
|---|---|
| Documents fed to `mapPayload` | 213 |
| Document goldens written | **161** (140 `IN_CONTRACT`, 21 `SCHEMA_INVALID`) |
| Documents the generator refused | **52** (all `SCHEMA_INVALID`) |
| Batch goldens written | **7** |
| `cases` entries across the document goldens | 331 |

### The 52 refusals

Every one is a `SCHEMA_INVALID` case, and that is the finding: the generator has no tolerance for a
document the frozen contract would reject. Two shapes, both located from the thrown stack:

| Count | Thrown at | Cause |
|---|---|---|
| 51 | `buildParentGuardianNameAndAddress` **`:184`** | `NullPointerException`. `:179` accepts a defendant that *has* a `parentGuardian`, `:182` reads that guardian's `address`, and `:184` dereferences it without a null check. A parent guardian carrying no address kills the whole payload - which is C29's second half, seen from progression's end. |
| 1 | `getAge` **`:325`** | `ClassCastException: javax.json.JsonValueImpl cannot be cast to javax.json.JsonString`. `:324` asks `containsKey("dateOfBirth")` and `:325` then reads it with the **one-argument** `getString`, which throws on a JSON `null`. The line above it, `:75`, survived the same value because the two-argument `getString(name, default)` swallows the cast and answers `DASH`. The case is `mut__surviving-youth-defendant__null-field__…-persondetails-dateofbirth`. |

They are recorded in `INDEX.json` as `pdfPayloadDocuments[].refusal` with no golden file, because
there is no output to be equal to. The port must never reach `PdfPayloadMapper` with one of these:
the contract validator refuses at the write, so no such document is ever recorded, batched or
rendered - and a document that *is* recorded cannot carry an address-less parent guardian, because
the frozen schema requires the address. What the port does about `:184` if it ever meets one is a
`doc/DEFECT-FIXES.md` question, not a golden.

### `cases[].age` is clock-dependent, and it is the only field that is

`getAge` (`:323-329`) is `Period.between(dateOfBirth, LocalDate.now()).getYears()`. **This set was
recorded on 2026-09-05, `TZ=Europe/London`** (`INDEX.json` `ageClockDate`). Nothing else in the
payload reads a clock: every other date is formatted from the document's own value.

`PdfPayloadMapper` therefore has to take a `Clock`, and `PdfPayloadMapperTest` has to pin it to
`2026-09-05` in `Europe/London` before comparing. A test that lets the mapper read the wall clock
will pass today and fail on the first birthday in the corpus. This is a defect the port owns, not one
the goldens can record away: `LocalDate.now()` cannot be pinned from outside the JVM, and no
`libfaketime` is installed on the recording host, so re-running the harness on a later date will move
`age` and nothing else.

## `defendant-type/` - what was recorded

One golden per case: `{hearingFixture, documentSource, courtApplicationId, defendantType}`, plus
`courtApplicationFoundOnHearing` where an application was looked up, `rule` where the answer comes
from the caller rather than the rule, `threw` where the rule threw, and `note` on the synthesised
cases. 9 goldens.

The court application is matched out of `hearing.courtApplications[]` by the id
`getCourtApplicationId` returns, which is research §5's decision: progression reads the
`ApplicationAggregate`, the port reads the hearing's as-at-hearing copy, and the two agree on the
type flags. `respondents` is where they can diverge; that deviation has its own pinning test
(`respondents_are_read_from_the_hearing_not_the_aggregate`) and is not something a golden can settle.

| Golden | `courtApplicationId` | `defendantType` |
|---|---|---|
| `hearing-with-address-less-youth-and-parent.json` | `null` | `""` |
| `hearing-with-non-prosecuting-authority-application.json` | `null` | `""` |
| `hearing-with-surviving-youth-defendant.json` | `null` | `""` |
| `synthetic__applicant-flags-false.json` | `6984d5b6-…` | `Applicant` |
| `synthetic__applicant-no-respondent-match.json` | `6984d5b6-…` | `Applicant` |
| `synthetic__appellant.json` | `6984d5b6-…` | `Appellant` |
| `synthetic__respondent.json` | `6984d5b6-…` | `Respondent` |
| `synthetic__master-defendant-without-flags.json` | `6984d5b6-…` | `null` - threw |
| `synthetic__respondents-absent.json` | `6984d5b6-…` | `null` - threw |

### The fixture gaps, and what was synthesised

**No base fixture can produce a non-empty defendant type.** All three that have a recorded document
answer `null` from `getCourtApplicationId`, because that method reads
`defendants[0].prosecutionCasesOrApplications[0].courtApplicationId` (`:234`) and element zero of
every one of them is the *prosecution case*, which carries no application id. The application is
element one and is never consulted. That is the no-application case T019 asks for, and it is real
rather than contrived - but it is also the only answer the fixtures can give.

**The other three base fixtures produced no document at all.** `base__adult-first-youth-second`,
`base__complete-court-centre` and `base__group-proceedings` are `NO_DOCUMENT` in the corpus (no
matched subscriptions; group proceedings), so there is no register document to type. They are in
`INDEX.json` with a `skipped` reason and have no golden.

So `Applicant`, `Appellant` and `Respondent` are **synthesised**, and so are the two throwing shapes.
The six inputs are `defendant-type/synthetic/*.json`, each a minimal
`{hearing: {courtApplications: […]}, document: {defendants: […]}}` carrying only the five fields the
rule reads, with the base fixtures' own ids so they stay recognisable:

| Input | What it adds | Answer |
|---|---|---|
| `applicant-flags-false.json` | applicant with a `masterDefendant`, both flags `false` | `Applicant` |
| `applicant-no-respondent-match.json` | applicant without a `masterDefendant`, one respondent who is not in the document | `Applicant` |
| `appellant.json` | applicant with a `masterDefendant`, `appealFlag` and `applicantAppellantFlag` both `true` | `Appellant` |
| `respondent.json` | applicant without a `masterDefendant`, a respondent whose `masterDefendantId` is the document's defendant | `Respondent` |
| `master-defendant-without-flags.json` | applicant with a `masterDefendant`, `type` present but carrying neither flag | `NullPointerException` |
| `respondents-absent.json` | applicant without a `masterDefendant`, no `respondents` at all | `NullPointerException` |

The last two are the shapes the base fixtures actually carry, and they are recorded because they say
what the rule does with them: `:139` unboxes `getAppealFlag()` and `:144` dereferences
`getRespondents()`, so an application whose type flags are absent, or which has no respondents, kills
the rule rather than defaulting. Progression is protected from it only by
`courtApplicationType.json` making both flags `required`. The port's `DefendantTypeResolver` has to
decide what it does instead, and whatever it decides is a difference from progression that needs a
`doc/DEFECT-FIXES.md` row - it is not covered by any existing P row, and T004 does not write rows.

## Verification

- Both goldens directories were recorded twice into a clean tree and `diff -r`'d: `pdf-payload`,
  `defendant-type` and `INDEX.json` are byte-identical between runs.
- All 184 JSON files under `goldens/progression/` parse, and every one ends in exactly one newline.
- The counts above are `INDEX.json`'s own, not a second tally.

## Files

```
goldens/progression/
├── PROVENANCE.md                 this file
├── INDEX.json                    every golden, its source, both digests, the counts, the clock date
├── pdf-payload/                  161 document goldens + 7 batch goldens
├── defendant-type/               9 goldens
│   └── synthetic/                6 authored inputs, not recordings
└── harness/                      the recorder and the digest indexer, for reference - not on the
                                  test classpath
```

**Both digests**, precisely: `inputDigests` maps each of the 404 recorded inputs (repo-relative
path) to its sha256, and `corpusDigest` is the manifest digest over that map; each of the 177
entries that names a golden - 161 documents, 7 batches, 9 defendant types - also carries
`outputSha256`, the sha256 of the golden file itself. An entry with `golden: null` is a refusal or
a skip and carries neither a golden nor an `outputSha256`. `harness/index-output-digests.py`
writes the output digests and is re-run after every re-record; it needs nothing but this tree,
unlike the recorder, and it fails if the goldens on disk and the goldens named here are not the
same set. It does not touch `corpusDigest`, which remains a statement about the inputs.
