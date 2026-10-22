/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

/**
 * The real backend. The natives live in {@code libghidroid.so}, which the
 * Android side has already loaded into this process; it owns the
 * ANativeWindow and the input queue filled by the Activity.
 */
final class AndroidBackend implements Backend {

    private static native void nativePresent(int[] pixels, int w, int h);

    private static native void nativePresentRegion(int[] pixels, int stride, int key, int x, int y, int w, int h);

    /** Blocks for the next input event; fills {@code out[0..4]}. */
    private static native void nativeNextEvent(int[] out);

    private static native void nativeHostCall(int what, int arg, String text);

    private static native String nativeHostQuery(int what);

    private static final int HOST_CLIPBOARD_SET = 1;
    private static final int HOST_OPEN_URI = 2;
    private static final int HOST_TEXT_FOCUS = 3;
    private static final int HOST_CURSOR = 4;
    private static final int HOST_HAPTIC = 5;
    private static final int HOST_WINDOWS = 6;
    private static final int QUERY_CLIPBOARD = 1;

    AndroidBackend(String lib) {
        System.load(lib);
    }

    @Override
    public void start() {
        Thread t = new Thread(() -> {
            int[] ev = new int[5];
            while (true) {
                nativeNextEvent(ev);
                try {
                    Input.handle(ev[0], ev[1], ev[2], ev[3], ev[4]);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        }, "ghidroid-input");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void present(int[] pixels, int w, int h) {
        nativePresent(pixels, w, h);
    }

    @Override
    public void presentRegion(int[] pixels, int stride, int key, int x, int y, int w, int h) {
        nativePresentRegion(pixels, stride, key, x, y, w, h);
    }

    @Override
    public void setClipboard(String text) {
        nativeHostCall(HOST_CLIPBOARD_SET, 0, text);
    }

    @Override
    public String getClipboard() {
        return nativeHostQuery(QUERY_CLIPBOARD);
    }

    @Override
    public void openUri(String uri) {
        nativeHostCall(HOST_OPEN_URI, 0, uri);
    }

    @Override
    public void textFocus(boolean editing) {
        nativeHostCall(HOST_TEXT_FOCUS, editing ? 1 : 0, null);
    }

    @Override
    public void windowsChanged(String list) {
        nativeHostCall(HOST_WINDOWS, 0, list);
    }

    @Override
    public void haptic() {
        nativeHostCall(HOST_HAPTIC, 0, null);
    }

    @Override
    public void setCursor(int type) {
        nativeHostCall(HOST_CURSOR, type, null);
    }
}
