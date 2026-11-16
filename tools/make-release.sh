#!/usr/bin/env bash
# Build the release APKs of rehome and its Wi-Fi helper, check that both are signed with the release key, and put
# them where the GitHub release upload takes them from (docs/releasing.md).
#
#   tools/make-release.sh [output dir]      (default: ~/.rehome/dist; never a folder inside the repo)
#
# It refuses to run on a working tree with changes (a release is built from a commit, nothing else) or without the
# four REHOME_* signing properties, and fails unless both APKs carry exactly one signer, CN=rehome, with the same
# certificate. It writes rehome.apk and rehome-wifi.apk (the names the installer downloads), copies named after
# each app's versionName, SHA256SUMS and BUILD.txt (commit, versions, certificate). It never prints a password.
set -euo pipefail

die() { echo "make-release: $*" >&2; exit 1; }

REPO=$(cd "$(dirname "$0")/.." && pwd -P)

OUT=${1:-$HOME/.rehome/dist}
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
inside_repo() {
  case "$1/" in "$REPO"/*) die "the output dir $1 is inside the repo; pick one outside it (default ~/.rehome/dist)." ;; esac
}
inside_repo "$OUT"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd -P)
inside_repo "$OUT"
cd "$REPO"

# A release is exactly a commit: no changed, staged or untracked files.
[ -z "$(git status --porcelain)" ] || die "the working tree has changes; commit or stash them first:
$(git status --short)"
COMMIT=$(git rev-parse HEAD)

# The signing key: all four properties, as environment variables or in Gradle's user properties.
PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
missing=()
for name in REHOME_STORE_FILE REHOME_STORE_PASSWORD REHOME_KEY_ALIAS REHOME_KEY_PASSWORD; do
  if [ -n "${!name:-}" ]; then continue; fi
  if [ -f "$PROPS" ] && grep -Eq "^[[:space:]]*$name[[:space:]]*[=:][[:space:]]*[^[:space:]]" "$PROPS"; then continue; fi
  missing+=("$name")
done
[ ${#missing[@]} -eq 0 ] || die "release signing is not set up: ${missing[*]} missing (environment or $PROPS)."

# apksigner from the Android SDK: $APKSIGNER, then the SDK's newest build-tools.
find_apksigner() {
  if [ -n "${APKSIGNER:-}" ]; then echo "$APKSIGNER"; return; fi
  local sdk_dir=""
  if [ -f local.properties ]; then sdk_dir=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1); fi
  local sdk found
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$sdk_dir" /opt/homebrew/share/android-commandlinetools; do
    [ -n "$sdk" ] || continue
    found=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
    if [ -n "$found" ]; then echo "$found"; return; fi
  done
}
APKSIGNER=$(find_apksigner)
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || die "apksigner not found: set ANDROID_HOME, or APKSIGNER to its path."

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH
fi

echo "Building $COMMIT (clean build, unit tests, lint, installer tests) ..."
./gradlew -q clean assembleRelease testReleaseUnitTest lintRelease
python3 -m unittest tools/test_rehome.py >/dev/null 2>&1 || die "the installer tests fail: python3 -m unittest tools/test_rehome.py"

APP=app/build/outputs/apk/release/app-release.apk
WIFI=wifi/build/outputs/apk/release/wifi-release.apk
[ -f "$APP" ] && [ -f "$WIFI" ] || die "assembleRelease did not leave $APP and $WIFI."

# Exactly one signer, CN=rehome, the same certificate on both (the Wi-Fi helper's permission is signature-only).
cert_of() {
  local apk=$1 out dn
  out=$("$APKSIGNER" verify --print-certs "$apk") || die "$apk does not verify."
  dn=$(printf '%s\n' "$out" | grep -E '^Signer #[0-9]+ certificate DN: ' || true)
  [ "$(printf '%s\n' "$dn" | grep -c .)" -eq 1 ] || die "$apk has $(printf '%s\n' "$dn" | grep -c .) signers, not 1."
  [ "$dn" = "Signer #1 certificate DN: CN=rehome" ] || die "$apk is not signed with the release key ($dn)."
  printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
APP_CERT=$(cert_of "$APP")
WIFI_CERT=$(cert_of "$WIFI")
[ -n "$APP_CERT" ] && [ "$APP_CERT" = "$WIFI_CERT" ] || die "the two APKs are signed with different certificates."

version_of() {  # versionName and versionCode from the build's output-metadata.json
  python3 -I -c 'import json, sys; e = json.load(open(sys.argv[1]))["elements"][0]; print(e["versionName"], e["versionCode"])' \
    "$(dirname "$1")/output-metadata.json"
}
read -r APP_VERSION APP_CODE <<<"$(version_of "$APP")"
read -r WIFI_VERSION WIFI_CODE <<<"$(version_of "$WIFI")"

if [ -f "$OUT/rehome-$APP_VERSION.apk" ] && ! cmp -s "$APP" "$OUT/rehome-$APP_VERSION.apk"; then
  echo "NOTE: replacing a different build of rehome $APP_VERSION in $OUT. If that one was published, bump"
  echo "      versionCode and versionName instead (docs/releasing.md)."
fi

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
TMP=$(mktemp -d "$OUT/.make-release.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
cp "$APP" "$TMP/rehome.apk"
cp "$APP" "$TMP/rehome-$APP_VERSION.apk"
cp "$WIFI" "$TMP/rehome-wifi.apk"
cp "$WIFI" "$TMP/rehome-wifi-$WIFI_VERSION.apk"
(cd "$TMP" && sha256 rehome.apk rehome-wifi.apk "rehome-$APP_VERSION.apk" "rehome-wifi-$WIFI_VERSION.apk" > SHA256SUMS)
cat > "$TMP/BUILD.txt" <<EOF
commit          $COMMIT
rehome          $APP_VERSION (versionCode $APP_CODE)
rehome-wifi     $WIFI_VERSION (versionCode $WIFI_CODE)
signer          CN=rehome
cert SHA-256    $APP_CERT
built           $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
for f in rehome.apk rehome-wifi.apk "rehome-$APP_VERSION.apk" "rehome-wifi-$WIFI_VERSION.apk" SHA256SUMS BUILD.txt; do
  mv -f "$TMP/$f" "$OUT/$f"
done

echo "Release $APP_VERSION in $OUT (signed CN=rehome, cert SHA-256 $APP_CERT):"
(cd "$OUT" && cat SHA256SUMS)
echo "Upload rehome.apk and rehome-wifi.apk to the GitHub release v$APP_VERSION (docs/releasing.md)."
