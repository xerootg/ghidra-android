/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.EventQueue;
import java.io.File;
import java.lang.reflect.Method;

/**
 * Switches Ghidra's theme while it runs, when Android's dark mode changes.
 *
 * The toolkit lives in java.desktop and cannot link against Ghidra, so this
 * goes through reflection on Ghidra's public theme API. The theme files are
 * named by {@code ghidroid.theme.dark|black|light}: a {@code .theme} file path,
 * or a GTheme class name.
 */
final class GhidraTheme {

    static final int LIGHT = 0;
    static final int DARK = 1;
    static final int BLACK = 2;

    private GhidraTheme() {
    }

    static void apply(int kind) {
        String key = switch (kind) {
            case DARK -> "ghidroid.theme.dark";
            case BLACK -> "ghidroid.theme.black";
            default -> "ghidroid.theme.light";
        };
        String locator = System.getProperty(key);
        if (locator == null || locator.isEmpty()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                ClassLoader cl = ClassLoader.getSystemClassLoader();
                Class<?> gtheme = Class.forName("generic.theme.GTheme", true, cl);
                Object theme;
                if (locator.endsWith(".theme")) {
                    Class<?> reader = Class.forName("generic.theme.ThemeReader", true, cl);
                    var ctor = reader.getDeclaredConstructor(File.class);
                    ctor.setAccessible(true);
                    Object r = ctor.newInstance(new File(locator));
                    Method read = reader.getMethod("readTheme");
                    read.setAccessible(true); // ThemeReader is package-private
                    theme = read.invoke(r);
                } else {
                    theme = Class.forName(locator, true, cl).getDeclaredConstructor().newInstance();
                }
                Class<?> tm = Class.forName("generic.theme.ThemeManager", true, cl);
                Object manager = tm.getMethod("getInstance").invoke(null);
                Object active = tm.getMethod("getActiveTheme").invoke(manager);
                Method name = gtheme.getMethod("getName");
                if (active != null && name.invoke(active).equals(name.invoke(theme))) {
                    return;
                }
                tm.getMethod("setTheme", gtheme).invoke(manager, theme);
                // Ghidra updates its own windows; Cacio's decoration proxies
                // (title bars, borders) need it too.
                // Cacio also gave every window a background from the theme of
                // the moment it was created.
                java.awt.Color bg = javax.swing.UIManager.getColor("Panel.background");
                java.awt.Color fg = javax.swing.UIManager.getColor("windowText");
                for (java.awt.Window w : java.awt.Window.getWindows()) {
                    if (w instanceof com.github.caciocavallosilano.cacio.peer.ProxyWindow) {
                        javax.swing.SwingUtilities.updateComponentTreeUI(w);
                        // Repainting the proxy window itself does nothing;
                        // its root pane paints through the real window.
                        for (java.awt.Component c : w.getComponents()) {
                            if (c instanceof javax.swing.JRootPane rp) {
                                com.github.caciocavallosilano.cacio.peer.PeerAccess.styleTitleBar(rp);
                            }
                            if (c instanceof javax.swing.JComponent j) {
                                j.revalidate();
                                j.repaint();
                            }
                        }
                        continue;
                    }
                    if (bg != null) {
                        w.setBackground(bg);
                    }
                    if (fg != null) {
                        w.setForeground(fg);
                    }
                }
                WindowManager.exposeAll();
            } catch (ReflectiveOperationException | LinkageError e) {
                System.err.println("ghidroid: cannot switch theme: " + e);
            }
        });
    }

    /** File > Exit Ghidra, through Ghidra's AppInfo (reflection, like the theme API). */
    static void exitGhidra() {
        EventQueue.invokeLater(() -> {
            try {
                Class<?> app = Class.forName("ghidra.framework.main.AppInfo", true,
                        ClassLoader.getSystemClassLoader());
                app.getMethod("exitGhidra").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                System.err.println("ghidroid: cannot exit Ghidra: " + e);
            }
        });
    }
}
