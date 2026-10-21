#!/usr/bin/env bash
# Build a *self-contained* APK from a locally generated portable offline page.
# This is intentionally a LOCAL process: it must not publish proprietary assets.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 1 ]]; then
  echo "Usage: bash android/package-offline.sh /path/to/isaac.html" >&2
  echo "First generate the HTML with: python scripts/recomp/assets/portable.py offline .scratch/game-dist .scratch/isaac.html" >&2
  exit 2
fi
INPUT="$(realpath "$1")"
if [[ ! -f "$INPUT" ]]; then
  echo "Input HTML does not exist: $INPUT" >&2
  exit 2
fi
if [[ $(wc -c < "$INPUT") -lt 131072 ]]; then
  echo "Input is too small to contain an Isaac offline game." >&2
  exit 2
fi

DEST="$ROOT/android/app/src/main/assets/offline.html"
if [[ -e "$DEST" ]]; then
  echo "Refusing to overwrite an existing locally packaged game: $DEST" >&2
  exit 2
fi
mkdir -p "$(dirname "$DEST")" "$ROOT/.scratch"
# This copy is ignored by git and cleaned on both success and failure.
trap 'rm -f "$DEST"' EXIT
cp -- "$INPUT" "$DEST"

# Verify that this is the standalone portable format (not the online shell).
python3 - "$DEST" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
with p.open("rb") as f:
    first = f.read(1024).lower()
    if b"<!doctype html" not in first and b"<html" not in first:
        raise SystemExit("Input is not HTML")
    marker = b"window.__isaacPortableData"
    pending = b""
    found = False
    while block := f.read(128 * 1024):
        segment = pending + block
        if marker in segment:
            found = True
            break
        pending = segment[-len(marker):]
    if not found:
        raise SystemExit("Input is not a portable offline HTML (missing embedded payload marker)")
PY

# Reuse an existing local JDK/SDK; don't require a globally installed Gradle.
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
  echo "Android SDK platform 36 is not installed or ANDROID_HOME is incorrect." >&2
  echo "Set ANDROID_HOME=/path/to/sdk and install platforms;android-36." >&2
  exit 2
fi
if [[ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]]; then
  echo "Android SDK Build Tools 36.0.0 is missing under $ANDROID_HOME." >&2
  echo 'Install with sdkmanager "build-tools;36.0.0".' >&2
  exit 2
fi
GRADLE_BIN="${GRADLE_BIN:-$ROOT/android/gradlew}"
if ! command -v "$GRADLE_BIN" >/dev/null 2>&1; then
  echo "Cannot execute Gradle: $GRADLE_BIN" >&2
  echo "Run the repository wrapper at android/gradlew or set GRADLE_BIN explicitly." >&2
  exit 2
fi
echo "Using JAVA_HOME=${JAVA_HOME:-<system-java>}"
echo "Using ANDROID_HOME=$ANDROID_HOME"
echo "Using Gradle executable=$GRADLE_BIN"
"$GRADLE_BIN" -p "$ROOT/android" --no-daemon :app:assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
unzip -Z1 "$APK" | grep -Fx 'assets/offline.html' >/dev/null || {
  echo "Offline game HTML was not packaged into the APK!" >&2
  exit 1
}
OUTPUT="$ROOT/.scratch/isaac-self-contained-offline-debug.apk"
cp -- "$APK" "$OUTPUT"
echo "Offline bundled APK: $OUTPUT"
echo "Do not redistribute the APK without the game's rights holder's permission."
