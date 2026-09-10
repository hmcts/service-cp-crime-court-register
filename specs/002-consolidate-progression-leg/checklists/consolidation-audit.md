# Consolidation Audit Checklist: the progression leg's second oracle

**Purpose**: record what the consolidation audit establishes, where the goldens it rests on came
from, and what remains owned outside this repository
**Created**: 2026-09-10 (T076)
**Feature**: [spec.md](../spec.md) · **Register**: [doc/DEFECT-FIXES.md](../../../doc/DEFECT-FIXES.md)

Increment 001 audited this service against the JavaScript function app. Increment 002 gave it a
**second oracle** — progression's own court-register leg — and this checklist is the record of that
audit. The distinction that matters: **it is not a check that was taken once.** T069 turned it into
assertions inside `DifferentialAuditTest`, so every one of the claims below is re-established by
`./gradlew build` and a corpus that quietly shrank, a golden that was edited, or a deviation nobody
registered fails the build rather than going unnoticed.

## What the audit establishes

- [x] **The 001 corpus reproduces by manifest digest.**
      `the_001_corpus_reproduces_the_digest_the_recording_left_on_it` rebuilds the manifest over the
      whole recorded corpus and compares it to the digest the recording left behind:
      `20fcb12324bf674d3b141b4fa822076f2ff56be1aad43531000d575aa649d924`, which is `INDEX.json`'s own
      `corpusDigest`. One digest over everything, so a single edited byte anywhere in the corpus
      moves it.
- [x] **All 177 goldens digest to their recorded `outputSha256`.**
      `every_recorded_golden_is_the_one_that_was_recorded` — 161 document goldens + 7 whole-batch
      goldens + 9 defendant-type goldens. Checked against the digest written *at recording time*
      rather than against a file, so a golden and a re-recorded digest cannot drift together.
- [x] **All 168 payload goldens reproduce from their recorded inputs.**
      `every_pdf_payload_golden_is_reproduced_from_its_recorded_input` — this port's mapper is run
      over each recorded input and its output compared to progression's recorded output.
- [x] **All 52 recorded refusals are refused here too.**
      `every_document_progression_refused_is_refused_here_too` — the documents progression's
      generator threw on rather than mapping produce no payload here either, under C29's rule. A
      port that mapped one of them would be inventing a register progression never produced.
- [x] **Nothing is quietly excluded from the comparison.**
      `takes nothing out of the comparison that the recorded corpus ever carried` — the counts are
      asserted (404 recorded inputs, 205 recorded documents, 381 corpus cases) so an audit cannot
      start passing by looking at less.
- [x] **Exactly one attributed deviation, and it is P10.**
      `every_deviation_from_the_progression_oracle_names_its_p_row` — two of the nine defendant-type
      goldens carry a thrown `NullPointerException` where this port answers `Applicant`
      (`synthetic__master-defendant-without-flags`, `synthetic__respondents-absent`); the other
      seven reproduce. Every deviation must be explained by **exactly one**
      `doc/DEFECT-FIXES.md` P row, and these two are explained by P10. A tenth answer that changed
      would arrive with no row to name it and fail the build.
- [x] **The register is read by the suite, not merely described by it.**
      `RegisteredDefectFixes.progressionLegRows()` is the catalogue the audit asks, and
      `registers nothing that is not a row of doc/DEFECT-FIXES.md` holds it to the file: every
      registered entry cites a row that exists. So P10 cannot be edited as prose alone — what the
      row claims is what the audit will accept.

## The goldens' provenance

- [x] Recorded by **executing progression's own code**, not by reading it — the recorder and the
      digest indexer are under `goldens/progression/harness/` and are deliberately *off* the test
      classpath.
- [x] `INDEX.json` is the manifest: every golden, its source, both digests, the counts and the
      clock date (`ageClockDate: 2026-09-05`). The counts this checklist quotes are its own, not a
      second tally.
- [x] `cases[].age` is the one clock-dependent field, and `PROVENANCE.md` says so.
- [x] Six of the defendant-type inputs are **authored, not recorded** (`defendant-type/synthetic/`)
      and are labelled as such, because three of the shapes P10 names reach no recorded case.
- [x] `PROVENANCE.md`'s Verification section now cites this audit alongside the one-off `diff -r`
      it recorded at recording time. The `diff -r` sentence stands: it is the record of what was
      done when the goldens were made, and this audit is what re-establishes it per build.

## Residue — owned outside this repository, tracked to conclusion

The rule these follow is the one C18, C28 and C34 already carry: an **owner** and a **trigger**,
never an assumption.

- [ ] **P6 — dead subscriptions and an orphan schema.** RETIRED by deleting the artefact in
      progression's retirement PR (design §10.6). Owner: the progression team, sequenced after the
      four conditions in §10.5(4). **Trigger: that PR merging.** Nothing in this repository can
      assert it, so `RETIRED` is the disposition agreed for the row and not an observation.
- [ ] **P7 — five-year-old informant-register residue.** Same PR, same owner, same trigger. This row
      had an owner and **no trigger at all** until T074 gave it one.
- [ ] **C18, C28, C34** — legacy-repo items, unchanged: the kill-switch wiring in the legacy
      triggers, its dead test file, and its packaging.
- [ ] **The SIT→STE replay gate** — a hard pre-cutover gate held outside the repo README.
- [ ] **Finding 12's counter, refused with its reason.** The design owner ruled that
      `CliMain.reported` keeps ERROR; Principle VI would ordinarily pair an ERROR with a metric an
      alert fires on, and this path is recorded as having none **because it can have none** — a
      command's JVM runs `WebApplicationType.NONE`, exposes no scrape endpoint, holds no push
      registry, and `ReportNotWritten` leaves the context before the catch is reached. A counter
      there would increment and die with the process. Recorded in `CliMain.reported`'s javadoc so
      the next reader does not re-open it as an oversight; revisit only if a command's JVM ever
      gains a way to export a meter.
- [ ] **Finding 14** — the 127-member batch's header comes from progression's `stream().findAny()`
      and is reproduced bug-for-bug. The golden reproduces, so nothing is wrong; if that behaviour
      is ever judged a defect it has no row of its own, and `PROVENANCE.md` calls it "the P4 shape"
      though P4's fix is the recipient union rather than the header.

## Notes

- Validation run 1 (2026-09-10, T076): every "what the audit establishes" and "provenance" item
  passes, re-established by the suite rather than by inspection. The residue items are unchecked by
  design — each is a thing this repository cannot assert, and an unchecked box with an owner and a
  trigger is the honest state.
- `1fe0285`'s commit body mis-cites this evidence as T075's. It is T076's; the commit is not
  rewritten, and this line is the correction.
