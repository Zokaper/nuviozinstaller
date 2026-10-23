# Nuvio Z Installer

Puts [Nuvio Z](https://github.com/Zokaper/nuvio-z) on an iPhone, signed with your own Apple Account, from a
Windows PC, a Mac or an Android phone. After that, Nuvio Z keeps itself signed.

## Download

Always the newest release:

- Windows: [Nuvio-Z-Installer-Windows-x64.zip](https://github.com/Zokaper/nuviozinstaller/releases/latest/download/Nuvio-Z-Installer-Windows-x64.zip)
- Mac (Intel and Apple Silicon, macOS 11+): [Nuvio-Z-Installer-macOS.zip](https://github.com/Zokaper/nuviozinstaller/releases/latest/download/Nuvio-Z-Installer-macOS.zip)
- Android (arm64, USB host): [Nuvio-Z-Installer-Android.apk](https://github.com/Zokaper/nuviozinstaller/releases/latest/download/Nuvio-Z-Installer-Android.apk)

## What's in here

| Folder | What it is |
| --- | --- |
| `ui/` | The installer's screens. One set for every platform: desktop shows them in Tauri, Android in a WebView. |
| `core/` | Device probes, Apple sign-in, signing and install (Rust, no UI). |
| `app/` | The desktop app (Tauri 2) for Windows and macOS. |
| `ffi/` | `nuvioz-signer`: the core as a C library. Nuvio Z's iOS app links it to renew and update itself. |
| `android/` | The Android app: a WebView shell, the Java bridge and its own native core (`android/native/`). |
| `scripts/` | The render harness and the signing-crate auto-update tooling. |

## Building

- Desktop: `cargo build --release -p nuvioz-ios-installer`. Core tests: `cargo test -p nuvioz-installer-core`.
- Android: from `android/`, `./gradlew assembleDebug` (needs `ANDROID_HOME`, NDK 28.2.13676358 and
  `rustup target add aarch64-linux-android`). A debug build is `com.nuvio.z.installer.dev` and installs beside
  the released app.
- Screens: `bash scripts/render-screens.sh <out-dir>` renders every step with headless Edge. Add
  `?platform=android` to a preview URL for the Android variant.

Releases are made by the **Release** workflow from `main`. **Auto-release** updates the signing libraries
(isideload, idevice) once they're reviewed and tested, and publishes a new installer by itself.

## License

GPL-3.0-only.
