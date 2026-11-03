package dev.openvm.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.openvm.container.GuestRuntime;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Experimental: import an extracted Android/Linux root filesystem and run programs from it with vmloader. */
public class GuestActivity extends Activity {
    private static final int PICK = 91;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, out;
    private EditText cmd;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(24, 24, 24, 24);
        TextView t = new TextView(this); t.setText("Guest runtime (experimental)"); t.setTextSize(22f); col.addView(t);
        status = new TextView(this); status.setTextSize(13f); status.setPadding(0, 8, 0, 8); col.addView(status);
        col.addView(button("Import rootfs (.tar, .tar.gz, .zip)", new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK); } }));
        col.addView(button("Delete rootfs", new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(GuestActivity.this).setTitle("Delete the guest rootfs?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { GuestRuntime.deleteTree(GuestRuntime.root(GuestActivity.this)); refresh(); } })
                .setNegativeButton("Cancel", null).show(); } }));
        cmd = new EditText(this); cmd.setText("/system/bin/sh -c \"echo hello from the guest; ls /\"");
        cmd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); cmd.setTextSize(13f);
        col.addView(cmd);
        col.addView(button("Run", new View.OnClickListener() { public void onClick(View v) { run(); } }));
        col.addView(button("Test: Java on the guest's own ART", new View.OnClickListener() { public void onClick(View v) {
            cmd.setText(GuestRuntime.javaCmd("/data/local/tmp/hello.jar", "Hello")); run(); } }));
        col.addView(button("Test: Java with syscall trace (for debugging)", new View.OnClickListener() { public void onClick(View v) {
            cmd.setText(GuestRuntime.javaCmd("/data/local/tmp/hello.jar", "Hello")); GuestRuntime.trace = true; run(); } }));
        col.addView(button("Test: virtual root (id, su)", new View.OnClickListener() { public void onClick(View v) {
            cmd.setText("/system/bin/sh -c \"id; su -c id\""); run(); } }));
        col.addView(button("Test: Android version", new View.OnClickListener() { public void onClick(View v) {
            cmd.setText("/system/bin/toybox grep ro.build.version.release /system/build.prop"); run(); } }));
        col.addView(button("Test: system properties (getprop)", new View.OnClickListener() { public void onClick(View v) {
            cmd.setText("/system/bin/sh -c \"getprop ro.build.version.release; getprop ro.zygote; getprop ro.hardware; echo props: $(getprop | wc -l)\""); run(); } }));
        col.addView(button("Test: app_process (needs FULL rootfs)", new View.OnClickListener() { public void onClick(View v) {
            if (!GuestRuntime.hasFramework(GuestActivity.this)) { out.setText("app_process needs the Android framework (/system/framework/framework.jar), which the core rootfs does not have.\nImport android12-guest-FULL.tar.gz first.\n"); return; }
            cmd.setText("/system/bin/app_process64 -Xnoimage-dex2oat -Xint -Djava.class.path=/data/local/tmp/hello.jar /system/bin Hello"); run(); } }));
        col.addView(button("BOOT guest Android (servicemanager + zygote, live log)", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(GuestActivity.this, BootActivity.class)); } }));
        col.addView(button("Test: guest screen + touch (display bridge)", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(GuestActivity.this, DisplayActivity.class)); } }));
        col.addView(button("Test: sockets + property service", new View.OnClickListener() { public void onClick(View v) {
            String p = GuestRuntime.installTool(GuestActivity.this, "inittest"); if (p == null) { out.setText("libinittest.so is not in this build for your CPU.\n"); return; }
            GuestRuntime.background = java.util.Arrays.asList(p + " server"); GuestRuntime.sockets = java.util.Arrays.asList("zygote"); GuestRuntime.binderDebug = true;
            cmd.setText(p + " client"); run(); } }));
        col.addView(button("Test: binder driver (self-test)", new View.OnClickListener() { public void onClick(View v) {
            String p = GuestRuntime.installBinderTest(GuestActivity.this);
            if (p == null) { out.setText("libbindertest.so is not in this build for your CPU.\n"); return; }
            GuestRuntime.binderDebug = true; cmd.setText(p); run(); } }));
        col.addView(button("Test: servicemanager + `service list`", new View.OnClickListener() { public void onClick(View v) {
            GuestRuntime.background = java.util.Arrays.asList("/system/bin/servicemanager");
            GuestRuntime.binderDebug = true; cmd.setText("/system/bin/service list"); run(); } }));
        out = new TextView(this); out.setTextIsSelectable(true); out.setTextSize(12f); out.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(out);
        ScrollView sv = new ScrollView(this); sv.addView(col); setContentView(sv);
        refresh();
    }

    private Button button(String text, View.OnClickListener l) { Button b = new Button(this); b.setText(text); b.setOnClickListener(l); return b; }

    private void refresh() {
        File root = GuestRuntime.root(this);
        String[] top = root.list();
        status.setText("Loader: " + (GuestRuntime.loader(this).exists() ? "ok (" + android.os.Build.SUPPORTED_ABIS[0] + ")" : "MISSING for this CPU")
            + "\nRootfs: " + root.getAbsolutePath() + "\n" + (top == null || top.length == 0 ? "empty - import one" : top.length + " top-level entries"));
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        out.setText("Importing...\n");
        new Thread(new Runnable() { public void run() {
            String msg;
            try { InputStream in = getContentResolver().openInputStream(u); try { msg = "Imported " + GuestRuntime.importArchive(GuestActivity.this, in) + " entries."; } finally { in.close(); } }
            catch (Throwable t) { msg = "Import failed: " + t; }
            final String m = msg;
            ui.post(new Runnable() { public void run() { out.setText(m + "\n"); refresh(); } });
        } }).start();
    }

    private void run() {
        final List<String> args = GuestRuntime.split(cmd.getText().toString());
        if (args.isEmpty()) return;
        out.setText("$ " + cmd.getText() + "\n");
        new Thread(new Runnable() { public void run() {
            final String r = GuestRuntime.exec(GuestActivity.this, args, 240);
            ui.post(new Runnable() { public void run() { out.append(r); } });
        } }).start();
    }
}
