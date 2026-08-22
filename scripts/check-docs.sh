#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if ! python3 -c 'import mkdocs' >/dev/null 2>&1; then
    echo "MkDocs is not installed for this Python interpreter." >&2
    echo "Install the dependencies with: python3 -m pip install -r docs/requirements.txt" >&2
    exit 1
fi

python3 -m mkdocs build --strict --site-dir site
test -f site/index.html
echo "MkDocs strict build passed."
