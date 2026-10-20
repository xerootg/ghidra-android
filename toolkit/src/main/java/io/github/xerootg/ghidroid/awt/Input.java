/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import com.github.caciocavallosilano.cacio.ctc.CTCEventSource;
import com.github.caciocavallosilano.cacio.ctc.CTCScreen;
import com.github.caciocavallosilano.cacio.peer.CacioMouseInfoPeer;
import com.github.caciocavallosilano.cacio.peer.managed.EventData;

/**
 * Turns the backend's input messages into Cacio events.
 *
 * Coordinates are screen pixels. Modifiers are AWT extended modifiers
 * ({@code *_DOWN_MASK}) for the keyboard state; this class adds the mouse
 * button masks itself.
 */
public final class Input {

    public static final int MOVE = 1;
    public static final int PRESS = 2;
    public static final int RELEASE = 3;
    /** a,b = position, c = wheel rotation * 1000, d = modifiers. */
    public static final int WHEEL = 4;
    /** a = VK code, b = modifiers, c = key char (0 = none), d = key location. */
    public static final int KEY_DOWN = 5;
    public static final int KEY_UP = 6;
    /** a = char, b = modifiers. */
    public static final int TYPED = 7;
    /** a = width, b = height. */
    public static final int RESIZE = 8;
    /** Pointer left the screen (stylus hover exit). */
    public static final int LEAVE = 9;
    /** The surface was recreated; send a frame even if nothing changed. */
    public static final int REDRAW = 10;
    /** Shows the window switcher. */
    public static final int WINDOWS = 30;
    /** Prints the window and component tree to stdout. */
    public static final int DEBUG_DUMP = 99;

    /** Finger events (see {@link TouchHandler}): a,b = position. */
    public static final int TOUCH_DOWN = 20;
    public static final int TOUCH_MOVE = 21;
    public static final int TOUCH_UP = 22;
    public static final int TOUCH_CANCEL = 23;
    /** Two-finger scroll: a,b = position, c,d = delta * 100. */
    public static final int TOUCH_SCROLL = 24;
    /** Fling after a scroll: a,b = velocity in pixels per second. */
    public static final int TOUCH_FLING = 25;

    private static final long MULTI_CLICK_NANOS = 450_000_000L;
    private static final int CLICK_SLOP = 8;

    private static int buttons;
    private static int x, y;
    private static int pressX, pressY, pressButton;
    private static long lastPressTime;
    private static int lastPressX, lastPressY, lastPressButton;
    private static int clickCount;
    private static volatile long lastPressNanos;

    /** True if a pointer was pressed on {@code c} in the last {@code nanos}. */
    static boolean pressedOn(java.awt.Component c, long nanos) {
        if (System.nanoTime() - lastPressNanos >= nanos || !c.isShowing()) {
            return false;
        }
        java.awt.Point p = c.getLocationOnScreen();
        return lastPressX >= p.x && lastPressY >= p.y
                && lastPressX < p.x + c.getWidth() && lastPressY < p.y + c.getHeight();
    }

    private Input() {
    }

    public static synchronized void handle(int type, int a, int b, int c, int d) {
        switch (type) {
            case MOVE:
                move(a, b, d);
                break;
            case PRESS:
                press(a, b, c, d);
                break;
            case RELEASE:
                release(a, b, c, d);
                break;
            case WHEEL:
                wheel(a, b, c / 1000.0, d);
                break;
            case KEY_DOWN:
                key(KeyEvent.KEY_PRESSED, a, b, c);
                break;
            case KEY_UP:
                key(KeyEvent.KEY_RELEASED, a, b, c);
                break;
            case TYPED:
                typed((char) a, b);
                break;
            case RESIZE:
                Display.resize(a, b);
                break;
            case LEAVE:
                break;
            case REDRAW:
                Display.markDirty();
                break;
            case WINDOWS:
                java.awt.EventQueue.invokeLater(WindowManager::showWindowSwitcher);
                break;
            case DEBUG_DUMP:
                java.awt.EventQueue.invokeLater(Input::dumpWindows);
                break;
            case TOUCH_DOWN:
                TouchHandler.down(a, b);
                break;
            case TOUCH_MOVE:
                TouchHandler.move(a, b);
                break;
            case TOUCH_UP:
                TouchHandler.up(a, b);
                break;
            case TOUCH_CANCEL:
                TouchHandler.cancel();
                break;
            case TOUCH_SCROLL:
                TouchHandler.twoFingerScroll(a, b, c / 100.0, d / 100.0);
                break;
            case TOUCH_FLING:
                TouchHandler.fling(a, b);
                break;
            default:
                break;
        }
    }

    private static void dumpWindows() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            dump(w, 0, 7);
        }
    }

    private static void dump(java.awt.Component c, int depth, int max) {
        System.out.println("  ".repeat(depth) + c.getClass().getName() + " " + c.getBounds()
                + (c.isVisible() ? "" : " HIDDEN") + (c.isValid() ? "" : " INVALID")
                + (c.isShowing() ? "" : " notShowing"));
        if (depth < max && c instanceof java.awt.Container k) {
            for (java.awt.Component ch : k.getComponents()) {
                dump(ch, depth + 1, max);
            }
        }
    }

    private static int buttonMask(int button) {
        switch (button) {
            case MouseEvent.BUTTON1:
                return InputEvent.BUTTON1_DOWN_MASK;
            case MouseEvent.BUTTON2:
                return InputEvent.BUTTON2_DOWN_MASK;
            case MouseEvent.BUTTON3:
                return InputEvent.BUTTON3_DOWN_MASK;
            default:
                return 0;
        }
    }

    private static EventData mouse(int id, int button, int mods) {
        EventData ev = new EventData();
        ev.setId(id);
        ev.setSource(CTCScreen.getInstance());
        ev.setTime(System.currentTimeMillis());
        ev.setModifiers(mods | buttons);
        ev.setX(x);
        ev.setY(y);
        ev.setButton(button);
        ev.setClickCount(id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED
                ? 0 : clickCount);
        return ev;
    }

    private static void post(EventData ev) {
        CTCEventSource.getInstance().postEvent(ev);
    }

    private static void setPos(int nx, int ny) {
        x = nx;
        y = ny;
        CacioMouseInfoPeer.getInstance().setMouseScreenCoordinates(nx, ny);
    }

    static void mouseMove(int nx, int ny, int mods) {
        move(nx, ny, mods);
    }

    static void mousePress(int nx, int ny, int button, int mods) {
        press(nx, ny, button, mods);
    }

    static void mouseRelease(int nx, int ny, int button, int mods) {
        release(nx, ny, button, mods);
    }

    static void mouseWheel(int nx, int ny, double rotation, int mods) {
        wheel(nx, ny, rotation, mods);
    }

    private static void move(int nx, int ny, int mods) {
        setPos(nx, ny);
        int id = buttons != 0 ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED;
        post(mouse(id, MouseEvent.NOBUTTON, mods));
    }

    private static void press(int nx, int ny, int button, int mods) {
        if (x != nx || y != ny) {
            move(nx, ny, mods);
        }
        long now = System.nanoTime();
        lastPressNanos = now;
        boolean multi = now - lastPressTime < MULTI_CLICK_NANOS
                && button == lastPressButton
                && Math.abs(nx - lastPressX) <= CLICK_SLOP
                && Math.abs(ny - lastPressY) <= CLICK_SLOP;
        clickCount = multi ? clickCount + 1 : 1;
        lastPressTime = now;
        lastPressX = pressX = nx;
        lastPressY = pressY = ny;
        lastPressButton = pressButton = button;
        buttons |= buttonMask(button);
        EventData ev = mouse(MouseEvent.MOUSE_PRESSED, button, mods);
        // Linux convention: the popup trigger is on press.
        ev.setPopup(button == MouseEvent.BUTTON3);
        post(ev);
    }

    private static void release(int nx, int ny, int button, int mods) {
        if (x != nx || y != ny) {
            move(nx, ny, mods);
        }
        // The release event still carries the button mask, like a native one.
        EventData ev = mouse(MouseEvent.MOUSE_RELEASED, button, mods);
        buttons &= ~buttonMask(button);
        post(ev);
        if (button == pressButton
                && Math.abs(nx - pressX) <= CLICK_SLOP
                && Math.abs(ny - pressY) <= CLICK_SLOP) {
            post(mouse(MouseEvent.MOUSE_CLICKED, button, mods));
        } else {
            clickCount = 0;
        }
    }

    private static void wheel(int nx, int ny, double rotation, int mods) {
        setPos(nx, ny);
        EventData ev = mouse(MouseEvent.MOUSE_WHEEL, MouseEvent.NOBUTTON, mods);
        ev.setWheelRotation(rotation);
        post(ev);
    }

    private static void key(int id, int vk, int mods, int ch) {
        EventData ev = new EventData();
        ev.setSource(CTCScreen.getInstance());
        ev.setId(id);
        ev.setTime(System.currentTimeMillis());
        ev.setModifiers(mods | buttons);
        ev.setKeyCode(vk);
        ev.setKeyChar(ch == 0 ? KeyEvent.CHAR_UNDEFINED : (char) ch);
        post(ev);
        if (id == KeyEvent.KEY_PRESSED && ch != 0 && isTypable((char) ch, mods)) {
            typed((char) ch, mods);
        }
    }

    private static boolean isTypable(char ch, int mods) {
        // Ctrl/Meta chords are shortcuts, not text (AltGr is fine).
        if ((mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0
                && (mods & InputEvent.ALT_GRAPH_DOWN_MASK) == 0) {
            return false;
        }
        return ch >= 0x20 && ch != 0x7f || ch == '\n' || ch == '\t' || ch == '\b' || ch == 0x1b;
    }

    private static void typed(char ch, int mods) {
        EventData ev = new EventData();
        ev.setSource(CTCScreen.getInstance());
        ev.setId(KeyEvent.KEY_TYPED);
        ev.setTime(System.currentTimeMillis());
        ev.setModifiers(mods | buttons);
        ev.setKeyCode(KeyEvent.VK_UNDEFINED);
        ev.setKeyChar(ch);
        post(ev);
    }
}
