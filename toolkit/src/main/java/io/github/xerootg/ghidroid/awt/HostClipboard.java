/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.Objects;

/**
 * System clipboard that shares plain text with the host (Android) clipboard.
 * Richer flavors stay local to the JVM.
 */
public final class HostClipboard extends Clipboard {

    private String lastHostText;

    public HostClipboard() {
        super("System");
    }

    @Override
    public synchronized void setContents(Transferable contents, ClipboardOwner owner) {
        super.setContents(contents, owner);
        if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            try {
                String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                lastHostText = text;
                Bridge.setClipboard(text);
            } catch (UnsupportedFlavorException | IOException e) {
                // Nothing to share.
            }
        }
    }

    /** Picks up text copied in other apps since we last looked. */
    private synchronized void sync() {
        String host = Bridge.getClipboard();
        if (host != null && !Objects.equals(host, lastHostText)) {
            lastHostText = host;
            super.setContents(new StringSelection(host), null);
        }
    }

    @Override
    public Transferable getContents(Object requestor) {
        sync();
        return super.getContents(requestor);
    }

    @Override
    public DataFlavor[] getAvailableDataFlavors() {
        sync();
        return super.getAvailableDataFlavors();
    }

    @Override
    public boolean isDataFlavorAvailable(DataFlavor flavor) {
        sync();
        return super.isDataFlavorAvailable(flavor);
    }

    @Override
    public Object getData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        sync();
        return super.getData(flavor);
    }
}
