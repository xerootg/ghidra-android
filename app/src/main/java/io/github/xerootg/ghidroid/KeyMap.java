/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.util.SparseIntArray;
import android.view.KeyEvent;

/** Android key codes and meta state to AWT virtual keys and modifiers. */
final class KeyMap {

    // java.awt.event.KeyEvent values.
    static final int VK_ENTER = 0x0A, VK_BACK_SPACE = 0x08, VK_TAB = 0x09, VK_CANCEL = 0x03,
            VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_ALT = 0x12, VK_PAUSE = 0x13, VK_CAPS_LOCK = 0x14,
            VK_ESCAPE = 0x1B, VK_SPACE = 0x20, VK_PAGE_UP = 0x21, VK_PAGE_DOWN = 0x22, VK_END = 0x23,
            VK_HOME = 0x24, VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28,
            VK_COMMA = 0x2C, VK_MINUS = 0x2D, VK_PERIOD = 0x2E, VK_SLASH = 0x2F, VK_SEMICOLON = 0x3B,
            VK_EQUALS = 0x3D, VK_OPEN_BRACKET = 0x5B, VK_BACK_SLASH = 0x5C, VK_CLOSE_BRACKET = 0x5D,
            VK_DELETE = 0x7F, VK_NUM_LOCK = 0x90, VK_SCROLL_LOCK = 0x91, VK_F1 = 0x70,
            VK_PRINTSCREEN = 0x9A, VK_INSERT = 0x9B, VK_META = 0x9D, VK_BACK_QUOTE = 0xC0,
            VK_QUOTE = 0xDE, VK_CONTEXT_MENU = 0x020D, VK_NUMPAD0 = 0x60, VK_MULTIPLY = 0x6A,
            VK_ADD = 0x6B, VK_SUBTRACT = 0x6D, VK_DECIMAL = 0x6E, VK_DIVIDE = 0x6F, VK_AT = 0x0200,
            VK_0 = 0x30, VK_A = 0x41;

    // java.awt.event.InputEvent extended modifiers.
    static final int SHIFT_DOWN = 1 << 6, CTRL_DOWN = 1 << 7, META_DOWN = 1 << 8, ALT_DOWN = 1 << 9,
            ALT_GRAPH_DOWN = 1 << 13;

    // java.awt.event.KeyEvent locations.
    static final int LOCATION_STANDARD = 1, LOCATION_LEFT = 2, LOCATION_RIGHT = 3, LOCATION_NUMPAD = 4;

    private static final SparseIntArray MAP = new SparseIntArray();

    static {
        for (int i = 0; i < 26; i++) {
            MAP.put(KeyEvent.KEYCODE_A + i, VK_A + i);
        }
        for (int i = 0; i < 10; i++) {
            MAP.put(KeyEvent.KEYCODE_0 + i, VK_0 + i);
            MAP.put(KeyEvent.KEYCODE_NUMPAD_0 + i, VK_NUMPAD0 + i);
        }
        for (int i = 0; i < 12; i++) {
            MAP.put(KeyEvent.KEYCODE_F1 + i, VK_F1 + i);
        }
        int[][] pairs = {
            { KeyEvent.KEYCODE_ENTER, VK_ENTER }, { KeyEvent.KEYCODE_NUMPAD_ENTER, VK_ENTER },
            { KeyEvent.KEYCODE_DEL, VK_BACK_SPACE }, { KeyEvent.KEYCODE_FORWARD_DEL, VK_DELETE },
            { KeyEvent.KEYCODE_TAB, VK_TAB }, { KeyEvent.KEYCODE_ESCAPE, VK_ESCAPE },
            { KeyEvent.KEYCODE_SPACE, VK_SPACE },
            { KeyEvent.KEYCODE_SHIFT_LEFT, VK_SHIFT }, { KeyEvent.KEYCODE_SHIFT_RIGHT, VK_SHIFT },
            { KeyEvent.KEYCODE_CTRL_LEFT, VK_CONTROL }, { KeyEvent.KEYCODE_CTRL_RIGHT, VK_CONTROL },
            { KeyEvent.KEYCODE_ALT_LEFT, VK_ALT }, { KeyEvent.KEYCODE_ALT_RIGHT, VK_ALT },
            { KeyEvent.KEYCODE_META_LEFT, VK_META }, { KeyEvent.KEYCODE_META_RIGHT, VK_META },
            { KeyEvent.KEYCODE_CAPS_LOCK, VK_CAPS_LOCK }, { KeyEvent.KEYCODE_NUM_LOCK, VK_NUM_LOCK },
            { KeyEvent.KEYCODE_SCROLL_LOCK, VK_SCROLL_LOCK }, { KeyEvent.KEYCODE_BREAK, VK_PAUSE },
            { KeyEvent.KEYCODE_SYSRQ, VK_PRINTSCREEN }, { KeyEvent.KEYCODE_INSERT, VK_INSERT },
            { KeyEvent.KEYCODE_PAGE_UP, VK_PAGE_UP }, { KeyEvent.KEYCODE_PAGE_DOWN, VK_PAGE_DOWN },
            { KeyEvent.KEYCODE_MOVE_HOME, VK_HOME }, { KeyEvent.KEYCODE_MOVE_END, VK_END },
            { KeyEvent.KEYCODE_DPAD_LEFT, VK_LEFT }, { KeyEvent.KEYCODE_DPAD_RIGHT, VK_RIGHT },
            { KeyEvent.KEYCODE_DPAD_UP, VK_UP }, { KeyEvent.KEYCODE_DPAD_DOWN, VK_DOWN },
            { KeyEvent.KEYCODE_COMMA, VK_COMMA }, { KeyEvent.KEYCODE_PERIOD, VK_PERIOD },
            { KeyEvent.KEYCODE_MINUS, VK_MINUS }, { KeyEvent.KEYCODE_EQUALS, VK_EQUALS },
            { KeyEvent.KEYCODE_SLASH, VK_SLASH }, { KeyEvent.KEYCODE_BACKSLASH, VK_BACK_SLASH },
            { KeyEvent.KEYCODE_SEMICOLON, VK_SEMICOLON }, { KeyEvent.KEYCODE_APOSTROPHE, VK_QUOTE },
            { KeyEvent.KEYCODE_GRAVE, VK_BACK_QUOTE }, { KeyEvent.KEYCODE_LEFT_BRACKET, VK_OPEN_BRACKET },
            { KeyEvent.KEYCODE_RIGHT_BRACKET, VK_CLOSE_BRACKET }, { KeyEvent.KEYCODE_AT, VK_AT },
            { KeyEvent.KEYCODE_MENU, VK_CONTEXT_MENU },
            { KeyEvent.KEYCODE_NUMPAD_MULTIPLY, VK_MULTIPLY }, { KeyEvent.KEYCODE_NUMPAD_ADD, VK_ADD },
            { KeyEvent.KEYCODE_NUMPAD_SUBTRACT, VK_SUBTRACT }, { KeyEvent.KEYCODE_NUMPAD_DOT, VK_DECIMAL },
            { KeyEvent.KEYCODE_NUMPAD_DIVIDE, VK_DIVIDE }, { KeyEvent.KEYCODE_NUMPAD_COMMA, VK_COMMA },
            { KeyEvent.KEYCODE_NUMPAD_EQUALS, VK_EQUALS },
        };
        for (int[] p : pairs) {
            MAP.put(p[0], p[1]);
        }
    }

    private KeyMap() {
    }

    /** AWT virtual key for an Android key code, or 0 if it has none. */
    static int vk(int keyCode) {
        return MAP.get(keyCode, 0);
    }

    static int modifiers(int metaState) {
        int m = 0;
        if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
            m |= SHIFT_DOWN;
        }
        if ((metaState & KeyEvent.META_CTRL_ON) != 0) {
            m |= CTRL_DOWN;
        }
        if ((metaState & KeyEvent.META_ALT_LEFT_ON) != 0) {
            m |= ALT_DOWN;
        }
        if ((metaState & KeyEvent.META_ALT_RIGHT_ON) != 0) {
            // Right Alt types characters on many layouts.
            m |= ALT_GRAPH_DOWN;
        }
        if ((metaState & KeyEvent.META_META_ON) != 0) {
            m |= META_DOWN;
        }
        return m;
    }

    static int location(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_META_LEFT:
                return LOCATION_LEFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return LOCATION_RIGHT;
            default:
                return keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
                        ? LOCATION_NUMPAD : LOCATION_STANDARD;
        }
    }

    /** AWT virtual key for a typed character (soft keyboard), or 0. */
    static int vkForChar(char c) {
        if (c >= 'a' && c <= 'z') {
            return VK_A + (c - 'a');
        }
        if (c >= 'A' && c <= 'Z') {
            return VK_A + (c - 'A');
        }
        if (c >= '0' && c <= '9') {
            return VK_0 + (c - '0');
        }
        switch (c) {
            case ' ': return VK_SPACE;
            case '\n': return VK_ENTER;
            case '\t': return VK_TAB;
            case ',': return VK_COMMA;
            case '.': return VK_PERIOD;
            case '-': return VK_MINUS;
            case '=': return VK_EQUALS;
            case '/': return VK_SLASH;
            case '\\': return VK_BACK_SLASH;
            case ';': return VK_SEMICOLON;
            case '\'': return VK_QUOTE;
            case '`': return VK_BACK_QUOTE;
            case '[': return VK_OPEN_BRACKET;
            case ']': return VK_CLOSE_BRACKET;
            default: return 0;
        }
    }
}
