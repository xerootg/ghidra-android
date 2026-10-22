#!/usr/bin/env bash
# Development loop: rebuild the toolkit jar and the APK, install it on the
# connected device and start Ghidra (debug builds auto-launch with --ez launch).
#   tools/deploy.sh [adb-serial]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
serial=${1:-${ANDROID_SERIAL:-}}
adb=(adb ${serial:+-s "$serial"})
"$root/tools/build-toolkit.sh"
(cd "$root/toolkit/build/classes" && rm -f "$root/app/src/main/assets/toolkit.jar" && jar --create --file "$root/app/src/main/assets/toolkit.jar" .)
rm -rf "$root/builder/build" && mkdir -p "$root/builder/build/classes"
javac -nowarn --release 21 -d "$root/builder/build/classes" $(find "$root/builder/src/main/java" -name '*.java')
(cd "$root/builder/build/classes" && rm -f "$root/app/src/main/assets/ghidroid-tools.jar" && jar --create --file "$root/app/src/main/assets/ghidroid-tools.jar" .)
(cd "$root/extension" && rm -f "$root/app/src/main/assets/TouchUI-src.zip" && zip -qr -X "$root/app/src/main/assets/TouchUI-src.zip" TouchUI -x 'TouchUI/build/*' 'TouchUI/dist/*')
(cd "$root" && ./gradlew -q assembleDebug)
pkg=io.github.xerootg.ghidroid
"${adb[@]}" shell am force-stop "$pkg"
"${adb[@]}" install -r "$root/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"${adb[@]}" logcat -c
"${adb[@]}" shell am start -n "$pkg/.MainActivity" --ez launch true >/dev/null
echo "deployed; logs: adb logcat -s ghidroid ghidra"
