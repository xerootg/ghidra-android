/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scriptable client for the socket backend, for automated UI checks.
 * Reads commands from stdin, one per line:
 *   click X Y | rclick X Y | dclick X Y | move X Y | drag X1 Y1 X2 Y2
 *   wheel X Y AMOUNT | key VK [MODS] | type TEXT | resize W H
 *   sleep MS | snap FILE.png
 *   java tools/Driver.java [port] < commands
 */
import java.awt.event.KeyEvent;
import java.awt.image.*;
import java.io.*;
import java.net.Socket;
import javax.imageio.ImageIO;

public class Driver {
    static volatile BufferedImage img;
    static DataOutputStream out;

    static void send(int t, int a, int b, int c, int d) throws IOException {
        out.writeInt(t); out.writeInt(a); out.writeInt(b); out.writeInt(c); out.writeInt(d);
        out.flush();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7777;
        Socket s = null;
        for (int i = 0; i < 1200 && s == null; i++) {
            try { s = new Socket("127.0.0.1", port); } catch (IOException e) { Thread.sleep(250); }
        }
        s.setTcpNoDelay(true);
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 20));
        Thread reader = new Thread(() -> {
            try {
                byte[] buf = new byte[0];
                while (true) {
                    int w = in.readInt(), h = in.readInt();
                    if (buf.length != w * h * 4) buf = new byte[w * h * 4];
                    in.readFully(buf);
                    BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    int[] px = ((DataBufferInt) i.getRaster().getDataBuffer()).getData();
                    for (int p = 0, o = 0; p < w * h; p++, o += 4) {
                        px[p] = (buf[o] & 0xff) << 16 | (buf[o + 1] & 0xff) << 8 | (buf[o + 2] & 0xff);
                    }
                    img = i;
                }
            } catch (IOException e) {
                System.err.println("disconnected");
                System.exit(0);
            }
        });
        reader.setDaemon(true);
        reader.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = r.readLine()) != null) {
            String[] a = line.trim().split("\\s+", 2);
            String[] p = a.length > 1 ? a[1].split("\\s+") : new String[0];
            try {
                switch (a[0]) {
                    case "move" -> send(1, i(p[0]), i(p[1]), 0, 0);
                    case "click", "rclick", "dclick" -> {
                        int b = a[0].equals("rclick") ? 3 : 1;
                        int n = a[0].equals("dclick") ? 2 : 1;
                        for (int k = 0; k < n; k++) {
                            send(2, i(p[0]), i(p[1]), b, 0);
                            send(3, i(p[0]), i(p[1]), b, 0);
                        }
                    }
                    case "drag" -> {
                        int x1 = i(p[0]), y1 = i(p[1]), x2 = i(p[2]), y2 = i(p[3]);
                        send(2, x1, y1, 1, 0);
                        for (int k = 1; k <= 10; k++) {
                            send(1, x1 + (x2 - x1) * k / 10, y1 + (y2 - y1) * k / 10, 0, 0);
                            Thread.sleep(15);
                        }
                        send(3, x2, y2, 1, 0);
                    }
                    case "wheel" -> send(4, i(p[0]), i(p[1]), (int) (Double.parseDouble(p[2]) * 1000), 0);
                    case "key" -> {
                        int vk = vk(p[0]);
                        int mods = p.length > 1 ? i(p[1]) : 0;
                        send(5, vk, mods, 0, 1);
                        send(6, vk, mods, 0, 1);
                    }
                    case "type" -> {
                        for (char c : a[1].toCharArray()) {
                            int vk = KeyEvent.getExtendedKeyCodeForChar(c);
                            int mods = Character.isUpperCase(c) ? KeyEvent.SHIFT_DOWN_MASK : 0;
                            send(5, vk, mods, c, 1);
                            send(6, vk, mods, c, 1);
                        }
                    }
                    case "resize" -> send(8, i(p[0]), i(p[1]), 0, 0);
                    case "sleep" -> Thread.sleep(Long.parseLong(p[0]));
                    case "snap" -> {
                        Thread.sleep(150);
                        ImageIO.write(img, "png", new File(p[0]));
                        System.out.println("snap " + p[0]);
                    }
                    default -> System.err.println("? " + line);
                }
            } catch (Exception e) {
                System.err.println("error in '" + line + "': " + e);
            }
        }
    }

    static int i(String s) { return Integer.parseInt(s); }

    static int vk(String s) throws Exception {
        if (s.matches("\\d+")) return Integer.parseInt(s);
        return KeyEvent.class.getField("VK_" + s.toUpperCase()).getInt(null);
    }
}
