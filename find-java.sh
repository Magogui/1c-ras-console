# Поиск Java для rasconsole.sh и rascli.sh (подключается через «.»).
# Порядок: JAVA_HOME, Axiom/Liberica Full в стандартных каталогах, java из PATH.
# NEED_FX=0 - JavaFX не нужна (командная строка).
JAVA=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi
if [ -z "$JAVA" ]; then
    for d in /opt/axiomjdk-full-* /opt/axiom-jdk-full-* /usr/lib/jvm/axiomjdk-java*-full* \
             /usr/lib/jvm/bellsoft-java*-full* /usr/lib/jvm/liberica*-full* /opt/bellsoft/liberica*-full*; do
        if [ -x "$d/bin/java" ]; then JAVA="$d/bin/java"; fi
    done
fi
if [ -z "$JAVA" ]; then
    JAVA="$(command -v java)"
fi
if [ -z "$JAVA" ]; then
    echo "Не найдена Java 17+. Установите Axiom JDK Full или Liberica JDK Full либо задайте JAVA_HOME." >&2
    exit 1
fi
if [ "${NEED_FX:-1}" != "0" ] && ! "$JAVA" --list-modules 2>/dev/null | grep -q '^javafx.controls'; then
    echo "В $JAVA нет JavaFX. Нужна сборка Java с JavaFX: Axiom JDK Full или Liberica JDK Full (вариант Full)." >&2
    echo "Укажите её в JAVA_HOME, например: JAVA_HOME=/opt/axiomjdk-full-17 ./rasconsole.sh" >&2
    exit 1
fi
