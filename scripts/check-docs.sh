#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

python3 -m pip install -r docs/requirements.txt
python3 -m mkdocs build --strict --site-dir site
test -f site/index.html
echo "MkDocs strict build passed."
