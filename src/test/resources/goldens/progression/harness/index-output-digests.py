#!/usr/bin/env python3
"""Stamp every golden's own sha256 into INDEX.json as `outputSha256`.

`GoldenRecorder` records the goldens and writes `INDEX.json` with `inputDigests` - the sha256 of
each recorded *input* it read - so a changed input can be found rather than only detected. It
digests nothing it wrote, which left `PROVENANCE.md`'s "every golden, its source, its digest"
claim only two thirds true: a golden edited by hand after the recording would have gone unnoticed.

This is the step that closes that. It is deliberately separate from the recorder: the recorder
needs a `cpp-context-progression` checkout and four jars from it (see README.md), this needs only
the files already in the tree, so the digests can be re-checked by anyone with a clone.

Run it after every re-record, from anywhere:

    python3 src/test/resources/goldens/progression/harness/index-output-digests.py

It rewrites `../INDEX.json` in the same canonical form the recorder writes (keys sorted, two-space
indent, one trailing newline), so re-running it on an unchanged tree is a no-op. `corpusDigest` is
NOT touched: it is the manifest digest of `inputDigests` and stays a statement about the inputs.

Exits non-zero if the goldens on disk and the goldens named in `INDEX.json` are not the same set,
which is the check that makes the count mean something.
"""

import hashlib
import json
import pathlib
import sys

# The three arrays whose entries name a golden file. An entry with `golden: null` is a recorded
# refusal or a skip - there is no file to digest, and it must not carry a digest either.
GOLDEN_ARRAYS = ("pdfPayloadDocuments", "pdfPayloadBatches", "defendantType")

# Where the golden files live, relative to the goldens directory. `defendant-type/synthetic/` is
# NOT here: those six files are authored inputs, not recordings, and the recorder digests them
# into `inputDigests` like every other input it reads.
GOLDEN_DIRECTORIES = ("pdf-payload", "defendant-type")

HARNESS = pathlib.Path(__file__).resolve().parent
GOLDENS = HARNESS.parent
INDEX = GOLDENS / "INDEX.json"


def sha256_of(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def on_disk():
    """Every golden file, as a path relative to the goldens directory. Top level only."""
    found = set()
    for directory in GOLDEN_DIRECTORIES:
        for path in sorted((GOLDENS / directory).glob("*.json")):
            found.add(path.relative_to(GOLDENS).as_posix())
    return found


def main():
    index = json.loads(INDEX.read_text(encoding="utf-8"))

    indexed = {}
    for array in GOLDEN_ARRAYS:
        for entry in index[array]:
            golden = entry.get("golden")
            if golden is None:
                # Idempotence: a golden that stops being recorded must not keep a stale digest.
                entry.pop("outputSha256", None)
                continue
            if golden in indexed:
                print("golden named twice in INDEX.json: {}".format(golden), file=sys.stderr)
                return 1
            indexed[golden] = array
            entry["outputSha256"] = sha256_of(GOLDENS / golden)

    missing = sorted(set(indexed) - on_disk())
    unindexed = sorted(on_disk() - set(indexed))
    if missing or unindexed:
        for golden in missing:
            print("indexed but not on disk: {}".format(golden), file=sys.stderr)
        for golden in unindexed:
            print("on disk but not indexed: {}".format(golden), file=sys.stderr)
        return 1

    INDEX.write_text(
        json.dumps(index, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    for array in GOLDEN_ARRAYS:
        print("{:<20}: {}".format(array, sum(1 for g, a in indexed.items() if a == array)))
    print("{:<20}: {}".format("outputSha256 total", len(indexed)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
