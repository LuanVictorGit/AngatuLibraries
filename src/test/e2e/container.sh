#!/usr/bin/env bash
# Container E2E: the upgrade sequence of upgrade.sh inside Docker. One image per library JAR, one
# named volume at /data shared by every step, a memory limit like a small Coolify service, and the
# HEALTHCHECK must turn healthy on every start. Prints the container memory after each load.
#
# usage: container.sh <work-dir> <accounts> <deps-dir> <memory-limit> <jar> [<jar> ...]
set -euo pipefail

WORK="$1"; ACCOUNTS="$2"; DEPS="$3"; MEMORY="$4"; shift 4
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SEP=":"; native() { printf '%s' "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -m "$1"; };; esac
WORK="$(native "$WORK")"; DEPS="$(native "$DEPS")"; ROOT="$(native "$ROOT")"
JARS=(); for J in "$@"; do JARS+=("$(native "$J")"); done; set -- "${JARS[@]}"
SRC="$ROOT/src/test/java/br/com/angatusistemas/lib/e2e"
PORT=7802
VOLUME="angatu-e2e-data"
NAME="angatu-e2e"

rm -rf "$WORK"; mkdir -p "$WORK/context/classes" "$WORK/uds"
javac --release 21 -d "$WORK/context/classes" -cp "$1$SEP$DEPS/*" \
  "$SRC/Account.java" "$SRC/Note.java" "$SRC/E2ERoutes.java" "$SRC/E2EApp.java" "$SRC/E2EDriver.java"
cp -r "$ROOT/src/test/resources/public" "$WORK/context/classes/public"
cp -r "$DEPS" "$WORK/context/deps"
cp "$ROOT/src/test/e2e/Dockerfile" "$WORK/context/Dockerfile"

driver() { java -Djdk.net.unixdomain.tmpdir="$WORK/uds" -cp "$WORK/context/classes$SEP$DEPS/*" \
  br.com.angatusistemas.lib.e2e.E2EDriver "http://127.0.0.1:$PORT" "$WORK/state" "$@"; }

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker volume rm "$VOLUME" >/dev/null 2>&1 || true

step=0
for JAR in "$@"; do
  step=$((step + 1))
  cp "$JAR" "$WORK/context/angatu.jar"
  docker build -q -t "angatu-e2e:step$step" "$WORK/context" >/dev/null
  echo "== container step $step: $JAR"
  docker run -d --name "$NAME" -p "$PORT:8080" -m "$MEMORY" -v "$VOLUME:/data" "angatu-e2e:step$step" >/dev/null
  for i in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$NAME")" = healthy ] && break
    sleep 1
  done
  echo "   health: $(docker inspect -f '{{.State.Health.Status}}' "$NAME")"
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$NAME")" = healthy ] || { docker logs "$NAME" | tail -20; exit 1; }
  if [ "$step" = 1 ]; then driver seed "$ACCOUNTS"; fi
  driver verify "$ACCOUNTS"
  driver load 64 200 10000
  echo "   memory: $(docker stats --no-stream --format '{{.MemUsage}}' "$NAME")"
  driver verify "$ACCOUNTS"
  driver stop
  docker wait "$NAME" >/dev/null
  echo "   exit code: $(docker inspect -f '{{.State.ExitCode}}' "$NAME")"
  docker rm "$NAME" >/dev/null
done
docker volume rm "$VOLUME" >/dev/null
for i in $(seq 1 "$step"); do docker rmi "angatu-e2e:step$i" >/dev/null; done
echo "== container E2E passed over $step steps"
