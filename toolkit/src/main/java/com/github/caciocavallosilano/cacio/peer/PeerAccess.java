/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package com.github.caciocavallosilano.cacio.peer;

import java.awt.Component;

/** Ghidra for Android: public entry points into package-private peer code. */
public final class PeerAccess {

    private PeerAccess() {
    }

    /** Gives a decoration root pane the current theme's title bar colors. */
    public static void styleTitleBar(javax.swing.JRootPane rootPane) {
        CacioWindowPeer.styleTitleBar(rootPane);
    }

    /** Repaints a window's title bar and border. Call on the EDT. */
    public static void repaintDecorations(Component c) {
        CacioComponentPeer.repaintPeer(c);
    }
}
