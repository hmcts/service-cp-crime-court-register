# Changeset 008, deployed but not vendored

The deployed `fileservice` database carries one Liquibase changeset that no local clone of
`framework-libraries` holds, so there is no XML file beside the others for it. This note records it
by name so that a reader comparing `contracts/fileservice/` against a live `\d content` is not
surprised by an index the vendored changesets do not create.

| Field | Value |
|---|---|
| Changeset id | `008-add-index-on-deleted-at-column-in-content-table` |
| Applied | **2025-08-04** (per the deployed `databasechangelog` table) |
| What it creates | An index `content_deleted_at_index` on `content.deleted_at` |
| Vendored here? | **No.** The local `framework-libraries` clone (`58aad8664`, 2023-12-22) predates it, so the changeset XML is not available to copy |

**It does not affect this service.** `FileServicePayloadStore` issues two inserts - one into
`metadata` and one into `content` - and reads neither `deleted` nor `deleted_at`. An index on
`deleted_at` changes the plan of no statement this service makes, and adds no column, constraint or
default to either table. The `FileServicePayloadStoreIT` Testcontainers seed is therefore still a
faithful copy of the schema the two inserts run against, index aside.

**If a later increment needs it**, vendor it from a `framework-libraries` clone newer than
`58aad8664` rather than hand-writing the XML: the file name and the changeset id have to match the
deployed `databasechangelog` row, or Liquibase would treat a re-vendored copy as a new changeset.

Recorded 2026-09-07 alongside the T005 verification in `../README.md`.
