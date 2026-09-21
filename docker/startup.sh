#!/usr/bin/env sh
# Add any startup requirements in here
logmsg() {
    SCRIPTNAME=$(basename $0)
    echo "$SCRIPTNAME : $1"
}

export LOCALJARFILE=$(ls ./build/libs/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
export DOCKERJARFILE=$(ls /app/*.jar 2>/dev/null | grep -v 'plain' | head -n1)

# The entrypoint starts the application, full stop. It used to dispatch on a first argument as well,
# because the six operator actions were commands in this image and `kubectl exec ... -- ./startup.sh
# <command>` was the only way to reach one. They are the seven endpoints under `/operations/**` now
# (constitution Principle III), served by the pod itself behind `cp-auth-rules-filter` and
# `cp-audit-filter-springboot` - which is how an action comes to be authorised and audited at all,
# neither of which an exec into a pod ever was.
#
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
