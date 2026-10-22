/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Comfortable sizes: taller rows in trees and tables, and roomier menu
 * items, so they can be hit with a finger. Applies to every window of the
 * application, including panels and dialogs created later, and is undone
 * when turned off. Shared by all tools; the last setting wins.
 */
final class Sizes {

	private static final String[] MENU_KEYS =
		{ "MenuItem.margin", "Menu.margin", "CheckBoxMenuItem.margin", "RadioButtonMenuItem.margin" };

	private static boolean on;
	private static final Map<Component, Integer> original = new WeakHashMap<>();
	private static final Map<String, Object> originalDefaults = new java.util.HashMap<>();

	private static final AWTEventListener ADDED = e -> {
		if (on && e instanceof ContainerEvent ce && ce.getID() == ContainerEvent.COMPONENT_ADDED) {
			Component child = ce.getChild();
			SwingUtilities.invokeLater(() -> walk(child));
		}
	};

	private Sizes() {
	}

	static synchronized void set(boolean enable) {
		if (enable == on) {
			return;
		}
		on = enable;
		if (enable) {
			for (String k : MENU_KEYS) {
				Object v = UIManager.get(k);
				originalDefaults.put(k, v);
				Insets i = v instanceof Insets in ? in : new Insets(2, 6, 2, 6);
				UIManager.put(k, new Insets(Math.max(i.top, 8), i.left, Math.max(i.bottom, 8), i.right));
			}
			Toolkit.getDefaultToolkit().addAWTEventListener(ADDED, AWTEvent.CONTAINER_EVENT_MASK);
		}
		else {
			Toolkit.getDefaultToolkit().removeAWTEventListener(ADDED);
			originalDefaults.forEach(UIManager::put);
			originalDefaults.clear();
		}
		for (Window w : Window.getWindows()) {
			walk(w);
		}
	}

	private static int target(Component c) {
		int font = c.getFontMetrics(c.getFont()).getHeight();
		return font + Math.max(12, font / 2);
	}

	private static void walk(Component c) {
		if (c instanceof JTree t) {
			apply(t, t.getRowHeight(), t::setRowHeight);
		}
		else if (c instanceof JTable t) {
			apply(t, t.getRowHeight(), t::setRowHeight);
		}
		else if (c instanceof JMenuItem m) {
			m.updateUI();
		}
		if (c instanceof javax.swing.JMenu menu) {
			for (Component item : menu.getMenuComponents()) {
				walk(item);
			}
		}
		if (c instanceof Container k) {
			for (Component child : k.getComponents()) {
				walk(child);
			}
		}
	}

	private static void apply(Component c, int current, java.util.function.IntConsumer setter) {
		if (on) {
			int want = target(c);
			if (current < want) {
				original.putIfAbsent(c, current);
				setter.accept(want);
			}
		}
		else {
			Integer was = original.remove(c);
			if (was != null) {
				setter.accept(was);
			}
		}
	}
}
