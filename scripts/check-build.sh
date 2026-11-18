#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/check-env.sh"

if ! java -version >/dev/null 2>&1; then
    echo 'Java is unavailable. Set JAVA_HOME to a JDK 21 installation, or run:' >&2
    echo '  git config --local instagene.javaHome "/path/to/jdk-21"' >&2
    exit 1
fi

# --rerun applies only to :tests:test: compile dependencies can remain cached.
bash "$repo_root/scripts/test.sh" all --rerun
bash "$repo_root/scripts/compile.sh" all build
