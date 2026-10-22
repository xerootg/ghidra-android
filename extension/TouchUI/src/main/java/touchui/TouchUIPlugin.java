/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
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
import ghidra.framework.options.OptionsChangeListener;
import ghidra.framework.options.SaveState;
import ghidra.framework.options.ToolOptions;
import ghidra.framework.plugintool.Plugin;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.util.HelpLocation;
import ghidra.util.Swing;

/**
 * Ghidra for small screens and touch.
 * <ul>
 * <li>Focus layout: only the listing and the decompiler, split evenly; the
 * listing drops its raw bytes column, and the main toolbar gives way to a
 * bar of big buttons along the bottom.</li>
 * <li>Panels: every other panel, one tap away. In the focus layout a panel
 * opened from here is a drawer that hides again when you go back to the
 * code.</li>
 * <li>Commands: one search box for every command, plus go to address or
 * label.</li>
 * <li>Comfortable sizes: taller tree and table rows and roomier menus.</li>
 * </ul>
 * Built only on Ghidra's public plugin API and Swing, so it works on desktop
 * Ghidra as well as on Android. Start Ghidra with {@code -Dtouchui.touch=true}
 * to make the focus layout and comfortable sizes the defaults.
 */
//@formatter:off
@PluginInfo(
	status = PluginStatus.RELEASED,
	packageName = TouchUIPluginPackage.NAME,
	category = PluginCategoryNames.COMMON,
	shortDescription = "Focus layout, panel drawer and command palette for small screens",
	description = "Shows only the listing and the decompiler with big buttons along the " +
		"bottom; every other panel opens on demand and hides again when you go back to " +
		"the code. Adds a searchable command palette and touch-sized rows."
)
//@formatter:on
public class TouchUIPlugin extends Plugin implements OptionsChangeListener {

	static final String OPTIONS = "Touch UI";
	static final String OPT_SIZES = "Comfortable sizes";
	static final String OPT_HIDE_TOOLBAR = "Focus layout hides the toolbar";
	static final String OPT_HIDE_BYTES = "Focus layout hides the bytes column";

	private static final boolean TOUCH = Boolean.getBoolean("touchui.touch");
	private static final String MENU_GROUP = "TouchUI";
	private static final String TOOLBAR_GROUP = "0TouchUI";

	/** The panels the focus layout keeps: owner and name. */
	private static final String[][] KEEP = {
		{ "CodeBrowserPlugin", "Listing" },
		{ "DecompilePlugin", "Decompiler" },
	};

	private ToggleDockingAction focusAction;
	private boolean focused;
	private boolean stateRead;
	/** Panels the focus layout hid ("owner|name"), to show again on exit. */
	private final Set<String> hiddenByUs = new LinkedHashSet<>();
	/** Panels opened from the drawer; they close when the code is active again. */
	private final Set<ComponentProvider> peeking = new LinkedHashSet<>();
	private boolean bytesWereEnabled = true;
	private final List<String> recentCommands = new ArrayList<>();

	private JComponent hiddenToolBar;
	private JComponent actionBar;

	private final DockingContextListener contextListener = this::contextChanged;

	/** Ghidra rebuilds the toolbar when actions change; keep it hidden while focused. */
	private final AWTEventListener toolBarWatcher = e -> {
		if (focused && hideToolBar() && e instanceof ContainerEvent ce
				&& ce.getID() == ContainerEvent.COMPONENT_ADDED && Layouts.isToolBarPanel(ce.getChild())
				&& SwingUtilities.getWindowAncestor(ce.getChild()) == mainWindow()) {
			SwingUtilities.invokeLater(this::applyToolBar);
		}
	};

	public TouchUIPlugin(PluginTool tool) {
		super(tool);
		createActions();
	}

	@Override
	protected void init() {
		ToolOptions opts = tool.getOptions(OPTIONS);
		HelpLocation help = new HelpLocation("TouchUI", "Options");
		opts.registerOption(OPT_SIZES, TOUCH, help,
			"Taller rows in trees and tables, and roomier menus, for fingers");
		opts.registerOption(OPT_HIDE_TOOLBAR, true, help,
			"In the focus layout, replace the toolbar with big buttons along the bottom");
		opts.registerOption(OPT_HIDE_BYTES, true, help,
			"In the focus layout, hide the listing's raw bytes column");
		opts.addOptionsChangeListener(this);
		Sizes.set(opts.getBoolean(OPT_SIZES, TOUCH));

		tool.getWindowManager().addContextListener(contextListener);
		Toolkit.getDefaultToolkit().addAWTEventListener(toolBarWatcher, AWTEvent.CONTAINER_EVENT_MASK);

		// After the tool has restored its layout: apply the saved (or default) mode.
		Swing.runLater(() -> Swing.runLater(() -> {
			if (stateRead) {
				// The saved layout already has the panels hidden; redo the rest.
				if (focused) {
					applyToolBar();
					evenSplit();
				}
			}
			else if (TOUCH) {
				setFocused(true);
			}
		}));
	}

	@Override
	protected void dispose() {
		tool.getWindowManager().removeContextListener(contextListener);
		Toolkit.getDefaultToolkit().removeAWTEventListener(toolBarWatcher);
		tool.getOptions(OPTIONS).removeOptionsChangeListener(this);
		if (focused) {
			setFocused(false);
		}
		// Disposed while the tool stays open: the user removed the plugin.
		PluginTool t = tool;
		Swing.runLater(() -> {
			if (t.getToolFrame() != null && t.getToolFrame().isShowing()) {
				TouchUIInitializer.removedByUser(t);
			}
		});
	}

	@Override
	public void optionsChanged(ToolOptions options, String name, Object oldValue, Object newValue) {
		if (OPT_SIZES.equals(name)) {
			Sizes.set((Boolean) newValue);
		}
		else if (OPT_HIDE_TOOLBAR.equals(name) && focused) {
			applyToolBar();
		}
	}

	private boolean hideToolBar() {
		return tool.getOptions(OPTIONS).getBoolean(OPT_HIDE_TOOLBAR, true);
	}

	private boolean hideBytes() {
		return tool.getOptions(OPTIONS).getBoolean(OPT_HIDE_BYTES, true);
	}

	private Window mainWindow() {
		return tool.getWindowManager().getMainWindow();
	}

	private void createActions() {
		HelpLocation help = new HelpLocation("TouchUI", "Touch_UI");

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
			new KeyBindingData(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		focusAction.setDescription("Show only the code; other panels open from Panels");
		focusAction.setHelpLocation(help);
		tool.addAction(focusAction);

		DockingAction panels = new DockingAction("Panels", getName()) {
			@Override
			public void actionPerformed(ActionContext context) {
				Object src = context != null ? context.getSourceObject() : null;
				showPanels(src instanceof Component c && c.isShowing() ? c : null);
			}
		};
		panels.setMenuBarData(new MenuData(new String[] { "Window", "Panels..." }, null, MENU_GROUP));
		panels.setToolBarData(new ToolBarData(TouchIcons.PANELS, TOOLBAR_GROUP));
		panels.setKeyBindingData(
			new KeyBindingData(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		panels.setDescription("Open a panel (Symbol Tree, Data Types, Console, ...)");
		panels.setHelpLocation(help);
		tool.addAction(panels);

		DockingAction commands = new DockingAction("Command Palette", getName()) {
			@Override
			public void actionPerformed(ActionContext context) {
				showCommands();
			}
		};
		commands.setMenuBarData(
			new MenuData(new String[] { "Window", "Command Palette..." }, null, MENU_GROUP));
		commands.setToolBarData(new ToolBarData(TouchIcons.SEARCH, TOOLBAR_GROUP));
		commands.setKeyBindingData(
			new KeyBindingData(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		commands.setDescription("Search every command, or go to an address or label");
		commands.setHelpLocation(help);
		tool.addAction(commands);
	}

	void showCommands() {
		tool.showDialog(new CommandPalette(tool, recentCommands));
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
		Window w = tool.getWindowManager().getProviderWindow(p);
		return w == null || w == mainWindow();
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
			bytesWereEnabled = !hideBytes() || setBytesField(false, true);
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
		applyToolBar();
		if (on) {
			SwingUtilities.invokeLater(this::evenSplit);
		}
		tool.setConfigChanged(true);
	}

	private void evenSplit() {
		ComponentProvider listing = null, decompiler = null;
		for (ComponentProvider p : providers()) {
			if (isKept(p)) {
				if ("Listing".equals(p.getName())) {
					listing = p;
				}
				else {
					decompiler = p;
				}
			}
		}
		if (listing != null && decompiler != null) {
			Layouts.evenSplit(listing.getComponent(), decompiler.getComponent());
		}
	}

	/** Toolbar hidden and action bar shown while focused (if the option says so), else back. */
	private void applyToolBar() {
		boolean hide = focused && hideToolBar();
		JComponent bar = Layouts.findToolBarPanel(((javax.swing.RootPaneContainer) mainWindow())
				.getRootPane());
		if (hide) {
			if (bar != null && bar.isVisible()) {
				bar.setVisible(false);
				hiddenToolBar = bar;
			}
			if (actionBar == null) {
				actionBar = buildActionBar();
				tool.addStatusComponent(actionBar, false, false);
			}
		}
		else {
			if (hiddenToolBar != null) {
				hiddenToolBar.setVisible(true);
				hiddenToolBar = null;
			}
			if (bar != null && !bar.isVisible()) {
				bar.setVisible(true);
			}
			if (actionBar != null) {
				tool.removeStatusComponent(actionBar);
				actionBar = null;
			}
		}
	}

	/** Big buttons for what the toolbar was mostly used for. */
	private JComponent buildActionBar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		bar.setOpaque(false);
		bar.add(barButton("◀", "Back", () -> Actions.run(tool, "Previous Location in History")));
		bar.add(barButton("▶", "Forward", () -> Actions.run(tool, "Next Location in History")));
		bar.add(barButton("Go to", "Go to an address or label", () -> Actions.run(tool, "Go To Address/Label")));
		bar.add(barButton("Commands", "Search every command", this::showCommands));
		bar.add(barButton("Panels", "Open a panel", null));
		bar.add(barButton("Undo", "Undo", () -> Actions.run(tool, "Undo")));
		bar.add(barButton("Redo", "Redo", () -> Actions.run(tool, "Redo")));
		bar.add(barButton("Save", "Save", () -> Actions.run(tool, "Save File")));
		bar.add(barButton("Show all", "Leave the focus layout", () -> setFocused(false)));
		return bar;
	}

	private JButton barButton(String text, String tip, Runnable r) {
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setFocusable(false);
		Font f = b.getFont();
		b.setFont(f.deriveFont(f.getSize2D() * 1.15f));
		b.setMargin(new Insets(4, 10, 4, 10));
		Dimension d = b.getPreferredSize();
		b.setPreferredSize(new Dimension(Math.max(d.width, 44), Math.max(d.height, 30)));
		b.addActionListener(e -> {
			if (r != null) {
				r.run();
			}
			else {
				showPanels(b);
			}
		});
		return b;
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

	/** A panel's title without the program name ("Bytes: foo.bin" is "Bytes"). */
	private static String label(ComponentProvider p) {
		String t = p.getTitle();
		if (t == null || t.isBlank()) {
			return p.getName();
		}
		int colon = t.indexOf(':');
		return (colon > 0 ? t.substring(0, colon) : t).trim();
	}

	/** The panels to offer: the drawer first when focused, then the rest. */
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
		Comparator<ComponentProvider> byTitle = Comparator.comparing(p -> label(p).toLowerCase());
		drawer.sort(byTitle);
		others.sort(byTitle);
		drawer.addAll(others);
		return drawer;
	}

	private void showPanels(Component anchor) {
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
			Icon icon = p.getIcon();
			JMenuItem item = new JMenuItem(label(p), icon);
			item.setFont(dwm.isVisible(p) ? big.deriveFont(Font.BOLD) : big);
			Dimension d = item.getPreferredSize();
			item.setPreferredSize(new Dimension(Math.max(d.width + 24, 260), Math.max(d.height, 40)));
			item.addActionListener(e -> openPanel(p));
			menu.add(item);
		}
		menu.addSeparator();
		JMenuItem toggle = new JMenuItem(focused ? "Show all panels" : "Focus on the code");
		toggle.setFont(big);
		toggle.addActionListener(e -> setFocused(!focused));
		menu.add(toggle);

		if (anchor != null) {
			Dimension size = menu.getPreferredSize();
			// Open upwards from the bottom bar, downwards from the toolbar.
			boolean low = anchor.getLocationOnScreen().y > mainWindow().getLocationOnScreen().y
					+ mainWindow().getHeight() / 2;
			menu.show(anchor, 0, low ? -size.height : anchor.getHeight());
		}
		else {
			Window w = dwm.getActiveWindow() != null ? dwm.getActiveWindow() : mainWindow();
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
		state.putStrings("RECENT_COMMANDS", recentCommands.toArray(new String[0]));
	}

	@Override
	public void readConfigState(SaveState state) {
		if (!state.hasValue("FOCUSED")) {
			return;
		}
		stateRead = true;
		focused = state.getBoolean("FOCUSED", false);
		hiddenByUs.clear();
		for (String s : state.getStrings("HIDDEN", new String[0])) {
			hiddenByUs.add(s);
		}
		bytesWereEnabled = state.getBoolean("BYTES_WERE_ENABLED", true);
		recentCommands.clear();
		for (String s : state.getStrings("RECENT_COMMANDS", new String[0])) {
			recentCommands.add(s);
		}
		focusAction.setSelected(focused);
	}

}
