/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Desktop viewer for the toolkit's socket backend. Run Ghidra with
 * -Dghidroid.backend=socket:7777 (tools/run-desktop.sh), then:
 *
 *   java tools/Viewer.java [port] [scale]
 *
 * Frames are shown at 1/scale size so a tablet-sized screen fits a laptop.
 * Mouse and keyboard go back as Input messages. Resize the window to resize
 * the remote screen.
 */
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.net.Socket;
import javax.swing.*;

public class Viewer {
    static volatile BufferedImage img;
    static DataOutputStream out;
    static double scale = 1;

    static synchronized void send(int t, int a, int b, int c, int d) {
        try {
            out.writeInt(t); out.writeInt(a); out.writeInt(b); out.writeInt(c); out.writeInt(d);
            out.flush();
        } catch (IOException e) {
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7777;
        scale = args.length > 1 ? Double.parseDouble(args[1]) : 1;
        Socket s = null;
        for (int i = 0; i < 600 && s == null; i++) {
            try { s = new Socket("127.0.0.1", port); } catch (IOException e) { Thread.sleep(200); }
        }
        s.setTcpNoDelay(true);
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 20));

        JPanel view = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                BufferedImage i = img;
                if (i != null) {
                    ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(i, 0, 0, (int) (i.getWidth() / scale), (int) (i.getHeight() / scale), null);
                }
            }
        };
        view.setFocusable(true);
        view.setFocusTraversalKeysEnabled(false);
        MouseAdapter m = new MouseAdapter() {
            int x(MouseEvent e) { return (int) (e.getX() * scale); }
            int y(MouseEvent e) { return (int) (e.getY() * scale); }
            int mods(InputEvent e) {
                return e.getModifiersEx() & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
                        | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK);
            }
            public void mouseMoved(MouseEvent e) { send(1, x(e), y(e), 0, mods(e)); }
            public void mouseDragged(MouseEvent e) { send(1, x(e), y(e), 0, mods(e)); }
            public void mousePressed(MouseEvent e) { view.requestFocusInWindow(); send(2, x(e), y(e), e.getButton(), mods(e)); }
            public void mouseReleased(MouseEvent e) { send(3, x(e), y(e), e.getButton(), mods(e)); }
            public void mouseWheelMoved(MouseWheelEvent e) {
                send(4, x(e), y(e), (int) (e.getPreciseWheelRotation() * 1000), mods(e));
            }
        };
        view.addMouseListener(m);
        view.addMouseMotionListener(m);
        view.addMouseWheelListener(m);
        view.addKeyListener(new KeyAdapter() {
            int mods(KeyEvent e) { return e.getModifiersEx(); }
            int ch(KeyEvent e) { char c = e.getKeyChar(); return c == KeyEvent.CHAR_UNDEFINED ? 0 : c; }
            public void keyPressed(KeyEvent e) { send(5, e.getKeyCode(), mods(e), ch(e), e.getKeyLocation()); }
            public void keyReleased(KeyEvent e) { send(6, e.getKeyCode(), mods(e), ch(e), e.getKeyLocation()); }
        });
        JFrame f = new JFrame("Ghidra for Android viewer");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.add(view);
        view.setPreferredSize(new Dimension((int) (1600 / scale), (int) (1000 / scale)));
        f.pack();
        f.setVisible(true);
        view.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                send(8, (int) (view.getWidth() * scale), (int) (view.getHeight() * scale), 0, 0);
            }
        });
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
            view.repaint();
        }
    }
}
