package dev.openvm.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;
import dev.openvm.container.GuestBoot;
import dev.openvm.container.GuestRuntime;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The guest's screen. A shared framebuffer file (<rootfs>/dev/openvm/fb, the guest sees /dev/openvm/fb) is drawn on a SurfaceView
 * and touches are appended to an input ring (<rootfs>/dev/openvm/input). Whatever the guest renders into that framebuffer
 * (this build: a test program; later a HWC/gralloc bridge for SurfaceFlinger) shows up here.
 * Layout: fb = u32 magic 'OFVB'(0x4256464f), width, height, stride, format(1=RGBA8888), frame counter(@20), pad.., pixels @4096.
 *         input = u32 magic, u32 write index (@4), 256 records of {u32 action 0 down/1 up/2 move/3 cancel, i32 x, i32 y, u32 pointer}.
 *
 * Two modes (Intent extra "boot"):
 *   boot=false  runs the fbtest pattern program in the guest (proves guest -> screen and touch -> guest).
 *   boot=true   starts the guest Android boot (GuestBoot) and shows its live output as a console until the guest paints a frame itself.
 *               In both modes a frame the guest has painted in the last 3 seconds takes precedence over the console.
 */
public class DisplayActivity extends Activity implements SurfaceHolder.Callback {
    static final int W = 480, H = 800;
    private static final long FRAME_TIMEOUT_MS = 3000;
    private SurfaceView sv; private volatile boolean run; private Thread th; private MappedByteBuffer fb, in; private Process guest;
    private boolean bootMode;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        bootMode = getIntent().getBooleanExtra("boot", false);
        sv = new SurfaceView(this); sv.getHolder().addCallback(this); setContentView(sv);
        try { setup(); } catch (Throwable t) { Toast.makeText(this, "display setup failed: " + t, Toast.LENGTH_LONG).show(); return; }
        sv.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) { touch(e); return true; } });
        if (bootMode) {
            GuestBoot.start(this, new GuestBoot.Sink() { public void line(String s) { } });   // output is kept by GuestBoot itself (tailLines)
            return;
        }
        final String tool = GuestRuntime.installTool(this, "fbtest");
        if (tool != null) new Thread(new Runnable() { public void run() { try {
            guest = GuestRuntime.newGuestProcess(DisplayActivity.this, new ArrayList<String>(Arrays.asList("-d")), Arrays.asList(tool), null).start();
            java.io.InputStream i = guest.getInputStream(); byte[] x = new byte[4096]; while (i.read(x) > 0) { }
        } catch (Throwable ignore) {} } }, "openvm-guest-display").start();
    }
    private void setup() throws Exception {
        File d = new File(GuestRuntime.root(this), "dev/openvm"); d.mkdirs();
        File ff = new File(d, "fb"), fi = new File(d, "input");
        RandomAccessFile a = new RandomAccessFile(ff, "rw"); a.setLength(4096L + W * 4L * H);
        fb = a.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 4096L + W * 4L * H); fb.order(ByteOrder.LITTLE_ENDIAN);
        fb.putInt(0, 0x4256464f); fb.putInt(4, W); fb.putInt(8, H); fb.putInt(12, W * 4); fb.putInt(16, 1);
        if (bootMode) fb.putInt(20, 0);          // a stale counter from an earlier run must not look like a live guest
        RandomAccessFile c = new RandomAccessFile(fi, "rw"); c.setLength(8 + 256 * 16);
        in = c.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 8 + 256 * 16); in.order(ByteOrder.LITTLE_ENDIAN); in.putInt(0, 0x4e494d56);
        ff.setReadable(true, false); ff.setWritable(true, false); fi.setReadable(true, false); fi.setWritable(true, false);
    }
    private void touch(MotionEvent e) {
        if (in == null) return;
        int act = e.getActionMasked(); int a = act == MotionEvent.ACTION_DOWN ? 0 : act == MotionEvent.ACTION_UP ? 1 : act == MotionEvent.ACTION_MOVE ? 2 : act == MotionEvent.ACTION_CANCEL ? 3 : -1;
        if (a < 0) return;
        int x = (int) (e.getX() * W / Math.max(1, sv.getWidth())), y = (int) (e.getY() * H / Math.max(1, sv.getHeight()));
        synchronized (this) {
            int w = in.getInt(4); int off = 8 + (w & 255) * 16;
            in.putInt(off, a); in.putInt(off + 4, x); in.putInt(off + 8, y); in.putInt(off + 12, e.getPointerId(0));
            in.putInt(4, w + 1);
        }
    }

    // ---------------------------------------------------------------- boot console (drawn while the guest has not painted a frame)
    private final Paint bg = new Paint(), head = new Paint(), txt = new Paint(), err = new Paint(), dim = new Paint();
    private void initPaints() {
        bg.setColor(0xFF0E1216);
        head.setColor(0xFFFFFFFF); head.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); head.setAntiAlias(true);
        txt.setColor(0xFFB8C4CE); txt.setTypeface(Typeface.MONOSPACE); txt.setAntiAlias(true);
        err.setColor(0xFFFF8A65); err.setTypeface(Typeface.MONOSPACE); err.setAntiAlias(true);
        dim.setColor(0xFF7A8794); dim.setTypeface(Typeface.MONOSPACE); dim.setAntiAlias(true);
    }
    private static boolean isError(String l) {
        return l.contains("[guest-log] F ") || l.contains("[guest-log] E ") || l.contains("Fatal signal") || l.contains("[crash]") || l.contains("killed by signal")
            || l.contains("Aborting") || l.contains("failed") || l.contains("exited with code");
    }
    private void drawConsole(Canvas cv) {
        int w = cv.getWidth(), h = cv.getHeight();
        cv.drawRect(0, 0, w, h, bg);
        float px = Math.max(18f, w / 42f);                     // text size scales with the screen
        head.setTextSize(px * 1.25f); txt.setTextSize(px); err.setTextSize(px); dim.setTextSize(px * 0.9f);
        float cw = txt.measureText("M"), lh = px * 1.18f, pad = px * 0.6f;
        float y = pad + px * 1.25f;
        cv.drawText("OpenVM guest Android", pad, y, head); y += lh * 1.3f;
        boolean bad = GuestBoot.state().startsWith("zygote exited") || GuestBoot.state().startsWith("boot failed") || GuestBoot.state().startsWith("no rootfs");
        cv.drawText("state: " + GuestBoot.state(), pad, y, bad ? err : txt); y += lh;
        if (bootMode) {
            cv.drawText("no frame from the guest yet - boot console shown", pad, y, dim); y += lh;
            cv.drawText("(Android UI needs the SurfaceFlinger->screen bridge)", pad, y, dim); y += lh * 1.4f;
        } else { cv.drawText("waiting for the fbtest program in the guest...", pad, y, dim); y += lh * 1.4f; }
        int maxChars = Math.max(10, (int) ((w - 2 * pad) / cw));
        int rows = Math.max(1, (int) ((h - y - pad) / lh));
        List<String> raw = GuestBoot.tailLines(rows * 3);       // wrapped lines take several rows, so read more than needed
        List<String[]> wrapped = new ArrayList<String[]>();     // {text, "1" if error}
        for (String l : raw) {
            if (l.isEmpty()) continue;
            String flag = isError(l) ? "1" : "0";
            for (int i = 0; i < l.length(); i += maxChars) wrapped.add(new String[] { l.substring(i, Math.min(l.length(), i + maxChars)), flag });
        }
        int from = Math.max(0, wrapped.size() - rows);
        for (int i = from; i < wrapped.size(); i++) { String[] r = wrapped.get(i); cv.drawText(r[0], pad, y, "1".equals(r[1]) ? err : txt); y += lh; }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (fb == null) return;
        initPaints();
        run = true;
        th = new Thread(new Runnable() { public void run() {
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); int last = fb.getInt(20); Rect dst = new Rect();
            long lastChange = bootMode ? 0 : System.currentTimeMillis(); boolean guestFrame = !bootMode; long lastConsole = 0;
            ByteBuffer px = fb.duplicate(); px.order(ByteOrder.LITTLE_ENDIAN);
            while (run) {
                int f = fb.getInt(20); long now = System.currentTimeMillis(); boolean changed = f != last;
                if (changed) { last = f; lastChange = now; guestFrame = true; }
                boolean live = guestFrame && now - lastChange < FRAME_TIMEOUT_MS;
                if (changed || (!live && now - lastConsole > 250)) {
                    if (changed) { px.position(4096); bmp.copyPixelsFromBuffer(px); }
                    SurfaceHolder holder = sv.getHolder(); Canvas cv = holder.lockCanvas();
                    if (cv != null) { try {
                        if (live) { dst.set(0, 0, cv.getWidth(), cv.getHeight()); cv.drawBitmap(bmp, null, dst, null); }
                        else drawConsole(cv);
                    } finally { holder.unlockCanvasAndPost(cv); } }
                    lastConsole = now;
                }
                try { Thread.sleep(16); } catch (InterruptedException e) { break; }
            }
        } }, "openvm-display"); th.start();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { run = false; if (th != null) th.interrupt(); }
    @Override protected void onDestroy() { super.onDestroy(); run = false; if (guest != null) guest.destroy(); }
}
