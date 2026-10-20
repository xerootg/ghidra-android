#!/usr/bin/env bash
# Runs Ghidra on the desktop JDK with the Android toolkit and the socket
# backend, for development. Pair with: java tools/Viewer.java 7777 1.6
#   tools/run-desktop.sh /path/to/ghidra_12.x_PUBLIC [WxH]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
ghidra="${1:?ghidra install dir}"
size="${2:-1600x1000}"
toolkit="$here/toolkit/build/classes"
home="${GHIDROID_HOME:-$here/.cache/desktop-home}"
mkdir -p "$home"
fonts="${GHIDROID_FONTS:-$here/.cache/fonts/dejavu-fonts-ttf-2.37/ttf}"
sed "s#@FONTS@#$fonts#g" "$here/toolkit/fontconfig.properties.in" > "$home/fontconfig.properties"
exec java \
  --patch-module java.desktop="$toolkit" \
  -Dghidroid.backend=socket:7777 \
  -Dcacio.managed.screensize="$size" \
  -Dghidroid.fonts="$fonts" -Dghidroid.defaultFont="$fonts/DejaVuSans.ttf" \
  -Dsun.awt.fontconfig="$home/fontconfig.properties" \
  -Djava.system.class.loader=ghidra.GhidraClassLoader \
  -Dfile.encoding=UTF8 -Duser.home="$home" \
  -Dsun.java2d.opengl=false -Dsun.java2d.xrender=false \
  -Dawt.useSystemAAFontSettings=on \
  -Xshare:off --enable-native-access=ALL-UNNAMED -Xmx4g \
  ${GHIDROID_JVM_ARGS:-} \
  -cp "$ghidra/Ghidra/Framework/Utility/lib/Utility.jar" \
  ghidra.Ghidra ghidra.GhidraRun
