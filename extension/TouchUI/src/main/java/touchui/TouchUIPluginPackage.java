/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package touchui;

import ghidra.framework.plugintool.util.PluginPackage;

/** Groups the Touch UI plugins under their own entry in File > Configure. */
public class TouchUIPluginPackage extends PluginPackage {

	public static final String NAME = "Touch UI";

	public TouchUIPluginPackage() {
		super(NAME, TouchIcons.FOCUS,
			"Layouts and controls for small screens and touch: a focus layout that shows " +
				"only the code, with the other panels in a drawer.",
			FEATURE_PRIORITY);
	}
}
