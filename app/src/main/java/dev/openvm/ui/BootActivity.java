package dev.openvm.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.openvm.container.GuestBoot;

/** Boots the guest Android (servicemanager + zygote + system_server) and shows the live log. */
public class BootActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder buf = new StringBuilder();
    private TextView out, state; private ScrollView sv;
    private volatile boolean dirty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(20, 20, 20, 20);
        TextView t = new TextView(this); t.setText("Boot guest Android (experimental)"); t.setTextSize(20f); col.addView(t);
        state = new TextView(this); state.setTextSize(12f); col.addView(state);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(btn("Boot", new View.OnClickListener() { public void onClick(View v) { buf.setLength(0); GuestBoot.start(BootActivity.this, sink); } }));
        row.addView(btn("Stop", new View.OnClickListener() { public void onClick(View v) { GuestBoot.stop(); } }));
        row.addView(btn("Copy log", new View.OnClickListener() { public void onClick(View v) {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("boot log", buf.toString()));
            Toast.makeText(BootActivity.this, "Log copied", Toast.LENGTH_SHORT).show(); } }));
        row.addView(btn("Screen", new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(BootActivity.this, DisplayActivity.class)); } }));
        col.addView(row);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(btn("Boot + screen", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(BootActivity.this, DisplayActivity.class).putExtra("boot", true)); } }));
        col.addView(row2);
        sv = new ScrollView(this); out = new TextView(this); out.setTypeface(android.graphics.Typeface.MONOSPACE); out.setTextSize(10.5f); out.setTextIsSelectable(true);
        sv.addView(out); col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(col);
        ui.post(new Runnable() { public void run() { tick(); ui.postDelayed(this, 500); } });
    }
    private Button btn(String s, View.OnClickListener l) { Button x = new Button(this); x.setText(s); x.setOnClickListener(l); x.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f)); return x; }
    private final GuestBoot.Sink sink = new GuestBoot.Sink() { public void line(String s) {
        synchronized (buf) { buf.append(s).append('\n'); if (buf.length() > 80000) buf.delete(0, buf.length() - 60000); } dirty = true; } };
    private void tick() {
        state.setText(GuestBoot.running() ? "running - log file: " + GuestBoot.logFile(this) : "stopped - log file: " + GuestBoot.logFile(this));
        if (dirty) { dirty = false; String s; synchronized (buf) { s = buf.toString(); } out.setText(s); sv.post(new Runnable() { public void run() { sv.fullScroll(View.FOCUS_DOWN); } }); }
    }
    @Override protected void onDestroy() { super.onDestroy(); }
}
