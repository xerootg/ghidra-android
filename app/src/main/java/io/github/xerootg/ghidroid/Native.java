/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.view.Surface;

/** The ART half of libghidroid.so (see bridge.c). */
public final class Native {

    static {
        System.loadLibrary("ghidroid");
    }

    /** Host call codes; must match AndroidBackend. */
    static final int HOST_CLIPBOARD_SET = 1;
    static final int HOST_OPEN_URI = 2;
    static final int HOST_TEXT_FOCUS = 3;
    static final int HOST_CURSOR = 4;
    static final int HOST_HAPTIC = 5;
    static final int QUERY_CLIPBOARD = 1;

    /** Receives calls the JVM makes into Android. */
    interface Host {
        void onHostCall(int what, int arg, String text);

        String onHostQuery(int what);

        void onJvmExit(int code);
    }

    private static volatile Host host;

    private Native() {
    }

    static void setHost(Host h) {
        host = h;
    }

    static native void setSurface(Surface surface);

    static native void pushEvent(int type, int a, int b, int c, int d);

    static native void launchJvm(String[] args, String javaHome, String logPath);

    // Called from HotSpot threads through bridge.c.

    static void onHostCall(int what, int arg, String text) {
        Host h = host;
        if (h != null) {
            h.onHostCall(what, arg, text);
        }
    }

    static String onHostQuery(int what) {
        Host h = host;
        return h != null ? h.onHostQuery(what) : null;
    }

    static void onJvmExit(int code) {
        Host h = host;
        if (h != null) {
            h.onJvmExit(code);
        }
    }
}
