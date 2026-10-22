/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Extensions that ship with the app as source (Touch UI). They are built on
 * the device against whichever Ghidra is installed, so they work with any
 * Ghidra version, and rebuilt when the app or Ghidra changes. They go into
 * Ghidra's own Ghidra/Extensions folder, which Ghidra loads without asking
 * to configure new plugins; Touch UI then turns itself on in CodeBrowser.
 */
final class BundledExtensions {

    private static final String TAG = "ghidroid";
    static final String NAME = "TouchUI";
    private static final String ASSET = "TouchUI-src.zip";
    private static final String STAMP = ".ghidroid-bundled";

    private static boolean running;
    /** A build failed in this process; don't retry until the app restarts. */
    private static boolean failed;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private BundledExtensions() {
    }

    /** App build, Ghidra version and a checksum of the bundled source. */
    private static String expectedStamp(Context ctx, Installer inst) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                crc.update(buf, 0, n);
            }
        } catch (IOException e) {
            return BuildConfig.VERSION_CODE + ":" + inst.ghidraVersion();
        }
        return BuildConfig.VERSION_CODE + ":" + inst.ghidraVersion() + ":" + Long.toHexString(crc.getValue());
    }

    private static File stampFile(Extensions ext) {
        return new File(new File(ext.systemDir(), NAME), STAMP);
    }

    /** Whether Touch UI needs (re)building for this app and Ghidra. */
    static boolean needed(Context ctx) {
        Installer inst = new Installer(ctx);
        if (!inst.ghidraReady() || !inst.runtimeReady()) {
            return false;
        }
        String have = Installer.readText(stampFile(new Extensions(inst)));
        return have == null || !have.trim().equals(expectedStamp(ctx, inst));
    }

    static boolean isRunning() {
        return running;
    }

    /** A build is running or about to start; Ghidra should wait for it. */
    static boolean pending(Context ctx) {
        return running || (!failed && needed(ctx));
    }

    /**
     * Builds and installs Touch UI in the background if needed. {@code status}
     * gets progress text, and null when finished (on the main thread).
     */
    static void ensure(Context ctx, Consumer<String> status) {
        if (running || failed || !needed(ctx) || ExtensionsActivity.processRunning(ctx, ":tools")) {
            return;
        }
        running = true;
        Context app = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        Installer inst = new Installer(app);
        Extensions ext = new Extensions(inst);
        status.accept(app.getString(R.string.bundled_building));
        IO.execute(() -> {
            File project;
            File out = new File(ext.workDir(), "out/" + NAME + ".zip");
            try {
                File src = new File(ext.workDir(), ASSET);
                src.getParentFile().mkdirs();
                try (InputStream in = app.getAssets().open(ASSET); OutputStream o = new FileOutputStream(src)) {
                    in.transferTo(o);
                }
                project = ext.unpackSource(src, new File(ext.workDir(), "bundled"));
                out.getParentFile().mkdirs();
                out.delete();
            } catch (IOException e) {
                Log.w(TAG, "bundled extension", e);
                finish(ui, status, app.getString(R.string.bundled_failed));
                return;
            }
            ui.post(() -> {
                File log = ToolsService.build(app, project, out, false);
                long started = System.currentTimeMillis();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Integer rc = ToolsService.result(log);
                        boolean dead = rc == null && System.currentTimeMillis() - started > 15000
                                && !ExtensionsActivity.processRunning(app, ":tools");
                        if (rc == null && !dead) {
                            ui.postDelayed(this, 1000);
                            return;
                        }
                        if (rc == null || rc != 0 || !out.isFile()) {
                            Log.w(TAG, "Touch UI build failed: " + rc);
                            finish(ui, status, app.getString(R.string.bundled_failed));
                            return;
                        }
                        IO.execute(() -> {
                            try {
                                ext.install(out, true, ext.systemDir());
                                // An older copy in the user folder would load twice.
                                Installer.deleteTree(new File(ext.userDir(), NAME));
                                try (OutputStream o = new FileOutputStream(stampFile(ext))) {
                                    o.write(expectedStamp(app, inst).getBytes(StandardCharsets.UTF_8));
                                }
                                Log.i(TAG, "Touch UI installed");
                                finish(ui, status, null);
                            } catch (IOException e) {
                                Log.w(TAG, "Touch UI install", e);
                                finish(ui, status, app.getString(R.string.bundled_failed));
                            }
                        });
                    }
                }, 1000);
            });
        });
    }

    private static void finish(Handler ui, Consumer<String> status, String message) {
        ui.post(() -> {
            running = false;
            failed = message != null;
            status.accept(message);
        });
    }
}
