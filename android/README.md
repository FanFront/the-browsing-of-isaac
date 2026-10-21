# Android APK

This directory adds a small **online-only** Android WebView host for the existing
touch-enabled site. It is not an Android native port of the game, and the APK
**does not include game files, recompilation output, Wasm or copyrighted game
assets**. The website and its CDN must be reachable to play.

## Build and install using GitHub Actions

1. Open **Actions → Android APK → Run workflow** (or push a change under
   `android/` on `main`).
2. Open the successful workflow run and download its
   `isaac-android-debug-apk` artifact ZIP.
3. Extract `app-debug.apk` from the ZIP on the phone, then install it
   (Android may ask for permission to install apps from that file manager).

The workflow assembles a **debug-signed APK** so it can be sideloaded without
signing secrets. It is **not** a Play Store release build. An Android 10+
device with an up-to-date Android System WebView is required.

## Local build

Use JDK 17, Android SDK platform 35/build tools 35.0.0, and Gradle 8.9:

```sh
gradle -p android :app:assembleDebug
# output: android/app/build/outputs/apk/debug/app-debug.apk
```

The launch address is `app/src/main/res/values/strings.xml` → `game_url`.
It defaults to `https://tboi.online/`. Change it to your own **HTTPS**
deployment only if that deployment includes the complete game payload; a
GitHub source checkout alone cannot serve it. Network access is required.

## Runtime behavior and limitations

- Fullscreen immersive WebView with hardware acceleration; rotations keep the
  same WebView instance and touch controls come from the website itself.
- Web storage / IndexedDB persist in **this app's own storage**. Chrome's
  existing saves are *not* shared with the APK. Uninstalling or clearing app
  storage deletes them; export saves before doing so.
- Android's file picker supports ZIP / `.dat` imports. HTTPS downloads use the
  system Download Manager. Small blob-based save exports (up to 8 MiB) are
  saved under `Downloads/Isaac`; oversized blob exports are not supported.
- Android Back sends Escape to the game; press Back a second time within
  1.8 seconds to exit. External links open in a regular browser.
- The website requires modern browser support for WebAssembly JSPI, WebGL 2
  and WebAudio. WebView capabilities, device memory and GPU drivers vary.
  Successful APK compilation does **not** prove that gameplay works on every
  phone. Test game boot, touch, audio, imports/exports and an actual run on
  your device.

The game's copyrights and other upstream notices remain as described in
[../README.md](../README.md) and [../LICENSE](../LICENSE).
