# Vendored outbound contract — provenance

Location: `src/main/resources/contracts/progression/` — MAIN resources deliberately, because this
bundle is the runtime authority for the C29 pre-send validation as well as the test-side
classification authority. Tests read the same classpath location; there is no second copy.

- `courtRegister*.json` (12 files): extracted verbatim from `cpp-platform-core-domain`
  git tag `v17.103.13` (tag commit `1e593bcb5f4929942f3ebdfa2246af2ccfa50e03`), path
  `criminal-court-public-model/src/main/resources/json/schema/global/courtRegisterDocument/`.
  17.103.13 is the version progression compiles (`cpp-context-progression/pom.xml`
  `<coredomain.version>17.103.13</coredomain.version>`, dependency
  `uk.gov.moj.cpp.core.domain:criminal-court-public-model`). The same artefact ships as
  `criminal-court-public-model-17.103.13.jar`
  (sha256 `a971c0efb5b17068a9dbd48bbe9e4966362d70ea60cb26fd14ccdc8d99efd137`, as resolved under
  progression's `target/dependency/`).
- `courtsDefinitions.json`: same tag, path
  `criminal-court-public-model/src/main/resources/json/schema/global/courtsDefinitions.json` —
  vendored to close the `http://justice.gov.uk/core/courts/courtsDefinitions.json#/definitions/*`
  references (`positiveInteger`, `uuid`) the document schemas carry. Self-contained (its only
  `$ref` is internal).
- `definitions.json`: same tag, path
  `common-core-domain/src/main/resources/json/schema/definitions.json` (the
  `uk.gov.moj.cpp.core.domain:common-core-domain` artefact, also compiled by progression at
  17.103.13) — closes `http://justice.gov.uk/domain/core/common/definitions.json#/definitions/uuid`.
  Self-contained.
- `progression.add-court-register.json`: copied from
  `cpp-context-progression/progression-command/progression-command-api/src/raml/json/schema/`
  at `main` HEAD `79edf7cf3d`.

**Closure**: every external `$ref` across the bundle resolves to a file in this directory —
`http://justice.gov.uk/core/courts/courtRegisterDocument/<name>.json` → `<name>.json`,
`http://justice.gov.uk/core/courts/courtsDefinitions.json` → `courtsDefinitions.json`,
`http://justice.gov.uk/domain/core/common/definitions.json` → `definitions.json`. The validator is
configured with a URI-to-classpath mapping for exactly these three prefixes; nothing is fetched
over the network.

These files are the frozen contract the pre-send validator refuses against and the harness
classifies against. Re-vendor only when progression's `coredomain.version` moves, in the same
change that records the move in `doc/DEFECT-FIXES.md` / `doc/CHANGELOG.md`.
