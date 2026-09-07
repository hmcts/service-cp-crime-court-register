#!/usr/bin/env bash
#
# Container smoke: build the image, run it against the committed compose dependencies, require it to
# report readiness inside the 60-second budget (spec SC-101/SC-103, container half), and then run one
# operations command through the entrypoint that dispatches them (FR-016). Tears the stack down
# on every exit path, success or failure.
#
# This is the local equivalent of the "Container smoke" step in
# .github/workflows/ci-build-publish.yml; both run this same script, so the two cannot drift.
#
#   ./scripts/container-smoke.sh
#
# It proves the packaged artefact starts and answers, which no JUnit suite can: the *IT suites run
# inside the build's JVM and would still pass if the image were unbuildable.

set -euo pipefail

readonly READINESS_BUDGET_SECONDS=60
readonly DEPENDENCY_BUDGET_SECONDS=120
readonly READINESS_URL="http://localhost:8082/actuator/health/readiness"

# A project name of this script's own. Everything it creates — containers, network, volumes — is
# namespaced under it, so the teardown's `down --volumes` can only ever destroy what this script
# made. Without it the script would share the default project with a developer's own
# `docker compose up`, and a smoke run would silently delete their database volume.
readonly PROJECT_NAME="courtregister-smoke"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

compose() {
  docker compose --project-name "$PROJECT_NAME" "$@"
}

log() {
  printf '[container-smoke] %s\n' "$1"
}

teardown() {
  # Captured first: everything below overwrites $?, and the script's real outcome must survive the
  # cleanup rather than be replaced by it.
  local status=$?

  log "tearing down"
  if ! compose logs --no-color --tail 50 app; then
    log "WARNING: could not read the application container's logs"
  fi

  if ! compose down --volumes --remove-orphans; then
    log "FAIL: teardown left containers, networks or volumes behind"
    # A cleanup failure fails an otherwise green run: leftovers from this project poison the next
    # run, and a green tick over a stack that would not come down is a lie.
    if [ "$status" -eq 0 ]; then
      status=1
    fi
  fi

  exit "$status"
}
trap teardown EXIT

# Unconditional: the image is built from whatever sits in build/libs, and a jar left there by an
# earlier checkout would have this script smoke-testing code that is no longer in the tree.
log "building the application jar"
./gradlew bootJar

# The whole local stack, because the image now runs generation-enabled against it: `wiremock` is
# systemdocgenerator, notificationnotify and Azure App Configuration, `fileservice-postgres` is the
# framework file service's database and `artemis` carries `public.event`.
log "starting dependencies"
compose up --detach postgres servicebus-emulator wiremock fileservice-postgres artemis

# Only these two are waited on, and the readiness policy is why. `postgres` is a readiness input, so
# the pod cannot report UP without it; `wiremock` answers the flag read, so check-flag cannot get an
# answer without it. The broker is never a readiness input (spec FR-011) and the file-service
# component answers UP between runs without asking, both pinned by `e2e/ReadinessPolicyIT`, so
# waiting on either would only make this script slower than the thing it is testing.
for dependency in postgres wiremock; do
  log "waiting for ${dependency} to report healthy (budget ${DEPENDENCY_BUDGET_SECONDS}s)"
  deadline=$((SECONDS + DEPENDENCY_BUDGET_SECONDS))
  until [ "$(docker inspect --format '{{.State.Health.Status}}' \
      "$(compose ps --quiet "$dependency")")" = "healthy" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      log "FAIL: ${dependency} did not become healthy within ${DEPENDENCY_BUDGET_SECONDS}s"
      exit 1
    fi
    sleep 2
  done
done

log "building the application image"
compose build app

log "starting the application container"
compose up --detach app

log "polling ${READINESS_URL} (budget ${READINESS_BUDGET_SECONDS}s)"
deadline=$((SECONDS + READINESS_BUDGET_SECONDS))
until curl --silent --fail --max-time 2 "$READINESS_URL" | grep -q '"status":"UP"'; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: readiness did not report UP within ${READINESS_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 2
done

log "PASS: readiness reported UP within the ${READINESS_BUDGET_SECONDS}s budget"

# The other half of what the image has to do. A deployed pod serves the actuator and nothing else,
# so the only way support regenerates a date, resends a batch's failed recipients or reads the
# cutover flag is `kubectl exec ... -- ./startup.sh <command>` (FR-016, research 13) - and that path
# is in the entrypoint, not in the application, so no JUnit suite covers it. What is proved here is
# what only the built image can prove: the five names reach CliMain out of the fat jar rather than
# starting a second application, the script is executable at the path the runbooks name, and the
# code the command answered with is the code the container exits on.
#
# `check-flag` is the one to run: it reads and changes nothing, so a smoke run cannot leave a batch
# or an e-mail behind it.
#
# The reading is taken through the REAL reader, with no mode override at all. It used to need
# `COURTREGISTER_GENERATION_FLAG_MODE=STUB`, because the live reader authorises its App Configuration
# read on the pod's workload identity - AZURE_CLIENT_ID, the tenant and the projected federated token
# - and a compose container holds none of the three; a bearer credential is refused a plain-HTTP URL
# by the SDK before a socket is opened, so pointing it at the WireMock stub was not an option either.
# `courtregister.feature.credential=local-test`, which docker-compose.yml sets on `app`, swaps that
# identity for a published pair the stub does not check and leaves everything else deployed. So what
# this step now asserts is the whole path a runbook uses: the dispatch out of the fat jar, the
# deployed reader, the deployed SDK client, the key in the path, the label in the query and the
# fail-closed reading of the answer.
log "running check-flag through the entrypoint"
# In the `if` deliberately: errexit does not apply to a condition, so a non-zero code is read and
# reported here rather than ending the script with no line saying which command failed. `--no-TTY`
# because CI has no terminal to allocate and `docker compose exec` insists on one by default.
if cli_output=$(compose exec --no-TTY app ./startup.sh check-flag 2>&1); then
  cli_status=0
else
  cli_status=$?
fi

if [ "$cli_status" -ne 0 ]; then
  log "FAIL: startup.sh check-flag exited ${cli_status}, and 0 is the only code a flag that answers"
  log "      carries - 1 is a refusal and 2 is a flag nobody could read"
  printf '%s\n' "$cli_output" | grep -E '^(flag|command)=' || printf '%s\n' "$cli_output" | tail -5
  exit 1
fi

# Exit 0 alone is not the whole assertion: a script that dispatched nothing and returned would also
# be 0, and the line is what a runbook step greps for.
if ! printf '%s\n' "$cli_output" | grep -q '^flag=ON$'; then
  log "FAIL: startup.sh check-flag exited 0 without printing the reading a runbook step reads"
  printf '%s\n' "$cli_output" | tail -5
  exit 1
fi

log "PASS: startup.sh check-flag printed flag=ON and exited 0"
