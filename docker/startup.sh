#!/usr/bin/env sh
# Add any startup requirements in here
logmsg() {
    SCRIPTNAME=$(basename $0)
    echo "$SCRIPTNAME : $1"
}

export LOCALJARFILE=$(ls ./build/libs/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
export DOCKERJARFILE=$(ls /app/*.jar 2>/dev/null | grep -v 'plain' | head -n1)

# The operations commands, run out of the same image rather than off an API this service does not
# have. There is no REST surface to regenerate a date, resend a batch's failed recipients or read
# the cutover flag through, deliberately (FR-016, constitution Principle III), so support reaches
# those through `kubectl exec ... -- ./startup.sh <command>`, which runs with the pod's own identity
# and network path and needs no data-plane credential of its own (research 13).
#
# The five names below are exactly `CliMain.COMMANDS`, and the two lists are one list: a name this
# script does not recognise starts the application instead, and a name CliMain does not recognise is
# answered with the list and a refusal. A first argument that is none of them leaves everything
# below this block exactly as it was, so a container started with no arguments - which is every
# deployed pod - reaches the unchanged `exec java -jar` further down.
CLI_MAIN=uk.gov.hmcts.cp.courtregister.batch.cli.CliMain
# A Boot 4 fat jar's manifest names JarLauncher, whose Start-Class is the application. Running a
# second main class out of the same archive is what PropertiesLauncher and `loader.main` are for:
# BOOT-INF/classes and BOOT-INF/lib are on the classpath it builds, so a command sees exactly the
# dependencies the application does. `java -jar` cannot do it, because the manifest's Main-Class
# wins over anything on the command line, and `-cp` alone cannot either, because a nested jar is
# not a classpath entry.
BOOT_LAUNCHER=org.springframework.boot.loader.launch.PropertiesLauncher

case "${1:-}" in
    generate-register|notify-register|list-batches|supersede-before|check-flag)
        if [ -f "$DOCKERJARFILE" ]; then
            CLIJARFILE=$DOCKERJARFILE
        elif [ -f "$LOCALJARFILE" ]; then
            CLIJARFILE=$LOCALJARFILE
        else
            # 2 and not 1, because CliMain's codes mean the same three things whether it was reached
            # or not: the command could not be run, which is a failure, and not the refusal a
            # runbook step must never retry as though it were one.
            logmsg "ERROR - No jarfile found. Unable to run the $1 command" >&2
            exit 2
        fi
        # To stderr, alone among this script's lines. What a command writes to stdout is
        # line-oriented and grepped by the runbook step that ran it, and a line of this script's own
        # in front of it would be a line an operator has to know to skip.
        logmsg "Running the $1 command from $CLIJARFILE" >&2
        # `exec`, so the code the process ends on is the command's own - 0 did it, 1 declined, 2
        # could not - with no shell left in between to replace it, and so an operator's Ctrl-C
        # reaches the JVM rather than orphaning it. Every argument is passed through untouched,
        # including the name, which is what CliMain dispatches on.
        exec java -cp "$CLIJARFILE" "-Dloader.main=$CLI_MAIN" "$BOOT_LAUNCHER" "$@"
        ;;
esac

# `exec` replaces this shell with the JVM, so the JVM becomes PID 1 and receives the container's
# SIGTERM directly. Without it the signal is delivered to the shell, which dies while the JVM is
# left to be killed outright a grace period later — so Spring's shutdown never runs, the consumer
# lifecycle controller is never stopped, and every delivery in flight is abandoned by a lock that
# simply expires instead of being settled. The whole point of an orderly stop is that a rolling
# deployment costs nothing.
if [ -f "$DOCKERJARFILE" ]; then
    logmsg "Running docker java jarfile $DOCKERJARFILE"
    exec java -jar "$DOCKERJARFILE"
elif [ -f "$LOCALJARFILE" ]; then
    logmsg "Running local java jarfile $LOCALJARFILE"
    exec java -jar "$LOCALJARFILE"
else
    logmsg "ERROR - No jarfile found. Unable to start application"
fi
