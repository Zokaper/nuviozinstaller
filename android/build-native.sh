#!/usr/bin/env bash
# Builds the installer's native library for Android on Linux or macOS (CI); build-native.ps1 does the same on Windows.
#   bash build-native.sh <jniLibs output directory>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-${here}/app/build/generated/jniLibs}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "${sdk}" ] || { echo "Set ANDROID_HOME" >&2; exit 1; }
ndk="${sdk}/ndk/28.2.13676358"
[ -d "${ndk}" ] || { echo "Install NDK 28.2.13676358 in ${sdk}/ndk" >&2; exit 1; }
host="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
toolchain="${ndk}/toolchains/llvm/prebuilt/${host}/bin"
target=aarch64-linux-android
rustup target list --installed | grep -qx "${target}" || { echo "Run: rustup target add ${target}" >&2; exit 1; }
export CARGO_TARGET_DIR="${here}/native/build/target"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${toolchain}/clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-Clink-arg=--target=${target}24 -Clink-arg=-Wl,-z,max-page-size=16384"
export CC_aarch64_linux_android="${toolchain}/clang"
export AR_aarch64_linux_android="${toolchain}/llvm-ar"
export CFLAGS_aarch64_linux_android="--target=${target}24"
cargo build --locked --release --manifest-path "${here}/native/Cargo.toml" --target "${target}" -p nuvioz-usb-probe
mkdir -p "${out}/arm64-v8a"
cp "${CARGO_TARGET_DIR}/${target}/release/libnuvioz_usb_probe.so" "${out}/arm64-v8a/"
