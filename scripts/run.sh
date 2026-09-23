#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

target="${1:-gui}"
shift || true

case "$target" in
    gui) task=":app-gui:runGui" ;;
    cli) task=":app-cli:runCli" ;;
    web) task=":app-web:runWeb" ;;
    *)
        echo "Unknown target '$target'. The engine is a library and has no run task; " >&2
        echo "choose one of: gui cli web (default gui)" >&2
        exit 1
        ;;
esac

echo "Starting InstaGene ($target)..."
./gradlew "$task" --console=plain --quiet "$@"