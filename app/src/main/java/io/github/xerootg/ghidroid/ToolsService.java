/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one command-line Java tool (the extension builder) in a JVM of its
 * own, in the {@code :tools} process. A process hosts one JVM for its life,
 * and the JVM's exit ends the process, so every run gets a fresh process.
 *
 * Output goes to {@code log}; when the JVM exits, its status is written to
 * {@code log + ".rc"}, which the UI waits for.
 */
public final class ToolsService extends Service implements Native.Host {

    static final String EXTRA_ARGS = "args";
    static final String EXTRA_LOG = "log";

    private static boolean started;
    private File rcFile;

    /** Starts the extension builder; returns the log file to watch. */
    static File build(Context ctx, File project, File outZip, boolean fetchDeps) {
        Installer inst = new Installer(ctx);
        File log = new File(new Extensions(inst).workDir(), "build.log");
        log.getParentFile().mkdirs();
        log.delete();
        new File(log.getPath() + ".rc").delete();
        ArrayList<String> args = new ArrayList<>();
        args.add(inst.ghidra.getPath());
        args.add(project.getPath());
        args.add(outZip.getPath());
        if (fetchDeps) {
            args.add("--fetch-deps");
        }
        args.add("--log");
        args.add(log.getPath());
        args.add("--rc");
        args.add(log.getPath() + ".rc");
        ctx.startService(new Intent(ctx, ToolsService.class)
                .putStringArrayListExtra(EXTRA_ARGS, args)
                .putExtra(EXTRA_LOG, log.getPath()));
        return log;
    }

    /** Exit status of the run whose log is {@code log}, or null while it runs. */
    static Integer result(File log) {
        String rc = Installer.readText(new File(log.getPath() + ".rc"));
        if (rc == null) {
            return null;
        }
        try {
            return Integer.parseInt(rc.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || started) {
            return START_NOT_STICKY;
        }
        started = true;
        Native.setHost(this);
        Installer inst = new Installer(this);
        File log = new File(intent.getStringExtra(EXTRA_LOG));
        rcFile = new File(log.getPath() + ".rc");
        inst.tmp.mkdirs();
        File javac = inst.javacDir();
        List<String> a = new ArrayList<>();
        a.add("java");
        a.add("-Xmx1536m");
        a.add("-Xss8m");
        a.add("--patch-module");
        a.add("java.compiler=" + new File(javac, "compiler-patch.jar"));
        for (String pkg : new String[] { "jdk.internal.javac", "jdk.internal.jmod",
                "jdk.internal.misc", "sun.invoke.util", "sun.reflect.annotation" }) {
            a.add("--add-exports");
            a.add("java.base/" + pkg + "=ALL-UNNAMED");
        }
        a.add("-Djava.awt.headless=true");
        a.add("-Duser.home=" + inst.home);
        a.add("-Djava.io.tmpdir=" + inst.tmp);
        a.add("-Dfile.encoding=UTF8");
        a.add("-Djdk.lang.Process.launchMechanism=FORK");
        a.add("-Xshare:off");
        a.add("-cp");
        a.add(new File(inst.files, "ghidroid-tools.jar") + File.pathSeparator
                + new File(javac, "jdk.compiler.jar") + File.pathSeparator
                + new File(javac, "jdk.internal.opt.jar"));
        a.add("io.github.xerootg.ghidroid.tools.ExtensionBuilder");
        a.addAll(intent.getStringArrayListExtra(EXTRA_ARGS));
        Log.i("ghidroid", "tools: " + a);
        // The builder writes its own log and status (System.exit never returns here).
        Native.launchJvm(a.toArray(new String[0]), inst.jre.getPath(),
                new File(log.getParentFile(), "tools-jvm.log").getPath());
        return START_NOT_STICKY;
    }

    @Override
    public void onJvmExit(int code) {
        // Only reached if the JVM fails to start; a normal run exits the process.
        if (!rcFile.exists()) {
            try {
                Installer.writeText(rcFile, Integer.toString(code == 0 ? 1 : code));
            } catch (IOException e) {
                Log.e("ghidroid", "tools rc", e);
            }
        }
    }

    @Override
    public void onHostCall(int what, int arg, String text) {
    }

    @Override
    public String onHostQuery(int what) {
        return null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
