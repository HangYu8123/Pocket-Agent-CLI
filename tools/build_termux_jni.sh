#!/bin/bash
# Rebuild Termux's tiny terminal JNI helper (libtermux.so) with 16 KB page alignment,
# for every ABI the app ships. The copy inside the JitPack AAR (v0.118.3) is 4 KB
# aligned and fails to load on Android devices that boot with 16 KB pages. Output goes
# to app/src/main/jniLibs/<abi>/ and is picked over the AAR copy via
# packaging.jniLibs.pickFirsts in app/build.gradle.kts.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
HOST=$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64
[ -d "$NDK/toolchains/llvm/prebuilt/$HOST" ] || HOST=darwin-x86_64
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
SRC="$HERE/termux-jni/termux.c"
[ -f "$SRC" ] || curl -fsSL "https://raw.githubusercontent.com/termux/termux-app/v0.118.3/terminal-emulator/src/main/jni/termux.c" -o "$SRC"
for pair in "arm64-v8a:aarch64-linux-android26-clang" "x86_64:x86_64-linux-android26-clang"; do
  ABI="${pair%%:*}"; CC="$BIN/${pair##*:}"
  OUT="$HERE/../app/src/main/jniLibs/$ABI/libtermux.so"
  mkdir -p "$(dirname "$OUT")"
  "$CC" -shared -fPIC -O2 -Wall -std=c11 -D_GNU_SOURCE \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--hash-style=gnu -Wl,-soname,libtermux.so \
    -llog "$SRC" -o "$OUT"
  echo "wrote $OUT"
done
