#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

gradle=("$repo_root/gradlew" --console=plain)

check_zip_entries() {
    archive="$1"
    shift
    python3 - "$archive" "$@" <<'PY'
import sys
import zipfile

archive = sys.argv[1]
required = set(sys.argv[2:])
with zipfile.ZipFile(archive) as package:
    entries = set(package.namelist())
missing = sorted(required - entries)
if missing:
    raise SystemExit(f"{archive} is missing: {', '.join(missing)}")
PY
}

echo "== Checking portable GUI JAR =="
"${gradle[@]}" :app-gui:verifyStandaloneJar
test -f app-gui/build/distributions/instagene-gui.jar

echo "== Checking native file associations =="
"${gradle[@]}" :app-gui:verifyNativeFileAssociations

echo "== Checking CLI distribution ZIP =="
"${gradle[@]}" :app-cli:distZip
cli_zip="app-cli/build/distributions/instagene-cli.zip"
test -f "$cli_zip"
check_zip_entries "$cli_zip" \
    'instagene-cli/bin/app-cli' \
    'instagene-cli/bin/app-cli.bat'

case "$(uname -s)" in
    Linux*)
        command -v dpkg-deb >/dev/null 2>&1 || {
            echo "dpkg-deb is required for the local Linux DEB check." >&2
            exit 1
        }
        echo "== Checking Linux DEB =="
        "${gradle[@]}" :app-gui:jpackage -PjpackageType=DEB
        deb_count="$(find app-gui/build/jpackage/dist -maxdepth 1 -type f -name '*.deb' | wc -l)"
        test "$deb_count" -eq 1
        deb="$(find app-gui/build/jpackage/dist -maxdepth 1 -type f -name '*.deb' -print -quit)"
        dpkg-deb -I "$deb" | grep -qi '^ Package: instagene$'
        inspect_dir="$(mktemp -d)"
        cleanup_inspect_dir() {
            rm -rf "$inspect_dir"
        }
        trap cleanup_inspect_dir EXIT
        dpkg-deb -x "$deb" "$inspect_dir"
        test -x "$inspect_dir/usr/bin/instagene"
        mime_info="$inspect_dir/opt/instagene/lib/instagene-InstaGene-MimeInfo.xml"
        test -f "$mime_info"
        grep -q 'text/x-fasta' "$mime_info"
        dpkg-deb -e "$deb" "$inspect_dir/DEBIAN"
        grep -q 'xdg-mime install' "$inspect_dir/DEBIAN/postinst"

        echo "== Checking Linux app-image ZIP =="
        "${gradle[@]}" :app-gui:jpackageAppImageZip -PjpackageType=APP_IMAGE
        app_image_zip="app-gui/build/jpackage/instagene-app-image.zip"
        test -f "$app_image_zip"
        check_zip_entries "$app_image_zip" 'instagene' 'bin/InstaGene'
        trap - EXIT
        cleanup_inspect_dir
        ;;
    Darwin*)
        echo "Skipping DMG: native macOS packaging is covered by macOS CI."
        ;;
    MINGW*|MSYS*|CYGWIN*)
        echo "Skipping MSI: native Windows packaging is covered by Windows CI."
        ;;
    *)
        echo "Skipping native installer checks on unsupported host: $(uname -s)"
        ;;
esac

echo "Distribution checks passed."
