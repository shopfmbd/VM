package dev.openvm.container;

import android.app.Application;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One installed real Android app living inside OpenVM. */
public final class ContainerApp {
    public final String pkg;
    public String label, origLabel;
    public Drawable icon;
    final File dir, appDir, libDir, dataDir;
    final String dexPath;
    final PackageInfo pi;
    final ApplicationInfo ai;
    final String launcher;
    final Map<String, Set<String>> actions;

    ClassLoader cl;
    Resources res;
    Application app;
    PluginContext baseCtx;
    private boolean loaded, loading;
    final PluginServices services = new PluginServices(this);

    ContainerApp(String pkg, File dir, String dexPath, PackageInfo pi, String launcher, Map<String, Set<String>> actions) {
        this.pkg = pkg; this.dir = dir; this.appDir = new File(dir, "app"); this.libDir = new File(appDir, "lib"); this.dataDir = new File(dir, "data");
        this.dexPath = dexPath; this.pi = pi; this.ai = pi.applicationInfo; this.launcher = launcher; this.actions = actions;
    }

    public String launcherActivity() { return launcher; }

    public ActivityInfo activity(String cls) {
        if (pi.activities != null) for (ActivityInfo a : pi.activities) if (a.name.equals(cls)) return a;
        return null;
    }

    PackageInfo packageInfo() { pi.applicationInfo = ai; return pi; }

    int themeRes() { return ai.theme != 0 ? ai.theme : android.R.style.Theme_DeviceDefault; }

    /** Finds a plugin activity that declares this action (implicit intents between the app's own screens). */
    String activityForAction(String action) {
        if (action == null || pi.activities == null) return null;
        for (ActivityInfo a : pi.activities) {
            Set<String> s = actions.get(a.name);
            if (s != null && s.contains(action)) return a.name;
        }
        return null;
    }

    /** Creates class loader, resources, providers and the Application object (main thread). */
    synchronized void ensureLoaded(final Context host) {
        if (loaded || loading) return;
        loading = true;
        try {
            File cc = new File(dataDir, "code_cache"); cc.mkdirs();
            cl = new DexClassLoader(dexPath, cc.getAbsolutePath(), libDir.getAbsolutePath(), Context.class.getClassLoader());
            res = host.getPackageManager().getResourcesForApplication(ai);
            baseCtx = new PluginContext(host.getApplicationContext(), this);
            String cn = ai.className != null ? ai.className : "android.app.Application";
            android.app.Instrumentation in = ContainerHooks.realInstrumentation();
            app = in.newApplication(cl, cn, baseCtx);
            CLog.i(pkg + ": application " + cn + " created");
            installProviders();
            loaded = true;
            in.callApplicationOnCreate(app);
            CLog.i(pkg + ": Application.onCreate done");
        } catch (Throwable t) {
            CLog.e(pkg + ": failed to start application", t);
            loaded = true;
            if (app == null) throw new RuntimeException("Could not start " + pkg + ": " + t, t);
        } finally { loading = false; }
    }

    private void installProviders() {
        if (pi.providers == null) return;
        List<ProviderInfo> list = new ArrayList<ProviderInfo>(Arrays.asList(pi.providers));
        Collections.sort(list, new Comparator<ProviderInfo>() { public int compare(ProviderInfo a, ProviderInfo b) { return b.initOrder - a.initOrder; } });
        for (ProviderInfo p : list) {
            try {
                p.applicationInfo = ai;
                android.content.ContentProvider cp = (android.content.ContentProvider) cl.loadClass(p.name).newInstance();
                cp.attachInfo(baseCtx, p);
                CLog.i(pkg + ": provider " + p.name + " ready");
            } catch (Throwable t) { CLog.w(pkg + ": provider " + p.name + " skipped: " + t); }
        }
    }
}
