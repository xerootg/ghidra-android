/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import docking.ActionContext;
import docking.DialogComponentProvider;
import docking.action.DockingActionIf;
import docking.action.KeyBindingData;
import docking.action.MenuData;
import ghidra.app.services.CodeViewerService;
import ghidra.app.services.GoToService;
import ghidra.app.services.QueryData;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.util.ProgramLocation;
import ghidra.util.Swing;
import ghidra.util.task.TaskMonitor;

/**
 * One box for everything: type a few letters of any command (menu items,
 * panel buttons, key-binding-only actions) or an address or label to go to.
 * Recent commands come first. Rows are big enough to tap.
 */
final class CommandPalette extends DialogComponentProvider {

	/** A row: an action, or "go to" the typed text. */
	private record Entry(DockingActionIf action, String label, String detail, String goTo) {
	}

	private final PluginTool tool;
	private final ActionContext context;
	private final List<String> recent;
	private final List<Entry> all = new ArrayList<>();
	private final DefaultListModel<Entry> model = new DefaultListModel<>();
	private final JTextField field = new JTextField();
	private final JList<Entry> list = new JList<>(model);

	/**
	 * @param recent most recently used action names, newest first; updated
	 *            when a command runs
	 */
	CommandPalette(PluginTool tool, List<String> recent) {
		super("Commands", true, false, true, false);
		this.tool = tool;
		this.recent = recent;
		this.context = Actions.currentContext(tool);
		collect();
		addWorkPanel(build());
		setFocusComponent(field);
		setRememberSize(false);
		setPreferredSize(640, 520);
		filter();
	}

	private static String label(DockingActionIf a) {
		MenuData m = a.getMenuBarData();
		if (m == null) {
			m = a.getPopupMenuData();
		}
		if (m != null && m.getMenuPath().length > 0) {
			StringBuilder sb = new StringBuilder();
			for (String part : m.getMenuPath()) {
				if (sb.length() > 0) {
					sb.append(" › ");
				}
				sb.append(part.replace("&", "").replace("...", "").trim());
			}
			return sb.toString();
		}
		return a.getName();
	}

	private static String keys(DockingActionIf a) {
		KeyBindingData k = a.getKeyBindingData();
		if (k == null || k.getKeyBinding() == null) {
			return "";
		}
		KeyStroke ks = k.getKeyBinding();
		String mods = KeyEvent.getModifiersExText(ks.getModifiers());
		String key = KeyEvent.getKeyText(ks.getKeyCode());
		return mods.isEmpty() ? key : mods + "+" + key;
	}

	/** Actions that can run now, one per name and menu path. */
	private void collect() {
		// Buttons of panels that aren't showing don't belong in the list.
		Set<DockingActionIf> hidden = new HashSet<>();
		docking.DockingWindowManager dwm = tool.getWindowManager();
		for (docking.ComponentProvider p : dwm.getComponentProviders(docking.ComponentProvider.class)) {
			if (!dwm.isVisible(p)) {
				dwm.getComponentActions(p).forEachRemaining(hidden::add);
			}
		}
		Set<String> seen = new HashSet<>();
		for (DockingActionIf a : tool.getAllActions()) {
			if (hidden.contains(a) || Actions.contextFor(tool, a, context) == null) {
				continue;
			}
			String label = label(a);
			if (!seen.add(label + "\u0000" + a.getName())) {
				continue;
			}
			String detail = keys(a);
			all.add(new Entry(a, label, detail, null));
		}
		all.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
	}

	private JPanel build() {
		Font base = field.getFont();
		Font big = base.deriveFont(base.getSize2D() * 1.25f);
		field.setFont(big);
		field.setBorder(BorderFactory.createCompoundBorder(field.getBorder(),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				filter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				filter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				filter();
			}
		});
		field.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				int i = list.getSelectedIndex();
				switch (e.getKeyCode()) {
					case KeyEvent.VK_DOWN -> select(i + 1);
					case KeyEvent.VK_UP -> select(i - 1);
					case KeyEvent.VK_PAGE_DOWN -> select(i + 8);
					case KeyEvent.VK_PAGE_UP -> select(i - 8);
					case KeyEvent.VK_ENTER -> runSelected();
					default -> {
						return;
					}
				}
				e.consume();
			}
		});

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(Math.round(big.getSize2D() * 2.2f));
		list.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> l, Object value, int index,
					boolean selected, boolean focus) {
				Entry e = (Entry) value;
				String detail = e.detail.isEmpty() ? ""
						: "&nbsp;&nbsp;<font color=gray>" + escape(e.detail) + "</font>";
				JLabel c = (JLabel) super.getListCellRendererComponent(l,
					"<html>" + escape(e.label) + detail, index, selected, focus);
				c.setFont(big);
				c.setIcon(e.action != null && e.action.getToolBarData() != null
						? e.action.getToolBarData().getIcon() : null);
				c.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
				return c;
			}
		});
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int i = list.locationToIndex(e.getPoint());
				if (i >= 0) {
					list.setSelectedIndex(i);
					runSelected();
				}
			}
		});

		JPanel p = new JPanel(new BorderLayout(0, 6));
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		p.add(field, BorderLayout.NORTH);
		p.add(new JScrollPane(list), BorderLayout.CENTER);
		JLabel hint = new JLabel("Type a command, or an address or label to go to");
		hint.setEnabled(false);
		p.add(hint, BorderLayout.SOUTH);
		return p;
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void select(int i) {
		if (model.isEmpty()) {
			return;
		}
		i = Math.max(0, Math.min(model.size() - 1, i));
		list.setSelectedIndex(i);
		list.ensureIndexIsVisible(i);
	}

	/** All words of the query appear in the label or the action name. */
	private static boolean matches(Entry e, String[] words) {
		String hay = (e.label + " " + e.action.getName()).toLowerCase(Locale.ROOT);
		for (String w : words) {
			if (!hay.contains(w)) {
				return false;
			}
		}
		return true;
	}

	private void filter() {
		String q = field.getText().trim();
		model.clear();
		Map<String, Entry> byName = new LinkedHashMap<>();
		if (q.isEmpty()) {
			for (String name : recent) {
				for (Entry e : all) {
					if (e.action.getName().equals(name)) {
						byName.putIfAbsent(name, e);
						break;
					}
				}
			}
			byName.values().forEach(model::addElement);
			for (Entry e : all) {
				if (!byName.containsValue(e)) {
					model.addElement(e);
				}
			}
		}
		else {
			String[] words = q.toLowerCase(Locale.ROOT).split("\\s+");
			List<Entry> starts = new ArrayList<>();
			List<Entry> rest = new ArrayList<>();
			for (Entry e : all) {
				if (matches(e, words)) {
					String last = e.label.substring(e.label.lastIndexOf('›') + 1).trim();
					(last.toLowerCase(Locale.ROOT).startsWith(words[0]) ? starts : rest).add(e);
				}
			}
			starts.forEach(model::addElement);
			rest.forEach(model::addElement);
			if (tool.getService(GoToService.class) != null) {
				model.addElement(new Entry(null, "Go to “" + q + "”", "", q));
			}
		}
		select(0);
	}

	private void runSelected() {
		Entry e = list.getSelectedValue();
		if (e == null) {
			return;
		}
		close();
		if (e.goTo != null) {
			goTo(e.goTo);
			return;
		}
		recent.remove(e.action.getName());
		recent.add(0, e.action.getName());
		while (recent.size() > 8) {
			recent.remove(recent.size() - 1);
		}
		Actions.run(tool, e.action, context);
	}

	private void goTo(String text) {
		GoToService goTo = tool.getService(GoToService.class);
		CodeViewerService cv = tool.getService(CodeViewerService.class);
		ProgramLocation loc = cv != null ? cv.getCurrentLocation() : null;
		Address from = loc != null ? loc.getAddress() : null;
		Swing.runLater(() -> goTo.goToQuery(from, new QueryData(text, false), null, TaskMonitor.DUMMY));
	}

	@Override
	protected void cancelCallback() {
		close();
	}
}
