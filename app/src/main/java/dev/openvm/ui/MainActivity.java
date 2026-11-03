package dev.openvm.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.openvm.container.PhoneStore;
import dev.openvm.container.VPhoneActivity;

/** Lists the virtual phones. Open one, then tap "Install APK" on its home screen. */
public class MainActivity extends Activity {
    private LinearLayout phones;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("OpenVM"); title.setTextSize(22f); title.setPadding(0, 0, 0, 16);
        col.addView(title);
        Button add = new Button(this);
        add.setText("Quick mode: new container phone (runs APKs on this phone's Android)");
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { createPhone(); } });
        col.addView(add);
        Button guest = new Button(this);
        guest.setText("Virtual machine: boot guest Android (experimental)");
        guest.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(MainActivity.this, GuestActivity.class)); } });
        col.addView(guest);
        phones = new LinearLayout(this);
        phones.setOrientation(LinearLayout.VERTICAL);
        col.addView(phones);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void createPhone() {
        final EditText et = new EditText(this);
        et.setHint("Name (e.g. Games)");
        new AlertDialog.Builder(this).setTitle("New virtual phone").setView(et)
            .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { PhoneStore.create(MainActivity.this, et.getText().toString()); render(); }
            }).setNegativeButton("Cancel", null).show();
    }

    private void render() {
        phones.removeAllViews();
        String active = PhoneStore.active(this);
        for (final PhoneStore.Phone p : PhoneStore.list(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);
            TextView name = new TextView(this);
            name.setText(p.name + (p.id.equals(active) ? "  (loaded)" : "")); name.setTextSize(17f);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            Button open = new Button(this);
            open.setText("Open");
            open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VPhoneActivity.class).putExtra(VPhoneActivity.EXTRA_PHONE, p.id));
            }});
            row.addView(open);
            Button del = new Button(this);
            del.setText("X");
            del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (!PhoneStore.delete(MainActivity.this, p.id)) Toast.makeText(MainActivity.this, "Can't delete the first phone or the loaded one", Toast.LENGTH_SHORT).show();
                render();
            }});
            row.addView(del);
            phones.addView(row);
        }
    }
}
