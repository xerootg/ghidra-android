/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.WindowEvent;

import javax.swing.text.JTextComponent;

/**
 * Window policy for a single fixed screen with no desktop around it: frames
 * fill the screen, dialogs are kept on screen and no larger than it.
 */
final class WindowManager {

    private static final long KEYBOARD_TAP_NANOS = 600_000_000L;

    private WindowManager() {
    }

    static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(WindowManager::event,
                AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK
                        | AWTEvent.FOCUS_EVENT_MASK);
    }

    private static void event(AWTEvent e) {
        int id = e.getID();
        if (id == FocusEvent.FOCUS_GAINED || id == FocusEvent.FOCUS_LOST) {
            if (e.getSource() instanceof JTextComponent t) {
                if (id == FocusEvent.FOCUS_LOST) {
                    Bridge.textFocus(false);
                } else if (t.isEditable() && Input.pressedOn(t, KEYBOARD_TAP_NANOS)) {
                    // Only when the user tapped into the field: Ghidra moves
                    // focus to text fields on its own all the time (filters,
                    // dialogs), and the soft keyboard covers half the screen.
                    Bridge.textFocus(true);
                }
            }
            return;
        }
        if (!(e.getSource() instanceof Window w)) {
            return;
        }
        if (id == WindowEvent.WINDOW_OPENED || id == WindowEvent.WINDOW_CLOSED
                || id == WindowEvent.WINDOW_ACTIVATED || id == WindowEvent.WINDOW_DEACTIVATED
                || id == ComponentEvent.COMPONENT_SHOWN || id == ComponentEvent.COMPONENT_HIDDEN
                || id == ComponentEvent.COMPONENT_RESIZED || id == ComponentEvent.COMPONENT_MOVED) {
            WindowList.changed();
        }
        if (separate) {
            if (id == WindowEvent.WINDOW_OPENED || id == ComponentEvent.COMPONENT_SHOWN
                    || id == WindowEvent.WINDOW_CLOSED || id == ComponentEvent.COMPONENT_HIDDEN
                    || id == ComponentEvent.COMPONENT_RESIZED || id == ComponentEvent.COMPONENT_MOVED) {
                if (WindowList.managed(w) || id == WindowEvent.WINDOW_CLOSED
                        || id == ComponentEvent.COMPONENT_HIDDEN) {
                    layoutSoon();
                } else if (w.isShowing()) {
                    keepInsideOwner(w);
                }
                exposeAll();
            }
            return;
        }
        if (id == WindowEvent.WINDOW_OPENED || id == ComponentEvent.COMPONENT_SHOWN) {
            place(w);
            exposeAll();
        } else if (id == ComponentEvent.COMPONENT_RESIZED && w instanceof Dialog) {
            fit(w);
        } else if (id == WindowEvent.WINDOW_CLOSED || id == ComponentEvent.COMPONENT_HIDDEN
                || id == ComponentEvent.COMPONENT_MOVED || id == ComponentEvent.COMPONENT_RESIZED) {
            exposeAll();
        }
    }

    private static final javax.swing.Timer EXPOSE_SOON = new javax.swing.Timer(60, e -> repaintAll());
    private static final javax.swing.Timer EXPOSE_LATER = new javax.swing.Timer(400, e -> repaintAll());

    static {
        EXPOSE_SOON.setRepeats(false);
        EXPOSE_LATER.setRepeats(false);
    }

    /**
     * A window system sends expose events some time after windows are
     * mapped, moved or unmapped, by which point the application has usually
     * finished filling them; Ghidra relies on that. Cacio paints only when
     * asked, so repaint everything shortly after any window change.
     */
    static void exposeAll() {
        EXPOSE_SOON.restart();
        EXPOSE_LATER.restart();
    }

    private static void repaintAll() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                if (!w.isValid()) {
                    w.validate();
                }
                com.github.caciocavallosilano.cacio.peer.PeerAccess.repaintDecorations(w);
                w.repaint();
            }
        }
    }

    // ---- separate windows (DeX) -------------------------------------------------

    /**
     * Separate-windows mode: every frame and dialog is shown in its own host
     * window (Android windows in DeX). They keep their own sizes and are laid
     * out side by side on the screen, with gaps, so that each host can show
     * its window's rectangle without others on top. Popups and tooltips stay
     * inside the window they belong to.
     */
    static volatile boolean separate;

    /** Space between windows on the screen, so popups that spill over stay hidden. */
    private static final int GUTTER = 64;

    /** Window ids that currently have a host surface (separate mode). */
    static final java.util.Set<Integer> hosted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final javax.swing.Timer LAYOUT = new javax.swing.Timer(30, e -> layout());

    static {
        LAYOUT.setRepeats(false);
    }

    static void setSeparate(boolean on) {
        EventQueue.invokeLater(() -> {
            if (separate == on) {
                return;
            }
            separate = on;
            if (on) {
                layout();
            } else {
                Display.resize(singleWidth, singleHeight);
                for (Window w : Window.getWindows()) {
                    if (w.isShowing()) {
                        place(w);
                    }
                }
            }
            WindowList.changed();
            exposeAll();
        });
    }

    /** Screen size to go back to when leaving separate mode. */
    static volatile int singleWidth = 1280, singleHeight = 800;

    private static void layoutSoon() {
        LAYOUT.restart();
    }

    /** Places managed windows side by side and sizes the screen to fit them. EDT. */
    static void layout() {
        if (!separate) {
            return;
        }
        int x = 0;
        int h = 240;
        for (Window w : WindowList.windows()) {
            Rectangle b = w.getBounds();
            if (b.x != x || b.y != 0) {
                w.setLocation(x, 0);
            }
            x += b.width + GUTTER;
            h = Math.max(h, b.height);
        }
        int width = Math.max(x, 320);
        if (width != Display.getWidth() || h != Display.getHeight()) {
            Display.resizeOnly(width, h);
        }
        WindowList.changed();
        exposeAll();
    }

    /** The managed window a popup or tooltip belongs to, or null. */
    private static Window managedOwner(Window w) {
        for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
            if (WindowList.managed(o)) {
                return o;
            }
        }
        return null;
    }

    /** Moves a popup or tooltip back inside its window's content, where its host can show it. */
    private static void keepInsideOwner(Window w) {
        Window owner = managedOwner(w);
        if (owner == null) {
            return;
        }
        Rectangle c = contentBounds(owner);
        Rectangle b = w.getBounds();
        int nx = Math.max(c.x, Math.min(b.x, c.x + c.width - b.width));
        int ny = Math.max(c.y, Math.min(b.y, c.y + c.height - b.height));
        if (nx != b.x || ny != b.y) {
            w.setLocation(nx, ny);
        }
    }

    /** A window's bounds without Cacio's title bar and border (the host draws its own). */
    static Rectangle contentBounds(Window w) {
        Rectangle b = w.getBounds();
        java.awt.Insets in = w.getInsets();
        return new Rectangle(b.x + in.left, b.y + in.top,
                Math.max(1, b.width - in.left - in.right), Math.max(1, b.height - in.top - in.bottom));
    }

    /** The host resized window id's content area to w x h. */
    static void resizeContent(int id, int w, int h) {
        EventQueue.invokeLater(() -> {
            Window win = WindowList.byId(id);
            if (win == null || !win.isShowing() || w <= 0 || h <= 0) {
                return;
            }
            java.awt.Insets in = win.getInsets();
            int nw = w + in.left + in.right;
            int nh = h + in.top + in.bottom;
            if (win.getWidth() != nw || win.getHeight() != nh) {
                win.setSize(nw, nh);
                win.validate();
            }
            layout();
        });
    }

    /** The host's close button: ask the window to close, as its own close button would. */
    static void closeWindow(int id) {
        EventQueue.invokeLater(() -> {
            Window win = WindowList.byId(id);
            if (win != null && win.isShowing()) {
                win.dispatchEvent(new WindowEvent(win, WindowEvent.WINDOW_CLOSING));
            }
        });
    }

    static void place(Window w) {
        if (separate) {
            layoutSoon();
            return;
        }
        Rectangle screen = Display.getBounds();
        if (w instanceof Frame f && f.isResizable()) {
            if (!w.getBounds().equals(screen)) {
                setBounds(w, screen);
            }
        } else {
            fit(w);
        }
    }

    /** Shrinks a window to the screen and moves it fully on screen. */
    static void fit(Window w) {
        Rectangle screen = Display.getBounds();
        Rectangle b = w.getBounds();
        Rectangle n = new Rectangle(b);
        n.width = Math.min(b.width, screen.width);
        n.height = Math.min(b.height, screen.height);
        n.x = Math.max(0, Math.min(b.x, screen.width - n.width));
        n.y = Math.max(0, Math.min(b.y, screen.height - n.height));
        if (!n.equals(b)) {
            setBounds(w, n);
        }
    }

    /**
     * A native toolkit re-lays out a window after the window system resizes
     * it; nothing does that here, so validate after every move or resize.
     */
    private static void setBounds(Window w, Rectangle r) {
        w.setBounds(r);
        w.validate();
        w.repaint();
    }

    /**
     * Shows a menu of the open windows at the top of the screen. Every
     * frame fills the screen, so this is the only way to reach the ones
     * underneath.
     */
    static void showWindowSwitcher() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        Window top = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || w instanceof com.github.caciocavallosilano.cacio.peer.ProxyWindow
                    || w.getWidth() == 0) {
                continue;
            }
            if (!(w instanceof Frame) && !(w instanceof Dialog)) {
                continue;
            }
            String title = w instanceof Frame f ? f.getTitle() : ((Dialog) w).getTitle();
            if (title == null || title.isBlank()) {
                title = w.getClass().getSimpleName();
            }
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(title);
            item.addActionListener(e -> {
                w.toFront();
                w.requestFocus();
                exposeAll();
            });
            menu.add(item);
            if (w.isFocused() || top == null) {
                top = w;
            }
        }
        if (top == null) {
            return;
        }
        Rectangle screen = Display.getBounds();
        java.awt.Dimension d = menu.getPreferredSize();
        java.awt.Point p = new java.awt.Point((screen.width - d.width) / 2, 40);
        javax.swing.SwingUtilities.convertPointFromScreen(p, top);
        menu.show(top, p.x, p.y);
    }

    static void screenResized(int width, int height) {
        EventQueue.invokeLater(() -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing()) {
                    place(w);
                }
            }
            exposeAll();
        });
    }
}
