#!/usr/bin/env bash
#
# Installs InstaGene's git hooks (test gate + commit message rules).
#
# Hooks live in .githooks/ and are wired up through core.hooksPath, so there is
# nothing to copy into .git/hooks and the hooks stay in version control.
#
# Usage:  ./scripts/install-hooks.sh
set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

git config core.hooksPath "$ROOT/.githooks"
echo "Git hooks installed from $ROOT/.githooks (core.hooksPath)."
echo "On every commit: pre-commit runs the build, strict docs, and local distribution checks."
echo "Commit-msg validates the commit message."
echo "Temporarily bypass with: git commit --no-verify"
