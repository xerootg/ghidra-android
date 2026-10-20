/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

/** User settings, shared by both processes (read at JVM start). */
final class Prefs {

    private final SharedPreferences sp;
    private final Context ctx;

    Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        // Multi-process: always re-read from disk.
        sp = this.ctx.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
    }

    /** Max Java heap. Default: half the RAM, at most 8 GiB. */
    int heapMb() {
        return sp.getInt("heapMb", defaultHeapMb());
    }

    void setHeapMb(int mb) {
        sp.edit().putInt("heapMb", mb).apply();
    }

    int defaultHeapMb() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long half = mi.totalMem / 2 / (1024 * 1024);
        return (int) Math.max(1024, Math.min(8192, half / 256 * 256));
    }

    int maxHeapMb() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return (int) (mi.totalMem * 3 / 4 / (1024 * 1024) / 256 * 256);
    }

    /**
     * Screen pixels per Ghidra pixel. Ghidra is laid out for ~96-110 dpi
     * desktop screens; the default aims for a similar physical size at
     * reading distance on a tablet.
     */
    float scale() {
        return sp.getFloat("scale", defaultScale());
    }

    void setScale(float s) {
        sp.edit().putFloat("scale", s).apply();
    }

    float defaultScale() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float s = dm.densityDpi / 170f;
        return Math.max(1f, Math.round(s * 20) / 20f);
    }

    /** Extra JVM arguments, whitespace-separated. */
    String extraJvmArgs() {
        return sp.getString("jvmArgs", "");
    }

    void setExtraJvmArgs(String s) {
        sp.edit().putString("jvmArgs", s).apply();
    }

    /** Ghidra theme: follow Android's dark mode, always dark/light, or leave it to Ghidra. */
    static final String THEME_SYSTEM = "system", THEME_DARK = "dark", THEME_LIGHT = "light",
            THEME_GHIDRA = "ghidra";

    String themeMode() {
        return sp.getString("themeMode", THEME_SYSTEM);
    }

    void setThemeMode(String m) {
        // commit, not apply: the :ghidra process reads it right after.
        sp.edit().putString("themeMode", m).commit();
    }

    /** Use the true-black (OLED) dark theme. */
    boolean pureBlack() {
        return sp.getBoolean("pureBlack", false);
    }

    void setPureBlack(boolean b) {
        sp.edit().putBoolean("pureBlack", b).commit();
    }

    /** Ghidra Server as host:port, pre-filled in Ghidra's shared project dialogs. Empty: none. */
    String server() {
        return sp.getString("server", "");
    }

    void setServer(String s) {
        sp.edit().putString("server", s.trim()).commit();
    }

    /**
     * User name Ghidra presents to a server (Java's user.name). Empty: the
     * Android one (u0_aNNN), which a server will not know.
     */
    String userName() {
        return sp.getString("userName", "");
    }

    void setUserName(String s) {
        sp.edit().putString("userName", s.trim()).commit();
    }

    /** The user name Ghidra last ran with (to adopt its projects after a change). */
    String lastUserName() {
        return sp.getString("lastUserName", "");
    }

    void setLastUserName(String s) {
        sp.edit().putString("lastUserName", s).commit();
    }

    /** Show the floating key bar (Esc, Ctrl, Alt, ...). */
    boolean keyBar() {
        return sp.getBoolean("keyBar", true);
    }

    void setKeyBar(boolean b) {
        sp.edit().putBoolean("keyBar", b).apply();
    }
}
