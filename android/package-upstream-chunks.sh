#!/usr/bin/env bash
# Package the pinned, precompiled boi-portable browser game as a private offline APK.
# Never run with proprietary assets on a public release worker.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 1 ]]; then
  echo "Usage: bash android/package-upstream-chunks.sh /path/to/local-boi-portable" >&2
  exit 2
fi
INPUT="$(realpath "$1")"
test -f "$INPUT/index.html" || { echo "Missing localized index.html" >&2; exit 2; }
python3 "$ROOT/scripts/android/fetch_upstream_chunks.py" --out "$INPUT" --verify-only

ASSETS="$ROOT/android/app/src/main/assets"
HTML="$ASSETS/offline.html"
PAYLOAD="$ASSETS/payload"
if [[ -e "$HTML" || -e "$PAYLOAD" ]]; then
  echo "Refusing to overwrite existing private game assets in $ASSETS" >&2
  exit 2
fi
mkdir -p "$ASSETS" "$PAYLOAD/c" "$ROOT/.scratch"
cleanup() { rm -f -- "$HTML"; rm -rf -- "$PAYLOAD"; }
trap cleanup EXIT
cp "$INPUT/index.html" "$HTML"
for src in "$INPUT"/c/*.bin; do
  dest="$PAYLOAD/c/$(basename "$src")"
  ln "$src" "$dest" 2>/dev/null || cp -- "$src" "$dest"
done

if [[ -z "${JAVA_HOME:-}" && -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi
if [[ -z "${ANDROID_HOME:-}" && -d /ossdata/android-sdk ]]; then
  export ANDROID_HOME=/ossdata/android-sdk
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME/platforms/android-36" ]]; then
  echo "Android SDK platform 36 not found. Set ANDROID_HOME." >&2
  exit 2
fi
if [[ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]]; then
  echo 'Android SDK build-tools;36.0.0 missing.' >&2
  exit 2
fi
GRADLE_BIN="${GRADLE_BIN:-$ROOT/android/gradlew}"
"$GRADLE_BIN" -p "$ROOT/android" --no-daemon --stacktrace :app:assembleDebug

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
unzip -Z1 "$APK" | grep -Fx 'assets/offline.html' >/dev/null
COUNT="$(unzip -Z1 "$APK" | grep -Ec '^assets/payload/c/(a[0-3]|b([0-9]|1[0-9]|2[0-7]))\.bin$')"
if [[ "$COUNT" != "32" ]]; then
  echo "APK contains $COUNT chunks, expected 32" >&2
  exit 1
fi
OUTPUT="$ROOT/.scratch/isaac-upstream-chunks-offline-debug.apk"
cp -- "$APK" "$OUTPUT"
echo "Private full offline APK: $OUTPUT"
echo "Do not publish game-filled APK to a public repository or public artifact store."
