package dev.openvm.container;

import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Hidden-API access: lifts the restrictions once, then plain reflection helpers. */
final class Hidden {
    private Hidden() {}
    private static boolean ready;

    static synchronized void init() {
        if (ready || Build.VERSION.SDK_INT < 28) { ready = true; return; }
        try { if (org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")) { ready = true; return; } }
        catch (Throwable t) { CLog.w("HiddenApiBypass failed: " + t); }
        try {   // fallback: meta-reflection
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vm = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vm, "getRuntime", null);
            Method setEx = (Method) getDeclaredMethod.invoke(vm, "setHiddenApiExemptions", new Class[]{String[].class});
            Object rt = getRuntime.invoke(null);
            setEx.invoke(rt, new Object[]{new String[]{"L"}});
            ready = true;
        } catch (Throwable t) { CLog.w("meta-reflection bypass failed: " + t); }
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignore) {}
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }
    static Object get(Object o, Class<?> c, String name) throws Exception { return field(c, name).get(o); }
    static void set(Object o, Class<?> c, String name, Object v) throws Exception { field(c, name).set(o, v); }

    static Method method(Class<?> c, String name, Class<?>... p) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(name, p); m.setAccessible(true); return m; } catch (NoSuchMethodException ignore) {}
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
    static Object call(Object o, Class<?> c, String name, Class<?>[] p, Object... a) throws Exception {
        try { return method(c, name, p).invoke(o, a); } catch (InvocationTargetException e) { throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e; }
    }
}
