/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Runs Ghidra. Lives in the {@code :ghidra} process together with the JVM,
 * which is started once per process and outlives activity re-creation.
 */
public final class GhidraActivity extends Activity implements Native.Host, SurfaceHolder.Callback {

    private static final String TAG = "ghidroid";

    /** Sent by MainActivity when the theme settings change. */
    static final String ACTION_THEME_CHANGED = "io.github.xerootg.ghidroid.THEME_CHANGED";

    private final android.content.BroadcastReceiver themeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            prefs = new Prefs(GhidraActivity.this);
            int kind = themeKind();
            if (jvmStarted && kind != THEME_NONE) {
                Native.pushEvent(ScreenView.THEME, kind, 0, 0, 0);
            }
        }
    };

    /** The JVM can only be started once per process. */
    private static boolean jvmStarted;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScreenView screen;
    private LinearLayout keyBar;
    private Prefs prefs;
    private float scale;

    private int stickyMods;
    private boolean rightClickArmed;
    private final List<Button> modButtons = new ArrayList<>();
    private Button rightClickButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        scale = prefs.scale();
        lastNight = systemNight() ? 1 : 0;
        Native.setHost(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screen = new ScreenView(this);
        screen.setScale(scale);
        screen.getHolder().addCallback(this);
        screen.setModifiers(new ScreenView.Modifiers() {
            @Override
            public int take() {
                int m = stickyMods;
                if (m != 0) {
                    stickyMods = 0;
                    ui.post(GhidraActivity.this::refreshKeyBar);
                }
                return m;
            }

            @Override
            public boolean takeRightClick() {
                boolean r = rightClickArmed;
                if (r) {
                    rightClickArmed = false;
                    ui.post(GhidraActivity.this::refreshKeyBar);
                }
                return r;
            }
        });
        root.addView(screen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        keyBar = buildKeyBar();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(16), dp(16));
        root.addView(keyBar, lp);
        keyBar.setVisibility(prefs.keyBar() ? View.VISIBLE : View.GONE);

        setContentView(root);
        hideSystemBars();
        screen.requestFocus();

        // Back gestures (no key event) close dialogs and menus in Ghidra too.
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> screen.tapKey(KeyMap.VK_ESCAPE, 0x1b));

        registerReceiver(themeReceiver, new android.content.IntentFilter(ACTION_THEME_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);

        Intent svc = new Intent(this, GhidraService.class);
        startForegroundService(svc);
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    // ---- surface ------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        int w = Math.max(320, Math.round(width / scale));
        int h = Math.max(240, Math.round(height / scale));
        Native.setSurface(holder.getSurface());
        if (!jvmStarted) {
            jvmStarted = true;
            startJvm(w, h);
        } else {
            Native.pushEvent(ScreenView.RESIZE, w, h, 0, 0);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Native.setSurface(null);
    }

    private void startJvm(int w, int h) {
        Installer inst = new Installer(this);
        if (!inst.runtimeReady() || !inst.ghidraReady()) {
            Toast.makeText(this, R.string.not_installed, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        inst.home.mkdirs();
        inst.tmp.mkdirs();
        // Keep local projects opening when the user name changes.
        String android = Installer.androidUserName();
        String user = prefs.userName().isEmpty() ? android : prefs.userName();
        java.util.Set<String> previous = new java.util.HashSet<>();
        previous.add(android);
        if (!prefs.lastUserName().isEmpty()) {
            previous.add(prefs.lastUserName());
        }
        int adopted = inst.adoptProjects(previous, user);
        if (adopted > 0) {
            Log.i(TAG, "projects now owned by " + user + ": " + adopted);
        }
        prefs.setLastUserName(user);
        try {
            inst.prepareGhidraPrefs(themeLocator(themeKind()), prefs.server());
        } catch (java.io.IOException e) {
            Log.w(TAG, "themes", e);
        }
        List<String> args = new ArrayList<>();
        args.add("java");
        args.add("-Xmx" + prefs.heapMb() + "m");
        args.add("-XX:+UseG1GC");
        args.add("-Xss8m");
        args.add("--patch-module");
        args.add("java.desktop=" + inst.toolkitJar());
        // javac lives on the class path (see compiler-patch/README.md).
        File javac = inst.javacDir();
        args.add("--patch-module");
        args.add("java.compiler=" + new File(javac, "compiler-patch.jar"));
        for (String pkg : new String[] { "jdk.internal.javac", "jdk.internal.jmod",
                "jdk.internal.misc", "sun.invoke.util", "sun.reflect.annotation" }) {
            args.add("--add-exports");
            args.add("java.base/" + pkg + "=ALL-UNNAMED");
        }
        args.add("-Dghidroid.backend=android:" + new File(inst.nativeLibDir(), "libghidroid.so"));
        args.add("-Dcacio.managed.screensize=" + w + "x" + h);
        args.add("-Dghidroid.fonts=" + inst.fontsDir());
        args.add("-Dghidroid.defaultFont=" + new File(inst.fontsDir(), "DejaVuSans.ttf"));
        args.add("-Dsun.awt.fontconfig=" + inst.fontConfig());
        args.add("-Djava.awt.headless=false");
        args.add("-Dghidroid.theme.dark=" + inst.themeFile(Installer.THEME_DARK_FILE));
        args.add("-Dghidroid.theme.black=" + inst.themeFile(Installer.THEME_BLACK_FILE));
        args.add("-Dghidroid.theme.light=" + Installer.THEME_LIGHT_CLASS);
        args.add("-Djava.system.class.loader=ghidra.GhidraClassLoader");
        args.add("-Dfile.encoding=UTF8");
        args.add("-Duser.home=" + inst.home);
        args.add("-Duser.dir=" + inst.home);
        args.add("-Djava.io.tmpdir=" + inst.tmp);
        args.add("-Duser.language=en");
        if (!prefs.userName().isEmpty()) {
            // Ghidra Server logins (without passwords) use this name.
            args.add("-Duser.name=" + prefs.userName());
        }
        args.add("-Duser.country=US");
        // posix_spawn needs jspawnhelper, which cannot be exec()ed from app storage.
        args.add("-Djdk.lang.Process.launchMechanism=FORK");
        args.add("-Dsun.java2d.opengl=false");
        args.add("-Dsun.java2d.xrender=false");
        args.add("-Dsun.java2d.pmoffscreen=false");
        args.add("-Dawt.useSystemAAFontSettings=on");
        args.add("-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3");
        args.add("-Xshare:off");
        args.add("--enable-native-access=ALL-UNNAMED");
        for (String a : prefs.extraJvmArgs().trim().split("\\s+")) {
            if (!a.isEmpty()) {
                args.add(a);
            }
        }
        args.add("-cp");
        args.add(new File(inst.ghidra, "Ghidra/Framework/Utility/lib/Utility.jar").getPath()
                + File.pathSeparator + new File(javac, "jdk.compiler.jar")
                + File.pathSeparator + new File(javac, "jdk.internal.opt.jar"));
        args.add("ghidra.Ghidra");
        args.add("ghidra.GhidraRun");
        Log.i(TAG, "JVM args: " + args);
        Native.launchJvm(args.toArray(new String[0]), inst.jre.getPath(), inst.logFile().getPath());
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(themeReceiver);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        screen.tapKey(KeyMap.VK_ESCAPE, 0x1b);
    }

    // ---- theme ------------------------------------------------------------------

    private static final int THEME_LIGHT = 0, THEME_DARK = 1, THEME_BLACK = 2, THEME_NONE = -1;
    private int lastNight = -1;

    private boolean systemNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The theme to use now, or THEME_NONE to leave Ghidra's own choice alone. */
    private int themeKind() {
        int dark = prefs.pureBlack() ? THEME_BLACK : THEME_DARK;
        switch (prefs.themeMode()) {
            case Prefs.THEME_DARK:
                return dark;
            case Prefs.THEME_LIGHT:
                return THEME_LIGHT;
            case Prefs.THEME_GHIDRA:
                return THEME_NONE;
            default:
                return systemNight() ? dark : THEME_LIGHT;
        }
    }

    private String themeLocator(int kind) {
        Installer inst = new Installer(this);
        switch (kind) {
            case THEME_DARK:
                return "File:" + inst.themeFile(Installer.THEME_DARK_FILE);
            case THEME_BLACK:
                return "File:" + inst.themeFile(Installer.THEME_BLACK_FILE);
            case THEME_LIGHT:
                return "Class:" + Installer.THEME_LIGHT_CLASS;
            default:
                return null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // surfaceChanged reports the new size; here only dark mode matters.
        int night = systemNight() ? 1 : 0;
        if (lastNight != -1 && night != lastNight && jvmStarted
                && Prefs.THEME_SYSTEM.equals(prefs.themeMode())) {
            Native.pushEvent(ScreenView.THEME, themeKind(), 0, 0, 0);
        }
        lastNight = night;
    }

    // ---- keys -----------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            // Back closes dialogs and menus in Ghidra instead of leaving it.
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                screen.tapKey(KeyMap.VK_ESCAPE, 0x1b);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE && e.getCharacters() != null) {
            screen.typeText(e.getCharacters());
            return true;
        }
        if (screen.sendKey(e)) {
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int ch = e.getUnicodeChar();
            if (ch != 0) {
                screen.typeText(String.valueOf((char) ch));
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean hasHardwareKeyboard() {
        Configuration c = getResources().getConfiguration();
        return c.keyboard != Configuration.KEYBOARD_NOKEYS
                && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    private void showKeyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (show) {
            screen.requestFocus();
            imm.showSoftInput(screen, 0);
        } else {
            imm.hideSoftInputFromWindow(screen.getWindowToken(), 0);
        }
    }

    // ---- key bar ------------------------------------------------------------------

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private LinearLayout buildKeyBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD0202124);
        bg.setCornerRadius(dp(24));
        bar.setBackground(bg);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.setElevation(dp(6));

        Button kb = barButton("⌨", v -> showKeyboard(true));
        bar.addView(kb);
        bar.addView(barButton("Windows", v -> Native.pushEvent(ScreenView.WINDOWS, 0, 0, 0, 0)));
        bar.addView(barButton("Esc", v -> screen.tapKey(KeyMap.VK_ESCAPE, 0x1b)));
        bar.addView(barButton("Tab", v -> screen.tapKey(KeyMap.VK_TAB, '\t')));
        bar.addView(modButton("Ctrl", KeyMap.CTRL_DOWN));
        bar.addView(modButton("Alt", KeyMap.ALT_DOWN));
        bar.addView(modButton("Shift", KeyMap.SHIFT_DOWN));
        rightClickButton = barButton("Right", v -> {
            rightClickArmed = !rightClickArmed;
            refreshKeyBar();
        });
        bar.addView(rightClickButton);

        // Drag the bar by its keyboard button's long press to move it out of the way.
        View.OnTouchListener mover = new View.OnTouchListener() {
            float sx, sy, tx, ty;
            boolean moving;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        tx = bar.getTranslationX();
                        ty = bar.getTranslationY();
                        moving = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (moving || Math.hypot(dx, dy) > dp(12)) {
                            moving = true;
                            bar.setTranslationX(tx + dx);
                            bar.setTranslationY(ty + dy);
                            v.cancelPendingInputEvents();
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        if (moving) {
                            v.setPressed(false);
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        };
        kb.setOnTouchListener(mover);
        return bar;
    }

    private Button barButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        // Key bar taps must not steal focus from the screen (keyboard input).
        b.setFocusable(false);
        return b;
    }

    private Button modButton(String label, int mask) {
        Button b = barButton(label, v -> {
            stickyMods ^= mask;
            refreshKeyBar();
        });
        b.setTag(mask);
        modButtons.add(b);
        return b;
    }

    private void refreshKeyBar() {
        for (Button b : modButtons) {
            int mask = (Integer) b.getTag();
            b.setTextColor((stickyMods & mask) != 0 ? 0xFF8AB4F8 : Color.WHITE);
        }
        rightClickButton.setTextColor(rightClickArmed ? 0xFF8AB4F8 : Color.WHITE);
    }

    // ---- calls from the JVM ------------------------------------------------------

    @Override
    public void onHostCall(int what, int arg, String text) {
        ui.post(() -> {
            switch (what) {
                case Native.HOST_CLIPBOARD_SET: {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Ghidra", text));
                    break;
                }
                case Native.HOST_OPEN_URI:
                    openUri(text);
                    break;
                case Native.HOST_TEXT_FOCUS:
                    if (!hasHardwareKeyboard()) {
                        showKeyboard(arg != 0);
                    }
                    break;
                case Native.HOST_CURSOR:
                    screen.setAwtCursor(arg);
                    break;
                case Native.HOST_HAPTIC:
                    screen.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    break;
                default:
                    break;
            }
        });
    }

    private void openUri(String uri) {
        try {
            Uri u = Uri.parse(uri);
            if ("file".equals(u.getScheme())) {
                Toast.makeText(this, u.getPath(), Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, uri, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public String onHostQuery(int what) {
        if (what != Native.QUERY_CLIPBOARD) {
            return null;
        }
        FutureTask<String> t = new FutureTask<>(() -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            CharSequence s = clip.getItemAt(0).coerceToText(this);
            return s != null ? s.toString() : null;
        });
        ui.post(t);
        try {
            return t.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onJvmExit(int code) {
        Log.i(TAG, "Ghidra exited with " + code);
        CountDownLatch done = new CountDownLatch(1);
        ui.post(() -> {
            stopService(new Intent(this, GhidraService.class));
            finishAndRemoveTask();
            done.countDown();
        });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
