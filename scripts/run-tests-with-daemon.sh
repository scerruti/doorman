#!/usr/bin/env bash
set -euo pipefail
ROOT="$(pwd)"
DAEMON_PATH="$ROOT/src/main/resources/ble_daemon.py"
LOG="$ROOT/daemon.log"
PIDFILE="$ROOT/.daemon.pid"

python3 "$DAEMON_PATH" > "$LOG" 2>&1 & echo $! > "$PIDFILE"

for i in {1..30}; do
  if curl -s http://127.0.0.1:9001/admin/health | grep -q '"ok": true'; then break; fi
  sleep 0.5
done

"$ROOT/gradlew" test

kill "$(cat "$PIDFILE")" || true
rm -f "$PIDFILE"
