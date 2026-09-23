#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

target="${1:-all}"
shift || true

case "$target" in
    engine) tasks=":engine:compileKotlin" ;;
    cli)    tasks=":app-cli:compileKotlin" ;;
    gui)    tasks=":app-gui:compileKotlin" ;;
    web)    tasks=":app-web:compileKotlin" ;;
    all)    tasks="compileKotlin compileTestKotlin" ;;
    *)
        echo "Unknown target '$target'. Choose one of: engine cli gui web all (default all)" >&2
        exit 1
        ;;
esac

echo "Compiling InstaGene ($target)..."
# shellcheck disable=SC2086
./gradlew $tasks --console=plain --quiet "$@"
echo "Compilation successful."