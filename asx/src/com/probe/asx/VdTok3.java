package com.probe.asx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.graphics.PixelFormat;

import java.io.File;

/**
 * 诊断三：自建 display token（SurfaceControl.createDisplay）+ 手动激活，
 * 能不能被 captureDisplay 接受；若再共享虚拟屏的 layer stack，能不能抓到窗口内容。
 *
 * 背景：DMS 建的虚拟屏不把 display token 暴露给客户端
 * （VirtualDisplay.mToken 是 IVirtualDisplayCallback，Builder 也只有一个
 *   setDisplayToken(IBinder)，没有 setDisplayId，DisplayCaptureArgs 只有 mDisplayToken），
 * 所以想对虚拟屏做 captureDisplay，只能自己造 token 再把它「激活」成可合成的 display。
 *
 * 用法：
 *   保持虚拟屏存活（方便外部 dumpsys / move-stack）：
 *     VdTok3 hold 2400 1200 300
 *   自建 token 抓帧：
 *     VdTok3 raw <layerStack> <sec> [w] [h]      layerStack=0 表示不设置
 */
public final class VdTok3 {

    static final int VW = 2400, VH = 1200;

    public static void main(String[] args) {
        boolean child = false;
        String mode = "raw";
        for (String a : args) if ("--child".equals(a)) child = true;
        for (String a : args) if ("hold".equals(a) || "raw".equals(a)) mode = a;
        if (!child) {
            StringBuilder sb = new StringBuilder();
            for (String a : args) sb.append(a).append(' ');
            try {
                new ProcessBuilder("/system/bin/sh", "-c",
                        "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin "
                                + "com.probe.asx.VdTok3 " + sb + "--child > /data/local/tmp/vdtok3.log 2>&1 &")
                        .redirectErrorStream(true).start();
            } catch (Throwable t) { System.out.println("VDT3| launch FAIL " + t); }
            System.out.println("VDT3| launched " + mode);
            return;
        }
        try {
            if ("hold".equals(mode)) hold(args);
            else raw(args);
        } catch (Throwable t) { say("FAIL " + t); t.printStackTrace(); }
        try { Thread.sleep(300); } catch (Throwable ignored) { }
        System.exit(0);
    }

    // ---------------- hold：建虚拟屏并保持存活 ----------------
    static void hold(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context pkg = sys.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        DisplayManager dm = (DisplayManager) pkg.getSystemService(Context.DISPLAY_SERVICE);

        int sec = 300;
        int[] nums = collect(args);
        if (nums.length >= 1) sec = nums[nums.length - 1];

        ImageReader ir = ImageReader.newInstance(VW, VH, PixelFormat.RGBA_8888, 3);
        HandlerThread ht = new HandlerThread("drain");
        ht.start();
        final long[] frames = {0};
        ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try { img = r.acquireLatestImage(); if (img != null) frames[0]++; }
                catch (Throwable ignored) { }
                finally { if (img != null) try { img.close(); } catch (Exception ignored) { } }
            }
        }, new Handler(ht.getLooper()));

        VirtualDisplay vd = dm.createVirtualDisplay("asxhold", VW, VH, 160, ir.getSurface(), 0x9);
        if (vd == null) { say("CREATE_NULL"); return; }
        say("HOLD_VD_ID=" + vd.getDisplay().getDisplayId() + " state=" + vd.getDisplay().getState());
        long end = System.currentTimeMillis() + sec * 1000L;
        long last = 0;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(2000);
            long f = frames[0];
            say("hold vdFrames=" + f + " (+" + (f - last) + "/2s)");
            last = f;
        }
    }

    // ---------------- raw：自建 token + 手动激活 + capture ----------------
    static void raw(String[] args) throws Exception {
        int[] nums = collect(args);
        int stack = nums.length > 0 ? nums[0] : 0;
        int sec = nums.length > 1 ? nums[1] : 20;
        int w = nums.length > 2 ? nums[2] : VW;
        int h = nums.length > 3 ? nums[3] : VH;

        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        try { at.getMethod("getSystemContext").invoke(thread); } catch (Throwable ignored) { }

        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder tok = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class)
                .invoke(null, "asxraw3", false);
        say("createDisplay -> " + (tok == null ? "null" : tok.getClass().getName()));
        if (tok == null) return;

        ImageReader ir = null;
        boolean nosurf = false;
        for (String a : args) if ("--nosurf".equals(a)) nosurf = true;
        // 给这个 display 一个输出面（不消费会背压，所以开线程排空）
        try {
            if (!nosurf) {
                ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            }
            HandlerThread ht = new HandlerThread("drain3");
            ht.start();
            final long[] frames = {0};
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try { img = r.acquireLatestImage(); if (img != null) frames[0]++; }
                    catch (Throwable ignored) { }
                    finally { if (img != null) try { img.close(); } catch (Exception ignored) { } }
                }
            }, new Handler(ht.getLooper()));
        } catch (Throwable t) {
            say("ImageReader 建失败 " + t);
        }

        // 用显式 Transaction 激活这个 display：
        // 静态 setDisplaySurface() 依赖全局 transaction，在 app_process 里是 null（会 NPE）
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getConstructor().newInstance();
            if (ir != null) {
                txCls.getMethod("setDisplaySurface", IBinder.class, android.view.Surface.class)
                        .invoke(tx, tok, ir.getSurface());
                say("tx.setDisplaySurface ok");
            }
            txCls.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(tx, tok, 0, new Rect(0, 0, w, h), new Rect(0, 0, w, h));
            say("tx.setDisplayProjection ok");
            if (stack > 0) {
                txCls.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(tx, tok, stack);
                say("tx.setDisplayLayerStack(" + stack + ") ok");
            }
            txCls.getMethod("apply").invoke(tx);
            say("tx.apply ok");
        } catch (Throwable t) {
            say("激活失败 " + t + " cause=" + t.getCause());
            if (t.getCause() != null) t.getCause().printStackTrace();
        }

        // 立刻试一次 capture
        Class<?> bldCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Class<?> argCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        Object b = bldCls.getConstructor(IBinder.class).newInstance(tok);
        bldCls.getMethod("setSourceCrop", Rect.class).invoke(b, new Rect(0, 0, w, h));
        bldCls.getMethod("setSize", int.class, int.class).invoke(b, 1920, 568);
        Object capArgs = bldCls.getMethod("build").invoke(b);

        long ok = 0, nul = 0;
        long end = System.currentTimeMillis() + sec * 1000L;
        long nextReport = System.currentTimeMillis() + 1000;
        long t0 = System.currentTimeMillis();
        long wOk = 0, sumMs = 0, nMs = 0, minMs = 999, maxMs = 0;
        java.lang.reflect.Method mCapture = sc.getMethod("captureDisplay", argCls);
        while (System.currentTimeMillis() < end) {
            try {
                long c0 = System.nanoTime();
                Object res = mCapture.invoke(null, capArgs);
                long cms = (System.nanoTime() - c0) / 1000000L;
                sumMs += cms; nMs++;
                if (cms < minMs) minMs = cms;
                if (cms > maxMs) maxMs = cms;
                if (res == null) nul++;
                else {
                    ok++; wOk++;
                    Bitmap bmp = (Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
                    if (bmp != null && (ok == 1 || ok == 90 || ok == 600)) {
                        int nb = nonBlack(bmp);
                        say("frame#" + ok + " " + bmp.getWidth() + "x" + bmp.getHeight() + " nonBlack=" + nb + "%");
                        try {
                            String p = "/sdcard/vdraw_" + ok + ".png";
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(p);
                            bmp.compress(Bitmap.CompressFormat.PNG, 90, fo);
                            fo.close();
                            say("DUMPED " + p);
                        } catch (Throwable t) { say("dump FAIL " + t); }
                    }
                }
            } catch (Throwable t) {
                say("capture EX " + t);
                break;
            }
            long now = System.currentTimeMillis();
            if (now >= nextReport) {
                say("t=" + ((now - t0) / 1000) + "s cap/s=" + wOk + " ok=" + ok + " null=" + nul
                        + " capMs=" + (nMs > 0 ? (sumMs / nMs) : -1) + "(min " + minMs + "/max " + maxMs + ")");
                wOk = 0;
                nextReport = now + 1000;
            }
            try { Thread.sleep(33); } catch (Throwable ignored) { }
        }
        say("SUMMARY ok=" + ok + " null=" + nul + " avgCapMs=" + (nMs > 0 ? (sumMs / nMs) : -1)
                + " min=" + minMs + " max=" + maxMs);
        try { sc.getMethod("destroyDisplay", IBinder.class).invoke(null, tok); } catch (Throwable ignored) { }
    }

    static int nonBlack(Bitmap bmp) {
        try {
            Bitmap s = bmp.copy(Bitmap.Config.RGB_565, false);
            byte[] buf = new byte[s.getWidth() * s.getHeight() * 2];
            s.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buf));
            int c = 0, t = 0;
            for (int i = 0; i < buf.length; i += 2 * 97) {
                int v = (buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8);
                if (v > 1) c++;
                t++;
            }
            return t == 0 ? -1 : c * 100 / t;
        } catch (Throwable t) { return -1; }
    }

    /** 取所有纯数字参数 */
    static int[] collect(String[] args) {
        java.util.ArrayList<Integer> l = new java.util.ArrayList<Integer>();
        for (String a : args) {
            try { l.add(Integer.parseInt(a)); } catch (Exception ignored) { }
        }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    static void say(String s) {
        System.out.println("VDT3| " + s);
        System.out.flush();
    }
}
