package dev.openvm.container;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The virtual phones. Each one is a folder under filesDir/capps/phones/<id> holding its own installed apps and data. */
public final class PhoneStore {
    private PhoneStore() {}
    public static final class Phone { public final String id, name; Phone(String i, String n) { id = i; name = n; } }

    static File base(Context c) { File f = new File(c.getFilesDir(), "capps/phones"); f.mkdirs(); return f; }
    public static File dir(Context c, String id) { File f = new File(base(c), id); f.mkdirs(); return f; }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("vphones", Context.MODE_PRIVATE); }

    /** The phone whose apps are loaded in this process. Changing it needs a process restart. */
    public static String active(Context c) { ensureDefault(c); return prefs(c).getString("active", "default"); }
    public static void setActive(Context c, String id) { prefs(c).edit().putString("active", id).commit(); }

    static void ensureDefault(Context c) {
        File d = dir(c, "default");
        if (!new File(d, "name.txt").exists()) writeName(d, "Phone 1");
    }

    public static List<Phone> list(Context c) {
        ensureDefault(c);
        List<Phone> out = new ArrayList<Phone>();
        File[] ds = base(c).listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        for (File d : ds) if (d.isDirectory() && new File(d, "name.txt").exists()) out.add(new Phone(d.getName(), name(d)));
        return out;
    }

    public static String name(Context c, String id) { return name(dir(c, id)); }
    private static String name(File d) {
        try { java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(d, "name.txt"))); try { return r.readLine(); } finally { r.close(); } }
        catch (IOException e) { return d.getName(); }
    }
    private static void writeName(File d, String n) {
        try { FileWriter w = new FileWriter(new File(d, "name.txt")); try { w.write(n); } finally { w.close(); } } catch (IOException ignore) {}
    }

    public static String create(Context c, String name) {
        String id = "p" + Long.toString(System.currentTimeMillis(), 36);
        File d = dir(c, id); writeName(d, name == null || name.trim().isEmpty() ? "Phone" : name.trim());
        return id;
    }

    /** Deletes a phone and everything installed in it. The active phone can't be deleted from inside itself. */
    public static boolean delete(Context c, String id) {
        if ("default".equals(id) || id.equals(active(c))) return false;
        ContainerManager.deleteTree(dir(c, id));
        return true;
    }
}
