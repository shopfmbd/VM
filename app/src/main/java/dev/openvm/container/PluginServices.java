package dev.openvm.container;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Runs a container app's Services in-process (no system involvement). Everything happens on the main thread. */
final class PluginServices {
    private final ContainerApp app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Service> live = new HashMap<String, Service>();
    private final Map<String, IBinder> binders = new HashMap<String, IBinder>();
    private final Map<ServiceConnection, String> conns = new HashMap<ServiceConnection, String>();
    private final Map<String, Integer> startCount = new HashMap<String, Integer>();
    private int startId = 1;

    PluginServices(ContainerApp app) { this.app = app; }

    private Service obtain(String cls) throws Exception {
        Service s = live.get(cls);
        if (s != null) return s;
        s = (Service) app.cl.loadClass(cls).newInstance();
        PluginContext ctx = new PluginContext(app.baseCtx.getBaseContext(), app);
        boolean attached = false;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = Hidden.call(null, at, "currentActivityThread", new Class<?>[0]);
            Object am = Hidden.call(null, android.app.ActivityManager.class, "getService", new Class<?>[0]);
            for (Method m : Service.class.getDeclaredMethods()) {
                if (m.getName().equals("attach") && m.getParameterTypes().length == 6) {
                    m.setAccessible(true);
                    m.invoke(s, ctx, thread, cls, new Binder(), app.app, am);
                    attached = true; break;
                }
            }
        } catch (Throwable t) { CLog.w(app.pkg + ": Service.attach unavailable (" + t + "), using minimal attach"); }
        if (!attached) { Method m = Hidden.method(android.content.ContextWrapper.class, "attachBaseContext", android.content.Context.class); m.invoke(s, ctx); }
        live.put(cls, s);
        s.onCreate();
        CLog.i(app.pkg + ": service " + cls + " created");
        return s;
    }

    ComponentName start(final ComponentName c, final Intent intent) {
        main.post(new Runnable() { public void run() {
            try {
                Service s = obtain(c.getClassName());
                intent.setExtrasClassLoader(app.cl);
                Integer n = startCount.get(c.getClassName()); startCount.put(c.getClassName(), n == null ? 1 : n + 1);
                s.onStartCommand(intent, 0, startId++);
            } catch (Throwable t) { CLog.e(app.pkg + ": startService " + c.getClassName(), t); }
        }});
        return c;
    }

    boolean stop(final ComponentName c) {
        main.post(new Runnable() { public void run() { startCount.remove(c.getClassName()); maybeDestroy(c.getClassName()); }});
        return live.containsKey(c.getClassName());
    }

    boolean bind(final ComponentName c, final Intent intent, final ServiceConnection conn) {
        main.post(new Runnable() { public void run() {
            try {
                Service s = obtain(c.getClassName());
                intent.setExtrasClassLoader(app.cl);
                IBinder b = binders.get(c.getClassName());
                if (b == null) { b = s.onBind(intent); if (b != null) binders.put(c.getClassName(), b); }
                conns.put(conn, c.getClassName());
                if (b != null) conn.onServiceConnected(c, b);
            } catch (Throwable t) { CLog.e(app.pkg + ": bindService " + c.getClassName(), t); }
        }});
        return true;
    }

    boolean unbind(ServiceConnection conn) {
        final String cls = conns.remove(conn);
        if (cls == null) return false;
        main.post(new Runnable() { public void run() {
            boolean any = false; for (String v : conns.values()) if (v.equals(cls)) any = true;
            Service s = live.get(cls);
            if (!any && s != null) { try { s.onUnbind(new Intent()); } catch (Throwable ignore) {} binders.remove(cls); }
            maybeDestroy(cls);
        }});
        return true;
    }

    private void maybeDestroy(String cls) {
        if (startCount.containsKey(cls) || conns.containsValue(cls)) return;
        Service s = live.remove(cls);
        if (s != null) { try { s.onDestroy(); } catch (Throwable t) { CLog.w("onDestroy " + cls + ": " + t); } CLog.i(app.pkg + ": service " + cls + " destroyed"); }
    }
}
