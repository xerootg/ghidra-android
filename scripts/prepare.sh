#!/usr/bin/env bash
# Fetches and builds everything the APK bundles that is not Android code:
#   - the Android (bionic) JRE 21 for arm64, repacked as a zip asset
#   - the AWT toolkit, as a jar that is patched into java.desktop
#   - DejaVu fonts and the logical font map
#   - Ghidra's native decompiler and SLEIGH compiler, built with the NDK
#
# Ghidra itself is not bundled; the app downloads the official release (or
# imports a zip) on first start.
#
# Usage: scripts/prepare.sh            (uses $ANDROID_NDK_HOME or the SDK's NDK)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cache="$root/.cache"
assets="$root/app/src/main/assets"
jnilibs="$root/app/src/main/jniLibs/arm64-v8a"
stubs="$root/app/src/main/cpp/stubs"
mkdir -p "$cache" "$assets" "$jnilibs" "$stubs"

# Pinned inputs. Update together with app/build.gradle's ghidraVersion.
GHIDRA_TAG="${GHIDRA_TAG:-Ghidra_12.1.4_build}"
JRE_URL="https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-arm64.tar.xz"
JRE_SHA256="${JRE_SHA256:-}"
FONTS_URL="https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.zip"
# javac for Ghidra scripts, from the JDK release matching the Android JRE.
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.1%2B12/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.1_12.tar.gz"
NDK_VERSION="${NDK_VERSION:-29.0.14206865}"
API=31

fetch() { # url dest
    if [ ! -s "$2" ]; then
        echo "fetch $1"
        curl -fsSL --retry 4 -o "$2.part" "$1"
        mv "$2.part" "$2"
    fi
}

ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk "$HOME/Android/Sdk"; do
        if [ -n "$sdk" ] && [ -d "$sdk/ndk/$NDK_VERSION" ]; then
            ndk="$sdk/ndk/$NDK_VERSION"
            break
        fi
    done
fi
[ -d "$ndk" ] || { echo "NDK $NDK_VERSION not found; set ANDROID_NDK_HOME" >&2; exit 1; }
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"

# --- JRE ---------------------------------------------------------------------
fetch "$JRE_URL" "$cache/jre21-android-arm64.tar.xz"
if [ -n "$JRE_SHA256" ]; then
    echo "$JRE_SHA256  $cache/jre21-android-arm64.tar.xz" | sha256sum -c -
fi
rm -rf "$cache/jre-stage" && mkdir -p "$cache/jre-stage/jre"
tar -xJf "$cache/jre21-android-arm64.tar.xz" -C "$cache/jre-stage/jre"
# Not needed at run time, and legal/ is full of symlinks.
rm -rf "$cache/jre-stage/jre/legal" "$cache/jre-stage/jre/lib/src.zip" "$cache/jre-stage/jre/lib/ct.sym"
cp "$cache/jre-stage/jre/lib/libawt_headless.so" "$stubs/"
(cd "$cache/jre-stage" && rm -f "$assets/jre.zip" && zip -qr -X "$assets/jre.zip" jre)

# --- toolkit -----------------------------------------------------------------
"$root/tools/build-toolkit.sh"
rm -f "$assets/toolkit.jar"
(cd "$root/toolkit/build/classes" && jar --create --file "$assets/toolkit.jar" .)
cp "$root/toolkit/fontconfig.properties.in" "$assets/"

# --- javac (Ghidra compiles its Java scripts) -------------------------------
fetch "$JDK_URL" "$cache/temurin-21.0.1-aarch64.tar.gz"
rm -rf "$cache/javac-stage" "$assets/javac" && mkdir -p "$cache/javac-stage" "$assets/javac"
tar -xzf "$cache/temurin-21.0.1-aarch64.tar.gz" -C "$cache/javac-stage" --strip-components=1 \
    --wildcards '*/jmods/jdk.compiler.jmod' '*/jmods/jdk.internal.opt.jmod'
for m in jdk.compiler jdk.internal.opt; do
    jmod extract --dir "$cache/javac-stage/$m" "$cache/javac-stage/jmods/$m.jmod"
    (cd "$cache/javac-stage/$m/classes" && jar --create --file "$assets/javac/$m.jar" .)
done
rm -rf "$root/compiler-patch/build" && mkdir -p "$root/compiler-patch/build"
javac -nowarn --release 21 --patch-module java.compiler="$root/compiler-patch/src" \
    -d "$root/compiler-patch/build" "$root/compiler-patch/src/javax/tools/ToolProvider.java"
(cd "$root/compiler-patch/build" && jar --create --file "$assets/javac/compiler-patch.jar" .)

# --- fonts -------------------------------------------------------------------
fetch "$FONTS_URL" "$cache/dejavu-fonts-ttf-2.37.zip"
rm -rf "$cache/fonts-stage" && mkdir -p "$cache/fonts-stage" "$assets/fonts"
unzip -q -o "$cache/dejavu-fonts-ttf-2.37.zip" -d "$cache/fonts-stage"
for f in DejaVuSans DejaVuSans-Bold DejaVuSans-Oblique DejaVuSans-BoldOblique \
         DejaVuSansMono DejaVuSansMono-Bold DejaVuSansMono-Oblique DejaVuSansMono-BoldOblique \
         DejaVuSerif DejaVuSerif-Bold DejaVuSerif-Italic DejaVuSerif-BoldItalic; do
    cp "$cache/fonts-stage/dejavu-fonts-ttf-2.37/ttf/$f.ttf" "$assets/fonts/"
done
cp "$cache/fonts-stage/dejavu-fonts-ttf-2.37/LICENSE" "$assets/fonts/LICENSE"

# --- Ghidra decompiler and SLEIGH compiler --------------------------------
src="$cache/ghidra-src-$GHIDRA_TAG"
if [ ! -d "$src" ]; then
    fetch "https://github.com/NationalSecurityAgency/ghidra/archive/refs/tags/$GHIDRA_TAG.tar.gz" \
        "$cache/ghidra-src-$GHIDRA_TAG.tar.gz"
    mkdir -p "$src"
    tar -xzf "$cache/ghidra-src-$GHIDRA_TAG.tar.gz" -C "$src" --strip-components=1 \
        --wildcards '*/Ghidra/Features/Decompiler/src/decompile/cpp/*' \
        '*/GPL/DemanglerGnu/src/*' '*/Ghidra/Features/FileFormats/src/lzfse/*'
fi
cpp="$src/Ghidra/Features/Decompiler/src/decompile/cpp"
cxx="$tc/aarch64-linux-android$API-clang++ -std=c++11"
for target in ghidra_opt sleigh_opt; do
    make -C "$cpp" -j"$(nproc)" "$target" CXX="$cxx" ADDITIONAL_FLAGS="-static-libstdc++" >/dev/null
done
# Executables shipped as lib*.so so the package manager extracts them to the
# native library directory, the one place an app may exec() from.
"$tc/llvm-strip" -o "$jnilibs/libdecompile.so" "$cpp/ghidra_opt"
"$tc/llvm-strip" -o "$jnilibs/libsleigh.so" "$cpp/sleigh_opt"

cc="$tc/aarch64-linux-android$API-clang -w -O2 -std=gnu17 -DHAVE_STDLIB_H -DHAVE_STRING_H"
dem="$src/GPL/DemanglerGnu/src"
$cc -I"$dem/demangler_gnu_v2_41/headers" "$dem"/demangler_gnu_v2_41/c/*.c -o "$cache/demangler_gnu_v2_41"
$cc -DMAIN_CPLUS_DEM -I"$dem/demangler_gnu_v2_24/headers" "$dem"/demangler_gnu_v2_24/c/*.c -o "$cache/demangler_gnu_v2_24"
$cc "$src"/Ghidra/Features/FileFormats/src/lzfse/c/*.c -o "$cache/lzfse"
for b in demangler_gnu_v2_41 demangler_gnu_v2_24 lzfse; do
    "$tc/llvm-strip" -o "$jnilibs/lib$b.so" "$cache/$b"
done

echo "prepared: $(du -sh "$assets" | cut -f1) assets, natives in $jnilibs"
