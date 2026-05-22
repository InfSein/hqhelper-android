# AGENTS.md

## Architecture

This is an Android WebView shell that wraps a Vue3 web app. The web app lives in the `hqhelper/` git submodule (source: `github.com/InfSein/hqhelper-dawntrail`). The Android layer (`app/`) loads built web assets from local storage via a custom `https://applocal/` URL scheme intercepted by `LocalWebViewClient`.

All user-facing UI is the Vue3 app. The Kotlin code handles: WebView hosting, OTA update download/extraction, and a JavaScript bridge (`androidAPI.checkUpdate()`).

## Build (two-phase)

The Vue3 submodule must be built first; its output is copied into Android assets.

```bash
# 1. Init submodule (first time or after clean)
git submodule update --init --recursive

# 2. Build Vue3 web app
cd hqhelper && npm install && npm run build && cd ..

# 3. Copy web assets into Android asset directory
#    Build output goes to hqhelper/dist/
mkdir -p app/src/main/assets/www
rm -rf app/src/main/assets/www/*
cp -r hqhelper/dist/* app/src/main/assets/www/

# 4. Build Android APK
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

**No `gradlew` wrapper is checked in.** Run `gradle wrapper` first if `./gradlew` is missing, or use a system-installed `gradle` directly.

## Environment & Secrets

- Requires **JDK 17** and **Android SDK** (compileSdk 36, minSdk 24).
- `local.properties` has a hardcoded `sdk.dir=/opt/android/sdk` — override via `ANDROID_HOME` env var or edit the file for your machine.
- Release signing reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` from env vars.

## Key Gradle Plugins

- **KSP** — annotation processing for Room and Moshi codegen
- **Secrets Gradle Plugin** — reads `.env` / `.env.example` into `BuildConfig`
- **Roborazzi** — screenshot testing with Robolectric

## Testing

```bash
# Unit tests (Robolectric-based, no device needed)
./gradlew testDebugUnitTest

# Screenshot tests (Roborazzi) — outputs to src/test/screenshots/
./gradlew recordRoborazziDebug
```

Tests use `@Config(sdk = [36])` and `@GraphicsMode(GraphicsMode.Mode.NATIVE)` for screenshot fidelity. Test runner: `AndroidJUnitRunner`.

## Vue3 Submodule Notes

- The submodule has its own `package.json`, lint (`eslint . --fix`), and type-check (`vue-tsc --build --force`).
- `npm run build` runs both type-check and vite build in parallel via `npm-run-all2`.
- Auto-imports: `vue`, `vue-router`, `pinia`, and `useMessage` from naive-ui are auto-imported (no explicit import needed). Components in `src/components/templates/` are auto-registered.
- Version file: `public/version.json` is updated by `scripts/update-version.cjs` on `postinstall`.

## CI

- `build-android.yml`: full pipeline — checkout with submodules → build Vue3 → copy assets → build debug APK → upload artifact.
- `update-submodule.yml`: auto-updates the `hqhelper` submodule via PR on `repository_dispatch`.

## Conventions

- Kotlin source is in `app/src/main/java/com/example/` (single package, flat structure).
- UI theme files: `ui/theme/{Theme,Color,Type}.kt`.
- Dependencies are managed via `gradle/libs.versions.toml` version catalog.
- Some unused dependencies are intentionally commented out in `app/build.gradle.kts` for easy re-enabling.
