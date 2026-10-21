# Build a complete *private* offline APK using GitHub Actions and boi-portable

This route is designed for owners of the Windows game whose Linux machine
cannot afford a Ghidra/Emscripten rebuild. GitHub Actions downloads **already
compiled browser game resources** from the original author's published
`chiikabu/boi-portable` project and assembles a **fully bundled offline APK**.

**No local Ghidra, pypcode, Steam EXE, Emscripten, or 4 GB server build required.**

## Important: keep the resulting full-game APK private

The browser port's upstream project says its game data is for personal use by
owners of the original game. Access to publicly hosted assets is not the same
as permission to redistribute a game-filled APK.

**Never run this full-game packaging workflow as a public GitHub Actions
artifact or release.** The workflow refuses to build when the repository is
public, and it requires an explicit ownership checkbox.

Your current `FanFront/the-browsing-of-isaac` source repository is **public**.
To get an APK from Actions, create a *separate private repository* holding the
same source code. Do not use GitHub's public-fork button expecting a private
fork: create a new private repository and push your source-only checkout.

## Set up a private source repository (once)

Option A: with GitHub CLI, from a source checkout:

```bash
git clone https://github.com/FanFront/the-browsing-of-isaac.git
cd the-browsing-of-isaac
# Requires "gh auth login" first. Creates a separate private repository.
gh repo create isaac-offline-private --private --source . --remote private --push
```

Option B: create a new **private, empty** repository in the GitHub web UI,
then from the existing source checkout:

```bash
git remote add private https://github.com/<YOUR_ACCOUNT>/isaac-offline-private.git
git push private main
```

The Git working tree should contain **no original EXEs or archive assets**.
Source files are enough; the workflow fetches upstream assets only into
the private runner's ignored `.scratch/` directory.

## Build in GitHub Actions

1. Open the *private* repository → **Actions**.
2. Select **PRIVATE full offline Isaac APK (upstream chunks)**.
3. Select **Run workflow** and check **I own a legitimate copy of Isaac**.
4. GitHub Actions installs Java 21, Android SDK 36 and Gradle 8.11.1.
5. It fetches pinned `boi-portable` HTML and its 32 archive chunks, verifies
   byte counts and SHA-256 digests, and rewrites remote payload links to the
   APK's internal virtual origin.
6. It packages the chunks as **uncompressed APK assets** so that WebView's
   internal Range requests can seek through them. No Android INTERNET
   permission is added.
7. Download the private run's artifact:
   `isaac-complete-offline-apk-PRIVATE`. It is retained for **3 days**.

The APK file in the artifact is named
`isaac-upstream-chunks-offline-debug.apk`.

**Limits and caveats:** the CI workflow checks APK contents and lack of
Android INTERNET permission, not full gameplay on a physical device.
The Android WebView must support JSPI, WebGL 2 and WebAudio. Some phones may
run out of RAM. We have not yet performed a private payload build or a
device gameplay test.

## Upstream provenance / resource pinning

The workflow uses `scripts/android/fetch_upstream_chunks.py`. It pins the
source HTML to `chiikabu/boi-portable` commit:

```text
bc453b0653de52421a5e4271102f41126a9311ae
```

That HTML's **original embedded stream descriptors** identify the pinned
resources. The tool does not replace the embedded engine, its archive
offsets, encryption/scrambling key, or the archive data. It only:

- rewrites both remote stream bases to
  `https://appassets.androidplatform.net/payload/c`;
- disables the optional CDN-hosted MOD catalogue;
- downloads `a0.bin`–`a3.bin` and `b0.bin`–`b27.bin` into an ignored
  directory, with retries and exact byte-count verification;
- records a private SHA-256 build manifest and validates the 32 files again.

The Android app intercepts local requests under `/payload/c/`. It responds
with the correct **HTTP 206 Partial Content, Content-Range and Content-Length**
for windowed archive reads, or HTTP 200 for whole chunks, without touching
the network. Other external HTTP(S) requests stay blocked.

### Local use (optional; no Ghidra)

If you later want to assemble from your own machine instead of Actions:

```bash
python3 scripts/android/fetch_upstream_chunks.py --out .scratch/boi-portable
bash android/package-upstream-chunks.sh .scratch/boi-portable
```

This invokes the existing JDK 21 / Android SDK 36 / Gradle Wrapper build
toolchain. It does not download copyrighted data into Git history.

The prior local `android/package-offline.sh` path still accepts a
standalone offline `isaac.html` built from a personal Windows game;
it is separate from this **chunk-based upstream** workflow.

Upstream:
- [boi-portable](https://github.com/chiikabu/boi-portable)
- [the-browsing-of-isaac](https://github.com/chiikabu/the-browsing-of-isaac)
