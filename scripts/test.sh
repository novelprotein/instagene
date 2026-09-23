#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

target="${1:-all}"
shift || true

filter=()
case "$target" in
    engine) filter=(--tests "org.instagene.core.*") ;;
    cli)    filter=(--tests "org.instagene.app.cli.*") ;;
    gui)    filter=(--tests "org.instagene.app.gui.*") ;;
    web)    filter=(--tests "org.instagene.app.web.*") ;;
    all)    filter=() ;;
    *)
        echo "Unknown target '$target'. Choose one of: engine cli gui web all (default all)" >&2
        exit 1
        ;;
esac

echo "Running InstaGene tests ($target)..."
./gradlew :tests:test "${filter[@]}" --console=plain --quiet "$@"
echo "Tests passed."