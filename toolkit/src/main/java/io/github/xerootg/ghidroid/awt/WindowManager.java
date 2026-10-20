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
                Bridge.textFocus(id == FocusEvent.FOCUS_GAINED && t.isEditable());
            }
            return;
        }
        if (!(e.getSource() instanceof Window w)) {
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
                w.repaint();
            }
        }
    }

    static void place(Window w) {
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
