#!/usr/bin/env bash
# Command-center UI with USB serial or Bluetooth radio (Linux / Windows via Gradle).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"
if [[ ! -x "$ROOT/python/.venv/bin/python" ]]; then
  echo "Setting up the radio Python environment (once)…"
  bash "$ROOT/python/setup-venv.sh"
fi
export SALUTE_MESH_ROOT="$ROOT"
export SALUTE_PYTHON="$ROOT/python/.venv/bin/python"
exec ./gradlew :desktop:run "$@"
