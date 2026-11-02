# Reason for Android

Native Android app for [Reason](https://reasonmachines.com/agents). It runs the full Reason web app inside a hardened WebView, so every web feature is available, and adds the Android pieces a browser tab lacks.

## Features

- Full Reason web app (Sessions, chat, voice, pages, settings, billing) at `https://reasonmachines.com/agents`
- Sign-in with Google, GitHub, Microsoft, Apple and other providers stays in the app (popup windows supported); cookies persist across restarts
- File and image upload, including taking a photo with the camera; multiple files
- Microphone and camera for voice calls (`getUserMedia`), granted only to Reason origins
- Downloads: normal links via DownloadManager, `blob:` and `data:` links saved to Downloads
- Web notifications become Android notifications; tapping one opens the right page
- Deep links: opening a `reasonmachines.com` link offers to open it in the app
- External links, `mailto:`, `tel:` and app intents open in the matching app
- Back button navigates history; closes popups; double press to leave
- Automatic updates from GitHub Releases: checks on launch (every 6 hours at most), shows release notes, downloads the APK and opens the system installer. Long-press the app icon for "检查更新" and "更新设置". Because this repository is private, the app asks once for a fine-grained GitHub token with read-only Contents access to this repository; it is stored encrypted on the phone, never in the APK
- Follows system light/dark theme; offline page with retry; fullscreen video; pinch zoom

## Build

Requirements: JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (minified)
```

The release build is signed with the debug key so it installs directly. For Play Store distribution configure your own signing key in `app/build.gradle.kts`.

GitHub Actions builds the APK on every push; every push to `main` (or a `v*` tag) publishes a GitHub Release with `reason-android-<version>.apk`, which the in-app updater picks up.

## Install

Copy the APK to your phone and open it (allow "install unknown apps"), or `adb install app-release.apk`.

## Configuration

The start URL is `BuildConfig.START_URL` in `app/build.gradle.kts`. Trusted hosts are in `Hosts.kt`.
