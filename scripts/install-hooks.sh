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

git -C "$ROOT" config --local core.hooksPath .githooks
echo "Git hooks installed from $ROOT/.githooks (core.hooksPath)."
echo "On every commit: pre-commit requires fully staged tracked files and runs fresh tests, the build, strict docs, local distribution checks, and hook tests."
echo 'For IDE commits, persist runtime paths with git config --local instagene.javaHome and instagene.python.'
echo 'Run bash scripts/check-ci.sh to validate the checkout without committing.'
echo "Commit-msg validates the commit message."
echo "Temporarily bypass with: git commit --no-verify"
