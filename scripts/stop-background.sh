#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/logs/app.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "No PID file found at $PID_FILE"
  exit 0
fi

pid="$(cat "$PID_FILE")"
if [[ -z "$pid" ]]; then
  echo "PID file is empty"
  rm -f "$PID_FILE"
  exit 0
fi

if kill -0 "$pid" 2>/dev/null; then
  echo "Stopping PID $pid"
  kill "$pid"
  sleep 2
  if kill -0 "$pid" 2>/dev/null; then
    echo "Process did not stop gracefully, forcing stop"
    kill -9 "$pid"
  fi
else
  echo "PID $pid is not running"
fi

rm -f "$PID_FILE"
