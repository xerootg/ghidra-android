/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.Component;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import docking.ActionContext;
import docking.ComponentProvider;
import docking.DockingContextListener;
import docking.DockingWindowManager;
import docking.action.DockingAction;
import docking.action.KeyBindingData;
import docking.action.MenuData;
import docking.action.ToggleDockingAction;
import docking.action.ToolBarData;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.services.CodeViewerService;
import ghidra.app.util.viewer.field.FieldFactory;
import ghidra.app.util.viewer.format.FieldFormatModel;
import ghidra.framework.options.SaveState;
import ghidra.framework.plugintool.Plugin;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.util.HelpLocation;

/**
 * A layout for small screens and touch. The focus layout shows only the code
 * (the listing and the decompiler) and hides every other panel of the main
 * window. The hidden panels form a drawer: the Panels button lists them, a
 * tap shows one, and it hides again as soon as you go back to the code.
 * Turning the focus layout off puts everything back where it was.
 *
 * Built only on Ghidra's public plugin API, so it works on desktop Ghidra as
 * well as on Android, and survives Ghidra updates with a rebuild.
 */
//@formatter:off
@PluginInfo(
	status = PluginStatus.RELEASED,
	packageName = TouchUIPluginPackage.NAME,
	category = PluginCategoryNames.COMMON,
	shortDescription = "Focus layout and panel drawer for small screens",
	description = "Shows only the listing and the decompiler; every other panel " +
		"opens on demand from the Panels button and hides again when you go back " +
		"to the code. The listing also drops the raw bytes column while focused."
)
//@formatter:on
public class TouchUIPlugin extends Plugin {

	private static final String MENU_GROUP = "TouchUI";
	private static final String TOOLBAR_GROUP = "0TouchUI";

	/** The panels the focus layout keeps: owner and name. */
	private static final String[][] KEEP = {
		{ "CodeBrowserPlugin", "Listing" },
		{ "DecompilePlugin", "Decompiler" },
	};

	private ToggleDockingAction focusAction;
	private DockingAction panelsAction;

	private boolean focused;
	/** Panels the focus layout hid ("owner|name"), to show again on exit. */
	private final Set<String> hiddenByUs = new LinkedHashSet<>();
	/** Panels opened from the drawer; they close when the code is active again. */
	private final Set<ComponentProvider> peeking = new LinkedHashSet<>();
	/** Whether the listing's Bytes field was showing before the focus layout. */
	private boolean bytesWereEnabled = true;

	private final DockingContextListener contextListener = this::contextChanged;

	public TouchUIPlugin(PluginTool tool) {
		super(tool);
		createActions();
	}

	@Override
	protected void init() {
		tool.getWindowManager().addContextListener(contextListener);
	}

	@Override
	protected void dispose() {
		tool.getWindowManager().removeContextListener(contextListener);
	}

	private void createActions() {
		HelpLocation help = new HelpLocation("TouchUI", "Focus_Layout");

		focusAction = new ToggleDockingAction("Focus Layout", getName()) {
			@Override
			public void actionPerformed(ActionContext context) {
				setFocused(isSelected());
			}
		};
		focusAction.setMenuBarData(
			new MenuData(new String[] { "Window", "Focus Layout" }, null, MENU_GROUP));
		focusAction.setToolBarData(new ToolBarData(TouchIcons.FOCUS, TOOLBAR_GROUP));
		focusAction.setKeyBindingData(
			new KeyBindingData(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		focusAction.setDescription("Show only the code; other panels open from the Panels button");
		focusAction.setHelpLocation(help);
		tool.addAction(focusAction);

		panelsAction = new DockingAction("Panels", getName()) {
			@Override
			public void actionPerformed(ActionContext context) {
				showPanels(context);
			}
		};
		panelsAction.setMenuBarData(
			new MenuData(new String[] { "Window", "Panels..." }, null, MENU_GROUP));
		panelsAction.setToolBarData(new ToolBarData(TouchIcons.PANELS, TOOLBAR_GROUP));
		panelsAction.setKeyBindingData(
			new KeyBindingData(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		panelsAction.setDescription("Open a panel (Symbol Tree, Data Types, Console, ...)");
		panelsAction.setHelpLocation(help);
		tool.addAction(panelsAction);
	}

	// ---- focus layout ---------------------------------------------------------

	private static String key(ComponentProvider p) {
		return p.getOwner() + "|" + p.getName();
	}

	private static boolean isKept(ComponentProvider p) {
		if (p.isSnapshot() || p.isTransient()) {
			return false;
		}
		for (String[] k : KEEP) {
			if (k[0].equals(p.getOwner()) && k[1].equals(p.getName())) {
				return true;
			}
		}
		return false;
	}

	private List<ComponentProvider> providers() {
		return tool.getWindowManager().getComponentProviders(ComponentProvider.class);
	}

	/** True for panels docked in the main window (not in their own windows). */
	private boolean inMainWindow(ComponentProvider p) {
		DockingWindowManager dwm = tool.getWindowManager();
		Window w = dwm.getProviderWindow(p);
		return w == null || w == dwm.getMainWindow();
	}

	private void setFocused(boolean on) {
		if (on == focused) {
			return;
		}
		DockingWindowManager dwm = tool.getWindowManager();
		if (on) {
			hiddenByUs.clear();
			peeking.clear();
			for (ComponentProvider p : providers()) {
				if (!isKept(p) && dwm.isVisible(p) && inMainWindow(p)) {
					hiddenByUs.add(key(p));
					dwm.showComponent(p, false);
				}
			}
			for (ComponentProvider p : providers()) {
				if (isKept(p) && !dwm.isVisible(p)) {
					dwm.showComponent(p, true);
				}
			}
			bytesWereEnabled = setBytesField(false, true);
		}
		else {
			peeking.clear();
			for (ComponentProvider p : providers()) {
				if (hiddenByUs.contains(key(p))) {
					dwm.showComponent(p, true);
				}
			}
			hiddenByUs.clear();
			setBytesField(bytesWereEnabled, false);
		}
		focused = on;
		focusAction.setSelected(on);
		tool.setConfigChanged(true);
	}

	/**
	 * Turns the listing's Bytes field on or off; returns whether it was on.
	 * With {@code onlyIfEnabled}, nothing changes when it was already off.
	 */
	private boolean setBytesField(boolean enable, boolean onlyIfEnabled) {
		CodeViewerService cv = tool.getService(CodeViewerService.class);
		if (cv == null) {
			return true;
		}
		boolean was = true;
		FieldFormatModel model = cv.getFormatManager().getCodeUnitFormat();
		for (int row = 0; row < model.getNumRows(); row++) {
			for (FieldFactory f : model.getFactorys(row)) {
				if ("Bytes".equals(f.getFieldName())) {
					was = f.isEnabled();
					if (was != enable && !(onlyIfEnabled && !was)) {
						f.setEnabled(enable);
						model.modelChanged();
					}
				}
			}
		}
		return was;
	}

	// ---- drawer ---------------------------------------------------------------

	private void contextChanged(ActionContext context) {
		if (!focused || peeking.isEmpty() || context == null) {
			return;
		}
		ComponentProvider active = context.getComponentProvider();
		if (active != null && isKept(active)) {
			// Back to the code: close the drawer.
			List<ComponentProvider> close = new ArrayList<>(peeking);
			peeking.clear();
			SwingUtilities.invokeLater(() -> {
				for (ComponentProvider p : close) {
					tool.getWindowManager().showComponent(p, false);
				}
			});
		}
	}

	/** The panels the Panels list offers: the drawer first when focused, then the rest. */
	private List<ComponentProvider> panelChoices() {
		List<ComponentProvider> drawer = new ArrayList<>();
		List<ComponentProvider> others = new ArrayList<>();
		for (ComponentProvider p : providers()) {
			if (p.isTransient() || p.isSnapshot()) {
				continue;
			}
			if (focused && hiddenByUs.contains(key(p))) {
				drawer.add(p);
			}
			else if (!isKept(p)) {
				others.add(p);
			}
		}
		Comparator<ComponentProvider> byTitle =
			Comparator.comparing(p -> label(p).toLowerCase());
		drawer.sort(byTitle);
		others.sort(byTitle);
		drawer.addAll(others);
		return drawer;
	}

	/** A panel's title without the program name ("Bytes: foo.bin" is "Bytes"). */
	private static String label(ComponentProvider p) {
		String t = p.getTitle();
		if (t == null || t.isBlank()) {
			return p.getName();
		}
		int colon = t.indexOf(':');
		return (colon > 0 ? t.substring(0, colon) : t).trim();
	}

	private void showPanels(ActionContext context) {
		DockingWindowManager dwm = tool.getWindowManager();
		JPopupMenu menu = new JPopupMenu();
		Font big = menu.getFont().deriveFont(menu.getFont().getSize2D() * 1.25f);
		boolean drawerDone = false;
		for (ComponentProvider p : panelChoices()) {
			boolean inDrawer = focused && hiddenByUs.contains(key(p));
			if (focused && !inDrawer && !drawerDone) {
				menu.addSeparator();
			}
			drawerDone |= !inDrawer;
			JMenuItem item = new JMenuItem(label(p), p.getIcon());
			item.setFont(dwm.isVisible(p) ? big.deriveFont(Font.BOLD) : big);
			item.setPreferredSize(null);
			item.setPreferredSize(new java.awt.Dimension(
				Math.max(item.getPreferredSize().width + 24, 260),
				Math.max(item.getPreferredSize().height, 40)));
			item.addActionListener(e -> openPanel(p));
			menu.add(item);
		}
		menu.addSeparator();
		JMenuItem toggle = new JMenuItem(focused ? "Show all panels" : "Focus on the code");
		toggle.setFont(big);
		toggle.addActionListener(e -> setFocused(!focused));
		menu.add(toggle);

		Component anchor = null;
		Object src = context != null ? context.getSourceObject() : null;
		if (src instanceof Component c && c.isShowing()) {
			anchor = c;
		}
		if (anchor != null) {
			menu.show(anchor, 0, anchor.getHeight());
		}
		else {
			Window w = dwm.getActiveWindow() != null ? dwm.getActiveWindow() : dwm.getMainWindow();
			menu.show(w, 16, 64);
		}
	}

	private void openPanel(ComponentProvider p) {
		DockingWindowManager dwm = tool.getWindowManager();
		boolean inDrawer = focused && hiddenByUs.contains(key(p));
		if (dwm.isVisible(p) && !inDrawer) {
			dwm.showComponent(p, false);
			return;
		}
		dwm.showComponent(p, true);
		dwm.toFront(p);
		if (inDrawer) {
			peeking.add(p);
		}
	}

	// ---- state ----------------------------------------------------------------

	@Override
	public void writeConfigState(SaveState state) {
		state.putBoolean("FOCUSED", focused);
		state.putStrings("HIDDEN", hiddenByUs.toArray(new String[0]));
		state.putBoolean("BYTES_WERE_ENABLED", bytesWereEnabled);
	}

	@Override
	public void readConfigState(SaveState state) {
		focused = state.getBoolean("FOCUSED", false);
		hiddenByUs.clear();
		for (String s : state.getStrings("HIDDEN", new String[0])) {
			hiddenByUs.add(s);
		}
		bytesWereEnabled = state.getBoolean("BYTES_WERE_ENABLED", true);
		focusAction.setSelected(focused);
	}
}
