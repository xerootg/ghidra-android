/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ghidra extensions: the ones installed for this user (Ghidra's
 * {@code <settings>/Extensions}), the optional ones bundled with the release
 * ({@code <ghidra>/Extensions/Ghidra/*.zip}), installing zips, and building
 * from source in the {@code :tools} process ({@link ToolsService}).
 */
final class Extensions {

    /** One extension, installed or available. */
    static final class Info {
        final String name;
        final String version;
        final String description;
        final File location; // installed dir, or bundled zip

        Info(String name, String version, String description, File location) {
            this.name = name;
            this.version = version;
            this.description = description;
            this.location = location;
        }
    }

    private final Installer inst;

    Extensions(Installer inst) {
        this.inst = inst;
    }

    File userDir() {
        return new File(inst.ghidraSettingsDir(), "Extensions");
    }

    File bundledDir() {
        return new File(inst.ghidra, "Extensions/Ghidra");
    }

    /** Where the template copy and builds live. */
    File workDir() {
        return new File(inst.files, "extension-work");
    }

    File skeletonDir() {
        return new File(inst.ghidra, "Extensions/Ghidra/Skeleton");
    }

    List<Info> installed() {
        List<Info> out = new ArrayList<>();
        File[] dirs = userDir().listFiles();
        if (dirs == null) {
            return out;
        }
        for (File d : dirs) {
            File props = new File(d, "extension.properties");
            if (d.isDirectory() && props.isFile()) {
                Properties p = load(props);
                out.add(new Info(p.getProperty("name", d.getName()), p.getProperty("version", "?"),
                        p.getProperty("description", ""), d));
            }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    List<Info> bundled() {
        List<Info> out = new ArrayList<>();
        File[] zips = bundledDir().listFiles((dir, n) -> n.endsWith(".zip"));
        if (zips == null) {
            return out;
        }
        for (File z : zips) {
            try {
                Info i = describeZip(z);
                if (i != null) {
                    out.add(i);
                }
            } catch (IOException e) {
                // Not an extension zip.
            }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    boolean isInstalled(String name) {
        return new File(userDir(), name).isDirectory();
    }

    /** Reads NAME/extension.properties from an extension zip; null if it is not one. */
    static Info describeZip(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String[] parts = e.getName().split("/");
                if (parts.length == 2 && parts[1].equals("extension.properties")) {
                    Properties p = new Properties();
                    try (InputStream in = z.getInputStream(e)) {
                        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                    }
                    return new Info(p.getProperty("name", parts[0]), p.getProperty("version", "?"),
                            p.getProperty("description", ""), zip);
                }
            }
        }
        return null;
    }

    /**
     * Installs an extension zip into the user extension directory, replacing
     * an older copy. With {@code forceVersion}, extension.properties gets this
     * Ghidra's version (extensions built for a nearby version often work, but
     * Ghidra refuses to load a mismatch).
     */
    Info install(File zip, boolean forceVersion) throws IOException {
        Info info = describeZip(zip);
        if (info == null) {
            throw new IOException("not a Ghidra extension (no NAME/extension.properties)");
        }
        File top;
        try (ZipFile z = new ZipFile(zip)) {
            String root = null;
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (n.endsWith("/extension.properties") && n.split("/").length == 2) {
                    root = n.substring(0, n.indexOf('/'));
                }
            }
            File dest = new File(userDir(), info.name);
            File stage = new File(userDir(), "." + info.name + ".new");
            Installer.deleteTree(stage);
            stage.mkdirs();
            String stagePath = stage.getCanonicalPath() + File.separator;
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.getName().startsWith(root + "/")) {
                    continue;
                }
                String rel = e.getName().substring(root.length() + 1);
                if (rel.isEmpty()) {
                    continue;
                }
                File f = new File(stage, rel);
                if (!f.getCanonicalPath().startsWith(stagePath)) {
                    throw new IOException("bad zip entry " + e.getName());
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(f)) {
                    in.transferTo(o);
                }
            }
            if (forceVersion) {
                File props = new File(stage, "extension.properties");
                String text = Installer.readText(props);
                Installer.writeText(props, text.replaceAll("(?m)^version\\s*=.*$",
                        "version=" + inst.ghidraVersion()) + "\n");
            }
            Installer.deleteTree(dest);
            if (!stage.renameTo(dest)) {
                throw new IOException("rename " + stage);
            }
            top = dest;
        }
        return new Info(info.name, forceVersion ? inst.ghidraVersion() : info.version, info.description, top);
    }

    void remove(Info i) {
        Installer.deleteTree(i.location);
    }

    boolean versionMatches(Info i) {
        return i.version.equals(inst.ghidraVersion());
    }

    // ---- GitHub ---------------------------------------------------------------

    /** What a GitHub repository offers: a release zip for this Ghidra, or a source zip. */
    static final class GitHubSource {
        final String url;
        final boolean isRelease;
        final String label;

        GitHubSource(String url, boolean isRelease, String label) {
            this.url = url;
            this.isRelease = isRelease;
            this.label = label;
        }
    }

    private static final Pattern REPO = Pattern.compile(
            "(?:https?://github\\.com/)?([\\w.\\-]+)/([\\w.\\-]+?)(?:\\.git)?/?$");

    /**
     * Looks for a release asset built for this Ghidra version (most extension
     * projects name their zips ghidra_VERSION_PUBLIC_..._Name.zip); otherwise
     * the default branch's source zip, to build here.
     */
    GitHubSource resolveGitHub(String spec) throws IOException {
        Matcher m = REPO.matcher(spec.trim());
        if (!m.find()) {
            throw new IOException("expected owner/repo or a github.com URL");
        }
        String repo = m.group(1) + "/" + m.group(2);
        String version = inst.ghidraVersion();
        try {
            String json = http("https://api.github.com/repos/" + repo + "/releases?per_page=20");
            Matcher a = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.zip)\"").matcher(json);
            while (a.find()) {
                String url = a.group(1);
                String file = url.substring(url.lastIndexOf('/') + 1);
                if (file.contains("ghidra_" + version + "_") || file.contains("Ghidra_" + version + "_")
                        || file.contains("_" + version + "_") || file.contains("-" + version + ".zip")
                        || file.contains("_" + version + ".zip")) {
                    return new GitHubSource(url, true, file);
                }
            }
        } catch (IOException e) {
            // No releases; build from source.
        }
        return new GitHubSource("https://api.github.com/repos/" + repo + "/zipball", false,
                repo + " (source)");
    }

    static String http(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void download(String url, File out) throws IOException {
        HttpURLConnection c = open(url);
        out.getParentFile().mkdirs();
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            in.transferTo(o);
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ghidra-android");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() / 100 != 2) {
            throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        }
        return c;
    }

    /** Unzips a source archive and returns the folder that holds extension.properties. */
    File unpackSource(File zip, File dest) throws IOException {
        Installer.deleteTree(dest);
        dest.mkdirs();
        String destPath = dest.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(destPath)) {
                    throw new IOException("bad zip entry " + e.getName());
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(f)) {
                        in.transferTo(o);
                    }
                }
            }
        }
        File found = findProject(dest, 3);
        if (found == null) {
            throw new IOException("no extension.properties in the source");
        }
        return found;
    }

    /** The shallowest folder with an extension.properties (repos often nest the extension). */
    static File findProject(File dir, int depth) {
        if (new File(dir, "extension.properties").isFile()) {
            return dir;
        }
        if (depth == 0) {
            return null;
        }
        File[] kids = dir.listFiles(File::isDirectory);
        if (kids == null) {
            return null;
        }
        for (File k : kids) {
            File f = findProject(k, depth - 1);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    static void copyDir(File from, File to) throws IOException {
        to.mkdirs();
        File[] kids = from.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            File d = new File(to, k.getName());
            if (k.isDirectory()) {
                copyDir(k, d);
            } else {
                try (InputStream in = new FileInputStream(k)) {
                    Installer.copy(in, d);
                }
            }
        }
    }

    private static Properties load(File f) {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Empty.
        }
        return p;
    }
}
