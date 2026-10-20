/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

/** Where frames go and input comes from. */
interface Backend {

    /** Starts delivering input to {@link Input#handle}. */
    void start() throws Exception;

    /** Shows a frame. {@code pixels} is RGBX, {@code w * h} ints. */
    void present(int[] pixels, int w, int h);

    /** Copies text to the host clipboard. */
    default void setClipboard(String text) {
    }

    /** Returns the host clipboard text, or null. */
    default String getClipboard() {
        return null;
    }

    /** Opens a URI (help links, file locations) on the host. */
    default void openUri(String uri) {
    }

    /** Called when a text component gains or loses focus. */
    default void textFocus(boolean editing) {
    }

    /** Short vibration, e.g. when a press becomes a hold. */
    default void haptic() {
    }

    /** Sets the pointer shape for mouse and stylus hover ({@link java.awt.Cursor} type). */
    default void setCursor(int type) {
    }
}
