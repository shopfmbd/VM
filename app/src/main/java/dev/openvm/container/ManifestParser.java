package dev.openvm.container;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reads the binary AndroidManifest.xml of an APK: package, split name, launcher activity, per-activity actions. */
final class ManifestParser {
    private ManifestParser() {}

    // ================================================================ binary AndroidManifest.xml (AXML)
    /** Result of reading a binary AndroidManifest.xml. activityActions maps an activity class name (as written) to the actions of its intent filters. */
    public static final class Manifest {
        public String pkg, split, label, launcher, firstActivity;
        public final Map<String, Set<String>> activityActions = new HashMap<String, Set<String>>();
    }

    public static Manifest parse(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != 0x0003) throw new IllegalArgumentException("not binary XML");
        int total = Math.min(b.getInt(4), data.length);
        int pos = b.getShort(2) & 0xffff;
        String[] strs = new String[0];
        Manifest m = new Manifest();
        String activity = null; boolean main = false, launcher = false;
        Set<String> filterActions = new HashSet<String>();
        while (pos + 8 <= total) {
            int type = b.getShort(pos) & 0xffff, size = b.getInt(pos + 4);
            if (size < 8 || pos + size > data.length) break;
            if (type == 0x0001) strs = readPool(b, pos);
            else if (type == 0x0102) {
                String tag = str(strs, b.getInt(pos + 20));
                int attrOff = pos + 16 + (b.getShort(pos + 24) & 0xffff), attrSize = b.getShort(pos + 26) & 0xffff, n = b.getShort(pos + 28) & 0xffff;
                if ("manifest".equals(tag)) { m.pkg = attr(b, strs, attrOff, attrSize, n, "package"); m.split = attr(b, strs, attrOff, attrSize, n, "split"); }
                else if ("application".equals(tag)) m.label = attr(b, strs, attrOff, attrSize, n, "label");
                else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                    String t = attr(b, strs, attrOff, attrSize, n, "targetActivity");
                    String nm = "activity-alias".equals(tag) && t != null ? t : attr(b, strs, attrOff, attrSize, n, "name");
                    activity = nm; if (m.firstActivity == null) m.firstActivity = nm;
                } else if ("intent-filter".equals(tag)) { main = false; launcher = false; filterActions.clear(); }
                else if ("action".equals(tag)) { String an = attr(b, strs, attrOff, attrSize, n, "name"); if ("android.intent.action.MAIN".equals(an)) main = true; if (an != null) filterActions.add(an); }
                else if ("category".equals(tag)) { if ("android.intent.category.LAUNCHER".equals(attr(b, strs, attrOff, attrSize, n, "name"))) launcher = true; }
            } else if (type == 0x0103) {
                String tag = str(strs, b.getInt(pos + 20));
                if ("intent-filter".equals(tag)) {
                    if (main && launcher && activity != null && m.launcher == null) m.launcher = activity;
                    if (activity != null && !filterActions.isEmpty()) {
                        Set<String> set = m.activityActions.get(activity);
                        if (set == null) { set = new HashSet<String>(); m.activityActions.put(activity, set); }
                        set.addAll(filterActions);
                    }
                }
                else if ("activity".equals(tag) || "activity-alias".equals(tag)) activity = null;
            }
            pos += size;
        }
        return m;
    }

    private static String str(String[] s, int i) { return i >= 0 && i < s.length ? s[i] : null; }

    /** Attribute value as a string, looked up by local name (namespace ignored). Resource references return null. */
    private static String attr(ByteBuffer b, String[] strs, int off, int size, int n, String name) {
        for (int i = 0; i < n; i++) {
            int a = off + i * size;
            if (!name.equals(str(strs, b.getInt(a + 4)))) continue;
            int raw = b.getInt(a + 8), dataType = b.get(a + 15) & 0xff, data = b.getInt(a + 16);
            if (raw != -1) return str(strs, raw);
            if (dataType == 0x03) return str(strs, data);
            return null;
        }
        return null;
    }

    private static String[] readPool(ByteBuffer b, int pos) {
        int hdr = b.getShort(pos + 2) & 0xffff, n = b.getInt(pos + 8), flags = b.getInt(pos + 16), sOff = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        String[] r = new String[n];
        for (int i = 0; i < n; i++) {
            int p = pos + sOff + b.getInt(pos + hdr + 4 * i);
            try {
                if (utf8) {
                    int l = b.get(p++) & 0xff; if ((l & 0x80) != 0) p++;               // char count (1 or 2 bytes), unused
                    int u = b.get(p++) & 0xff; if ((u & 0x80) != 0) u = ((u & 0x7f) << 8) | (b.get(p++) & 0xff);
                    byte[] t = new byte[u]; for (int k = 0; k < u; k++) t[k] = b.get(p + k);
                    r[i] = new String(t, "UTF-8");
                } else {
                    int l = b.getShort(p) & 0xffff; p += 2;
                    if ((l & 0x8000) != 0) { l = ((l & 0x7fff) << 16) | (b.getShort(p) & 0xffff); p += 2; }
                    char[] c = new char[l]; for (int k = 0; k < l; k++) c[k] = b.getChar(p + 2 * k);
                    r[i] = new String(c);
                }
            } catch (Exception e) { r[i] = null; }
        }
        return r;
    }
}
