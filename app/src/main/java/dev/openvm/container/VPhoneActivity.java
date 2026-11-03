package dev.openvm.container;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** A virtual phone: its own home screen with an app grid. Install lives here, apps and data stay inside this phone. */
public class VPhoneActivity extends Activity {
    public static final String EXTRA_PHONE = "phone";
    private static final int PICK = 41;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private GridLayout grid;
    private TextView battery;
    private String phoneId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        phoneId = getIntent().getStringExtra(EXTRA_PHONE);
        if (phoneId == null) phoneId = PhoneStore.active(this);
        if (!phoneId.equals(PhoneStore.active(this))) {
            // each process hosts one phone at a time, so switching restarts OpenVM (the system reopens this screen)
            PhoneStore.setActive(this, phoneId);
            Toast.makeText(this, "Switching phone...", Toast.LENGTH_SHORT).show();
            Process.killProcess(Process.myPid());
            return;
        }
        buildUi();
        refresh();
        handleViewIntent(getIntent());
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        GradientDrawable wall = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0F2027, 0xFF203A43, 0xFF2C5364});
        FrameLayout root = new FrameLayout(this); root.setBackground(wall);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16), dp(10), dp(16), dp(6));
        TextClock clock = new TextClock(this); clock.setFormat12Hour("h:mm a"); clock.setFormat24Hour("HH:mm"); clock.setTextColor(Color.WHITE); clock.setTextSize(15);
        bar.addView(clock);
        TextView name = new TextView(this); name.setText(PhoneStore.name(this, phoneId)); name.setTextColor(0xCCFFFFFF); name.setGravity(Gravity.CENTER); name.setTextSize(14);
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        battery = new TextView(this); battery.setTextColor(Color.WHITE); battery.setTextSize(14); bar.addView(battery);
        col.addView(bar);

        TextView title = new TextView(this); title.setText("Apps"); title.setTextColor(Color.WHITE); title.setTextSize(26); title.setPadding(dp(20), dp(12), dp(20), dp(8));
        col.addView(title);

        grid = new GridLayout(this); grid.setColumnCount(4); grid.setPadding(dp(8), 0, dp(8), dp(8));
        ScrollView sv = new ScrollView(this); sv.addView(grid);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout dock = new LinearLayout(this); dock.setBackgroundColor(0x66000000); dock.setGravity(Gravity.CENTER);
        dock.addView(dockButton("\u25C0  Exit", new View.OnClickListener() { public void onClick(View v) { finish(); } }), new LinearLayout.LayoutParams(0, dp(52), 1));
        dock.addView(dockButton("\u2699  Log", new View.OnClickListener() { public void onClick(View v) { showLog(); } }), new LinearLayout.LayoutParams(0, dp(52), 1));
        col.addView(dock, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private TextView dockButton(String t, View.OnClickListener l) {
        TextView v = new TextView(this); v.setText(t); v.setTextColor(Color.WHITE); v.setGravity(Gravity.CENTER); v.setTextSize(15); v.setOnClickListener(l); return v;
    }

    @Override protected void onResume() {
        super.onResume();
        if (battery == null) return;
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i != null) { int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) battery.setText((l * 100 / s) + "%"); }
    }

    private View tile(String label, View iconView, View.OnClickListener tap, View.OnLongClickListener hold) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER_HORIZONTAL); t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.addView(iconView, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView l = new TextView(this); l.setText(label); l.setTextColor(Color.WHITE); l.setTextSize(11); l.setGravity(Gravity.CENTER); l.setMaxLines(2); l.setPadding(0, dp(4), 0, 0);
        t.addView(l, new LinearLayout.LayoutParams(dp(76), -2));
        t.setOnClickListener(tap); if (hold != null) t.setOnLongClickListener(hold);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = getResources().getDisplayMetrics().widthPixels / 4 - dp(4); t.setLayoutParams(lp);
        return t;
    }

    private void refresh() {
        grid.removeAllViews();
        for (final ContainerApp a : ContainerManager.get(this).list()) {
            ImageView icon = new ImageView(this); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (a.icon != null) icon.setImageDrawable(a.icon);
            grid.addView(tile(a.label, icon, new View.OnClickListener() { public void onClick(View v) { open(a); } },
                new View.OnLongClickListener() { public boolean onLongClick(View v) { options(a); return true; } }));
        }
        TextView plus = new TextView(this); plus.setText("+"); plus.setTextSize(32); plus.setTextColor(Color.WHITE); plus.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0x33FFFFFF); bg.setCornerRadius(dp(14)); plus.setBackground(bg);
        grid.addView(tile("Install APK", plus, new View.OnClickListener() { public void onClick(View v) { pickApk(); } }, null));
    }

    private void open(ContainerApp a) {
        try { ContainerManager.get(this).launch(this, a.pkg); }
        catch (Throwable t) { CLog.e("launch failed", t); msg("Could not open " + a.label, t + "\n\nOpen the Log (bottom bar) and send it."); }
    }

    private void options(final ContainerApp a) {
        final String[] items = {"Open", "App info", "Rename", "Clear data", "Remove from this phone"};
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                final ContainerManager m = ContainerManager.get(VPhoneActivity.this);
                if (w == 0) open(a);
                else if (w == 1) msg(a.label, m.info(a.pkg));
                else if (w == 2) rename(a);
                else if (w == 3) new AlertDialog.Builder(VPhoneActivity.this).setTitle("Clear data of " + a.label + "?")
                    .setMessage("Deletes its files, databases and settings but keeps the app. Close the app first if it is running, or fully restart OpenVM afterwards.")
                    .setPositiveButton("Clear", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d2, int w2) { m.clearData(a.pkg); Toast.makeText(VPhoneActivity.this, "Data cleared", Toast.LENGTH_SHORT).show(); } })
                    .setNegativeButton("Cancel", null).show();
                else new AlertDialog.Builder(VPhoneActivity.this).setTitle("Remove " + a.label + "?").setMessage("Deletes the app and its data from this virtual phone.")
                    .setPositiveButton("Remove", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d2, int w2) { m.uninstall(a.pkg); refresh(); } })
                    .setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    private void rename(final ContainerApp a) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(a.label); et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Rename").setView(et).setMessage("Leave empty to restore the original name.")
            .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                try { ContainerManager.get(VPhoneActivity.this).rename(a.pkg, et.getText().toString()); refresh(); }
                catch (Throwable t) { msg("Rename failed", String.valueOf(t)); }
            } }).setNegativeButton("Cancel", null).show();
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/zip", "application/octet-stream", "*/*"});
        startActivityForResult(i, PICK);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleViewIntent(i); }
    private void handleViewIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) { Uri u = i.getData(); i.setData(null); install(u); }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) install(data.getData());
    }

    private void install(final Uri uri) {
        final String name = displayName(uri);
        Toast.makeText(this, "Installing " + name + "...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { public void run() {
            String title, text;
            File tmp = new File(getCacheDir(), "import.bin");
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("can't open the file");
                OutputStream o = new FileOutputStream(tmp);
                try { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); } finally { o.close(); in.close(); }
                ContainerManager.Result r = ContainerManager.get(VPhoneActivity.this).install(tmp);
                title = "Installed " + r.app.label;
                StringBuilder sb = new StringBuilder(r.app.pkg);
                for (String w : r.warnings) sb.append("\n- ").append(w);
                text = sb.toString();
                CLog.i("installed " + r.app.pkg);
            } catch (Throwable t) { CLog.e("install failed", t); title = "Install failed"; text = String.valueOf(t.getMessage() != null ? t.getMessage() : t); }
            finally { tmp.delete(); }
            final String ft = title, fx = text;
            ui.post(new Runnable() { public void run() { refresh(); msg(ft, fx); } });
        }}).start();
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Throwable ignore) {} finally { if (c != null) c.close(); }
        return "app";
    }

    private void msg(String t, String m) { new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show(); }

    private void showLog() {
        TextView tv = new TextView(this); tv.setText(CLog.read()); tv.setTextIsSelectable(true); tv.setTextSize(11); tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        ScrollView sv = new ScrollView(this); sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Container log").setView(sv).setPositiveButton("Close", null).show();
    }
}
