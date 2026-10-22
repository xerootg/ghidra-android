/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

import docking.SplitPanel;

/**
 * Swing-level layout helpers for things Ghidra's docking API doesn't expose:
 * finding the tool's main toolbar, and evening out a split.
 */
final class Layouts {

	private Layouts() {
	}

	/**
	 * True for the panel Ghidra wraps a window's toolbar in: a BorderLayout
	 * panel with the toolbar on top and a separator underneath, docked at the
	 * top of the window's root panel.
	 */
	static boolean isToolBarPanel(Component c) {
		if (!(c instanceof JPanel p) || !(p.getLayout() instanceof BorderLayout bl)) {
			return false;
		}
		Component north = bl.getLayoutComponent(BorderLayout.NORTH);
		Component south = bl.getLayoutComponent(BorderLayout.SOUTH);
		if (north == null || !(south instanceof JSeparator)
				|| !north.getClass().getSimpleName().equals("VariableHeightPanel")) {
			return false;
		}
		Container parent = p.getParent();
		return parent != null && parent.getLayout() instanceof BorderLayout pl
				&& pl.getLayoutComponent(BorderLayout.NORTH) == p;
	}

	/** The toolbar panel of the window containing {@code c}, or null. */
	static JComponent findToolBarPanel(Component root) {
		if (isToolBarPanel(root)) {
			return (JComponent) root;
		}
		if (root instanceof Container c) {
			for (Component child : c.getComponents()) {
				JComponent found = findToolBarPanel(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** The split holding {@code a} on one side and {@code b} on the other, or null. */
	static SplitPanel commonSplit(Component a, Component b) {
		for (Container p = a.getParent(); p != null; p = p.getParent()) {
			if (p instanceof SplitPanel s && SwingUtilities.isDescendingFrom(b, s)) {
				return s;
			}
		}
		return null;
	}

	/** Splits {@code a} and {@code b} evenly (the position is a fraction, so it stays even). */
	static void evenSplit(Component a, Component b) {
		SplitPanel s = commonSplit(a, b);
		if (s != null) {
			s.setDividerPosition(0.5f);
			s.revalidate();
			s.repaint();
		}
	}
}
