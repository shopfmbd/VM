package dev.openvm.container;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the guest's system-property area (what init's property service creates at boot) as plain files in
 * &lt;rootfs&gt;/dev/__properties__, in the exact binary layout bionic's libc reads (Android 10-12):
 *   property_info     serialized context trie (every property maps to ONE context)
 *   properties_serial the (empty) serial area
 *   &lt;context&gt;        the prop_area holding all properties (128 KB)
 * Guest processes mmap these read-only; vmloader shows them as root-owned.
 */
public final class PropArea {
    private PropArea() {}

    public static final String CONTEXT = "u:object_r:default_prop:s0";
    private static final int PA_SIZE = 128 * 1024;
    private static final int HDR = 128;                // sizeof(prop_area) without data
    private static final int BT = 20;                  // sizeof(prop_bt)
    private static final int INFO = 96;                // sizeof(prop_info) without name
    private static final int VALUE_MAX = 92;

    /** Builds one prop_area from the properties. */
    static byte[] buildArea(Map<String, String> props) {
        ByteBuffer b = ByteBuffer.allocate(PA_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        Area a = new Area(b);
        for (Map.Entry<String, String> e : props.entrySet()) a.add(e.getKey(), e.getValue());
        return a.finish();
    }

    private static final class Area {
        final ByteBuffer b; int used = BT + VALUE_MAX;  // root prop_bt at data offset 0, then bionic's 92-byte dirty-backup area
        Area(ByteBuffer b) { this.b = b; }
        int alloc(int n) {
            int off = used; used += (n + 3) & ~3;
            if (HDR + used > PA_SIZE) throw new IllegalStateException("property area full");
            return off;
        }
        int u32(int dataOff) { return b.getInt(HDR + dataOff); }
        void put(int dataOff, int v) { b.putInt(HDR + dataOff, v); }
        // prop_bt: u32 namelen, prop, left, right, children, then name\0
        int newBt(String name) {
            byte[] nb = utf8(name); int off = alloc(BT + nb.length + 1);
            put(off, nb.length);
            for (int i = 0; i < nb.length; i++) b.put(HDR + off + BT + i, nb[i]);
            return off;
        }
        static int cmp(String x, String y) {           // bionic cmp_prop_name: shorter first, then bytes
            if (x.length() != y.length()) return x.length() < y.length() ? -1 : 1;
            return x.compareTo(y);
        }
        /** Finds or creates the child of node `parent` named `token` in the binary tree hung off parent.children. */
        int child(int parent, String token) {
            int cur = u32(parent + 16);                // children
            if (cur == 0) { int n = newBt(token); put(parent + 16, n); return n; }
            while (true) {
                String nm = btName(cur); int c = cmp(token, nm);
                if (c == 0) return cur;
                int slot = cur + (c < 0 ? 8 : 12);     // left / right
                int nx = u32(slot);
                if (nx == 0) { int n = newBt(token); put(slot, n); return n; }
                cur = nx;
            }
        }
        String btName(int off) {
            int len = u32(off); byte[] t = new byte[len];
            for (int i = 0; i < len; i++) t[i] = b.get(HDR + off + BT + i);
            return new String(t, java.nio.charset.Charset.forName("UTF-8"));
        }
        void add(String name, String value) {
            int node = 0;
            for (String tok : name.split("\\.", -1)) if (tok.isEmpty()) return;   // invalid name
            for (String tok : name.split("\\.", -1)) node = child(node, tok);
            if (u32(node + 4) != 0) return;            // already set (first definition wins)
            byte[] nb = utf8(name), vb = utf8(value);
            int longOff = 0, size = INFO + nb.length + 1;
            boolean isLong = vb.length >= VALUE_MAX;
            if (isLong) { longOff = (size + 3) & ~3; size = longOff + vb.length + 1; }
            int off = alloc(size); int base = HDR + off;
            if (!isLong) {
                b.putInt(base, vb.length << 24);
                for (int i = 0; i < vb.length; i++) b.put(base + 4 + i, vb[i]);
            } else {
                b.putInt(base, 1 << 16);               // kLongFlag
                byte[] msg = utf8("Must use __system_property_read_callback() to read");
                for (int i = 0; i < msg.length && i < 55; i++) b.put(base + 4 + i, msg[i]);
                b.putInt(base + 4 + 56, longOff);
                for (int i = 0; i < vb.length; i++) b.put(base + longOff + i, vb[i]);
            }
            for (int i = 0; i < nb.length; i++) b.put(base + INFO + i, nb[i]);
            put(node + 4, off);                        // prop_bt.prop = offset of prop_info
        }
        byte[] finish() {
            b.putInt(0, used);                         // bytes_used
            b.putInt(4, 0);                            // serial
            b.putInt(8, 0x504f5250);                   // magic "PROP"
            b.putInt(12, 0xfc6ed0ab);                  // version
            return b.array();
        }
    }

    /** Serialized property_info with a single context and no prefix/exact entries: everything maps to context 0. */
    static byte[] buildInfo() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        byte[] ctx = utf8(CONTEXT), type = utf8("string"), root = utf8("root");
        int p = 24;
        int ctxArr = p; p += 8;                        // u32 count + 1 offset
        int typeArr = p; p += 8;
        int ctxStr = p; p += ctx.length + 1; p = (p + 3) & ~3;
        int typeStr = p; p += type.length + 1; p = (p + 3) & ~3;
        int rootStr = p; p += root.length + 1; p = (p + 3) & ~3;
        int entry = p; p += 16;                        // PropertyEntry
        int node = p; p += 28;                         // TrieNodeInternal
        ByteBuffer d = ByteBuffer.allocate(p).order(ByteOrder.LITTLE_ENDIAN);
        d.putInt(0, 1); d.putInt(4, 1); d.putInt(8, p); d.putInt(12, ctxArr); d.putInt(16, typeArr); d.putInt(20, node);
        d.putInt(ctxArr, 1); d.putInt(ctxArr + 4, ctxStr);
        d.putInt(typeArr, 1); d.putInt(typeArr + 4, typeStr);
        for (int i = 0; i < ctx.length; i++) d.put(ctxStr + i, ctx[i]);
        for (int i = 0; i < type.length; i++) d.put(typeStr + i, type[i]);
        for (int i = 0; i < root.length; i++) d.put(rootStr + i, root[i]);
        d.putInt(entry, rootStr); d.putInt(entry + 4, root.length); d.putInt(entry + 8, 0); d.putInt(entry + 12, 0xffffffff);
        d.putInt(node, entry);                         // property_entry; every other field 0 = no children/prefixes/exact
        return d.array();
    }

    private static byte[] utf8(String s) { try { return s.getBytes("UTF-8"); } catch (Exception e) { return s.getBytes(); } }

    // ---------------------------------------------------------------- property sources
    /** Reads key=value lines (build.prop style) into out; later files do not override earlier ones for ro.*. */
    static void load(File f, Map<String, String> out) {
        if (!f.isFile()) return;
        try {
            FileInputStream in = new FileInputStream(f); byte[] all;
            try { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] t = new byte[8192]; int n; while ((n = in.read(t)) > 0) o.write(t, 0, n); all = o.toByteArray(); } finally { in.close(); }
            for (String line : new String(all, "UTF-8").split("\n")) {
                line = line.trim(); if (line.isEmpty() || line.startsWith("#") || line.startsWith("import ")) continue;
                int eq = line.indexOf('='); if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.startsWith("ro.") && out.containsKey(k)) continue;
                out.put(k, v);
            }
        } catch (IOException ignore) {}
    }

    /** (Re)creates &lt;root&gt;/dev/__properties__ from the guest's build.prop files plus the properties init would set. */
    public static int write(File root) throws IOException {
        Map<String, String> p = new TreeMap<String, String>();
        String[] files = { "system/build.prop", "system/etc/prop.default", "vendor/build.prop", "vendor/default.prop", "odm/etc/build.prop",
                           "product/etc/build.prop", "system_ext/etc/build.prop" };
        for (String f : files) load(new File(root, f), p);
        // what init / the bootloader / the kernel command line would provide
        String[][] extra = {
            { "ro.zygote", "zygote64" }, { "ro.hardware", "openvm" }, { "ro.boot.hardware", "openvm" }, { "ro.bootmode", "unknown" },
            { "ro.boot.slot_suffix", "" }, { "ro.debuggable", "1" }, { "ro.secure", "0" }, { "ro.adb.secure", "0" },
            { "ro.kernel.qemu", "0" }, { "ro.product.cpu.abilist", "arm64-v8a" }, { "ro.product.cpu.abilist64", "arm64-v8a" }, { "ro.product.cpu.abilist32", "" },
            { "ro.boottime.init", "1000000000" }, { "ro.revision", "0" }, { "ro.property_service.version", "2" }, { "ro.serialno", "OPENVM0001" },
            { "sys.boot_completed", "0" }, { "init.svc.servicemanager", "stopped" }, { "persist.sys.timezone", "UTC" },
            { "dalvik.vm.heapsize", "256m" }, { "dalvik.vm.isa.arm64.variant", "generic" }, { "dalvik.vm.isa.arm64.features", "default" },
        };
        for (String[] kv : extra) if (!p.containsKey(kv[0])) p.put(kv[0], kv[1]);
        p.put("dalvik.vm.usejit", "false"); p.put("dalvik.vm.usejitprofiles", "false");      // interpreter only (ptrace + JIT code patching is not supported yet)
        // AndroidRuntime passes -XjdwpProvider:default when this is unset, and ART then dlopen()s libadbconnection.so, which needs
        // libadbconnection_client.so from the adbd APEX (not part of the rootfs): "Plugin ... failed to load" -> Runtime aborting.
        p.put("dalvik.vm.jdwp-provider", "none");
        File dir = new File(root, "dev/__properties__"); dir.mkdirs();
        w(new File(dir, CONTEXT), buildArea(p));
        w(new File(dir, "properties_serial"), buildArea(new TreeMap<String, String>()));
        w(new File(dir, "property_info"), buildInfo());
        return p.size();
    }

    private static void w(File f, byte[] data) throws IOException {
        if (f.exists()) f.delete();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
        f.setReadable(true, false); f.setWritable(false, false); f.setWritable(true, true);
    }

    /** Test entry point: java -cp classes dev.openvm.container.PropArea ROOTFS */
    public static void main(String[] a) throws Exception { System.out.println(write(new File(a[0])) + " properties"); }
}
