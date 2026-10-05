#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

package_type="${1:-}"
case "$package_type" in
    DEB|MSI|DMG) ;;
    *)
        echo "Usage: $0 {DEB|MSI|DMG}" >&2
        exit 1
        ;;
esac

echo "Building InstaGene native package ($package_type)..."
./gradlew :app-gui:jpackage "-PjpackageType=$package_type" --console=plain --info --stacktrace
echo "Package build successful ($package_type)."
