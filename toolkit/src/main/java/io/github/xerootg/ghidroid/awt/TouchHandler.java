/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;

/**
 * Finger input. Mouse and stylus events go straight to {@link Input}; a
 * finger is not a mouse, so its gestures are interpreted here, where the
 * component under it is known:
 *
 * <ul>
 * <li>tap: left click (two quick taps: double click)</li>
 * <li>drag on scrollable content: scroll, with fling</li>
 * <li>drag on a scroll bar, divider, slider, header, title bar, or anything
 * that does not scroll (like the function graph): left drag</li>
 * <li>press and hold, then lift: right click (context menu)</li>
 * <li>press and hold, then drag: left drag (selecting text or bytes)</li>
 * <li>two fingers (handled by the host): scroll</li>
 * </ul>
 */
final class TouchHandler {

    private static final int SLOP = 10;
    private static final long HOLD_MS = 450;
    private static final double DEFAULT_PX_PER_NOTCH = 50;

    private enum Mode { IDLE, PENDING, HELD, DRAG, SCROLL }

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ghidroid-touch");
                t.setDaemon(true);
                return t;
            });

    private static Mode mode = Mode.IDLE;
    private static int ox, oy, lx, ly;
    private static Component target;
    private static ScheduledFuture<?> hold;
    private static ScheduledFuture<?> fling;
    private static int gesture;

    private TouchHandler() {
    }

    // All entry points run under the Input lock.

    static void down(int x, int y) {
        stopFling();
        cancelHold();
        mode = Mode.PENDING;
        ox = lx = x;
        oy = ly = y;
        target = componentAt(x, y);
        Input.mouseMove(x, y, 0);
        final int g = ++gesture;
        hold = TIMER.schedule(() -> {
            synchronized (Input.class) {
                if (g == gesture && mode == Mode.PENDING) {
                    mode = Mode.HELD;
                    Bridge.haptic();
                }
            }
        }, HOLD_MS, TimeUnit.MILLISECONDS);
    }

    static void move(int x, int y) {
        switch (mode) {
            case PENDING:
                if (far(x, y)) {
                    cancelHold();
                    if (isDragTarget(target)) {
                        startDrag(x, y);
                    } else {
                        mode = Mode.SCROLL;
                        scroll(x, y, x - lx, y - ly);
                    }
                }
                break;
            case HELD:
                if (far(x, y)) {
                    startDrag(x, y);
                }
                break;
            case DRAG:
                Input.mouseMove(x, y, 0);
                break;
            case SCROLL:
                scroll(x, y, x - lx, y - ly);
                break;
            default:
                break;
        }
        lx = x;
        ly = y;
    }

    static void up(int x, int y) {
        cancelHold();
        switch (mode) {
            case PENDING:
                Input.mousePress(ox, oy, MouseEvent.BUTTON1, 0);
                Input.mouseRelease(ox, oy, MouseEvent.BUTTON1, 0);
                break;
            case HELD:
                Input.mousePress(ox, oy, MouseEvent.BUTTON3, 0);
                Input.mouseRelease(ox, oy, MouseEvent.BUTTON3, 0);
                break;
            case DRAG:
                Input.mouseRelease(x, y, MouseEvent.BUTTON1, 0);
                break;
            default:
                break;
        }
        mode = Mode.IDLE;
    }

    static void cancel() {
        cancelHold();
        if (mode == Mode.DRAG) {
            Input.mouseRelease(lx, ly, MouseEvent.BUTTON1, 0);
        }
        mode = Mode.IDLE;
    }

    /** Two-finger scroll from the host, in screen pixels. */
    static void twoFingerScroll(int x, int y, double dx, double dy) {
        cancel();
        stopFling();
        target = componentAt(x, y);
        scroll(x, y, dx, dy);
    }

    /** Continues a scroll after the finger lifts; velocity in pixels/second. */
    static void fling(double vx, double vy) {
        if (target == null || Math.hypot(vx, vy) < 300) {
            return;
        }
        stopFling();
        final int px = lx, py = ly;
        final double[] v = { vx, vy };
        final int g = gesture;
        fling = TIMER.scheduleAtFixedRate(() -> {
            synchronized (Input.class) {
                if (g != gesture) {
                    return;
                }
                v[0] *= 0.94;
                v[1] *= 0.94;
                if (Math.hypot(v[0], v[1]) < 60) {
                    stopFling();
                    return;
                }
                scroll(px, py, v[0] / 60, v[1] / 60);
            }
        }, 16, 16, TimeUnit.MILLISECONDS);
    }

    private static void startDrag(int x, int y) {
        mode = Mode.DRAG;
        Input.mousePress(ox, oy, MouseEvent.BUTTON1, 0);
        Input.mouseMove(x, y, 0);
    }

    private static boolean far(int x, int y) {
        return Math.abs(x - ox) > SLOP || Math.abs(y - oy) > SLOP;
    }

    private static void cancelHold() {
        if (hold != null) {
            hold.cancel(false);
            hold = null;
        }
    }

    private static void stopFling() {
        if (fling != null) {
            fling.cancel(false);
            fling = null;
        }
    }

    private static void scroll(int x, int y, double dx, double dy) {
        double perNotch = pxPerNotch(target);
        // Content follows the finger: moving the finger down scrolls up.
        if (dy != 0) {
            Input.mouseWheel(x, y, -dy / perNotch, 0);
        }
        if (dx != 0) {
            Input.mouseWheel(x, y, -dx / perNotch, InputEvent.SHIFT_DOWN_MASK);
        }
    }

    private static double pxPerNotch(Component c) {
        JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, c);
        if (sp != null && sp.getVerticalScrollBar() != null) {
            int unit = sp.getVerticalScrollBar().getUnitIncrement(1);
            if (unit > 1) {
                return unit * 3.0;
            }
        }
        return DEFAULT_PX_PER_NOTCH;
    }

    private static Component componentAt(int x, int y) {
        Window[] windows = Window.getWindows();
        // Later windows are on top in Cacio's stacking.
        for (int i = windows.length - 1; i >= 0; i--) {
            Window w = windows[i];
            if (w.isShowing() && w.getBounds().contains(x, y)) {
                Point p = new Point(x, y);
                SwingUtilities.convertPointFromScreen(p, w);
                Component c = SwingUtilities.getDeepestComponentAt(w, p.x, p.y);
                return c != null ? c : w;
            }
        }
        return null;
    }

    /** Where a drag means "drag this", not "scroll". */
    static boolean isDragTarget(Component c) {
        if (c == null) {
            return true;
        }
        boolean scrollable = false;
        for (Component p = c; p != null; p = p.getParent()) {
            if (p instanceof JScrollBar || p instanceof JSlider || p instanceof JTableHeader) {
                return true;
            }
            String name = p.getClass().getName();
            if (name.contains("Divider") || name.contains("TitlePane") || name.contains("DockableHeader")
                    || name.contains("TitleBar")) {
                return true;
            }
            if (p instanceof JSplitPane) {
                break;
            }
            if (p instanceof JScrollPane || p.getMouseWheelListeners().length > 0) {
                scrollable = true;
                break;
            }
            if (p instanceof Window || !(p instanceof Container)) {
                break;
            }
        }
        return !scrollable;
    }
}
