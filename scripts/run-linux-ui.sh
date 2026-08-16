#!/usr/bin/env bash
# Same window as run-command-center.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/run-command-center.sh" "$@"
