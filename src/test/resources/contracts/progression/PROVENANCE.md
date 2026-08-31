# Vendored outbound contract — provenance

- `courtRegister*.json` (12 files): extracted verbatim from `cpp-platform-core-domain`
  git tag `v17.103.13`, path
  `criminal-court-public-model/src/main/resources/json/schema/global/courtRegisterDocument/`.
  17.103.13 is the version progression compiles (`cpp-context-progression/pom.xml`
  `<coredomain.version>17.103.13</coredomain.version>`, dependency
  `uk.gov.moj.cpp.core.domain:criminal-court-public-model`).
- `progression.add-court-register.json`: copied from
  `cpp-context-progression/progression-command/progression-command-api/src/raml/json/schema/`
  at `main` HEAD `79edf7cf3d`.
- These files are the frozen contract the pre-send validator and the harness classify
  against. Re-vendor only when progression's `coredomain.version` moves, in the same
  change that records the move in `doc/DEFECT-FIXES.md` / `doc/CHANGELOG.md`.
