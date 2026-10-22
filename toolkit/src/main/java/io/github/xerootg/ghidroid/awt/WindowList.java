/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Window;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.github.caciocavallosilano.cacio.peer.ProxyWindow;

/**
 * Ghidra's windows as the host sees them: every showing frame and dialog,
 * with a stable id, its title, and which one is active. The host (the
 * window tray, the Alt+` switcher, separate Android windows in DeX) gets
 * the list whenever it changes and can bring a window to the front.
 *
 * Message format, one window per line:
 * {@code id \t kind(f|d|m) \t active(0|1) \t x,y,w,h \t title}
 * where kind is frame, dialog or modal dialog and x,y,w,h is the content
 * area on the screen (without Cacio's title bar).
 */
public final class WindowList {

    private static final Map<Window, Integer> IDS = new WeakHashMap<>();
    private static int nextId = 1;
    private static String last = "";

    private static final PropertyChangeListener TITLE = e -> changed();

    private static final javax.swing.Timer PUBLISH = new javax.swing.Timer(80, e -> publish());

    static {
        PUBLISH.setRepeats(false);
    }

    private WindowList() {
    }

    /** True for the windows the host lists: frames and dialogs, not popups or proxies. */
    static boolean managed(Window w) {
        return (w instanceof Frame || w instanceof Dialog) && !(w instanceof ProxyWindow)
                && w.isShowing() && w.getWidth() > 0 && w.getHeight() > 0;
    }

    static synchronized int id(Window w) {
        Integer id = IDS.get(w);
        if (id == null) {
            id = nextId++;
            IDS.put(w, id);
            w.addPropertyChangeListener("title", TITLE);
        }
        return id;
    }

    static synchronized Window byId(int id) {
        for (Map.Entry<Window, Integer> e : IDS.entrySet()) {
            if (e.getValue() == id) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Something about the windows changed; publish soon (coalesced). Any thread. */
    static void changed() {
        EventQueue.invokeLater(PUBLISH::restart);
    }

    /** Managed windows, bottom to top as created. Call on the EDT. */
    static List<Window> windows() {
        List<Window> out = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (managed(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static void publish() {
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        StringBuilder sb = new StringBuilder();
        for (Window w : windows()) {
            String title = w instanceof Frame f ? f.getTitle() : ((Dialog) w).getTitle();
            if (title == null || title.isBlank()) {
                title = w.getClass().getSimpleName();
            }
            char kind = w instanceof Dialog d ? (d.isModal() ? 'm' : 'd') : 'f';
            Rectangle b = WindowManager.contentBounds(w);
            sb.append(id(w)).append('\t').append(kind).append('\t')
                    .append(w == active ? '1' : '0').append('\t')
                    .append(b.x).append(',').append(b.y).append(',').append(b.width).append(',').append(b.height)
                    .append('\t').append(title.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        String msg = sb.toString();
        if (!msg.equals(last)) {
            last = msg;
            Bridge.windowsChanged(msg);
        }
    }

    /** Brings a window to the front and focuses it (host request). */
    static void front(int id) {
        EventQueue.invokeLater(() -> {
            Window w = byId(id);
            if (w != null && w.isShowing()) {
                w.toFront();
                w.requestFocus();
                WindowManager.exposeAll();
                changed();
            }
        });
    }
}
