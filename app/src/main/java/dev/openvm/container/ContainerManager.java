package dev.openvm.container;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs real APKs into OpenVM's private storage and runs them in this process. */
public final class ContainerManager {
    private static ContainerManager I;
    final Context host;
    final File root;
    public final String activePhone;
    private final Map<String, ContainerApp> apps = new LinkedHashMap<String, ContainerApp>();

    private ContainerManager(Context host) {
        this.host = host.getApplicationContext() != null ? host.getApplicationContext() : host;
        File all = new File(this.host.getFilesDir(), "capps"); all.mkdirs();
        CLog.file = new File(all, "container.log");
        this.activePhone = PhoneStore.active(this.host);
        this.root = PhoneStore.dir(this.host, activePhone);
        migrateLegacy(all);
    }

    public static synchronized ContainerManager get(Context c) { if (I == null) I = new ContainerManager(c); return I; }
    static ContainerManager peek() { return I; }

    /** Call once from Application.onCreate. */
    public void init() {
        CLog.i("init: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), abis=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS) + ", 64bit=" + Process.is64Bit());
        Hidden.init();
        ContainerHooks.install(host);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                CLog.e("CRASH on thread " + t.getName(), e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        File[] dirs = root.listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.isDirectory() || !new File(d, "app/base.apk").exists()) continue;
            try { ContainerApp a = load(d); apps.put(a.pkg, a); } catch (Throwable t) { CLog.e("could not load " + d.getName(), t); }
        }
    }

    /** v0.2.0 kept apps directly in capps/<pkg>; move them into the first phone. */
    private void migrateLegacy(File all) {
        File[] ds = all.listFiles();
        if (ds == null) return;
        File first = PhoneStore.dir(host, "default");
        for (File d : ds) {
            if (d.isDirectory() && !d.getName().equals("phones") && new File(d, "app/base.apk").exists() && !new File(first, d.getName()).exists())
                d.renameTo(new File(first, d.getName()));
        }
    }

    public synchronized List<ContainerApp> list() { return new ArrayList<ContainerApp>(apps.values()); }
    synchronized ContainerApp byPackage(String pkg) { return pkg == null ? null : apps.get(pkg); }
    synchronized ContainerApp byLoader(ClassLoader cl) {
        if (cl == null) return null;
        for (ContainerApp a : apps.values()) if (a.cl == cl) return a;
        return null;
    }

    static ComponentName serviceTarget(PluginContext ctx, Intent i) {
        ComponentName c = i.getComponent();
        return c != null && c.getPackageName().equals(ctx.app.pkg) ? c : null;
    }

    // ---------------------------------------------------------------- launch
    public void launch(Context from, String pkg) {
        ContainerApp a = byPackage(pkg);
        if (a == null) throw new IllegalStateException("not installed: " + pkg);
        if (a.launcher == null) throw new IllegalStateException("this app has no launcher activity");
        a.ensureLoaded(host);
        ComponentName c = new ComponentName(a.pkg, a.launcher);
        Intent orig = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c);
        Intent stub = Redirect.wrap(host.getPackageName(), orig, a.activity(a.launcher), c);
        stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(stub);
    }

    // ---------------------------------------------------------------- install / uninstall
    public static final class Result { public ContainerApp app; public final List<String> warnings = new ArrayList<String>(); }

    public synchronized void uninstall(String pkg) {
        ContainerApp a = apps.remove(pkg);
        if (a != null) deleteTree(a.dir);
    }

    /** Installs an .apk, or a bundle (.apks/.xapk/.apkm) that contains a base apk plus splits. */
    public synchronized Result install(File src) throws IOException {
        File tmp = new File(root, ".tmp"); deleteTree(tmp); tmp.mkdirs();
        try {
            List<File> apks = new ArrayList<File>();
            ZipFile z = new ZipFile(src);
            try {
                if (z.getEntry("AndroidManifest.xml") != null) {
                    File f = new File(tmp, "base.apk"); copy(src, f); apks.add(f);
                } else {
                    int k = 0;
                    for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".apk")) continue;
                        File f = new File(tmp, "in" + (k++) + ".apk");
                        InputStream in = z.getInputStream(e); try { copy(in, f); } finally { in.close(); }
                        apks.add(f);
                    }
                    if (apks.isEmpty()) throw new IOException("no .apk inside this file");
                }
            } finally { z.close(); }

            File base = null; String pkg = null; List<File> splits = new ArrayList<File>();
            ManifestParser.Manifest baseM = null;
            for (File f : apks) {
                ManifestParser.Manifest m = manifestOf(f);
                if (m.pkg == null) throw new IOException(f.getName() + ": no package name in manifest");
                if (m.split == null && (base == null || f.length() > base.length())) { if (base != null) splits.add(base); base = f; baseM = m; pkg = m.pkg; }
                else splits.add(f);
            }
            if (base == null) throw new IOException("no base APK found (all files are split APKs)");

            File dir = new File(root, pkg);
            File app = new File(dir, "app");
            Result r = new Result();
            ContainerApp old = apps.remove(pkg);
            deleteTree(app);
            app.mkdirs();
            File baseDst = new File(app, "base.apk"); copy(base, baseDst);
            List<File> all = new ArrayList<File>(); all.add(baseDst);
            int n = 0;
            for (File s : splits) { File d = new File(app, "split_" + (n++) + ".apk"); copy(s, d); all.add(d); }
            for (File f : all) f.setReadOnly();          // Android 14+ refuses writable dex containers
            extractLibs(all, new File(app, "lib"), r);
            File[] spl = new File[all.size() - 1]; String[] splPaths = new String[spl.length];
            StringBuilder dex = new StringBuilder(baseDst.getAbsolutePath());
            for (int i = 1; i < all.size(); i++) { splPaths[i - 1] = all.get(i).getAbsolutePath(); dex.append(':').append(splPaths[i - 1]); }
            new File(dir, "data").mkdirs();
            ContainerApp a = load(dir);
            apps.put(pkg, a);
            r.app = a;
            if (old != null) r.warnings.add("Updated the existing install (its data was kept). Close the app first if it was running.");
            if (a.launcher == null) r.warnings.add("No launcher activity found; this app can't be opened from the list.");
            if (a.pi.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) r.warnings.add("This app needs Android API " + a.pi.applicationInfo.minSdkVersion + "+, you have " + Build.VERSION.SDK_INT + ".");
            return r;
        } finally { deleteTree(tmp); }
    }

    private ContainerApp load(File dir) throws IOException {
        File app = new File(dir, "app");
        File[] files = app.listFiles();
        List<String> splits = new ArrayList<String>();
        if (files != null) { java.util.Arrays.sort(files); for (File f : files) if (f.getName().startsWith("split_") && f.getName().endsWith(".apk")) splits.add(f.getAbsolutePath()); }
        File baseApk = new File(app, "base.apk");
        StringBuilder dex = new StringBuilder(baseApk.getAbsolutePath());
        for (String s : splits) dex.append(':').append(s);

        PackageManager pm = host.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS
                | PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS | PackageManager.GET_CONFIGURATIONS
                | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        PackageInfo pi = pm.getPackageArchiveInfo(baseApk.getAbsolutePath(), flags);
        if (pi == null || pi.applicationInfo == null) throw new IOException("Android could not parse this APK (corrupt, or unsupported)");
        android.content.pm.ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = ai.publicSourceDir = baseApk.getAbsolutePath();
        if (!splits.isEmpty()) { ai.splitSourceDirs = splits.toArray(new String[0]); ai.splitPublicSourceDirs = ai.splitSourceDirs; }
        ai.nativeLibraryDir = new File(app, "lib").getAbsolutePath();
        ai.dataDir = new File(dir, "data").getAbsolutePath();
        ai.deviceProtectedDataDir = ai.dataDir;
        ai.uid = Process.myUid();
        ai.processName = host.getPackageName();
        ai.enabled = true;
        if (pi.activities != null) for (android.content.pm.ActivityInfo x : pi.activities) x.applicationInfo = ai;
        if (pi.services != null) for (android.content.pm.ServiceInfo x : pi.services) x.applicationInfo = ai;
        if (pi.receivers != null) for (android.content.pm.ActivityInfo x : pi.receivers) x.applicationInfo = ai;
        if (pi.providers != null) for (android.content.pm.ProviderInfo x : pi.providers) x.applicationInfo = ai;

        ManifestParser.Manifest m = manifestOf(baseApk);
        String launcher = m.launcher != null ? m.launcher : m.firstActivity;
        if (launcher != null) launcher = ApkImporterNames.full(pi.packageName, launcher);
        Map<String, java.util.Set<String>> acts = new java.util.HashMap<String, java.util.Set<String>>();
        for (Map.Entry<String, java.util.Set<String>> e : m.activityActions.entrySet()) acts.put(ApkImporterNames.full(pi.packageName, e.getKey()), e.getValue());

        ContainerApp a = new ContainerApp(pi.packageName, dir, dex.toString(), pi, launcher, acts);
        try {
            Resources r = pm.getResourcesForApplication(ai);
            CharSequence l = ai.labelRes != 0 ? r.getText(ai.labelRes) : ai.nonLocalizedLabel;
            a.label = l != null ? l.toString() : pi.packageName;
            if (ai.icon != 0) a.icon = r.getDrawable(ai.icon, null);
        } catch (Throwable t) { a.label = pi.packageName; CLog.w(pi.packageName + ": label/icon: " + t); }
        a.origLabel = a.label;
        File ov = new File(dir, "label.txt");
        if (ov.exists()) try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(ov));
            try { String l = br.readLine(); if (l != null && !l.trim().isEmpty()) a.label = l.trim(); } finally { br.close(); }
        } catch (IOException ignore) {}
        return a;
    }

    private static final class ApkImporterNames {
        static String full(String pkg, String n) { return n.startsWith(".") ? pkg + n : (n.indexOf('.') < 0 ? pkg + "." + n : n); }
    }

    private static ManifestParser.Manifest manifestOf(File apk) throws IOException {
        ZipFile z = new ZipFile(apk);
        try {
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) throw new IOException(apk.getName() + " has no AndroidManifest.xml");
            InputStream in = z.getInputStream(e);
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                try { return ManifestParser.parse(o.toByteArray()); } catch (RuntimeException ex) { throw new IOException("bad manifest: " + ex); }
            } finally { in.close(); }
        } finally { z.close(); }
    }

    private static void extractLibs(List<File> apks, File out, Result r) throws IOException {
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        java.util.Set<String> present = new java.util.LinkedHashSet<String>();
        for (File f : apks) {
            ZipFile z = new ZipFile(f);
            try { for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) present.add(n.substring(4, n.indexOf('/', 4)));
            } } finally { z.close(); }
        }
        if (present.isEmpty()) return;
        String chosen = null;
        for (String abi : abis) if (present.contains(abi)) { chosen = abi; break; }
        if (chosen == null) { r.warnings.add("Native libraries only for " + present + " but OpenVM runs as " + java.util.Arrays.toString(abis) + ". Parts using them will crash."); return; }
        out.mkdirs();
        for (File f : apks) {
            ZipFile z = new ZipFile(f);
            try { for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("lib/" + chosen + "/") && e.getName().endsWith(".so")) {
                    InputStream in = z.getInputStream(e);
                    try { copy(in, new File(out, new File(e.getName()).getName())); } finally { in.close(); }
                }
            } } finally { z.close(); }
        }
        if (!present.contains(chosen) || present.size() > 1) CLog.i("native libs: using " + chosen + " of " + present);
    }

    // ---------------------------------------------------------------- app management
    /** Shows a custom name on the home screen. Empty text restores the app's own name. */
    public synchronized void rename(String pkg, String name) throws IOException {
        ContainerApp a = apps.get(pkg); if (a == null) return;
        File f = new File(a.dir, "label.txt");
        if (name == null || name.trim().isEmpty()) { f.delete(); a.label = a.origLabel; return; }
        java.io.FileWriter w = new java.io.FileWriter(f); try { w.write(name.trim()); } finally { w.close(); }
        a.label = name.trim();
    }

    /** Deletes the app's files, databases, caches and preferences but keeps the app installed. */
    public synchronized void clearData(String pkg) {
        ContainerApp a = apps.get(pkg); if (a == null) return;
        File[] cs = a.dataDir.listFiles();
        if (cs != null) for (File c : cs) if (!c.getName().equals("code_cache")) deleteTree(c);
        File sp = new File(host.getDataDir(), "shared_prefs");
        File[] ps = sp.listFiles();
        if (ps != null) for (File p : ps) if (p.getName().startsWith("capp." + pkg + ".")) p.delete();
        CLog.i("cleared data of " + pkg);
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0; File[] c = f.listFiles();
        if (c != null) for (File x : c) n += size(x);
        return n;
    }
    private static String mb(long b) { return b < 1024 * 1024 ? (b / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0); }

    /** Text for the "App info" dialog. */
    public synchronized String info(String pkg) {
        ContainerApp a = apps.get(pkg); if (a == null) return "not installed";
        PackageInfo pi = a.pi;
        StringBuilder sb = new StringBuilder();
        sb.append("Package: ").append(a.pkg);
        sb.append("\nVersion: ").append(pi.versionName).append(" (").append(Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode).append(")");
        sb.append("\nTarget / min SDK: ").append(pi.applicationInfo.targetSdkVersion).append(" / ").append(pi.applicationInfo.minSdkVersion);
        sb.append("\nLauncher: ").append(a.launcher == null ? "none" : a.launcher);
        sb.append("\nActivities: ").append(pi.activities == null ? 0 : pi.activities.length);
        sb.append("  Services: ").append(pi.services == null ? 0 : pi.services.length);
        sb.append("\nPermissions asked: ").append(pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length);
        sb.append("\nApp size: ").append(mb(size(a.appDir)));
        sb.append("\nData size: ").append(mb(size(a.dataDir)));
        File base = new File(a.appDir, "base.apk");
        if (base.exists()) sb.append("\nInstalled: ").append(new java.util.Date(base.lastModified()));
        sb.append("\nNative libs: ").append(a.libDir.exists() ? "yes" : "none");
        sb.append("\nData folder: ").append(a.dataDir.getAbsolutePath());
        return sb.toString();
    }

    // ---------------------------------------------------------------- io
    private static void copy(File a, File b) throws IOException { InputStream in = new java.io.FileInputStream(a); try { copy(in, b); } finally { in.close(); } }
    private static void copy(InputStream in, File b) throws IOException {
        b.getParentFile().mkdirs();
        OutputStream o = new FileOutputStream(b);
        try { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); } finally { o.close(); }
    }
    static void deleteTree(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteTree(x);
        f.setWritable(true); f.delete();
    }
}
