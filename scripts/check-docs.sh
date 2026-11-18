#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/check-env.sh"

if ! "$INSTAGENE_PYTHON" -c 'import mkdocs' >/dev/null 2>&1; then
    echo "MkDocs is not installed for this Python interpreter." >&2
    echo "Install the dependencies with: \"$INSTAGENE_PYTHON\" -m pip install -r docs/requirements.txt" >&2
    echo 'Set INSTAGENE_PYTHON or git config --local instagene.python to select an interpreter.' >&2
    exit 1
fi

"$INSTAGENE_PYTHON" -m mkdocs build --strict --site-dir site
test -f site/index.html
echo "MkDocs strict build passed."
