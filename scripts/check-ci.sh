#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/check-env.sh"

run_gate() {
    local label="$1"
    shift
    echo "== InstaGene: $label =="
    if ! "$@"; then
        echo "Local checks failed: $label. Fix the failure before committing." >&2
        exit 1
    fi
}

run_gate "fresh full test suite and build" bash "$repo_root/scripts/check-build.sh"
run_gate "strict documentation build" bash "$repo_root/scripts/check-docs.sh"
run_gate "host-supported distribution checks" bash "$repo_root/scripts/check-distributions.sh"
run_gate "commit gate integration tests" "$INSTAGENE_PYTHON" "$repo_root/scripts/test-hooks.py"
echo '== All local checks passed =='
