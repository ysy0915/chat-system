#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
PID_FILE="$LOG_DIR/app.pid"
OUT_FILE="$LOG_DIR/app.out"
ERR_FILE="$LOG_DIR/app.err"

mkdir -p "$LOG_DIR"

if [[ -f "$PID_FILE" ]]; then
  existing_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "$existing_pid" ]] && kill -0 "$existing_pid" 2>/dev/null; then
    echo "Service already running with PID $existing_pid"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

port_pid="$(lsof -t -iTCP:8080 -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
if [[ -n "$port_pid" ]]; then
  process_info="$(ps -p "$port_pid" -o args= 2>/dev/null || true)"
  if [[ "$process_info" == *"chat-system-project"* ]]; then
    echo "Service already running on port 8080 with PID $port_pid"
    echo "$port_pid" > "$PID_FILE"
    exit 0
  fi
fi

if ! find "$ROOT_DIR/target" -maxdepth 1 -name '*.jar' | grep -q .; then
  echo "No jar found in target/, building one..."
  (cd "$ROOT_DIR" && mvn -q -DskipTests package)
fi

JAR_PATH="$(find "$ROOT_DIR/target" -maxdepth 1 -name '*.jar' | sort | head -n 1)"
if [[ -z "$JAR_PATH" ]]; then
  echo "Unable to find a runnable jar"
  exit 1
fi

echo "Starting service from $JAR_PATH"
nohup java -jar "$JAR_PATH" >"$OUT_FILE" 2>"$ERR_FILE" &
new_pid=$!
echo "$new_pid" > "$PID_FILE"

sleep 3
if kill -0 "$new_pid" 2>/dev/null; then
  echo "Service started with PID $new_pid"
  echo "Logs: $OUT_FILE"
else
  echo "Service failed to start"
  rm -f "$PID_FILE"
  exit 1
fi
