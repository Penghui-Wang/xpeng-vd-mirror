package com.probe.asx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * 验证「对虚拟屏的 display token 做 captureDisplay」是否可行。
 *
 * 背景：虚拟屏(OWN_CONTENT_ONLY)的内容是真实独立的，但 SF 只在有 damage 时合成，
 * 所以直接读 ImageReader 会经常 0 帧（静止画面）。如果 captureDisplay 能对虚拟屏的
 * token 生效，就能同时拿到「独立内容」+「主动触发的稳定帧率」。
 *
 * 本程序同时记录两组数字做对照：
 *   vdFps —— 虚拟屏自己往 ImageReader 交了多少帧（damage 驱动，静止时应接近 0）
 *   capFps—— 我们对同一个 token 主动 capture 的帧率（应稳定在目标值）
 * 如果 capFps 高而 vdFps 低，说明 captureDisplay 确实强制了合成。
 *
 * 拿 token：VirtualDisplay 里的 mToken 就是 SF 的 display token，
 * 直接反射取（不同版本字段名可能不同，这里按 mToken / getToken 两种都试）。
 *
 * 用法（shell 域）：
 *   CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
 *     com.probe.asx.VdCap5 --vd 2400x1200 --crop 960,345,1440,426 --out 1920x568 --sec 90 \
 *     > /data/local/tmp/vdcap5.log 2>&1 &
 * 触发存图：touch /data/local/tmp/vdcap5   -> /sdcard/vdcap5.png
 */
public final class VdCap5 {

    static final String DEX = "/data/local/tmp/vd.dex";
    static final String LOG = "/data/local/tmp/vdcap5.log";
    static final String TRIG = "/data/local/tmp/vdcap5";
    static final String PNG = "/sdcard/vdcap5.png";

    static int VW = 2400, VH = 1200;          // 虚拟屏尺寸（与主屏同尺寸，freeform 窗口能完整装下）
    static int CX = 960, CY = 345, CW = 1440, CH = 426;
    static int OW = 1920, OH = 568;
    static int SEC = 90;
    static final int VD_FLAGS = 0x9;           // PUBLIC | OWN_CONTENT_ONLY

    static volatile long vdFrames = 0, capOk = 0, capNull = 0, capErr = 0;
    static volatile int lastNonBlack = -1;
    static volatile int frameHash = 0;
    static volatile long hashChanges = 0;
    static volatile boolean dumpWanted = false;
    static volatile Bitmap lastBmp = null;
    static final Object BMP_LOCK = new Object();

    static Class<?> argsCls;
    static Method mCap;
    static Object capArgs;

    public static void main(String[] args) {
        boolean child = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--child".equals(a)) { child = true; continue; }
            if ("--vd".equals(a) && i + 1 < args.length) { int[] v = pair(args[++i]); VW = v[0]; VH = v[1]; continue; }
            if ("--crop".equals(a) && i + 1 < args.length) {
                String[] p = args[++i].split(",");
                if (p.length >= 4) {
                    CX = Integer.parseInt(p[0].trim()); CY = Integer.parseInt(p[1].trim());
                    CW = Integer.parseInt(p[2].trim()); CH = Integer.parseInt(p[3].trim());
                }
                continue;
            }
            if ("--out".equals(a) && i + 1 < args.length) { int[] v = pair(args[++i]); OW = v[0]; OH = v[1]; continue; }
            if ("--sec".equals(a) && i + 1 < args.length) { SEC = Integer.parseInt(args[++i].trim()); continue; }
        }

        if (!child) {
            launchChild(args);
            System.out.println("VD5| launched child, parent exiting");
            return;
        }
        runChild();
    }

    static int[] pair(String s) {
        String[] p = s.toLowerCase().split("x");
        return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
    }

    static void launchChild(String[] args) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String a : args) sb.append(a).append(' ');
            String cmd = "CLASSPATH=" + DEX + " setsid app_process /system/bin com.probe.asx.VdCap5 "
                    + sb + "--child > " + LOG + " 2>&1 &";
            new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
        } catch (Throwable t) {
            System.out.println("VD5| launchChild FAIL " + t);
        }
    }

    static void runChild() {
        say("start vd=" + VW + "x" + VH + " crop=" + CX + "," + CY + "," + CW + "x" + CH
                + " out=" + OW + "x" + OH + " sec=" + SEC + " flags=0x" + Integer.toHexString(VD_FLAGS)
                + " uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
        long t0 = SystemClock.elapsedRealtime();
        try {
            android.os.Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
            DisplayManager dm = (DisplayManager) sys.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) { say("NO_DM"); return; }

            ImageReader ir = ImageReader.newInstance(VW, VH, PixelFormat.RGBA_8888, 4);
            HandlerThread ht = new HandlerThread("vd-drain");
            ht.start();
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        vdFrames++;
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) try { img.close(); } catch (Exception ignored) { }
                    }
                }
            }, new Handler(ht.getLooper()));

            VirtualDisplay vd = null;
            // 策略 1：system context（package="android"，uid 需为 1000）
            vd = tryCreate(dm, ir, "S1 system-ctx");
            // 策略 2：用 com.android.shell 的 package context（uid 2000 时对上号）
            if (vd == null) {
                try {
                    Context pkg = sys.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
                    DisplayManager dm2 = (DisplayManager) pkg.getSystemService(Context.DISPLAY_SERVICE);
                    say("S2 package ctx = " + pkg.getPackageName());
                    vd = tryCreate(dm2, ir, "S2 shell-ctx");
                } catch (Throwable t) {
                    say("S2 FAIL " + t);
                }
            }
            // 策略 3：降权到 uid 1000（只有在 root 域下才可能成功）
            if (vd == null) {
                try {
                    android.system.Os.setgid(1000);
                    android.system.Os.setuid(1000);
                    say("S3 setuid -> " + android.system.Os.getuid());
                    vd = tryCreate(dm, ir, "S3 uid1000");
                } catch (Throwable t) {
                    say("S3 FAIL " + t);
                }
            }
            if (vd == null) { say("CREATE_FAIL all strategies"); return; }

            Display d = vd.getDisplay();
            int id = d == null ? -1 : d.getDisplayId();
            say("VD_ID=" + id + " state=" + (d == null ? "?" : d.getState()));

            IBinder token = extractToken(vd);
            if (token == null) { say("TOKEN_NULL"); return; }
            say("TOKEN=" + token.getClass().getName() + " " + token);

            initCapture(token);
            say("capture ready, crop rect=" + new Rect(CX, CY, CX + CW, CY + CH) + " size=" + OW + "x" + OH);

            // 先做一次探针 capture，确认 token 被 SF 接受
            boolean first = captureOnce(true);
            say("FIRST_CAPTURE ok=" + first + " nonBlack=" + lastNonBlack + "%");

            long end = SystemClock.elapsedRealtime() + SEC * 1000L;
            long nextReport = SystemClock.elapsedRealtime() + 1000;
            long sumCap = 0, nCap = 0;
            long windowStart = SystemClock.elapsedRealtime();
            long windowCap = capOk, windowVd = vdFrames;
            long capMin = Long.MAX_VALUE, capMax = 0;
            int tick = 0;
            final long gap = 33;   // 30fps
            while (SystemClock.elapsedRealtime() < end) {
                long c0 = SystemClock.elapsedRealtime();
                boolean ok = captureOnce(false);
                long c1 = SystemClock.elapsedRealtime();
                if (ok) {
                    sumCap += (c1 - c0); nCap++;
                    if (c1 - c0 < capMin) capMin = c1 - c0;
                    if (c1 - c0 > capMax) capMax = c1 - c0;
                }
                if (new File(TRIG).exists()) {
                    try { new File(TRIG).delete(); } catch (Throwable ignored) { }
                    dumpWanted = true;
                    say("TRIG dump armed");
                }
                if (dumpWanted) { dumpWanted = false; dumpPng(); }

                long now = SystemClock.elapsedRealtime();
                if (now >= nextReport) {
                    long el = now - windowStart;
                    long dc = capOk - windowCap, dv = vdFrames - windowVd;
                    say("t=" + (++tick) + "s cap/s=" + (dc * 1000 / Math.max(1, el))
                            + " vd/s=" + (dv * 1000 / Math.max(1, el))
                            + " ok=" + capOk + " null=" + capNull + " err=" + capErr
                            + " capMs=" + (nCap > 0 ? (sumCap / nCap) : -1)
                            + " nonBlack=" + lastNonBlack + "% hashChg=" + hashChanges);
                    windowStart = now; windowCap = capOk; windowVd = vdFrames;
                    nextReport = now + 1000;
                }
                long used = SystemClock.elapsedRealtime() - c0;
                if (used < gap) {
                    try { Thread.sleep(gap - used); } catch (Throwable ignored) { }
                }
            }

            long el = SystemClock.elapsedRealtime() - t0;
            say("SUMMARY total=" + capOk + " null=" + capNull + " err=" + capErr
                    + " avgCapMs=" + (nCap > 0 ? (sumCap / nCap) : -1)
                    + " min=" + (capMin == Long.MAX_VALUE ? -1 : capMin) + " max=" + capMax
                    + " vdFrames=" + vdFrames + " sec=" + (el / 1000));
        } catch (Throwable t) {
            say("FAIL " + t);
            t.printStackTrace();
        } finally {
            try { Thread.sleep(300); } catch (Throwable ignored) { }
            System.exit(0);
        }
    }

    static VirtualDisplay tryCreate(DisplayManager dm, ImageReader ir, String tag) {
        try {
            VirtualDisplay vd = dm.createVirtualDisplay("asxcap", VW, VH, 160, ir.getSurface(), VD_FLAGS);
            say(tag + " -> " + (vd == null ? "null" : "OK id=" + vd.getDisplay().getDisplayId()));
            return vd;
        } catch (Throwable t) {
            say(tag + " FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** VirtualDisplay 里的 mToken 就是 SF 的 display token */
    static IBinder extractToken(VirtualDisplay vd) {
        try {
            Field f = VirtualDisplay.class.getDeclaredField("mToken");
            f.setAccessible(true);
            Object o = f.get(vd);
            if (o instanceof IBinder) return (IBinder) o;
            say("mToken 非 IBinder: " + o);
        } catch (Throwable t) {
            say("mToken FAIL " + t);
        }
        try {
            Method m = VirtualDisplay.class.getDeclaredMethod("getToken");
            m.setAccessible(true);
            Object o = m.invoke(vd);
            if (o instanceof IBinder) return (IBinder) o;
        } catch (Throwable t) {
            say("getToken FAIL " + t);
        }
        return null;
    }

    static void initCapture(IBinder token) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Class<?> bld = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Object b = bld.getConstructor(IBinder.class).newInstance(token);
        bld.getMethod("setSourceCrop", Rect.class).invoke(b, new Rect(CX, CY, CX + CW, CY + CH));
        bld.getMethod("setSize", int.class, int.class).invoke(b, OW, OH);
        capArgs = bld.getMethod("build").invoke(b);
        argsCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        mCap = sc.getMethod("captureDisplay", argsCls);
    }

    static boolean captureOnce(boolean probe) {
        long t0 = SystemClock.elapsedRealtime();
        Bitmap hw = null, soft = null;
        Object hb = null;
        try {
            Object res = mCap.invoke(null, capArgs);
            if (res == null) { capNull++; if (probe) say("probe null"); return false; }
            hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
            hw = (Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
            if (hw == null) { capErr++; return false; }
            soft = hw.copy(Bitmap.Config.RGB_565, false);
            if (soft == null) soft = hw.copy(Bitmap.Config.ARGB_8888, false);
            if (soft == null) { capErr++; return false; }
            capOk++;
            analyze(soft);
            synchronized (BMP_LOCK) {
                if (lastBmp != null) lastBmp.recycle();
                lastBmp = soft;      // 交给 dump 用，不回收
                soft = null;
            }
            return true;
        } catch (Throwable t) {
            capErr++;
            if (probe) say("probe FAIL " + t);
            return false;
        } finally {
            if (soft != null) try { soft.recycle(); } catch (Throwable ignored) { }
            if (hw != null) try { hw.recycle(); } catch (Throwable ignored) { }
            if (hb instanceof AutoCloseable) {
                try { ((AutoCloseable) hb).close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 采样统计非黑比例 + 简易哈希，用来判断"有没有内容""画面有没有在变" */
    static void analyze(Bitmap bmp) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            byte[] buf = new byte[w * h * 2];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            bmp.copyPixelsToBuffer(bb);
            int step = 97;   // 质数步长，避免只采到同一列
            int nonBlack = 0, total = 0, hash = 1, hs = 0;
            int n = w * h;
            for (int i = 0; i < n; i += step) {
                int o = i * 2;
                int v = (buf[o] & 0xFF) | ((buf[o + 1] & 0xFF) << 8);
                if (v > 1) nonBlack++;
                total++;
                hash = hash * 31 + v;
                hs++;
            }
            lastNonBlack = total == 0 ? -1 : (nonBlack * 100 / total);
            if (hash != frameHash) { frameHash = hash; hashChanges++; }
        } catch (Throwable ignored) { }
    }

    static void dumpPng() {
        Bitmap b;
        synchronized (BMP_LOCK) { b = lastBmp; }
        if (b == null) { say("dump: no frame yet"); return; }
        try {
            FileOutputStream fo = new FileOutputStream(PNG);
            boolean ok = b.compress(Bitmap.CompressFormat.PNG, 90, fo);
            fo.close();
            say("DUMPED " + PNG + " ok=" + ok + " " + b.getWidth() + "x" + b.getHeight()
                    + " nonBlack=" + lastNonBlack + "%");
        } catch (Throwable t) {
            say("dump FAIL " + t);
        }
    }

    static void say(String s) {
        System.out.println("VD5| " + s);
        System.out.flush();
    }
}
