# Nuvio Z Installer: agent instructions

The installer moved here from `Zokaper/nuvio-z` (`iosInstaller/`) on 2026-10-24, with its history.
`nuvio-z/Docs/` stays canonical for the product as a whole (Z-FEATURES, RELEASES). This file covers this repository.

## Rules

1. **One UI for every platform.** `ui/` is the only copy of the screens. Platform differences go in explicit
   blocks: `forAndroid()` in `ui/steps.js`, and `ctx.platform` / `ctx.isAndroid` in `ui/app.js`. Desktop
   renders must stay identical when you change Android. Compare the rendered DOM of every preview before and after.
2. **Render before you build.** Screens are reviewed as PNGs (`scripts/render-screens.sh`; for phone width, render
   inside a fixed-size iframe, because headless Edge enforces a minimum window width).
3. **The signer is pinned from nuvio-z.** Nuvio Z's iOS app builds `ffi/` (`nuvioz-signer`) from a pinned commit
   of this repository. A change to `core/` or `ffi/` must keep `signer-ios.yml` green, and nuvio-z bumps the pin on
   purpose.
4. **Android signing key.** Every released APK must be signed with the installer's release key (GitHub secrets
   `NUVIOZ_ANDROID_*`; certificate SHA-256 `75a9088d…ca5024e7`, checked in `android.yml`), or no existing copy can
   update. The maintainer holds the backup. Never commit a keystore.
5. **Never uninstall the maintainer's Android test app or clear its data.** It holds the iPhone pairing and the
   signing key. Update it with `adb install -r`.
6. **Credentials stay in the app.** Apple passwords and 2FA codes are typed into the installer only, never into
   chat, logs or reports. Diagnostics carry booleans and allowlisted device info, never identities or keys.
7. **Two sets of signing pins.** The desktop core uses crates.io isideload/idevice (root `Cargo.toml`, bumped by
   Auto-release). The Android core (`android/native/`) uses the same versions, but isideload is vendored with an
   Android-only TLS patch (`android/VENDOR.md`). Bump it by hand, and never weaken TLS.
8. **Releases.** `Release` publishes `v<version>` from `main` for all three platforms. The version lives in
   `Cargo.toml` (`[workspace.package]`) and `app/tauri.conf.json`; Android reads it from Cargo.toml. The
   maintainer writes release notes; offer suggestions only.
9. More than one agent may work here: stage only the files you changed. Never `git add -A`.
