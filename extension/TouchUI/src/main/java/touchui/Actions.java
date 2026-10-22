/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import docking.ActionContext;
import docking.ComponentProvider;
import docking.DockingWindowManager;
import docking.action.DockingActionIf;
import docking.action.ToggleDockingActionIf;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.Swing;

/** Finds tool actions by name and runs them the way a menu or key binding would. */
final class Actions {

	private Actions() {
	}

	/** The context of the panel the user was last working in. */
	static ActionContext currentContext(PluginTool tool) {
		ComponentProvider p = tool.getActiveComponentProvider();
		return p != null ? p.getActionContext(null) : null;
	}

	/** The context to run {@code action} with, or null if it can't run now. */
	static ActionContext contextFor(PluginTool tool, DockingActionIf action, ActionContext local) {
		if (local != null && action.isValidContext(local) && action.isEnabledForContext(local)) {
			return local;
		}
		if (local == null || action.supportsDefaultContext()) {
			DockingWindowManager dwm = tool.getWindowManager();
			ActionContext def = dwm.getDefaultActionContext(action.getContextClass());
			if (def != null && action.isValidContext(def) && action.isEnabledForContext(def)) {
				return def;
			}
		}
		return null;
	}

	/** The first action named {@code name} that can run now, or any with that name. */
	static DockingActionIf find(PluginTool tool, String name, ActionContext local) {
		DockingActionIf any = null;
		for (DockingActionIf a : tool.getAllActions()) {
			if (name.equals(a.getName())) {
				if (contextFor(tool, a, local) != null) {
					return a;
				}
				any = any == null ? a : any;
			}
		}
		return any;
	}

	/** Runs an action by name with the current context; false if it can't run now. */
	static boolean run(PluginTool tool, String name) {
		ActionContext local = currentContext(tool);
		DockingActionIf a = find(tool, name, local);
		return a != null && run(tool, a, local);
	}

	static boolean run(PluginTool tool, DockingActionIf action, ActionContext local) {
		ActionContext ctx = contextFor(tool, action, local);
		if (ctx == null) {
			return false;
		}
		// Let focus settle (a dialog closing, a button press) before acting.
		Swing.runLater(() -> {
			if (action instanceof ToggleDockingActionIf t) {
				t.setSelected(!t.isSelected());
			}
			action.actionPerformed(ctx);
		});
		return true;
	}
}
