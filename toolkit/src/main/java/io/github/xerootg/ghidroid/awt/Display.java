/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.github.caciocavallosilano.cacio.peer.managed.FullScreenWindowFactory;

/**
 * The pixels of the one AWT screen.
 *
 * The buffer is {@code TYPE_INT_BGR}: on a little-endian CPU each int is laid
 * out as R, G, B, X in memory, which is exactly Android's {@code RGBX_8888},
 * so a frame goes to the surface with a plain copy.
 */
public final class Display {

    /**
     * How long after the last paint the frame pump keeps sending frames at
     * full rate. A paint can take longer than a frame and we cannot see it
     * end (the toolkit only sees the Graphics being created), so this is
     * generous; the pump also refreshes slowly when idle.
     */
    private static final long SETTLE_NANOS = 500_000_000L;

    private static volatile BufferedImage image;
    private static volatile int[] pixels;
    private static volatile long dirtyUntil;

    static {
        Dimension d = FullScreenWindowFactory.getScreenDimension();
        allocate(d.width, d.height);
    }

    private Display() {
    }

    private static void allocate(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_BGR);
        BufferedImage old = image;
        if (old != null) {
            Graphics2D g = img.createGraphics();
            g.drawImage(old, 0, 0, null);
            g.dispose();
        }
        pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        image = img;
        markDirty();
    }

    public static BufferedImage getImage() {
        return image;
    }

    public static int[] getPixels() {
        return pixels;
    }

    public static int getWidth() {
        return image.getWidth();
    }

    public static int getHeight() {
        return image.getHeight();
    }

    public static Rectangle getBounds() {
        BufferedImage img = image;
        return new Rectangle(0, 0, img.getWidth(), img.getHeight());
    }

    public static Graphics2D createGraphics() {
        markDirty();
        return image.createGraphics();
    }

    public static void markDirty() {
        dirtyUntil = System.nanoTime() + SETTLE_NANOS;
    }

    static boolean isDirty() {
        return System.nanoTime() - dirtyUntil < 0;
    }

    /**
     * Changes the screen size. Called off the EDT; windows are re-laid out by
     * {@link WindowManager}.
     */
    public static synchronized void resize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (WindowManager.separate) {
            // In separate mode the screen is sized to the windows; this is the
            // single-screen size to return to.
            WindowManager.singleWidth = w;
            WindowManager.singleHeight = h;
            return;
        }
        if (w == getWidth() && h == getHeight()) {
            return;
        }
        resizeOnly(w, h);
        WindowManager.screenResized(w, h);
    }

    /** Changes the buffer size without re-placing windows. */
    static synchronized void resizeOnly(int w, int h) {
        if (w == getWidth() && h == getHeight()) {
            return;
        }
        FullScreenWindowFactory.setScreenDimension(w, h);
        allocate(w, h);
    }
}
