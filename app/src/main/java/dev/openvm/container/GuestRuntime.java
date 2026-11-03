package dev.openvm.container;

import android.content.Context;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Stage 1 of the real-ROM guest: rootfs storage, archive import, and running programs through libvmloader.so. */
public final class GuestRuntime {
    private GuestRuntime() {}

    private static final String A = "/apex/com.android.art/javalib/";
    public static final String BCP = A + "core-oj.jar:" + A + "core-libart.jar:" + A + "okhttp.jar:" + A + "bouncycastle.jar:" + A + "apache-xml.jar:"
        + "/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar";
    /** Command line that runs the guest's own ART (dalvikvm64) on a jar inside the rootfs. */
    public static String javaCmd(String jar, String mainClass) {
        return "/apex/com.android.art/bin/dalvikvm64 -Xbootclasspath:" + BCP + " -Xmx96m -Xms4m -Xnoimage-dex2oat -Xint -cp " + jar + " " + mainClass;
    }
    private static final String HELLO_JAR_B64 = "UEsDBBQAAAAIAFZDYl3Ztb4XsAIAAAAFAAALABwAY2xhc3Nlcy5kZXhVVAkAA0RJ6GpESehqdXgLAAEEAAAAAAQAAAAAhZTPTxNREMe/7+1uW352KSBSNVnwAh6KPxBjABNINJLUX2iIicZY6FqWtLtluyVw08SjevBP8GJMiPFu4oGrmHiTmx44cDAmnoxe9Pt2t1KBxG0/O/Nm5k3nzXanaK+2njxzFuuvi5npj++fL94bfvHh8Y2Np89u9/WYr0wYQBXA6txoBvF1XweOIbK3kG2SJCkB8Iu7IrIX4/U6b6MasEn5TQLfyQ/ym5ygfYycJxPkIpkhV0iRrJIn5CV5S96RDfKJfCZfyS/Sxpq6SJYcJ0PkFDlHjLi+VtJG2kkH6SRp0kUSRI/P1xLrW6wvhWj/FxnZjdiejnOqy4x1ZW/oO1LllOiH6oFOTckEDlFKZslSarHd+CslMqHUYgkcDWsT6EbUSxHui65GvbOxIsIPMKVHvqqphac0aFX5L9G4TDlPBoVBv05PVmpwrQQG0C6ysgfLZorR7XLXhiZ/7wF+0eQ//J/9ffv8rplkFZSWZPcyImN2yx1tjN43YouHGvoZ1iujeiWy2pEw3+kwh8FO7c3RGfdJjyVimYKwoFtebRKJCcd1ggsYvmyXy571wPcq1tTsLctxa07RtoJF2yrV7VowYC0VVgq5lcokWsPQnFpDzEDkkcyHpnHI/AzJozevvCOON3Ldd9zgZuDbhco4MpG5XHBLI9fml+yF4F8b4xy3NI7+fbbpulMu2v6e8LVaYDOtmIOcy6P7zgGpEoVq1XaLaCvZwXXfq9p+sIb2+Cw5t1CxkW6sVmy/5ngu9ErBcZH0armCv7AYKmGg5tUDJKvqRGVG1epsRirwop9iY5Od0NRdXE2LwXT4/8ajh/qmJlq2tY6m/jdkY3bIpvmhNc2QxnNTc8TA7ixJYHee8EmG+9VMEWa0X71/0oryqzmjxTHqfYAV7Q3fFTPS1Rz7A1BLAQIeAxQAAAAIAFZDYl3Ztb4XsAIAAAAFAAALABgAAAAAAAAAAACkgQAAAABjbGFzc2VzLmRleFVUBQADREnoanV4CwABBAAAAAAEAAAAAFBLBQYAAAAAAQABAFEAAAD1AgAAAAA=";

    /** Small fix-ups the imported tree needs before programs can run. */
    public static void prepare(Context c) {
        try {
            File root = root(c);
            new File(root, "data/local/tmp").mkdirs(); new File(root, "data/dalvik-cache/arm64").mkdirs(); new File(root, "data/dalvik-cache/x86_64").mkdirs(); new File(root, "dev").mkdirs();
            File hello = new File(root, "data/local/tmp/hello.jar");
            if (!hello.exists() && new File(root, "system").exists()) { OutputStream o = new FileOutputStream(hello); try { o.write(android.util.Base64.decode(HELLO_JAR_B64, 0)); } finally { o.close(); } }
            // libart.so links libstatssocket.so, which lives in the statsd APEX; the "core" rootfs omits it, so ship the tiny lib with the app.
            File stat = new File(root, "apex/com.android.os.statsd/lib64/libstatssocket.so");
            if (new File(root, "apex").exists() && !stat.exists()) {
                stat.getParentFile().mkdirs();
                InputStream ai = c.getAssets().open("statsd/libstatssocket.so"); try { copy(ai, stat, false); } finally { ai.close(); }
                stat.setReadable(true, false);
            }
            File ld = new File(root, "linkerconfig/ld.config.txt");       // the guest linker must also serve programs that live under /apex
            if (ld.exists()) {
                String all = new String(readAll(ld), "UTF-8"); boolean edit = false;
                if (!all.contains("dir.legacy = /apex")) {
                    all = "dir.legacy = /apex\n" + all; edit = true;
                    // A "dir." line is useless without its section; add a permissive one when the generated file has none.
                    if (!all.contains("[legacy]")) {
                        all += "\n[legacy]\nnamespace.default.isolated = false\nnamespace.default.search.paths = /system/${LIB}\n"; edit = true;
                    }
                }
                // dalvikvm64 dlopens libart.so by bare name; in the legacy section that only works if the ART/runtime APEX dirs are on the default search path.
                if (!all.contains("#openvm-apex-paths2")) {
                    String add = "#openvm-apex-paths2\nnamespace.default.search.paths += /apex/com.android.art/${LIB}\nnamespace.default.search.paths += /apex/com.android.i18n/${LIB}\n"
                        + "namespace.default.search.paths += /apex/com.android.runtime/${LIB}\nnamespace.default.search.paths += /apex/com.android.conscrypt/${LIB}\nnamespace.default.search.paths += /apex/com.android.os.statsd/${LIB}\nnamespace.default.search.paths += /apex/com.android.runtime/${LIB}/bionic\n";
                    int at = all.indexOf("namespace.default.links = ");
                    if (at < 0) { int lg = all.indexOf("[legacy]"); at = lg < 0 ? -1 : all.indexOf('\n', lg) + 1; }
                    if (at >= 0) { all = all.substring(0, at) + add + all.substring(at); edit = true; }
                }
                if (edit) { OutputStream w = new FileOutputStream(ld); try { w.write(all.getBytes("UTF-8")); } finally { w.close(); } }
                // dalvikvm64 (an APEX binary) first looks for its own /linkerconfig/com.android.art/ld.config.txt and warns when it is missing.
                // Give it the same (already patched) config the linker would otherwise fall back to, so behaviour is identical but the warning is gone.
                File artCfg = new File(root, "linkerconfig/com.android.art/ld.config.txt");
                byte[] want = all.getBytes("UTF-8"); boolean same = false;
                try { same = artCfg.exists() && java.util.Arrays.equals(readAll(artCfg), want); } catch (Throwable ignore) {}
                if (!same) { artCfg.getParentFile().mkdirs(); OutputStream w2 = new FileOutputStream(artCfg); try { w2.write(want); } finally { w2.close(); } artCfg.setReadable(true, false); }
            }
            // System properties: every Android process maps /dev/__properties__ at startup (init's property service normally makes it).
            if (new File(root, "system/build.prop").exists()) {
                File mark = new File(root, "dev/__properties__/.openvm-v4");      // bump when PropArea's property set changes, so existing rootfs get it too
                if (!mark.exists() || !new File(root, "dev/__properties__/property_info").exists()) {
                    int n = PropArea.write(root); mark.createNewFile();
                    CLog.i("guest properties written: " + n);
                }
            }
            // Virtual su: the guest already runs as uid 0 (vmloader -0), so su only has to run its argument.
            File su = new File(root, "system/xbin/su");
            boolean ours = false; try { ours = su.exists() && new String(readAll(su), "UTF-8").contains("# openvm su"); } catch (Throwable ignore) {}
            if (new File(root, "system/bin/sh").exists() && !ours) {     // replaces the GSI's own su, which rejects "su -c cmd"
                su.getParentFile().mkdirs(); su.delete();
                OutputStream o = new FileOutputStream(su);
                try { o.write("#!/system/bin/sh\n# openvm su\n[ \"$1\" = \"-c\" ] && { shift; exec /system/bin/sh -c \"$1\"; }\n[ $# -eq 0 ] && exec /system/bin/sh\nexec \"$@\"\n".getBytes("UTF-8")); } finally { o.close(); }
                su.setExecutable(true, false);
            }
        } catch (Throwable t) { CLog.e("guest prepare failed", t); }
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f); ByteArrayOutputStream o = new ByteArrayOutputStream();
        try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); } finally { in.close(); }
        return o.toByteArray();
    }

    /** When true, the next run prints every failing system call and signal (loader -s). */
    public static volatile boolean trace;
    /** Extra background guest programs for the NEXT exec() only (e.g. servicemanager), and binder driver logging. */
    public static volatile List<String> background;
    public static volatile boolean binderDebug;
    public static volatile List<String> sockets;     // init-style sockets ("name" or "name:dgram") for the NEXT exec()

    public static File root(Context c) { File f = new File(c.getFilesDir(), "guest/rootfs"); f.mkdirs(); return f; }
    public static File loader(Context c) { return new File(c.getApplicationInfo().nativeLibraryDir, "libvmloader.so"); }

    // ---------------------------------------------------------------- run
    /** Runs the command; when a Java VM run fails, appends this app's own Android log so the real reason is visible. */
    public static String exec(Context c, List<String> cmd, int timeoutSec) {
        boolean tr = trace; trace = false;
        long t0 = System.currentTimeMillis();
        trace = tr; String r = execInner(c, cmd, timeoutSec); trace = false;
        long ms = System.currentTimeMillis() - t0;
        String full = r;   // untrimmed output, for the saved log file
        if (tr) {   // keep the start and the last part of a long trace
            String[] ls = r.split("\n");
            if (ls.length > 140) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 20; i++) sb.append(ls[i]).append('\n'); sb.append("... (" + (ls.length - 140) + " lines skipped) ...\n"); for (int i = ls.length - 120; i < ls.length; i++) sb.append(ls[i]).append('\n'); r = sb.toString(); }
        }
        boolean java = !cmd.isEmpty() && cmd.get(0).contains("dalvikvm");
        if (java && !r.contains("[exit 0]")) r += "\n--- guest files ---\n" + javaFiles(c) + "\n--- Android log (this app only) ---\n" + logTail();
        else if (java) r += "\n--- run log ---\n" + runSummary(c, cmd, full, ms, tr) + "--- Android log (this app only, last 40 lines) ---\n" + logTail(40) + "\n";
        saveRunLog(c, cmd, full, ms, tr);
        return r;
    }

    /** The guest's own log never reaches logcat (no logd), so show whether the files ART needs are really there. */
    private static String javaFiles(Context c) {
        File r = root(c); StringBuilder sb = new StringBuilder();
        String[] need = { "apex/com.android.art/bin/dalvikvm64", "apex/com.android.art/lib64/libart.so", "apex/com.android.art/lib64/libartbase.so",
            "apex/com.android.art/javalib/core-oj.jar", "apex/com.android.runtime/lib64/bionic/libc.so", "system/lib64/libc.so", "linkerconfig/ld.config.txt" };
        for (String n : need) { File f = new File(r, n); boolean link = false; try { link = android.system.Os.lstat(f.getPath()) != null; } catch (Throwable ignore) {}
            sb.append(f.exists() ? "ok      " : link ? "BROKEN  " : "MISSING ").append('/').append(n).append('\n'); }
        String ml = missingLibs(r, "libart.so"); sb.append(ml.isEmpty() ? "libart.so dependencies: all present\n" : ml);
        return sb.toString();
    }

    // ---------------------------------------------------------------- dependency check (the guest's own log never reaches logcat)
    private static final String[] LIBDIRS = { "system/lib64", "apex/com.android.art/lib64", "apex/com.android.i18n/lib64", "apex/com.android.runtime/lib64",
        "apex/com.android.runtime/lib64/bionic", "apex/com.android.conscrypt/lib64", "apex/com.android.os.statsd/lib64", "system_ext/lib64", "vendor/lib64", "product/lib64" };

    /** Follows symlinks the way the guest would: absolute targets are relative to the rootfs. */
    static File resolve(File root, File f, int depth) {
        try {
            if (depth > 12) return null;
            android.system.StructStat st = android.system.Os.lstat(f.getPath());
            if ((st.st_mode & 0170000) != 0120000) return f;
            String t = android.system.Os.readlink(f.getPath());
            File n = t.startsWith("/") ? new File(root, t) : new File(f.getParentFile(), t);
            return resolve(root, n, depth + 1);
        } catch (Throwable t) { return null; }
    }

    /** DT_NEEDED names of a 64-bit little-endian ELF file. */
    static List<String> needed(File f) {
        List<String> r = new ArrayList<String>();
        try {
            java.io.RandomAccessFile a = new java.io.RandomAccessFile(f, "r");
            try {
                byte[] eh = new byte[64]; a.readFully(eh);
                if (eh[0] != 0x7f || eh[1] != 'E' || eh[4] != 2) return r;
                ByteBuffer b = ByteBuffer.wrap(eh).order(ByteOrder.LITTLE_ENDIAN);
                long phoff = b.getLong(32); int phsz = b.getShort(54) & 0xffff, phn = b.getShort(56) & 0xffff;
                byte[] ph = new byte[phsz * phn]; a.seek(phoff); a.readFully(ph);
                ByteBuffer p = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN);
                long dynOff = 0, dynSz = 0; long[][] ld = new long[phn][3]; int nl = 0;
                for (int i = 0; i < phn; i++) {
                    int o = i * phsz; int type = p.getInt(o);
                    if (type == 2) { dynOff = p.getLong(o + 8); dynSz = p.getLong(o + 32); }
                    else if (type == 1) { ld[nl][0] = p.getLong(o + 16); ld[nl][1] = p.getLong(o + 8); ld[nl][2] = p.getLong(o + 32); nl++; }
                }
                if (dynSz <= 0 || dynSz > (1 << 20)) return r;
                byte[] dyn = new byte[(int) dynSz]; a.seek(dynOff); a.readFully(dyn);
                ByteBuffer d = ByteBuffer.wrap(dyn).order(ByteOrder.LITTLE_ENDIAN);
                List<Long> names = new ArrayList<Long>(); long str = 0;
                for (int o = 0; o + 16 <= dyn.length; o += 16) { long tag = d.getLong(o), val = d.getLong(o + 8); if (tag == 0) break; if (tag == 1) names.add(val); else if (tag == 5) str = val; }
                long so = -1; for (int i = 0; i < nl; i++) if (str >= ld[i][0] && str < ld[i][0] + ld[i][2]) so = str - ld[i][0] + ld[i][1];
                if (so < 0) return r;
                for (long nm : names) {
                    a.seek(so + nm); StringBuilder sb = new StringBuilder(); int ch; while ((ch = a.read()) > 0 && sb.length() < 200) sb.append((char) ch);
                    r.add(sb.toString());
                }
            } finally { a.close(); }
        } catch (Throwable ignore) {}
        return r;
    }

    /** Walks the shared-library closure of start and returns "missing -> needed by" lines (empty when complete). */
    static String missingLibs(File root, String start) {
        Map<String, String> missing = new LinkedHashMap<String, String>(); List<String> todo = new ArrayList<String>(); List<String> seen = new ArrayList<String>();
        todo.add(start); todo.add("libc.so");
        String by = "(start)"; Map<String, String> parent = new java.util.HashMap<String, String>();
        while (!todo.isEmpty()) {
            String n = todo.remove(todo.size() - 1); if (seen.contains(n)) continue; seen.add(n);
            File found = null;
            for (String dd : LIBDIRS) { File cand = resolve(root, new File(root, dd + "/" + n), 0); if (cand != null && cand.exists()) { found = cand; break; } }
            if (found == null) { missing.put(n, parent.containsKey(n) ? parent.get(n) : by); continue; }
            for (String m : needed(found)) { if (!seen.contains(m)) { todo.add(m); if (!parent.containsKey(m)) parent.put(m, n); } }
        }
        StringBuilder sb = new StringBuilder(); for (Map.Entry<String, String> e : missing.entrySet()) sb.append("MISSING LIB ").append(e.getKey()).append("  (needed by ").append(e.getValue()).append(")\n");
        return sb.toString();
    }

    private static int count(String s, String needle) { int n = 0, i = 0; while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); } return n; }

    /** A few facts about the run, shown under the output of the normal Java test. */
    private static String runSummary(Context c, List<String> cmd, String out, long ms, boolean tr) {
        StringBuilder sb = new StringBuilder();
        sb.append("loader : ").append(loader(c)).append('\n');
        sb.append("rootfs : ").append(root(c)).append('\n');
        sb.append("time   : ").append(ms).append(" ms").append(tr ? " (syscall trace on)" : "").append('\n');
        sb.append("guest-log lines: ").append(count(out, "[guest-log]")).append(", failing syscalls traced: ").append(count(out, "[trace]")).append('\n');
        sb.append("log file: ").append(runLogFile(c)).append('\n');
        return sb.toString();
    }

    public static File runLogFile(Context c) { File d = new File(c.getFilesDir(), "guest"); d.mkdirs(); return new File(d, "last-run.log"); }

    /** Writes the complete output of the last run to guest/last-run.log and a summary to the app log (CLog / logcat tag OpenVM). */
    private static void saveRunLog(Context c, List<String> cmd, String out, long ms, boolean tr) {
        try {
            String head = cmd.isEmpty() ? "" : cmd.get(0);
            CLog.i("guest run: " + head + " exit=" + (out.contains("[exit 0]") ? "0" : "see log") + " " + ms + "ms guest-log=" + count(out, "[guest-log]") + " trace=" + count(out, "[trace]"));
            java.io.FileWriter w = new java.io.FileWriter(runLogFile(c), false);
            try { w.write("$ " + android.text.TextUtils.join(" ", cmd) + "\n" + runSummary(c, cmd, out, ms, tr) + "----\n" + out); } finally { w.close(); }
        } catch (Throwable t) { CLog.e("could not save guest run log", t); }
    }

    /** The core ART/libcore jars, plus the platform framework jars when the rootfs has them (FULL rootfs). */
    public static String bootClassPath(Context c) {
        StringBuilder sb = new StringBuilder(BCP);
        File r = root(c);
        for (String j : new String[] { "framework", "ext", "telephony-common", "voip-common", "ims-common" })
            if (new File(r, "system/framework/" + j + ".jar").exists()) sb.append(":/system/framework/").append(j).append(".jar");
        return sb.toString();
    }
    public static boolean hasFramework(Context c) { return new File(root(c), "system/framework/framework.jar").exists(); }

    /** Copies the static binder self-test (shipped as libbindertest.so) into the guest so it can be run by vmloader. */
    public static String installBinderTest(Context c) {
        try {
            File src = new File(c.getApplicationInfo().nativeLibraryDir, "libbindertest.so");
            if (!src.exists()) return null;
            File dst = new File(root(c), "data/local/tmp/test_binder"); dst.getParentFile().mkdirs();
            java.io.InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst);
            try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); } finally { in.close(); out.close(); }
            dst.setExecutable(true, false); dst.setReadable(true, false);
            return "/data/local/tmp/test_binder";
        } catch (Throwable t) { return null; }
    }

    public static String logTail() { return logTail(120); }
    public static String logTail(int lines) {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", "" + lines, "-v", "brief", "*:W").redirectErrorStream(true).start();
            ByteArrayOutputStream o = new ByteArrayOutputStream(); InputStream in = p.getInputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0 && o.size() < 30000) o.write(b, 0, n);
            p.destroy();
            String t = o.toString("UTF-8").trim();
            return t.isEmpty() ? "(empty)" : t;
        } catch (Throwable t) { return "(could not read log: " + t + ")"; }
    }

    /** Starts a long-running guest process (several programs under ONE supervisor via "-x", init-style sockets via "-S"). */
    public static ProcessBuilder newGuestProcess(Context c, List<String> loaderArgs, List<String> cmd, Map<String, String> extraEnv) {
        prepare(c);
        List<String> full = new ArrayList<String>();
        full.add(loader(c).getAbsolutePath()); full.add("-0"); full.addAll(loaderArgs); full.add("-r"); full.add(root(c).getAbsolutePath()); full.add("--"); full.addAll(cmd);
        ProcessBuilder pb = new ProcessBuilder(full);
        Map<String, String> env = pb.environment(); env.clear();
        env.put("PATH", "/system/bin:/system/xbin:/vendor/bin:/bin:/usr/bin"); env.put("ANDROID_ROOT", "/system"); env.put("ANDROID_DATA", "/data");
        env.put("HOME", "/data"); env.put("TMPDIR", "/data/local/tmp"); env.put("ANDROID_BOOTLOGO", "1"); env.put("ANDROID_ASSETS", "/system/app");
        env.put("ANDROID_STORAGE", "/storage"); env.put("EXTERNAL_STORAGE", "/sdcard"); env.put("ASEC_MOUNTPOINT", "/mnt/asec");
        env.put("ANDROID_ART_ROOT", "/apex/com.android.art"); env.put("ANDROID_I18N_ROOT", "/apex/com.android.i18n");
        env.put("ANDROID_TZDATA_ROOT", "/apex/com.android.tzdata"); env.put("BOOTCLASSPATH", bootClassPath(c));
        env.put("LD_LIBRARY_PATH", "/apex/com.android.art/lib64:/apex/com.android.i18n/lib64:/apex/com.android.runtime/lib64:/apex/com.android.conscrypt/lib64:/apex/com.android.os.statsd/lib64:/system/lib64");
        if (extraEnv != null) env.putAll(extraEnv);
        pb.redirectErrorStream(true);
        return pb;
    }

    /** Copies one of the app's bundled static test programs (lib<name>.so) into the guest at /data/local/tmp/<name>. */
    public static String installTool(Context c, String name) {
        try {
            File src = new File(c.getApplicationInfo().nativeLibraryDir, "lib" + name + ".so"); if (!src.exists()) return null;
            File dst = new File(root(c), "data/local/tmp/" + name); dst.getParentFile().mkdirs();
            java.io.InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst);
            try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); } finally { in.close(); out.close(); }
            dst.setExecutable(true, false); dst.setReadable(true, false); return "/data/local/tmp/" + name;
        } catch (Throwable t) { return null; }
    }

    private static String execInner(Context c, List<String> cmd, int timeoutSec) {
        prepare(c);
        File ld = loader(c);
        if (!ld.exists()) return "libvmloader.so is not in this build for your CPU (" + ld + ")\n";
        List<String> full = new ArrayList<String>();
        List<String> bg = background; background = null; boolean bdbg = binderDebug; binderDebug = false;
        full.add(ld.getAbsolutePath()); if (trace) full.add("-s"); if (bdbg) full.add("-N");
        if (bg != null) for (String b : bg) { full.add("-x"); full.add(b); }
        List<String> so = sockets; sockets = null; if (so != null) for (String b : so) { full.add("-S"); full.add(b); } full.add("-0"); full.add("-d"); full.add("-r"); full.add(root(c).getAbsolutePath()); full.add("--");
        full.addAll(cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(full);
            Map<String, String> env = pb.environment(); env.clear();
            env.put("PATH", "/system/bin:/system/xbin:/vendor/bin:/bin:/usr/bin"); env.put("ANDROID_ROOT", "/system"); env.put("ANDROID_DATA", "/data");
            env.put("HOME", "/data"); env.put("TMPDIR", "/data/local/tmp"); env.put("ANDROID_BOOTLOGO", "1");
            env.put("ANDROID_ART_ROOT", "/apex/com.android.art"); env.put("ANDROID_I18N_ROOT", "/apex/com.android.i18n");
            env.put("ANDROID_TZDATA_ROOT", "/apex/com.android.tzdata"); env.put("BOOTCLASSPATH", bootClassPath(c));
            env.put("LD_LIBRARY_PATH", "/apex/com.android.art/lib64:/apex/com.android.i18n/lib64:/apex/com.android.runtime/lib64:/apex/com.android.conscrypt/lib64:/apex/com.android.os.statsd/lib64:/system/lib64");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            InputStream in = p.getInputStream(); byte[] buf = new byte[8192];
            long end = System.currentTimeMillis() + timeoutSec * 1000L;
            while (System.currentTimeMillis() < end) {
                if (in.available() > 0) { int n = in.read(buf); if (n < 0) break; o.write(buf, 0, n); if (o.size() > 200000) break; }
                else { try { int code = p.exitValue(); while (in.available() > 0) { int n = in.read(buf); if (n < 0) break; o.write(buf, 0, n); } o.write(("\n[exit " + code + "]\n").getBytes("UTF-8")); return o.toString("UTF-8"); }
                       catch (IllegalThreadStateException running) { try { Thread.sleep(40); } catch (InterruptedException ie) { break; } } }
            }
            p.destroy(); try { p.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
            return o.toString("UTF-8") + "\n[stopped: timeout or output limit]\n";
        } catch (Throwable t) { CLog.e("guest exec failed", t); return "Could not start: " + t + "\n"; }
    }

    /** Splits a command line; double or single quotes group words. */
    public static List<String> split(String s) {
        List<String> r = new ArrayList<String>(); StringBuilder cur = new StringBuilder(); char q = 0; boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) { if (ch == q) q = 0; else cur.append(ch); }
            else if (ch == '"' || ch == '\'') { q = ch; any = true; }
            else if (Character.isWhitespace(ch)) { if (cur.length() > 0 || any) { r.add(cur.toString()); cur.setLength(0); any = false; } }
            else cur.append(ch);
        }
        if (cur.length() > 0 || any) r.add(cur.toString());
        return r;
    }

    // ---------------------------------------------------------------- import (.tar / .tar.gz / .zip)
    public static int importArchive(Context c, InputStream raw) throws IOException {
        File root = root(c);
        BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
        in.mark(8); int b0 = in.read(), b1 = in.read(); in.reset();
        if (b0 == 0x1f && b1 == 0x8b) return untar(new GZIPInputStream(in, 1 << 16), root);
        if (b0 == 'P' && b1 == 'K') return unzip(in, root);
        return untar(in, root);
    }

    private static File safe(File root, String name) throws IOException {
        while (name.startsWith("./") || name.startsWith("/")) name = name.startsWith("./") ? name.substring(2) : name.substring(1);
        File f = new File(root, name);
        String rp = root.getCanonicalPath() + File.separator, fp = f.getParentFile() == null ? rp : f.getParentFile().getCanonicalPath() + File.separator;
        if (!fp.startsWith(rp) && !(f.getCanonicalPath() + File.separator).equals(rp)) throw new IOException("unsafe path in archive: " + name);
        return f;
    }

    private static int unzip(InputStream in, File root) throws IOException {
        ZipInputStream z = new ZipInputStream(in); ZipEntry e; int n = 0;
        while ((e = z.getNextEntry()) != null) {
            File f = safe(root, e.getName());
            if (e.isDirectory()) { f.mkdirs(); continue; }
            f.getParentFile().mkdirs(); copy(z, f, false); n++;
            if (e.getName().contains("/bin/") || e.getName().contains("/xbin/")) f.setExecutable(true, false);
        }
        return n;
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0; boolean any = false;
        if ((b[off] & 0x80) != 0) { for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff); return v; }   // base-256
        for (int i = off; i < off + len; i++) { int ch = b[i]; if (ch == 0 || ch == ' ') { if (any) break; else continue; } v = v * 8 + (ch - '0'); any = true; }
        return v;
    }
    private static String cstr(byte[] b, int off, int len) { int e = off; while (e < off + len && b[e] != 0) e++; try { return new String(b, off, e - off, "UTF-8"); } catch (Exception x) { return ""; } }
    private static void skip(InputStream in, long n) throws IOException { while (n > 0) { long k = in.skip(n); if (k <= 0) { if (in.read() < 0) return; k = 1; } n -= k; } }
    private static byte[] readN(InputStream in, int n) throws IOException { byte[] b = new byte[n]; int o = 0; while (o < n) { int k = in.read(b, o, n - o); if (k < 0) throw new IOException("truncated archive"); o += k; } return b; }

    private static int untar(InputStream in, File root) throws IOException {
        byte[] h = new byte[512]; String longName = null, longLink = null; int count = 0;
        for (;;) {
            int o = 0; while (o < 512) { int k = in.read(h, o, 512 - o); if (k < 0) return count; o += k; }
            boolean zero = true; for (int i = 0; i < 512 && zero; i++) if (h[i] != 0) zero = false;
            if (zero) return count;
            String name = cstr(h, 0, 100); String prefix = cstr(h, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(cstr(h, 257, 5))) name = prefix + "/" + name;
            long size = octal(h, 124, 12); int mode = (int) octal(h, 100, 8); char type = (char) h[156]; String link = cstr(h, 157, 100);
            long pad = (512 - size % 512) % 512;
            if (type == 'L' || type == 'K') { String s = cstr(readN(in, (int) size), 0, (int) size); skip(in, pad); if (type == 'L') longName = s; else longLink = s; continue; }
            if (type == 'x') {            // pax header: "len key=value\n" records
                String p = new String(readN(in, (int) size), "UTF-8"); skip(in, pad);
                for (String line : p.split("\n")) { int sp = line.indexOf(' '), eq = line.indexOf('='); if (sp > 0 && eq > sp) { String k = line.substring(sp + 1, eq), v = line.substring(eq + 1); if (k.equals("path")) longName = v; else if (k.equals("linkpath")) longLink = v; } }
                continue;
            }
            if (type == 'g') { skip(in, size + pad); continue; }
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            File f = safe(root, name);
            if (f.getPath().equals(root.getPath())) { skip(in, size + pad); continue; }
            try {
                if (type == '5') { f.mkdirs(); try { Os.chmod(f.getPath(), (mode & 0777) | 0700); } catch (Throwable ignore) {} skip(in, size + pad); }
                else if (type == '2') { f.getParentFile().mkdirs(); f.delete(); Os.symlink(link, f.getPath()); count++; skip(in, size + pad); }
                else if (type == '1') { f.getParentFile().mkdirs(); File src = safe(root, link); copy(new java.io.FileInputStream(src), f, true); try { Os.chmod(f.getPath(), mode & 0777); } catch (Throwable ignore) {} count++; skip(in, size + pad); }
                else if (type == '0' || type == 0 || type == '7') {
                    f.getParentFile().mkdirs(); f.delete();
                    OutputStream os = new FileOutputStream(f);
                    try { byte[] buf = new byte[1 << 16]; long left = size; while (left > 0) { int k = in.read(buf, 0, (int) Math.min(buf.length, left)); if (k < 0) throw new IOException("truncated archive"); os.write(buf, 0, k); left -= k; } } finally { os.close(); }
                    skip(in, pad);
                    try { Os.chmod(f.getPath(), (mode & 0777) | 0400); } catch (Throwable ignore) {}
                    count++;
                } else skip(in, size + pad);      // devices, fifos: not created (no root)
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(name + ": " + e); }
        }
    }

    private static void copy(InputStream in, File f, boolean close) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); } finally { o.close(); if (close) in.close(); }
    }

    public static void deleteTree(File f) {
        try { if (android.system.Os.lstat(f.getPath()).st_mode != 0 && (android.system.Os.lstat(f.getPath()).st_mode & 0170000) == 0120000) { f.delete(); return; } } catch (Throwable ignore) {}
        File[] c = f.listFiles(); if (c != null) for (File x : c) deleteTree(x);
        f.setWritable(true); f.delete();
    }
}
