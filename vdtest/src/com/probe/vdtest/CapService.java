package com.probe.vdtest;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.ByteBuffer;

/**
 * 虚拟屏投屏试验台。
 *
 * 一次授权后常驻，用文件下指令，不用反复重装（重装就要重新点一次录屏授权）：
 *   <files>/CMD   一行指令，处理后删除
 *       1920x568          重建虚拟屏为该尺寸
 *       com.tencent.qqmusicpad              启动到虚拟屏
 *       com.baidu.naviauto/.SlashActivity.DefaultActivity   指定 Activity
 *       stop              释放虚拟屏
 *   <files>/GO    存在就存一张 raw（含宽高/stride 头）
 *   <files>/vdtest.log  日志
 */
public class CapService extends Service {

    static final String TAG = "VDTEST";
    static final int NOTIF_ID = 0x56D7;
    static final String CH = "vdtest";

    static int W = 1280;
    static int H = 720;
    static int DPI = 144;

    MediaProjection mp;
    VirtualDisplay vd;
    ImageReader reader;
    volatile int vdId = -1;

    volatile boolean running = true;
    volatile int frameCount = 0;
    volatile long curSig = 0;

    File outDir;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        outDir = getExternalFilesDir(null);
        if (outDir == null) outDir = getFilesDir();
        outDir.mkdirs();
        log("--- onCreate outDir=" + outDir.getAbsolutePath());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent == null) { log("onStartCommand intent=null"); return START_NOT_STICKY; }
        final int code = intent.getIntExtra("resultCode", 0);
        final Intent data = intent.getParcelableExtra("data");
        log("onStartCommand code=" + code + " data=" + (data != null));

        new Thread(new Runnable() {
            public void run() { bootstrap(code, data); }
        }, "boot").start();
        return START_NOT_STICKY;
    }

    void goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm.getNotificationChannel(CH) == null) {
                    nm.createNotificationChannel(
                            new NotificationChannel(CH, "VDTest", NotificationManager.IMPORTANCE_LOW));
                }
            }
            Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this);
            nb.setContentTitle("VDTest")
              .setContentText("virtual display probe")
              .setSmallIcon(android.R.drawable.ic_menu_view)
              .setOngoing(true);
            Notification n = nb.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
            log("foreground started (type=mediaProjection)");
        } catch (Throwable t) {
            log("goForeground FAIL " + t);
        }
    }

    void bootstrap(int code, Intent data) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mp = mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            log("FATAL getMediaProjection: " + t);
            return;
        }
        if (mp == null) { log("FATAL mp null"); return; }
        log("getMediaProjection OK");

        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { log("MediaProjection.onStop"); running = false; }
        }, new android.os.Handler(android.os.Looper.getMainLooper()));

        int[] sz = readSize();
        startCapture(sz[0], sz[1], DPI);

        new Thread(new Runnable() { public void run() { cmdLoop(); } }, "cmd").start();
        new Thread(new Runnable() { public void run() { statLoop(); } }, "stat").start();
    }

    /** vd.cfg 里写 1920x568，没有就用默认。 */
    int[] readSize() {
        try {
            File f = new File(outDir, "vd.cfg");
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String s = r.readLine();
                r.close();
                if (s != null && s.contains("x")) {
                    String[] p = s.trim().split("x");
                    int w = Integer.parseInt(p[0].trim());
                    int h = Integer.parseInt(p[1].trim());
                    if (w > 0 && h > 0) { W = w; H = h; }
                }
            }
        } catch (Throwable t) { log("readSize " + t); }
        return new int[]{W, H};
    }

    synchronized void startCapture(int w, int h, int dpi) {
        startCapture(w, h, dpi, false);
    }

    /**
     * mirror=false 时用 DisplayManager 直接建独立显示器（不经过 MediaProjection），
     * 这类屏有自己的内容，不会镜像主屏——app 在它上面跑就能一直渲染。
     * mirror=true 走 MediaProjection，那本质是主屏镜像，OWN_CONTENT_ONLY 会被忽略。
     */
    synchronized void startCapture(int w, int h, int dpi, boolean mirror) {
        startCapture(w, h, dpi, mirror, -1);
    }

    /** customFlags >= 0 时用调用方指定的标志位，-1 用默认组合。 */
    synchronized void startCapture(int w, int h, int dpi, boolean mirror, int customFlags) {
        stopCapture();
        W = w; H = h;
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
            int vflags = customFlags >= 0 ? customFlags
                    : (DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                       | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                       | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            if (mirror) {
                vd = mp.createVirtualDisplay("ultra", w, h, dpi, vflags, reader.getSurface(), null, null);
            } else {
                DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                vd = dm.createVirtualDisplay("ultra", w, h, dpi, reader.getSurface(), vflags);
            }
            if (vd == null) { log("FATAL vd null mirror=" + mirror); return; }
            Display d = vd.getDisplay();
            vdId = d == null ? -1 : d.getDisplayId();
            log("VD " + w + "x" + h + "@" + dpi + " displayId=" + vdId
                    + " mirror=" + mirror + " flags=0x" + Integer.toHexString(vflags));
            dumpDisplays();
            new Thread(new Runnable() { public void run() { readerLoop(); } }, "reader").start();
        } catch (Throwable t) {
            log("FATAL startCapture mirror=" + mirror + " flags=0x"
                    + Integer.toHexString(customFlags) + " " + t);
        }
    }

    synchronized void stopCapture() {
        if (vd != null) { try { vd.release(); } catch (Throwable ignored) {} vd = null; }
        if (reader != null) { try { reader.close(); } catch (Throwable ignored) {} reader = null; }
        frameCount = 0;
    }

    void dumpDisplays() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display[] all = dm.getDisplays();
            StringBuilder sb = new StringBuilder("displays=" + all.length);
            for (Display d : all) {
                sb.append(" [id=").append(d.getDisplayId())
                  .append(" ").append(d.getWidth()).append("x").append(d.getHeight())
                  .append(" state=").append(d.getState()).append("]");
            }
            log(sb.toString());
        } catch (Throwable t) { log("dumpDisplays " + t); }
    }

    void cmdLoop() {
        long mark = System.currentTimeMillis();
        while (running) {
            try {
                File f = new File(outDir, "CMD");
                if (f.exists()) {
                    BufferedReader r = new BufferedReader(new FileReader(f));
                    String line = r.readLine();
                    r.close();
                    f.delete();
                    if (line != null) handleCmd(line.trim());
                }
            } catch (Throwable t) { log("cmdLoop " + t); }
            sleep(400);
            if (System.currentTimeMillis() - mark > 60000) { mark += 60000; dumpDisplays(); }
        }
    }

    void handleCmd(String c) {
        if (c.isEmpty()) return;
        log("CMD " + c);
        if (c.equals("stop")) { stopCapture(); log("stopped"); return; }
        // mf <flags> <w>x<h>   指定标志位重新建屏（镜像模式）
        // df <flags> <w>x<h>   同上，但走 DisplayManager（独立屏）
        if (c.startsWith("mf ") || c.startsWith("df ")) {
            String[] p = c.split("\\s+");
            int flags = Integer.decode(p[1]);
            String[] wh = p[2].split("x");
            startCapture(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), DPI,
                    c.startsWith("mf"), flags);
            return;
        }
        if (c.startsWith("dm ") || c.startsWith("mp ")) {
            String[] p = c.substring(3).trim().split("x");
            startCapture(Integer.parseInt(p[0]), Integer.parseInt(p[1]), DPI, c.startsWith("mp"));
            return;
        }
        if (c.matches("\\d+x\\d+")) {
            String[] p = c.split("x");
            startCapture(Integer.parseInt(p[0]), Integer.parseInt(p[1]), DPI);
            return;
        }
        launch(c, vdId);
    }

    void launch(String spec, int did) {
        if (did < 0) { log("LAUNCH no display"); return; }
        Intent i;
        if (spec.contains("/")) {
            String[] p = spec.split("/", 2);
            i = new Intent();
            i.setComponent(new ComponentName(p[0], p[1]));
        } else {
            i = getPackageManager().getLaunchIntentForPackage(spec);
            if (i == null) { log("LAUNCH " + spec + " -> no launcher intent"); return; }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(did);
            startActivity(i, opts.toBundle());
            log("LAUNCH " + spec + " on display " + did);
        } catch (Throwable t) {
            log("LAUNCH " + spec + " FAIL " + t);
        }
    }

    void readerLoop() {
        final ImageReader r = reader;
        while (running && r == reader) {
            Image img = null;
            try { img = r.acquireLatestImage(); } catch (Throwable t) { }
            if (img == null) { sleep(4); continue; }
            try {
                frameCount++;
                if ((frameCount & 31) == 0) curSig = sig(img);
                if (pending("GO")) saveFrame(img, "snap");
            } catch (Throwable t) {
                log("readerLoop " + t);
            } finally {
                img.close();
            }
        }
    }

    void statLoop() {
        int last = 0;
        long lastSig = 0;
        while (running) {
            sleep(2000);
            int n = frameCount;
            int fps = (n - last) / 2;
            last = n;
            long sig = curSig;
            log("stat fps=" + fps + " total=" + n + " sig=" + Long.toHexString(sig)
                    + (sig != 0 && sig != lastSig ? " CHANGED" : ""));
            lastSig = sig;
        }
    }

    /** 缩略采样算个指纹，用来判断画面有没有在动。 */
    long sig(Image img) {
        try {
            ByteBuffer b = img.getPlanes()[0].getBuffer();
            int rs = img.getPlanes()[0].getRowStride();
            long h = 0;
            for (int y = 0; y < img.getHeight(); y += 16) {
                for (int x = 0; x < img.getWidth(); x += 32) {
                    int o = y * rs + x * 4;
                    if (o + 3 < b.limit()) h = h * 31 + (b.get(o) & 0xff) + (b.get(o + 1) & 0xff) * 3;
                }
            }
            return h;
        } catch (Throwable t) {
            return 0;
        }
    }

    boolean pending(String name) {
        try {
            File f = new File(outDir, name);
            if (f.exists() && f.delete()) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    void saveFrame(Image img, String name) {
        // 存到媒体库，adb 才能 pull（app 私有目录 shell 读不了）
        try {
            android.graphics.Bitmap bmp = null;
            if (Build.VERSION.SDK_INT >= 29) {
                android.hardware.HardwareBuffer hb = img.getHardwareBuffer();
                android.graphics.Bitmap wrap = android.graphics.Bitmap.wrapHardwareBuffer(hb, null);
                if (wrap != null) {
                    bmp = wrap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    wrap.recycle();
                }
            }
            if (bmp == null) { log("saveFrame bmp null"); return; }

            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/vdtest");
            android.net.Uri uri = getContentResolver()
                    .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { log("saveFrame uri null"); return; }
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os);
            os.close();

            long nonBlack = 0, sampled = 0;
            int step = 8;
            for (int y = 0; y < bmp.getHeight(); y += step) {
                for (int x = 0; x < bmp.getWidth(); x += step) {
                    int c = bmp.getPixel(x, y);
                    sampled++;
                    if (((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff) > 30) nonBlack++;
                }
            }
            log("SAVED " + name + ".png " + bmp.getWidth() + "x" + bmp.getHeight()
                    + " nonBlack=" + String.format("%.1f%%", sampled == 0 ? 0 : nonBlack * 100.0 / sampled));
            bmp.recycle();
        } catch (Throwable t) {
            log("saveFrame FAIL " + t);
        }
    }

    void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    void log(String s) {
        Log.i(TAG, s);
        try {
            FileWriter fw = new FileWriter(new File(outDir, "vdtest.log"), true);
            fw.write(s + "\n");
            fw.close();
        } catch (Throwable ignored) { }
    }
}
