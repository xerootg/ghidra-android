/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Setup and launch: installs the runtime and Ghidra, holds the settings. */
public final class MainActivity extends Activity {

    private static final int REQ_ZIP = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Installer inst;
    private Prefs prefs;
    private volatile boolean busy;

    private TextView status;
    private TextView progressText;
    private ProgressBar progress;
    private Button launch;
    private Button download;
    private Button importZip;
    private Button storage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inst = new Installer(this);
        prefs = new Prefs(this);
        setContentView(buildUi());
        runTask(p -> inst.installRuntime(p));
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---- UI ---------------------------------------------------------------------

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        col.addView(title);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, dp(8), 0, dp(16));
        col.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        col.addView(progress);
        progressText = new TextView(this);
        progressText.setVisibility(View.GONE);
        col.addView(progressText);

        launch = button(col, getString(R.string.launch), v -> launch());
        launch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        download = button(col, getString(R.string.download, BuildConfig.GHIDRA_VERSION),
                v -> runTask(p -> {
                    File zip = inst.download(BuildConfig.GHIDRA_VERSION, p);
                    inst.installGhidra(zip, p);
                    zip.delete();
                }));
        importZip = button(col, getString(R.string.install_zip), v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            startActivityForResult(i, REQ_ZIP);
        });
        storage = button(col, getString(R.string.storage_access), v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        col.addView(heading(getString(R.string.server_heading)));
        addServerSettings(col);

        col.addView(heading(getString(R.string.settings)));
        addThemeSetting(col);
        addScaleSetting(col);
        addHeapSetting(col);
        addJvmArgsSetting(col);
        Button keyBar = button(col, "", null);
        keyBar.setOnClickListener(v -> {
            prefs.setKeyBar(!prefs.keyBar());
            keyBar.setText(prefs.keyBar() ? R.string.keybar_on : R.string.keybar_off);
        });
        keyBar.setText(prefs.keyBar() ? R.string.keybar_on : R.string.keybar_off);
        button(col, getString(R.string.show_log), v -> showLog());

        TextView about = new TextView(this);
        about.setText(R.string.about);
        about.setPadding(0, dp(24), 0, 0);
        about.setAlpha(0.7f);
        col.addView(about);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        return sv;
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setPadding(0, dp(24), 0, dp(8));
        return t;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private EditText textSetting(LinearLayout col, int label, String value, String hint,
            java.util.function.Consumer<String> save) {
        TextView l = new TextView(this);
        l.setText(label);
        col.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint(hint);
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                save.accept(e.getText().toString());
            }
        });
        col.addView(e);
        return e;
    }

    private void addServerSettings(LinearLayout col) {
        EditText server = textSetting(col, R.string.server_label, prefs.server(), "192.168.1.10:13100",
                prefs::setServer);
        server.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = textSetting(col, R.string.user_label, prefs.userName(), getString(R.string.user_hint),
                prefs::setUserName);
        TextView result = new TextView(this);
        Button test = button(col, getString(R.string.server_test), null);
        col.addView(result);
        test.setOnClickListener(v -> {
            prefs.setServer(server.getText().toString());
            prefs.setUserName(user.getText().toString());
            String s = prefs.server();
            if (s.isEmpty()) {
                result.setText(R.string.server_none);
                return;
            }
            result.setText(R.string.server_testing);
            worker.execute(() -> {
                String msg = testServer(s);
                ui.post(() -> result.setText(msg));
            });
        });
        TextView note = new TextView(this);
        note.setText(R.string.server_note);
        note.setAlpha(0.7f);
        col.addView(note);
    }

    /** Checks that the server's three ports answer (registry, SSL RMI, block stream). */
    private String testServer(String server) {
        String host = server;
        int port = 13100;
        int colon = server.lastIndexOf(':');
        if (colon > 0) {
            host = server.substring(0, colon);
            try {
                port = Integer.parseInt(server.substring(colon + 1));
            } catch (NumberFormatException e) {
                return getString(R.string.server_bad_port);
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean all = true;
        for (int p = port; p <= port + 2; p++) {
            long t0 = System.nanoTime();
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress(host, p), 3000);
                sb.append(String.format(Locale.US, "✓ %d (%d ms)  ", p, (System.nanoTime() - t0) / 1_000_000));
            } catch (java.io.IOException e) {
                all = false;
                sb.append(String.format(Locale.US, "✗ %d: %s  ", p, e.getMessage()));
            }
        }
        return (all ? getString(R.string.server_ok) : getString(R.string.server_fail)) + "\n" + sb;
    }

    private void addThemeSetting(LinearLayout col) {
        TextView label = new TextView(this);
        label.setText(R.string.theme_label);
        col.addView(label);
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        String[][] modes = {
            { Prefs.THEME_SYSTEM, getString(R.string.theme_system) },
            { Prefs.THEME_DARK, getString(R.string.theme_dark) },
            { Prefs.THEME_LIGHT, getString(R.string.theme_light) },
            { Prefs.THEME_GHIDRA, getString(R.string.theme_ghidra) },
        };
        for (String[] m : modes) {
            android.widget.RadioButton b = new android.widget.RadioButton(this);
            b.setText(m[1]);
            b.setId(View.generateViewId());
            b.setPadding(0, 0, dp(16), 0);
            group.addView(b);
            if (m[0].equals(prefs.themeMode())) {
                b.setChecked(true);
            }
            b.setOnCheckedChangeListener((v, on) -> {
                if (on) {
                    prefs.setThemeMode(m[0]);
                    themeChanged();
                }
            });
        }
        col.addView(group);
        android.widget.CheckBox black = new android.widget.CheckBox(this);
        black.setText(R.string.theme_black);
        black.setChecked(prefs.pureBlack());
        black.setOnCheckedChangeListener((v, on) -> {
            prefs.setPureBlack(on);
            themeChanged();
        });
        col.addView(black);
        TextView note = new TextView(this);
        note.setText(R.string.theme_note);
        note.setAlpha(0.7f);
        note.setPadding(0, 0, 0, dp(12));
        col.addView(note);
    }

    /** Tells a running Ghidra (in the :ghidra process) to re-apply the theme. */
    private void themeChanged() {
        sendBroadcast(new Intent(GhidraActivity.ACTION_THEME_CHANGED).setPackage(getPackageName()));
    }

    private void addScaleSetting(LinearLayout col) {
        TextView label = new TextView(this);
        col.addView(label);
        SeekBar bar = new SeekBar(this);
        // 1.00 .. 3.00 in steps of 0.05
        bar.setMax(40);
        bar.setProgress(Math.round((prefs.scale() - 1f) * 20));
        Runnable show = () -> label.setText(getString(R.string.scale_label,
                String.format(Locale.US, "%.2f", prefs.scale()), prefs.defaultScale()));
        show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    prefs.setScale(1f + p / 20f);
                    show.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(bar);
    }

    private void addHeapSetting(LinearLayout col) {
        TextView label = new TextView(this);
        label.setPadding(0, dp(12), 0, 0);
        col.addView(label);
        SeekBar bar = new SeekBar(this);
        int max = prefs.maxHeapMb();
        bar.setMax((max - 1024) / 256);
        bar.setProgress((prefs.heapMb() - 1024) / 256);
        Runnable show = () -> label.setText(getString(R.string.heap_label, prefs.heapMb()));
        show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    prefs.setHeapMb(1024 + p * 256);
                    show.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(bar);
    }

    private void addJvmArgsSetting(LinearLayout col) {
        TextView label = new TextView(this);
        label.setText(R.string.jvm_args);
        label.setPadding(0, dp(12), 0, 0);
        col.addView(label);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(prefs.extraJvmArgs());
        e.setHint("-Dfont.size.override=14");
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                prefs.setExtraJvmArgs(e.getText().toString());
            }
        });
        col.addView(e);
    }

    private void refresh() {
        boolean runtime = inst.runtimeReady();
        boolean ghidra = inst.ghidraReady();
        StringBuilder sb = new StringBuilder();
        sb.append(runtime ? getString(R.string.runtime_ok) : getString(R.string.runtime_missing));
        sb.append('\n');
        sb.append(ghidra ? getString(R.string.ghidra_ok, inst.ghidraVersion())
                : getString(R.string.ghidra_missing));
        sb.append('\n');
        boolean files = Environment.isExternalStorageManager();
        sb.append(files ? getString(R.string.storage_ok) : getString(R.string.storage_missing));
        status.setText(sb);
        launch.setEnabled(!busy && runtime && ghidra);
        download.setEnabled(!busy);
        importZip.setEnabled(!busy);
        storage.setVisibility(files ? View.GONE : View.VISIBLE);
    }

    // ---- actions --------------------------------------------------------------

    private void launch() {
        if (!prefs.extraJvmArgs().isEmpty() && getCurrentFocus() instanceof EditText) {
            getCurrentFocus().clearFocus();
        }
        startActivity(new Intent(this, GhidraActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    interface Task {
        void run(Installer.Progress p) throws Exception;
    }

    private void runTask(Task t) {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        progress.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            String error = null;
            try {
                t.run((msg, done, total) -> ui.post(() -> {
                    progressText.setText(total > 0
                            ? String.format(Locale.US, "%s  %d%%", msg, done * 100 / total) : msg);
                    progress.setIndeterminate(total <= 0);
                    if (total > 0) {
                        progress.setProgress((int) (done * 1000 / total));
                    }
                }));
            } catch (Exception e) {
                error = e.toString();
            }
            String err = error;
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                progressText.setVisibility(err != null ? View.VISIBLE : View.GONE);
                if (err != null) {
                    progressText.setText(err);
                }
                refresh();
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            runTask(p -> {
                p.update("Copying zip", 0, 0);
                File zip = new File(getCacheDir(), "import.zip");
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    Installer.copy(in, zip);
                }
                inst.installGhidra(zip, p);
                zip.delete();
            });
        }
    }

    /** "Share to" / "Open with": copy the file where Ghidra's import dialog starts. */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        // Debug builds: `am start ... --es installZip /path/in/app/storage.zip`
        // installs Ghidra without the picker (used for on-device testing).
        String debugZip = intent.getStringExtra("installZip");
        if (BuildConfig.DEBUG && debugZip != null) {
            runTask(p -> inst.installGhidra(new File(debugZip), p));
            return;
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("launch", false)) {
            ui.postDelayed(this::launch, 3000);
            return;
        }
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }
        if (uri == null) {
            return;
        }
        Uri u = uri;
        String name = displayName(u);
        runTask(p -> {
            p.update("Copying " + name, 0, 0);
            File f;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                f = inst.importFile(in, name);
            }
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.imported_title)
                    .setMessage(getString(R.string.imported_text, f.getPath()))
                    .setPositiveButton(R.string.launch, (d, w) -> launch())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) {
                    return n;
                }
            }
        } catch (RuntimeException e) {
            // Fall through to the path.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "imported.bin";
    }

    private void showLog() {
        String log = Installer.readText(inst.logFile());
        if (log == null) {
            log = getString(R.string.no_log);
        } else if (log.length() > 60000) {
            log = log.substring(log.length() - 60000);
        }
        TextView t = new TextView(this);
        t.setText(log);
        t.setTextIsSelectable(true);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setMovementMethod(new ScrollingMovementMethod());
        t.setGravity(Gravity.START);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle(R.string.show_log).setView(sv)
                .setPositiveButton(android.R.string.ok, null).show();
    }
}
