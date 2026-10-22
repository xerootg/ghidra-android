/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 */
package io.github.xerootg.ghidroid.awt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Development backend: serves frames over TCP to {@code tools/Viewer.java} so
 * the toolkit and the Ghidra tweaks can be worked on from a desktop JDK.
 *
 * Wire format, big-endian. Server to viewer: {@code int w, int h, w*h*4 bytes RGBX}.
 * Viewer to server: {@code int type, a, b, c, d} per {@link Input#handle}.
 */
final class SocketBackend implements Backend {

    private final int port;
    private volatile DataOutputStream out;
    private byte[] frame = new byte[0];

    SocketBackend(int port) {
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        ServerSocket server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket s = server.accept()) {
                    s.setTcpNoDelay(true);
                    out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 20));
                    Display.markDirty();
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    while (true) {
                        Input.handle(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
                    }
                } catch (IOException e) {
                    out = null;
                }
            }
        }, "ghidroid-socket");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean wholeScreen() {
        return true;
    }

    @Override
    public void windowsChanged(String list) {
        System.out.print("ghidroid windows:\n" + list);
    }

    @Override
    public void present(int[] pixels, int w, int h) {
        DataOutputStream o = out;
        if (o == null) {
            return;
        }
        int n = w * h;
        if (frame.length != n * 4) {
            frame = new byte[n * 4];
        }
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, n);
        try {
            o.writeInt(w);
            o.writeInt(h);
            o.write(frame);
            o.flush();
        } catch (IOException e) {
            out = null;
        }
    }
}
