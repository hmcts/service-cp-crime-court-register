# Differential audit — the legacy as oracle, the register as the only licence to differ

**Task**: T075 (US5, Phase 8) · **Run**: 2026-09-01 · **Result**: 381/381 cases audited, **zero
unexplained differences**, full `./gradlew build` green.

The rule this report is the evidence for is constitution Principle I, restated at the head of
`doc/DEFECT-FIXES.md`:

> the port encodes FIXED behaviour, the legacy is the oracle for everything uncatalogued — every
> legacy-vs-port difference must derive from a `doc/DEFECT-FIXES.md` row (C1–C36); an unattributed
> difference is a port defect.

The audit enforces it in both directions, and this report answers both. **Forwards**: nothing the
port does differently is unaccounted for. **Backwards**: every content-changing row of the register
is actually *reached* by the corpus — because a fix that produces no difference anywhere is not a
fix that has been demonstrated, it is a corpus that never built the shape.

---

## 1. Corpus provenance

The corpus is 381 recordings of the **real Node function app**, not a re-implementation of it.
`oracle/lib/pipeline.js` loads `SetCourtRegister/index.js`, `CourtRegisterSubscriptions/index.js`
and `OutboundCourtRegister/index.js` from the legacy working tree and runs them behind the
orchestrator's own group-proceedings guard, with every `callActivity` argument and return value
crossing the Durable Task serialisation boundary.

| Field | Value |
|---|---|
| Legacy commit | `0d63f3ae6bd0f4929e182de8d8ad9429160dd07f` (`cpp-context-azure-legalaidagency/azure-functions/durable-functions`) |
| Legacy working tree | clean |
| `packageLockSha256` | `d7d9a932…c7d859` |
| Node | v24.18.1 |
| `oracleDigest` | `c016073e…beb416b4` |
| `contractBundleDigest` | `ff19dfcb…c74ffadc` (15 vendored progression schemas) |
| Clock pin | `2026-08-21T09:15:00.000Z` (canonical), `2026-12-04T23:40:00.000Z` (alternate) |
| Timezone | `TZ=Europe/London`, pinned in the harness and skippable so the pin is testable |
| Harness | `analysis/results-distribution/CourtRegister/differential-pack/` — **not in this repository** |
| Installed at | `src/test/resources/differential/recorded/` (22 MB) |

Checked rather than claimed, on this build: **381/381 deterministic** at a fixed clock (every case
run twice and compared); **4 clock-dependent** cases identified by re-running the whole corpus at a
second clock across both a date boundary and the BST/GMT boundary, each carrying an
`expected-alternate-clock.json`; **381/381 byte-identical** under `TZ=Asia/Tokyo` and
`TZ=America/Los_Angeles` with the pin skipped; and `verify-boundary.js` reports **0 cases** where
the activity boundary changes the output.

### Size and shape

| Cut | Counts |
|---|---|
| Cases | **381** — 6 unmutated base hearings, 366 from twenty-one mutation operators, 9 subscription-set equivalence cases |
| Legacy outcome | 205 document · 152 no-document · 15 swallowed-exception · 9 skipped-group-proceedings |
| Output axis (`contractStatus`) | **IN_CONTRACT 135 · SCHEMA_INVALID 70 · NO_DOCUMENT 176** |
| Input axis (`inputContractStatus`) | IN_CONTRACT 246 · SCHEMA_INVALID 101 · PRODUCER_IMPLAUSIBLE 34 |
| Both axes `IN_CONTRACT` | **84** — the full parity obligations |

`contractStatus` is what the frozen, vendored `progression.add-court-register` bundle makes of the
document **the legacy produced**, and it decides how a case may be asserted:

* **IN_CONTRACT (135)** — progression accepts it, so the port must produce it. Every difference must
  be claimed by a C-row; an unclaimed one fails, naming its JSON pointer.
* **SCHEMA_INVALID (70)** — progression answers 400 and C1 swallows it, so the register is lost with
  no trace. The port must **classify rather than reproduce**: refuse at the contract with a pointer
  the recorder's own validator named (C29), or repair under a row that authorises the repair.
* **NO_DOCUMENT (176)** — the legacy produced nothing and its recorded reason carries the obligation
  instead: the same no-op where no row applies, the row's own answer where one does.

A run the legacy ended by swallowing an exception is **never** agreement, whatever the port did:
reporting success on a failure is C2.

---

## 2. Differences observed, by defect-fix row

Twenty-one rows explain differences across the corpus; **nothing is unattributed, and nothing is
claimed by two rows** — the audit asserts mutual exclusivity, so a divergence two rows could explain
fails as loudly as one no row explains.

| Row | Differences | An example |
|---|---:|---|
| C2 (orchestration reports success on failure) | 18 | `mut__address-less-youth-and-parent__refdata-unavailable__connection-refused` — the run's outcome |
| C4 (court-centre code compared against informant codes) | 2 | `mut__address-less-youth-and-parent__subscription-routing__informant-code-only` — the run's outcome |
| C5 (no court-register branch in the matcher) | 4 | `mut__address-less-youth-and-parent__subscription-routing__now-subscription-route` — the run's outcome |
| C7 (group-proceedings skip loosely typed) | 4 | `mut__address-less-youth-and-parent__group-proceedings__string-false` — the run's outcome |
| C8 (attendance lookup assigns instead of comparing) | 1 | `mut__surviving-youth-defendant__empty-array__defendantattendance` — the run's outcome |
| C9 (attendance date can never match) | 252 | `base__non-prosecuting-authority-application` — `/defendants/0/hearing/defendantPresent` |
| C10 (BST local time labelled as UTC) | 131 | `base__non-prosecuting-authority-application` — the derived component `registerDate` |
| C11 (filename carries colons and is not unique) | 131 | `base__non-prosecuting-authority-application` — `/fileName` |
| C12 (evening shares read the next day's subscriptions) | 3 | `mut__address-less-youth-and-parent__shared-time__bst-2300-utc` — the day the subscription set was read for |
| C19 (youth mapper dies on non-person defendants) | 4 | `mut__address-less-youth-and-parent__legal-entity__all` — the run's outcome |
| C21 (ASN derivation dies on legal-entity records) | 2 | `mut__surviving-youth-defendant__asn-record-without-person__legal-entity-record` — the run's outcome |
| C22 (non-prosecuting-authority applications reach the register) | 8 | `base__non-prosecuting-authority-application` — `/defendants/0/prosecutionCasesOrApplications` |
| C23 (`verdictCode` carries a prose description) | 147 | `base__non-prosecuting-authority-application` — `…/offences/0/verdictCode` |
| C24 (`####` sentinel and `undefined` residue) | 276 | `base__non-prosecuting-authority-application` — the derived component `wording` |
| C25 (ethnicity only when both descriptions present) | 2 | `mut__surviving-youth-defendant__ethnicity-partial__observed-only` — `/defendants/0/ethnicity` |
| C26 (mapper/model drift) | 4 | `mut__surviving-youth-defendant__null-field__…-orderindex` — `…/offences/0/orderIndex` |
| C29 (missing required address silently loses the register) | 59 | `base__address-less-youth-and-parent` — the run's outcome |
| C30 (major-creditor flags match inconsistently on empty data) | 2 | `mut__address-less-youth-and-parent__subscription-routing__any-major-creditor` — the run's outcome |
| C31 (only the first defendant's vocabulary is matched) | 74 | `base__adult-first-youth-second` — the run's outcome |
| C35 (hearing date stamped from the wall clock) | 4 | `mut__surviving-youth-defendant__hearing-date__hearing-days-not-an-array` — the run's outcome |
| C36 (recipient-less registers submitted to nobody) | 4 | `mut__address-less-youth-and-parent__subscriptions-shape__court-register-but-no-recipient` — the run's outcome |
| **Unattributed** | **0** | — |

Three cases carry no oracle at all and are listed rather than counted:
`mut__{address-less-youth-and-parent,adult-first-youth-second,surviving-youth-defendant}__shared-time__absent`.
The recorder ran them with no `sharedTime`, `moment` reads an absent date as *now*, and so the
register date, the file-name day and the reference-data day are all readings of the corpus's own
clock. There is nothing there to reproduce — reproducing it would need a clock inside a
transformation the constitution requires to be pure — so the audit holds them to the one thing that
is assertable: **the port refuses the payload rather than inventing a date for it.**

### How a difference is claimed

Two mechanisms, because there are two kinds of difference, and neither is an exclusion:

* A **derivation** (C10 `registerDate`, C24 `wording`) computes, from the value the oracle recorded,
  the value the port is now required to write, and the comparator demands exactly that. The check is
  as strict as equality was; only the expected string moved. An oracle value the derivation does not
  describe is reported as a difference rather than waved through — and C10 accepts *both* offsets in
  London's repeated autumn hour, because the legacy's rendering genuinely does not record which of
  the two it was.
* A **claim** carries a predicate that recognises the *signature* of one fix. Predicates are
  deliberately narrow: "anything at this path may differ" would be an exclusion wearing a C-number.
  C11 derives the required file name from the recording's own instant, so a name right about the
  court code and wrong about the day is still a difference; C29 requires the pointer the port names
  to be one the recorder's own validator named; C35 reads the recorded `hearingDate` back as a
  London wall clock and requires it to name the instant the corpus was built at.

Every claim's `reference()` is checked against `doc/DEFECT-FIXES.md` by
`registers_nothing_that_is_not_a_row_of_the_defect_fix_register`, so an entry citing a C-number that
does not exist cannot attribute a behaviour change to a review that never happened.

---

## 3. Port defects the audit caught

Both were found by T074 writing the register against the recorded corpus, both were fixed red/green,
and both were behaviour this repository's own pinning suites had agreed with.

| # | Defect | Found at | Commits |
|---|---|---|---|
| 1 | `JsStrings` trimmed with Java's `String.trim()` where the legacy trims with `String.prototype.trim()`. The two disagree **in both directions** — Java strips ASCII control characters JavaScript keeps, JavaScript strips Unicode spaces Java keeps — so a child's name reached the register still padded with the U+00A0 it arrived in. `jsTrim` is now ECMA-262's own whitespace set, and the email trim behind C27 gets it too. | `mut__surviving-youth-defendant__unicode-name__nbsp-padded` | `41c6ae2` (red) / `805599c` (green) |
| 2 | C11's file name took its day from `Dates.localDate` — London's calendar day — so a 23:00Z share was filed under the *following* day, and under a different day from the subscription set it was addressed by. `Dates.registerDay` answers the UTC day and is asserted equal to `subscriptionDay` on the same value. | `mut__surviving-youth-defendant__shared-time__bst-2300-utc` | `7efee83` (red) / `360d29a` (green) |

The second is the more interesting one: it was a port defect **against C10's own row**. That row
claims three values move for a BST evening share — the stored timestamps, the file-name day and the
reference-data lookup day — and only two of them did. C10 and C11 were amended to record what the
audit found (`39c54fe`).

Nothing else was found. In particular the 351-case corpus produced **zero** unexplained differences
on the first run, which is what made the reverse reconciliation below the substance of this task.

---

## 4. Reconciliation: every content-changing row must appear in the diff set

The register itself is the authority on which rows change content, and it says so in two places —
the sign-off column and the ⚠ impact note. **Seventeen** rows are marked content-changing,
content-affecting, wire-visible or PDF-visible outright:

> C4, C7, C8, C9, C10, C11, C12, C19, C20, C21, C22, C23, C24, C25, C31, C35, C36

**Two more** are marked content-changing *conditionally* by their impact note — "iff any live
subscription's `informantCode` equals a court-centre code" (C4's sibling **C5**) and "iff any live
court-register subscription relies on the vacuously-true `anyMajorCreditor`" (**C30**) — which the
Q9 reference-data snapshot settles before cutover. They are held to the same standard here, because
a conditional content change is still a behaviour change the corpus must be able to demonstrate.
**Nineteen rows in total.**

The task statement's candidate list differs, and the register wins each disagreement: it named
**C27**, whose sign-off says *"internal reliability, no content change"*; it named **C29**, whose
sign-off says *"the visibility fix itself is internal"*; and it omitted **C19, C20 and C21**, each of
which the register marks content-changing in as many words — "register produced where legacy dropped
it", "partial register instead of none", "register survives where legacy dropped it". Nothing is
lost by the two exclusions: C29 appears in the diff set anyway, 59 times.

### First run: eight rows explained nothing

| Row | Why the corpus could not reach it |
|---|---|
| C4 | No court-register subscription in the 274-entry capture carries an `informantCode` (40 non-register entries do), so `matchProsecutor` never fired. |
| C5 | No court-register entry is also flagged `isNowSubscription`, `isEDTSubscription` or `isPrisonCourtRegisterSubscription`, so the accidental arms were never taken. |
| C12 | The corpus *did* record the day (the recorder captures the whole `now-subscriptions` GET), but the audit never looked at it. |
| C20 | The unguarded application lookup has no trigger — see below. |
| C21 | Reaching `getASN`'s dereference needs a person-less record *beside* an intact defendant; the `legal-entity` operator removes the person from the defendant itself, and C19 throws first. |
| C25 | All six base hearings carry both ethnicity descriptions, and the legacy's guard needs both. |
| C30 | **No** entry anywhere in the capture sets `anyMajorCreditor`, so the vacuous branch was never evaluated. |
| C35 | Every base carries an ordered date and an empty `hearingDays` — the one route through `getHearingDate` that neither reads the clock nor throws. |

None of these is evidence that the fix is absent. Each is evidence that the corpus never built the
shape, so the corpus was extended.

### The extensions, and what each found

Five operators added to the analysis pack; corpus re-recorded from 351 to **381** cases. **Every one
of the 351 earlier `expected.json` files is byte-identical after the rebuild** — only `oracleDigest`
moved, which is exactly what that field is for. The audit also gained one comparison it had been
missing.

| Extension | Cases | Row(s) | What it found |
|---|---:|---|---|
| `subscription-routing` (operator) | 18 | C4, C5, C30 | Closes the court-house route on the one entry that covers `B01LY00` and re-opens each of the matcher's other arms in turn — `informantCode`, the prison-register flag, the NOWs flag — plus the three major-creditor flags with the court-house route left open. On every base whose vocabulary gate the register survives, the legacy produces a register through the re-opened arm and the port produces none. |
| `hearing-date` (operator) | 4 | C35 | Walks `getHearingDate`'s other four legs. Both clock legs stamp `hearingDate` with the corpus's own clock pin; the two throwing legs (`hearingDays` a truthy non-array, and a null sitting record) lose the whole hearing to a swallowed `TypeError` where the port classifies. |
| `ethnicity-partial` (operator) | 3 | C25 | With one description removed the legacy writes no ethnicity at all — including in `observed-only`, where the value it would have returned is the one that *is* present. The `neither` variant is the control and shows no difference. |
| `asn-record-without-person` (operator) | 2 | C21 | Puts a `masterDefendantId`-sharing record with no person block *beside* an intact defendant, which is the only shape that reaches `getASN`'s dereference. Recorded `TypeError: … reading 'arrestSummonsNumber'`, swallowed; the port survives with the register. |
| `application-reference` (operator) | 3 | C20 | A **negative** result, and a measured one — see below. |
| Reference-data day comparison (audit) | — | C12 | No new recording needed: the recorder had captured `…now-subscriptions?on=2020-06-02` for a `2020-06-01T23:00:00Z` share all along. The audit now compares the day the legacy asked for against the day the port's own `Dates.subscriptionDay` answers, and reports the difference like any other. Three cases. |

Two audit-side changes went with them, both narrowing rather than widening:

* The clock-dependent exclusion now applies only to the `shared-time__absent` cases. C35's two clock
  legs are recorded clock-dependent too, but there the payload is complete and *one field* is filled
  from the clock, so the rest of the document remains a perfectly good oracle. Excluding every
  clock-dependent case would have hidden the row the corpus was extended to reach.
* C35's claim covers two shapes of the same leg: where the port has an ordered date to fall back to
  the difference is the field; where it has none, the document it assembles carries no `hearingDate`
  and the frozen contract refuses it **at that very pointer** — which is what C35's row says leg (a)
  must do, in preference to accepting a clock reading.

### After the extensions

Eighteen of the nineteen content-changing rows now appear in the diff set. The nineteenth is C20,
and it is not a gap:

> **C20's trigger does not exist, and that is now measured rather than argued.** The row describes an
> unguarded `hearingJson.courtApplications.find(...)` that dies on an absent array or an unmatched
> `applicationId`. No hearing payload can produce either. `DefendantContextBaseService.js:149`
> pushes an application id onto a defendant **only** for applications that passed `isEligible`, and
> `ProsecutionCaseOrApplicationMapper.js:62-65` looks that id up in **that same array** — so
> `registerDefendant.applications` is always a subset of the ids present, never the reverse. The
> three `application-reference` cases move every application id, remove the eligibility, and add an
> ineligible second application; **all three produce a register and none swallows anything.** C20 is
> a defensive guard, symmetrical with the SNI-9005 fix on the prosecution-case path, whose fault the
> legacy cannot be made to commit — so its "partial register instead of none" impact is unreachable
> from a hearing payload.

That is a finding about the row, not a weakening of the claim: the corpus was extended to look for
it, and what the extension established is that the shape does not exist. C20's row has been amended
to record it, and its content-change impact re-stated as conditional on a payload no producer emits.

### The rows that correctly explain nothing

Fourteen fixed rows are architecture, internal reliability or *no content change*, and are not
expected to produce a diff: C1, C2, C3, C6, C13, C14, C15, C16, C17, C26, C27, C29, C32, C33. Their
pinning tests live in the suites named in their register rows; the differential corpus is not where
they are demonstrated, and their silence here is the correct result.

Three of them appear anyway, and each for a reason worth recording rather than a reason to
reclassify:

* **C2 (18)** and **C29 (59)** — an internal fix that changes *where a run ends* is visible to a
  differential audit even when it changes nothing on the wire. A run the legacy ended by swallowing
  an exception and reporting `Success: true` cannot be agreement, and a document the frozen contract
  refuses is a dead-lettered `OUTBOUND_CONTRACT_VIOLATION` here rather than a silent 400 there.
* **C26 (4)** — the legacy copies a producer's explicit JSON `null` straight to the wire and the
  port's typed records cannot express present-and-null. All four cases are `SCHEMA_INVALID` at
  exactly that pointer, so the difference is between a document progression refuses and one it does
  not, which is what the row means by no content change.

The remaining eleven explain nothing, correctly.

---

## 5. Where the audit runs, and what it costs

The whole corpus goes through the **real chain** — the group-proceedings policy, then
`RegisterTransformationChain` over the fragment builder, the subscription matcher, the twelve
mappers and the frozen-contract validator. Nothing is stubbed: the chain is pure by construction, so
the only inputs it needs are the two the recorder captured. Subscriptions arrive pre-fetched,
because that is how the running service supplies them.

It therefore needs no container, no socket and no clock, runs untagged in **`./gradlew build`** with
everything else, and takes about two seconds for 381 cases. `audits_the_whole_recorded_corpus`
asserts the size, so a corpus that quietly shrank cannot make the suite pass by looking at less.

**Full build on this run: `BUILD SUCCESSFUL`** — compile, the whole test suite, Checkstyle, PMD,
SpotBugs and the JaCoCo coverage gate.

---

## 6. What this audit does not know

Inherited from the corpus and restated here, because a clean audit over a corpus with blind spots is
only as good as the blind spots are visible:

* **The base hearings are authored, not captured.** The legacy has no court-register fixture of the
  shapes the fix register is about — nine of its ten court-centre-bearing fixtures carry an id and
  an LJA and nothing else. All six bases share one spine.
* **Operator scope is deliberately narrow.** Any operator scoped narrower than `all` is a base the
  corpus never asked that question of; the table is recorded in `recorded/provenance.json` under
  `operatorScope`.
* **The vocabulary gate is thinly exercised.** The major-creditor branches are now covered;
  `includedPrompts`, `excludedPrompts`, `includedResults`, `excludedResults`, `isCpsProsecuted`,
  `childSubscriptions` and `userGroup` remain **UNSPECIFIED** — a port could get any of them wrong
  and this corpus would not notice.
* **`resultData` shapes** — durations, next hearings, financial impositions — are not grafted here;
  building that operator needs court-register result fixtures the legacy does not have.
* **One fidelity risk in the classifier**: `pattern` is a JavaScript `RegExp` in the recorder and a
  Java regex in the port. The bundle's patterns use only constructs both dialects share, but that is
  a residual risk rather than a proof.

---

## 7. Verdict

| Question the task asks | Answer |
|---|---|
| Zero unexplained differences? | **Yes** — 381/381 cases, every difference claimed by exactly one C-row. |
| Every difference claimed by exactly one row? | **Yes** — asserted, not assumed; a doubly-claimed divergence fails. |
| Every registered claim cites a real row? | **Yes** — checked against `doc/DEFECT-FIXES.md` in the suite. |
| Every content-changing fix appears in the diff set? | **18 of 19.** C20's trigger is proven unreachable from a hearing payload, measured by three recordings rather than argued. |
| Corpus extended rather than claim weakened? | **Yes** — 351 → 381 cases, five new operators, one new comparison; all 351 earlier recordings byte-identical. |
| Audit runs in `./gradlew build`? | **Yes** — untagged, ~2 s, no container. |
| Port defects found? | **Two**, both fixed red/green (`805599c`, `360d29a`); C10 and C11 amended to record what the audit found. |
