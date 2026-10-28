#!/bin/sh
# Командная строка консоли RAS (для cron и скриптов). Справка: ./rascli.sh help
DIR="$(cd "$(dirname "$0")" && pwd)"
NEED_FX=0
. "$DIR/find-java.sh"
exec "$JAVA" $RAS_JAVA_OPTS -cp "$DIR/ras-console.jar:$DIR/lib/*" ru.rasconsole.CliBoot "$@"
