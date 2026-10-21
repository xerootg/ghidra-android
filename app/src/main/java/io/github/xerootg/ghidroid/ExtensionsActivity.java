/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manage Ghidra extensions: see what is installed, add the optional ones that
 * come with Ghidra, install zips, fetch from GitHub, and build extension
 * projects from source on the device.
 */
public final class ExtensionsActivity extends Activity {

    private static final int REQ_ZIP = 1;
    private static final int REQ_TREE = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Installer inst;
    private Extensions ext;

    private LinearLayout content;
    private TextView status;
    private TextView log;
    private volatile boolean busy;
    /** Runs on the UI thread after the current task finishes (e.g. a build after a download). */
    private volatile Runnable then;
    private boolean changed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inst = new Installer(this);
        ext = new Extensions(inst);
        setTitle(R.string.extensions_title);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(32), 0, dp(32), dp(32));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(dp(32), dp(16), dp(32), dp(8));

        // Build output first, where it is seen without scrolling.
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        log.setTextIsSelectable(true);
        log.setVisibility(View.GONE);
        log.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                log.setVisibility(e.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
        col.addView(log);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        col.addView(content);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        // Status stays visible above the scrolling list.
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(status);
        page.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ---- listing ----------------------------------------------------------------

    private void refresh() {
        content.removeAllViews();
        if (!inst.ghidraReady()) {
            status.setText(R.string.not_installed);
            return;
        }
        StringBuilder sb = new StringBuilder(getString(R.string.extensions_for, inst.ghidraVersion()));
        if (changed) {
            sb.append("\n").append(getString(ghidraRunning(this)
                    ? R.string.extensions_restart_running : R.string.extensions_restart));
        }
        status.setText(sb);
        if (changed && ghidraRunning(this)) {
            button(content, getString(R.string.extensions_restart_button), v -> {
                sendBroadcast(new Intent(GhidraActivity.ACTION_EXIT).setPackage(getPackageName()));
                status.setText(R.string.extensions_exiting);
            });
        }

        heading(R.string.extensions_add);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        button(row, getString(R.string.extensions_install_zip), v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            startActivityForResult(i, REQ_ZIP);
        });
        button(row, getString(R.string.extensions_github), v -> askGitHub());
        content.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        button(row2, getString(R.string.extensions_build_folder), v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE));
        button(row2, getString(R.string.extensions_new), v -> askNewProject());
        content.addView(row2);

        List<File> projects = projects();
        if (!projects.isEmpty()) {
            heading(R.string.extensions_projects);
            note(getString(R.string.extensions_projects_note, projectsDir()));
            for (File p : projects) {
                item(p.getName(), p.getPath(), null,
                        getString(R.string.extensions_build), v -> buildAndInstall(p, false));
            }
        }

        heading(R.string.extensions_installed);
        List<Extensions.Info> installed = ext.installed();
        if (installed.isEmpty()) {
            note(getString(R.string.extensions_none));
        }
        for (Extensions.Info i : installed) {
            String warn = ext.versionMatches(i) ? null
                    : getString(R.string.extensions_mismatch, i.version, inst.ghidraVersion());
            item(i.name + "  " + i.version, i.description, warn, getString(R.string.extensions_remove), v ->
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.extensions_remove_confirm, i.name))
                            .setPositiveButton(R.string.extensions_remove, (d, w) -> {
                                ext.remove(i);
                                changed = true;
                                refresh();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show());
        }

        heading(R.string.extensions_bundled);
        for (Extensions.Info i : ext.bundled()) {
            if (ext.isInstalled(i.name)) {
                continue;
            }
            item(i.name, i.description, null, getString(R.string.extensions_install),
                    v -> runTask(getString(R.string.extensions_installing, i.name), () -> install(i.location)));
        }
    }

    private void heading(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setPadding(0, dp(24), 0, dp(8));
        content.addView(t);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setAlpha(0.7f);
        content.addView(t);
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), dp(8), 0);
        parent.addView(b, lp);
        return b;
    }

    private void item(String title, String detail, String warning, String action, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.addView(t);
        if (detail != null && !detail.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(detail);
            d.setAlpha(0.7f);
            text.addView(d);
        }
        if (warning != null) {
            TextView w = new TextView(this);
            w.setText(warning);
            w.setTextColor(0xFFE8A33C);
            text.addView(w);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        b.setText(action);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        row.addView(b);
        content.addView(row);
    }

    // ---- actions -------------------------------------------------------------------

    interface Task {
        void run() throws Exception;
    }

    private void runTask(String what, Task t) {
        if (busy) {
            return;
        }
        busy = true;
        status.setText(what);
        worker.execute(() -> {
            String err = null;
            try {
                t.run();
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String error = err;
            ui.post(() -> {
                busy = false;
                Runnable next = then;
                then = null;
                refresh();
                if (error != null) {
                    status.append("\n" + getString(R.string.extensions_error, error));
                } else if (next != null) {
                    next.run();
                }
            });
        });
    }

    /** Installs a zip, asking about a version mismatch first. Runs on the worker. */
    private void install(File zip) throws IOException {
        Extensions.Info info = Extensions.describeZip(zip);
        if (info == null) {
            throw new IOException(getString(R.string.extensions_not_extension));
        }
        if (info.version.equals(inst.ghidraVersion()) || info.version.contains("@")) {
            ext.install(zip, info.version.contains("@"));
            changed = true;
            return;
        }
        ui.post(() -> new AlertDialog.Builder(this)
                .setTitle(info.name)
                .setMessage(getString(R.string.extensions_mismatch_ask, info.version, inst.ghidraVersion()))
                .setPositiveButton(R.string.extensions_install_anyway, (d, w) ->
                        runTask(getString(R.string.extensions_installing, info.name), () -> {
                            ext.install(zip, true);
                            changed = true;
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void askGitHub() {
        EditText e = new EditText(this);
        e.setHint("owner/repo");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle(R.string.extensions_github)
                .setMessage(R.string.extensions_github_note)
                .setView(e)
                .setPositiveButton(R.string.extensions_get, (d, w) -> fromGitHub(e.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fromGitHub(String spec) {
        runTask(getString(R.string.extensions_github_looking, spec), () -> {
            Extensions.GitHubSource src = ext.resolveGitHub(spec);
            File work = ext.workDir();
            File zip = new File(work, "download.zip");
            ui.post(() -> status.setText(getString(R.string.extensions_downloading, src.label)));
            Extensions.download(src.url, zip);
            if (src.isRelease) {
                install(zip);
                return;
            }
            File project = ext.unpackSource(zip, new File(work, "github"));
            then = () -> buildAndInstall(project, true);
        });
    }

    /** Builds a project in the :tools process, then installs the result. */
    private void buildAndInstall(File project, boolean fetchDeps) {
        if (busy) {
            return;
        }
        busy = true;
        File out = new File(ext.workDir(), "out/" + project.getName() + ".zip");
        out.delete();
        status.setText(getString(R.string.extensions_building, project.getName()));
        log.setText("");
        File logFile = ToolsService.build(this, project, out, fetchDeps);
        long started = System.currentTimeMillis();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                String text = Installer.readText(logFile);
                if (text != null) {
                    log.setText(text);
                }
                Integer rc = ToolsService.result(logFile);
                boolean dead = rc == null && System.currentTimeMillis() - started > 15000
                        && !processRunning(ExtensionsActivity.this, ":tools");
                if (rc == null && !dead) {
                    ui.postDelayed(this, 500);
                    return;
                }
                busy = false;
                if (rc == null || rc != 0) {
                    // Nothing from the builder: the JVM itself failed; show its output.
                    String jvm = Installer.readText(new File(logFile.getParentFile(), "tools-jvm.log"));
                    if ((text == null || text.isEmpty()) && jvm != null) {
                        log.setText(jvm.length() > 4000 ? jvm.substring(jvm.length() - 4000) : jvm);
                    }
                }
                if (rc != null && rc == 0 && out.isFile()) {
                    runTask(getString(R.string.extensions_installing, project.getName()), () -> install(out));
                } else {
                    refresh();
                    status.append("\n" + getString(R.string.extensions_build_failed));
                }
            }
        }, 500);
    }

    private void askNewProject() {
        EditText e = new EditText(this);
        e.setHint("MyExtension");
        e.setSingleLine(true);
        String msg = getString(R.string.extensions_new_note, projectsDir());
        if (!Environment.isExternalStorageManager()) {
            msg += "\n\n" + getString(R.string.extensions_new_private);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.extensions_new)
                .setMessage(msg)
                .setView(e)
                .setPositiveButton(R.string.extensions_create, (d, w) -> {
                    String name = e.getText().toString().trim().replaceAll("[^A-Za-z0-9_\\-]", "");
                    if (name.isEmpty()) {
                        return;
                    }
                    runTask(getString(R.string.extensions_creating, name), () -> {
                        File dir = new File(projectsDir(), name);
                        if (dir.exists()) {
                            throw new IOException(getString(R.string.extensions_exists, dir));
                        }
                        Extensions.copyDir(ext.skeletonDir(), dir);
                        new File(dir, "buildTemplate.gradle").renameTo(new File(dir, "build.gradle"));
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Extension projects you edit with any editor: Documents/Ghidra extensions
     * on shared storage when the app may use it, otherwise inside the app.
     */
    private File projectsDir() {
        File dir = Environment.isExternalStorageManager()
                ? new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "Ghidra extensions")
                : new File(ext.workDir(), "projects");
        dir.mkdirs();
        return dir;
    }

    private List<File> projects() {
        List<File> out = new java.util.ArrayList<>();
        File[] kids = projectsDir().listFiles(File::isDirectory);
        if (kids != null) {
            for (File k : kids) {
                if (new File(k, "extension.properties").isFile()) {
                    out.add(k);
                }
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_ZIP) {
            runTask(getString(R.string.extensions_installing, ""), () -> {
                File zip = new File(ext.workDir(), "picked.zip");
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    Installer.copy(in, zip);
                }
                install(zip);
            });
        } else if (requestCode == REQ_TREE) {
            runTask(getString(R.string.extensions_copying), () -> {
                File dest = new File(ext.workDir(), "picked-src");
                Installer.deleteTree(dest);
                copyTree(uri, DocumentsContract.getTreeDocumentId(uri), dest);
                File project = Extensions.findProject(dest, 2);
                if (project == null) {
                    throw new IOException(getString(R.string.extensions_no_props));
                }
                then = () -> buildAndInstall(project, true);
            });
        }
    }

    /** Copies a Storage Access Framework folder into app storage. */
    private void copyTree(Uri tree, String docId, File dest) throws IOException {
        dest.mkdirs();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE };
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                if (name.equals("build") || name.equals(".git") || name.equals(".gradle")) {
                    continue;
                }
                File f = new File(dest, name.replace('/', '_'));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    copyTree(tree, id, f);
                } else {
                    try (InputStream in = getContentResolver().openInputStream(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id))) {
                        Installer.copy(in, f);
                    }
                }
            }
        }
    }

    /** An extension zip shared or opened from another app. */
    private void handleIntent(Intent intent) {
        String path = intent != null ? intent.getStringExtra("zip") : null;
        if (path != null) {
            File zip = new File(path);
            runTask(getString(R.string.extensions_installing, zip.getName()), () -> install(zip));
        }
    }

    static boolean ghidraRunning(Context ctx) {
        return processRunning(ctx, ":ghidra");
    }

    static boolean processRunning(Context ctx, String suffix) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) {
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.processName.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
