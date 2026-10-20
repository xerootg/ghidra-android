cmake_minimum_required(VERSION 3.22)
project(ghidroid C)

add_library(ghidroid SHARED bridge.c)
target_compile_options(ghidroid PRIVATE -Wall -Wextra -O2)
target_link_libraries(ghidroid android log dl)

# Stand-in for the JRE's missing libawt_xawt.so; copied into the JRE's lib
# directory at install time. It must link against the JRE's
# libawt_headless.so, which is not available at build time, so the
# dependency is added by name.
add_library(awt_xawt SHARED xawt_stub.c)
target_compile_options(awt_xawt PRIVATE -Wall -O2)
target_link_options(awt_xawt PRIVATE -Wl,--no-as-needed -Wl,--allow-shlib-undefined)
target_link_libraries(awt_xawt "-L${CMAKE_CURRENT_SOURCE_DIR}/stubs" awt_headless)
