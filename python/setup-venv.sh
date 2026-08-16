#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PYTHON_BIN="${PYTHON_BIN:-python3}"
if [[ ! -x "$ROOT/python/.venv/bin/python" ]]; then
  "$PYTHON_BIN" -m venv "$ROOT/python/.venv"
fi
"$ROOT/python/.venv/bin/pip" install -q -r "$ROOT/python/requirements.txt"
echo "Radio Python environment ready: $ROOT/python/.venv"
