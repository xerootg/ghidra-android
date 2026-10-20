# compiler-patch

`javax.tools.ToolProvider` from OpenJDK 21.0.1 (GPL-2.0 with Classpath
exception), patched into `java.compiler` with `--patch-module` so that
`getSystemJavaCompiler()` falls back to javac on the class path. Ghidra
compiles its Java scripts through this call; the Android JRE has no
`jdk.compiler` module. Built by `scripts/prepare.sh`.
