# Contracts — increment 002

This increment adds **consumed** platform contracts (constitution Principle III, v3.0.0). None is
owned here; each is vendored so adapter tests assert against the exact shape the platform publishes,
and so a change upstream shows up as a failing test rather than a runtime surprise.

| Directory | Files | Source (local clone, commit, date) | Used by |
|---|---|---|---|
| `systemdocgenerator/` | `systemdocgenerator.generate-document.json` (command body: required `templateIdentifier`, `conversionFormat` ∈ pdf/thymeleaf/csv, `payloadFileServiceId`; optional `sourceCorrelationId`, `originatingSource`, `additionalInformation[]`; `additionalProperties: false`) · `systemdocgenerator.query.document.json` (query answer: `payloadFileServiceId`, `templateIdentifier`, `conversionFormat`, `requestedTime`, `documentFileServiceId?`, `generatedTime?`, `failedTime?`, `reason?`) · `public.systemdocgenerator.events.document-available.json` · `public.systemdocgenerator.events.generation-failed.json` | `cpp-context-system-doc-generator` `be26a916a` (2026-07-02): `systemdocgenerator-command/systemdocgenerator-command-api/src/raml/json/schema/`, `systemdocgenerator-query/systemdocgenerator-query-api/src/raml/json/schema/`, `systemdocgenerator-event/systemdocgenerator-event-processor/src/yaml/json/schema/` | `SystemDocGeneratorClientTest`, `DocumentEventListenerTest`, `GenerationReconcilerTest` |
| `notificationnotify/` | **API-side (what this service sends):** `notificationnotify.email.json` - the body of `POST /notifications/{notificationId}` under `application/vnd.notificationnotify.email+json` (required `templateId`, `sendToAddress`; optional `fileId`, `personalisation{}`, `replyToAddress[Id]`, `materialUrl`, `clientContext`; `additionalProperties: false`; **no `notificationId` in the body**, it is the path parameter). **Handler-side (the internal command it becomes):** `notificationnotify.command.send-email-notification.json` - the same fields plus `notificationId`, which the framework adds from the path before the command reaches the handler; kept here so the mapping from what we send to what runs is visible, but it is not a body this service ever posts. | `cpp-context-notification-notify` `992a414a` (2026-02-11): `notificationnotify-command/notificationnotify-command-api/src/raml/json/schema/` (API-side) and `notificationnotify-command/notificationnotify-command-handler/src/raml/json/schema/` (handler-side) | `NotificationNotifyClientTest` |
| `fileservice/` | `file-service-liquibase-db-changelog.xml` + changesets `001`–`006` (`metadata(file_id, metadata jsonb)`, `content(file_id, content bytea, deleted, date_deleted)`) | `framework-libraries` `58aad8664` (**2023-12-22** — the local clone is old; **verify against the deployed STE `fileservice` database before PH.02 ends**: task in tasks.md) : `file-service/file-service-liquibase/src/main/resources/liquibase/` | `FileServicePayloadStoreIT` (Testcontainers Postgres seeded with these changesets) |
| `appconfiguration/` | `feature-flag-value.schema.json` — the JSON value of an App Configuration feature flag setting as this service parses it (`id`, `enabled`, optional `description`, `conditions`) | Azure App Configuration feature-flag content type `application/vnd.microsoft.appconfig.ff+json;charset=utf-8` (documented shape; not from a local clone) | `AppConfigurationFlagReaderTest` |

REST paths, media types and ACLs (from the RAML / drl files in the same clones, not vendored):

- `POST {SDG}/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document`,
  `application/vnd.systemdocgenerator.generate-document+json`, 202; ACL `isSystemUser`.
- `GET {SDG}/systemdocgenerator-query-api/query/api/rest/systemdocgenerator/document/{payloadFileId}`,
  `application/vnd.systemdocgenerator.query.document+json`; ACL `isSystemUser`.
- `POST {NN}/notificationnotify-command-api/command/api/rest/notificationnotify/notifications/{notificationId}`,
  `application/vnd.notificationnotify.email+json`, 202; ACL includes System Users. The media type
  maps to the command `notificationnotify.send-email-notification`; the body is
  `notificationnotify.email.json` and carries no `notificationId`, because the id is the path
  parameter.
- Artemis topic `public.event`; JMS string property `CPPNAME` = event name; body = framework
  `JsonEnvelope` (`_metadata` + payload).
- App Configuration key `.appconfig.featureflag/CourtRegisterService`, label = stack.

Unchanged from 001 and still authoritative: the inbound `distribution-command.schema.json` and the
frozen register document under `src/main/resources/contracts/progression/` (v17.103.13).
