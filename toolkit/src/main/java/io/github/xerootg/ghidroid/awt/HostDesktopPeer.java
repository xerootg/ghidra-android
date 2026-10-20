/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.Desktop.Action;
import java.awt.peer.DesktopPeer;
import java.io.File;
import java.io.IOException;
import java.net.URI;

/** {@link java.awt.Desktop} support: hands links and files to Android. */
final class HostDesktopPeer implements DesktopPeer {

    @Override
    public boolean isSupported(Action action) {
        return action == Action.BROWSE || action == Action.OPEN || action == Action.MAIL;
    }

    @Override
    public void open(File file) throws IOException {
        Bridge.openUri(file.toURI().toString());
    }

    @Override
    public void edit(File file) throws IOException {
        open(file);
    }

    @Override
    public void print(File file) throws IOException {
        throw new IOException("printing is not supported");
    }

    @Override
    public void mail(URI mailtoURL) throws IOException {
        Bridge.openUri(mailtoURL.toString());
    }

    @Override
    public void browse(URI uri) throws IOException {
        Bridge.openUri(uri.toString());
    }
}
