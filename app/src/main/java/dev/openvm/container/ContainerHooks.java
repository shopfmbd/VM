package dev.openvm.container;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.Process;
import android.view.ContextThemeWrapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** The two hooks that make real apps run: Instrumentation (launch/redirect activities) and IPackageManager (answer for container packages). */
final class ContainerHooks {
    private ContainerHooks() {}
    private static Instrumentation real;

    static Instrumentation realInstrumentation() { return real != null ? real : new Instrumentation(); }

    static void install(Context host) {
        try { installInstrumentation(); CLog.i("hook: Instrumentation ok"); } catch (Throwable t) { CLog.e("hook: Instrumentation FAILED", t); }
        try { installPackageManager(host); CLog.i("hook: PackageManager ok"); } catch (Throwable t) { CLog.e("hook: PackageManager FAILED", t); }
    }

    // ---------------------------------------------------------------- Instrumentation
    private static void installInstrumentation() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = Hidden.call(null, at, "currentActivityThread", new Class<?>[0]);
        Instrumentation cur = (Instrumentation) Hidden.get(thread, at, "mInstrumentation");
        if (cur instanceof ProxyInstrumentation) { real = ((ProxyInstrumentation) cur).orig; return; }
        real = cur;
        Hidden.set(thread, at, "mInstrumentation", new ProxyInstrumentation(cur));
    }

    /** If the intent targets a container app, returns the stub-wrapped intent; otherwise the intent unchanged. */
    static Intent redirect(Context who, Intent intent) {
        try {
            if (intent == null || Redirect.isWrapped(intent)) return intent;
            ContainerManager mgr = ContainerManager.peek();
            if (mgr == null) return intent;
            ComponentName c = intent.getComponent();
            ContainerApp target = null;
            if (c != null) target = mgr.byPackage(c.getPackageName());
            else if (intent.getAction() != null) {
                ContainerApp caller = who == null ? null : mgr.byLoader(who.getClassLoader());
                if (caller != null) { String cls = caller.activityForAction(intent.getAction()); if (cls != null) { target = caller; c = new ComponentName(caller.pkg, cls); } }
            }
            if (target == null || c == null) return intent;
            target.ensureLoaded(mgr.host);
            ActivityInfo info = target.activity(c.getClassName());
            return Redirect.wrap(mgr.host.getPackageName(), intent, info, c);
        } catch (Throwable t) { CLog.e("redirect failed", t); return intent; }
    }

    static final class ProxyInstrumentation extends Instrumentation {
        final Instrumentation orig;
        ProxyInstrumentation(Instrumentation orig) { this.orig = orig; }

        @Override public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            if (Redirect.isWrapped(intent)) {
                String pkg = intent.getStringExtra(Redirect.K_PKG), cls = intent.getStringExtra(Redirect.K_CLS);
                ContainerManager mgr = ContainerManager.peek();
                ContainerApp app = mgr == null ? null : mgr.byPackage(pkg);
                if (app != null) {
                    try { app.ensureLoaded(mgr.host); }
                    catch (Throwable t) { StubBase.lastError = "Application failed to start:\n" + t; return new StubBase(); }
                    Intent o = Redirect.unwrap(intent, app.cl);
                    Redirect.copyInto(intent, o);
                    CLog.i("launch " + pkg + "/" + cls);
                    try { return (Activity) app.cl.loadClass(cls).newInstance(); }
                    catch (Throwable t) {
                        CLog.e("could not create " + cls, t);
                        StubBase.lastError = "Could not create " + cls + ":\n" + t;
                        return new StubBase();
                    }
                }
                CLog.w("launch: container app " + pkg + " is gone");
            }
            return super.newActivity(cl, className, intent);
        }

        private void prepare(Activity a, Bundle state) {
            ContainerManager mgr = ContainerManager.peek();
            ContainerApp app = mgr == null ? null : mgr.byLoader(a.getClass().getClassLoader());
            if (app == null) return;
            try {
                if (state != null) state.setClassLoader(app.cl);
                Context oldBase = a.getBaseContext();
                if (!(oldBase instanceof PluginContext)) Hidden.set(a, android.content.ContextWrapper.class, "mBase", new PluginContext(oldBase, app));
                Hidden.set(a, ContextThemeWrapper.class, "mTheme", null);
                Hidden.set(a, ContextThemeWrapper.class, "mResources", null);
                try { Hidden.set(a, ContextThemeWrapper.class, "mInflater", null); } catch (Throwable ignore) {}
                Hidden.set(a, Activity.class, "mApplication", app.app);
                ActivityInfo info = null;
                ComponentName comp = a.getIntent() == null ? null : a.getIntent().getComponent();
                if (comp != null) info = app.activity(comp.getClassName());
                a.setTheme(info != null && info.theme != 0 ? info.theme : app.themeRes());
                CharSequence label = null;
                if (info != null && info.labelRes != 0) try { label = app.res.getText(info.labelRes); } catch (Throwable ignore) {}
                if (label == null) label = app.label;
                if (label != null) { a.setTitle(label); a.setTaskDescription(new android.app.ActivityManager.TaskDescription(label.toString())); }
                if (info != null) {
                    if (info.screenOrientation != -1) try { a.setRequestedOrientation(info.screenOrientation); } catch (Throwable ignore) {}
                    if (info.softInputMode != 0) a.getWindow().setSoftInputMode(info.softInputMode);
                }
            } catch (Throwable t) { CLog.e("activity setup failed for " + a.getClass().getName(), t); }
        }

        @Override public void callActivityOnCreate(Activity a, Bundle b) { prepare(a, b); super.callActivityOnCreate(a, b); }
        @Override public void callActivityOnCreate(Activity a, Bundle b, PersistableBundle p) { prepare(a, b); super.callActivityOnCreate(a, b, p); }
        @Override public void callActivityOnRestoreInstanceState(Activity a, Bundle b) {
            ContainerManager mgr = ContainerManager.peek(); ContainerApp app = mgr == null ? null : mgr.byLoader(a.getClass().getClassLoader());
            if (app != null && b != null) b.setClassLoader(app.cl);
            super.callActivityOnRestoreInstanceState(a, b);
        }
        @Override public void callActivityOnNewIntent(Activity a, Intent intent) {
            if (Redirect.isWrapped(intent)) {
                ContainerManager mgr = ContainerManager.peek(); ContainerApp app = mgr == null ? null : mgr.byLoader(a.getClass().getClassLoader());
                if (app != null) intent = Redirect.unwrap(intent, app.cl);
            }
            super.callActivityOnNewIntent(a, intent);
        }

        // Hidden in the SDK jar, but this is the exact signature the framework calls; no @Override so it compiles against the public API.
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token, Activity target, Intent intent, int requestCode, Bundle options) {
            return (ActivityResult) forward(new Class<?>[]{Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class, int.class, Bundle.class},
                    who, thread, token, target, redirect(who, intent), requestCode, options);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token, String target, Intent intent, int requestCode, Bundle options) {
            return (ActivityResult) forward(new Class<?>[]{Context.class, IBinder.class, IBinder.class, String.class, Intent.class, int.class, Bundle.class},
                    who, thread, token, target, redirect(who, intent), requestCode, options);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token, android.app.Fragment target, Intent intent, int requestCode, Bundle options) {
            return (ActivityResult) forward(new Class<?>[]{Context.class, IBinder.class, IBinder.class, android.app.Fragment.class, Intent.class, int.class, Bundle.class},
                    who, thread, token, target, redirect(who, intent), requestCode, options);
        }

        private Object forward(Class<?>[] sig, Object... args) {
            try {
                Method m = Instrumentation.class.getDeclaredMethod("execStartActivity", sig);
                m.setAccessible(true);
                return m.invoke(orig, args);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException) throw (RuntimeException) c;
                if (c instanceof Error) throw (Error) c;
                throw new RuntimeException(c);
            } catch (Exception e) { throw new RuntimeException("execStartActivity forward failed", e); }
        }
    }

    // ---------------------------------------------------------------- IPackageManager
    private static void installPackageManager(Context host) throws Exception {
        final Class<?> iface = Class.forName("android.content.pm.IPackageManager");
        Class<?> at = Class.forName("android.app.ActivityThread");
        final Object realPm = Hidden.call(null, at, "getPackageManager", new Class<?>[0]);
        if (Proxy.isProxyClass(realPm.getClass())) return;
        Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                try {
                    Object r = answer(m, a);
                    if (r != NOT_HANDLED) return r;
                    return m.invoke(realPm, a);
                } catch (InvocationTargetException e) { throw e.getCause(); }
            }
        });
        Hidden.set(null, at, "sPackageManager", proxy);
        PackageManager pm = host.getPackageManager();
        Hidden.set(pm, pm.getClass(), "mPM", proxy);
    }

    private static final Object NOT_HANDLED = new Object();

    private static Object answer(Method m, Object[] a) {
        if (a == null || a.length == 0) return NOT_HANDLED;
        ContainerManager mgr = ContainerManager.peek();
        if (mgr == null) return NOT_HANDLED;
        String n = m.getName();
        if (a[0] instanceof String) {
            ContainerApp app = mgr.byPackage((String) a[0]);
            if (app == null) return NOT_HANDLED;
            if (n.equals("getPackageInfo")) return app.packageInfo();
            if (n.equals("getApplicationInfo")) return app.ai;
            if (n.equals("isPackageAvailable")) return Boolean.TRUE;
            if (n.equals("getPackageUid")) return Process.myUid();
        } else if (a[0] instanceof ComponentName) {
            ComponentName c = (ComponentName) a[0];
            ContainerApp app = mgr.byPackage(c.getPackageName());
            if (app == null) return NOT_HANDLED;
            if (n.equals("getActivityInfo")) return app.activity(c.getClassName());
            if (n.equals("getServiceInfo") && app.pi.services != null) { for (android.content.pm.ServiceInfo s : app.pi.services) if (s.name.equals(c.getClassName())) return s; return null; }
            if (n.equals("getReceiverInfo") && app.pi.receivers != null) { for (ActivityInfo s : app.pi.receivers) if (s.name.equals(c.getClassName())) return s; return null; }
            if (n.equals("getProviderInfo") && app.pi.providers != null) { for (android.content.pm.ProviderInfo s : app.pi.providers) if (s.name.equals(c.getClassName())) return s; return null; }
        }
        return NOT_HANDLED;
    }
}
