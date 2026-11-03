package dev.openvm.container;

import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logcat + a small rolling file (filesDir/capps/container.log) so problems can be read from the app. */
public final class CLog {
    private CLog() {}
    static volatile File file;
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static void i(String m) { Log.i("OpenVM", m); write("I " + m); }
    public static void w(String m) { Log.w("OpenVM", m); write("W " + m); }
    public static void e(String m, Throwable t) { Log.e("OpenVM", m, t); write("E " + m + (t == null ? "" : "\n" + Log.getStackTraceString(t))); }

    private static synchronized void write(String line) {
        File f = file; if (f == null) return;
        try {
            if (f.length() > 256 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            try { w.write(F.format(new Date()) + " " + line + "\n"); } finally { w.close(); }
        } catch (IOException ignore) {}
    }
    public static String read() {
        File f = file; if (f == null || !f.exists()) return "(empty)";
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 60000)];
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
            try { r.seek(Math.max(0, f.length() - b.length)); r.readFully(b); } finally { r.close(); }
            return new String(b, "UTF-8");
        } catch (Exception e) { return "(can't read log: " + e + ")"; }
    }
}
