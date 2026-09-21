# Proposal — write the Principle II red-run waiver into the constitution

**Status: UNRATIFIED. Nothing in this file is in force.** The constitution stands at 5.0.3,
unchanged. This is a proposal for the design owner to accept, amend or reject.

## Why it is being proposed

Principle II reads "Red → Green → Refactor for every behaviour change, without exception", while 48
of increment 005's 61 tasks landed with the test in the same commit as the code and no red run
recorded, under a waiver the design owner gave on 2026-09-20. That waiver lives in
`spec.md` assumption 18 and in this increment's tasks file, and nowhere else.

Two consequences, and they are the whole argument for writing it down:

- the `qa` reviewer gates on a convention the constitution states absolutely, so it cannot read the
  waiver and cannot tell a waived increment from a breach;
- a waiver that is honoured but unwritten is a precedent every later increment inherits by
  example, on nobody's authority.

Rejecting the proposal is a coherent answer too: the waiver then stays where it is, as a one-off
recorded in one increment's spec, and Principle II keeps its absolute wording.

## Why it is a proposal rather than a commit

It was written by an agent during increment 005's own review gate — the party under review
amending the rule it was being judged against, and asserting the design owner's name and date on
the amendment. The constitution is the design owner's document. It is also a **NON-NEGOTIABLE**
principle whose headline would move from "without exception" to "without exception — save…",
which sits on the MAJOR/MINOR boundary; the bump below is the author's reading, not a ruling.

The amendment procedure's step 3 — re-run `/speckit-analyze` on every in-flight feature spec — has
not been done, and increment 004 is in flight.

## The proposed text, verbatim

Ratifying means pasting the two blocks below into `.specify/memory/constitution.md` and bumping the
version line; nothing else in the file changes.

### Principle II, headline

Replace:

> Red → Green → Refactor for every behaviour change, without exception.

with:

> Red → Green → Refactor for every behaviour change, without exception — save
> the one thing a named, dated waiver may relax, which is the *recording* of the
> red run and is set out below.

### Principle II, new clause after the existing convention

> **The recording of the red run may be waived for a named increment, and
> nothing else about this principle may.** The convention above is evidence, not
> the discipline itself: what protects the register is the test, and what a red
> run protects is the *reader's* confidence that the test could have failed. A
> design owner may therefore waive, for **one named increment**, the recording of
> the red run and the commit order inside a test/implementation pair. The waiver:
>
> - is given by the design owner, dated, and written into that increment's
>   `spec.md` **and** recorded here, in this clause, naming the increment — an
>   increment whose waiver is not written here has none, whatever its spec says;
> - covers the *recording* only. Every task still gets its test, and no other
>   gate is relaxed by it: the coverage gate, the defect-fix pinning tests, the
>   golden gate and the differential audit all stand unchanged;
> - does not travel. It expires with the increment that names it, and a later
>   increment inherits the unwaived convention.
>
> What the `qa` reviewer judges in place of the ceremony is stated in the
> waiver: for increment 005 it is the coverage gate (LINE 0.88 / BRANCH 0.85,
> `config/**` excluded) and **behaviour coverage per endpoint** — allow and deny
> per group, every refusal code the endpoint can answer, and the flag rule where
> the endpoint has one.
>
> **Waivers given** (append-only):
>
> | Increment | Given by | Date | What was waived |
> |---|---|---|---|
> | `005-operations-rest-api` | design owner | 2026-09-20 | The red-run recording and the commit order inside a pair, from Phase 2 onwards (48 of 61 tasks). Judged instead: the coverage gate and behaviour coverage per endpoint. Recorded in `specs/005-operations-rest-api/spec.md` assumption 18 |

### Version line

Proposed as MINOR, 5.0.3 → 5.1.0: the principle gains a clause it has been operated under without
carrying, and the obligation on a waiver-free increment is exactly what it was. A reading that the
headline of a NON-NEGOTIABLE principle may not gain an exception without a MAJOR bump is equally
defensible, and is the design owner's to take.

`.claude/rules/workflow.md` and `.claude/agents/qa.md` both restate the red-run convention. Neither
needs changing under this proposal: the waiver is per-increment and named, and what those files
state is what applies without one.
