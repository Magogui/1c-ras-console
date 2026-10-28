#!/bin/sh
# Консоль администрирования RAS для Linux.
# Нужна Java 17+ с JavaFX: Axiom JDK Full, Liberica JDK Full или OpenJDK + OpenJFX.
# Путь к Java можно задать в JAVA_HOME, дополнительные параметры JVM - в RAS_JAVA_OPTS.
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/find-java.sh"
exec "$JAVA" --enable-native-access=javafx.graphics $RAS_JAVA_OPTS -jar "$DIR/ras-console.jar" "$@"
