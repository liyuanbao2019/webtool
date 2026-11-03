#!/bin/sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$APP_DIR"
[ ! -f "$APP_DIR/app.env" ] || . "$APP_DIR/app.env"
JAVA=/apps/eoms/jdk1.8.0_361/bin/java
command -v "$JAVA" >/dev/null 2>&1 || { echo "Java not found" >&2; exit 1; }
JAR="$APP_DIR/complaint-auditor.jar"
[ -f "$JAR" ] || JAR="$APP_DIR/target/complaint-auditor.jar"
[ -f "$JAR" ] || { echo "JAR not found" >&2; exit 1; }
JAR=$(readlink -f "$JAR")
mkdir -p runtime data
LOCK="$APP_DIR/runtime/start.lock"
mkdir "$LOCK" 2>/dev/null || { echo "Another start is in progress (lock: $LOCK)" >&2; exit 1; }
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
trap 'exit 1' HUP INT TERM
# Match the exact -jar path, resolving relative paths against the process cwd.
find_pids() {
    for proc in /proc/[0-9]*; do
        [ -r "$proc/cmdline" ] || continue
        exe=$(readlink "$proc/exe" 2>/dev/null || true)
        case "$exe" in */java|*/java\ \(deleted\)) ;; *) continue ;; esac
        arg=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | awk 'nextjar {print; exit} $0 == "-jar" {nextjar=1}')
        [ -n "$arg" ] || continue
        case "$arg" in /*) candidate="$arg" ;; *) candidate="$proc/cwd/$arg" ;; esac
        resolved=$(readlink -f "$candidate" 2>/dev/null || true)
        [ "$resolved" != "$JAR" ] || echo "${proc##*/}"
    done
    return 0
}
pids=$(find_pids)
if [ -n "$pids" ]; then
    echo "Stopping existing service: $pids"
    for service_pid in $pids; do kill "$service_pid" 2>/dev/null || true; done
    count=0
    while [ -n "$(find_pids)" ] && [ "$count" -lt 30 ]; do sleep 1; count=$((count+1)); done
    remaining=$(find_pids)
    if [ -n "$remaining" ]; then
        echo "Graceful shutdown timed out; terminating: $remaining"
        for service_pid in $remaining; do kill -9 "$service_pid" 2>/dev/null || true; done
        sleep 1
    fi
    [ -z "$(find_pids)" ] || { echo "Old service could not be stopped" >&2; exit 1; }
fi
HOST=${HOST:-0.0.0.0}
PORT=${PORT:-8789}
nohup "$JAVA" -Duser.timezone=Asia/Shanghai -Xms64m -Xmx2048m -jar "$JAR" --host "$HOST" --port "$PORT" --data-dir "$APP_DIR/data" "$@" >> "$APP_DIR/runtime/stdout.log" 2>> "$APP_DIR/runtime/stderr.log" </dev/null &
service_pid=$!
echo "$service_pid" > "$APP_DIR/runtime/service.pid"
sleep 2
kill -0 "$service_pid" 2>/dev/null || { echo "Start failed; see runtime/stderr.log" >&2; exit 1; }
echo "Started PID $service_pid, configured port $PORT; logs: runtime/stdout.log and runtime/stderr.log"
