package com.probe.asx;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 以 root(app_process) 建 PUBLIC 虚拟屏，并从 ImageReader 直接统计出帧情况。
 *
 * 踩过的坑：
 *   1. 售后通道的 shell 不支持 '&'，因此本程序两级启动：自己 fork 一个脱离进程组的子进程；
 *   2. DisplayManagerService 要求 packageName 与调用 uid 同属一个 app，uid=0 不匹配任何包，
 *      所以建屏前 setuid(1000)，用 system context 的 "android" 包名；
 *   3. screencap -d <virtual> 拿不到虚拟屏内容(0 字节)，必须消费建屏时传入的 surface。
 *
 * 用法: app_process /system/bin com.probe.asx.Vd [w] [h]
 */
public final class Vd {

    static final String DEX = "/data/local/tmp/vd.dex";
    static final String LOG = "/data/local/tmp/vd.log";
    static int FLAGS = 0;
    static int W = 1920, H = 568;

    static volatile long frames = 0;
    static volatile long lastFrames = 0;
    static volatile int lastNonBlackPct = -1;
    static volatile int frameHash = 0;
    static volatile int hashChanges = 0;
    static volatile boolean dumpWanted = false;
    static volatile String dumpPath = "/data/local/tmp/vdframe.png";

    public static void main(String[] args) {
        boolean child = false;
        int w = 1920, h = 568, k = 0;
        int[] nums = new int[3];
        for (String a : args) {
            if ("--child".equals(a)) { child = true; continue; }
            if (k < 3) {
                try { nums[k++] = Integer.parseInt(a); } catch (Exception ignored) { }
            }
        }
        if (k > 0) w = nums[0];
        if (k > 1) h = nums[1];
        int flags = k > 2 ? nums[2]
                : (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                   | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        FLAGS = flags;

        W = w; H = h;
        if (!child) {
            launchChild(w, h);
            System.out.println("VD| launched child, parent exiting");
            return;
        }
        runChild(w, h);
    }

    static void launchChild(int w, int h) {
        try {
            String cmd = "CLASSPATH=" + DEX
                    + " setsid app_process /system/bin com.probe.asx.Vd --child " + w + " " + h + " " + FLAGS
                    + " > " + LOG + " 2>&1 &";
            new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
        } catch (Throwable t) {
            System.out.println("VD| launchChild FAIL " + t);
        }
    }

    static void runChild(int w, int h) {
        say("child start w=" + w + " h=" + h + " pid=" + android.os.Process.myPid());
        try {
            android.os.Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) { say("NO_DM"); return; }

            try {
                android.system.Os.setgid(1000);
                android.system.Os.setuid(1000);
                say("switched uid=" + android.system.Os.getuid()
                        + " gid=" + android.system.Os.getgid());
            } catch (Throwable t) {
                say("setuid FAIL " + t);
            }

            final ImageReader ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
            say("flags=0x" + Integer.toHexString(FLAGS));
            VirtualDisplay vd = dm.createVirtualDisplay("asx", w, h, 160, ir.getSurface(), FLAGS);
            if (vd == null) { say("CREATE_NULL"); return; }
            Display d = vd.getDisplay();
            int id = d == null ? -1 : d.getDisplayId();
            say("VD_ID=" + id + " state=" + (d == null ? "?" : d.getState()));

            HandlerThread ht = new HandlerThread("vd-frames");
            ht.start();
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        int sz = buf.remaining();
                        int step = Math.max(4, sz / 4000);
                        int nonBlack = 0, total = 0;
                        for (int i = 0; i + 3 < sz; i += step) {
                            int p = buf.getInt(i);
                            if ((p & 0x00FFFFFF) != 0) nonBlack++;
                            total++;
                        }
                        lastNonBlackPct = total == 0 ? -1 : (nonBlack * 100 / total);
                        int h = 1;
                        for (int i = 0; i + 3 < sz; i += Math.max(4, sz / 400)) {
                            h = h * 31 + buf.getInt(i);
                        }
                        if (h != frameHash) { frameHash = h; hashChanges++; }
                        frames++;
                        if (dumpWanted) {
                            dumpWanted = false;
                            dumpPng(img, dumpPath);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) try { img.close(); } catch (Exception ignored) { }
                    }
                }
            }, new Handler(ht.getLooper()));

            long end = SystemClock.elapsedRealtime() + 60 * 60 * 1000L;
            int tick = 0;
            long nextReport = SystemClock.elapsedRealtime() + 1000;
            while (SystemClock.elapsedRealtime() < end) {
                Thread.sleep(200);
                // 外部触发抓帧：touch /data/local/tmp/vdcap → 立刻 dump 一帧到 vdcap.png
                File trig = new File("/data/local/tmp/vdcap");
                if (trig.exists()) {
                    trig.delete();
                    dumpPath = "/data/local/tmp/vdcap.png";
                    dumpWanted = true;
                    say("CAP triggered, total=" + frames);
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextReport) {
                    nextReport = now + 1000;
                    long f = frames;
                    say("t=" + (++tick) + "s fps=" + (f - lastFrames)
                            + " total=" + f + " nonBlack=" + lastNonBlackPct + "%"
                            + " changes=" + hashChanges);
                    lastFrames = f;
                }
            }
        } catch (Throwable t) {
            say("FAIL " + t);
            t.printStackTrace();
        }
    }

    static void dumpPng(Image img, String path) {
        try {
            int iw = img.getWidth(), ih = img.getHeight();
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer b = pl.getBuffer();
            int rs = pl.getRowStride(), ps = pl.getPixelStride();
            Bitmap bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
            if (rs == iw * 4 && ps == 4) {
                bmp.copyPixelsFromBuffer(b);
            } else {
                int[] px = new int[iw * ih];
                for (int y = 0; y < ih; y++) {
                    int base = y * rs;
                    for (int x = 0; x < iw; x++) {
                        int o = base + x * ps;
                        int r = b.get(o) & 0xFF, g = b.get(o + 1) & 0xFF,
                                bl = b.get(o + 2) & 0xFF, a = b.get(o + 3) & 0xFF;
                        px[y * iw + x] = (a << 24) | (r << 16) | (g << 8) | bl;
                    }
                }
                bmp.setPixels(px, 0, iw, 0, 0, iw, ih);
            }
            FileOutputStream fo = new FileOutputStream(path);
            boolean okPng = bmp.compress(Bitmap.CompressFormat.PNG, 90, fo);
            fo.close();
            say("DUMPED png ok=" + okPng + " " + iw + "x" + ih + " rs=" + rs + " ps=" + ps);
        } catch (Throwable t) {
            say("dumpPng FAIL " + t);
        }
    }

    static void say(String s) {
        System.out.println("VD| " + s);
        System.out.flush();
    }
}
