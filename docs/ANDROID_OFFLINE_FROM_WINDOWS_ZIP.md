# Build a fully offline Android APK from your Windows Isaac ZIP

**Target:** `Windows game ZIP → static WebAssembly (WASM) + archives → game-dist → isaac.html → APK`.

This guide applies to this fork of **the-browsing-of-isaac**, not the separate
hand-translated `npm run build:wasm` experiment. It documents both the usable
build commands and the **known blocking prerequisite**. Do not assume that a
plain Windows installation ZIP is sufficient to reproduce the exact existing
WASM port.

## 0. Read this before starting

- You must legally own your copy of **The Binding of Isaac: Repentance+**.
- Do not upload the proprietary ZIP, executable, game archives, resulting
  `isaac.html`, or populated APK to this public GitHub repository, its Actions
  artifacts, or its Releases.
- The public **Android Offline Loader APK** Action can compile only the empty
  local-game importer. A truly *self-contained* APK is built **locally** using
  `android/package-offline.sh`.
- **Most important caveat:** the recompilation pipeline is **version-bound** to
  `tools/isaac-ng.unpacked.exe`, whose expected SHA-256 is
  `5129DF723E645DAAEA59514394195F3EA1DCE1671BB0433D724648A845017200`.
  This is a custom *unpacked and patched* executable, not simply the Steam
  `isaac-ng.exe` renamed. See `docs/recomp-boot.md` for details. **This
  repository does not provide a verified one-command recipe to create that
  exact executable from every Steam release.** If you have only an ordinary
  Windows installation and do not have the matching prepared executable, you
  can complete asset inspection, but full WASM recompilation may be blocked.
- The lift uses a large Ghidra analysis and many source files. Use a
  workstation with ample RAM/disk (16 GB+ RAM recommended, tens of GB of
  free disk space) rather than expecting a 4 GB cloud VM to handle Ghidra's
  default ~10 GB Java heap. This is a resource guideline, not a measured
  minimum.

Commands below target **Ubuntu Linux or WSL2 Ubuntu**, using Bash. Do the
CPU-intensive work on a Linux filesystem (for example under `$HOME`), not
under `/mnt/c` if using WSL. If the Android SDK is already configured on this
same environment, reuse it; do not reinstall it blindly. The APK packaging
requires a Linux-accessible Gradle/JDK/SDK installation. A Windows-only SDK
cannot simply be assumed to work unchanged in WSL.

## 1. Clone the project

```bash
mkdir -p ~/projects
cd ~/projects
git clone --recursive https://github.com/FanFront/the-browsing-of-isaac.git
cd the-browsing-of-isaac
git submodule update --init --recursive
mkdir -p .scratch tools
```

## 2. Extract your Windows game ZIP and locate the game root

Set `ZIP_PATH` to the **real path** of your ZIP; the example is for WSL.
The extracted files and the original executable stay in ignored local paths.

```bash
export ZIP_PATH="/mnt/c/Users/YOUR_WINDOWS_USER/Downloads/isaac-windows.zip"
test -f "$ZIP_PATH" || { echo "Game ZIP not found: $ZIP_PATH"; exit 1; }
unzip -t "$ZIP_PATH"
mkdir -p .scratch/windows-extracted
unzip -q "$ZIP_PATH" -d .scratch/windows-extracted

# Locate the directory containing isaac-ng.exe.
find .scratch/windows-extracted -type f -iname "isaac-ng.exe" -print
# The next line picks the first match; inspect it if multiple copies exist.
export GAME_DIR="$(dirname "$(find .scratch/windows-extracted -type f -iname 'isaac-ng.exe' -print -quit)")"
test -f "$GAME_DIR/isaac-ng.exe"
test -d "$GAME_DIR/resources/packed"
echo "Game root: $GAME_DIR"
```

The `GAME_DIR` is the directory containing both `isaac-ng.exe` and
`resources/`. If your ZIP has extra nested folders, point it at the inner
directory. Check the ten archives used by the current game's bundling rules:

```bash
for a in animations config fonts graphics music rooms sfx videos afterbirth afterbirthp; do
  test -s "$GAME_DIR/resources/packed/$a.a" \
    && echo "OK  $a.a" || echo "MISSING  $a.a"
done
test -f "$GAME_DIR/resources/scripts/main.lua" || echo "MISSING main.lua"
test -f "$GAME_DIR/resources/scripts/enums.lua" || echo "MISSING enums.lua"
sha256sum "$GAME_DIR/isaac-ng.exe"
```

**Stop and investigate missing archives/scripts.** Different DLC versions,
partial installs or archive layouts can differ from the version expected by
this recompilation pipeline.

## 3. IMPORTANT: validate the prepared game executable

The source code uses hardcoded function addresses and a verified executable
hash. Never make `tools/isaac-ng.unpacked.exe` by renaming the original EXE
unless its bytes actually match the expected prepared target.

```bash
EXPECTED="5129DF723E645DAAEA59514394195F3EA1DCE1671BB0433D724648A845017200"
ORIGINAL="$(sha256sum "$GAME_DIR/isaac-ng.exe" | awk '{print toupper($1)}')"
printf 'Original ZIP EXE SHA-256: %s\nExpected prepared hash : %s\n' "$ORIGINAL" "$EXPECTED"
```

**If the original hash differs:** do **not** copy it to `tools/` and proceed.
The documentation (`docs/recomp-boot.md`) describes custom patches and a
version-specific target. The source checkout doesn't contain a complete
general-purpose conversion/repatch recipe. Obtain the correct,
**personally prepared** version from your own installation using the upstream
port's documented, version-matched workflow, or acquire an already built
`.scratch/game-dist`/`isaac.html` that you have rights to use. Merely
changing the hash constant or deleting validation is **not** a safe fix.

**Only when you possess a version-matched prepared executable**:

```bash
cp "/path/to/your/matching/isaac-ng.unpacked.exe" tools/isaac-ng.unpacked.exe
echo "$EXPECTED  tools/isaac-ng.unpacked.exe" | sha256sum -c -
```

Do not run later recompilation steps until that checksum returns `OK`.

## 4. Verify/reuse Android and Java tools

Previous Android setup may already supply these. Check them first:

```bash
# Reuse your Ubuntu host's JDK 21 and Android SDK without reinstalling them.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/ossdata/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
java -version
./android/gradlew --version
adb version
echo "ANDROID_HOME=$ANDROID_HOME"
test -d "${ANDROID_HOME:-/invalid}/platforms/android-36" && echo "SDK 36 present"
test -d "${ANDROID_HOME:-/invalid}/build-tools/36.0.0" && echo "Build Tools 36.0.0 present"
```

**Your existing setup is Android SDK 36 + OpenJDK 21. Reuse both.**
This fork now builds with AGP **8.10.1**, Gradle **8.11.1**, SDK platform
**36**, and Build Tools **36.0.0**. Do not install JDK 17 merely for this
project: Gradle 8.11.1 runs on JDK 21. The Java source/target compatibility
remains **17** so the Android code does not depend on Java 21 APIs.
The Android `compileSdk` is **36**, whereas `targetSdk` is deliberately
**35** until Android 16 behavior changes are tested; changing the SDK
installed on your build machine does not require raising `targetSdk`.

If `sdkmanager` is available and a component is missing, install only
the missing component:

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

**Ghidra 12.1 also uses JDK 21**, so the same OpenJDK 21 installation
can be used for Ghidra and Android Gradle; no JDK switching is needed.
The project now includes a **standard Gradle Wrapper** at
`android/gradlew` and pins **Gradle 8.11.1** with a SHA-256-verified
distribution download. Run `./android/gradlew --version` from the repository
root; it will download Gradle 8.11.1 into `~/.gradle/wrapper/dists` on
first use. There is **no need** for a globally installed `gradle` command,
`GRADLE_HOME` or `GRADLE_USER_HOME`. Your existing cached Gradle
**9.7.1** may remain untouched; don't use it for this AGP 8.10.1 project
because compatibility has not been verified.

## 5. Install/check native WASM build dependencies

The documented build needs Python 3.11+, Node.js 20+, Ghidra,
Emscripten 6.0.5+, a C++20 compiler, and optional Playwright/Chromium for
browser validation. Example Ubuntu packages (adapt to your distro):

```bash
sudo apt-get update
sudo apt-get install -y git unzip zip curl python3 python3-pip python3-venv \
  build-essential clang cmake ninja-build
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install pypcode capstone pillow brotli pefile
npm ci
node --version
python --version
clang++ --version
```

To install Emscripten if `emcc --version` is unavailable, for example:

```bash
git clone https://github.com/emscripten-core/emsdk.git "$HOME/emsdk"
cd "$HOME/emsdk"
./emsdk install 6.0.5
./emsdk activate 6.0.5
source ./emsdk_env.sh
cd ~/projects/the-browsing-of-isaac
emcc --version
```

Use Ghidra's official distribution and export the **actual**
`GHIDRA_HOME` directory where `support/analyzeHeadless` exists. Ghidra
requires Java 21; use the same OpenJDK 21 already configured
for your Android environment when running the exporter.

```bash
export GHIDRA_HOME="/path/to/ghidra_12.1.2_PUBLIC"
test -x "$GHIDRA_HOME/support/analyzeHeadless"
java -version                       # should report Java 21 for Ghidra
```

If you already have Emscripten, Ghidra and Python installed, reuse them
rather than cloning/installing again.

## 6. Create the asset bundle (can be tested before the WASM lift)

The asset bundle does **not** produce the game engine or `boot.wasm`.
It is still a useful, independently testable step.

```bash
python scripts/recomp/assets/bundle.py build "$GAME_DIR" .scratch/game-bundle
python scripts/recomp/assets/bundle.py check .scratch/game-bundle
```

The `bundle.py` rules only keep the ten expected archives, selected Lua
scripts, page assets and the save-path note. The complete ZIP may include
unknown files that are reported as unclassified and dropped. Inspect that
report carefully. After you are satisfied the tree is compatible, you may
repeat with `--strict`; this option **fails** on unclassified files, including
legitimate extras. Do not use it blindly as a universal test of validity.

## 7. Run Ghidra export and prepare the lift (ONLY after step 3 passes)

If your prepared EXE is hash-matched and your build environment is ready:

```bash
node scripts/recomp/export-all.mjs --stage all \
  --input tools/isaac-ng.unpacked.exe \
  --ghidra-home "$GHIDRA_HOME" \
  --threads 4

test -s output/recomp/export/functions.jsonl
test -s output/recomp/export/recovered-functions.tsv

# The decoded PE call index is required by patch_reentry.py.
python scripts/decomp/tools/build-pe-index.py

# Builds output/recomp/lift/starts_union.txt and verifies the EXE SHA.
python scripts/recomp/lift/prepare_starts.py
test -s output/recomp/lift/starts_union.txt
```

The Ghidra export has multiple resumable stages (`analyze`, `recover`,
`inventory`, `decompile`, `merge`, `report`). Check
`output/recomp/export/` logs if it fails. On low-memory machines reduce
`--threads` and the Ghidra heap options, but reducing the heap excessively
will fail analysis.

## 8. Prepare memory image, host shims and Lua

Download the official Lua 5.3.3 archive into your ignored local `tools/`
directory. **Extract it manually before invoking `lua_build.py`**; the
current script's tarball-autoload branch references an undefined variable
(`THIRD_PARTY`). Having `tools/lua-5.3.3/src/lua.h` present avoids that path.

```bash
curl -fL https://www.lua.org/ftp/lua-5.3.3.tar.gz -o tools/lua-5.3.3.tar.gz
echo "5113c06884f7de453ce57702abaac1d618307f33f6789fa870e87a59d772aca2  tools/lua-5.3.3.tar.gz" | sha256sum -c -
tar -xzf tools/lua-5.3.3.tar.gz -C tools
test -f tools/lua-5.3.3/src/lua.h

python scripts/recomp/host/boot_tables.py
python scripts/recomp/host/memimage.py
python scripts/recomp/host/verify_memimage.py
python scripts/recomp/host/gen_shims.py

# Normal Lua archive for the native host self-test:
python scripts/recomp/host/lua_build.py --build
python scripts/recomp/host/build_selftest.py
```

The subsequent **web** build automatically requests a separately built Lua
archive with `--sjlj wasm` as needed.

## 9. Lift the Windows executable to C and link WASM

Run the original full-lift arguments from `docs/DEVELOPING.md`, using
the verified EXE and outputs from steps 7–8:

```bash
python scripts/recomp/lift/lift_parallel.py --jobs 4 \
  --exe tools/isaac-ng.unpacked.exe \
  --ghidra-functions output/recomp/export/functions.jsonl \
  --fragments-tsv output/recomp/export/recovered-functions.tsv \
  --imports output/recomp/census/imports.json \
  --shim-table output/recomp/host/shim-table.json \
  --va-file output/recomp/lift/starts_union.txt \
  --hand-written scripts/recomp/host/src/missing_fns.c \
  --dispatch auto --max-insns 100000 --split-va 0x30000 --trace-va \
  --out output/recomp/lift/gu --module lifted \
  --stats output/recomp/lift/gu/stats.json

python scripts/recomp/lift/patch_reentry.py \
  --dir output/recomp/lift/gu \
  --exe tools/isaac-ng.unpacked.exe

python scripts/recomp/lift/build_boot.py --web --fast --jobs 3

test -s output/recomp/lift/boot-web-fast/boot.wasm
test -s output/recomp/lift/boot-web-fast/boot.mjs
test -s output/recomp/host/isaac.segs.bin
```

**This stage is not a guaranteed build from arbitrary game ZIPs.** Compiled
artifacts and generated inventories are not shipped in Git; earlier
development also relied on version-specific patches. Stop and inspect the
first real error rather than inventing substitute files or disabling
verification gates.

## 10. Assemble and verify the complete playable web distribution

```bash
python scripts/recomp/assets/ship.py build
python scripts/recomp/assets/ship.py check .scratch/game-dist
test -s .scratch/game-dist/play.html
test -s .scratch/game-dist/boot.wasm
test -s .scratch/game-dist/isaac.segs.bin
```

For a smoke test on the workstation:

```bash
node scripts/recomp/web/serve_dist.mjs .scratch/game-dist 8200
# Open http://127.0.0.1:8200 in a supported Chromium browser.
# Stop the server with Ctrl+C after confirming startup and gameplay.
```

Check actual start, input, audio and a real game room before the final APK
package. A manifest check only verifies packaged bytes.

## 11. Generate an all-in-one offline HTML

```bash
python scripts/recomp/assets/portable.py offline \
  .scratch/game-dist .scratch/isaac.html
test -s .scratch/isaac.html
du -h .scratch/isaac.html
```

This embeds the WASM, archives and web modules in one potentially very
large HTML file. A plain downloaded web page or the source repo's root
`index.html` is **not** a replacement.

## 12. Build the fully offline APK (uses existing Android tooling)

Keep your existing **OpenJDK 21** (no need to switch to JDK 17).
The project now includes a **Gradle 8.11.1 Wrapper**; do not use
the cached Gradle 9.7.1 binary. Make sure SDK 36 and Build Tools 36.0.0
are installed in the **same environment** running Gradle.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/ossdata/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
java -version
./android/gradlew --version
sdkmanager "platforms;android-36" "build-tools;36.0.0"

bash android/package-offline.sh .scratch/isaac.html

test -s .scratch/isaac-self-contained-offline-debug.apk
unzip -Z1 .scratch/isaac-self-contained-offline-debug.apk | grep -Fx 'assets/offline.html'
```

Final output:

```text
.scratch/isaac-self-contained-offline-debug.apk
```

The packaging script temporarily embeds `isaac.html` as an Android asset,
builds a debug APK, checks that the HTML is inside, and removes the staging
copy. **Do not push this resulting APK to the public repository.**

You can transfer the APK to your Android 10+ phone, enable installing from
your file manager, and open it offline. If `adb` is already configured:

```bash
adb devices
adb install -r .scratch/isaac-self-contained-offline-debug.apk
```

An APK signed locally may not upgrade a previous GitHub Actions APK signed
with a different debug key. Back up/export existing saves before uninstalling
the previous app to resolve `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## 13. Diagnostics/checkpoints

| Last output | Meaning | Next action |
|---|---|---|
| `GAME_DIR/resources/packed/*.a` | Game assets found | Check all expected archives and Lua scripts |
| `tools/isaac-ng.unpacked.exe` matches expected SHA | Required patched executable is correct | Start the expensive Ghidra pipeline |
| `output/recomp/export/functions.jsonl` | Ghidra inventory ready | Build PE index, prepare starts and lift |
| `output/recomp/lift/boot-web-fast/boot.wasm` | Browser engine linked | Ship the distribution |
| `.scratch/game-dist/dist.json` | Distribution created | Run `ship.py check` and play test |
| `.scratch/isaac.html` | All-in-one portable browser build | Package into APK |
| `.scratch/isaac-self-contained-offline-debug.apk` | Locally bundled offline app | Install and test on a real phone |

**Troubleshooting**

- **Original EXE hash differs:** not proof your legal installation is
  broken. It means this exact recompilation build is tied to different,
  custom-patched bytes; ask for a version-matched preparation recipe.
- **`prepare_starts.py` says hash mismatch:** never override the hash
  without porting all version-bound addresses and patches.
- **`bundle.py --strict` fails:** extra unclassified original-game files
  need investigation; build without `--strict` first and inspect the list.
- **`THIRD_PARTY` undefined in `lua_build.py`:** manually extract the Lua
  tarball under `tools/lua-5.3.3` before running the Lua build.
- **Ghidra / Gradle Java error:** use your existing OpenJDK 21 for both. Use `./android/gradlew` to obtain the pinned Gradle 8.11.1 for AGP 8.10.1.
- **`emcc not found`:** `source "$HOME/emsdk/emsdk_env.sh"` in your
  current terminal, or supply `EMCC`/`EMSDK` paths.
- **Gradle cannot see SDK:** set `ANDROID_HOME` to the SDK directory and
  ensure Linux tools are available in WSL/Linux.
- **Insufficient phone storage or WebView fails to load:** this port
  allocates hundreds of MiB of WASM memory and embeds large base64 assets.
  A successful Gradle build is not proof that the game runs on your device.

See also [Developing](DEVELOPING.md), [Recompilation boot](recomp-boot.md),
and [Android README](../android/README.md).
