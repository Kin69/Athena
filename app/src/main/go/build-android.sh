#!/bin/bash

set -e

# The Gradle build passes its own ndkVersion here. This default only covers
# running the script by hand.
NDK_VERSION="${NDK_VERSION:-30.0.16248370}"

# This defaulted to $HOME/Library/Android/sdk -- a macOS path -- which fails for
# any Linux contributor whose ANDROID_HOME is unset. Honour the standard
# variables first, then fall back per-platform.
if [ -z "$ANDROID_HOME" ]; then
    if [ -n "$ANDROID_SDK_ROOT" ]; then
        ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"

if [ ! -d "$NDK" ]; then
    echo "Error: Android NDK not found at $NDK"
    echo "Please set ANDROID_HOME or install NDK version $NDK_VERSION"
    exit 1
fi

# The Linux branch hardcoded linux-x86_64, which does not exist in an arm64 NDK
# and would not run here if it did. Pick by host architecture instead, and fail
# with the directory listing rather than letting Go die on a missing compiler.
#
# (The darwin branch's two arms were identical -- both darwin-x86_64 -- so the
# uname test did nothing. Collapsed, since Apple silicon runs it under Rosetta
# and that is what the NDK actually ships.)
if [[ "$OSTYPE" == "darwin"* ]]; then
    TOOLCHAIN_PATH="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
elif [[ $(uname -m) == "aarch64" || $(uname -m) == "arm64" ]]; then
    TOOLCHAIN_PATH="$NDK/toolchains/llvm/prebuilt/linux-arm64/bin"
else
    TOOLCHAIN_PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi

if [ ! -d "$TOOLCHAIN_PATH" ]; then
    echo "Error: NDK toolchain not found at $TOOLCHAIN_PATH"
    echo "Host is $(uname -m). Available prebuilt toolchains in this NDK:"
    ls "$NDK/toolchains/llvm/prebuilt/" 2>/dev/null | sed 's/^/  /'
    exit 1
fi

export PATH="$PATH:$TOOLCHAIN_PATH"
export GOOS='android'
export CGO_ENABLED=1

# Output directories
OUTPUT_DIR="../../../libs"
mkdir -p "$OUTPUT_DIR/arm64-v8a"
mkdir -p "$OUTPUT_DIR/armeabi-v7a"

echo "Building nflog for Android..."

# Build for arm64-v8a
echo "Building for arm64-v8a..."
export GOARCH='arm64'
export CC="aarch64-linux-android21-clang"
export CXX="aarch64-linux-android21-clang++"
export CGO_CFLAGS="-O2 -fPIC -DANDROID"
export CGO_CPPFLAGS="-O2 -fPIC -DANDROID"
export CGO_CXXFLAGS="-O2 -fPIC -DANDROID"
export CGO_FFLAGS="-O2 -fPIC"
export CGO_LDFLAGS="-s -w -Wl,-z,max-page-size=16384"

go clean
go build -x -ldflags="-s -w" -compiler gc -gcflags="-m -dwarf=false" -o "$OUTPUT_DIR/arm64-v8a/libnflog.so" .

# Build for armeabi-v7a
echo "Building for armeabi-v7a..."
export GOARCH='arm'
export CC="armv7a-linux-androideabi21-clang"
export CXX="armv7a-linux-androideabi21-clang++"
export CGO_CFLAGS="-O2 -fPIC -DANDROID -mfpu=neon"
export CGO_CPPFLAGS="-O2 -fPIC -DANDROID -mfpu=neon"
export CGO_CXXFLAGS="-O2 -fPIC -DANDROID -mfpu=neon"
export CGO_FFLAGS="-O2 -fPIC -mfpu=neon"
export CGO_LDFLAGS="-s -w -Wl,-z,max-page-size=16384"

go clean
go build -x -ldflags="-s -w" -compiler gc -gcflags="-m -dwarf=false" -o "$OUTPUT_DIR/armeabi-v7a/libnflog.so" .

echo "Build completed successfully!"
echo "Executables created:"
echo "  - $OUTPUT_DIR/arm64-v8a/libnflog.so"
echo "  - $OUTPUT_DIR/armeabi-v7a/libnflog.so"