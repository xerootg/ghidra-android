/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Ghidra window (a frame or dialog) in an Android window of its own, for
 * Samsung DeX and other freeform windowing. GhidraActivity opens these as
 * Ghidra opens windows, and finishes them as Ghidra closes them.
 *
 * The window's content area is shown here; DeX draws the title bar, and its
 * close button asks Ghidra to close the window (Ghidra may still ask to save
 * or refuse, in which case GhidraActivity opens a new host).
 */
public final class GhidraWindowActivity extends Activity implements SurfaceHolder.Callback {

    static final String EXTRA_ID = "window";

    /** Live hosts by Ghidra window id (the :ghidra process). */
    static final Map<Integer, GhidraWindowActivity> HOSTS = new ConcurrentHashMap<>();

    private int id;
    private ScreenView screen;
    private float scale;
    /** Set when Ghidra closed the window (not the user closing the host). */
    boolean closedByGhidra;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getIntent().getIntExtra(EXTRA_ID, -1);
        if (id < 0 || HOSTS.containsKey(id)) {
            finish();
            return;
        }
        HOSTS.put(id, this);
        scale = new Prefs(this).scale();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screen = new ScreenView(this);
        screen.setScale(scale);
        screen.getHolder().addCallback(this);
        root.addView(screen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        screen.requestFocus();
        update(GhidraActivity.windowInfo(id));
    }

    int windowId() {
        return id;
    }

    /** New window list entry for this window: title and where its content is on the screen. */
    void update(WindowTray.Win w) {
        if (w == null || screen == null) {
            return;
        }
        screen.setOrigin(w.x, w.y);
        if (!w.title.equals(getTitle() == null ? "" : getTitle().toString())) {
            setTitle(w.title);
            setTaskDescription(new ActivityManager.TaskDescription.Builder().setLabel(w.title).build());
        }
    }

    /** Ghidra closed the window. */
    void closeFromGhidra() {
        closedByGhidra = true;
        finishAndRemoveTask();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Native.setSurfaceFor(id, holder.getSurface());
        Native.pushEvent(ScreenView.HOST_ATTACH, id, 0, 0, 0);
        Native.pushEvent(ScreenView.WINDOW_RESIZE, id, Math.max(160, Math.round(width / scale)),
                Math.max(100, Math.round(height / scale)), 0);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Native.pushEvent(ScreenView.HOST_DETACH, id, 0, 0, 0);
        Native.setSurfaceFor(id, null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Keys typed here go to this Ghidra window.
            Native.pushEvent(ScreenView.WINDOW_FRONT, id, 0, 0, 0);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                screen.tapKey(KeyMap.VK_ESCAPE, 0x1b);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(e);
        }
        if (screen.sendKey(e)) {
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getUnicodeChar() != 0) {
            screen.typeText(String.valueOf((char) e.getUnicodeChar()));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        if (HOSTS.get(id) == this) {
            HOSTS.remove(id);
            if (!closedByGhidra && isFinishing()) {
                // The user closed the Android window: close the Ghidra window
                // the way its own close button would.
                Native.pushEvent(ScreenView.WINDOW_CLOSE, id, 0, 0, 0);
                GhidraActivity main = GhidraActivity.current;
                if (main != null) {
                    main.reconcileSoon(id);
                }
            }
        }
        super.onDestroy();
    }

    /** Opens a host for Ghidra window {@code id} in a new freeform window. */
    static void open(Context ctx, WindowTray.Win w, float scale, android.graphics.Rect near) {
        Intent i = new Intent(ctx, GhidraWindowActivity.class)
                .putExtra(EXTRA_ID, w.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
        // Keep the window on the display: big frames get shrunk (Ghidra
        // follows the host's size) and everything cascades from the opener.
        android.view.WindowMetrics max = ctx.getSystemService(android.view.WindowManager.class)
                .getMaximumWindowMetrics();
        android.graphics.Insets bars = max.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        android.graphics.Rect screen = new android.graphics.Rect(max.getBounds());
        // The caption bar of the freeform window sits above the content.
        screen.inset(bars.left, bars.top + Math.round(40 * scale), bars.right, bars.bottom);
        int margin = Math.round(24 * scale);
        int width = Math.min(Math.round(w.w * scale), screen.width() - 2 * margin);
        int height = Math.min(Math.round(w.h * scale), screen.height() - 2 * margin);
        int step = 1 + HOSTS.size() % 6;
        int left = Math.max(screen.left + margin, Math.min(near.left + Math.round(48 * scale) * step,
                screen.right - margin - width));
        int top = Math.max(screen.top + margin, Math.min(near.top + Math.round(36 * scale) * step,
                screen.bottom - margin - height));
        opts.setLaunchBounds(new android.graphics.Rect(left, top, left + width, top + height));
        ctx.startActivity(i, opts.toBundle());
    }
}
