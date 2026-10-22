/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;
import javax.swing.UIManager;

/** Toolbar icons drawn in code, in the theme's text colour (no image files). */
final class TouchIcons implements Icon {

	/** Focus layout: one big pane with a narrow strip beside it. */
	static final Icon FOCUS = new TouchIcons(true);
	/** Panels: a list. */
	static final Icon PANELS = new TouchIcons(1);
	/** Command palette: a magnifier. */
	static final Icon SEARCH = new TouchIcons(2);

	private final int kind;

	private TouchIcons(boolean focus) {
		this(focus ? 0 : 1);
	}

	private TouchIcons(int kind) {
		this.kind = kind;
	}

	@Override
	public int getIconWidth() {
		return 16;
	}

	@Override
	public int getIconHeight() {
		return 16;
	}

	@Override
	public void paintIcon(Component c, Graphics g0, int x, int y) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Color fg = UIManager.getColor("Label.foreground");
		g.setColor(fg != null ? fg : Color.GRAY);
		g.setStroke(new BasicStroke(1.5f));
		g.translate(x, y);
		if (kind == 2) {
			g.drawOval(2, 2, 8, 8);
			g.setStroke(new BasicStroke(2.2f));
			g.drawLine(10, 10, 14, 14);
		}
		else if (kind == 0) {
			g.drawRoundRect(1, 2, 14, 12, 3, 3);
			g.drawLine(5, 2, 5, 14);
			g.fillRect(7, 5, 6, 1);
			g.fillRect(7, 8, 6, 1);
			g.fillRect(7, 11, 4, 1);
		}
		else {
			for (int i = 0; i < 3; i++) {
				g.fillOval(1, 2 + i * 5, 3, 3);
				g.fillRect(6, 3 + i * 5, 9, 1);
			}
		}
		g.dispose();
	}
}
