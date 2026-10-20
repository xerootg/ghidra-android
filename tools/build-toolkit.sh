#!/usr/bin/env bash
# Compiles the AWT toolkit into toolkit/build/classes (patched into java.desktop).
set -euo pipefail
cd "$(dirname "$0")/../toolkit"
rm -rf build/classes
mkdir -p build/classes
javac -nowarn --release 21 --patch-module java.desktop=src/main/java \
  -d build/classes $(find src/main/java -name '*.java') 2>&1 | grep -v '^Note:' || true
test -f build/classes/sun/awt/PlatformGraphicsInfo.class
