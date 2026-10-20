/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Lays out the runtime under the app's files directory:
 *
 * <pre>
 * files/jre/                  Android JRE 21 (from assets/jre.zip), plus the xawt stub
 * files/toolkit.jar           AWT toolkit, patched into java.desktop
 * files/fonts/, fontconfig.properties
 * files/ghidra/               the Ghidra release, minus other platforms' binaries
 * files/home/                 user.home: Ghidra settings and projects
 * </pre>
 */
final class Installer {

    interface Progress {
        void update(String message, long done, long total);
    }

    /** Executables built for Android, by where Ghidra looks for them. */
    private static final String[][] EXECUTABLES = {
        { "Ghidra/Features/Decompiler/os/linux_arm_64/decompile", "libdecompile.so" },
        { "Ghidra/Features/Decompiler/os/linux_arm_64/sleigh", "libsleigh.so" },
        { "GPL/DemanglerGnu/os/linux_arm_64/demangler_gnu_v2_41", "libdemangler_gnu_v2_41.so" },
        { "GPL/DemanglerGnu/os/linux_arm_64/demangler_gnu_v2_24", "libdemangler_gnu_v2_24.so" },
        { "Ghidra/Features/FileFormats/os/linux_arm_64/lzfse", "liblzfse.so" },
    };

    /** Release entries that are useless on Android: docs and other platforms' binaries. */
    private static final Pattern SKIP = Pattern.compile(
            "^[^/]+/(docs/.*|.*/os/(?!linux_arm_64/)[^/]+/.*|Extensions/Eclipse/.*|server/.*)$");

    private final Context ctx;
    final File files;
    final File jre;
    final File ghidra;
    final File home;
    final File tmp;
    final File imports;

    Installer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        files = this.ctx.getFilesDir();
        jre = new File(files, "jre");
        ghidra = new File(files, "ghidra");
        home = new File(files, "home");
        tmp = new File(this.ctx.getCacheDir(), "tmp");
        imports = new File(home, "imports");
    }

    File toolkitJar() {
        return new File(files, "toolkit.jar");
    }

    /** javac and the ToolProvider patch (for Ghidra scripts). */
    File javacDir() {
        return new File(files, "javac");
    }

    File fontsDir() {
        return new File(files, "fonts");
    }

    File fontConfig() {
        return new File(files, "fontconfig.properties");
    }

    File logFile() {
        return new File(files, "ghidra.log");
    }

    String nativeLibDir() {
        return ctx.getApplicationInfo().nativeLibraryDir;
    }

    // ---- runtime (from the APK) -------------------------------------------

    private String apkStamp() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.getLongVersionCode() + "-" + pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean runtimeReady() {
        return apkStamp().equals(readText(new File(files, "runtime.stamp")));
    }

    /** Unpacks the JRE, toolkit and fonts if this APK has not done so yet. */
    void installRuntime(Progress p) throws IOException {
        if (runtimeReady()) {
            return;
        }
        p.update("Unpacking Java runtime", 0, 0);
        deleteTree(jre);
        File zip = new File(ctx.getCacheDir(), "jre.zip");
        try (InputStream in = ctx.getAssets().open("jre.zip")) {
            copy(in, zip);
        }
        unzip(zip, files, null, p, "Unpacking Java runtime");
        zip.delete();
        File lib = new File(jre, "lib");
        copy(new File(nativeLibDir(), "libawt_xawt.so"), new File(lib, "libawt_xawt.so"));
        for (File f : listTree(jre)) {
            if (f.getName().endsWith(".so") || f.getParentFile().getName().equals("bin")) {
                f.setExecutable(true, false);
            }
        }

        p.update("Installing toolkit and fonts", 0, 0);
        try (InputStream in = ctx.getAssets().open("toolkit.jar")) {
            copy(in, toolkitJar());
        }
        File javac = javacDir();
        deleteTree(javac);
        javac.mkdirs();
        for (String name : ctx.getAssets().list("javac")) {
            try (InputStream in = ctx.getAssets().open("javac/" + name)) {
                copy(in, new File(javac, name));
            }
        }
        File fonts = fontsDir();
        fonts.mkdirs();
        for (String name : ctx.getAssets().list("fonts")) {
            try (InputStream in = ctx.getAssets().open("fonts/" + name)) {
                copy(in, new File(fonts, name));
            }
        }
        String map;
        try (InputStream in = ctx.getAssets().open("fontconfig.properties.in")) {
            map = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        writeText(fontConfig(), map.replace("@FONTS@", fonts.getAbsolutePath()));
        if (ghidraReady()) {
            linkExecutables();
            freshenSla();
        }
        writeText(new File(files, "runtime.stamp"), apkStamp());
    }

    // ---- Ghidra (downloaded or imported) -----------------------------------

    boolean ghidraReady() {
        return new File(ghidra, "Ghidra/Framework/Utility/lib/Utility.jar").isFile()
                && new File(ghidra, "Ghidra/application.properties").isFile();
    }

    /** Version of the installed Ghidra, from application.properties. */
    String ghidraVersion() {
        String props = readText(new File(ghidra, "Ghidra/application.properties"));
        if (props == null) {
            return null;
        }
        for (String line : props.split("\n")) {
            if (line.startsWith("application.version=")) {
                return line.substring("application.version=".length()).trim();
            }
        }
        return null;
    }

    static String releaseUrl(String version) {
        return "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/Ghidra_"
                + version + "_build";
    }

    /** Downloads the official release zip; returns the file. */
    File download(String version, Progress p) throws IOException {
        p.update("Looking up Ghidra " + version, 0, 0);
        String json = httpText(releaseUrl(version));
        java.util.regex.Matcher m = Pattern.compile(
                "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.zip)\"").matcher(json);
        if (!m.find()) {
            throw new IOException("no zip in release " + version);
        }
        String url = m.group(1);
        // GitHub publishes a sha256 digest for each asset.
        java.util.regex.Matcher d = Pattern.compile(
                "\"digest\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").matcher(json);
        String sha = d.find() ? d.group(1) : null;

        File out = new File(ctx.getCacheDir(), "ghidra-" + version + ".zip");
        File part = new File(out.getPath() + ".part");
        HttpURLConnection c = open(url);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            MessageDigest md = sha256();
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                p.update("Downloading Ghidra " + version, done, total);
            }
            if (sha != null && !sha.equals(hex(md.digest()))) {
                part.delete();
                throw new IOException("checksum mismatch for " + url);
            }
        }
        if (!part.renameTo(out)) {
            throw new IOException("rename " + part);
        }
        return out;
    }

    /** Installs a Ghidra release zip (the official PUBLIC zip layout). */
    void installGhidra(File zip, Progress p) throws IOException {
        File stage = new File(files, "ghidra.new");
        deleteTree(stage);
        stage.mkdirs();
        unzip(zip, stage, SKIP, p, "Installing Ghidra");
        File[] top = stage.listFiles();
        if (top == null || top.length != 1 || !new File(top[0], "Ghidra/application.properties").isFile()) {
            deleteTree(stage);
            throw new IOException("not a Ghidra release zip");
        }
        File old = new File(files, "ghidra.old");
        deleteTree(old);
        if (ghidra.exists() && !ghidra.renameTo(old)) {
            throw new IOException("rename " + ghidra);
        }
        if (!top[0].renameTo(ghidra)) {
            throw new IOException("rename " + top[0]);
        }
        deleteTree(stage);
        deleteTree(old);
        linkExecutables();
        writeText(new File(ghidra, ".sla-fresh"), "");
    }

    /**
     * Points Ghidra's linux_arm_64 executables at the copies in the native
     * library directory, the only place this app may exec() from.
     */
    void linkExecutables() throws IOException {
        for (String[] e : EXECUTABLES) {
            File link = new File(ghidra, e[0]);
            link.getParentFile().mkdirs();
            link.delete();
            try {
                Os.symlink(new File(nativeLibDir(), e[1]).getAbsolutePath(), link.getAbsolutePath());
            } catch (ErrnoException ex) {
                throw new IOException("symlink " + link, ex);
            }
        }
    }

    // ---- Ghidra theme ---------------------------------------------------------

    static final String THEME_DARK_FILE = "android-dark.theme";
    static final String THEME_BLACK_FILE = "android-black.theme";
    static final String THEME_LIGHT_CLASS = "generic.theme.builtin.FlatLightTheme";

    /** Ghidra's per-version settings directory, ~/.config/ghidra/ghidra_VERSION_RELEASE. */
    File ghidraSettingsDir() {
        String props = readText(new File(ghidra, "Ghidra/application.properties"));
        String version = ghidraVersion();
        String release = "PUBLIC";
        if (props != null) {
            for (String line : props.split("\n")) {
                if (line.startsWith("application.release.name=")) {
                    release = line.substring("application.release.name=".length()).trim();
                }
            }
        }
        return new File(home, ".config/ghidra/ghidra_" + version + "_" + release);
    }

    File themeFile(String name) {
        return new File(new File(ghidraSettingsDir(), "themes"), name);
    }

    /**
     * Puts the app's themes where Ghidra's theme chooser finds them, and when
     * {@code locator} is non-null, makes it the theme Ghidra starts with.
     * Must run before the JVM starts: Ghidra rewrites the file on exit.
     */
    void prepareThemes(String locator) throws IOException {
        prepareGhidraPrefs(locator, null);
    }

    /**
     * Installs the app's themes and updates Ghidra's preferences before it
     * starts: the theme (if {@code themeLocator} is non-null) and the most
     * recent server, which the shared-project dialogs offer first (if
     * {@code server} is non-empty, as host:port).
     */
    void prepareGhidraPrefs(String themeLocator, String server) throws IOException {
        String locator = themeLocator;
        File dir = new File(ghidraSettingsDir(), "themes");
        dir.mkdirs();
        for (String name : ctx.getAssets().list("themes")) {
            try (InputStream in = ctx.getAssets().open("themes/" + name)) {
                copy(in, new File(dir, name));
            }
        }
        boolean hasServer = server != null && !server.isEmpty();
        if (locator == null && !hasServer) {
            return;
        }
        File prefs = new File(ghidraSettingsDir(), "preferences");
        java.util.Properties p = new java.util.Properties();
        if (prefs.isFile()) {
            try (InputStream in = new FileInputStream(prefs)) {
                p.load(in);
            }
        }
        if (locator != null) {
            p.setProperty("Theme", locator);
        }
        if (hasServer) {
            p.setProperty("ServerInfo", server.contains(":") ? server : server + ":13100");
        }
        try (OutputStream out = new FileOutputStream(prefs)) {
            p.store(out, "User Preferences");
        }
    }

    // ---- project ownership -------------------------------------------------

    /** Android's name for this app's user (u0_aNNN), Java's default user.name. */
    static String androidUserName() {
        // Same rule as bionic's getpwuid for app uids: u<user>_a<appId - 10000>.
        int uid = android.os.Process.myUid();
        return "u" + (uid / 100000) + "_a" + (uid % 100000 - 10000);
    }

    private static final Pattern OWNER = Pattern.compile(
            "(<STATE NAME=\"OWNER\" TYPE=\"string\" VALUE=\")([^\"]*)(\" />)");

    /**
     * Local Ghidra projects only open for the user that owns them. When the
     * user name changes, hand the projects owned by any of {@code from} to
     * {@code to}, so they keep opening. Projects under home, two levels deep.
     */
    int adoptProjects(java.util.Set<String> from, String to) {
        int n = 0;
        java.util.ArrayDeque<File> todo = new java.util.ArrayDeque<>();
        todo.add(home);
        while (!todo.isEmpty()) {
            File dir = todo.pop();
            File[] kids = dir.listFiles();
            if (kids == null) {
                continue;
            }
            for (File k : kids) {
                if (!k.isDirectory() || Files.isSymbolicLink(k.toPath())) {
                    continue;
                }
                if (k.getName().endsWith(".rep")) {
                    if (reown(new File(k, "project.prp"), from, to)) {
                        n++;
                    }
                } else if (!k.getName().startsWith(".")
                        && home.toPath().relativize(k.toPath()).getNameCount() < 2) {
                    todo.add(k);
                }
            }
        }
        return n;
    }

    private static boolean reown(File prp, java.util.Set<String> from, String to) {
        String xml = readText(prp);
        if (xml == null) {
            return false;
        }
        java.util.regex.Matcher m = OWNER.matcher(xml);
        if (!m.find() || !from.contains(m.group(2)) || m.group(2).equals(to)) {
            return false;
        }
        try {
            writeText(prp, m.replaceFirst("$1" + java.util.regex.Matcher.quoteReplacement(to) + "$3") + "\n");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Installs made before timestamps were kept have .slaspec files newer
     * than the shipped .sla, so Ghidra recompiles every language on first
     * use (minutes on a tablet). The shipped .sla are current; mark them so.
     */
    void freshenSla() {
        File marker = new File(ghidra, ".sla-fresh");
        if (marker.exists()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : listTree(new File(ghidra, "Ghidra/Processors"))) {
            if (f.getName().endsWith(".sla")) {
                f.setLastModified(now);
            }
        }
        try {
            writeText(marker, "");
        } catch (IOException e) {
            // Retried next time.
        }
    }

    /** Copies a shared/opened file into the Ghidra home so it can be imported. */
    File importFile(InputStream in, String name) throws IOException {
        imports.mkdirs();
        String safe = name.replaceAll("[/\\\\:\\x00]", "_");
        File f = new File(imports, safe);
        copy(in, f);
        return f;
    }

    // ---- helpers ------------------------------------------------------------

    private static void unzip(File zip, File dest, Pattern skip, Progress p, String what)
            throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            long total = z.size();
            long done = 0;
            byte[] buf = new byte[1 << 16];
            String destPath = dest.getCanonicalPath() + File.separator;
            for (java.util.Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                done++;
                if (skip != null && skip.matcher(e.getName()).matches()) {
                    continue;
                }
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(destPath)) {
                    throw new IOException("bad zip entry " + e.getName());
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
                // Keep the release's timestamps: Ghidra recompiles a
                // language's .sla when its .slaspec looks newer.
                if (e.getTime() > 0) {
                    f.setLastModified(e.getTime());
                }
                if ((done & 63) == 0) {
                    p.update(what, done, total);
                }
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "ghidra-android");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() / 100 != 2) {
            throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        }
        return c;
    }

    private static String httpText(String url) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    static void copy(InputStream in, File out) throws IOException {
        out.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
        }
    }

    static void copy(File in, File out) throws IOException {
        try (InputStream i = new FileInputStream(in)) {
            copy(i, out);
        }
    }

    static String readText(File f) {
        if (!f.isFile()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    static void writeText(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static java.util.List<File> listTree(File root) {
        java.util.List<File> out = new java.util.ArrayList<>();
        java.util.ArrayDeque<File> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            File d = todo.pop();
            File[] kids = d.listFiles();
            if (kids == null) {
                continue;
            }
            for (File k : kids) {
                if (Files.isSymbolicLink(k.toPath())) {
                    out.add(k);
                } else if (k.isDirectory()) {
                    todo.add(k);
                } else {
                    out.add(k);
                }
            }
        }
        return out;
    }

    static void deleteTree(File f) {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) {
            return;
        }
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
    }
}
