package dev.openvm.container;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Parcel;
import java.util.ArrayList;

/** Wraps an intent for a container activity into an intent for one of the manifest stub activities, and back. */
final class Redirect {
    private Redirect() {}
    static final String K_RAW = "ov.raw", K_PKG = "ov.pkg", K_CLS = "ov.cls";
    static final int STD = 16, TOP = 8, TASK = 8, INST = 4;

    static String stubClass(String hostPkg, ActivityInfo info, ComponentName target) {
        int h = (target.flattenToString().hashCode()) & 0x7fffffff;
        String p = "dev.openvm.container.stub.";
        switch (info == null ? 0 : info.launchMode) {
            case ActivityInfo.LAUNCH_SINGLE_TOP: return p + "T" + (h % TOP);
            case ActivityInfo.LAUNCH_SINGLE_TASK: return p + "K" + (h % TASK);
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE: case 4: return p + "I" + (h % INST);
            default: return p + "A" + (h % STD);
        }
    }

    static Intent wrap(String hostPkg, Intent orig, ActivityInfo info, ComponentName target) {
        byte[] raw;
        Parcel p = Parcel.obtain();
        try { orig.writeToParcel(p, 0); raw = p.marshall(); } finally { p.recycle(); }
        Intent s = new Intent();
        s.setComponent(new ComponentName(hostPkg, stubClass(hostPkg, info, target)));
        s.setFlags(orig.getFlags());
        s.putExtra(K_RAW, raw);
        s.putExtra(K_PKG, target.getPackageName());
        s.putExtra(K_CLS, target.getClassName());
        return s;
    }

    static boolean isWrapped(Intent i) { return i != null && i.getExtras() != null && i.hasExtra(K_RAW); }

    static Intent unwrap(Intent stub, ClassLoader pluginCl) {
        byte[] raw = stub.getByteArrayExtra(K_RAW);
        Parcel p = Parcel.obtain();
        try {
            p.unmarshall(raw, 0, raw.length); p.setDataPosition(0);
            Intent o = Intent.CREATOR.createFromParcel(p);
            o.setExtrasClassLoader(pluginCl);
            return o;
        } finally { p.recycle(); }
    }

    /** Makes dst look like src (the system keeps using the dst object as the activity's intent). */
    static void copyInto(Intent dst, Intent src) {
        dst.setComponent(src.getComponent());
        dst.setAction(src.getAction());
        dst.setDataAndType(src.getData(), src.getType());
        dst.setFlags(src.getFlags());
        dst.setPackage(src.getPackage());
        dst.setClipData(src.getClipData());
        if (dst.getCategories() != null) for (String c : new ArrayList<String>(dst.getCategories())) dst.removeCategory(c);
        if (src.getCategories() != null) for (String c : src.getCategories()) dst.addCategory(c);
        dst.replaceExtras(src);
    }
}
