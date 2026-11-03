package dev.openvm.container;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Manifest placeholder. A container app's real activity object replaces this one (see ContainerHooks).
 * If this code runs, the replacement did not happen - show why instead of silently closing.
 */
public class StubBase extends Activity {
    static volatile String lastError;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String why = lastError != null ? lastError : "The container hook did not take over this screen (see the Log on the phone's home screen).";
        CLog.w("stub shown instead of app: " + why);
        TextView tv = new TextView(this);
        tv.setText("App could not start\n\n" + why + "\n\nOpen Log on the phone's home screen for the full trace.");
        tv.setTextIsSelectable(true); tv.setPadding(32, 64, 32, 32); tv.setTextSize(13);
        ScrollView sv = new ScrollView(this); sv.addView(tv);
        setContentView(sv);
        lastError = null;
    }
}
