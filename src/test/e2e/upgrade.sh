#!/usr/bin/env bash
# Upgrade E2E: one build of the E2E app, compiled against the FIRST library JAR, runs on the same
# database file under each JAR in turn (old -> new -> old). The first JAR seeds; every JAR verifies,
# runs a concurrent load and verifies again. Any broken invariant stops the script with exit 1.
#
# usage: upgrade.sh <work-dir> <accounts> <deps-dir> <jar> [<jar> ...]
set -euo pipefail

WORK="$1"; ACCOUNTS="$2"; DEPS="$3"; shift 3
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SEP=":"; native() { printf '%s' "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -m "$1"; };; esac
WORK="$(native "$WORK")"; DEPS="$(native "$DEPS")"; ROOT="$(native "$ROOT")"
JARS=(); for J in "$@"; do JARS+=("$(native "$J")"); done; set -- "${JARS[@]}"
SRC="$ROOT/src/test/java/br/com/angatusistemas/lib/e2e"
PORT="${PORT:-7801}"
BASE="http://127.0.0.1:$PORT"

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/uds"
javac --release 21 -d "$WORK/classes" -cp "$1$SEP$DEPS/*" \
  "$SRC/Account.java" "$SRC/Note.java" "$SRC/E2ERoutes.java" "$SRC/E2EApp.java" "$SRC/E2EDriver.java"
cp -r "$ROOT/src/test/resources/public" "$WORK/classes/public"

driver() { java -Djdk.net.unixdomain.tmpdir="$WORK/uds" -cp "$WORK/classes$SEP$DEPS/*" br.com.angatusistemas.lib.e2e.E2EDriver "$BASE" "$WORK/state" "$@"; }

step=0
for JAR in "$@"; do
  step=$((step + 1))
  echo "== step $step: $JAR"
  PORT="$PORT" ANGATU_DB_PATH="$WORK/database.db" java -Djdk.net.unixdomain.tmpdir="$WORK/uds" \
    -cp "$WORK/classes$SEP$JAR$SEP$DEPS/*" br.com.angatusistemas.lib.e2e.E2EApp > "$WORK/app-$step.log" 2>&1 &
  APP=$!
  trap 'kill $APP 2>/dev/null || true' EXIT
  driver wait
  if [ "$step" = 1 ]; then driver seed "$ACCOUNTS"; fi
  driver verify "$ACCOUNTS"
  driver load 64 200 10000
  driver verify "$ACCOUNTS"
  ls -l "$WORK"/database.db* | awk '{print "   " $5 " " $NF}'
  driver stop
  wait $APP || true
  trap - EXIT
done
echo "== upgrade E2E passed over $step steps"
