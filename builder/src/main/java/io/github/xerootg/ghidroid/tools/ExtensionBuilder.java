/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Builds a Ghidra extension from source without Gradle, the way Ghidra's
 * {@code support/buildExtension.gradle} does: compile {@code src/main/java}
 * against Ghidra's jars and {@code lib/*.jar}, put the classes and
 * resources in {@code lib/NAME.jar}, and zip that with the rest of the
 * project (extension.properties, Module.manifest, data/, ghidra_scripts/, ...).
 *
 * <pre>
 * ExtensionBuilder GHIDRA_INSTALL_DIR PROJECT_DIR OUT_ZIP [--fetch-deps]
 * </pre>
 *
 * With {@code --fetch-deps}, direct Maven dependencies written as
 * {@code implementation 'group:artifact:version'} in build.gradle are
 * downloaded into lib/ from Maven Central (not transitively). Progress goes to
 * stdout; the exit status is 0 on success.
 */
public final class ExtensionBuilder {

    private static final Pattern GRADLE_DEP = Pattern.compile(
            "(?:implementation|api|compile|runtimeOnly)\\s*\\(?\\s*['\"]([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)['\"]");

    private final File ghidra;
    private final File project;
    private final File out;
    private final boolean fetchDeps;

    private ExtensionBuilder(File ghidra, File project, File out, boolean fetchDeps) {
        this.ghidra = ghidra;
        this.project = project.getAbsoluteFile();
        this.out = out;
        this.fetchDeps = fetchDeps;
    }

    /**
     * Options after the three paths: {@code --fetch-deps}; {@code --log FILE}
     * (output goes there, flushed per line); {@code --rc FILE} (the exit
     * status is written there just before exiting, for callers that cannot
     * see it, like the Android app).
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("usage: ExtensionBuilder GHIDRA_INSTALL_DIR PROJECT_DIR OUT_ZIP"
                    + " [--fetch-deps] [--log FILE] [--rc FILE]");
            System.exit(2);
        }
        boolean fetch = false;
        File rc = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--fetch-deps" -> fetch = true;
                case "--log" -> {
                    java.io.PrintStream ps = new java.io.PrintStream(
                            new FileOutputStream(args[++i]), true, StandardCharsets.UTF_8);
                    System.setOut(ps);
                    System.setErr(ps);
                }
                case "--rc" -> rc = new File(args[++i]);
                default -> System.out.println("ignoring " + args[i]);
            }
        }
        int status;
        try {
            new ExtensionBuilder(new File(args[0]), new File(args[1]), new File(args[2]), fetch).build();
            System.out.println("BUILD SUCCESSFUL");
            status = 0;
        } catch (BuildException e) {
            System.out.println("BUILD FAILED: " + e.getMessage());
            status = 1;
        } catch (Exception e) {
            e.printStackTrace(System.out);
            System.out.println("BUILD FAILED: " + e);
            status = 1;
        }
        System.out.flush();
        if (rc != null) {
            Files.writeString(rc.toPath(), Integer.toString(status));
        }
        System.exit(status);
    }

    static final class BuildException extends Exception {
        BuildException(String m) {
            super(m);
        }
    }

    private void build() throws Exception {
        File props = new File(project, "extension.properties");
        if (!props.isFile()) {
            throw new BuildException("no extension.properties in " + project
                    + " (is this the extension's root folder?)");
        }
        Properties app = load(new File(ghidra, "Ghidra/application.properties"));
        String ghidraVersion = app.getProperty("application.version");
        String javaLevel = app.getProperty("application.java.compiler", "21");
        String name = extensionName(load(props));
        System.out.println("Extension " + name + " for Ghidra " + ghidraVersion);

        if (hasKotlin()) {
            throw new BuildException("Kotlin sources are not supported; only Java");
        }
        File lib = new File(project, "lib");
        if (fetchDeps) {
            fetchGradleDependencies(lib);
        } else {
            warnAboutGradleDependencies(lib);
        }

        File work = Files.createTempDirectory("ghidroid-build").toFile();
        try {
            File classes = new File(work, "classes");
            classes.mkdirs();
            List<File> sources = listFiles(new File(project, "src/main/java"), ".java");
            if (sources.isEmpty()) {
                System.out.println("No Java sources; packaging data and scripts only");
            } else {
                compile(sources, classes, classpath(lib), javaLevel);
            }
            copyTree(new File(project, "src/main/resources"), classes);
            copyTree(new File(project, "src/main/help"), classes);

            File jar = new File(work, name + ".jar");
            if (!sources.isEmpty() || classes.list().length > 0) {
                zipDir(classes, jar, "");
            }
            File srcZip = new File(work, name + "-src.zip");
            if (!sources.isEmpty()) {
                zipDir(new File(project, "src/main/java"), srcZip, "src/main/java/");
            }
            assemble(name, ghidraVersion, props, jar, srcZip);
        } finally {
            deleteTree(work);
        }
        System.out.println("Wrote " + out);
    }

    private static final Pattern ROOT_NAME = Pattern.compile(
            "rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");
    /** GitHub source archives unpack to owner-repo-shortsha. */
    private static final Pattern ZIPBALL_DIR = Pattern.compile("^[\\w.\\-]+?-([\\w.\\-]+)-[0-9a-f]{7,40}$");

    /**
     * The name Gradle would give it: extension.properties' name unless it is
     * still the @extname@ token, then settings.gradle's rootProject.name, then
     * the folder name (a GitHub archive's owner-repo-sha folder counts as the
     * repo; a generic folder like "extension" takes its parent's name).
     */
    private String extensionName(Properties p) throws IOException {
        String n = p.getProperty("name", "").trim();
        if (!n.isEmpty() && !n.contains("@")) {
            return n;
        }
        File settings = new File(project, "settings.gradle");
        if (settings.isFile()) {
            Matcher m = ROOT_NAME.matcher(Files.readString(settings.toPath(), StandardCharsets.UTF_8));
            if (m.find()) {
                return m.group(1);
            }
        }
        File dir = project;
        String name = dir.getName();
        if (name.matches("(?i)extension|ghidra|plugin|src|ghidra_extension|ghidra-extension")
                && dir.getParentFile() != null) {
            dir = dir.getParentFile();
            name = dir.getName();
        }
        Matcher z = ZIPBALL_DIR.matcher(name);
        return z.matches() ? z.group(1) : name;
    }

    private boolean hasKotlin() throws IOException {
        return !listFiles(new File(project, "src/main"), ".kt").isEmpty();
    }

    private List<File> classpath(File lib) throws IOException {
        List<File> cp = new ArrayList<>();
        cp.addAll(listFiles(lib, ".jar"));
        for (String dir : new String[] { "Framework", "Features", "Debug", "Processors" }) {
            cp.addAll(listFiles(new File(ghidra, "Ghidra/" + dir), ".jar"));
        }
        return cp;
    }

    private void compile(List<File> sources, File classes, List<File> classpath, String level)
            throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new BuildException("no Java compiler available");
        }
        System.out.println("Compiling " + sources.size() + " source files against "
                + classpath.size() + " jars");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.US, StandardCharsets.UTF_8);
        List<String> opts = new ArrayList<>();
        opts.add("-d");
        opts.add(classes.getPath());
        opts.add("-classpath");
        StringBuilder sb = new StringBuilder();
        for (File f : classpath) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparatorChar);
            }
            sb.append(f.getPath());
        }
        opts.add(sb.toString());
        opts.add("-source");
        opts.add(level);
        opts.add("-target");
        opts.add(level);
        opts.add("-encoding");
        opts.add("UTF-8");
        opts.add("-proc:none");
        opts.add("-g");
        opts.add("-nowarn");
        StringWriter extra = new StringWriter();
        boolean ok = javac.getTask(extra, fm, diags, opts, null,
                fm.getJavaFileObjectsFromFiles(sources)).call();
        int errors = 0;
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors++;
                String where = d.getSource() != null
                        ? project.toPath().relativize(Path.of(d.getSource().toUri())) + ":" + d.getLineNumber()
                        : "";
                System.out.println("error: " + where + ": " + d.getMessage(Locale.US));
            }
        }
        if (extra.getBuffer().length() > 0) {
            System.out.println(extra);
        }
        fm.close();
        if (!ok) {
            throw new BuildException(errors + " compile error(s)");
        }
    }

    /** The extension zip, laid out like buildExtension.gradle's. */
    private void assemble(String name, String version, File props, File jar, File srcZip) throws IOException {
        out.getParentFile().mkdirs();
        String root = name + "/";
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            String p = Files.readString(props.toPath(), StandardCharsets.UTF_8)
                    .replace("@extversion@", version).replace("@extname@", name);
            // Ghidra refuses extensions built for another version.
            p = p.replaceAll("(?m)^version\\s*=.*$", "version=" + version);
            put(z, root + "extension.properties", p.getBytes(StandardCharsets.UTF_8));
            if (!new File(project, "Module.manifest").isFile()) {
                put(z, root + "Module.manifest", new byte[0]);
            }
            if (jar.isFile()) {
                put(z, root + "lib/" + name + ".jar", Files.readAllBytes(jar.toPath()));
            }
            if (srcZip.isFile()) {
                put(z, root + "lib/" + name + "-src.zip", Files.readAllBytes(srcZip.toPath()));
            }
            Path base = project.toPath();
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }
                    String rel = base.relativize(f).toString().replace(File.separatorChar, '/');
                    if (excluded(rel) || rel.equals("extension.properties")
                            || rel.equals("lib/" + name + ".jar") || rel.equals("lib/" + name + "-src.zip")) {
                        continue;
                    }
                    put(z, root + rel, Files.readAllBytes(f));
                }
            }
        }
    }

    private static boolean excluded(String rel) {
        String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        switch (top) {
            case "build": case "dist": case "bin": case "src": case ".gradle": case ".vscode":
            case ".settings": case ".git": case "developer_scripts": case "gradle": case ".idea":
                return true;
            default:
                break;
        }
        return rel.endsWith(".gradle") || rel.equals("certification.manifest") || rel.equals(".classpath")
                || rel.equals(".project") || rel.equals(".pydevproject") || rel.equals(".antProperties.xml")
                || rel.equals("gradlew") || rel.equals("gradlew.bat") || rel.equals(".gitignore")
                || rel.equals("gradle.properties");
    }

    // ---- dependencies ------------------------------------------------------------

    private List<String[]> gradleDependencies() throws IOException {
        List<String[]> deps = new ArrayList<>();
        File gradle = new File(project, "build.gradle");
        if (!gradle.isFile()) {
            return deps;
        }
        Matcher m = GRADLE_DEP.matcher(Files.readString(gradle.toPath(), StandardCharsets.UTF_8));
        while (m.find()) {
            deps.add(new String[] { m.group(1), m.group(2), m.group(3) });
        }
        return deps;
    }

    private void warnAboutGradleDependencies(File lib) throws IOException {
        for (String[] d : gradleDependencies()) {
            File jar = new File(lib, d[1] + "-" + d[2] + ".jar");
            if (!jar.isFile()) {
                System.out.println("warning: build.gradle depends on " + String.join(":", d)
                        + ", which is not in lib/ (build with dependency download, or add the jar)");
            }
        }
    }

    private void fetchGradleDependencies(File lib) throws IOException {
        for (String[] d : gradleDependencies()) {
            File jar = new File(lib, d[1] + "-" + d[2] + ".jar");
            if (jar.isFile()) {
                continue;
            }
            String url = "https://repo1.maven.org/maven2/" + d[0].replace('.', '/') + "/" + d[1] + "/" + d[2]
                    + "/" + d[1] + "-" + d[2] + ".jar";
            System.out.println("Downloading " + String.join(":", d));
            lib.mkdirs();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            if (c.getResponseCode() != 200) {
                System.out.println("warning: " + url + ": HTTP " + c.getResponseCode());
                continue;
            }
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(jar)) {
                in.transferTo(o);
            }
        }
    }

    // ---- files -----------------------------------------------------------------

    private static Properties load(File f) throws IOException {
        Properties p = new Properties();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            p.load(r);
        }
        return p;
    }

    private static List<File> listFiles(File dir, String suffix) throws IOException {
        List<File> out = new ArrayList<>();
        if (!dir.isDirectory()) {
            return out;
        }
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(suffix))
                    .sorted().forEach(p -> out.add(p.toFile()));
        }
        return out;
    }

    private static void copyTree(File from, File to) throws IOException {
        if (!from.isDirectory()) {
            return;
        }
        Path base = from.toPath();
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    Path dest = to.toPath().resolve(base.relativize(p).toString());
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void zipDir(File dir, File zip, String prefix) throws IOException {
        Path base = dir.toPath();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip));
                Stream<Path> s = Files.walk(base)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    put(z, prefix + base.relativize(p).toString().replace(File.separatorChar, '/'),
                            Files.readAllBytes(p));
                }
            }
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
