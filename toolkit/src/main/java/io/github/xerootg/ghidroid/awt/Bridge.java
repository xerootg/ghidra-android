/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

/**
 * Starts the backend and the frame pump. Called once when the toolkit is
 * created.
 *
 * {@code -Dghidroid.backend=android:/path/libghidroid.so} on the device,
 * {@code -Dghidroid.backend=socket:7777} for desktop development.
 */
public final class Bridge {

    private static final long FRAME_NANOS = 1_000_000_000L / 60;

    /** Idle refresh: catches the end of paints that outlast the settle time. */
    private static final int IDLE_FRAMES = 15;

    private static Backend backend;

    private Bridge() {
    }

    public static synchronized void start() {
        if (backend != null) {
            return;
        }
        String spec = System.getProperty("ghidroid.backend", "socket:7777");
        int colon = spec.indexOf(':');
        String kind = colon < 0 ? spec : spec.substring(0, colon);
        String arg = colon < 0 ? "" : spec.substring(colon + 1);
        Backend b = switch (kind) {
            case "android" -> new AndroidBackend(arg);
            case "socket" -> new SocketBackend(Integer.parseInt(arg.isEmpty() ? "7777" : arg));
            default -> throw new IllegalArgumentException("ghidroid.backend: " + spec);
        };
        try {
            b.start();
        } catch (Exception e) {
            throw new IllegalStateException("display backend failed", e);
        }
        backend = b;
        Thread pump = new Thread(Bridge::pump, "ghidroid-frames");
        pump.setDaemon(true);
        pump.setPriority(Thread.MAX_PRIORITY - 1);
        pump.start();
        Thread wm = new Thread(WindowManager::install, "ghidroid-wm-init");
        wm.setDaemon(true);
        wm.start();
    }

    static Backend backend() {
        return backend;
    }

    private static void pump() {
        long next = System.nanoTime();
        int idle = 0;
        while (true) {
            if (Display.isDirty() || ++idle >= IDLE_FRAMES) {
                idle = 0;
                int[] px = Display.getPixels();
                int w = Display.getWidth();
                int h = Display.getHeight();
                if (px.length >= w * h) {
                    if (WindowManager.separate && !backend.wholeScreen()) {
                        presentWindows(px, w, h);
                    } else {
                        backend.present(px, w, h);
                    }
                }
            }
            next += FRAME_NANOS;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    /** Separate mode: each hosted window's content area to its own surface. */
    private static void presentWindows(int[] px, int sw, int sh) {
        for (int id : WindowManager.hosted) {
            java.awt.Window win = WindowList.byId(id);
            if (win == null || !win.isShowing()) {
                continue;
            }
            java.awt.Rectangle r = WindowManager.contentBounds(win).intersection(new java.awt.Rectangle(0, 0, sw, sh));
            if (!r.isEmpty()) {
                backend.presentRegion(px, sw, id, r.x, r.y, r.width, r.height);
            }
        }
    }

    public static void setClipboard(String text) {
        if (backend != null) {
            backend.setClipboard(text);
        }
    }

    public static String getClipboard() {
        return backend == null ? null : backend.getClipboard();
    }

    public static void openUri(String uri) {
        if (backend != null) {
            backend.openUri(uri);
        }
    }

    public static void setCursor(int type) {
        if (backend != null) {
            backend.setCursor(type);
        }
    }

    static void windowsChanged(String list) {
        if (backend != null) {
            backend.windowsChanged(list);
        }
    }

    static void haptic() {
        if (backend != null) {
            backend.haptic();
        }
    }

    public static java.awt.peer.DesktopPeer createDesktopPeer() {
        return new HostDesktopPeer();
    }

    static void textFocus(boolean editing) {
        if (backend != null) {
            backend.textFocus(editing);
        }
    }
}
