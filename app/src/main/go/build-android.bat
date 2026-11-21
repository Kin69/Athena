@echo off
REM Build nflog for Android
REM This script compiles the Go code for Android arm64-v8a and armeabi-v7a architectures

setlocal enabledelayedexpansion

REM Configuration
if not defined NDK_VERSION set NDK_VERSION=30.0.16248370
if "%ANDROID_HOME%"=="" set ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk
set NDK=%ANDROID_HOME%\ndk\%NDK_VERSION%

REM Check if NDK exists
if not exist "%NDK%" (
    echo Error: Android NDK not found at %NDK%
    echo Please set ANDROID_HOME or install NDK version %NDK_VERSION%
    exit /b 1
)

REM Set up environment
set PATH=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin;%PATH%
set GOOS=android
set CGO_ENABLED=1

REM Output directories
set OUTPUT_DIR=..\..\..\libs
if not exist "%OUTPUT_DIR%\arm64-v8a" mkdir "%OUTPUT_DIR%\arm64-v8a"
if not exist "%OUTPUT_DIR%\armeabi-v7a" mkdir "%OUTPUT_DIR%\armeabi-v7a"

echo Building nflog for Android...

REM Build for arm64-v8a
echo Building for arm64-v8a...
set GOARCH=arm64
set CC=aarch64-linux-android21-clang
set CXX=aarch64-linux-android21-clang++
set CGO_CFLAGS=-O2 -fPIC -DANDROID
set CGO_CPPFLAGS=-O2 -fPIC -DANDROID
set CGO_CXXFLAGS=-O2 -fPIC -DANDROID
set CGO_FFLAGS=-O2 -fPIC
set CGO_LDFLAGS=-s -w -Wl,-z,max-page-size=16384

go clean
go build -x -ldflags="-s -w" -compiler gc -gcflags="-m -dwarf=false" -o "%OUTPUT_DIR%\arm64-v8a\libnflog.so" .

REM Build for armeabi-v7a
echo Building for armeabi-v7a...
set GOARCH=arm
set CC=armv7a-linux-androideabi21-clang
set CXX=armv7a-linux-androideabi21-clang++
set CGO_CFLAGS=-O2 -fPIC -DANDROID -mfpu=neon
set CGO_CPPFLAGS=-O2 -fPIC -DANDROID -mfpu=neon
set CGO_CXXFLAGS=-O2 -fPIC -DANDROID -mfpu=neon
set CGO_FFLAGS=-O2 -fPIC -mfpu=neon
set CGO_LDFLAGS=-s -w -Wl,-z,max-page-size=16384

go clean
go build -x -ldflags="-s -w" -compiler gc -gcflags="-m -dwarf=false" -o "%OUTPUT_DIR%\armeabi-v7a\libnflog.so" .

echo Build completed successfully!
echo Executables created:
echo   - %OUTPUT_DIR%\arm64-v8a\libnflog.so
echo   - %OUTPUT_DIR%\armeabi-v7a\libnflog.so

endlocal