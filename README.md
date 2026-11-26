# Ladla Sheikh — Android app (WebView)

Wraps https://android-web-apk.vercel.app/ in a native app (package `com.sheikhali.apk`).

## What the app does
- Whole website runs inside the app: Sign In / Sign Up / Privacy / Terms open inside the app (the site is one page: `#/login`, `#/signup` ...).
- Login popups (Google / GitHub / Facebook via Firebase) open in an in-app window.
- GitHub release links and files (.apk .zip .rar ...) download inside the app with a themed progress sheet (Hide / Cancel / Install / Open) — they no longer jump to Chrome.
- Login screen works on old phones: the website CSS has fallbacks for old WebViews, the app adds a light mode (" lite" in the user-agent) on weak phones and asks once to update Android System WebView when it is older than v108.
- Splash screen, themed status bar, pull-to-refresh, loading bar, full-screen video, offline page that reloads by itself.
- Other websites (YouTube, Telegram, WhatsApp ...) open in their own apps / the browser.

## Settings (top of `MainActivity.kt`)
`startUrl`, `internalHosts`, `keepInsideApp`, `immersiveMode`, `installApkFromApp`, `enableWebDebugging`.

## Build
- JDK 17, Gradle 8.13 (wrapper included -> `./gradlew assembleRelease`; Play Store needs `./gradlew bundleRelease`).
- compileSdk / targetSdk **36** (Google Play requires API 36 for new apps and updates since 31 Aug 2026).
- Use the included workflow `.github/workflows/build.yml` (it runs `./gradlew`). An old workflow that calls plain `gradle` with an older version will fail.

## Before publishing on Google Play
1. Remove `REQUEST_INSTALL_PACKAGES` from `AndroidManifest.xml` and set `installApkFromApp = false`.
2. Upload an app bundle (`.aab`), signed with a key you keep forever (Play App Signing).
3. Add `https://android-web-apk.vercel.app/.well-known/assetlinks.json` (package + SHA-256 of your signing key) so website links open in the app.
4. Upload `store/play-store-icon-512.png` as the Play Store icon (512×512). The app icon itself is an adaptive icon (premium gradient background + monochrome layer for Android 13 themed icons).
