package dev.openvm.container;

import android.content.Context;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The app's "init": prepares the guest's /data, derives the classpaths with the guest's own derive_classpath, then starts
 * servicemanager, hwservicemanager, vndservicemanager, installd and zygote64 (--start-system-server) under ONE vmloader
 * supervisor, so they share the emulated binder, the property service and the init-style sockets.
 */
public final class GuestBoot {
    private GuestBoot() {}
    public interface Sink { void line(String s); }

    private static Process proc;
    private static Thread reader;

    public static synchronized boolean running() {
        if (proc == null) return false;
        try { proc.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }
    public static synchronized void stop() { if (proc != null) { proc.destroy(); proc = null; } }
    public static File logFile(Context c) { File d = new File(c.getFilesDir(), "guest"); d.mkdirs(); return new File(d, "boot.log"); }

    static void mk(File root, String... paths) { for (String p : paths) new File(root, p).mkdirs(); }

    /** The directories init/vold/apexd would have created. */
    static void prepareDirs(File root) {
        mk(root, "data/system", "data/system/environ", "data/system_ce/0", "data/system_de/0", "data/misc", "data/misc/user/0", "data/misc/zoneinfo",
            "data/misc/apexdata/com.android.art/dalvik-cache/arm64", "data/misc/profiles/cur/0", "data/misc/profiles/ref", "data/dalvik-cache/arm64",
            "data/app", "data/data", "data/user/0", "data/user_de/0", "data/local/tmp", "data/anr", "data/tombstones", "data/media/0", "data/app-private", "data/property",
            // installd: "Failed to save version to /data/misc/installd/layout_version: No such file or directory ... Could not create directories; exiting."
            "data/misc/installd", "data/misc_ce/0", "data/misc_de/0", "data/misc/profiles/reference", "data/app-staging", "data/resource-cache", "data/ota", "data/ota_package",
            "mnt/user/0", "mnt/asec", "mnt/expand", "storage/emulated", "sdcard", "cache", "dev/socket", "dev/openvm", "dev/pts", "metadata", "tmp",
            "sys/fs/selinux");
    }

    // ---- recent output, for the on-screen boot console (DisplayActivity)
    private static final java.util.ArrayDeque<String> RING = new java.util.ArrayDeque<String>();
    private static volatile String state = "idle";
    private static void push(String s) {
        synchronized (RING) { RING.addLast(s); while (RING.size() > 400) RING.removeFirst(); }
    }
    /** The last n output lines of the current/last boot. */
    public static List<String> tailLines(int n) {
        synchronized (RING) { List<String> all = new ArrayList<String>(RING); return all.subList(Math.max(0, all.size() - n), all.size()); }
    }
    /** One line: idle / booting / exited with code N. */
    public static String state() { return state; }

    private static Map<String, String> readClasspath(File root) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        File f = new File(root, "data/system/environ/classpath");
        if (!f.isFile()) return m;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try { String l; while ((l = r.readLine()) != null) { l = l.trim(); if (l.startsWith("export ")) { String[] p = l.substring(7).split("\\s+", 2); if (p.length == 2) m.put(p[0], p[1]); } } } finally { r.close(); }
        } catch (Throwable ignore) {}
        return m;
    }

    /** Starts the boot on a background thread; every output line goes to the sink and to boot.log. */
    public static synchronized void start(final Context ctx, final Sink userSink) {
        if (running() || (reader != null && reader.isAlive())) { userSink.line("already running"); return; }
        synchronized (RING) { RING.clear(); }
        state = "booting";
        final Sink sink = new Sink() { public void line(String s) { push(s); userSink.line(s); } };
        final Context c = ctx.getApplicationContext();
        reader = new Thread(new Runnable() { public void run() { doBoot(c, sink); } }, "openvm-boot");
        reader.start();
    }

    private static void doBoot(Context c, Sink sink) {
        FileWriter log = null;
        try {
            log = new FileWriter(logFile(c), false);
            File root = GuestRuntime.root(c);
            if (!new File(root, "system/bin/app_process64").exists()) { sink.line("No Android rootfs: import android12-guest-FULL.tar.gz first."); state = "no rootfs imported"; return; }
            if (!GuestRuntime.hasFramework(c)) sink.line("WARNING: /system/framework/framework.jar is missing (core rootfs). zygote will fail; import the FULL rootfs.");
            if (!loaderIsCurrent(c)) {
                sink.line("ERROR: libvmloader.so in this APK is OLD (no fake selinuxfs): servicemanager will abort with 'selinux_status_open(true) >= 0'.");
                sink.line("       The native loader is prebuilt, not built by Gradle. Rebuild it (tools/build_native.sh, needs 'pip install ziglang') and rebuild the APK,");
                sink.line("       or use the GitHub workflow, which now does this.");
                state = "stale libvmloader.so"; return;
            }
            GuestRuntime.exec(c, Arrays.asList("/system/bin/true"), 20);      // runs prepare(): linker config, property area
            prepareDirs(root);
            sink.line("== deriving classpaths (guest derive_classpath)");
            String d = GuestRuntime.exec(c, Arrays.asList("/apex/com.android.sdkext/bin/derive_classpath", "/data/system/environ/classpath"), 60);
            Map<String, String> cp = readClasspath(root);
            sink.line(cp.isEmpty() ? "derive_classpath produced nothing:\n" + tail(d, 1200) : "classpaths: " + cp.keySet());
            if (cp.isEmpty()) sink.line(GuestRuntime.exec(c, Arrays.asList("/system/bin/sh", "-c", "ls -l /data/system/environ /apex/com.android.sdkext/bin /apex/com.android.sdkext/etc/classpaths"), 20));
            Map<String, String> env = new LinkedHashMap<String, String>(cp);
            List<String> loaderArgs = new ArrayList<String>(Arrays.asList("-d", "-N",      // -N: log binder driver activity ([binder] lines), to diagnose service crashes
                "-S", "zygote", "-S", "usap_pool_primary", "-S", "installd",
                "-x", "/system/bin/servicemanager", "-x", "/system/bin/hwservicemanager", "-x", "/system/bin/installd"));
            List<String> cmd = Arrays.asList("/system/bin/app_process64", "-Xzygote", "/system/bin", "--zygote", "--start-system-server");
            sink.line("== starting: servicemanager, hwservicemanager, installd, zygote64");
            ProcessBuilder pb = GuestRuntime.newGuestProcess(c, loaderArgs, cmd, env);
            Process p = pb.start();
            synchronized (GuestBoot.class) { proc = p; }
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String l; long lines = 0;
            while ((l = r.readLine()) != null) { sink.line(l); log.write(l); log.write('\n'); if ((++lines & 31) == 0) log.flush(); }
            int code = p.waitFor();
            sink.line("== boot process exited with code " + code);
            state = "zygote exited with code " + code;
        } catch (Throwable t) {
            sink.line("boot failed: " + t);
            state = "boot failed: " + t;
        } finally {
            try { if (log != null) log.close(); } catch (Throwable ignore) {}
            synchronized (GuestBoot.class) { proc = null; }
        }
    }
    /** True when the bundled loader knows "-V" and reports the selinuxfs feature (older prebuilt loaders print their usage text and exit 2). */
    static boolean loaderIsCurrent(Context c) {
        try {
            Process p = new ProcessBuilder(GuestRuntime.loader(c).getAbsolutePath(), "-V").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String l; while ((l = r.readLine()) != null && sb.length() < 2000) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString().contains("selinuxfs");
        } catch (Throwable t) { return true; }      // cannot tell: do not block the boot
    }
    private static String tail(String s, int n) { return s.length() <= n ? s : s.substring(s.length() - n); }
}
