/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import java.awt.GraphicsEnvironment;

import javax.swing.Timer;

import ghidra.app.services.CodeViewerService;
import ghidra.framework.ModuleInitializer;
import ghidra.framework.main.AppInfo;
import ghidra.framework.model.Project;
import ghidra.framework.model.ToolManager;
import ghidra.framework.model.Workspace;
import ghidra.framework.model.WorkspaceChangeListener;
import ghidra.framework.plugintool.Plugin;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.preferences.Preferences;
import ghidra.util.Msg;
import ghidra.util.Swing;

/**
 * Turns Touch UI on in every CodeBrowser, without the "New Plugins Found"
 * round trip. If the user removes the plugin from a tool, it stays removed.
 * Runs at Ghidra startup (Ghidra runs every module's initializer); does
 * nothing headless.
 */
public class TouchUIInitializer implements ModuleInitializer {

	private static final String PREF_REMOVED = "TouchUI.removedFrom.";

	private Project watched;

	private final WorkspaceChangeListener listener = new WorkspaceChangeListener() {
		@Override
		public void toolAdded(Workspace ws, PluginTool tool) {
			Swing.runLater(() -> ensure(tool));
		}

		@Override
		public void toolRemoved(Workspace ws, PluginTool tool) {
		}

		@Override
		public void workspaceAdded(Workspace ws) {
		}

		@Override
		public void workspaceRemoved(Workspace ws) {
		}

		@Override
		public void workspaceSetActive(Workspace ws) {
		}

		@Override
		public void propertyChange(java.beans.PropertyChangeEvent evt) {
		}
	};

	@Override
	public String getName() {
		return "Touch UI";
	}

	@Override
	public void run() {
		if (GraphicsEnvironment.isHeadless()) {
			return;
		}
		// The front end and project come later; follow the active project.
		Swing.runLater(() -> new Timer(1000, e -> watch()).start());
	}

	private void watch() {
		Project p = AppInfo.getActiveProject();
		if (p == watched) {
			return;
		}
		watched = p;
		if (p == null) {
			return;
		}
		ToolManager tm = p.getToolManager();
		tm.addWorkspaceChangeListener(listener);
		for (PluginTool t : tm.getRunningTools()) {
			ensure(t);
		}
	}

	/** The user removed the plugin from a tool (File > Configure): stop adding it there. */
	static void removedByUser(PluginTool tool) {
		Preferences.setProperty(PREF_REMOVED + tool.getToolName(), "true");
		Preferences.store();
	}

	static void ensure(PluginTool tool) {
		if (tool == null || tool.getService(CodeViewerService.class) == null) {
			return;
		}
		for (Plugin p : tool.getManagedPlugins()) {
			if (p instanceof TouchUIPlugin) {
				return;
			}
		}
		if (Preferences.getProperty(PREF_REMOVED + tool.getToolName()) != null) {
			return; // The user took it out of this tool.
		}
		try {
			tool.addPlugin(TouchUIPlugin.class.getName());
		}
		catch (Exception e) {
			Msg.warn(TouchUIInitializer.class, "Could not add Touch UI to " + tool.getToolName(), e);
		}
	}
}
