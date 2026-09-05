# Quickstart: Consolidate the progression court-register leg

## Local dependencies

```bash
docker compose up -d postgres servicebus-emulator artemis fileservice-postgres wiremock sdg-echo
```

- `postgres` — the service's store (Flyway V1 + V2 on first start), on 5432.
- `servicebus-emulator` (+ its SQL Server companion) — `courtregister.requests`.
- `artemis` — an Artemis broker (`apache/activemq-artemis`, pinned) with `public.event` created as a
  multicast address, on 61616; console on 8161 (admin/admin).
- `sdg-echo` — the helper that closes the loop: it watches WireMock's request journal and publishes
  `public.systemdocgenerator.events.document-available` onto `public.event` after each
  `generate-document`, carrying back the `sourceCorrelationId` and `payloadFileServiceId` the
  service sent, so the event-driven completion path runs locally without a real SDG.
- `fileservice-postgres` — a Postgres on 5433 seeded with `docker/fileservice/init.sql`, which is
  the vendored file-service liquibase DDL
  (`specs/002-consolidate-progression-leg/contracts/fileservice/`, changesets 001–006) as plain DDL.
- `wiremock` — on 8089: mappings for systemdocgenerator (command 202, query document),
  notificationnotify (202) and the App Configuration `kv` endpoint, with the flag ON by default.
  See `docker/wiremock/README.md`.

## Run the service with generation enabled

```bash
COURTREGISTER_PAYLOAD_MODE=STUB COURTREGISTER_REFERENCEDATA_MODE=STUB \
COURT_REGISTER_SYSTEM_USER_ID=00000000-0000-0000-0000-000000000000 \
COURTREGISTER_GENERATION_ENABLED=true \
FILESERVICE_DATASOURCE_URL=jdbc:postgresql://localhost:5433/fileservice \
FILESERVICE_DATASOURCE_USERNAME=fileservice FILESERVICE_DATASOURCE_PASSWORD=fileservice \
SYSTEMDOCGENERATOR_BASE_URL=http://localhost:8089 NOTIFICATIONNOTIFY_BASE_URL=http://localhost:8089 \
APPCONFIG_ENDPOINT=http://localhost:8089 STACK_LABEL=LOCAL \
CR_EMAIL_TEMPLATE_ID=11111111-1111-1111-1111-111111111111 \
ARTEMIS_BROKER_URL=tcp://localhost:61616 ARTEMIS_USER=admin ARTEMIS_PASSWORD=admin \
./gradlew bootRun
```

Startup refuses if generation is enabled and any of the file-service datasource, the flag
configuration, the SDG/NN endpoints or the template id is missing, or if the zone is not
`Europe/London`.

## Drive the flow end to end

```bash
# 1. publish a command (the 001 helper) — the service records a RECORDED row
./scripts/publish-command.sh fixtures/hearing-with-surviving-youth-defendant.json

# 2. run the job now instead of waiting for 18:00 London (flag is ON in the WireMock stub)
docker compose exec app ./startup.sh generate-register --date "$(date +%F)"

# 3. watch the batch: PENDING → GENERATING → GENERATED (SDG stub echoes document-available) → NOTIFIED
docker compose exec app ./startup.sh list-batches --date "$(date +%F)"

# 4. flip the flag off and show the gate
curl -X PUT http://localhost:8089/flag/off
docker compose exec app ./startup.sh generate-register --date "$(date +%F)"   # refuses: flag OFF
docker compose exec app ./startup.sh check-flag                                 # OFF
docker compose exec app ./startup.sh generate-register --date "$(date +%F)" --ignore-flag

# 5. flag back on (either form; they set the same WireMock scenario state)
curl -X PUT http://localhost:8089/flag/on
curl -X PUT http://localhost:8089/__admin/scenarios/CourtRegisterServiceFlag/state -d '{"state":"Started"}'
curl -s http://localhost:8089/__admin/scenarios      # which state the flag is in now
```

The shorthand is `/flag/off` and `/flag/on`, NOT `/__admin/flag/off` as the plan first sketched:
WireMock reserves the whole `/__admin` prefix for its own admin API and never consults the stub
mappings there, so a mapping registered under it is unreachable (it answers 404 from the admin
router — verified against `wiremock/wiremock:3.13.2`). The second form above is WireMock's real
scenario admin endpoint, which is what the shorthand drives.

## Tests

```bash
./gradlew test --tests '*RegisterStoreIT' '*PdfPayloadMapperTest' '*DefendantTypeResolverTest'
./gradlew test --tests '*FileServicePayloadStoreIT' '*DocumentEventListenerIT'
./gradlew test --tests '*GenerationEndToEndIT' '*FlagGateEndToEndIT' '*GenerationFailureEndToEndIT'
./gradlew build            # everything, including PMD/Checkstyle/JaCoCo gates
./scripts/container-smoke.sh   # image ready < 60 s with generation enabled against compose stubs
```

## Recording the progression goldens (one-off, outside this repo)

See research §6. In a local, uncommitted module of `cpp-context-progression` at `79edf7cf3d`, run
the recording harness over the 001 recorded documents; copy the output to
`src/test/resources/goldens/progression/` with its `PROVENANCE.md`. `PdfPayloadMapperTest` and
`DefendantTypeResolverTest` fail until the goldens are present.
