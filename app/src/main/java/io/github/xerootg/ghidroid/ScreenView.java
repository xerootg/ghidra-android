/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The Ghidra screen. Shows the JVM's frames (through the Surface) and turns
 * Android input into toolkit input events.
 *
 * Fingers become touch gestures, interpreted on the Java side where the
 * component under the finger is known. The stylus and a mouse are pointers:
 * hover moves the pointer (tooltips, hover previews), the stylus button or
 * secondary mouse button right-clicks.
 */
final class ScreenView extends SurfaceView {

    // Input event types; must match io.github.xerootg.ghidroid.awt.Input.
    static final int MOVE = 1, PRESS = 2, RELEASE = 3, WHEEL = 4, KEY_DOWN = 5, KEY_UP = 6,
            TYPED = 7, RESIZE = 8, LEAVE = 9, TOUCH_DOWN = 20, TOUCH_MOVE = 21, TOUCH_UP = 22,
            TOUCH_CANCEL = 23, TOUCH_SCROLL = 24, TOUCH_FLING = 25, WINDOWS = 30, THEME = 31;
    static final int BUTTON1 = 1, BUTTON2 = 2, BUTTON3 = 3;

    /** Pen input suppresses finger input for this long (palm rejection). */
    private static final long PEN_PALM_MS = 600;

    interface Modifiers {
        /** Sticky modifiers from the key bar; consumed by the next key or click. */
        int take();

        /** True once if the key bar's right-click button is armed. */
        boolean takeRightClick();
    }

    private float scale = 1;
    private Modifiers modifiers;
    private PointerIcon pointerIcon;

    // Pointer (stylus/mouse) state.
    private int penButton;
    private int mouseButtons;
    private long lastPenTime;

    // Finger state.
    private boolean twoFinger;
    private float lastCx, lastCy;
    private boolean fingerDown;
    private boolean rightClickTap;
    private VelocityTracker velocity;

    ScreenView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setScale(float s) {
        scale = s;
    }

    void setModifiers(Modifiers m) {
        modifiers = m;
    }

    private int x(MotionEvent e, int i) {
        return Math.round(e.getX(i) / scale);
    }

    private int y(MotionEvent e, int i) {
        return Math.round(e.getY(i) / scale);
    }

    private static void push(int t, int a, int b, int c, int d) {
        Native.pushEvent(t, a, b, c, d);
    }

    // ---- touch, stylus and mouse ------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int tool = e.getToolType(0);
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            return onPen(e);
        }
        if (tool == MotionEvent.TOOL_TYPE_MOUSE || e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            return onMouse(e);
        }
        if (e.getEventTime() - lastPenTime < PEN_PALM_MS) {
            return true;
        }
        return onFinger(e);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int tool = e.getToolType(0);
        boolean pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
        if (pen) {
            lastPenTime = e.getEventTime();
        }
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                push(MOVE, x(e, 0), y(e, 0), 0, KeyMap.modifiers(e.getMetaState()));
                if (!pen) {
                    mouseButtonsChanged(e);
                }
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                push(LEAVE, 0, 0, 0, 0);
                return true;
            case MotionEvent.ACTION_SCROLL: {
                int mods = KeyMap.modifiers(e.getMetaState());
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (v != 0) {
                    push(WHEEL, x(e, 0), y(e, 0), Math.round(-v * 1000), mods);
                }
                if (h != 0) {
                    push(WHEEL, x(e, 0), y(e, 0), Math.round(h * 1000), mods | KeyMap.SHIFT_DOWN);
                }
                return true;
            }
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (!pen) {
                    mouseButtonsChanged(e);
                }
                return true;
            default:
                return super.onGenericMotionEvent(e);
        }
    }

    private boolean onPen(MotionEvent e) {
        lastPenTime = e.getEventTime();
        int x = x(e, 0), y = y(e, 0);
        int mods = KeyMap.modifiers(e.getMetaState());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                boolean side = (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY
                        | MotionEvent.BUTTON_SECONDARY)) != 0;
                if (modifiers != null && modifiers.takeRightClick()) {
                    side = true;
                }
                penButton = side ? BUTTON3 : BUTTON1;
                mods |= modifiers != null ? modifiers.take() : 0;
                push(MOVE, x, y, 0, mods);
                push(PRESS, x, y, penButton, mods);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                push(MOVE, x, y, 0, mods);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (penButton != 0) {
                    push(RELEASE, x, y, penButton, mods);
                    penButton = 0;
                }
                break;
            default:
                break;
        }
        return true;
    }

    private boolean onMouse(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                push(MOVE, x(e, 0), y(e, 0), 0, KeyMap.modifiers(e.getMetaState()));
                break;
            default:
                break;
        }
        mouseButtonsChanged(e);
        return true;
    }

    private void mouseButtonsChanged(MotionEvent e) {
        int now = e.getButtonState() & (MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY
                | MotionEvent.BUTTON_TERTIARY);
        int changed = now ^ mouseButtons;
        if (changed == 0) {
            return;
        }
        int x = x(e, 0), y = y(e, 0);
        int mods = KeyMap.modifiers(e.getMetaState());
        int[][] map = {
            { MotionEvent.BUTTON_PRIMARY, BUTTON1 },
            { MotionEvent.BUTTON_SECONDARY, BUTTON3 },
            { MotionEvent.BUTTON_TERTIARY, BUTTON2 },
        };
        for (int[] m : map) {
            if ((changed & m[0]) != 0) {
                boolean down = (now & m[0]) != 0;
                if (down && modifiers != null) {
                    mods |= modifiers.take();
                }
                push(down ? PRESS : RELEASE, x, y, m[1], mods);
            }
        }
        mouseButtons = now;
    }

    private boolean onFinger(MotionEvent e) {
        if (velocity == null) {
            velocity = VelocityTracker.obtain();
        }
        velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                twoFinger = false;
                fingerDown = true;
                rightClickTap = modifiers != null && modifiers.takeRightClick();
                if (!rightClickTap) {
                    push(TOUCH_DOWN, x(e, 0), y(e, 0), 0, 0);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!twoFinger) {
                    if (!rightClickTap) {
                        push(TOUCH_CANCEL, 0, 0, 0, 0);
                    }
                    twoFinger = true;
                }
                lastCx = centroidX(e);
                lastCy = centroidY(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoFinger) {
                    float cx = centroidX(e), cy = centroidY(e);
                    int dx = Math.round((cx - lastCx) / scale * 100);
                    int dy = Math.round((cy - lastCy) / scale * 100);
                    if (dx != 0 || dy != 0) {
                        push(TOUCH_SCROLL, Math.round(cx / scale), Math.round(cy / scale), dx, dy);
                        lastCx = cx;
                        lastCy = cy;
                    }
                } else if (!rightClickTap) {
                    push(TOUCH_MOVE, x(e, 0), y(e, 0), 0, 0);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // Keep scrolling with the remaining fingers from where they are.
                lastCx = centroidXExcluding(e, e.getActionIndex());
                lastCy = centroidYExcluding(e, e.getActionIndex());
                break;
            case MotionEvent.ACTION_UP:
                if (rightClickTap) {
                    int x = x(e, 0), y = y(e, 0);
                    int mods = modifiers != null ? modifiers.take() : 0;
                    push(MOVE, x, y, 0, mods);
                    push(PRESS, x, y, BUTTON3, mods);
                    push(RELEASE, x, y, BUTTON3, mods);
                } else {
                    velocity.computeCurrentVelocity(1000);
                    int vx = Math.round(velocity.getXVelocity() / scale);
                    int vy = Math.round(velocity.getYVelocity() / scale);
                    if (!twoFinger) {
                        push(TOUCH_UP, x(e, 0), y(e, 0), 0, 0);
                    }
                    push(TOUCH_FLING, vx, vy, 0, 0);
                }
                fingerDown = false;
                velocity.recycle();
                velocity = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                push(TOUCH_CANCEL, 0, 0, 0, 0);
                fingerDown = false;
                velocity.recycle();
                velocity = null;
                break;
            default:
                break;
        }
        return true;
    }

    private static float centroidX(MotionEvent e) {
        return centroidXExcluding(e, -1);
    }

    private static float centroidY(MotionEvent e) {
        return centroidYExcluding(e, -1);
    }

    private static float centroidXExcluding(MotionEvent e, int skip) {
        float s = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i != skip) {
                s += e.getX(i);
                n++;
            }
        }
        return n == 0 ? 0 : s / n;
    }

    private static float centroidYExcluding(MotionEvent e, int skip) {
        float s = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i != skip) {
                s += e.getY(i);
                n++;
            }
        }
        return n == 0 ? 0 : s / n;
    }

    // ---- pointer icon -------------------------------------------------------

    void setAwtCursor(int type) {
        int icon;
        switch (type) {
            case 1: icon = PointerIcon.TYPE_CROSSHAIR; break;
            case 2: icon = PointerIcon.TYPE_TEXT; break;
            case 3: icon = PointerIcon.TYPE_WAIT; break;
            case 4: case 8: icon = PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW; break;
            case 5: case 7: icon = PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW; break;
            case 6: case 9: icon = PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW; break;
            case 10: case 11: icon = PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW; break;
            case 12: icon = PointerIcon.TYPE_HAND; break;
            case 13: icon = PointerIcon.TYPE_ALL_SCROLL; break;
            default: icon = PointerIcon.TYPE_ARROW; break;
        }
        pointerIcon = PointerIcon.getSystemIcon(getContext(), icon);
        setPointerIcon(pointerIcon);
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        return pointerIcon != null ? pointerIcon : super.onResolvePointerIcon(event, pointerIndex);
    }

    // ---- keyboard -----------------------------------------------------------

    /** Sends an Android key event; returns false if it has no AWT equivalent. */
    boolean sendKey(KeyEvent e) {
        int vk = KeyMap.vk(e.getKeyCode());
        if (vk == 0) {
            return false;
        }
        int mods = KeyMap.modifiers(e.getMetaState());
        if (e.getAction() == KeyEvent.ACTION_DOWN && modifiers != null) {
            mods |= modifiers.take();
        }
        int ch = e.getUnicodeChar(e.getMetaState());
        if ((ch & KeyCharacterMapCombining) != 0) {
            ch = 0;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_ENTER || e.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            ch = '\n';
        } else if (e.getKeyCode() == KeyEvent.KEYCODE_DEL) {
            ch = '\b';
        } else if (e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            ch = 0x1b;
        } else if (e.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL) {
            ch = 0x7f;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            push(KEY_DOWN, vk, mods, ch, KeyMap.location(e.getKeyCode()));
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            push(KEY_UP, vk, mods, ch, KeyMap.location(e.getKeyCode()));
        }
        return true;
    }

    private static final int KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT;

    /** Types text from the soft keyboard as key strokes. */
    void typeText(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int mods = modifiers != null ? modifiers.take() : 0;
            int vk = KeyMap.vkForChar(c);
            if (Character.isUpperCase(c)) {
                mods |= KeyMap.SHIFT_DOWN;
            }
            if (vk != 0) {
                push(KEY_DOWN, vk, mods, c, KeyMap.LOCATION_STANDARD);
                push(KEY_UP, vk, mods, c, KeyMap.LOCATION_STANDARD);
            } else {
                push(TYPED, c, mods, 0, 0);
            }
        }
    }

    void tapKey(int vk, int ch) {
        int mods = modifiers != null ? modifiers.take() : 0;
        push(KEY_DOWN, vk, mods, ch, KeyMap.LOCATION_STANDARD);
        push(KEY_UP, vk, mods, ch, KeyMap.LOCATION_STANDARD);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            /** Text shown as composing; already typed into Ghidra. */
            private String composing = "";

            private void replaceComposing(CharSequence text) {
                // Erase what the composition typed so far, then type the new text.
                for (int i = 0; i < composing.length(); i++) {
                    tapKey(KeyMap.VK_BACK_SPACE, '\b');
                }
                typeText(text);
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                replaceComposing(text);
                composing = "";
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                replaceComposing(text);
                composing = text.toString();
                return true;
            }

            @Override
            public boolean finishComposingText() {
                composing = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    tapKey(KeyMap.VK_BACK_SPACE, '\b');
                }
                for (int i = 0; i < afterLength; i++) {
                    tapKey(KeyMap.VK_DELETE, 0x7f);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (!sendKey(event) && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int ch = event.getUnicodeChar();
                    if (ch != 0) {
                        typeText(String.valueOf((char) ch));
                    }
                }
                return true;
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                tapKey(KeyMap.VK_ENTER, '\n');
                return true;
            }
        };
    }
}
